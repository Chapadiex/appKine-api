#!/usr/bin/env bash
# Prueba un backup restaurandolo de verdad en un MySQL efimero (G-13).
#
#   ops/verificar-backup.sh /srv/backups/akine/akine-20261009T030000Z
#
# Un backup que nunca se restauro no es un backup: es una esperanza. Este script levanta un
# MySQL 8.4 descartable (con log_bin_trust_function_creators=1, como produccion), un volumen
# descartable para los adjuntos, corre ops/restore.sh contra ellos —que verifica checksums,
# version de Flyway, tablas, triggers y el cruce fila/binario de cada adjunto— y borra todo.
#
# No toca la base ni el almacenamiento reales y no necesita sus credenciales. Pensado para
# correr despues de cada backup, o al menos una vez por semana (docs/operaciones/backup-y-restore.md).

source "$(dirname "$0")/lib.sh"

origen="${1:-}"
[[ -n "$origen" && -d "$origen" ]] || fail "uso: verificar-backup.sh <directorio del backup>"

id="akine-verif-$(date -u +%s)-$$"
limpiar() {
	docker rm -f "$id-mysql" >/dev/null 2>&1 || true
	docker volume rm "$id-var" >/dev/null 2>&1 || true
	docker network rm "$id-net" >/dev/null 2>&1 || true
}
trap limpiar EXIT

clave="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
docker network create "$id-net" >/dev/null
docker volume create "$id-var" >/dev/null
log "levantando MySQL efimero $id-mysql"
docker run -d --name "$id-mysql" --network "$id-net" \
	-e MYSQL_DATABASE=akine_verif -e MYSQL_USER=akine -e MYSQL_PASSWORD="$clave" \
	-e MYSQL_RANDOM_ROOT_PASSWORD=yes -e TZ=UTC \
	"$AKINE_OPS_IMAGE" \
	--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci \
	--default-time-zone=+00:00 --log-bin-trust-function-creators=1 >/dev/null

for _ in $(seq 1 90); do
	if MYSQL_PWD="$clave" docker exec -e MYSQL_PWD "$id-mysql" \
		mysql -uakine -h127.0.0.1 akine_verif -e 'SELECT 1' >/dev/null 2>&1; then
		listo=1; break
	fi
	sleep 2
done
[[ "${listo:-0}" == 1 ]] || fail "el MySQL efimero no quedo listo"

AKINE_DB_HOST="$id-mysql" AKINE_DB_PORT=3306 AKINE_DB_NAME=akine_verif \
AKINE_DB_USER=akine AKINE_DB_PASSWORD="$clave" AKINE_DB_DOCKER_NETWORK="$id-net" \
AKINE_MYSQL_CLIENT=docker AKINE_RESTORE_CONFIRM=akine_verif \
AKINE_ADJUNTOS_VOLUME="$id-var" AKINE_ADJUNTOS_DIR= \
	"$(dirname "$0")/restore.sh" "$origen"

log "BACKUP VERIFICADO: $origen se restaura completo"
