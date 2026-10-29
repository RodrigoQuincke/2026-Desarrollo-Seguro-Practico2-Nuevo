"""
Pruebas de integración - Ejercicio 4: Server Side Template Injection (inyección de expresiones SpEL).

Vulnerabilidad (rama main):
    El buscador pasa lo que escribe el usuario a SpelEvaluator, que lo evalúa como una
    expresión de Spring (SpEL) con un StandardEvaluationContext:
        parser.parseExpression(buscar).getValue(standardContext)
    Es decir, el servidor EJECUTA lo que escribe el usuario. Con "7*7" la página mostraba
    "Resultados buscando por: 49". El mismo mecanismo permite acceder a clases de Java.

Mitigación (rama practico-2):
    Se eliminó SpelEvaluator. La búsqueda usa findByNombreFuncionContainingIgnoreCase(query):
    lo que escribe el usuario es solo texto a buscar y nunca se evalúa.

Resultado esperado:
    main        -> FALLAN todos los tests (las expresiones se evalúan).
    practico-2  -> PASAN todos (las expresiones se tratan como texto).

Los payloads son inofensivos (cuentas y operaciones de texto): alcanzan para demostrar
que el servidor evalúa la entrada, sin ejecutar nada peligroso.

Son pruebas de integración: hablan por HTTP con la aplicación real corriendo en Docker.
Antes de ejecutarlas hay que levantar el contenedor del Ejercicio 4:
    docker compose up -d --build

Ejecutar (desde la carpeta Ejercicio4):
    python -m pytest -v

La URL base se puede cambiar con la variable de entorno BASE_URL (por defecto http://localhost:8080).
"""
import os
import time

import pytest
import requests


BASE_URL = os.getenv("BASE_URL", "http://localhost:8080")


@pytest.fixture(scope="session", autouse=True)
def esperar_aplicacion():
    """Espera (hasta 2 minutos) a que la aplicación responda antes de correr los tests."""
    limite = time.time() + 120
    while time.time() < limite:
        try:
            if requests.get(f"{BASE_URL}/", timeout=5).status_code == 200:
                return
        except requests.ConnectionError:
            pass
        time.sleep(3)
    pytest.fail(f"La aplicación no responde en {BASE_URL}. ¿Levantaste el contenedor con 'docker compose up -d --build'?")


def buscar(texto):
    """Hace una búsqueda en el buscador de funciones (GET /?buscar=...) y devuelve el HTML."""
    return requests.get(f"{BASE_URL}/", params={"buscar": texto}, timeout=15).text


# --------------------------------------------------------------------------------------
# Tests: deben FALLAR en main y PASAR en practico-2.
# --------------------------------------------------------------------------------------

def test_expresion_matematica_no_se_evalua():
    """Versión del "7*7" del Práctico 2, con un resultado que no puede aparecer por casualidad.

    1337*3 = 4011, un número que no existe en los datos de la aplicación.
    main:       SpEL calcula 4011 y la página muestra "Resultados buscando por: 4011".
    practico-2: busca literalmente el texto "1337*3" -> el 4011 no aparece.
    """
    html = buscar("1337*3")

    assert "4011" not in html, "El servidor evaluó la expresión 1337*3 y mostró el resultado"


def test_expresion_con_clases_java_no_se_evalua():
    """El contexto de main (StandardEvaluationContext) permite usar clases de Java con T(...).

    Se usa java.lang.Math, que es inofensiva, para demostrar el acceso a clases.
    Con este mismo mecanismo un atacante podría llegar a clases peligrosas (por ejemplo Runtime).
    main:       T(java.lang.Math).max(1337, 7) * 3 se ejecuta -> 1337 * 3 = 4011 aparece en la página.
    practico-2: es solo texto de búsqueda -> no aparece 4011.

    El resultado (4011) no está escrito dentro de la expresión a propósito: la página repite
    en el cuadro de búsqueda lo que escribió el usuario, y eso no debe confundirse con el resultado.
    """
    html = buscar("T(java.lang.Math).max(1337, 7) * 3")

    assert "4011" not in html, "El servidor ejecutó un método de una clase Java enviado por el usuario"


def test_expresion_no_modifica_la_busqueda():
    """Una expresión de texto no debe convertirse en otra búsqueda distinta.

    main:       'Incep'.concat('tion') se evalúa como "Inception" y busca esa película
                -> "Resultados buscando por: Inception".
    practico-2: se busca el texto literal, que no coincide con ninguna función
                -> "No se encontraron coincidencias."
    """
    html = buscar("'Incep'.concat('tion')")

    assert "Resultados buscando por: Inception" not in html, \
        "La expresión fue evaluada y cambió el término de búsqueda"
