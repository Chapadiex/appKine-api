#!/bin/sh
# Inicializacion del contenedor de PostgreSQL de desarrollo. Corre una sola vez, con el volumen vacio.
#
# Crea el rol de la aplicacion sin superusuario y su base, y habilita en ella las extensiones que
# el esquema necesita. CREATE EXTENSION vector exige superusuario: por eso va aca y no en Flyway,
# que corre con el rol de la aplicacion (V1 la repite con IF NOT EXISTS, que no exige nada si ya
# existe).
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE ${AKINE_DB_USER} LOGIN PASSWORD '${AKINE_DB_PASSWORD}';
CREATE DATABASE ${AKINE_DB_NAME} OWNER ${AKINE_DB_USER} ENCODING 'UTF8' TEMPLATE template0;
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$AKINE_DB_NAME" <<SQL
CREATE EXTENSION IF NOT EXISTS vector;
ALTER DATABASE ${AKINE_DB_NAME} SET timezone TO 'UTC';
SQL
