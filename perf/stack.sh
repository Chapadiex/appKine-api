#!/usr/bin/env bash
# Stack AISLADO para la prueba de carga (AKINE G-10). Ver docs/carga.md.
#
#   perf/stack.sh build     # imagenes <P>-akine-api:carga (este repo) y <P>-akine-web:carga
#   perf/stack.sh up        # red <P>-net + PostgreSQL 5424 + Mailpit 8040/1040 + backend 8094 + web 4294
#   perf/stack.sh base      # solo la red y PostgreSQL (lo mismo que `up` hasta antes del backend)
#   perf/stack.sh api       # recrea SOLO el backend (p. ej. con otro AKINE_CARGA_POOL), misma base
#   perf/stack.sh sembrar   # k6 sembrado.js  -> perf/salida/sembrado.json
#   perf/stack.sh carga     # k6 carga.js     -> perf/salida/carga-resumen.{md,json} + latencia-servidor.md
#   perf/stack.sh sembrar-actividad  # k6 sembrado-actividad.js (F9, despues de sembrar)
#   perf/stack.sh actividad # k6 actividad.js -> perf/salida/actividad-resumen.{md,json} (F9, 09.03)
#   perf/stack.sh metricas  # digest de pg_stat_statements -> perf/salida/digest.tsv
#   perf/stack.sh historia  # sentencias recientes por conexion (`historia on` antes de la carga)
#   perf/stack.sh por-pedido      # sentencias y transacciones por pedido -> perf/salida/por-pedido.md
#   perf/stack.sh cpu-por-pedido  # ms de CPU del backend por pedido de cada endpoint -> perf/salida/cpu-por-pedido.md
#   perf/stack.sh down      # baja y BORRA todo lo <P>-* (contenedores, volumenes, red; imagenes con --imagenes)
#
# Todo lleva el prefijo <P>- (g10 por defecto) y puertos propios: no toca akine-postgres (5432),
# akine-mailpit, el 8080 ni el 4200 de desarrollo. La base es efimera (volumen anonimo): `down` la borra.
#
# PostgreSQL con la misma imagen que compose.yaml y los tests, inicializado con el mismo
# ops/postgres/init (rol akine sin superusuario, base akine_carga, extension vector). Para medir:
#   - pg_stat_statements (shared_preload_libraries) reemplaza al digest del performance_schema;
#   - log_min_duration_statement=100 es el slow log: va en JSON a $PGDATA/log/postgresql.json,
#     dentro del contenedor, como antes iba a /var/lib/mysql/slow.log.
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
PUERTO_DB="${AKINE_CARGA_PUERTO_DB:-5424}"
IMG_DB="${AKINE_CARGA_IMAGEN_DB:-pgvector/pgvector:0.8.1-pg18-trixie}"
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

# psql como superusuario dentro del contenedor, por socket (el pg_hba de la imagen confia en el
# socket local): las vistas de medicion y ALTER SYSTEM lo exigen. Credenciales sinteticas.
psql_su() {
	docker exec -i "$P-postgres" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d akine_carga "$@"
}

levantar_base() {
	docker network create "$RED" >/dev/null 2>&1 || true
	docker run -d --name "$P-postgres" --network "$RED" -p "127.0.0.1:$PUERTO_DB:5432" \
		-v "$RAIZ/ops/postgres/init:/docker-entrypoint-initdb.d:ro" \
		-e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
		-e AKINE_DB_NAME=akine_carga -e AKINE_DB_USER=akine -e AKINE_DB_PASSWORD=akine \
		-e TZ=UTC -e PGTZ=UTC "$IMG_DB" \
		postgres -c timezone=UTC -c max_connections=300 \
		-c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=all \
		-c pg_stat_statements.max=10000 -c track_io_timing=on \
		-c logging_collector=on -c log_destination=jsonlog -c log_directory=log \
		-c log_filename=postgresql -c log_rotation_age=0 -c log_rotation_size=0
	# Por TCP y con el usuario de la aplicacion en SU base: pg_isready responde durante el arranque
	# temporal del entrypoint, antes de que corra el init y del reinicio final, y el backend que
	# arranca en ese momento muere con "Connection refused".
	until docker exec -e PGPASSWORD=akine "$P-postgres" \
		psql -h 127.0.0.1 -U akine -d akine_carga -XAtqc "SELECT 1" >/dev/null 2>&1; do sleep 2; done
	# El slow log (100 ms) va por ALTER SYSTEM y no por -c: lo que se pasa en la linea de comandos le
	# gana a ALTER SYSTEM, y `historia on` tiene que poder bajarlo a 0 sin reiniciar.
	psql_su -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" \
		-c "ALTER SYSTEM SET log_min_duration_statement = 100" -c "SELECT pg_reload_conf()" >/dev/null
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
		-e "AKINE_DB_URL=jdbc:postgresql://$P-postgres:5432/akine_carga" \
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
base)
	levantar_base
	echo "PostgreSQL $P-postgres en 127.0.0.1:$PUERTO_DB, base akine_carga"
	;;
