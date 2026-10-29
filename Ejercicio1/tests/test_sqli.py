"""
Pruebas de regresión - Ejercicio 1: SQL Injection en el buscador de películas.

Vulnerabilidad (rama main):
    buscar_funciones() arma la consulta SQL pegando el texto del usuario con f-strings:
        WHERE peliculas.nombre LIKE '%{query}%'
        ORDER BY ... {sort_dir}
    Así, el texto que escribe el usuario puede cambiar la estructura de la consulta.

Mitigación (rama practico-2):
    - El término de búsqueda viaja como parámetro:  WHERE peliculas.nombre LIKE ?
    - Columna y sentido de ORDER BY se eligen de listas permitidas (SORT_COLUMNS / SORT_DIRECTIONS).

Resultado esperado:
    main        -> FALLAN todos los tests (la inyección funciona).
    practico-2  -> PASAN todos (la inyección fue mitigada).

Son pruebas unitarias: no necesitan Docker ni la app levantada. Cada test usa su propia
base SQLite temporal, así que no tocan cine.db.

Ejecutar (desde la carpeta Ejercicio1):
    python -m pytest -v
"""
import sqlite3

import pytest

import app as cine_app  # Ejercicio1/app.py. Lo llamamos cine_app para no confundirlo con el objeto Flask "app".


# Payload exacto usado en la prueba de concepto del Práctico 2.
PAYLOAD_UNION = "' UNION SELECT sqlite_version(), '1970-01-01', 0 --"


# --------------------------------------------------------------------------------------
# Fixtures: preparación que Pytest ejecuta antes de cada test que las pida por nombre.
# --------------------------------------------------------------------------------------

@pytest.fixture
def db_path(tmp_path):
    """Crea una base SQLite temporal con el mismo esquema que init_db.py y pocos datos conocidos.

    tmp_path es una fixture que trae Pytest: una carpeta temporal nueva para cada test,
    que se borra sola. Tener datos conocidos permite saber exactamente cuántas filas esperar.
    """
    path = tmp_path / "cine_test.db"
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE peliculas (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            nombre TEXT NOT NULL,
            genero TEXT NOT NULL,
            director TEXT NOT NULL
        );
        CREATE TABLE funciones (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            pelicula_id INTEGER NOT NULL,
            fecha_hora TEXT NOT NULL,
            asientos_totales INTEGER NOT NULL DEFAULT 100,
            asientos_ocupados INTEGER NOT NULL DEFAULT 0
        );
        INSERT INTO peliculas (nombre, genero, director) VALUES
            ('Dune: Parte Dos', 'Ciencia Ficción', 'Denis Villeneuve'),
            ('Oppenheimer', 'Drama/Biografía', 'Christopher Nolan');
        INSERT INTO funciones (pelicula_id, fecha_hora, asientos_totales, asientos_ocupados) VALUES
            (1, '2026-07-13 14:30:00', 120, 85),
            (2, '2026-07-13 16:00:00', 100, 92);
    """)
    conn.commit()
    conn.close()
    return str(path)


@pytest.fixture
def flask_app(db_path):
    """Devuelve la app Flask del ejercicio apuntando a la base temporal en vez de cine.db."""
    cine_app.app.config["DATABASE"] = db_path
    cine_app.app.config["TESTING"] = True
    return cine_app.app


# --------------------------------------------------------------------------------------
# Tests: deben FALLAR en main y PASAR en practico-2.
# --------------------------------------------------------------------------------------

def test_union_select_no_inyecta_filas(flask_app):
    """El payload UNION SELECT del Práctico 2 no debe agregar filas inventadas.

    main:       la consulta queda "... LIKE '%' UNION SELECT sqlite_version(), '1970-01-01', 0 --%'"
                y aparece una fila falsa con la versión de SQLite y fecha 1970-01-01.
    practico-2: se busca literalmente una película llamada así -> 0 resultados.
    """
    # get_db() usa flask.g, que solo existe dentro de un "contexto de aplicación".
    with flask_app.app_context():
        filas = cine_app.buscar_funciones(PAYLOAD_UNION)

    fechas = [fila["fecha_hora"] for fila in filas]
    assert "1970-01-01" not in fechas, f"La inyección agregó una fila falsa: {[dict(f) for f in filas]}"
    assert filas == []


def test_or_1_igual_1_no_devuelve_todas_las_funciones(flask_app):
    """El clásico ' OR 1=1 -- no debe devolver toda la tabla.

    main:       WHERE ... LIKE '%' OR 1=1 --%'  -> la condición siempre es verdadera -> 2 filas.
    practico-2: busca el texto literal "' OR 1=1 --" -> 0 filas.
    """
    with flask_app.app_context():
        filas = cine_app.buscar_funciones("' OR 1=1 --")

    assert filas == [], f"La inyección devolvió {len(filas)} filas"


def test_sentido_de_orden_no_permite_inyectar_sql(flask_app):
    """El parámetro 'sentido' (ASC/DESC) se pega en ORDER BY, que no se puede parametrizar.

    Se envía "ASC LIMIT 0": SQL válido que, si se inyecta, hace que la consulta devuelva 0 filas.
    main:       ORDER BY peliculas.nombre ASC LIMIT 0  -> 0 filas.
    practico-2: el valor no está en SORT_DIRECTIONS, se usa ASC -> 1 fila (Dune).
    """
    with flask_app.app_context():
        filas = cine_app.buscar_funciones("Dune", "nombre", "ASC LIMIT 0")

    assert len(filas) == 1, "El valor de 'sentido' llegó al SQL y modificó la consulta"


def test_payload_por_http_no_muestra_fila_inyectada(flask_app):
    """Mismo ataque que en el Práctico 2, pero entrando por la ruta "/" como lo haría un usuario.

    test_client() simula pedidos HTTP dentro del proceso, sin levantar servidor ni Docker.
    main:       la página muestra la fila falsa con fecha 1970/01/01.
    practico-2: la página dice que no hay resultados.
    """
    cliente = flask_app.test_client()
    respuesta = cliente.get("/", query_string={"buscar": PAYLOAD_UNION})
    html = respuesta.get_data(as_text=True)

    assert respuesta.status_code == 200
    assert "1970/01/01" not in html
