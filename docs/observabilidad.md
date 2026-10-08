# Observabilidad del backend (G-4)

Qué emite `akine-api` para operarlo, cómo se configura y cómo se mira. Cubre los tres pilares
que pide ADR-0016 —log estructurado, métricas Prometheus y trazas OpenTelemetry— y el id de
correlación que los une.

> **Fuera de alcance de G-4:** dashboards, alertas y la corrida de carga contra los SLO (G-10).
> Esto deja las señales listas para que esas piezas se monten encima.

## 1. Variables de entorno

| Variable | Default | Qué hace |
|---|---|---|
| `AKINE_LOG_FORMAT` | `logstash` (perfil `local`: vacío = texto) | Formato del log de consola. `logstash` = una línea JSON por evento. También acepta `ecs` y `gelf` (formatos nativos de Spring Boot). Vacío = texto legible |
| `AKINE_METRICS_SCRAPE_TOKEN` | vacío | Token del scraper de Prometheus (≥ 32 caracteres; más corto, **la aplicación no arranca**). Ver §3 |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | sin definir | URL OTLP/HTTP del collector, p. ej. `http://otel-collector:4318/v1/traces`. **Sin definir no se exporta ninguna traza** |
| `AKINE_TRACING_SAMPLING_PROBABILITY` | `0.1` | Fracción de trazas muestreadas (0.0 a 1.0) |
| `AKINE_ENVIRONMENT` | `sin-definir` | Atributo `deployment.environment` de las trazas (`staging`, `produccion`...) |
| `MANAGEMENT_SERVER_PORT` | sin definir | Opcional: sirve todo el actuator en otro puerto. Ver §3 |

Otras propiedades de Spring Boot sirven igual por variable de entorno (relaxed binding), por
ejemplo `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_HEADERS_AUTHORIZATION` para un collector
que pide credencial.

## 2. Log estructurado y correlación

**Formato.** Fuera de un perfil de desarrollo, la consola emite JSON con el formato `logstash`
de Spring Boot (`logging.structured.format.console`). No hay archivo: en contenedor el log es
stdout y lo recoge el runtime. Campos de cada línea:

| Campo | Origen |
|---|---|
| `@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `stack_trace` | Spring Boot |
| `service` | fijo, `akine-api` |
| `traceId`, `spanId` | Micrometer Tracing, en todo request (se exporte o no la traza) |
| `requestId` | `CorrelationIdFilter`: el `X-Request-Id` recibido o un UUID generado |
| `organizationId`, `consultorioId` | `TenantContextFilter`, solo en requests con contexto de trabajo |

**Id de correlación.** Todo request lleva `X-Request-Id` en la respuesta, **también los
rechazos** de la cadena de seguridad (401, 403, 429): el filtro corre antes que ella. Si el
cliente manda uno válido (`[A-Za-z0-9._:-]`, hasta 64 caracteres) se respeta; si no, se genera.
Un valor inválido —por ejemplo con saltos de línea— se descarta en silencio, no se rechaza el
pedido. El header está permitido y expuesto en CORS, así que el frontend puede leerlo y
mostrarlo en un error. El `requestId` también va al span como atributo `akine.request_id`.

**`requestId` vs `traceId`.** El `traceId` es la llave de la traza distribuida y es lo que se
graba en `audit_event.correlation_id` (antes de G-4 esa columna quedaba siempre nula: los
`AuditEvents` la leían del MDC y nadie la ponía). El `requestId` es la llave que tiene el
usuario: con él se encuentra el `traceId` en el log.

**Qué NO va al log.** Ni PHI ni secretos. El MDC lleva solo identificadores técnicos; la cuenta
del usuario no va, a propósito. Los cuerpos de request y respuesta no se loguean, y los
payloads de auditoría y notificaciones siguen pasando por sus sanitizadores (`SanitizedPayload`
y los de cada módulo), que G-4 no toca. El JSON escapa los valores: un salto de línea dentro de
un mensaje no fabrica una línea falsa.

**En local.** El perfil `local` sale en texto, con `[akine-api,traceId,spanId,requestId]` en
cada línea. Para ver el JSON:

```bash
AKINE_LOG_FORMAT=logstash ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## 3. Métricas

`GET /actuator/prometheus`, formato de texto de Prometheus (Micrometer). Incluye las métricas
estándar de Spring Boot: JVM, proceso, pool de Hikari, Tomcat, logback y
`http_server_requests_seconds` (método RED: tasa, errores por `status`/`outcome`, duración).

**Histograma para los SLO.** `http.server.requests` publica histograma con buckets
(`percentiles-histogram`) más los dos SLO de ADR-0016 como buckets exactos, `le="0.3"` y
`le="0.8"`. Así se calculan p95/p99 en Prometheus, agregando entre instancias:

```promql
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application="akine-api"}[5m])))
# fracción de requests dentro del SLO de p95 (300 ms)
sum(rate(http_server_requests_seconds_bucket{le="0.3"}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))
```

ADR-0016 excluye exportaciones y proveedores externos: filtrar por `uri` en la consulta.

**Cardinalidad.** Ningún tag lleva ids. Los de `http_server_requests` son `method`, `uri` (la
**plantilla** de la ruta, `/api/v1/organizations/{orgId}/...`, nunca la ruta con valores),
`status`, `outcome`, `exception`, más `application=akine-api`. Un request que no llega a un
controller (rechazado por un filtro, ruta inexistente) no publica su ruta cruda: Spring Boot lo
agrupa en un valor fijo (`UNKNOWN`, `NOT_FOUND`). El `requestId` y el tenant van a log y trazas, nunca a
métricas.

**Quién puede leerlas — la decisión.** Exponer el endpoint no lo abre. Lo decide
`MetricsScrapeSecurityConfig`, una cadena de seguridad propia que se evalúa antes que la
principal:

| `AKINE_METRICS_SCRAPE_TOKEN` | Perfil | Resultado |
|---|---|---|
| definido | cualquiera | solo con `Authorization: Bearer <token>`; sin él o con otro, 401 |
| vacío | desarrollo (`local`, `dev`, `desarrollo`, `test`) | abierto |
| vacío | cualquier otro, o ninguno | cerrado para todos (401) |

Por qué así: las métricas no tienen datos de pacientes, pero dicen cuánto tráfico recibe cada
endpoint, cuánto tarda y cuándo el sistema está saturado — información útil para atacarlo. Es la
misma regla que la documentación del contrato: fuera de desarrollo, lo que no se configura a
propósito queda cerrado. El token es estático porque Prometheus no inicia sesión, y la cadena
es propia porque en la principal el filtro de JWT rechazaría cualquier bearer que no sea un
access token. La comparación es en tiempo constante.

Configuración de Prometheus:

```yaml
scrape_configs:
  - job_name: akine-api
    metrics_path: /actuator/prometheus
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/akine-scrape-token
    static_configs:
      - targets: ['akine-api:8080']
```

**Defensa adicional (opcional).** Con `MANAGEMENT_SERVER_PORT=9091` todo el actuator —health
incluido— pasa a ese puerto, y basta con que el balanceador no lo publique. Se suma al token, no
lo reemplaza. Si se usa, mover también el `HEALTHCHECK` del `Dockerfile` y los probes del
orquestador a ese puerto.

## 4. Trazas

Micrometer Tracing con el puente a OpenTelemetry (`micrometer-tracing-bridge-otel`) y el
exportador OTLP. Se usan los módulos sueltos y no `spring-boot-starter-opentelemetry` porque el
starter agrega `micrometer-registry-otlp`, que empuja métricas por OTLP a `localhost:4318` cada
minuto y llena el log de errores donde no hay collector.

- **Siempre:** cada request abre un span, con `traceId`/`spanId` en el MDC. Se propaga W3C
  `traceparent` de entrada y de salida.
- **Exportar es opt-in:** solo con `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`. Sin
  ella no se crea exportador: no hace falta collector en local ni en los tests. (No se declara
  en `application.yml` con default vacío: Spring Boot activa el exportador con que la propiedad
  *exista*, y un valor vacío cumple.)
- **Muestreo bajo:** 10 % por default (`AKINE_TRACING_SAMPLING_PROBABILITY`), padre-primero: si
  el request trae un `traceparent` muestreado, se respeta.

Para mirar trazas en local, un Jaeger con OTLP:

```bash
docker run --rm -d --name jaeger -p 16686:16686 -p 4318:4318 jaegertracing/jaeger:2.10.0
MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4318/v1/traces \
AKINE_TRACING_SAMPLING_PROBABILITY=1.0 \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

UI en <http://localhost:16686>, servicio `akine-api`.

## 5. Imagen Docker

La imagen no fija nada de esto: todo llega por entorno. Un despliegue típico:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e AKINE_METRICS_SCRAPE_TOKEN="$(cat scrape-token)" \
  -e MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces \
  -e AKINE_ENVIRONMENT=staging \
  ... (AKINE_DB_*, AKINE_JWT_SECRET, etc.: ver AGENT.md §12) \
  akine-api:local
```

- **Logs:** `docker logs -f akine-api` → JSON por línea; `docker logs akine-api | jq 'select(.requestId=="<id>")'`.
- **Métricas:** `curl -H "Authorization: Bearer $(cat scrape-token)" http://localhost:8080/actuator/prometheus`.
- **Trazas:** en el backend que esté detrás del collector.

Con `SPRING_PROFILES_ACTIVE=local` (el ejemplo de `AGENT.md`) el log sale en texto y el scrape
queda abierto, como en desarrollo.
