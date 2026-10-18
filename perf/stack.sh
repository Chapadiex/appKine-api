#!/usr/bin/env bash
# Stack AISLADO para la prueba de carga (AKINE G-10). Ver docs/carga.md.
#
#   perf/stack.sh build     # imagenes <P>-akine-api:carga (este repo) y <P>-akine-web:carga
#   perf/stack.sh up        # red <P>-net + MySQL 3324 + Mailpit 8040/1040 + backend 8094 + web 4294
#   perf/stack.sh api       # recrea SOLO el backend (p. ej. con otro AKINE_CARGA_POOL), misma base
#   perf/stack.sh sembrar   # k6 sembrado.js  -> perf/salida/sembrado.json
#   perf/stack.sh carga     # k6 carga.js     -> perf/salida/carga-resumen.{md,json} + latencia-servidor.md
#   perf/stack.sh sembrar-actividad  # k6 sembrado-actividad.js (F9, despues de sembrar)
#   perf/stack.sh actividad # k6 actividad.js -> perf/salida/actividad-resumen.{md,json} (F9, 09.03)
#   perf/stack.sh metricas  # digest del performance_schema -> perf/salida/digest.tsv
#   perf/stack.sh historia  # sentencias recientes por conexion (`historia on` antes de la carga)
#   perf/stack.sh down      # baja y BORRA todo lo <P>-* (contenedores, volumenes, red; imagenes con --imagenes)
#
# Todo lleva el prefijo <P>- (g10 por defecto) y puertos propios: no toca akine-mysql,
# akine-mailpit, el 8080 ni el 4200 de desarrollo. La base es efimera (volumen anonimo): `down` la borra.
set -euo pipefail

# En Git Bash, `pwd -W` da C:/...: con MSYS_NO_PATHCONV, docker build no encuentra /c/...
RAIZ="$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })"
PERF="$RAIZ/perf"
WEB_DIR="${AKINE_WEB_DIR:-$RAIZ/../appKine-web}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.3.0}"
PLATAFORMA_EMAIL="${AKINE_CARGA_PLATAFORMA_EMAIL:-plataforma.carga@ejemplo.test}"

# Prefijo y puertos: el default es el de G-10. Otra corrida en paralelo usa los suyos
# (p. ej. AKINE_CARGA_PREFIJO=perf2 AKINE_CARGA_PUERTO_API=8098 ...) sin pisar este stack.
P="${AKINE_CARGA_PREFIJO:-g10}"
PUERTO_MYSQL="${AKINE_CARGA_PUERTO_MYSQL:-3324}"
PUERTO_MAILPIT_WEB="${AKINE_CARGA_PUERTO_MAILPIT_WEB:-8040}"
PUERTO_MAILPIT_SMTP="${AKINE_CARGA_PUERTO_MAILPIT_SMTP:-1040}"
PUERTO_API="${AKINE_CARGA_PUERTO_API:-8094}"
PUERTO_WEB="${AKINE_CARGA_PUERTO_WEB:-4294}"
RED="$P-net"
IMG_API="${AKINE_CARGA_IMAGEN_API:-$P-akine-api:carga}"
IMG_WEB="$P-akine-web:carga"
# Opciones extra de la JVM, que se suman al JAVA_OPTS de la imagen (p. ej. una grabacion JFR).
JAVA_EXTRA="${AKINE_CARGA_JAVA_OPTS:-}"
# k6 encuentra al backend y a Mailpit por el nombre del contenedor dentro de la red.
K6_ENV=(-e "AKINE_API=http://$P-api:8080" -e "AKINE_MAILPIT=http://$P-mailpit:8025")

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
		if curl -fsS "http://localhost:$PUERTO_API/actuator/health/readiness" >/dev/null 2>&1; then
			echo "backend listo"
			return 0
		fi
		sleep 5
	done
	echo "el backend no arranco en 15 minutos (el primer arranque aplica todas las migraciones): docker logs $P-api" >&2
	return 1
}

