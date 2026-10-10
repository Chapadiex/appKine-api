#!/usr/bin/env bash
# Funciones comunes de los scripts de operaciones (G-13). Se incluye con `source`, no se ejecuta.
#
# Toda la configuracion llega por variable de entorno, igual que en la imagen del backend: los
# scripts no tienen ni un host ni una credencial escritos adentro. Ver docs/operaciones/.
#
# Cliente de MySQL: si `mysqldump` y `mysql` estan en el PATH se usan esos; si no, se corren desde
# la imagen oficial `mysql:8.4` con `docker run`, pasando los datos por stdin/stdout. Asi el host
# de operaciones solo necesita Docker, y la version del cliente es la misma que la del servidor.

set -euo pipefail

# Git Bash en Windows reescribe los argumentos que empiezan con "/" como rutas de Windows
# (`tar -C /data` termina en `C:/Program Files/Git/data`). En Linux esta variable no hace nada.
export MSYS_NO_PATHCONV=1

AKINE_OPS_IMAGE="${AKINE_OPS_IMAGE:-mysql:8.4}"

log()  { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
fail() { printf '[%s] ERROR: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; exit 1; }

require_var() {
	local nombre
	for nombre in "$@"; do
		[[ -n "${!nombre:-}" ]] || fail "falta la variable de entorno $nombre"
	done
}

# ---- Base de datos ----------------------------------------------------------------------------
#
# AKINE_DB_HOST, AKINE_DB_PORT (3306), AKINE_DB_NAME, AKINE_DB_USER, AKINE_DB_PASSWORD.
# AKINE_DB_DOCKER_NETWORK: red de Docker a la que se conecta el cliente cuando corre en
#   contenedor (p. ej. la red donde vive el contenedor de MySQL, y entonces AKINE_DB_HOST es el
#   nombre del contenedor). Sin ella, el cliente en contenedor alcanza la maquina como
#   host.docker.internal.
# AKINE_MYSQL_CLIENT: local | docker. Por defecto, local si hay mysqldump en el PATH.
#
# La contrasena viaja en MYSQL_PWD y nunca como argumento: un argumento se ve en `ps`.

db_init() {
	require_var AKINE_DB_HOST AKINE_DB_NAME AKINE_DB_USER AKINE_DB_PASSWORD
	AKINE_DB_PORT="${AKINE_DB_PORT:-3306}"
	if [[ -z "${AKINE_MYSQL_CLIENT:-}" ]]; then
		if command -v mysqldump >/dev/null 2>&1 && command -v mysql >/dev/null 2>&1; then
			AKINE_MYSQL_CLIENT=local
		else
			AKINE_MYSQL_CLIENT=docker
		fi
	fi
	export MYSQL_PWD="$AKINE_DB_PASSWORD"
}

_db_run() {
	# _db_run <programa> <args...> — stdin y stdout pasan derecho.
	local programa="$1"; shift
	if [[ "$AKINE_MYSQL_CLIENT" == local ]]; then
		"$programa" --host="$AKINE_DB_HOST" --port="$AKINE_DB_PORT" --user="$AKINE_DB_USER" "$@"
	else
		local red=()
		local host="$AKINE_DB_HOST"
		if [[ -n "${AKINE_DB_DOCKER_NETWORK:-}" ]]; then
			red=(--network "$AKINE_DB_DOCKER_NETWORK")
		elif [[ "$host" == localhost || "$host" == 127.0.0.1 ]]; then
			host=host.docker.internal
			red=(--add-host=host.docker.internal:host-gateway)
		fi
		docker run --rm -i "${red[@]}" -e MYSQL_PWD --entrypoint "$programa" "$AKINE_OPS_IMAGE" \
			--host="$host" --port="$AKINE_DB_PORT" --user="$AKINE_DB_USER" "$@"
	fi
}

# mysql_query "<sql>" — una consulta, salida tabulada sin encabezado.
mysql_query() {
	_db_run mysql --batch --skip-column-names --default-character-set=utf8mb4 \
		--database="$AKINE_DB_NAME" --execute="$1"
}

# mysql_exec — ejecuta el SQL que llega por stdin contra AKINE_DB_NAME.
mysql_exec() {
	_db_run mysql --default-character-set=utf8mb4 --database="$AKINE_DB_NAME"
}

mysql_dump() {
	_db_run mysqldump "$@"
}

# ---- Adjuntos ---------------------------------------------------------------------------------
#
# Los binarios viven bajo /app/var del contenedor del backend: `adjuntos/` (administrativos,
# akine.person.adjuntos.base-dir) y `adjuntos-clinicos/` (akine.clinical.adjuntos.base-dir).
# Se indica UNO de los dos:
#   AKINE_ADJUNTOS_VOLUME  volumen de Docker montado en /app/var
#   AKINE_ADJUNTOS_DIR     directorio del host que contiene adjuntos/ y adjuntos-clinicos/

adjuntos_mount() {
	# Imprime el argumento -v para montar el almacenamiento en /data.
	local modo="${1:-ro}"
	if [[ -n "${AKINE_ADJUNTOS_VOLUME:-}" ]]; then
		printf '%s' "${AKINE_ADJUNTOS_VOLUME}:/data:${modo}"
	elif [[ -n "${AKINE_ADJUNTOS_DIR:-}" ]]; then
		printf '%s' "${AKINE_ADJUNTOS_DIR}:/data:${modo}"
	else
		fail "falta AKINE_ADJUNTOS_VOLUME o AKINE_ADJUNTOS_DIR"
	fi
}

# en_adjuntos <ro|rw> <comando bash> — corre un comando con el almacenamiento montado en /data.
en_adjuntos() {
	local modo="$1"; shift
	docker run --rm -i -v "$(adjuntos_mount "$modo")" --entrypoint bash "$AKINE_OPS_IMAGE" -c "$1"
}

# Directorios que forman el almacenamiento. Si mañana aparece un tercero, se agrega aca.
ADJUNTOS_SUBDIRS="adjuntos adjuntos-clinicos"