up)
	levantar_base
	docker run -d --name "$P-mailpit" --network "$RED" \
		-p "127.0.0.1:$PUERTO_MAILPIT_WEB:8025" -p "127.0.0.1:$PUERTO_MAILPIT_SMTP:1025" \
		-e MP_MAX_MESSAGES=2000 -e MP_SMTP_AUTH_ACCEPT_ANY=true -e MP_SMTP_AUTH_ALLOW_INSECURE=true \
		axllent/mailpit:v1.21.8
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
	psql_su -c "SELECT pg_stat_statements_reset()" >/dev/null
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
	psql_su -c "SELECT pg_stat_statements_reset()" >/dev/null
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
	# Sentencias y transacciones por pedido de cada endpoint: llamadas de pg_stat_statements por tipo
	# y conexiones pedidas a Hikari, antes y despues de N pedidos en serie de un solo usuario. Con el
	# backend quieto (sin carga): el resto de lo que corre —el outbox— entra como ruido y es chico.
	# La columna "BEGIN/SET" es la que en MySQL era SET autocommit: con PostgreSQL el driver abre la
	# transaccion con BEGIN, y SET queda para el modo de solo lectura.
	mkdir -p "$PERF/salida"
	N="${AKINE_POR_PEDIDO_N:-100}"
	contadores() {
		# Mismo orden que daba MySQL (Com_commit Com_insert Com_select Com_set_option Com_update
		# Questions), para que el awk de abajo no cambie.
		psql_su -At -F ' ' -c "SELECT
			COALESCE(sum(calls) FILTER (WHERE query ~* '^\s*commit'), 0),
			COALESCE(sum(calls) FILTER (WHERE query ~* '^\s*insert'), 0),
			COALESCE(sum(calls) FILTER (WHERE query ~* '^\s*select'), 0),
			COALESCE(sum(calls) FILTER (WHERE query ~* '^\s*(begin|start|set)'), 0),
			COALESCE(sum(calls) FILTER (WHERE query ~* '^\s*update'), 0),
			COALESCE(sum(calls), 0)
			FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
			WHERE d.datname = 'akine_carga'"
		curl -fsS "http://localhost:$PUERTO_API/actuator/prometheus" \
			| awk '/^hikaricp_connections_acquire_seconds_count\{/ {print $2}'
	}
	k6_por_pedido() {
		docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "${K6_ENV[@]}" \
			-e "AKINE_POR_PEDIDO_ENDPOINT=$1" -e "AKINE_POR_PEDIDO_N=$2" "$K6_IMAGE" run -q /perf/k6/por-pedido.js
	}
	k6_por_pedido token 1 >/dev/null
	{
	echo "| Endpoint | Pedidos | Sentencias | SELECT | INSERT | UPDATE | BEGIN/SET | COMMIT | Conexiones del pool |"
	echo "|---|---:|---:|---:|---:|---:|---:|---:|---:|"
	for e in ${AKINE_POR_PEDIDO_ENDPOINTS:-agenda_dia buscar_slots recepcion_dia paciente_360 cobro reporte reserva login}; do
		n="$N"; [ "$e" = login ] && n=20
		antes=$(contadores | tr '\n' ' ')
		k6_por_pedido "$e" "$n" >&2
		despues=$(contadores | tr '\n' ' ')
		echo "$antes $despues" | awk -v e="$e" -v n="$n" '{
			# commit insert select begin/set update total; despues, las conexiones pedidas a Hikari.
			c=$8-$1; i=$9-$2; s=$10-$3; so=$11-$4; u=$12-$5; q=$13-$6; h=$14-$7
			printf "| %s | %d | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |\n", e, n, q/n, s/n, i/n, u/n, so/n, c/n, h/n }'
	done
	} | tee "$PERF/salida/por-pedido.md"
	;;
