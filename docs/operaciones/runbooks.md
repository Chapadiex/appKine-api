# Runbooks

Incidentes típicos, cada uno con **síntomas**, **diagnóstico** y **acciones**. Las señales son
las de G-4 (`docs/observabilidad.md`): log JSON por stdout con `requestId`, `traceId` y tenant;
`/actuator/prometheus` con `http_server_requests_seconds_*`, Hikari y JVM; trazas OTLP si están
encendidas.

Convenciones de los comandos: el backend corre como contenedor `akine-api`, la base es accesible
con un cliente `mysql` (o `docker exec akine-mysql mysql ...`), y `jq` está disponible donde se
leen logs. Nunca pegar en un ticket una línea de log o una fila con datos de un paciente: ids y
`requestId`, sí; nombres, documentos o contenido clínico, no.

| # | Incidente | Gravedad típica |
|---|---|---|
| [R1](#r1-el-backend-no-arranca-por-flyway) | El backend no arranca por Flyway | Caída total |
| [R2](#r2-outbox-de-correo-clavado) | Outbox de correo clavado | Nadie recibe activaciones ni recuperaciones |
| [R3](#r3-mysql-lleno) | MySQL lleno | Escrituras fallan, luego caída |
| [R4](#r4-429-masivos) | 429 masivos | Nadie puede entrar |
| [R5](#r5-error-500-correlacion-por-x-request-id--traceid) | Error 500: correlación por `X-Request-Id` / `traceId` | Un flujo roto |
| [R6](#r6-restauracion-de-adjuntos) | Restauración de adjuntos | Documentos que no se descargan |

---

## R1. El backend no arranca por Flyway

**Síntomas**
- El contenedor reinicia en bucle o queda `unhealthy`; el smoke da rojo en `liveness`.
- El frontend responde 502 en todo `/api`.
- Arriba de todo en el log aparece una `UnsatisfiedDependencyException` o un
  `BeanCreationException` de **otro** bean (históricamente `jwtAuthenticationFilter`), que **no
  dice nada del problema real**: la causa está más abajo, en el `Caused by` de `flywayInitializer`.

**Diagnóstico**

```bash
docker logs akine-api 2>&1 | jq -r 'select(.level=="ERROR") | .message, .stack_trace' | grep -iE 'flyway|migration|caused by' | head -30
```

```sql
SELECT installed_rank, version, description, success, installed_on
  FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

| Mensaje | Causa | Acción |
|---|---|---|
| `Validate failed: ... Detected applied migration not resolved locally: 85` | La imagen es más vieja que el esquema **y** alguien sobreescribió `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS` (el default `*:future` lo tolera: `rollback.md` §1) | Quitar la variable. Si no era un rollback, arrancar la imagen correcta |
| `Migration checksum mismatch for migration version 57` | Alguien **editó** una migración ya aplicada | Nunca reparar en producción "para que arranque". Volver la migración a su contenido original en el código. `flyway repair` sólo con la causa entendida y decidida |
| `Detected failed migration to version 14` (fila con `success = 0`) | Una migración **murió a mitad**. En MySQL el DDL no hace rollback: lo que alcanzó a crear quedó | Ver abajo |
| `Access denied; you need (at least one of) the SUPER ... privilege(s)` al crear un trigger | El servidor tiene binlog y `log_bin_trust_function_creators=0`. Es `V14` | Arrancar MySQL con `--log-bin-trust-function-creators=1` y seguir "migración fallida" |
| `Duplicate key name` / `Duplicate foreign key constraint name` | Reintento sobre lo que dejó una migración fallida, o nombre de constraint repetido entre tablas (únicos **por esquema** en MySQL) | Ver abajo / corregir el nombre en una migración nueva |
| `Communications link failure` | No hay base, no la alcanza, o credenciales | R3 si el disco está lleno; si no, red y `AKINE_DB_*` |

**Acciones — migración fallida a mitad**

1. **Detener** el backend (no dejar que reintente en bucle).
2. Backup del estado actual (`ops/backup.sh` se niega con migraciones fallidas: usar
   `mysqldump` a mano o un snapshot del volumen). Sirve para el post-mortem.
3. Leer la migración y ver qué alcanzó a aplicar: `SHOW CREATE TABLE`, `SHOW INDEX FROM`,
   `SHOW TRIGGERS`. Deshacer **a mano sólo eso**. Ejemplo real con `V14` (25/08/2026): había creado
   los dos índices y muerto en el trigger:

   ```sql
   ALTER TABLE audit_event DROP INDEX ix_audit_event_org_time;
   ALTER TABLE audit_event DROP INDEX ix_audit_event_org_loc_time;
   DELETE FROM flyway_schema_history WHERE version = '14' AND success = 0;
   ```
4. Corregir la causa (la flag, el nombre repetido) y arrancar: Flyway reintenta la migración
   completa.
5. Si no se entiende qué dejó a medias: **restore** del backup previo al deploy (`rollback.md` §3).

**Prevención:** el checklist pre-deploy pide probar las migraciones contra una copia restaurada
del entorno. Un `CHECK` o una FK con nombre repetido sólo fallan **al ejecutar**.

---

## R2. Outbox de correo clavado

**Contexto.** Ningún correo sale desde el servicio que lo pide: se encola en
`notification_outbox` y un worker lo envía cada 15 s (`OutboxWorker`, lotes, backoff 1-5-15-60-180
min, 5 intentos, lease de 5 min para filas `PROCESANDO` huérfanas). En septiembre de 2026 **una
sola invitación con un payload que el parser no leía dejó sin correo a todo el despliegue**: la
excepción volteaba el tick entero y la misma fila se reclamaba en cada ciclo. Eso se corrigió
(cada fila se aísla), pero el síntoma vale para cualquier causa futura.

**Síntomas**
- "No me llegó el correo de activación / recuperación / invitación", de **varias** personas.
- El alta self-service y la recuperación responden 202 igual (ADR-0018): **el usuario no ve
  error**. El problema sólo se ve en el outbox.

**Diagnóstico**

```sql
-- ¿Hay cola? ¿Desde cuándo?
SELECT estado, COUNT(*), MIN(proxima_ejecucion_en), MAX(updated_at)
  FROM notification_outbox GROUP BY estado;

-- Lo pendiente más viejo y por qué falló (error_sanitizado no lleva secretos ni direcciones)
SELECT id, tipo, estado, intentos, proxima_ejecucion_en, procesando_desde, error_sanitizado
  FROM notification_outbox
 WHERE estado IN ('PENDIENTE','REINTENTABLE','PROCESANDO')
 ORDER BY proxima_ejecucion_en LIMIT 20;
```

```bash
docker logs --since 30m akine-api 2>&1 | jq -r 'select(.logger_name|test("notification")) | "\(.["@timestamp"]) \(.level) \(.message)"' | tail -40
```

| Lo que se ve | Causa | Acción |
|---|---|---|
| `PENDIENTE` crece y `MAX(updated_at)` no se mueve; log `El tick del outbox fallo` en cada ciclo | Algo voltea el tick entero (el defecto de septiembre, u otro) | La fila que lo dispara es la más vieja reclamable. Capturar el `stack_trace`, abrir defecto. Para destrabar al resto: marcarla `FALLIDA` con motivo, a mano y registrado en el incidente |
| Muchas `REINTENTABLE` con `error_sanitizado` de conexión o autenticación SMTP | El relay está caído o rechaza la credencial | Revisar `AKINE_MAIL_HOST/PORT/USER/PASSWORD/TRANSPORT_SECURITY`. Al volver el relay, el backoff las reenvía solas |
| `PROCESANDO` con `procesando_desde` de hace más de 5 min | Un worker murió a mitad (reinicio, OOM) | Nada: el lease las devuelve a `REINTENTABLE`. Si no vuelven, el tick no corre (ver primera fila) |
| `AGOTADA` con motivo de token consumido o vencido | El enlace ya no sirve: reenviarlo no tiene sentido | La persona pide uno nuevo (reenvío de activación, nueva recuperación, reenvío de invitación) |
| Nada en el outbox | El correo nunca se encoló: el problema es del flujo, no del envío | R5 con el `requestId` del pedido |

**Acciones**
- Reintento administrativo de una notificación de un tenant:
  `POST /api/v1/organizations/{orgId}/notifications/{id}/retry` (permiso `colaborador:manage`).
- **No** borrar filas: el outbox es también el registro de qué se envió.
- **No** pasar el despliegue a `AKINE_EMAIL_MODE=log` para "destrabar": fuera de un perfil de
  desarrollo la aplicación no arranca en ese modo, y si arrancara descartaría cada correo en
  silencio.

---

## R3. MySQL lleno

**Síntomas**
- 500 en escrituras con `The table '...' is full`, `Error writing file ... (Errcode: 28 - No space
  left on device)` o `Disk full` en el log del backend.
- Después: `readiness` en `DOWN`, Hikari sin conexiones, MySQL que no arranca.
- El binlog crece aunque las tablas no: es la causa más común en MySQL 8.4 con binlog activo.

**Diagnóstico**

```bash
docker exec akine-mysql df -h /var/lib/mysql
docker exec akine-mysql du -sh /var/lib/mysql/* | sort -h | tail
```

```sql
SHOW BINARY LOGS;                     -- tamaño y cantidad de binlogs
SELECT @@binlog_expire_logs_seconds;  -- default 30 días
SELECT table_name, ROUND((data_length + index_length)/1024/1024) AS mb
  FROM information_schema.TABLES WHERE table_schema = DATABASE()
 ORDER BY mb DESC LIMIT 10;
```

En Prometheus: `hikaricp_connections_pending` sube, `http_server_requests_seconds_count{status="500"}`
sube en endpoints de escritura.

**Acciones**
1. **Liberar binlog** si es la causa: `PURGE BINARY LOGS BEFORE NOW() - INTERVAL 3 DAY;` —**sólo**
   si no hay réplicas que los necesiten y el último backup es posterior— y bajar
   `binlog_expire_logs_seconds` en la configuración del servidor.
2. Si son datos: **agrandar el volumen**. No hay purga posible en AKINE: la información histórica
   relevante no se borra (baja lógica, ADR-0004), `audit_event` es append-only por trigger y las
   filas `ENVIADA` del outbox se conservan como registro. Las tablas que más crecen son
   `audit_event` y `notification_outbox`: vigilarlas.
3. Si el volumen de backups comparte disco con la base, moverlo: es una mala ubicación por partida
   doble (R3 y desastre).
4. Al recuperar espacio, el backend se recupera solo (Hikari reconecta); si quedó `DOWN`,
   reiniciarlo. Verificar con el smoke.
5. Revisar que no haya quedado una migración a medias si el disco se llenó durante un deploy (R1).

**Prevención:** alerta al 80 % del disco de datos y sobre el crecimiento diario de binlog.

---

## R4. 429 masivos

**Contexto.** `RateLimitFilter` limita login, refresh, recuperación y reenvío de activación (30
por minuto), el alta self-service (5) y el alta directa de colaboradores (10). La clave es
**ruta + IP del socket** (`getRemoteAddr()`), nunca el email (ADR-0018). El contador vive **en la
memoria de cada instancia**.

> **Riesgo conocido del despliegue con proxy — leer antes de diagnosticar.** El backend ignora
> `X-Forwarded-For` a propósito (lo escribe el cliente), y no hay proxy de confianza declarado
> (TODO de `RateLimitFilter`). Detrás del nginx del frontend, o de cualquier balanceador, **todos
> los pedidos llegan con la IP del proxy**: el límite deja de ser por usuario y pasa a ser
> **global** — 30 logins por minuto para todo el sistema. Con uso real esto produce 429 masivos a
> primera hora sin ningún ataque. Falla del lado seguro, pero falla. Arreglarlo es una decisión de
> código (declarar el proxy confiable y leer la cabecera reenviada), no de operación.

**Síntomas**
- Usuarios que no pueden entrar: el login responde 429 aunque la contraseña sea correcta.
- Respuestas 429 con `Retry-After`.

**Diagnóstico**

```bash
# Quién y dónde: el filtro loguea ruta e IP en cada rechazo
docker logs --since 15m akine-api 2>&1 | jq -r 'select(.message|startswith("Rate limit alcanzado")) | .message' | sort | uniq -c | sort -rn | head
```

```promql
sum by (uri) (rate(http_server_requests_seconds_count{status="429"}[5m]))
```

| Lo que se ve | Lectura |
|---|---|
| Una sola `ip=` que es la del proxy/nginx, muchos usuarios afectados | El riesgo de arriba: tráfico legítimo agregado |
| Una IP externa con cientos de intentos sobre `/auth/login` | Ataque de fuerza bruta o un script roto: el límite está haciendo su trabajo |
| Muchas IP, ruta `/auth/register` | Alta automatizada (lo que el cupo de 5 existe para frenar) |
| Pico después de un smoke en bucle | El smoke consume el cupo de la IP de operación |

**Acciones**
- **Ataque:** dejar el límite; bloquear la IP en el borde (WAF, firewall). Revisar `audit_event`
  de logins fallidos por cuenta.
- **Tráfico legítimo agregado por el proxy:** mitigación inmediata, **con su costo dicho**:
  `AKINE_RATE_LIMIT_ENABLED=false` y reinicio quita la protección contra fuerza bruta y contra la
  bomba de correos del alta. Sólo como medida temporal, con el borde limitando por IP real, y
  registrada en el incidente. La solución es el cambio de código.
- Reiniciar el backend vacía los contadores (viven en memoria): alivia un minuto, no resuelve.
- Con varias instancias, el límite efectivo es N × el configurado.

---

## R5. Error 500: correlación por `X-Request-Id` / `traceId`

**Contexto.** Todo 500 responde un Problem Details genérico (`type .../internal-error`,
"Ocurrio un error inesperado") **sin detalle interno**, y el log tiene la excepción completa
(`GlobalExceptionHandler`, "Excepcion no controlada"). Lo que los une es el id de correlación:
cada respuesta —también 401, 403 y 429— lleva `X-Request-Id`; el frontend puede leerlo y mostrarlo.

**Síntomas**
- Un usuario reporta un error en una pantalla; o sube `http_server_requests_seconds_count{status="500"}`.

**Diagnóstico**

1. Conseguir el `X-Request-Id` (del usuario, de la pestaña Red del navegador, o del smoke).
2. Buscarlo en el log: da el `traceId`, el tenant y la excepción.

   ```bash
   docker logs akine-api 2>&1 | jq -c 'select(.requestId=="<id>") | {t: .["@timestamp"], level, logger_name, message, traceId, organizationId}'
   docker logs akine-api 2>&1 | jq -r 'select(.requestId=="<id>" and .level=="ERROR") | .stack_trace'
   ```
3. Con el `traceId`, todo lo que pasó en ese pedido (incluidas las llamadas que hizo) y su
   auditoría:

   ```bash
   docker logs akine-api 2>&1 | jq -c 'select(.traceId=="<traceId>")'
   ```
   ```sql
   SELECT occurred_at, event_type, organization_id FROM audit_event WHERE correlation_id = '<traceId>';
   ```
   Si las trazas se exportan (`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`), buscar el
   `traceId` en el backend de trazas. Con muestreo del 10 %, puede no estar: el log siempre está.
4. Sin `requestId` a mano: acotar por hora y tenant.

   ```bash
   docker logs --since 1h akine-api 2>&1 | jq -c 'select(.level=="ERROR" and .organizationId=="<orgId>") | {t: .["@timestamp"], requestId, message}'
   ```
5. Para ver si es un patrón y no un caso: `sum by (uri, exception) (rate(http_server_requests_seconds_count{status="500"}[15m]))`.

**Acciones**
- Un 500 **siempre es un defecto** (o una dependencia caída: base, SMTP, disco). Abrir el defecto
  con `requestId`, `traceId`, endpoint (la **plantilla** de la ruta, no la ruta con ids) y la
  excepción. Sin PHI en el ticket.
- Si empezó con un deploy: comparar con la hora del deploy y evaluar rollback (`rollback.md`).
- Si es concurrencia (`OptimisticLockingFailure`, deadlock, `UnexpectedRollbackException`):
  revisar las trampas conocidas del proyecto (`CLAUDE.md`, reglas transversales) antes de
  "arreglar" con un reintento.

---

## R6. Restauración de adjuntos

**Síntomas**
- Descargar un documento responde **409** `adjunto-no-disponible` /
  `adjunto-clinico-no-disponible`, en vez del archivo.
- Varios a la vez: se perdió o se montó mal el volumen de `/app/var`.
- Log `No se pudo leer el contenido de un adjunto`.

**Diagnóstico**

```bash
# ¿El volumen está montado donde la aplicación escribe?
docker inspect akine-api --format '{{range .Mounts}}{{.Name}} -> {{.Destination}}{{"\n"}}{{end}}'

# Cruce completo fila ↔ binario: FALTA, CORRUPTO, REPARABLE (sólo ids y conteos)
AKINE_DB_*=... AKINE_ADJUNTOS_VOLUME=akine-var ops/verificar-adjuntos.sh
```

**Acciones**
1. **Volumen mal montado** (contenedor recreado sin `-v`): los archivos están en el volumen
   viejo. Volver a montarlo; no restaurar nada.
2. **Binarios perdidos:** reponer **sólo los que faltan** desde el backup más reciente que los
   tenga, sin pisar los existentes ni tocar la base:

   ```bash
   AKINE_ADJUNTOS_VOLUME=akine-var AKINE_RESTORE_ADJUNTOS_MODO=completar \
     ops/restore.sh /srv/backups/akine/akine-<UTC> --solo-adjuntos
   ops/verificar-adjuntos.sh
   ```
   Los adjuntos subidos **después** de ese backup no están en ningún lado: quedan `FALTA` y hay que
   pedir el documento de nuevo a quien lo subió.
3. **Filas `REPARABLE`:** son adjuntos que el sistema marcó `NO_DISPONIBLE` cuando no encontró el
   binario (la lectura clínica lo marca; la subida administrativa que falló también) y cuyo
   binario **volvió** con el checksum exacto. **El código no tiene camino para volver una fila a
   `DISPONIBLE`**: hasta que exista, la reposición es un UPDATE manual, acotado a los ids que el
   script reportó como `REPARABLE` y registrado en el incidente:

   ```sql
   -- Sólo ids listados como REPARABLE por ops/verificar-adjuntos.sh (archivo presente, SHA-256 igual)
   UPDATE adjunto_clinico         SET estado = 'DISPONIBLE', version = version + 1 WHERE id IN (...) AND estado = 'NO_DISPONIBLE';
   UPDATE adjunto_administrativo SET estado = 'DISPONIBLE', version = version + 1 WHERE id IN (...) AND estado = 'NO_DISPONIBLE';
   ```
   Esto **no** es "maquillar la base" (§6 de `CLAUDE.md` lo prohíbe para el QA): es reponer un
   hecho verificado byte a byte. El UPDATE mueve `version` (regla del proyecto: todo UPDATE por
   fuera de JPA la mueve), así que una reclasificación abierta en otra pantalla recibe 409 en vez
   de pisar el estado.
4. **`CORRUPTO`:** el archivo existe y no coincide con su checksum. No reemplazarlo a ciegas:
   copiar el archivo actual aparte (evidencia), reponer desde backup con un restore a un volumen
   efímero (`ops/verificar-backup.sh` deja el ejemplo) y copiar ese único archivo.
5. Verificar descargando el documento desde la aplicación (y que quede `ADJUNTO_DOWNLOADED` en la
   auditoría).
