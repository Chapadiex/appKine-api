# 01 · Onboarding y cuentas — P2

Módulo `identity` (M02) y el onboarding compuesto de `organization` (M01). Lo cubren bien dos specs
E2E contra el backend real (`auth-flujo`, `cuenta-ciclo-real`): acá se hace el camino feliz por
pantalla **mirando la base**, y se concentra el tiempo en lo que ningún test mira —el correo de
"ya registrado", el reenvío que invalida el enlace anterior, las sesiones que el reset revoca—.

Variables: §4.1 del [README](README.md). Tablas: `cuenta`, `token_verificacion`, `refresh_token`,
`onboarding_registro`, `organization`, `consultorio`, `membership`, `subscription`,
`notification_outbox`, `account_active_context`.

> Las tablas de `identity` **no llevan `organization_id`** (ADR-0019) y sus eventos de auditoría
> pueden tener `organization_id` NULL. Se buscan por cuenta.

---

### ON-01 · Alta self-service del centro A — `[E2E auth-flujo]`

**Pantalla:** `http://localhost:4200/auth/registro`. Email `qa.admin-a@ejemplo.test`, nombre y
apellido inventados, organización `Centro QA A`, contraseña de 12+ caracteres. Enviar.

**Esperado:** la pantalla dice que llegó un correo para activar. No dice si el email ya existía
(ADR-0018). En Mailpit (<http://localhost:8025>) aparece "Activa tu cuenta de AKINE" para esa casilla.

**DB:** una cuenta `PENDIENTE_ACTIVACION`. La contraseña ya está guardada, como hash Argon2id con
el prefijo `{argon2}` de Spring: lo que falta es activar.

```sql
SELECT id, email, estado, password_hash LIKE '{argon2}$argon2id$%' AS hash_argon2id, intentos_fallidos, created_at
FROM cuenta WHERE email_normalizado = @email_admin_a;

SELECT r.estado, r.organization_id, r.consultorio_id, o.name, o.slug, o.timezone, c.name AS sede, c.slot_minutes
FROM onboarding_registro r
JOIN organization o ON o.id = r.organization_id
JOIN consultorio c ON c.id = r.consultorio_id
WHERE r.email_normalizado = @email_admin_a;
```

Esperado: `estado = PENDIENTE_ACTIVACION`, `hash_argon2id = 1`; registro `COMPLETADO` con una
organización y **una** sede, zona `America/Argentina/Cordoba`.

```sql
SET @cuenta_admin_a := (SELECT id FROM cuenta WHERE email_normalizado = @email_admin_a);
SET @org_a  := (SELECT organization_id FROM onboarding_registro WHERE email_normalizado = @email_admin_a AND estado = 'COMPLETADO');
SET @sede_a := (SELECT consultorio_id FROM onboarding_registro WHERE email_normalizado = @email_admin_a AND estado = 'COMPLETADO');

SELECT id, role_code, is_founder, consultorio_id, estado, valid_from, valid_until
FROM membership WHERE organization_id = @org_a AND account_id = @cuenta_admin_a;

SELECT s.status, p.code AS plan
FROM subscription s JOIN plan p ON p.id = s.plan_id
WHERE s.organization_id = @org_a;

SELECT tipo, expira_en, usado_en, invalidado_en
FROM token_verificacion WHERE cuenta_id = @cuenta_admin_a ORDER BY id;

SELECT tipo, estado, intentos, enviado_en, payload_sanitizado
FROM notification_outbox WHERE destinatario = @email_admin_a ORDER BY id;
```

Esperado: membership `ORG_ADMIN`, `is_founder = 1`, `consultorio_id` NULL (alcance organización),
`ACTIVA`; suscripción `ACTIVA` en `BASICO`; un token `ACTIVACION` sin usar; una notificación
`ACTIVACION_CUENTA` que pasa a `ENVIADA` (el worker del outbox corre cada ~15 s). **El
`payload_sanitizado` no lleva el token ni el enlace**: solo claves de una lista cerrada. Si ves un
token o una URL ahí, es hallazgo crítico.

**Auditoría:**

```sql
SELECT id, organization_id, event_type, entity_type, entity_id, new_state, details
FROM audit_event
WHERE actor_account_id = @cuenta_admin_a AND occurred_at >= @desde
ORDER BY id;
```

`ORGANIZATION_CREATED`, `MEMBERSHIP_CREATED` (`isFounder: true`) y `CUENTA_CREADA`
(`PENDIENTE_ACTIVACION`).

---

### ON-02 · Activar por el enlace del correo — `[E2E cuenta-ciclo-real]`

**Pantalla:** abrir el enlace del correo en Mailpit (lleva a `/auth/activar?token=...`).

**Esperado:** la cuenta queda activa y la pantalla manda a ingresar. Volver a abrir **el mismo
enlace** falla con un mensaje humano, sin internals.

**DB:**

```sql
SELECT estado FROM cuenta WHERE id = @cuenta_admin_a;
SELECT tipo, usado_en, invalidado_en FROM token_verificacion WHERE cuenta_id = @cuenta_admin_a ORDER BY id;
```

Esperado: `ACTIVA`; el token `ACTIVACION` con `usado_en` lleno. Solo se guarda el hash del token
(`token_hash`): el valor del enlace no está en ninguna tabla.

**Auditoría:** `CUENTA_ACTIVADA` (`PENDIENTE_ACTIVACION → ACTIVA`, `organization_id` NULL). Con la
consulta de ON-01.

---

### ON-03 · Ingresar y elegir contexto — `[E2E auth-flujo]`

**Pantalla:** `/auth/ingresar` con admin-a. Con un solo contexto entra directo; con varios, pasa por
`/seleccionar-contexto`.

**Esperado:** entra a la organización A. El refresh viaja en la cookie `akine_rt` (`HttpOnly`;
mirala en DevTools → Application → Cookies: el valor no es legible desde JavaScript).

**DB:**

```sql
SELECT id, familia_id, context_organization_id, context_consultorio_id, emitido_en, expira_en,
       usado_en, revocado_en, motivo_revocacion, LEFT(token_hash, 12) AS hash
FROM refresh_token WHERE cuenta_id = @cuenta_admin_a ORDER BY id DESC LIMIT 5;

SELECT organization_id, consultorio_id, updated_at
FROM account_active_context WHERE account_id = @cuenta_admin_a;
```

Esperado: un refresh vigente con el contexto `@org_a` / `@sede_a` y `token_hash` de 64 caracteres
hexadecimales (SHA-256, nunca el token). El contexto activo apunta a A.

**Auditoría:** `LOGIN_EXITOSO`, `SESION_ABIERTA` (los dos con `organization_id` NULL) y
`CONTEXTO_SELECCIONADO` (con `organization_id = @org_a`).

> `SESION_ABIERTA`/`SESION_CERRADA` de **identidad** (`entity_type = 'Cuenta'`) no son la sesión
> clínica: `SESION_CERRADA` de `encounter` tiene `entity_type = 'Sesion'`. Filtrá siempre por
> `entity_type`.

---

### ON-04 · Contraseña equivocada y cuenta sin activar: misma respuesta — `[E2E auth-flujo]`

**Pantalla:** ingresar con admin-a y una contraseña equivocada; después, con una cuenta que nunca
activaste (podés dar de alta `qa.sin-activar@ejemplo.test` y no abrir el correo).

**Esperado:** **el mismo mensaje** en los dos casos y en el de un email que no existe. Ninguno
revela si la cuenta existe (ADR-0018). La cuenta **no se bloquea** por intentos: es una decisión
(`AuthenticationService`: bloquear por intentos habilita un DoS dirigido); lo que la frena es el
límite de 30 por minuto y por IP.

**DB:**

```sql
SELECT email, estado, intentos_fallidos, ultimo_login_en, bloqueada_en
FROM cuenta WHERE email_normalizado IN (@email_admin_a, 'qa.sin-activar@ejemplo.test');
```

Esperado: `intentos_fallidos` sube en 1 por intento fallido de admin-a y vuelve a 0 con el
siguiente login correcto; `bloqueada_en` sigue NULL.

**Auditoría:** `LOGIN_FALLIDO` (`details.intentosFallidos`) y, para la cuenta sin activar,
`LOGIN_RECHAZADO_ESTADO`, ambos con `organization_id` NULL:

```sql
SELECT id, event_type, entity_id, details, occurred_at
FROM audit_event
WHERE event_type IN ('LOGIN_FALLIDO', 'LOGIN_RECHAZADO_ESTADO', 'ACTIVIDAD_SOSPECHOSA') AND occurred_at >= @desde
ORDER BY id DESC LIMIT 10;
```

---

### ON-05 · Registrarse con un email que ya existe

**Pantalla:** `/auth/registro` otra vez con `qa.admin-a@ejemplo.test` y otro nombre de organización.

**Esperado:** **la misma pantalla** que un alta nueva (202). Lo que cambia es el correo: a la casilla
le llega un aviso de "ya tenés una cuenta", no un enlace de activación. **No** se crea otra
organización.

**DB:**

```sql
SELECT estado, organization_id, created_at
FROM onboarding_registro WHERE email_normalizado = @email_admin_a ORDER BY id;

SELECT COUNT(*) AS organizaciones_del_fundador
FROM membership WHERE account_id = @cuenta_admin_a AND is_founder = 1;

SELECT tipo, estado, created_at
FROM notification_outbox WHERE destinatario = @email_admin_a ORDER BY id;
```

Esperado: un segundo registro `DUPLICADO` sin organización nueva; sigue habiendo **una**
organización fundada; aparece una notificación `CUENTA_YA_REGISTRADA`.

---

### ON-06 · Reenviar la activación invalida el enlace anterior

**Pantalla:** dar de alta `qa.reenvio@ejemplo.test`; sin activar, ir a `/auth/reenviar-activacion`
y pedir el reenvío. En Mailpit hay dos correos: abrí **el primero**.

**Esperado:** el primer enlace falla (mensaje humano); el segundo activa.

**DB:**

```sql
SELECT t.id, t.tipo, t.created_at, t.usado_en, t.invalidado_en
FROM token_verificacion t JOIN cuenta c ON c.id = t.cuenta_id
WHERE c.email_normalizado = 'qa.reenvio@ejemplo.test' ORDER BY t.id;
```

Esperado: dos tokens `ACTIVACION`; el primero con `invalidado_en` lleno y `usado_en` NULL, el
segundo con `usado_en` lleno.

**Auditoría:** `ACTIVACION_REENVIADA` y después `CUENTA_ACTIVADA`.

---

### ON-07 · Restablecer la contraseña revoca las sesiones abiertas — `[E2E cuenta-ciclo-real]`

**Pantalla:** con admin-a ingresada en una ventana, abrir otra ventana privada →
`/auth/olvide-mi-contrasena` → pedir el reset → abrir el enlace de Mailpit (`/auth/restablecer`) →
contraseña nueva. Volver a la primera ventana y navegar.

**Esperado:** el pedido responde igual exista o no la cuenta. Tras el canje, la sesión de la primera
ventana muere al refrescar (vuelve al login). La contraseña vieja ya no entra; la nueva sí.
**Actualizá la contraseña en tu `qa-config.md`.**

**DB:**

```sql
SELECT tipo, created_at, usado_en, invalidado_en
FROM token_verificacion WHERE cuenta_id = @cuenta_admin_a AND tipo = 'RESET' ORDER BY id;

SELECT id, familia_id, revocado_en, motivo_revocacion
FROM refresh_token WHERE cuenta_id = @cuenta_admin_a ORDER BY id DESC LIMIT 5;

SELECT tipo, estado FROM notification_outbox
WHERE destinatario = @email_admin_a AND tipo = 'RESET_PASSWORD';
```

Esperado: un token `RESET` usado; **todos** los refresh que estaban vivos antes del canje con
`motivo_revocacion = RESET_PASSWORD`; la notificación `RESET_PASSWORD` `ENVIADA`.

**Auditoría:** `RESET_SOLICITADO`, `RESET_COMPLETADO`.

---

### ON-08 · Cerrar sesión

**Pantalla:** menú de usuario → cerrar sesión.

**Esperado:** vuelve al login; el botón "atrás" del navegador no muestra datos (contexto sin fuga,
`[E2E contexto-sin-fuga]`).

**DB:**

```sql
SELECT id, revocado_en, motivo_revocacion
FROM refresh_token WHERE cuenta_id = @cuenta_admin_a ORDER BY id DESC LIMIT 3;
```

Esperado: el refresh de la sesión con `motivo_revocacion = LOGOUT`.

**Auditoría:** `SESION_CERRADA` con `entity_type = 'Cuenta'`.

---

### ON-09 · Las demás cuentas sintéticas

**Pantalla:** dar de alta y activar, respetando 5 altas por minuto: `qa.profesional@`,
`qa.administrativo@`, `qa.paciente@` y `qa.admin-b@ejemplo.test` (organización `Centro QA B`).

**DB:**

```sql
SELECT c.email, c.estado, m.organization_id, m.role_code, m.is_founder
FROM cuenta c JOIN membership m ON m.account_id = c.id
WHERE c.email_normalizado IN (@email_admin_a, @email_admin_b, @email_profesional, @email_administrativo, @email_paciente)
ORDER BY c.email, m.organization_id;
```

Esperado: las cinco `ACTIVA`, cada una fundadora (`ORG_ADMIN`) de su propia organización. Fijá
`@org_b` y `@sede_b` con el bloque del §4.1 del README.

---

### ON-10 · El contexto de una cuenta con dos organizaciones — `[E2E contexto-sin-fuga]`

Se completa después de [ORG-04](02-organizacion-sedes-colaboradores.md), cuando profesional tiene
membership en A además de la suya.

**Pantalla:** ingresar con profesional: el selector ofrece **dos** contextos. Elegir A, mirar el
padrón; volver al selector y elegir su propia organización.

**Esperado:** al cambiar, **nada** de A queda en pantalla (padrón, agenda, encabezado).

**DB:**

```sql
SET @cuenta_profesional := (SELECT id FROM cuenta WHERE email_normalizado = @email_profesional);
SELECT organization_id, consultorio_id, updated_at FROM account_active_context WHERE account_id = @cuenta_profesional;
SELECT id, event_type, organization_id, details, occurred_at
FROM audit_event
WHERE actor_account_id = @cuenta_profesional AND event_type IN ('CONTEXTO_SELECCIONADO', 'CONTEXTO_RECHAZADO')
ORDER BY id DESC LIMIT 5;
```

Esperado: el contexto activo es el último elegido; un `CONTEXTO_SELECCIONADO` por cada elección,
cada uno con su `organization_id`.

---

### ON-11 · La sesión guarda la IP del cliente, no la del proxy (api #93)

Desde `appKine-api` #93 la sesión (`refresh_token.ip`) sale del mismo resolutor que el rate limit
(#82): `X-Forwarded-For` **solo** si el pedido viene de un proxy declarado en
`AKINE_TRUSTED_PROXIES`; si no, la IP del socket. **No hay pantalla** que liste las sesiones
abiertas: se mira en la base.

**Pantalla:** ingresar con admin-a desde el navegador (`npm start`, proxy de desarrollo).

```sql
SELECT id, ip, LEFT(user_agent, 60) AS user_agent, emitido_en, revocado_en
FROM refresh_token WHERE cuenta_id = @cuenta_admin_a ORDER BY id DESC LIMIT 3;
```

Esperado con el stack de desarrollo de esta guía (README §2, sin `AKINE_TRUSTED_PROXIES`): `ip` es
la del socket que ve el backend —`127.0.0.1` o `::1` (`0:0:0:0:0:0:0:1`) desde el proxy de
`ng serve`— y el `user_agent` es el del navegador. **Lo que importa probar va con las imágenes
Docker** (frontend detrás del nginx de `appKine-web`): sin `AKINE_TRUSTED_PROXIES`, todas las
sesiones quedan con la IP del contenedor nginx; con `AKINE_TRUSTED_PROXIES` = la red de ese
contenedor, quedan con la IP real del navegador. Si ves la IP del nginx **con** el proxy declarado,
es hallazgo. Un `X-Forwarded-For` falso mandado desde un cliente que **no** es proxy de confianza
(`curl -H "X-Forwarded-For: 203.0.113.9"` directo al 8080) **no** debe aparecer en `ip`.

> Validado sobre un stack aislado: el backend en un contenedor, sin proxy declarado, registró la IP
> del gateway de la red de Docker en las 20 sesiones del sembrado (la del socket).

---

### ON-12 · La base no responde: 503 y la sesión sigue viva (api #91, web #62)

Desde `appKine-api` #91, una falla **de conexión** con la base responde **503
`service-unavailable`** —en los filtros de seguridad y en los controllers—, nunca 401: antes la
autenticación respondía 401 y el frontend borraba el token. Desde `appKine-web` #62 el frontend
muestra un aviso arriba de la pantalla ("Servicio no disponible…", región `status`, con botón
"Cerrar aviso") y **no desloguea**. Cualquier otro error de base (un bug) sigue siendo 500.

**Pantalla:** ingresado con admin-a en el padrón (`/pacientes`). En otra terminal, **pausá** la base
de la corrida (no la apagues: `pause` no cierra las conexiones y se recupera sin reiniciar nada):

```powershell
docker pause akine-mysql
```

Recargá el padrón (o pasá a la agenda). El pedido tarda **unos 30 segundos** (el `connection-timeout`
del pool) y vuelve con 503. Después:

```powershell
docker unpause akine-mysql
```

**Esperado:** durante la pausa, el aviso de servicio no disponible; **ninguna** redirección a
`/auth/ingresar`; en DevTools → Network, el pedido con status 503 y cuerpo `type`
`…/problems/service-unavailable`, detalle "Su sesion sigue valida: intente de nuevo en unos
segundos". Tras `unpause`, "Reintentar" o recargar trae los datos **con la misma sesión** (sin volver
a ingresar).

```sql
SELECT id, revocado_en, motivo_revocacion FROM refresh_token
WHERE cuenta_id = @cuenta_admin_a ORDER BY id DESC LIMIT 3;
```

Esperado después del `unpause`: el refresh vigente **no** quedó revocado por el corte. **Auditoría:**
nada nuevo (el 503 no es un hecho de negocio).

> Validado sobre el stack aislado: con la base pausada, `GET /api/v1/personas` respondió 503
> `service-unavailable` a los 30,7 s; después del `unpause`, el mismo token respondió 200.

---

### ON-13 · La misma `Idempotency-Key` con otros datos es 409 (api #104, web #73) — `[IT IdempotenciaYUniquesIT]`

Hasta el 13/10 el alta self-service reconocía el reintento **solo** por la clave: la misma
`Idempotency-Key` con otro email u otra organización respondía el 202 del pedido original y la
persona esperaba un correo que nunca llegaba (escenario diferido 7b). Desde `V87` el registro
guarda el SHA-256 del pedido canónico (`onboarding_registro.request_hash`, **sin** la contraseña) y
la misma clave con otro contenido es **409 `idempotency-key-conflict`**. El IT prueba el replay y
el conflicto; la pantalla y la base, no.

**Pantalla:** `/auth/registro` genera una clave por contenido: si cambiás un campo y volvés a
enviar, sale con clave nueva. El 409 por pantalla solo aparece si el backend ya tiene esa clave con
otros datos; si aparece, el mensaje es "Ya hay un registro en curso con otros datos. Recarga la
pagina e intenta de nuevo." y el próximo envío sale con otra clave. Lo que se prueba es la API
(sin token, es público):

```powershell
$k = Nueva-Clave
$alta = @{ email = 'qa.idem@ejemplo.test'; password = '<contraseña de qa-config>'; firstName = 'QA'; lastName = 'Idem'; organizationName = 'Centro QA Idem' }
(Api POST '/api/v1/auth/register' $null $alta @{ 'Idempotency-Key' = $k }).Status                                   # 202
(Api POST '/api/v1/auth/register' $null $alta @{ 'Idempotency-Key' = $k }).Status                                   # 202: replay
$otra = $alta.Clone(); $otra.password = '<otra contraseña válida>'
(Api POST '/api/v1/auth/register' $null $otra @{ 'Idempotency-Key' = $k }).Status                                   # 202: la contraseña no entra al hash
$otra = $alta.Clone(); $otra.organizationName = 'Otro nombre QA'
Api POST '/api/v1/auth/register' $null $otra @{ 'Idempotency-Key' = $k }                                            # 409 idempotency-key-conflict
$otra = $alta.Clone(); $otra.email = 'qa.admin-a@ejemplo.test'
(Api POST '/api/v1/auth/register' $null $otra @{ 'Idempotency-Key' = $k }).Tipo                                     # idempotency-key-conflict, igual con un email existente
```

**Esperado:** el 409 dice "La clave de idempotencia ya fue usada para una solicitud con otro
contenido" y **no** dice nada de la cuenta ni del email (la respuesta es la misma exista o no). Un
solo correo de activación en Mailpit para `qa.idem@ejemplo.test`; ninguno por los 409.

```sql
SELECT id, estado, email_normalizado, organization_id IS NOT NULL AS creo_organizacion,
       LENGTH(request_hash) AS largo_hash, created_at
FROM onboarding_registro WHERE email_normalizado = 'qa.idem@ejemplo.test' ORDER BY id;

SELECT COUNT(*) AS organizaciones_del_conflicto FROM organization WHERE name = 'Otro nombre QA';
```

Esperado: **una** fila `COMPLETADO` con `largo_hash = 64` (las filas anteriores a `V87` lo tienen
NULL y su reintento se acepta como replay); cero organizaciones "Otro nombre QA". **Auditoría:**
nada nuevo: el 409 no es un hecho de negocio y la identidad no tiene `organization_id`.

> Validado sobre el stack aislado (14/10): replay 202, solo otra contraseña 202, otro nombre de
> organización 409, otro email 409, y con un email ya registrado (fila `DUPLICADO`) también 409.
