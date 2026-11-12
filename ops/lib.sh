#!/usr/bin/env bash
# Funciones comunes de los scripts de operaciones (G-13). Se incluye con `source`, no se ejecuta.
#
# Toda la configuracion llega por variable de entorno, igual que en la imagen del backend: los
# scripts no tienen ni un host ni una credencial escritos adentro. Ver docs/operaciones/.
#
# Cliente de PostgreSQL: si `pg_dump` y `psql` estan en el PATH se usan esos; si no, se corren desde
# la misma imagen del servidor (`pgvector/pgvector:0.8.1-pg18-trixie`) con `docker run`, pasando los
# datos por stdin/stdout. Asi el host de operaciones solo necesita Docker, y la version del cliente
# es la misma que la del servidor. (pg_dump exige una version >= a la del servidor: un cliente local
# mas viejo falla con "server version mismatch"; en ese caso, AKINE_DB_CLIENT=docker.)

set -euo pipefail

# Git Bash en Windows reescribe los argumentos que empiezan con "/" como rutas de Windows
# (`tar -C /data` termina en `C:/Program Files/Git/data`). En Linux esta variable no hace nada.
export MSYS_NO_PATHCONV=1

AKINE_OPS_IMAGE="${AKINE_OPS_IMAGE:-pgvector/pgvector:0.8.1-pg18-trixie}"

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
# AKINE_DB_HOST, AKINE_DB_PORT (5432), AKINE_DB_NAME, AKINE_DB_USER, AKINE_DB_PASSWORD.
# AKINE_DB_DOCKER_NETWORK: red de Docker a la que se conecta el cliente cuando corre en
#   contenedor (p. ej. la red donde vive el contenedor de PostgreSQL, y entonces AKINE_DB_HOST es
#   el nombre del contenedor). Sin ella, el cliente en contenedor alcanza la maquina como
#   host.docker.internal.
# AKINE_DB_CLIENT: local | docker. Por defecto, local si hay pg_dump y psql en el PATH.
#
# La contrasena viaja en PGPASSWORD y nunca como argumento: un argumento se ve en `ps`.
#
# Todo lo que se cuenta (tablas, triggers, funciones) es del esquema `public`, que es donde Flyway
# crea el esquema de AKINE, y sin los objetos que pertenecen a una extension: las ~100 funciones de
# pgvector viven en `public` pero no son de AKINE, pg_dump no las vuelca (las recrea el
# CREATE EXTENSION) y contarlas haria que nada cierre.

db_init() {
	require_var AKINE_DB_HOST AKINE_DB_NAME AKINE_DB_USER AKINE_DB_PASSWORD
	AKINE_DB_PORT="${AKINE_DB_PORT:-5432}"
	if [[ -z "${AKINE_DB_CLIENT:-}" ]]; then
		if command -v pg_dump >/dev/null 2>&1 && command -v psql >/dev/null 2>&1; then
			AKINE_DB_CLIENT=local
		else
			AKINE_DB_CLIENT=docker
		fi
	fi
	export PGPASSWORD="$AKINE_DB_PASSWORD"
	# Que ningun PGSERVICE del host de operaciones cambie a quien se conecta, y sin los NOTICE
	# ("extension ya existe") que ensucian la salida: los errores y avisos reales siguen saliendo.
	unset PGSERVICE
	export PGOPTIONS="-c client_min_messages=warning"
}

_db_run() {
	# _db_run <programa> <args...> — stdin y stdout pasan derecho.
	local programa="$1"; shift
	if [[ "$AKINE_DB_CLIENT" == local ]]; then
		"$programa" --host="$AKINE_DB_HOST" --port="$AKINE_DB_PORT" --username="$AKINE_DB_USER" \
			--no-password "$@"
	else
		local red=()
		local host="$AKINE_DB_HOST"
		if [[ -n "${AKINE_DB_DOCKER_NETWORK:-}" ]]; then
			red=(--network "$AKINE_DB_DOCKER_NETWORK")
		elif [[ "$host" == localhost || "$host" == 127.0.0.1 ]]; then
			host=host.docker.internal
			red=(--add-host=host.docker.internal:host-gateway)
		fi
		docker run --rm -i "${red[@]}" -e PGPASSWORD -e PGOPTIONS -e PGTZ=UTC --entrypoint "$programa" "$AKINE_OPS_IMAGE" \
			--host="$host" --port="$AKINE_DB_PORT" --username="$AKINE_DB_USER" --no-password "$@"
	fi
}

# db_query "<sql>" — una consulta, salida separada por tabuladores, sin encabezado ni pie.
db_query() {
	_db_run psql --no-psqlrc --quiet --tuples-only --no-align --field-separator=$'\t' \
		--set=ON_ERROR_STOP=1 --dbname="$AKINE_DB_NAME" --command="$1"
}

# db_exec — ejecuta el SQL que llega por stdin contra AKINE_DB_NAME, en UNA transaccion y cortando
# en el primer error: o entra todo, o no queda nada.
db_exec() {
	_db_run psql --no-psqlrc --quiet --set=ON_ERROR_STOP=1 --single-transaction \
		--dbname="$AKINE_DB_NAME"
}

db_dump() {
	_db_run pg_dump "$@"
}

