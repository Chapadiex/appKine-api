#!/usr/bin/env bash
# Backup de AKINE: base PostgreSQL + almacenamiento de adjuntos, con verificacion y retencion (G-13).
#
#   AKINE_BACKUP_DIR=/srv/backups/akine \
#   AKINE_DB_HOST=... AKINE_DB_NAME=... AKINE_DB_USER=... AKINE_DB_PASSWORD=... \
#   AKINE_ADJUNTOS_VOLUME=akine-var \
#     ops/backup.sh
#
# El usuario de backup tiene que poder leer todas las tablas: el dueño de la base (el rol de la
# aplicacion) o un rol con pg_read_all_data. No necesita superusuario.
#
# Deja un directorio akine-<UTC>/ con:
#   db.sql.gz        pg_dump consistente en SQL plano, con extensiones, collation, funciones y triggers
#   adjuntos.tar.gz  adjuntos/ y adjuntos-clinicos/, con dueño numerico
#   MANIFEST         que se respaldo, de que version de esquema, cuantas tablas, triggers y
#                    funciones, y que extensiones va a necesitar el restore
#   SHA256SUMS       integridad de los dos archivos
#
# El directorio se escribe como .partial y se renombra al final: un backup que murio a mitad de
# camino nunca se confunde con uno bueno, y la retencion no lo cuenta.
#
# Variables opcionales:
#   AKINE_BACKUP_RETENTION_DAYS  (14)  borra backups completos mas viejos que esto...
#   AKINE_BACKUP_KEEP_MIN        (7)   ...pero nunca deja menos que esta cantidad
#   AKINE_BACKUP_SKIP_ADJUNTOS   (0)   1 = solo la base (no recomendado: ver docs)
#   AKINE_BACKUP_LOCK_TIMEOUT    (60s) cuanto espera pg_dump un lock antes de abortar
#
# Ver docs/operaciones/backup-y-restore.md.

source "$(dirname "$0")/lib.sh"

require_var AKINE_BACKUP_DIR
db_init
RETENCION_DIAS="${AKINE_BACKUP_RETENTION_DAYS:-14}"
MINIMO="${AKINE_BACKUP_KEEP_MIN:-7}"
SIN_ADJUNTOS="${AKINE_BACKUP_SKIP_ADJUNTOS:-0}"

sello="$(date -u +%Y%m%dT%H%M%SZ)"
destino="$AKINE_BACKUP_DIR/akine-$sello"
parcial="$destino.partial"
mkdir -p "$parcial"
trap '[[ -d "$parcial" ]] && log "backup incompleto, queda en $parcial para diagnostico"' EXIT

# ---- 1. Estado de la base ANTES del dump ------------------------------------------------------
# Lo que se espera encontrar en el dump. Se lee antes, no despues: si alguien migra durante el
# backup, el manifiesto describe lo que habia al empezar y la verificacion lo detecta.
log "leyendo estado de $AKINE_DB_NAME en $AKINE_DB_HOST:$AKINE_DB_PORT"
version_postgres="$(db_query "SELECT current_setting('server_version')")"
flyway="$(db_query "$SQL_FLYWAY_VERSION")"
fallidas="$(db_query "$SQL_FLYWAY_FALLIDAS")"
[[ "$fallidas" == 0 ]] || fail "flyway_schema_history tiene $fallidas migraciones fallidas: la base esta a medio migrar. Ver runbook R1 antes de respaldar"
tablas="$(db_query "$SQL_TABLAS")"
triggers="$(db_query "$SQL_TRIGGERS")"
funciones="$(db_query "$SQL_FUNCIONES")"
extensiones="$(db_query "$SQL_EXTENSIONES")"

# ---- 2. Dump ----------------------------------------------------------------------------------
# pg_dump siempre saca una foto consistente: el dump entero sale de UNA transaccion con un solo
#   snapshot, sin bloquear escrituras (el equivalente de --single-transaction de mysqldump). Lo
#   que no convive es el DDL: pg_dump toma ACCESS SHARE sobre cada tabla y un ALTER de Flyway
#   espera detras, o pg_dump espera detras de el. No correr durante un deploy.
#   --lock-wait-timeout hace que el backup falle en vez de quedar colgado detras de una migracion.
# SQL plano comprimido: se lee con gunzip, se verifica con awk y se restaura con psql. Trae las
#   extensiones, la collation akine_ci, las funciones y los triggers (los append-only de
#   audit_event y los *_updated_at): sin ellos un restore deja la auditoria modificable y nadie se
#   entera.
# --no-owner --no-privileges: el dump no fija dueños ni GRANT del ORIGEN; todo pasa a ser de quien
#   restaura, que es el dueño de la base destino. (Es lo que en MySQL obligaba a quitar el DEFINER.)
# Sin --create: el dump no fija el nombre de la base y se restaura en cualquiera.
log "pg_dump -> db.sql.gz"
db_dump \
	--format=plain --no-owner --no-privileges --encoding=UTF8 \
	--lock-wait-timeout="${AKINE_BACKUP_LOCK_TIMEOUT:-60s}" \
	--dbname="$AKINE_DB_NAME" | gzip -6 > "$parcial/db.sql.gz"

