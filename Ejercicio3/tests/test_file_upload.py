"""
Pruebas de integración - Ejercicio 3: carga insegura de archivos (File Upload).

Vulnerabilidad (rama main):
    POST /upload/{id} guarda el archivo con el nombre que manda el usuario
    (archivo.getOriginalFilename()) y sin revisar su contenido. Después,
    GET /uploads/{nombre} lo sirve con el tipo que corresponde a su extensión.
    Así se puede subir un .html con JavaScript y ejecutarlo desde el dominio de la app.

Mitigación (rama practico-2):
    - El contenido se valida con ImageIO: solo se aceptan PNG o JPEG reales (y hasta 5 MB).
    - Si el archivo no es válido, se rechaza y se redirige de nuevo al formulario /upload/{id}.
    - La imagen se re-codifica y se guarda como <UUID>.png: el nombre lo decide el servidor.
    - GET /uploads/ solo sirve nombres con formato <UUID>.png y agrega X-Content-Type-Options: nosniff.

Resultado esperado:
    main        -> FALLAN todos los tests.
    practico-2  -> PASAN todos.

Son pruebas de integración: hablan por HTTP con la aplicación real corriendo en Docker.
Antes de ejecutarlas hay que levantar el contenedor del Ejercicio 3:
    docker compose up -d --build

Ejecutar (desde la carpeta Ejercicio3):
    python -m pytest -v

La URL base se puede cambiar con la variable de entorno BASE_URL (por defecto http://localhost:8080).
"""
import os
import re
import struct
import time
import uuid
import zlib
from urllib.parse import urlparse

import pytest
import requests


BASE_URL = os.getenv("BASE_URL", "http://localhost:8080")
PELICULA_ID = 1  # "Inception" en data.sql

# Contenido del archivo malicioso usado en la prueba de concepto del Práctico 2.
CONTENIDO_HTML = b'<script>alert("File Upload Exploit Exitoso")</script>'


# --------------------------------------------------------------------------------------
# Preparación y funciones auxiliares
# --------------------------------------------------------------------------------------

@pytest.fixture(scope="session", autouse=True)
def esperar_aplicacion():
    """Espera (hasta 2 minutos) a que la aplicación responda antes de correr los tests.

    Spring Boot tarda unos segundos en arrancar después de "docker compose up".
    scope="session": se ejecuta una sola vez para todos los tests.
    autouse=True: se aplica sola, sin que los tests tengan que pedirla.
    """
    limite = time.time() + 120
    while time.time() < limite:
        try:
            if requests.get(f"{BASE_URL}/", timeout=5).status_code == 200:
                return
        except requests.ConnectionError:
            pass
        time.sleep(3)
    pytest.fail(f"La aplicación no responde en {BASE_URL}. ¿Levantaste el contenedor con 'docker compose up -d --build'?")


def nombre_unico(extension):
    """Genera un nombre de archivo distinto en cada ejecución.

    En main, Files.copy() falla si ya existe un archivo con ese nombre, así que reutilizar
    nombres haría fallar el test por un motivo que no es la vulnerabilidad.
    """
    return f"prueba_{uuid.uuid4().hex}.{extension}"


def crear_png(ancho=2, alto=2):
    """Construye en memoria una imagen PNG válida y mínima (píxeles rojos).

    Así el test no depende de tener un archivo de imagen guardado en el repositorio.
    Un PNG es: firma de 8 bytes + bloques IHDR (tamaño), IDAT (píxeles comprimidos) e IEND (fin).
    """
    def bloque(tipo, datos):
        crc = zlib.crc32(tipo + datos) & 0xFFFFFFFF
        return struct.pack(">I", len(datos)) + tipo + datos + struct.pack(">I", crc)

    filas = b"".join(b"\x00" + b"\xff\x00\x00" * ancho for _ in range(alto))
    return (
        b"\x89PNG\r\n\x1a\n"
        + bloque(b"IHDR", struct.pack(">IIBBBBB", ancho, alto, 8, 2, 0, 0, 0))
        + bloque(b"IDAT", zlib.compress(filas))
        + bloque(b"IEND", b"")
    )


def subir_afiche(nombre, contenido, tipo_mime):
    """Envía el formulario "Subir afiche" (POST /upload/1), igual que lo haría el navegador.

    allow_redirects=False: queremos ver a dónde redirige el servidor, sin seguir la redirección.
    """
    return requests.post(
        f"{BASE_URL}/upload/{PELICULA_ID}",
        files={"afiche": (nombre, contenido, tipo_mime)},
        allow_redirects=False,
        timeout=15,
    )


