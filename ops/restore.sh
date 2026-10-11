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
#   - La base destino tiene que existir y estar VACIA. Este script no hace DROP de nada: si hay
#     que pisar una base, se vacia a mano y con nombre propio (docs/operaciones/backup-y-restore.md).
#   - AKINE_RESTORE_CONFIRM tiene que repetir el nombre de la base destino.
#   - El servidor destino necesita log_bin_trust_function_creators=1 (o binlog apagado): los
#     triggers de V14 no se crean sin eso y el restore quedaria a medias.
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
log "backup del $(manifiesto creado_utc), base $(manifiesto base), flyway $(manifiesto flyway_version)"

# ---- Base -------------------------------------------------------------------------------------
if [[ "$alcance" != --solo-adjuntos ]]; then
	db_init
	[[ "${AKINE_RESTORE_CONFIRM:-}" == "$AKINE_DB_NAME" ]] \
		|| fail "AKINE_RESTORE_CONFIRM tiene que ser exactamente '$AKINE_DB_NAME'"

	existentes="$(mysql_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()")"
	[[ "$existentes" == 0 ]] || fail "la base $AKINE_DB_NAME tiene $existentes tablas: restaurar exige una base vacia"

	binlog="$(mysql_query 'SELECT @@log_bin')"
	confianza="$(mysql_query 'SELECT @@log_bin_trust_function_creators')"
	if [[ "$binlog" == 1 && "$confianza" != 1 ]]; then
		fail "el servidor tiene binlog y log_bin_trust_function_creators=0: los triggers de V14 no se van a crear. Arrancar MySQL con --log-bin-trust-function-creators=1"
	fi

	# DEFINER fuera: el dump trae el usuario que creo los triggers en el ORIGEN. Si el usuario del
	# destino es otro, crear un trigger con DEFINER ajeno exige SET_USER_ID/SYSTEM_USER. Sin la
	# clausula, el definer pasa a ser quien restaura, que es el dueño de la base.
	log "restaurando base $AKINE_DB_NAME en $AKINE_DB_HOST:$AKINE_DB_PORT"
	gunzip -c "$origen/db.sql.gz" \
		| sed -E 's/DEFINER=`[^`]+`@`[^`]+`//g' \
		| mysql_exec

	esperado_flyway="$(manifiesto flyway_version)"
	flyway="$(mysql_query "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")"
	tablas="$(mysql_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")"
	triggers="$(mysql_query "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()")"
	[[ "$flyway" == "$esperado_flyway" ]] || fail "flyway restaurado $flyway, el backup decia $esperado_flyway"
	[[ "$tablas" == "$(manifiesto tablas)" ]] || fail "tablas restauradas $tablas, el backup decia $(manifiesto tablas)"
	[[ "$triggers" == "$(manifiesto triggers)" ]] || fail "triggers restaurados $triggers, el backup decia $(manifiesto triggers)"
	log "base restaurada: flyway $flyway, $tablas tablas, $triggers triggers"
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