# ---- 3. Adjuntos ------------------------------------------------------------------------------
# DESPUES de la base, a proposito: un archivo subido entre el dump y el tar queda como binario
# sin fila (inofensivo). El orden inverso dejaria filas apuntando a archivos que no estan.
archivos=0
if [[ "$SIN_ADJUNTOS" == 1 ]]; then
	log "AKINE_BACKUP_SKIP_ADJUNTOS=1: se omiten los adjuntos"
else
	log "tar de adjuntos -> adjuntos.tar.gz"
	en_adjuntos ro "cd /data && for d in $ADJUNTOS_SUBDIRS; do mkdir -p /tmp/vacio/\$d; done; \
		dirs=''; for d in $ADJUNTOS_SUBDIRS; do [ -d \"\$d\" ] && dirs=\"\$dirs \$d\"; done; \
		if [ -z \"\$dirs\" ]; then tar -C /tmp/vacio -czf - $ADJUNTOS_SUBDIRS; \
		else tar --numeric-owner -czf - \$dirs; fi" > "$parcial/adjuntos.tar.gz"
	archivos="$(en_adjuntos ro "cd /data && find $ADJUNTOS_SUBDIRS -type f 2>/dev/null | wc -l")"
fi

# ---- 4. Verificacion --------------------------------------------------------------------------
log "verificando"
gzip -t "$parcial/db.sql.gz" || fail "db.sql.gz no es un gzip valido"
# Una sola pasada por el dump. (No `gunzip | grep -q`: con pipefail, grep -q corta temprano,
# gunzip muere por SIGPIPE y el pipeline "falla" justo cuando encontro lo que buscaba.)
# pg_dump escribe cada CREATE al principio de la linea: se cuentan esos, que son exactos.
read -r tablas_dump triggers_dump funciones_dump con_flyway completo < <(gunzip -c "$parcial/db.sql.gz" | awk '
	/^CREATE (UNLOGGED )?TABLE / { t++ }
	/^CREATE (CONSTRAINT )?TRIGGER / { g++ }
	/^CREATE (OR REPLACE )?(FUNCTION|PROCEDURE|AGGREGATE) / { f++ }
	/^CREATE TABLE public\.flyway_schema_history / { fl = 1 }
	/^-- PostgreSQL database dump complete/ { c = 1 }
	END { print t + 0, g + 0, f + 0, fl + 0, c + 0 }')
[[ "$completo" == 1 ]] || fail "el dump no trae '-- PostgreSQL database dump complete': pg_dump se corto"
[[ "$con_flyway" == 1 ]] || fail "el dump no trae flyway_schema_history: el backend restaurado no podria validar el esquema"
[[ "$tablas_dump" == "$tablas" ]] || fail "el dump trae $tablas_dump tablas y la base tenia $tablas"
[[ "$triggers_dump" == "$triggers" ]] || fail "el dump trae $triggers_dump triggers y la base tenia $triggers"
[[ "$funciones_dump" == "$funciones" ]] || fail "el dump trae $funciones_dump funciones y la base tenia $funciones"
if [[ -f "$parcial/adjuntos.tar.gz" ]]; then
	en_tar="$(gunzip -c "$parcial/adjuntos.tar.gz" | tar -tf - | grep -vc '/$' || true)"
	[[ "$en_tar" == "$archivos" ]] || fail "el tar trae $en_tar archivos y el almacenamiento tenia $archivos"
fi

{
	echo "formato=akine-backup/2"
	echo "creado_utc=$sello"
	echo "motor=postgresql"
	echo "base=$AKINE_DB_NAME"
	echo "postgres_version=$version_postgres"
	echo "flyway_version=$flyway"
	echo "tablas=$tablas"
	echo "triggers=$triggers"
	echo "funciones=$funciones"
	echo "extensiones=$extensiones"
	echo "adjuntos_incluidos=$([[ "$SIN_ADJUNTOS" == 1 ]] && echo no || echo si)"
	echo "adjuntos_archivos=$archivos"
	echo "imagen_backend=${AKINE_BACKEND_IMAGE:-sin-dato}"
} > "$parcial/MANIFEST"

( cd "$parcial" && sha256sum db.sql.gz $( [[ -f adjuntos.tar.gz ]] && echo adjuntos.tar.gz ) MANIFEST > SHA256SUMS )

mv "$parcial" "$destino"
trap - EXIT
log "backup completo: $destino (flyway $flyway, $tablas tablas, $triggers triggers, $funciones funciones, $archivos adjuntos)"

# ---- 5. Retencion -----------------------------------------------------------------------------
# Solo cuenta backups COMPLETOS (los .partial no). Borra los vencidos empezando por el mas viejo
# y se detiene si quedaria por debajo del minimo: con el job de backup roto una semana, la
# retencion por dias sola borraria el ultimo backup bueno.
mapfile -t completos < <(find "$AKINE_BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name 'akine-*' ! -name '*.partial' | sort)
total=${#completos[@]}
limite="$(date -u -d "-$RETENCION_DIAS days" +%Y%m%dT%H%M%SZ 2>/dev/null || date -u -v-"$RETENCION_DIAS"d +%Y%m%dT%H%M%SZ)"
for dir in "${completos[@]}"; do
	(( total > MINIMO )) || break
	nombre="$(basename "$dir")"
	[[ "${nombre#akine-}" < "$limite" ]] || break
	log "retencion: borro $nombre (mas viejo que $RETENCION_DIAS dias)"
	rm -rf -- "$dir"
	total=$((total - 1))
done

echo "$destino"
