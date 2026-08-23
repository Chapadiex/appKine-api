# ADR-0017 — Custodia y ciclo de vida de los tokens de sesión

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-01.02

## Contexto

La especificación de M02 fija **una sola cosa** sobre sesiones: RF-M02-002 exige "emitir una sesión
segura" y RF-M02-003 habla de un "enlace temporal" de un solo uso. No define expiración, refresh,
rotación, revocación granular ni sesiones concurrentes. La única restricción dura es RN-M02-003: los
tokens sensibles **nunca en texto plano ni en logs**. Dos decisiones previas acotan el espacio. El [ADR-0009](0009-identidad-unica-con-seleccion-de-contexto.md)
obliga a un access token acotado al contexto activo y deja abierta "la ventana de validez del access
token" ante una revocación a mitad de sesión. El
[ADR-0001 del frontend](../../../appKine-web/docs/adr/0001-token-en-memoria-refresh-en-cookie-httponly.md)
decidió que el access vive **solo en memoria** y el refresh en **cookie `httpOnly`**, y le deja al
backend dos deberes: revocación server-side del refresh, y CSRF en ese endpoint. Encima cae la trampa T4 del digest de M02: RF-M02-005 "impedir acceso futuro" no se cumple marcando un
flag y esperando. Bloquear tiene que **cortar el acceso ya**, y eso exige estado server-side, no un JWT
autocontenido. El dato en juego es historia clínica: un token robado no es una molestia, es lectura de
datos de salud de pacientes ajenos.

## Decisión

**Access token: JWT HS256**, secreto de ≥256 bits por variable de entorno (`AKINE_JWT_SECRET`). Sin
default en producción: sin secreto la aplicación no arranca. **TTL 10 minutos.** Claims `iss`, `sub`,
`jti`, `iat`/`exp`, `scope` (`pre_context` | `context`), `org`, `loc`, `rol`, `fam`. Ningún endpoint de
negocio acepta `pre_context`. **Refresh token: opaco**, 32 bytes de `SecureRandom`. No porta claims: es un puntero a estado
server-side, lo único que permite revocación real. **TTL 12 horas absolutas desde el login**; la
rotación **no** extiende `expira_en`, que se hereda de la familia.

**Rotación en cada uso con detección de reuso.** El refresh marca `usado_en` en la fila presentada e
inserta una sucesora en la misma `familia_id` (`reemplazado_por_id`). Presentar un token con `usado_en`
seteado es **reuso**: se revoca la **familia completa** (`ROTACION_REUSO`), se emite
`REFRESH_REUSO_DETECTADO` y se responde `401`. Ante robo de cookie, víctima y atacante quedan afuera.
**Ventana de gracia de 10 segundos:** si un token ya usado se presenta dentro de los 10 s y su sucesor
todavía **no fue usado**, se devuelve ese mismo sucesor sin rotar de nuevo. Resuelve la carrera entre
pestañas del navegador sin desloguear a nadie y no debilita la detección: usado el sucesor, la
presentación repetida vuelve a ser reuso.

**Cookie `akine_rt`: `httpOnly` + `Secure` + `SameSite=Strict`, `Path=/api/v1/auth`** — no viaja a
ningún endpoint de negocio. Sin prefijo `__Host-`, que exigiría `Path=/`. **CSRF sin sync-token:**
`SameSite=Strict`, más un filtro que valida `Origin` (`Referer` como fallback) contra los orígenes CORS
permitidos, más `POST` con `Content-Type: application/json`, no submitible por `<form>`. Esto **es** la
reactivación de CSRF para el endpoint de refresh que pide el ADR-0001 del frontend. **Persistencia solo como SHA-256:** `refresh_token.token_hash` y
`token_verificacion.token_hash` guardan el hash hex; el valor plano existe solo en la respuesta y en el
enlace del email. Sin salt: 256 bits de entropía no son adivinables por diccionario. Tokens de un solo
uso: **30 minutos** para reset, **7 días** para activación.

**La ventana de revocación se acepta y no hay lista de revocación.** Al bloquear o desactivar se revocan
todos los refresh de la cuenta en la transacción de la transición; el access en vuelo sigue siendo
criptográficamente válido **hasta 10 minutos**. La distinción importa:

| Qué cambia | Ventana |
|---|---|
| Membership, consultorio, suscripción — permisos y contexto | **Cero.** El `TenantContextFilter` de 01.01 revalida contra la base en cada request, sin caché |
| Estado de la cuenta (bloqueada, desactivada) | **≤10 min**, el TTL del access |

