#!/usr/bin/env bash
# Stack AISLADO para validar las consultas SQL de la guia de QA (AKINE G-12). Ver docs/qa/README.md §8.
#
#   docs/qa/validacion/stack.sh build    # imagen g12-akine-api:qa desde este repo
#   docs/qa/validacion/stack.sh up       # red g12-net + MySQL 3329 + Mailpit 8049/1049 + backend 8099
#   docs/qa/validacion/stack.sh down     # baja y BORRA todo lo g12-* (contenedores, volumen, red; imagen con --imagen)
#
# Todo lleva el prefijo g12- y puertos propios: NO toca akine-mysql, akine-mailpit, el 8080 ni el
# 4200 del entorno de desarrollo. La base es efimera (volumen anonimo): `down` la borra.
# Esto NO es el QA manual: el QA se corre contra el stack de desarrollo (README §2). Esto solo
# prueba que las consultas de la guia corren contra el esquema real.
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
RED=g12-net
PLATAFORMA_EMAIL="${AKINE_QA_PLATAFORMA_EMAIL:-plataforma.qa@ejemplo.test}"
export MSYS_NO_PATHCONV=1

esperar_salud() {
	local i
	for i in $(seq 1 180); do
		if curl -fsS "http://localhost:8099/actuator/health/readiness" >/dev/null 2>&1; then
			echo "backend listo en http://localhost:8099"
			return 0
		fi
		sleep 5
	done
	echo "el backend no arranco en 15 minutos: docker logs g12-api" >&2
	return 1
}

case "${1:-}" in
build)
	docker build -t g12-akine-api:qa "$RAIZ"
	;;
up)
	docker network create "$RED" >/dev/null 2>&1 || true
	docker run -d --name g12-mysql --network "$RED" -p 127.0.0.1:3329:3306 \
		-e MYSQL_DATABASE=akine_local -e MYSQL_USER=akine -e MYSQL_PASSWORD=akine \
		-e MYSQL_ROOT_PASSWORD=root -e TZ=UTC mysql:8.4 \
		--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci \
		--default-time-zone=+00:00 --log-bin-trust-function-creators=1
	docker run -d --name g12-mailpit --network "$RED" \
		-p 127.0.0.1:8049:8025 -p 127.0.0.1:1049:1025 \
		-e MP_SMTP_AUTH_ACCEPT_ANY=true -e MP_SMTP_AUTH_ALLOW_INSECURE=true \
		axllent/mailpit:v1.21.8
	until docker exec g12-mysql mysqladmin ping -h localhost -uroot -proot --silent >/dev/null 2>&1; do sleep 2; done
	docker run -d --name g12-api --network "$RED" -p 127.0.0.1:8099:8080 \
		-e SPRING_PROFILES_ACTIVE=local \
		-e 'AKINE_DB_URL=jdbc:mysql://g12-mysql:3306/akine_local?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC' \
		-e AKINE_DB_USER=akine -e AKINE_DB_PASSWORD=akine \
		-e AKINE_MAIL_HOST=g12-mailpit -e AKINE_MAIL_PORT=1025 \
		-e AKINE_BOOTSTRAP_ADMIN_EMAIL="$PLATAFORMA_EMAIL" \
		g12-akine-api:qa
	esperar_salud
	;;
down)
	docker rm -f -v g12-api g12-mailpit g12-mysql >/dev/null 2>&1 || true
	docker network rm "$RED" >/dev/null 2>&1 || true
	if [ "${2:-}" = "--imagen" ]; then
		docker rmi g12-akine-api:qa >/dev/null 2>&1 || true
	fi
	echo "stack g12 borrado"
	;;
*)
	sed -n '2,11p' "$0"
	exit 1
	;;
esac
