#!/usr/bin/env bash
# Stack AISLADO para validar las consultas SQL de la guia de QA (AKINE G-12). Ver docs/qa/README.md §8.
#
#   docs/qa/validacion/stack.sh build    # imagen <prefijo>-akine-api:qa desde este repo
#   docs/qa/validacion/stack.sh up       # red <prefijo>-net + MySQL + Mailpit + backend
#   docs/qa/validacion/stack.sh down     # baja y BORRA todo lo <prefijo>-* (contenedores, volumen, red; imagen con --imagen)
#
# Default: prefijo g12-, MySQL 3329, Mailpit 8049/1049, backend 8099. Si esos puertos estan tomados
# (perf/stack.sh usa 3329 y 8099), se cambian por entorno:
#   QA_PREFIJO=qa2 QA_PUERTO_MYSQL=3339 QA_PUERTO_API=8109 QA_PUERTO_MAILPIT=8059 QA_PUERTO_SMTP=1059
# Nunca toca akine-mysql, akine-mailpit, el 8080 ni el 4200 del entorno de desarrollo. La base es
# efimera (volumen anonimo): `down` la borra.
# Esto NO es el QA manual: el QA se corre contra el stack de desarrollo (README §2). Esto solo
# prueba que las consultas de la guia corren contra el esquema real.
set -euo pipefail

# `pwd -W` en Git Bash: con MSYS_NO_PATHCONV=1 docker no entiende la ruta /c/Users/...
RAIZ="$(cd "$(dirname "$0")/../../.." && (pwd -W 2>/dev/null || pwd))"
P="${QA_PREFIJO:-g12}"
PUERTO_MYSQL="${QA_PUERTO_MYSQL:-3329}"
PUERTO_API="${QA_PUERTO_API:-8099}"
PUERTO_MAILPIT="${QA_PUERTO_MAILPIT:-8049}"
PUERTO_SMTP="${QA_PUERTO_SMTP:-1049}"
RED="$P-net"
PLATAFORMA_EMAIL="${AKINE_QA_PLATAFORMA_EMAIL:-plataforma.qa@ejemplo.test}"
export MSYS_NO_PATHCONV=1

esperar_salud() {
	local i
	for i in $(seq 1 180); do
		if curl -fsS "http://localhost:$PUERTO_API/actuator/health/readiness" >/dev/null 2>&1; then
			echo "backend listo en http://localhost:$PUERTO_API"
			return 0
		fi
		sleep 5
	done
	echo "el backend no arranco en 15 minutos: docker logs $P-api" >&2
	return 1
}

case "${1:-}" in
build)
	docker build -t "$P-akine-api:qa" "$RAIZ"
	;;
up)
	docker network create "$RED" >/dev/null 2>&1 || true
	docker run -d --name "$P-mysql" --network "$RED" -p "127.0.0.1:$PUERTO_MYSQL:3306" \
		-e MYSQL_DATABASE=akine_local -e MYSQL_USER=akine -e MYSQL_PASSWORD=akine \
		-e MYSQL_ROOT_PASSWORD=root -e TZ=UTC mysql:8.4 \
		--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci \
		--default-time-zone=+00:00 --log-bin-trust-function-creators=1
	docker run -d --name "$P-mailpit" --network "$RED" \
		-p "127.0.0.1:$PUERTO_MAILPIT:8025" -p "127.0.0.1:$PUERTO_SMTP:1025" \
		-e MP_SMTP_AUTH_ACCEPT_ANY=true -e MP_SMTP_AUTH_ALLOW_INSECURE=true \
		axllent/mailpit:v1.21.8
	# Por TCP: el servidor temporal del init del contenedor escucha solo el socket y respondia al ping
	# antes de reiniciarse, y el backend moria con "Communications link failure".
	until docker exec "$P-mysql" mysqladmin ping -h 127.0.0.1 --protocol=TCP -uroot -proot --silent >/dev/null 2>&1; do sleep 2; done
	docker run -d --name "$P-api" --network "$RED" -p "127.0.0.1:$PUERTO_API:8080" \
		-e SPRING_PROFILES_ACTIVE=local \
		-e "AKINE_DB_URL=jdbc:mysql://$P-mysql:3306/akine_local?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC" \
		-e AKINE_DB_USER=akine -e AKINE_DB_PASSWORD=akine \
		-e "AKINE_MAIL_HOST=$P-mailpit" -e AKINE_MAIL_PORT=1025 \
		-e AKINE_BOOTSTRAP_ADMIN_EMAIL="$PLATAFORMA_EMAIL" \
		"$P-akine-api:qa"
	esperar_salud
	;;
down)
	docker rm -f -v "$P-api" "$P-mailpit" "$P-mysql" >/dev/null 2>&1 || true
	docker network rm "$RED" >/dev/null 2>&1 || true
	if [ "${2:-}" = "--imagen" ]; then
		docker rmi "$P-akine-api:qa" >/dev/null 2>&1 || true
	fi
	echo "stack $P borrado"
	;;
*)
	sed -n '2,14p' "$0"
	exit 1
	;;
esac
