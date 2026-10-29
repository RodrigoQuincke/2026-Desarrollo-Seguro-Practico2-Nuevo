"""
Pruebas de regresión - Ejercicio 2: Cross Site Scripting (XSS) almacenado.

Vulnerabilidad (rama main):
    La descripción de una película se guarda tal cual la escribe el usuario y luego
    templates/edit.html la muestra con el filtro "safe":
        {{ pelicula['descripcion'] | safe }}
    "safe" le dice a Jinja que NO escape el contenido, así que el HTML/JavaScript
    guardado llega crudo al navegador y se ejecuta.

Mitigación (rama practico-2):
    Se quitó "| safe". Jinja vuelve a escapar automáticamente la salida:
        <script>  ->  &lt;script&gt;
    El navegador lo muestra como texto y no lo ejecuta.

Resultado esperado:
    main        -> FALLAN todos los tests (el payload aparece crudo en el HTML).
    practico-2  -> PASAN todos (el payload aparece escapado).

Son pruebas unitarias: usan el cliente de pruebas de Flask (no levanta servidor ni Docker)
y una base SQLite temporal (no tocan cine2.db).

Ejecutar (desde la carpeta Ejercicio2):
    python -m pytest -v
"""
import sqlite3

import pytest

import app as cine_app  # Ejercicio2/app.py


# Payloads XSS. El primero es el usado en la prueba de concepto del Práctico 2.
PAYLOADS = [
    "<script>alert('Vulnerabilidad XSS explotada')</script>",
    "<img src=x onerror=alert('XSS')>",
    "<svg onload=alert('XSS')>",
]


@pytest.fixture
def client(tmp_path):
    """Crea una base SQLite temporal con una película y devuelve el cliente de pruebas de Flask.

    La tabla tiene el mismo esquema que la tabla peliculas de init_db.py.
    """
    path = tmp_path / "cine2_test.db"
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE peliculas (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            nombre TEXT NOT NULL,
            genero TEXT NOT NULL,
            director TEXT NOT NULL,
            descripcion TEXT
        );
        INSERT INTO peliculas (nombre, genero, director, descripcion)
            VALUES ('Dune: Parte Dos', 'Ciencia Ficción', 'Denis Villeneuve', 'Descripción original');
    """)
    conn.commit()
    conn.close()

    cine_app.app.config["DATABASE"] = str(path)
    cine_app.app.config["TESTING"] = True
    return cine_app.app.test_client()


def guardar_descripcion(client, descripcion):
    """Simula al usuario enviando el formulario de edición de la película 1 (POST /edit/1)."""
    return client.post("/edit/1", data={
        "nombre": "Dune: Parte Dos",
        "genero": "Ciencia Ficción",
        "director": "Denis Villeneuve",
        "descripcion": descripcion,
    })


@pytest.mark.parametrize("payload", PAYLOADS)
def test_xss_almacenado_no_se_renderiza_crudo(client, payload):
    """Un payload guardado en la descripción no debe aparecer como HTML ejecutable en /edit/1.

    Es el mismo flujo del ataque del Práctico 2: se guarda (POST) y se vuelve a ver la película (GET).
    main:       edit.html usa "| safe" -> el payload aparece tal cual en el HTML -> FALLA.
    practico-2: Jinja escapa "<" y ">" -> el payload crudo no aparece -> PASA.
    """
    guardar_descripcion(client, payload)

    respuesta = client.get("/edit/1")
    html = respuesta.get_data(as_text=True)

    assert respuesta.status_code == 200
    assert payload not in html, "El payload XSS se insertó sin escapar en la página"


def test_etiqueta_script_no_llega_al_navegador(client):
    """Con el payload del Práctico 2, la página no debe contener ninguna etiqueta <script>.

    La página edit.html no tiene scripts propios, así que cualquier <script> en la
    respuesta solo puede venir del dato guardado por el usuario.
    """
    guardar_descripcion(client, PAYLOADS[0])

    html = client.get("/edit/1").get_data(as_text=True)

    assert "<script>" not in html, "Hay una etiqueta <script> inyectada en la página"