cpu-por-pedido)
	# Milisegundos de CPU del backend por pedido de cada endpoint (docs/carga.md §13): un usuario,
	# en serie, con el backend quieto. Primero una tanda de calentamiento (JIT), despues N pedidos
	# entre dos lecturas de process_cpu_time_ns_total. Al total se le resta lo que el proceso gasta
	# quieto (outbox, jobs, el propio scrape), medido antes durante 20 s y prorrateado por la
	# duracion de cada tanda. AKINE_POR_PEDIDO_DESDE_RESERVA corre los horarios de la reserva para
	# que una segunda pasada sobre la misma base siga ganando.
	mkdir -p "$PERF/salida"
	N="${AKINE_POR_PEDIDO_N:-300}"
	DESDE="${AKINE_POR_PEDIDO_DESDE_RESERVA:-0}"
	cpu_ns() {
		curl -fsS "http://localhost:$PUERTO_API/actuator/prometheus" \
			| awk '/^process_cpu_time_ns_total\{/ {printf "%.0f", $2}'
	}
	ahora_ms() { date +%s%3N; }
	k6_cpu() {
		docker run --rm --network "$RED" -v "$(montaje_perf):/perf" "${K6_ENV[@]}" \
			-e "AKINE_POR_PEDIDO_ENDPOINT=$1" -e "AKINE_POR_PEDIDO_N=$2" \
			-e "AKINE_POR_PEDIDO_DESDE_RESERVA=$3" "$K6_IMAGE" run -q /perf/k6/por-pedido.js >&2
	}
	k6_cpu token 1 0
	c0=$(cpu_ns); t0=$(ahora_ms); sleep 20; c1=$(cpu_ns); t1=$(ahora_ms)
	QUIETO=$(awk -v c="$((c1 - c0))" -v t="$((t1 - t0))" 'BEGIN {printf "%.6f", c / t}')
	{
	echo "| Endpoint | Pedidos | ms de CPU por pedido |"
	echo "|---|---:|---:|"
	for e in ${AKINE_POR_PEDIDO_ENDPOINTS:-agenda_dia buscar_slots recepcion_dia paciente_360 cobro reporte reserva login}; do
		n="$N"; [ "$e" = login ] && n=20
		if [ "$e" != login ]; then
			k6_cpu "$e" "$((n / 3))" "$DESDE"; DESDE=$((DESDE + n / 3))
		fi
		c0=$(cpu_ns); t0=$(ahora_ms)
		k6_cpu "$e" "$n" "$DESDE"; [ "$e" = reserva ] && DESDE=$((DESDE + n))
		c1=$(cpu_ns); t1=$(ahora_ms)
		awk -v e="$e" -v n="$n" -v c="$((c1 - c0))" -v t="$((t1 - t0))" -v q="$QUIETO" \
			'BEGIN {printf "| %s | %d | %.2f |\n", e, n, (c - q * t) / n / 1e6}'
	done
	echo
	echo "Quieto: $(awk -v q="$QUIETO" 'BEGIN {printf "%.1f", q / 1e3}') ms de CPU por segundo. Siguiente AKINE_POR_PEDIDO_DESDE_RESERVA=$DESDE"
	} | tee "$PERF/salida/cpu-por-pedido.md"
	;;