def fue_rechazado(respuesta):
    """En practico-2, un archivo rechazado redirige de vuelta al formulario /upload/{id}.

    Un archivo aceptado redirige a la página principal "/".
    """
    destino = urlparse(respuesta.headers.get("Location", "")).path.rstrip("/")
    return respuesta.status_code in (301, 302, 303) and destino == f"/upload/{PELICULA_ID}"


# --------------------------------------------------------------------------------------
# Tests: deben FALLAR en main y PASAR en practico-2.
# --------------------------------------------------------------------------------------

def test_html_renombrado_a_jpg_es_rechazado_y_no_queda_accesible():
    """Reproduce la verificación del Práctico 2: exploit.html renombrado a .jpg.

    main:       lo acepta (no mira el contenido) y queda disponible en /uploads/<nombre>.jpg.
    practico-2: ImageIO no lo reconoce como imagen -> "El archivo no es una imagen valida."
    """
    nombre = nombre_unico("jpg")

    respuesta = subir_afiche(nombre, CONTENIDO_HTML, "image/jpeg")
    assert fue_rechazado(respuesta), "El servidor aceptó un archivo HTML disfrazado de JPG"

    descarga = requests.get(f"{BASE_URL}/uploads/{nombre}", timeout=15)
    assert descarga.status_code == 404, "El archivo malicioso quedó accesible en /uploads/"


def test_archivo_html_no_se_sirve_como_pagina_web():
    """Reproduce el ataque original del Práctico 2: subir exploit.html y abrirlo.

    main:       /uploads/<nombre>.html responde 200 con Content-Type text/html
                -> el navegador ejecuta el JavaScript.
    practico-2: el archivo se rechaza y además /uploads/ solo sirve <UUID>.png -> 404.
    """
    nombre = nombre_unico("html")

    subir_afiche(nombre, CONTENIDO_HTML, "text/html")
    descarga = requests.get(f"{BASE_URL}/uploads/{nombre}", timeout=15)

    tipo = descarga.headers.get("Content-Type", "")
    assert not (descarga.status_code == 200 and "text/html" in tipo), \
        "El servidor sirve el archivo subido como una página HTML ejecutable"


def test_el_nombre_del_archivo_lo_decide_el_servidor():
    """El nombre que manda el usuario no debe usarse para guardar el archivo.

    Se sube una imagen PNG real con un nombre elegido por el "usuario".
    main:       se guarda con ese nombre -> /uploads/<ese nombre> responde 200.
    practico-2: se guarda como <UUID>.png -> /uploads/<ese nombre> responde 404.
    """
    nombre = nombre_unico("png")

    subir_afiche(nombre, crear_png(), "image/png")
    descarga = requests.get(f"{BASE_URL}/uploads/{nombre}", timeout=15)

    assert descarga.status_code == 404, "El archivo se guardó con el nombre enviado por el usuario"


def test_imagen_guardada_se_sirve_como_png_con_nosniff():
    """Las imágenes guardadas deben tener nombre <UUID>.png y servirse con nosniff.

    Se sube una imagen válida y se busca su URL en la página principal (como haría el navegador).
    X-Content-Type-Options: nosniff impide que el navegador "adivine" otro tipo de contenido.
    main:       el nombre es el del usuario y la respuesta no trae el encabezado nosniff.
    practico-2: nombre <UUID>.png y encabezado nosniff presente.
    """
    subir_afiche(nombre_unico("png"), crear_png(), "image/png")

    pagina = requests.get(f"{BASE_URL}/", params={"buscar": "Inception"}, timeout=15).text
    encontrado = re.search(r'src="/uploads/([^"]+)"', pagina)
    assert encontrado, "No se encontró el afiche de la película en la página principal"
    nombre_guardado = encontrado.group(1)

    assert re.fullmatch(r"[0-9a-fA-F-]{36}\.png", nombre_guardado), \
        f"El archivo se guardó como '{nombre_guardado}' en vez de <UUID>.png"

    imagen = requests.get(f"{BASE_URL}/uploads/{nombre_guardado}", timeout=15)
    assert imagen.status_code == 200
    assert imagen.headers.get("Content-Type", "").startswith("image/png")
    assert imagen.headers.get("X-Content-Type-Options") == "nosniff"