levantar_api() {
	local extra=()
	if [ -n "$POOL" ]; then
		extra+=(-e "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$POOL")
	fi
	if [ -n "$JAVA_EXTRA" ]; then
		extra+=(-e "JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError $JAVA_EXTRA")
	fi
	# Perfil local (scrape abierto, SMTP a Mailpit sin TLS) pero SIN el DEBUG de com.akine que
	# trae application-local.yml: loguear cada request a nivel DEBUG falsearia la latencia.
	docker run -d --name "$P-api" --network "$RED" -p "127.0.0.1:$PUERTO_API:8080" \
		--cpus "$API_CPUS" --memory "$API_MEMORIA" \
		-e SPRING_PROFILES_ACTIVE=local \
		-e LOGGING_LEVEL_COM_AKINE=INFO \
		-e "AKINE_DB_URL=jdbc:mysql://$P-mysql:3306/akine_carga?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC" \
		-e AKINE_DB_USER=akine -e AKINE_DB_PASSWORD=akine \
		-e AKINE_MAIL_HOST="$P-mailpit" -e AKINE_MAIL_PORT=1025 \
		-e AKINE_PUBLIC_BASE_URL="http://localhost:$PUERTO_WEB" \
		-e AKINE_CORS_ORIGINS="http://localhost:$PUERTO_WEB" \
		-e AKINE_BOOTSTRAP_ADMIN_EMAIL="$PLATAFORMA_EMAIL" \
		${extra[@]+"${extra[@]}"} \
		"$IMG_API"
	esperar_salud
}

case "${1:-}" in
build)
	docker build -t "$IMG_API" "$RAIZ"
	if [ "${2:-}" = "--solo-api" ]; then
		:
	elif [ -d "$WEB_DIR" ]; then
		docker build -t "$IMG_WEB" "$WEB_DIR"
	else
		echo "sin $WEB_DIR: no se construye el frontend (solo hace falta para el LCP)"
	fi
	;;
up)
	docker network create "$RED" >/dev/null 2>&1 || true
	docker run -d --name "$P-mysql" --network "$RED" -p "127.0.0.1:$PUERTO_MYSQL:3306" \
		-e MYSQL_DATABASE=akine_carga -e MYSQL_USER=akine -e MYSQL_PASSWORD=akine \
		-e MYSQL_ROOT_PASSWORD=root -e TZ=UTC mysql:8.4 \
		--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci \
		--default-time-zone=+00:00 --log-bin-trust-function-creators=1 \
		--max-connections=300 \
		--slow-query-log=1 --long-query-time=0.1 --slow-query-log-file=/var/lib/mysql/slow.log
	docker run -d --name "$P-mailpit" --network "$RED" \
		-p "127.0.0.1:$PUERTO_MAILPIT_WEB:8025" -p "127.0.0.1:$PUERTO_MAILPIT_SMTP:1025" \
		-e MP_MAX_MESSAGES=2000 -e MP_SMTP_AUTH_ACCEPT_ANY=true -e MP_SMTP_AUTH_ALLOW_INSECURE=true \
		axllent/mailpit:v1.21.8
	# Por TCP y con el usuario de la aplicacion: el ping por socket responde durante la
	# inicializacion del contenedor, cuando el servidor todavia no acepta conexiones de red, y el
	# backend que arranca en ese momento muere con "Connection refused".
	until docker exec "$P-mysql" mysql -h127.0.0.1 -uakine -pakine akine_carga -e "SELECT 1" >/dev/null 2>&1; do sleep 2; done
	if docker image inspect "$IMG_WEB" >/dev/null 2>&1; then
		docker run -d --name "$P-web" --network "$RED" -p "127.0.0.1:$PUERTO_WEB:8080" \
			-e AKINE_API_URL="http://$P-api:8080" "$IMG_WEB"
	else
		echo "sin imagen $IMG_WEB: no se levanta el frontend (solo hace falta para el LCP)"
	fi
	levantar_api
	;;