El claim `rol` es cosmética de corto plazo, nunca autoridad. El `JwtAuthenticationFilter` **no** consulta
el estado de cuenta en cada request —sería un `SELECT` extra en todo request autenticado para cubrir un
evento raro—: el estado se revalida en cada refresh, o sea al menos cada 10 minutos, que es exactamente
el ancho de la ventana aceptada.

## Alternativas consideradas

**RS256.** Descartada: emisor y verificador son el mismo proceso, así que la clave pública no se le
entrega a nadie. RS256 paga cuando hay verificadores externos que no deben poder firmar. Sin `kid`, por
YAGNI: migrar es un cambio acotado de configuración y de la clase que firma, y se hará con un ADR nuevo
el día que aparezca un consumidor externo.

**Lista de revocación de access tokens en caché (por `jti` o `fam`).** Cerraría la ventana de 10 minutos.
Descartada: reintroduce estado compartido que anula el beneficio del token stateless y obliga a decidir
qué pasa cuando la caché no responde —fallar abierto es un agujero, fallar cerrado es una caída del
login—. El riesgo que cubre es una cuenta bloqueada operando 10 minutos más, con permisos que igual se
revalidan contra la base. No lo vale.

**Refresh deslizante que extiende la sesión.** Es lo habitual y evita cortarle la jornada al usuario.
Descartada: una sesión que se renueva mientras haya una pestaña abierta **no es una sesión**, es un
acceso permanente, y convierte el robo de una cookie en persistencia ilimitada. Las 12 h absolutas
cubren la jornada de un consultorio, que es el caso real.

**Refresh como JWT autocontenido.** Descartada de plano: no hay forma de revocarlo, y T4 exige
revocación efectiva al bloquear. **Token CSRF sincronizado.** Descartada: no hay sesión server-side
donde anclarlo, y `SameSite` más `Origin` cubren el mismo vector con menos piezas sobre un solo endpoint.
**Persistir los tokens de verificación en claro** para reenviar el enlace. Descartada: RN-M02-003 lo
prohíbe, y esa tabla sería un depósito de credenciales que va a los backups.

## Consecuencias

### Positivas

- La revocación al bloquear es **real**: los refresh mueren con la transición, no al expirar. El robo de
  la cookie se detecta solo: su uso genera reuso y mata la familia entera.
- Sin estado distribuido: el access se verifica con una firma y nada más. Una base comprometida no
  entrega tokens usables, solo hashes.

### Negativas

- **Hay hasta 10 minutos en los que una cuenta recién bloqueada sigue operando.** Cuando alguien reporte
  "lo bloqueé y todavía entra", la respuesta es que no es un bug.
- A las 12 h la sesión cae aunque el usuario esté trabajando, y va a caer en el peor momento. Refrescar
  cada 10 minutos multiplica los requests a ese endpoint y obliga al frontend a serializar las
  renovaciones concurrentes con una cola single-flight.
- La detección de reuso puede **desloguear a un usuario legítimo** por una anomalía de red o una
  restauración de sesión del navegador. La gracia cubre el caso común, no todos.
- El secreto HS256 es un punto único de compromiso: quien lo tiene, firma tokens válidos. Su rotación
  invalida todos los access vivos y no tiene procedimiento escrito.
- Depurar sesiones es más difícil: en la base solo hay hashes, no el token que reportó el usuario.

### Qué obliga a hacer

- `identity` es propietario de `refresh_token` y `token_verificacion`; **ningún otro módulo las lee**
  ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)). Toda transición a `BLOQUEADA` o `DESACTIVADA`,
  y todo `password-reset/confirm`, revocan los refresh de la cuenta **en la misma transacción** que el
  cambio de estado.
- El filtro de `Origin` vive en `platform` y corre antes del filtro JWT; el CSRF nativo de Spring queda
  deshabilitado, porque la API es stateless por Bearer. Sin secretos versionados: el logging nunca
  serializa bodies de `/auth/**`, ni `Authorization`, ni la cookie `akine_rt` (RN-M02-003).
- Los rechazos de `/auth` son Problem Details ([ADR-0005](0005-errores-como-problem-details.md)):
  `invalid-credentials`, `invalid-refresh`, `invalid-token`, `csrf-rejected`, mapeados en el advice de
  `identity.api`, nunca en `GlobalExceptionHandler`.
- **Deuda:** la purga de `refresh_token` expirados o revocados hace más de 30 días se implementa en la
  etapa de observabilidad e infraestructura, no en 01.02 — son artefactos de sesión, no información
  histórica: la traza vive en la auditoría. **Deuda:** procedimiento de rotación del secreto HS256.
