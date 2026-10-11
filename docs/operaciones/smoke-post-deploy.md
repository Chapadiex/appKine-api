# Smoke post-deploy

`ops/smoke.mjs` es el chequeo que se corre **después de cada despliegue y de cada rollback**. No
reemplaza al QA ni a los E2E: contesta una sola pregunta, *¿lo que acabo de desplegar está vivo,
es la versión que creo, y un usuario puede entrar?*, en menos de diez segundos.

```bash
AKINE_SMOKE_API_URL=http://akine-api:8080 \
AKINE_SMOKE_WEB_URL=https://app.akine.example \
AKINE_SMOKE_CONTRACT=0.80.0 \
AKINE_SMOKE_EMAIL=smoke@akine.example AKINE_SMOKE_PASSWORD="$(cat /run/secrets/akine_smoke)" \
  node ops/smoke.mjs
```

Node 18+, sin dependencias. Sale con 0 sólo si **todo** pasó; un paso salteado por falta de
configuración cuenta como rojo salvo con `AKINE_SMOKE_ALLOW_SKIP=1`.

## Qué verifica

| # | Chequeo | Qué falla si da rojo |
|---|---|---|
| 1 | `/actuator/health/liveness`, `/readiness` y `/actuator/health` en `UP` | El proceso no arrancó, o no ve la base (`readiness` incluye el `db`) |
| 2 | `GET /api/v1/version`: `contract` = `AKINE_SMOKE_CONTRACT` | Corre otra imagen que la desplegada (tag mal escrito, contenedor viejo que no se reemplazó) |
| 3 | El `X-Request-Id` enviado vuelve igual | La correlación de G-4 no está: un incidente no se va a poder rastrear |
| 4 | Login de la cuenta de humo, `/me/contexts`, `POST /auth/context` y `GET /me/permissions` | Autenticación, firma JWT (`AKINE_JWT_SECRET`), base y resolución de permisos |
| 5 | El frontend sirve `index.html` en `/` y en una ruta profunda, y `/healthz` responde `ok` | La imagen nginx o su fallback de SPA |
| 6 | `GET <web>/api/v1/version` devuelve el **mismo contrato** que el backend directo | El proxy `/api` (`AKINE_API_URL`) no llega, o llega a **otro** backend |

El actuator se consulta en `AKINE_SMOKE_API_URL` (o `AKINE_SMOKE_ACTUATOR_URL` si se sirve en
otro puerto con `MANAGEMENT_SERVER_PORT`): el frontend no lo expone, a propósito. El login entra
**por el frontend** si está configurado, que es el camino de los usuarios.

Cada falla imprime el `X-Request-Id` de la respuesta: con él se busca el `traceId` en el log
(runbook R5).

## Sin PHI

- La única lectura autenticada es `/api/v1/me/permissions`: códigos de permiso, nada de personas.
- La salida no imprime la contraseña ni el token, ni el cuerpo de ninguna respuesta.

## La cuenta de humo

Una cuenta **dedicada**, que no es de nadie, en una organización de humo propia (por ejemplo
"AKINE Smoke"), sin pacientes cargados:

1. Crearla una vez por entorno con el alta self-service (`POST /api/v1/auth/register`) y activarla
   por el enlace del correo.
2. Contraseña larga, en el gestor de secretos, rotada como cualquier otra credencial.
3. **No** darle `PLATFORM_ADMIN`: otorgárselo le quita los permisos de tenant y además sería la
   credencial más poderosa del sistema guardada en un script.
4. Las organizaciones de humo se excluyen de los reportes de negocio por su nombre o id.

Cuidado con el **rate limit del login** (30 por minuto por IP, `akine.security.rate-limit`): un
smoke en bucle desde la misma máquina que usan los operadores les come el cupo. Ver runbook R4.

## Si da rojo

| Rojo en | Primer paso |
|---|---|
| 1 | `docker logs akine-api`: casi siempre Flyway (runbook R1) o una variable obligatoria faltante |
| 2 | `docker inspect akine-api --format '{{.Config.Image}}'`: ¿es la imagen que se quiso desplegar? |
| 4 con 401 | ¿La cuenta de humo existe en este entorno? ¿Se rotó el secreto? ¿Se restauró una base vieja? |
| 4 con 429 | Rate limit: esperar un minuto (runbook R4) |
| 6 con 502 | El nginx no resuelve `AKINE_API_URL`: nombre del servicio, red de Docker |
| 6 con otro contrato | El frontend apunta a otro backend: `AKINE_API_URL` del contenedor web |

Si tras un deploy el smoke no se pone verde en 15 minutos, **rollback** (`rollback.md`).
