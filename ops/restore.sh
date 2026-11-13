#!/usr/bin/env bash
# Restore de un backup de AKINE hecho con ops/backup.sh (G-13).
#
#   AKINE_RESTORE_CONFIRM=<nombre de la base> \
#   AKINE_DB_HOST=... AKINE_DB_NAME=... AKINE_DB_USER=... AKINE_DB_PASSWORD=... \
#   AKINE_ADJUNTOS_VOLUME=akine-var \
#     ops/restore.sh /srv/backups/akine/akine-20261009T030000Z [--solo-db | --solo-adjuntos]
#
# Reglas, todas a proposito:
#   - El backend tiene que estar DETENIDO. Restaurar debajo de una aplicacion viva mezcla dos
#     historias en la misma base.
#   - La base destino tiene que existir y estar VACIA (sin tablas, funciones, tipos ni collations
#     propias en `public`; las extensiones si pueden estar). Este script no hace DROP de nada: si
#     hay que pisar una base, se vacia a mano y con nombre propio (docs/operaciones/backup-y-restore.md).
#   - AKINE_RESTORE_CONFIRM tiene que repetir el nombre de la base destino.
#   - AKINE_DB_USER pasa a ser el dueño de todo lo restaurado (el dump no trae dueños): tiene que
#     ser el dueño de la base destino y el rol con el que despues corre el backend.
#   - Las extensiones del MANIFEST (hoy vector y unaccent) se crean ANTES de cargar el dump.
#     `unaccent` es "trusted" y la puede crear el dueño de la base; `vector` NO: exige
#     superusuario. O el restore corre con un usuario que puede crearla, o —lo recomendado, el
#     mismo reparto que compose.yaml y ops/postgres/init— el DBA corre `CREATE EXTENSION vector;`
#     en la base destino antes y el restore corre con el rol de la aplicacion. Si falta y no se
#     puede crear, el script corta antes de cargar nada.
#   - El servidor destino tiene que ser PostgreSQL de la misma version mayor que el origen o mas
#     nueva (MANIFEST postgres_version), con pgvector e ICU (la collation akine_ci).
#   - Todo el dump entra en UNA transaccion: si algo falla, la base queda vacia como estaba, no a
#     medias.
#
# Adjuntos:
#   AKINE_RESTORE_ADJUNTOS_MODO=vacio (default)  el destino tiene que estar vacio
#   AKINE_RESTORE_ADJUNTOS_MODO=completar        agrega solo los archivos que faltan, sin pisar
#                                                ninguno (runbook R6: binarios perdidos)
#   AKINE_ADJUNTOS_UID (10001)                   dueño con el que quedan: el usuario de la imagen
#
# Al terminar corre ops/verificar-adjuntos.sh si se restauraron las dos partes.

source "$(dirname "$0")/lib.sh"

origen="${1:-}"
alcance="${2:-todo}"
[[ -n "$origen" && -d "$origen" ]] || fail "uso: restore.sh <directorio del backup> [--solo-db|--solo-adjuntos]"
case "$alcance" in todo|--solo-db|--solo-adjuntos) ;; *) fail "alcance desconocido: $alcance" ;; esac
[[ -f "$origen/MANIFEST" && -f "$origen/SHA256SUMS" ]] || fail "$origen no tiene MANIFEST/SHA256SUMS: no es un backup completo"

log "verificando integridad de $origen"
( cd "$origen" && sha256sum -c --quiet SHA256SUMS ) || fail "SHA256SUMS no coincide: el backup esta corrupto"
manifiesto() { grep "^$1=" "$origen/MANIFEST" | cut -d= -f2-; }
# akine-backup/1 son los dumps de MySQL de antes de la migracion y no se cargan en PostgreSQL: los
# datos de MySQL pasan con la herramienta de ops/migracion-mysql, no con este script.
[[ "$(manifiesto formato)" == akine-backup/2 ]] \
	|| fail "formato '$(manifiesto formato)': este script restaura akine-backup/2 (PostgreSQL). akine-backup/1 es un dump de MySQL"
log "backup del $(manifiesto creado_utc), base $(manifiesto base), flyway $(manifiesto flyway_version), PostgreSQL $(manifiesto postgres_version)"

