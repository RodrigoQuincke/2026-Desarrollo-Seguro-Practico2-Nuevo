# Práctico 4 - Pruebas de seguridad

Requisitos: Python 3, Docker y Docker Compose.

Todos los comandos se ejecutan desde la raíz del repositorio.

## Ejercicio 1 - SQL Injection

```bash
cd Ejercicio1
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-test.txt
python -m pytest -v
deactivate
cd ..
```

## Ejercicio 2 - Cross Site Scripting

```bash
cd Ejercicio2
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-test.txt
python -m pytest -v
deactivate
cd ..
```

## Ejercicio 3 - File Upload (Pytest)

Requiere la aplicación levantada en Docker (puerto 8080).

```bash
cd Ejercicio3
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-test.txt
docker compose up -d --build
python -m pytest -v
docker compose down
deactivate
cd ..
```

## Ejercicio 4 - Server Side Template Injection (Pytest)

Requiere la aplicación levantada en Docker (puerto 8080).

```bash
cd Ejercicio4
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-test.txt
docker compose up -d --build
python -m pytest -v
docker compose down
deactivate
cd ..
```

## Ejercicio 5 - Almacenamiento inseguro


```bash
cd Ejercicio5
mkdir -p ~/.m2
docker run --rm -u "$(id -u):$(id -g)" \
  -v "$PWD":/app -w /app \
  -v "$HOME/.m2":/var/maven/.m2 -e MAVEN_CONFIG=/var/maven/.m2 \
  maven:3.9.11-eclipse-temurin-17-noble \
  mvn -B -Duser.home=/var/maven test
cd ..
```

