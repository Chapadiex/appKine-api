#!/usr/bin/env bash
# Prueba un backup restaurandolo de verdad en un PostgreSQL efimero (G-13).
#
#   ops/verificar-backup.sh /srv/backups/akine/akine-20261009T030000Z
#
# Un backup que nunca se restauro no es un backup: es una esperanza. Este script levanta un
# PostgreSQL 18 + pgvector descartable, inicializado como produccion (rol de aplicacion SIN
# superusuario dueño de la base, y vector creada de antemano por el superusuario, que es el paso
# del DBA: ver pg_efimero en lib.sh), un volumen descartable para los adjuntos, corre
# ops/restore.sh contra ellos con el rol de la aplicacion —que verifica checksums, version de
# Flyway, tablas, triggers, funciones, extensiones y el cruce fila/binario de cada adjunto— y
# borra todo.
#
# No toca la base ni el almacenamiento reales y no necesita sus credenciales. Pensado para
# correr despues de cada backup, o al menos una vez por semana (docs/operaciones/backup-y-restore.md).

source "$(dirname "$0")/lib.sh"

origen="${1:-}"
[[ -n "$origen" && -d "$origen" ]] || fail "uso: verificar-backup.sh <directorio del backup>"

id="akine-verif-$(date -u +%s)-$$"
limpiar() {
	docker rm -f "$id-postgres" >/dev/null 2>&1 || true
	docker volume rm "$id-var" >/dev/null 2>&1 || true
	docker network rm "$id-net" >/dev/null 2>&1 || true
}
trap limpiar EXIT

clave="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
docker network create "$id-net" >/dev/null
docker volume create "$id-var" >/dev/null
log "levantando PostgreSQL efimero $id-postgres"
pg_efimero "$id-postgres" "$id-net" akine_verif akine "$clave"

AKINE_DB_HOST="$id-postgres" AKINE_DB_PORT=5432 AKINE_DB_NAME=akine_verif \
AKINE_DB_USER=akine AKINE_DB_PASSWORD="$clave" AKINE_DB_DOCKER_NETWORK="$id-net" \
AKINE_DB_CLIENT=docker AKINE_RESTORE_CONFIRM=akine_verif \
AKINE_ADJUNTOS_VOLUME="$id-var" AKINE_ADJUNTOS_DIR= \
	"$(dirname "$0")/restore.sh" "$origen"

log "BACKUP VERIFICADO: $origen se restaura completo"
