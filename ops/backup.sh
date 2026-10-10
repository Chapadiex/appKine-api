#!/usr/bin/env bash
# Backup de AKINE: base MySQL + almacenamiento de adjuntos, con verificacion y retencion (G-13).
#
#   AKINE_BACKUP_DIR=/srv/backups/akine \
#   AKINE_DB_HOST=... AKINE_DB_NAME=... AKINE_DB_USER=... AKINE_DB_PASSWORD=... \
#   AKINE_ADJUNTOS_VOLUME=akine-var \
#     ops/backup.sh
#
# Deja un directorio akine-<UTC>/ con:
#   db.sql.gz        mysqldump consistente (--single-transaction) con rutinas, triggers y eventos
#   adjuntos.tar.gz  adjuntos/ y adjuntos-clinicos/, con dueño numerico
#   MANIFEST         que se respaldo, de que version de esquema, cuantas filas y triggers
#   SHA256SUMS       integridad de los dos archivos
#
# El directorio se escribe como .partial y se renombra al final: un backup que murio a mitad de
# camino nunca se confunde con uno bueno, y la retencion no lo cuenta.
#
# Variables opcionales:
#   AKINE_BACKUP_RETENTION_DAYS  (14) borra backups completos mas viejos que esto...
#   AKINE_BACKUP_KEEP_MIN        (7)  ...pero nunca deja menos que esta cantidad
#   AKINE_BACKUP_SKIP_ADJUNTOS   (0)  1 = solo la base (no recomendado: ver docs)
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
version_mysql="$(mysql_query 'SELECT VERSION()')"
flyway="$(mysql_query "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")"
fallidas="$(mysql_query "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")"
[[ "$fallidas" == 0 ]] || fail "flyway_schema_history tiene $fallidas migraciones fallidas: la base esta a medio migrar. Ver runbook R1 antes de respaldar"
tablas="$(mysql_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")"
triggers="$(mysql_query "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()")"
rutinas="$(mysql_query "SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()")"

# ---- 2. Dump ----------------------------------------------------------------------------------
# --single-transaction: foto consistente de InnoDB sin bloquear escrituras. Lo que NO tolera es
#   DDL concurrente: no correr durante un deploy (las migraciones de Flyway son DDL).
# --routines --triggers --events: V14 crea los triggers append-only de audit_event; sin ellos
#   un restore deja la auditoria modificable y nadie se entera.
# --set-gtid-purged=OFF: el dump se restaura en un servidor nuevo, no en una replica.
# --no-tablespaces: evita exigir el privilegio PROCESS al usuario de backup.
# Sin --databases: el dump no fija el nombre de la base y se restaura en cualquiera.
log "mysqldump -> db.sql.gz"
mysql_dump \
	--single-transaction --quick --routines --triggers --events \
	--hex-blob --set-gtid-purged=OFF --no-tablespaces \
	--default-character-set=utf8mb4 \
	"$AKINE_DB_NAME" | gzip -6 > "$parcial/db.sql.gz"

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
read -r tablas_dump triggers_dump con_flyway completo < <(gunzip -c "$parcial/db.sql.gz" | awk '
	/^CREATE TABLE / { t++ }
	/TRIGGER `/ { g++ }
	/^CREATE TABLE `flyway_schema_history`/ { f = 1 }
	{ ultima = $0 }
	END { print t + 0, g + 0, f + 0, (ultima ~ /^-- Dump completed/) ? 1 : 0 }')
[[ "$completo" == 1 ]] || fail "el dump no termina con '-- Dump completed': mysqldump se corto"
[[ "$con_flyway" == 1 ]] || fail "el dump no trae flyway_schema_history: el backend restaurado no podria validar el esquema"
[[ "$tablas_dump" == "$tablas" ]] || fail "el dump trae $tablas_dump tablas y la base tenia $tablas"
[[ "$triggers_dump" -ge "$triggers" ]] || fail "el dump trae $triggers_dump triggers y la base tenia $triggers"
if [[ -f "$parcial/adjuntos.tar.gz" ]]; then
	en_tar="$(gunzip -c "$parcial/adjuntos.tar.gz" | tar -tf - | grep -vc '/$' || true)"
	[[ "$en_tar" == "$archivos" ]] || fail "el tar trae $en_tar archivos y el almacenamiento tenia $archivos"
fi

{
	echo "formato=akine-backup/1"
	echo "creado_utc=$sello"
	echo "base=$AKINE_DB_NAME"
	echo "mysql_version=$version_mysql"
	echo "flyway_version=$flyway"
	echo "tablas=$tablas"
	echo "triggers=$triggers"
	echo "rutinas=$rutinas"
	echo "adjuntos_incluidos=$([[ "$SIN_ADJUNTOS" == 1 ]] && echo no || echo si)"
	echo "adjuntos_archivos=$archivos"
	echo "imagen_backend=${AKINE_BACKEND_IMAGE:-sin-dato}"
} > "$parcial/MANIFEST"

( cd "$parcial" && sha256sum db.sql.gz $( [[ -f adjuntos.tar.gz ]] && echo adjuntos.tar.gz ) MANIFEST > SHA256SUMS )

mv "$parcial" "$destino"
trap - EXIT
log "backup completo: $destino (flyway $flyway, $tablas tablas, $triggers triggers, $archivos adjuntos)"

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