historia)
	# Las ultimas 10 000 sentencias de todas las conexiones, para medir que corre bajo un lock con
	# perf/seccion-critica.mjs (docs/carga.md §10). PostgreSQL no tiene un equivalente de
	# events_statements_history_long: `historia on` (antes de la carga) baja
	# log_min_duration_statement a 0, y el log JSON pasa a tener cada sentencia con su duracion; sin
	# argumento, vuelca una foto con la hora en el nombre, en el mismo TSV que antes (conexion,
	# orden, inicio, fin en picosegundos relativos a la foto, texto de 90 caracteres), que es lo que
	# lee seccion-critica.mjs. Se toman las lineas `execute` y `statement`: el parse y el bind del
	# protocolo extendido quedan afuera (son cortos y contarlos triplicaria cada sentencia).
	mkdir -p "$PERF/salida"
	if [ "${2:-}" = "on" ]; then
		psql_su -c "ALTER SYSTEM SET log_min_duration_statement = 0" -c "SELECT pg_reload_conf()" >/dev/null
		exit 0
	fi
	docker exec "$P-postgres" sh -c 'cat "$PGDATA/log/postgresql.json"' | node -e '
		const lineas = require("fs").readFileSync(0, "utf8").split("\n");
		const re = /^duration: ([\d.]+) ms  (?:statement|execute [^:]*): ([\s\S]*)$/;
		const filas = [];
		for (const l of lineas) {
			if (!l) continue;
			let r; try { r = JSON.parse(l); } catch { continue; }
			const m = re.exec(r.message || "");
			if (!m) continue;
			const finMs = Date.parse(r.timestamp.replace(" UTC", "Z").replace(" ", "T"));
			filas.push({ pid: r.pid, finMs, durMs: Number(m[1]), texto: m[2].replace(/\s+/g, " ").trim().slice(0, 90) });
		}
		const ultimas = filas.slice(-10000);
		const t0 = Math.min(...ultimas.map((f) => f.finMs - f.durMs));
		const ps = (ms) => Math.round((ms - t0) * 1e9);
		ultimas.map((f, i) => ({ ...f, i })).sort((a, b) => a.pid - b.pid || a.i - b.i)
			.forEach((f) => console.log([f.pid, f.i, ps(f.finMs - f.durMs), ps(f.finMs), f.texto].join("\t")));
	' >"$PERF/salida/historia-$(date +%H%M%S).tsv"
	;;
metricas)
	# Las 40 consultas que mas tiempo sumaron, de pg_stat_statements (lo vacian `carga` y `actividad`
	# antes de correr). Lo que el digest de MySQL daba y PostgreSQL no lleva por sentencia —filas
	# examinadas, uso sin indice, tiempo de lock— se reemplaza por bloques leidos de cache y de disco
	# y tiempo de E/S; el plan de una consulta sospechosa se mira con EXPLAIN (ANALYZE, BUFFERS).
	mkdir -p "$PERF/salida"
	psql_su -A -F "	" -P footer=off -c "
		SELECT left(regexp_replace(query, '\s+', ' ', 'g'), 160) AS consulta, calls AS veces,
		       round((total_exec_time / 1e3)::numeric, 2) AS total_s, round(mean_exec_time::numeric, 2) AS prom_ms,
		       rows AS filas, shared_blks_hit AS bloques_cache, shared_blks_read AS bloques_disco,
		       round(((shared_blk_read_time + shared_blk_write_time) / 1e3)::numeric, 2) AS io_s
		FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
		WHERE d.datname = 'akine_carga'
		ORDER BY total_exec_time DESC LIMIT 40" >"$PERF/salida/digest.tsv"
	echo "perf/salida/digest.tsv"
	;;
down)
	# -v: el volumen anonimo de /var/lib/postgresql se va con el contenedor.
	docker rm -f -v "$P-web" "$P-api" "$P-mailpit" "$P-postgres" >/dev/null 2>&1 || true
	docker network rm "$RED" >/dev/null 2>&1 || true
	if [ "${2:-}" = "--imagenes" ]; then
		docker rmi "$IMG_API" "$IMG_WEB" >/dev/null 2>&1 || true
	fi
	echo "stack $P borrado"
	;;
*)
	sed -n '2,26p' "$0"
	exit 1
	;;
esac
