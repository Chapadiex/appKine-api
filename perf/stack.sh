#!/usr/bin/env bash
# Stack AISLADO para la prueba de carga (AKINE G-10). Ver docs/carga.md.
#
#   perf/stack.sh build     # imagenes g10-akine-api:carga (este repo) y g10-akine-web:carga
#   perf/stack.sh up        # red g10-net + MySQL 3324 + Mailpit 8040/1040 + backend 8094 + web 4294
#   perf/stack.sh api       # recrea SOLO el backend (p. ej. con otro AKINE_CARGA_POOL), misma base
#   perf/stack.sh sembrar   # k6 sembrado.js  -> perf/salida/sembrado.json
#   perf/stack.sh carga     # k6 carga.js     -> perf/salida/carga-resumen.{md,json} + latencia-servidor.md
#   perf/stack.sh metricas  # digest del performance_schema -> perf/salida/digest.tsv
#   perf/stack.sh down      # baja y BORRA todo lo g10-* (contenedores, volumenes, red; imagenes con --imagenes)
#
# Todo lleva el prefijo g10- y puertos propios: no toca akine-mysql, akine-mailpit, el 8080 ni el
# 4200 de desarrollo. La base es efimera (volumen anonimo): `down` la borra.
set -euo pipefail

# En Git Bash, `pwd -W` da C:/...: con MSYS_NO_PATHCONV, docker build no encuentra /c/...
RAIZ="$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })"
PERF="$RAIZ/perf"
WEB_DIR="${AKINE_WEB_DIR:-$RAIZ/../appKine-web}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.3.0}"
RED=g10-net
PLATAFORMA_EMAIL="${AKINE_CARGA_PLATAFORMA_EMAIL:-plataforma.carga@ejemplo.test}"

# Recursos del backend: fijos para que dos corridas sean comparables entre si.
API_CPUS="${AKINE_CARGA_API_CPUS:-4}"
API_MEMORIA="${AKINE_CARGA_API_MEMORIA:-2g}"
# Tamano del pool de Hikari. Vacio = el default de la aplicacion (10), que es lo que se despliega.
POOL="${AKINE_CARGA_POOL:-}"

# En Git Bash de Windows, sin esto docker recibe C:/... reescrito como ruta de MSYS.
export MSYS_NO_PATHCONV=1
montaje_perf() {
	if command -v cygpath >/dev/null 2>&1; then cygpath -w "$PERF"; else echo "$PERF"; fi
}

esperar_salud() {
	local i
	for i in $(seq 1 180); do
		if curl -fsS "http://localhost:8094/actuator/health/readiness" >/dev/null 2>&1; then
			echo "backend listo"
			return 0
		fi
		sleep 5
	done
	echo "el backend no arranco en 15 minutos (el primer arranque aplica todas las migraciones): docker logs g10-api" >&2
	return 1
}

levantar_api() {
	local extra=()
	if [ -n "$POOL" ]; then
		extra+=(-e "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$POOL")
	fi
	# Perfil local (scrape abierto, SMTP a Mailpit sin TLS) pero SIN el DEBUG de com.akine que
	# trae application-local.yml: loguear cada request a nivel DEBUG falsearia la latencia.
	docker run -d --name g10-api --network "$RED" -p 127.0.0.1:8094:8080 \
		--cpus "$API_CPUS" --memory "$API_MEMORIA" \
		-e SPRING_PROFILES_ACTIVE=local \
		-e LOGGING_LEVEL_COM_AKINE=INFO \
		-e 'AKINE_DB_URL=jdbc:mysql://g10-mysql:3306/akine_carga?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC' \
		-e AKINE_DB_USER=akine -e AKINE_DB_PASSWORD=akine \
		-e AKINE_MAIL_HOST=g10-mailpit -e AKINE_MAIL_PORT=1025 \
		-e AKINE_PUBLIC_BASE_URL=http://localhost:4294 \
		-e AKINE_CORS_ORIGINS=http://localhost:4294 \
		-e AKINE_BOOTSTRAP_ADMIN_EMAIL="$PLATAFORMA_EMAIL" \
		"${extra[@]}" \
		g10-akine-api:carga
	esperar_salud
}

case "${1:-}" in
build)
	docker build -t g10-akine-api:carga "$RAIZ"
	if [ -d "$WEB_DIR" ]; then
		docker build -t g10-akine-web:carga "$WEB_DIR"
	else
		echo "sin $WEB_DIR: no se construye el frontend (solo hace falta para el LCP)"
	fi
	;;