api)
	docker rm -f "$P-api" >/dev/null 2>&1 || true
	levantar_api
	;;
sembrar)
	mkdir -p "$PERF/salida"
	docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "${K6_ENV[@]}" "$K6_IMAGE" run -q /perf/k6/sembrado.js
	;;
sembrar-actividad)
	# F9 (09.03), encima del centro de `sembrar`: oferta grupal, pack, pases y clases.
	docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "${K6_ENV[@]}" "$K6_IMAGE" run -q /perf/k6/sembrado-actividad.js
	;;
actividad)
	# Carga de F9 (09.03): inscribir, asistencia en lote, venta de pases y lecturas. Igual que
	# `carga`, con la latencia del lado del servidor restando dos fotos de Prometheus.
	shift
	mkdir -p "$PERF/salida"
	docker exec "$P-mysql" mysql -uroot -proot -e "TRUNCATE performance_schema.events_statements_summary_by_digest" 2>/dev/null
	curl -fsS "http://localhost:$PUERTO_API/actuator/prometheus" >"$PERF/salida/prometheus-antes.txt"
	docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "${K6_ENV[@]}" "$@" "$K6_IMAGE" run -q /perf/k6/actividad.js || true
	curl -fsS "http://localhost:$PUERTO_API/actuator/prometheus" >"$PERF/salida/prometheus-despues.txt"
	(cd "$PERF" && node prometheus-latencia.mjs salida/prometheus-antes.txt salida/prometheus-despues.txt) \
		| tee "$PERF/salida/latencia-servidor-actividad.md"
	;;
carga)
	shift
	mkdir -p "$PERF/salida"
	# El digest de consultas y la foto de Prometheus cubren SOLO la corrida, no el sembrado.
	docker exec "$P-mysql" mysql -uroot -proot -e "TRUNCATE performance_schema.events_statements_summary_by_digest" 2>/dev/null
	curl -fsS "http://localhost:$PUERTO_API/actuator/prometheus" >"$PERF/salida/prometheus-antes.txt"
	# Muestreo de saturacion cada 5 s mientras corre k6: pool de conexiones, hilos de la JVM y CPU
	# del proceso. Si el backend esta trabado, la propia foto tarda: la hora real queda en la fila.
	(
		echo "hora hikari_activas hikari_pendientes jvm_hilos_vivos cpu_proceso"
		while true; do
			m=$(curl -fsS --max-time 60 "http://localhost:$PUERTO_API/actuator/prometheus" 2>/dev/null) || break
			echo "$(date +%T) $(echo "$m" | awk '/^hikaricp_connections_active\{/ {a=$2} /^hikaricp_connections_pending\{/ {p=$2} /^jvm_threads_live_threads\{/ {t=$2} /^process_cpu_usage\{/ {c=$2} END {print a, p, t, c}')"
			sleep 5
		done
	) >"$PERF/salida/saturacion.txt" &
	MUESTREO=$!
	docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "${K6_ENV[@]}" "$@" "$K6_IMAGE" run -q /perf/k6/carga.js || true
	kill "$MUESTREO" 2>/dev/null || true
	curl -fsS "http://localhost:$PUERTO_API/actuator/prometheus" >"$PERF/salida/prometheus-despues.txt"
	# Rutas relativas: en Git Bash, con MSYS_NO_PATHCONV, node recibiria /c/... sin traducir.
	(cd "$PERF" && node prometheus-latencia.mjs salida/prometheus-antes.txt salida/prometheus-despues.txt) \
		| tee "$PERF/salida/latencia-servidor.md"
	;;