# Consultas de estado que comparten backup, restore y simulacro. Se definen una vez para que el
# manifiesto y la verificacion cuenten exactamente lo mismo.
SQL_FLYWAY_VERSION="SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"
SQL_FLYWAY_FALLIDAS="SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success"
# Objetos de una extension: se excluyen de todos los conteos (ver arriba).
_SQL_NO_EXT="NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = o.oid AND d.deptype = 'e')"
SQL_TABLAS="SELECT COUNT(*) FROM pg_class o JOIN pg_namespace n ON n.oid = o.relnamespace
	WHERE n.nspname = 'public' AND o.relkind IN ('r', 'p') AND $_SQL_NO_EXT"
SQL_TRIGGERS="SELECT COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
	JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND NOT t.tgisinternal"
SQL_FUNCIONES="SELECT COUNT(*) FROM pg_proc o JOIN pg_namespace n ON n.oid = o.pronamespace
	WHERE n.nspname = 'public' AND $_SQL_NO_EXT"
# Extensiones instaladas, sin plpgsql (viene en toda base): 'unaccent:1.1,vector:0.8.1'.
SQL_EXTENSIONES="SELECT COALESCE(string_agg(extname || ':' || extversion, ',' ORDER BY extname), '')
	FROM pg_extension WHERE extname <> 'plpgsql'"
# Objetos propios en public (relaciones, funciones, tipos, collations), sin los de extensiones.
SQL_OBJETOS_PROPIOS="SELECT
	(SELECT COUNT(*) FROM pg_class o JOIN pg_namespace n ON n.oid = o.relnamespace
		WHERE n.nspname = 'public' AND $_SQL_NO_EXT)
	+ (SELECT COUNT(*) FROM pg_proc o JOIN pg_namespace n ON n.oid = o.pronamespace
		WHERE n.nspname = 'public' AND $_SQL_NO_EXT)
	+ (SELECT COUNT(*) FROM pg_collation o JOIN pg_namespace n ON n.oid = o.collnamespace
		WHERE n.nspname = 'public' AND $_SQL_NO_EXT)
	+ (SELECT COUNT(*) FROM pg_type o JOIN pg_namespace n ON n.oid = o.typnamespace
		WHERE n.nspname = 'public' AND o.typrelid = 0 AND o.typelem = 0 AND $_SQL_NO_EXT)"

# ---- PostgreSQL efimero -----------------------------------------------------------------------
#
# pg_efimero <contenedor> <red> <base> <usuario> <clave> [puerto del host] [volumen de datos]
#
# Levanta un PostgreSQL con la imagen de produccion e inicializado con el MISMO script que el
# compose.yaml (ops/postgres/init/01-akine.sh): superusuario `postgres` con clave aleatoria que no
# sale del contenedor, rol de aplicacion SIN superusuario dueño de la base, y la extension vector
# ya creada, que es el paso del DBA. Asi lo que se prueba contra el es el camino de produccion: el
# restore corre con el usuario de la aplicacion, que no puede crear vector por si mismo.
pg_efimero() {
	local nombre="$1" red="$2" base="$3" usuario="$4" clave="$5" puerto="${6:-}" volumen="${7:-}"
	local init
	init="$(cd "$(dirname "${BASH_SOURCE[0]}")/postgres/init" && pwd)"
	# docker en Windows no entiende /c/...: se le pasa la ruta nativa.
	if command -v cygpath >/dev/null 2>&1; then init="$(cygpath -m "$init")"; fi
	local extra=()
	[[ -n "$puerto" ]] && extra+=(-p "127.0.0.1:$puerto:5432")
	[[ -n "$volumen" ]] && extra+=(-v "$volumen:/var/lib/postgresql")
	docker run -d --name "$nombre" --network "$red" "${extra[@]}" \
		-v "$init:/docker-entrypoint-initdb.d:ro" \
		-e POSTGRES_USER=postgres \
		-e POSTGRES_PASSWORD="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" \
		-e AKINE_DB_NAME="$base" -e AKINE_DB_USER="$usuario" -e AKINE_DB_PASSWORD="$clave" \
		-e TZ=UTC -e PGTZ=UTC \
		"$AKINE_OPS_IMAGE" postgres -c timezone=UTC >/dev/null
	# pg_isready da verde durante el arranque temporal del entrypoint, antes de que corra el init y
	# antes del reinicio final: se espera a que el rol de la aplicacion entre a SU base por TCP.
	local _
	for _ in $(seq 1 90); do
		if docker exec -e PGPASSWORD="$clave" "$nombre" \
			psql -h 127.0.0.1 -U "$usuario" -d "$base" -XAtqc 'SELECT 1' >/dev/null 2>&1; then
			return 0
		fi
		sleep 2
	done
	docker logs --tail 40 "$nombre" >&2 || true
	fail "el PostgreSQL efimero $nombre no quedo listo"
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
# La imagen de PostgreSQL es Debian: trae bash, tar, find y sha256sum.
en_adjuntos() {
	local modo="$1"; shift
	docker run --rm -i -v "$(adjuntos_mount "$modo")" --entrypoint bash "$AKINE_OPS_IMAGE" -c "$1"
}

# Directorios que forman el almacenamiento. Si mañana aparece un tercero, se agrega aca.
ADJUNTOS_SUBDIRS="adjuntos adjuntos-clinicos"
