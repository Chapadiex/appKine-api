# syntax=docker/dockerfile:1
#
# Imagen del backend de AKINE (G-3).
#
#   docker build -t akine-api:local .
#
# Dos etapas: la primera compila con el mvnw del repo sobre un JDK 21; la segunda corre el jar
# en capas sobre un JRE 21, con un usuario sin privilegios. Los tests NO corren aca: corren en
# el CI, que es el que tiene Docker para Testcontainers y el gate de drift del contrato.
#
# La imagen NO lleva configuracion de entorno ni secretos: base de datos, secreto del JWT, SMTP,
# CORS y perfil llegan por variable de entorno al arrancar (ver application.yml y la seccion
# "Imagen Docker" de AGENT.md).

# ============================================================================================
# Etapa 1 — build
# ============================================================================================
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Capa de dependencias: solo cambia cuando cambia el pom.xml o el wrapper. Un cambio de codigo
# reutiliza esta capa y no vuelve a bajar Maven ni el repositorio entero.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B --no-transfer-progress dependency:go-offline

# Capa de codigo. El contrato openapi/ no se copia: no participa del package, solo del gate de
# drift, que corre en el CI.
COPY src/ src/
RUN ./mvnw -B --no-transfer-progress -DskipTests package \
	&& cp target/akine-api-*.jar application.jar \
	&& java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

# ============================================================================================
# Etapa 2 — runtime
# ============================================================================================
FROM eclipse-temurin:21-jre AS runtime

# Usuario sin privilegios, con UID fijo para que los permisos de un volumen montado sean
# predecibles. El proceso nunca corre como root.
RUN groupadd --system --gid 10001 akine \
	&& useradd --system --uid 10001 --gid akine --home-dir /app --shell /usr/sbin/nologin akine

WORKDIR /app

# Capas del jar, de la que menos cambia a la que mas: las dependencias de terceros quedan en
# capas que un cambio de codigo no invalida.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

# Modelo de embeddings de la biblioteca (M30), OPCIONAL: sólo si al construir se pasan las dos URL
#   docker build --build-arg AKINE_BIBLIOTECA_MODELO_URL=... --build-arg AKINE_BIBLIOTECA_TOKENIZER_URL=... .
# Sin ellas la imagen queda exactamente como antes y la biblioteca sigue apagada. Con ellas, el
# modelo ONNX cuantizado y su tokenizer.json quedan en /opt/akine/modelos/e5-base/ y se activan con
# AKINE_BIBLIOTECA_ENABLED=true, AKINE_BIBLIOTECA_MODELO_RUTAMODELO=/opt/akine/modelos/e5-base/model.onnx
# y AKINE_BIBLIOTECA_MODELO_RUTATOKENIZER=/opt/akine/modelos/e5-base/tokenizer.json.
# Pasar una sola de las dos URL corta el build: antes se salteaba en silencio y la imagen salia sin
# modelo. Opcionalmente, AKINE_BIBLIOTECA_MODELO_SHA256 y AKINE_BIBLIOTECA_TOKENIZER_SHA256 fijan el
# hash esperado de cada archivo y el build falla si no coincide (sha256sum -c).
ARG AKINE_BIBLIOTECA_MODELO_URL=""
ARG AKINE_BIBLIOTECA_TOKENIZER_URL=""
ARG AKINE_BIBLIOTECA_MODELO_SHA256=""
ARG AKINE_BIBLIOTECA_TOKENIZER_SHA256=""
RUN if [ -n "$AKINE_BIBLIOTECA_MODELO_URL" ] && [ -n "$AKINE_BIBLIOTECA_TOKENIZER_URL" ]; then \
		mkdir -p /opt/akine/modelos/e5-base \
		&& curl -fsSL "$AKINE_BIBLIOTECA_MODELO_URL" -o /opt/akine/modelos/e5-base/model.onnx \
		&& curl -fsSL "$AKINE_BIBLIOTECA_TOKENIZER_URL" -o /opt/akine/modelos/e5-base/tokenizer.json \
		&& if [ -n "$AKINE_BIBLIOTECA_MODELO_SHA256" ]; then \
			echo "$AKINE_BIBLIOTECA_MODELO_SHA256  /opt/akine/modelos/e5-base/model.onnx" | sha256sum -c -; \
		fi \
		&& if [ -n "$AKINE_BIBLIOTECA_TOKENIZER_SHA256" ]; then \
			echo "$AKINE_BIBLIOTECA_TOKENIZER_SHA256  /opt/akine/modelos/e5-base/tokenizer.json" | sha256sum -c -; \
		fi \
		&& chmod -R a+rX /opt/akine/modelos; \
	elif [ -n "$AKINE_BIBLIOTECA_MODELO_URL" ] || [ -n "$AKINE_BIBLIOTECA_TOKENIZER_URL" ]; then \
		echo "Faltan datos del modelo de la biblioteca: hay que pasar AKINE_BIBLIOTECA_MODELO_URL y AKINE_BIBLIOTECA_TOKENIZER_URL juntas" >&2; \
		exit 1; \
	fi

# Raiz de los adjuntos administrativos (./var/adjuntos) y clinicos (./var/adjuntos-clinicos).
# Es lo unico en lo que la aplicacion escribe. En un despliegue real se monta un volumen sobre
# /app/var —o uno por raiz, que es para lo que estan separadas—: sin volumen, los binarios se
# pierden con el contenedor.
RUN mkdir -p /app/var && chown -R akine:akine /app/var

USER akine:akine

# Observabilidad (G-4): el log sale en JSON por stdout (fuera del perfil local), las metricas en
# /actuator/prometheus —cerrado salvo AKINE_METRICS_SCRAPE_TOKEN— y las trazas se exportan
# solo si se define MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT. Nada de eso se fija
# aca: todo llega por entorno. Ver docs/observabilidad.md.
#
# Flags de JVM para contenedor: el heap se dimensiona contra el limite de memoria del
# contenedor, no contra la RAM del host. Se reemplazan con `-e JAVA_OPTS=...` al arrancar.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
	AKINE_PORT=8080

EXPOSE 8080

# Liveness del actuator (spring-boot-starter-actuator, probes habilitadas en application.yml y
# /actuator/health/** abierto en SecurityConfig). Se usa liveness y no /actuator/health a secas:
# este ultimo incluye la base, y un corte de MySQL no se arregla reiniciando el backend.
# start-period de 10 minutos: el primer arranque contra una base vacia aplica todas las migraciones
# de Flyway. Medido: 3 min 9 s el 11/10/2026 (4 CPU, 2 GB, 72 migraciones) y 8 min 23 s en la
# corrida de G-10 del 09/10 (docs/carga.md §6.7). Con 120 s el contenedor quedaba unhealthy a mitad
# de las migraciones, y un orquestador que reinicia por healthcheck lo mataba. Un chequeo que pasa
# DENTRO del start-period marca healthy en el acto: el margen no demora los arranques normales.
HEALTHCHECK --interval=30s --timeout=5s --start-period=600s --retries=3 \
	CMD curl -fsS "http://localhost:${AKINE_PORT}/actuator/health/liveness" || exit 1

# Forma shell con exec: expande JAVA_OPTS y deja a la JVM como PID 1, que recibe el SIGTERM de
# `docker stop` y hace el apagado ordenado de Spring.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher \"$@\"", "--"]