por-pedido)
	# Sentencias y transacciones por pedido de cada endpoint: contadores de MySQL y conexiones
	# pedidas a Hikari, antes y despues de N pedidos en serie de un solo usuario. Con el backend
	# quieto (sin carga): el resto de lo que corre —el outbox— entra como ruido y es chico.
	mkdir -p "$PERF/salida"
	N="${AKINE_POR_PEDIDO_N:-100}"
	contadores() {
		docker exec "$P-mysql" mysql -uroot -proot -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN
			('Questions','Com_select','Com_insert','Com_update','Com_commit','Com_set_option')" 2>/dev/null \
			| awk '{printf "%s ", $2} END {print ""}'
		curl -fsS "http://localhost:$PUERTO_API/actuator/prometheus" \
			| awk '/^hikaricp_connections_acquire_seconds_count\{/ {print $2}'
	}
	k6_por_pedido() {
		docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "${K6_ENV[@]}" \
			-e "AKINE_POR_PEDIDO_ENDPOINT=$1" -e "AKINE_POR_PEDIDO_N=$2" "$K6_IMAGE" run -q /perf/k6/por-pedido.js
	}
	k6_por_pedido token 1 >/dev/null
	{
	echo "| Endpoint | Pedidos | Sentencias | SELECT | INSERT | UPDATE | SET | COMMIT | Conexiones del pool |"
	echo "|---|---:|---:|---:|---:|---:|---:|---:|---:|"
	for e in ${AKINE_POR_PEDIDO_ENDPOINTS:-agenda_dia buscar_slots recepcion_dia paciente_360 cobro reporte reserva login}; do
		n="$N"; [ "$e" = login ] && n=20
		antes=$(contadores | tr '\n' ' ')
		k6_por_pedido "$e" "$n" >&2
		despues=$(contadores | tr '\n' ' ')
		echo "$antes $despues" | awk -v e="$e" -v n="$n" '{
			# MySQL devuelve las variables en orden alfabetico: Com_commit Com_insert Com_select
			# Com_set_option Com_update Questions; despues, las conexiones pedidas a Hikari.
			c=$8-$1; i=$9-$2; s=$10-$3; so=$11-$4; u=$12-$5; q=$13-$6; h=$14-$7
			printf "| %s | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |\n", e, n, q/n, s/n, i/n, u/n, so/n, c/n, h/n }'
	done
	} | tee "$PERF/salida/por-pedido.md"
	;;
historia)
	# Las ultimas 10 000 sentencias de todas las conexiones (events_statements_history_long), para
	# medir que corre bajo un lock con perf/seccion-critica.mjs (docs/carga.md §10). `historia on`
	# habilita el consumidor (antes de la carga); sin argumento, vuelca una foto con la hora en el nombre.
	mkdir -p "$PERF/salida"
	if [ "${2:-}" = "on" ]; then
		docker exec "$P-mysql" mysql -uroot -proot -e "UPDATE performance_schema.setup_consumers
			SET ENABLED = 'YES' WHERE NAME = 'events_statements_history_long'" 2>/dev/null
		exit 0
	fi
	docker exec "$P-mysql" mysql -uroot -proot -N -e "
		SELECT THREAD_ID, EVENT_ID, TIMER_START, TIMER_END, LEFT(REPLACE(DIGEST_TEXT, CHAR(9), ' '), 90)
		FROM performance_schema.events_statements_history_long ORDER BY THREAD_ID, EVENT_ID" 2>/dev/null \
		>"$PERF/salida/historia-$(date +%H%M%S).tsv"
	;;
metricas)
	mkdir -p "$PERF/salida"
	docker exec "$P-mysql" mysql -uroot -proot -e "
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
	docker rm -f -v "$P-web" "$P-api" "$P-mailpit" "$P-mysql" >/dev/null 2>&1 || true
	docker network rm "$RED" >/dev/null 2>&1 || true
	if [ "${2:-}" = "--imagenes" ]; then
		docker rmi "$IMG_API" "$IMG_WEB" >/dev/null 2>&1 || true
	fi
	echo "stack $P borrado"
	;;
*)
	sed -n '2,16p' "$0"
	exit 1
	;;
esac