# ---- Base -------------------------------------------------------------------------------------
if [[ "$alcance" != --solo-adjuntos ]]; then
	db_init
	[[ "${AKINE_RESTORE_CONFIRM:-}" == "$AKINE_DB_NAME" ]] \
		|| fail "AKINE_RESTORE_CONFIRM tiene que ser exactamente '$AKINE_DB_NAME'"

	existentes="$(db_query "$SQL_OBJETOS_PROPIOS")"
	[[ "$existentes" == 0 ]] || fail "la base $AKINE_DB_NAME tiene $existentes objetos propios en public: restaurar exige una base vacia"

	zona="$(db_query "SELECT current_setting('TimeZone')")"
	[[ "$zona" == UTC ]] || log "AVISO: la zona de la sesion en $AKINE_DB_NAME es $zona, no UTC. El dump no la trae (es de la base o del servidor): ALTER DATABASE $AKINE_DB_NAME SET timezone TO 'UTC', como hace ops/postgres/init"

	# Extensiones primero y por separado: si vector falta y este usuario no puede crearla, el error
	# es este, claro, y no un CREATE EXTENSION perdido en la mitad del dump.
	IFS=',' read -r -a requeridas <<< "$(manifiesto extensiones)"
	for item in "${requeridas[@]}"; do
		[[ -n "$item" ]] || continue
		ext="${item%%:*}"
		db_query "CREATE EXTENSION IF NOT EXISTS \"$ext\" WITH SCHEMA public" >/dev/null \
			|| fail "no se pudo crear la extension $ext en $AKINE_DB_NAME con el usuario $AKINE_DB_USER. Si exige superusuario (vector), el DBA corre antes 'CREATE EXTENSION $ext;' en esa base, o se restaura con un usuario que pueda crearla"
	done

	# COMMENT ON EXTENSION fuera: comentar una extension exige ser su dueño, y si la creo el DBA el
	# dueño es otro. Es lo unico que se descarta, y es el texto descriptivo de la extension.
	log "restaurando base $AKINE_DB_NAME en $AKINE_DB_HOST:$AKINE_DB_PORT"
	gunzip -c "$origen/db.sql.gz" \
		| sed -E '/^COMMENT ON EXTENSION /d' \
		| db_exec >/dev/null   # la salida son los SELECT setval/set_config del dump; los errores van a stderr

	esperado_flyway="$(manifiesto flyway_version)"
	flyway="$(db_query "$SQL_FLYWAY_VERSION")"
	tablas="$(db_query "$SQL_TABLAS")"
	triggers="$(db_query "$SQL_TRIGGERS")"
	funciones="$(db_query "$SQL_FUNCIONES")"
	extensiones="$(db_query "$SQL_EXTENSIONES")"
	[[ "$flyway" == "$esperado_flyway" ]] || fail "flyway restaurado $flyway, el backup decia $esperado_flyway"
	[[ "$tablas" == "$(manifiesto tablas)" ]] || fail "tablas restauradas $tablas, el backup decia $(manifiesto tablas)"
	[[ "$triggers" == "$(manifiesto triggers)" ]] || fail "triggers restaurados $triggers, el backup decia $(manifiesto triggers)"
	[[ "$funciones" == "$(manifiesto funciones)" ]] || fail "funciones restauradas $funciones, el backup decia $(manifiesto funciones)"
	# Se comparan los nombres: una version mas nueva de la extension en el destino es valida.
	nombres() { tr ',' '\n' | cut -d: -f1 | sort | tr '\n' ' '; }
	[[ "$(nombres <<< "$extensiones")" == "$(manifiesto extensiones | nombres)" ]] \
		|| fail "extensiones restauradas '$extensiones', el backup decia '$(manifiesto extensiones)'"
	log "base restaurada: flyway $flyway, $tablas tablas, $triggers triggers, $funciones funciones, extensiones $extensiones"
fi

# ---- Adjuntos ---------------------------------------------------------------------------------
if [[ "$alcance" != --solo-db ]]; then
	if [[ "$(manifiesto adjuntos_incluidos)" != si ]]; then
		log "el backup no incluye adjuntos: se omite esa parte"
	else
		modo="${AKINE_RESTORE_ADJUNTOS_MODO:-vacio}"
		uid="${AKINE_ADJUNTOS_UID:-10001}"
		case "$modo" in
			vacio)
				hay="$(en_adjuntos ro "find /data -mindepth 1 -type f | wc -l")"
				[[ "$hay" == 0 ]] || fail "el almacenamiento destino ya tiene $hay archivos. Para reponer solo los faltantes: AKINE_RESTORE_ADJUNTOS_MODO=completar"
				opcion_tar="" ;;
			completar)
				opcion_tar="--skip-old-files" ;;
			*) fail "AKINE_RESTORE_ADJUNTOS_MODO desconocido: $modo" ;;
		esac
		log "restaurando adjuntos (modo $modo)"
		antes="$(en_adjuntos ro "find /data -type f | wc -l")"
		en_adjuntos rw "tar -C /data --numeric-owner $opcion_tar -xzf - && chown -R $uid:$uid /data" \
			< "$origen/adjuntos.tar.gz"
		despues="$(en_adjuntos ro "find /data -type f | wc -l")"
		log "adjuntos: $antes archivos antes, $despues despues ($(manifiesto adjuntos_archivos) en el backup)"
	fi
fi

if [[ "$alcance" == todo && "$(manifiesto adjuntos_incluidos)" == si ]]; then
	"$(dirname "$0")/verificar-adjuntos.sh"
fi
log "restore terminado. Arrancar el backend con la MISMA imagen del backup ($(manifiesto imagen_backend)) o una posterior: Flyway valida el historial al arrancar"