up)
	docker network create "$RED" >/dev/null 2>&1 || true
	docker run -d --name g10-mysql --network "$RED" -p 127.0.0.1:3324:3306 \
		-e MYSQL_DATABASE=akine_carga -e MYSQL_USER=akine -e MYSQL_PASSWORD=akine \
		-e MYSQL_ROOT_PASSWORD=root -e TZ=UTC mysql:8.4 \
		--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci \
		--default-time-zone=+00:00 --log-bin-trust-function-creators=1 \
		--max-connections=300 \
		--slow-query-log=1 --long-query-time=0.1 --slow-query-log-file=/var/lib/mysql/slow.log
	docker run -d --name g10-mailpit --network "$RED" \
		-p 127.0.0.1:8040:8025 -p 127.0.0.1:1040:1025 \
		-e MP_MAX_MESSAGES=2000 -e MP_SMTP_AUTH_ACCEPT_ANY=true -e MP_SMTP_AUTH_ALLOW_INSECURE=true \
		axllent/mailpit:v1.21.8
	# Por TCP y con el usuario de la aplicacion: el ping por socket responde durante la
	# inicializacion del contenedor, cuando el servidor todavia no acepta conexiones de red, y el
	# backend que arranca en ese momento muere con "Connection refused".
	until docker exec g10-mysql mysql -h127.0.0.1 -uakine -pakine akine_carga -e "SELECT 1" >/dev/null 2>&1; do sleep 2; done
	if docker image inspect g10-akine-web:carga >/dev/null 2>&1; then
		docker run -d --name g10-web --network "$RED" -p 127.0.0.1:4294:8080 \
			-e AKINE_API_URL=http://g10-api:8080 g10-akine-web:carga
	else
		echo "sin imagen g10-akine-web:carga: no se levanta el frontend (solo hace falta para el LCP)"
	fi
	levantar_api
	;;
api)
	docker rm -f g10-api >/dev/null 2>&1 || true
	levantar_api
	;;
sembrar)
	mkdir -p "$PERF/salida"
	docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "$K6_IMAGE" run -q /perf/k6/sembrado.js
	;;
carga)
	shift
	mkdir -p "$PERF/salida"
	# El digest de consultas y la foto de Prometheus cubren SOLO la corrida, no el sembrado.
	docker exec g10-mysql mysql -uroot -proot -e "TRUNCATE performance_schema.events_statements_summary_by_digest" 2>/dev/null
	curl -fsS http://localhost:8094/actuator/prometheus >"$PERF/salida/prometheus-antes.txt"
	# Muestreo de saturacion cada 5 s mientras corre k6: pool de conexiones, hilos de la JVM y CPU
	# del proceso. Si el backend esta trabado, la propia foto tarda: la hora real queda en la fila.
	(
		echo "hora hikari_activas hikari_pendientes jvm_hilos_vivos cpu_proceso"
		while true; do
			m=$(curl -fsS --max-time 60 http://localhost:8094/actuator/prometheus 2>/dev/null) || break
			echo "$(date +%T) $(echo "$m" | awk '/^hikaricp_connections_active\{/ {a=$2} /^hikaricp_connections_pending\{/ {p=$2} /^jvm_threads_live_threads\{/ {t=$2} /^process_cpu_usage\{/ {c=$2} END {print a, p, t, c}')"
			sleep 5
		done
	) >"$PERF/salida/saturacion.txt" &
	MUESTREO=$!
	docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "$@" "$K6_IMAGE" run -q /perf/k6/carga.js || true
	kill "$MUESTREO" 2>/dev/null || true
	curl -fsS http://localhost:8094/actuator/prometheus >"$PERF/salida/prometheus-despues.txt"
	# Rutas relativas: en Git Bash, con MSYS_NO_PATHCONV, node recibiria /c/... sin traducir.
	(cd "$PERF" && node prometheus-latencia.mjs salida/prometheus-antes.txt salida/prometheus-despues.txt) \
		| tee "$PERF/salida/latencia-servidor.md"
	;;
metricas)
	mkdir -p "$PERF/salida"
	docker exec g10-mysql mysql -uroot -proot -e "
		SELECT LEFT(DIGEST_TEXT, 160) AS consulta, COUNT_STAR AS veces,
		       ROUND(SUM_TIMER_WAIT/1e12, 2) AS total_s, ROUND(AVG_TIMER_WAIT/1e9, 2) AS prom_ms,
		       SUM_ROWS_EXAMINED AS filas_examinadas, SUM_ROWS_SENT AS filas_enviadas,
		       SUM_NO_INDEX_USED AS sin_indice, ROUND(SUM_LOCK_TIME/1e12, 2) AS lock_s
		FROM performance_schema.events_statements_summary_by_digest
		WHERE SCHEMA_NAME = 'akine_carga'
		ORDER BY SUM_TIMER_WAIT DESC LIMIT 40" >"$PERF/salida/digest.tsv"
	echo "perf/salida/digest.tsv"
	;;
down)
	# -v: el volumen anonimo de /var/lib/mysql se va con el contenedor.
	docker rm -f -v g10-web g10-api g10-mailpit g10-mysql >/dev/null 2>&1 || true
	docker network rm "$RED" >/dev/null 2>&1 || true
	if [ "${2:-}" = "--imagenes" ]; then
		docker rmi g10-akine-api:carga g10-akine-web:carga >/dev/null 2>&1 || true
	fi
	echo "stack g10 borrado"
	;;
*)
	sed -n '2,13p' "$0"
	exit 1
	;;
esac
