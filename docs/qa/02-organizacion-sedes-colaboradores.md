# 02 · Organización, sedes, colaboradores y permisos — P1

Módulo `organization` (M01, M03, M05) y el evaluador de permisos. La matriz se prueba por HTTP en
`MatrizDePermisosIT` (cinco roles × once familias) y los grants por rol en G-5, **pero ningún test
pasa por la pantalla**: acá se verifica que la pantalla ofrece lo que el rol puede, que oculta lo que
no, y que lo que se escribe queda bien en la base.

Tablas: `organization`, `consultorio`, `consultorio_alta`, `consultorio_horario`, `espacio`,
`membership`, `membership_grant`, `colaborador_invitacion`, `subscription`, `plan_limit`.

> **Los códigos de permiso se guardan con el nombre del enum**, no con el código de la API:
> `paciente:manage` en la pantalla es `PACIENTE_MANAGE` en `membership_grant.permission_code`.

---

### ORG-01 · Ver y editar la organización

**Pantalla:** admin-a → `/organizacion`. Cambiar el nombre visible.

**Esperado:** guarda y muestra el nombre nuevo. `ORG_ADMIN` **no** puede cambiar de plan ni
suspender: eso es de plataforma (matriz §4, "Limitado"). Con el administrativo, `/organizacion` no
permite editar.

```sql
SELECT id, name, slug, timezone, version, updated_at FROM organization WHERE id = @org_a;
```

Esperado: `name` nuevo, `version` +1, `slug` sin cambios.

**Auditoría:** `ORGANIZATION_UPDATED` con estado anterior y nuevo.

---

### ORG-02 · Editar la sede

**Pantalla:** `/organizacion/sedes` → la sede de A → datos legales (razón social, CUIT, dirección,
teléfono, email de contacto) y duración de slot.

```sql
SELECT id, name, legal_name, tax_id, address_line, phone, contact_email, timezone, slot_minutes, active, version
FROM consultorio WHERE id = @sede_a;
```

**Auditoría:** `CONSULTORIO_UPDATED`.

---

### ORG-03 · El plan `BASICO` no deja abrir una segunda sede

**Pantalla:** `/organizacion/sedes/nueva` con admin-a, completar y enviar.

**Esperado:** rechazo con un mensaje que habla del plan (409), **sin** sede creada a medias.

```sql
SELECT p.code, l.limit_code, l.limit_value
FROM subscription s JOIN plan p ON p.id = s.plan_id JOIN plan_limit l ON l.plan_id = p.id
WHERE s.organization_id = @org_a AND l.active = 1;

SELECT COUNT(*) AS sedes FROM consultorio WHERE organization_id = @org_a;
SELECT COUNT(*) AS altas_registradas FROM consultorio_alta WHERE organization_id = @org_a;
```

Esperado: `MAX_CONSULTORIOS = 1`; sigue habiendo **1** sede y ninguna fila nueva en
`consultorio_alta`.

**Auditoría:** `PLAN_LIMIT_REJECTED`.

> Que el `ORG_ADMIN` no pueda cambiar su propio plan es una decisión pendiente conocida (ver
> [90](90-conocido-y-aceptado.md)). El cambio lo hace plataforma en [PLA-06](10-plataforma.md);
> después de ese paso se corre ORG-03b.

### ORG-03b · Alta de sede en un acto, con primer box y horario general — `[IT AltaDeSedeEnUnActoIT]`

**Pantalla:** con el plan ya en `PROFESIONAL`, `/organizacion/sedes/nueva`: nombre `Sede QA 2`,
primer box `Box 1`, horario general lunes a viernes 08:00–18:00.

```sql
SET @sede_a2 := (SELECT MAX(id) FROM consultorio WHERE organization_id = @org_a);
SELECT id, name, timezone, slot_minutes FROM consultorio WHERE id = @sede_a2;
SELECT id, name, tipo, capacidad, active FROM espacio WHERE consultorio_id = @sede_a2;
SELECT dia_semana, hora_desde, hora_hasta, active FROM consultorio_horario WHERE consultorio_id = @sede_a2 ORDER BY dia_semana;
SELECT idempotency_key, consultorio_id, created_at FROM consultorio_alta WHERE consultorio_id = @sede_a2;
```

Esperado: la sede, **un** box (`tipo = BOX`), cinco franjas y la fila de idempotencia, todo creado
en la misma transacción. Doble click en "Abrir" no crea dos sedes (misma `Idempotency-Key`).

**Auditoría:** `CONSULTORIO_CREATED`, `ESPACIO_CREATED`, `HORARIO_GENERAL_UPDATED`.

---

### ORG-03c · Baja de la sede 2: turnos pendientes, y reservar en una sede de baja (api #96) — `[IT BajaDeSedeContraReservaIT]`

Se corre al final de la corrida, o con una sede de prueba aparte: **la baja no tiene vuelta
atrás** (no hay reactivación). Requiere la `Sede QA 2` de ORG-03b con una oferta, un profesional
con horario y **un turno futuro reservado** (CAT-04 a CAT-08 y AGE-01 sobre esa sede).

Desde #96 la baja toma la fila de la sede con lock exclusivo **antes** de contar los turnos
pendientes, y reservar, reprogramar y crear o mover una serie la releen con lock compartido bajo el
de la agenda: o la baja ve el turno, o la reserva ve la sede de baja. La carrera la prueba
`BajaDeSedeContraReservaIT`; a mano, los dos desenlaces en secuencia.

**Pantalla:** admin-a → `/organizacion/sedes` → `Sede QA 2` → "Dar de baja" con motivo →
"Confirmar la baja".

1. **Con el turno pendiente:** rechazo 409 `consultorio-has-active-references` con un mensaje que
   habla de turnos y su cantidad (`referenceType = turnos-futuros`, `referenceCount`). La sede sigue
   activa.
2. Cancelá el turno (AGE-06) y repetí: **200**, la sede pasa a dada de baja y desaparece del
   selector de contexto.
3. **Reservar en la sede de baja**, por la API, con admin-a en contexto de la **sede A** (alcance de
   organización) y con un token que tenía contexto en la sede 2:

```powershell
Api POST "/api/v1/consultorios/<sede_a2>/turnos/ofertas/<oferta de la sede 2>" $A @{ inicio = '<un slot futuro, ISO UTC>'; personaId = <persona>; profesionalId = <membership profesional en la sede 2>; idempotencyKey = (Nueva-Clave) }
```

Esperado: con contexto en la sede A, **409 `consultorio-inactive`** ("La sede … esta dada de baja:
no admite turnos nuevos"); con el token viejo de contexto en la sede 2, **404** (el contexto de una
sede de baja ya no vale). Ningún turno nuevo. Cancelar o marcar ausente un turno de una sede de baja
**sí** funciona: liberar su agenda es justamente lo que hay que poder hacer.

```sql
SET @sede_a2 := (SELECT MAX(id) FROM consultorio WHERE organization_id = @org_a);
SELECT id, name, active, deleted_at, deactivation_reason, version FROM consultorio WHERE id = @sede_a2;
SELECT id, estado, inicio, deleted_at FROM turno WHERE consultorio_id = @sede_a2 ORDER BY id;
SELECT COUNT(*) AS turnos_vivos_en_sede_de_baja
FROM turno t JOIN consultorio c ON c.id = t.consultorio_id
WHERE c.organization_id = @org_a AND c.active = 0 AND t.deleted_at IS NULL AND t.estado IN ('RESERVADO', 'CONFIRMADO');
```

Esperado: `active = 0` con `deleted_at` y el motivo; todos los turnos de la sede 2 cancelados
(`deleted_at` lleno); **`turnos_vivos_en_sede_de_baja = 0`**: un turno vivo en una sede de baja es
el defecto que #96 cerró y es hallazgo **alto**. **Auditoría:** `CONSULTORIO_DEACTIVATED` con
`reason`; el 409 de la baja y el de la reserva no dejan fila.

> Validado sobre el stack aislado (14/10): baja con dos turnos pendientes → 409 con
> `referenceCount = 2`; cancelados los dos, 200; reservar después, 409 `consultorio-inactive` con
> contexto en la sede A y 404 con el contexto de la sede 2.

---

### ORG-04 · Vincular colaboradores por alta directa

**Pantalla:** `/organizacion/colaboradores/nuevo` con admin-a. Vincular, en la sede de A:
`qa.profesional@` como `PROFESIONAL`, `qa.administrativo@` como `ADMINISTRATIVO` y `qa.paciente@`
como `PACIENTE`. El motivo es obligatorio.

**Esperado:** los tres aparecen en `/organizacion/colaboradores`. Un email sin cuenta responde 404
"no existe" —es la excepción declarada a la uniformidad (threat model §2.1), con su propio cupo de
10 por minuto—.

```sql
SELECT m.id, c.email, m.role_code, m.consultorio_id, m.estado, m.is_founder, m.valid_from
FROM membership m JOIN cuenta c ON c.id = m.account_id
WHERE m.organization_id = @org_a ORDER BY m.id;

SET @membership_profesional    := (SELECT m.id FROM membership m JOIN cuenta c ON c.id = m.account_id
                                   WHERE m.organization_id = @org_a AND c.email_normalizado = @email_profesional AND m.estado = 'ACTIVA' AND m.consultorio_id = @sede_a);
SET @membership_administrativo := (SELECT m.id FROM membership m JOIN cuenta c ON c.id = m.account_id
                                   WHERE m.organization_id = @org_a AND c.email_normalizado = @email_administrativo AND m.estado = 'ACTIVA' AND m.consultorio_id = @sede_a);
SET @membership_paciente       := (SELECT m.id FROM membership m JOIN cuenta c ON c.id = m.account_id
                                   WHERE m.organization_id = @org_a AND c.email_normalizado = @email_paciente AND m.estado = 'ACTIVA' AND m.consultorio_id = @sede_a);
SELECT @membership_profesional, @membership_administrativo, @membership_paciente;
```

Esperado: tres memberships `ACTIVA` con `consultorio_id = @sede_a`.

**Auditoría:** `MEMBERSHIP_CREATED`, `reason` = el motivo escrito, `details.accountId`. Un email
inexistente deja `MEMBERSHIP_ALTA_RECHAZADA`.

---

### ORG-05 · Tope de 5 miembros activos del plan `BASICO`

Antes de [PLA-06](10-plataforma.md). Con admin-a + los tres de ORG-04 hay 4.

**Pantalla:** vincular un quinto (p. ej. `qa.reenvio@` de ON-06) → entra. Vincular un sexto
(`qa.admin-b@`) → rechazo por plan.

```sql
SELECT COUNT(*) AS activos FROM membership WHERE organization_id = @org_a AND estado = 'ACTIVA';
```

Esperado: 5, no 6. **Auditoría:** `PLAN_LIMIT_REJECTED`. Después desvinculá al quinto (ORG-11)
para dejar lugar a la invitación de ORG-06.

---

### ORG-06 · Invitar por correo y aceptar

**Pantalla:** `/organizacion/colaboradores/invitaciones` → invitar a `qa.invitado@ejemplo.test`
como `ADMINISTRATIVO` en la sede de A. En Mailpit, abrir el enlace (`/auth/invitacion?token=...`),
completar nombre, apellido y contraseña, aceptar.

**Esperado:** la invitación pasa a aceptada y el invitado entra con contexto en A. Reenviar
invalida el enlace anterior; cancelar una pendiente deja el enlace inútil.

```sql
SELECT id, email_normalizado, role_code, consultorio_id, estado, expira_en, resuelta_en,
       aceptada_por_account_id, membership_id, LENGTH(token_hash) AS largo_hash
FROM colaborador_invitacion WHERE organization_id = @org_a ORDER BY id;

SELECT tipo, estado FROM notification_outbox WHERE destinatario = 'qa.invitado@ejemplo.test' ORDER BY id;

SELECT c.estado, m.role_code, m.consultorio_id, m.estado AS membership
FROM cuenta c JOIN membership m ON m.account_id = c.id
WHERE c.email_normalizado = 'qa.invitado@ejemplo.test' AND m.organization_id = @org_a;
```

Esperado: invitación `ACEPTADA` con `membership_id` y `aceptada_por_account_id`; notificación
`INVITACION_COLABORADOR` `ENVIADA`; la cuenta nueva `ACTIVA` con membership `ADMINISTRATIVO`.

**Auditoría:** `INVITACION_EMITIDA`, `INVITACION_ACEPTADA` (y `INVITACION_REENVIADA` /
`INVITACION_CANCELADA` si los probaste).

---

### ORG-07 · Grants que la matriz admite — `[E2E flujo-colaboradores]`

**Pantalla:** `/organizacion/colaboradores` → profesional → permisos adicionales → otorgar
`paciente:manage` ("Gestionar paciente: según permiso") con motivo.

```sql
SELECT g.id, g.permission_code, g.grant_activo, g.reason, g.valid_from, g.valid_until, g.active, g.revoked_reason
FROM membership_grant g WHERE g.organization_id = @org_a ORDER BY g.id;
```

Esperado: fila `PACIENTE_MANAGE`, `active = 1`. Con el profesional ingresado, el padrón ahora le
permite dar de alta una persona.

Los grants que la matriz admite (G-5, matriz §16.1), más los **redundantes** que `appKine-api` #85
también acepta: un permiso que el rol ya tiene por base se puede otorgar —no agrega nada, y
rechazarlo rompería un grant hecho antes de un cambio de rol—:

| Permiso | Otorgable a (celda de la matriz) | Además, redundante para |
|---|---|---|
| `auditoria:read-clinica` | `ORG_ADMIN`, `CONSULTORIO_ADMIN` | — |
| `paciente:manage` | `PROFESIONAL` | `ORG_ADMIN`, `CONSULTORIO_ADMIN`, `ADMINISTRATIVO` |
| `hc:read` | `ORG_ADMIN`, `CONSULTORIO_ADMIN` | `PROFESIONAL` |
| `hc:write` | `CONSULTORIO_ADMIN` | `PROFESIONAL` |

**Pantalla (web #48):** el selector de "permisos adicionales" ofrece **solo** lo que devuelve
`GET /api/v1/organizations/<org_a>/roles/<rol>/permisos-otorgables` para el rol de esa persona,
incluidos los redundantes (la pantalla no conoce los permisos base). Abrí el panel de cada
colaborador y compará con la tabla: profesional → `hc:read`, `hc:write`, `paciente:manage`;
administrativo → solo `paciente:manage`; paciente → **nada** (el selector vacío lo explica). Por
la API, `PLATFORM_ADMIN` o un rol inexistente responden 400. Si la consulta de otorgables falla, el
selector queda vacío con un mensaje: **no** debe ofrecer el catálogo completo.

Un grant que el rol **actual** ya no admite se sigue listando con la marca **"No vigente con el rol
actual"** y el botón de baja (ver ORG-09).

**Auditoría:** `GRANT_ASSIGNED` con `reason`. Revocarlo deja `GRANT_REVOKED` y la fila con
`active = 0` y `revoked_reason`, **no se borra**.

---

### ORG-08 · Grants que la matriz prohíbe — `[IT MatrizDePermisosIT]`

**Pantalla:** intentar, uno por uno: `hc:write` al administrativo (Editar HC: "No"), `hc:read` al
administrativo (Ver HC "Limitado" = nunca contenido clínico), `paciente:manage` al paciente,
`auditoria:read-clinica` al profesional. Si la pantalla no ofrece el permiso, **eso es lo
correcto**: probalo además por la API (README §5):

```powershell
$A = Entrar 'qa.admin-a@ejemplo.test'
Api POST "/api/v1/organizations/<org_a>/memberships/<membership_administrativo>/grants" $A @{ permissionCode = 'hc:write'; reason = 'QA G-12: grant prohibido' }
```

**Esperado:** **400 `permiso-no-otorgable-al-rol`** (desde `appKine-api` #85; antes era
`validation-error`) con `permissionCode` y `roleCode` en el cuerpo y el detalle "La matriz de
permisos no admite otorgar hc:write al rol ADMINISTRATIVO". La pantalla redacta el mensaje con esos
dos datos. Ninguna fila nueva.

```sql
SELECT g.permission_code, m.role_code, g.created_at
FROM membership_grant g JOIN membership m ON m.id = g.membership_id
WHERE g.organization_id = @org_a AND g.created_at >= @desde
  AND NOT ((g.permission_code = 'PACIENTE_MANAGE' AND m.role_code IN ('PROFESIONAL', 'ORG_ADMIN', 'CONSULTORIO_ADMIN', 'ADMINISTRATIVO'))
        OR (g.permission_code = 'AUDITORIA_READ_CLINICA' AND m.role_code IN ('ORG_ADMIN', 'CONSULTORIO_ADMIN'))
        OR (g.permission_code = 'HC_READ' AND m.role_code IN ('ORG_ADMIN', 'CONSULTORIO_ADMIN', 'PROFESIONAL'))
        OR (g.permission_code = 'HC_WRITE' AND m.role_code IN ('CONSULTORIO_ADMIN', 'PROFESIONAL')));
```

Esperado: **cero filas**. Una sola fila es hallazgo crítico (un grant que la matriz prohíbe). La
consulta admite los redundantes de ORG-07 y mira el rol **actual**: un grant que quedó "No vigente"
por un cambio de rol posterior (ORG-09) también aparece, y ése **no** es hallazgo — confirmalo con un
`MEMBERSHIP_ROLE_CHANGED` posterior al `GRANT_ASSIGNED`.

---

### ORG-09 · Cambiar el rol de una membership — `[E2E flujo-colaboradores]`

**Pantalla:** cambiar el rol del administrativo a `CONSULTORIO_ADMIN` con motivo, otorgarle `hc:write`
(admitido para ese rol), y devolverlo a `ADMINISTRATIVO`.

**Esperado:** el grant `hc:write` **sigue listado**, con la marca "No vigente con el rol actual"
(web #48; en la respuesta, `vigenteConElRolActual = false`), pero ya no concede nada: el evaluador ignora el
grant que el rol actual no admite (G-5). Con el administrativo ingresado, una lectura de historia
clínica sigue siendo 403.

```sql
SELECT m.role_code, m.version, g.permission_code, g.active
FROM membership m LEFT JOIN membership_grant g ON g.membership_id = m.id
WHERE m.id = @membership_administrativo;
```

**Auditoría:** dos `MEMBERSHIP_ROLE_CHANGED` y un `GRANT_ASSIGNED`. Que el grant inútil se siga
listando es el riesgo residual R11 del threat model, no un hallazgo.

---

### ORG-10 · Suspender y reactivar

**Pantalla:** con el administrativo ingresado en otra ventana, admin-a lo suspende con motivo. En la
ventana del administrativo, recargar la agenda.

**Esperado:** el administrativo pierde acceso **sin esperar a que venza su token**: cada request
re-resuelve la membership contra la base. Reactivar devuelve el acceso.

```sql
SELECT id, estado, revoked_reason, revoked_by_account_id, version FROM membership WHERE id = @membership_administrativo;
```

Esperado: `SUSPENDIDA` y después `ACTIVA`. **Auditoría:** `MEMBERSHIP_SUSPENDED`,
`MEMBERSHIP_REACTIVATED`, los dos con `reason`.

---

### ORG-11 · Desvincular con impacto previo

**Pantalla:** `/organizacion/colaboradores` → desvincular al quinto de ORG-05. Antes de confirmar,
la pantalla muestra el impacto (turnos futuros, casos a cargo).

```sql
SELECT id, estado, valid_until, revoked_reason, revoked_by_account_id, active, deleted_at
FROM membership WHERE organization_id = @org_a AND estado = 'REVOCADA';
```

Esperado: `REVOCADA`, con `valid_until` y motivo; la fila **no se borra** (baja lógica, la autoría
histórica se conserva). **Auditoría:** `MEMBERSHIP_REVOKED`.

---

### ORG-12 · Último administrador y auto-revocación

**Pantalla:** admin-a intenta suspenderse o revocarse a sí misma, y quitarse el rol `ORG_ADMIN`.

**Esperado:** rechazo (409): una organización no puede quedar sin `ORG_ADMIN` vigente y nadie se
revoca su último rol administrativo (matriz §7). Nada cambia en la base:

```sql
SELECT COUNT(*) AS org_admins_vigentes
FROM membership WHERE organization_id = @org_a AND role_code = 'ORG_ADMIN' AND estado = 'ACTIVA';
```

Esperado: ≥ 1.

---

### ORG-13 · La matriz por pantalla, rol por rol — `[IT MatrizDePermisosIT]` · `[E2E cuenta-ciclo-real]` (un caso)

Ingresá con cada cuenta (en A) y recorré el menú. Lo esperado por rol (matriz §2, §6, enmiendas
§14–§16, DP-15, DP-22):

| Pantalla / acción | ORG_ADMIN | ADMINISTRATIVO | PROFESIONAL | PACIENTE |
|---|---|---|---|---|
| Organización, suscripción | ver y editar | — | — | — |
| Colaboradores, invitaciones | gestionar | ver | ver | — |
| Auditoría | ver (sin contenido clínico salvo grant) | — | — | — |
| Padrón y ficha de persona | ver y gestionar | ver y gestionar | ver; gestionar solo con grant | **403** |
| Agenda, recepción | sí | sí | sí | — |
| Atención clínica (sesión) | — | — | sí | — |
| Cobros y cuenta corriente | sí | sí | — | — |
| Caja | sí | sí | — | — |
| Presentaciones | sí | sí | — | — |
| Convenios, financiadores | gestionar | solo leer (*) | solo leer (*) | solo leer (*) |
| Reportes | todo | operativos y caja | solo su actividad | — |

(*) Las lecturas de `contracting` se autorizan por pertenencia (matriz §13.4): cualquier membership
las ve. Es conocido, ver [90](90-conocido-y-aceptado.md); no se reporta.

**Esperado:** lo que el rol no puede, o no aparece o lleva a `/sin-permiso`. Si entrás igual por URL,
el backend responde **403** (nunca 200 con datos vacíos).

```sql
SELECT a.id, c.email, a.consultorio_id, a.details, a.occurred_at
FROM audit_event a JOIN cuenta c ON c.id = a.actor_account_id
WHERE a.organization_id = @org_a AND a.event_type = 'PERMISSION_DENIED' AND a.occurred_at >= @desde
ORDER BY a.id DESC LIMIT 20;
```

Esperado: una fila por cada 403 que provocaste, con `details.permissionCode` y `denialKind`.

---

### ORG-14 · Auditoría por pantalla y redacción de lo clínico

Se corre después del circuito [06](06-atencion-clinica.md), cuando ya hay eventos clínicos.

**Pantalla:** admin-a → `/organizacion/auditoria`. Filtrar por entidad `Sesion` o `HistoriaClinica`.

**Esperado:** sin `auditoria:read-clinica`, los eventos clínicos aparecen con `reason` y `details`
**redactados**. Otorgá el grant (ORG-07) y volvé a mirar: ahora se ven. La auditoría de B no es
alcanzable desde A (ver [AIS-03](11-aislamiento-y-rol-paciente.md)).

```sql
SELECT event_type, COUNT(*) AS n, SUM(reason IS NOT NULL) AS con_reason
FROM audit_event
WHERE organization_id = @org_a AND occurred_at >= @desde
  AND event_type REGEXP '^(HISTORIA_CLINICA|ENTRADA_CLINICA|ADJUNTO_CLINICO|CASO_|PLAN_|SESION_|TRATAMIENTO_|TIMELINE)'
GROUP BY event_type ORDER BY event_type;
```

Compará: lo que la base guarda contra lo que la pantalla muestra sin y con el grant.

---

### ORG-15 · Notificaciones fallidas y reintento por fila (web #46) — `[E2E flujo-notificaciones]`

**Pantalla nueva:** `/organizacion/colaboradores/notificaciones` (también desde la pantalla de
invitaciones). Lista las notificaciones **de la organización** con filtro por estado; por defecto
`FALLIDA` y `AGOTADA`, las únicas que admiten "Reintentar". Exige `colaborador:manage` (criterio
provisorio, ver [90](90-conocido-y-aceptado.md)).

El E2E llega a `FALLIDA` **escribiendo la base**, cosa que esta guía prohíbe (README §1, regla 3).
Por el producto, el camino es **bajar el servidor de correo**:

1. `docker stop akine-mailpit`.
2. admin-a → invitar `qa.notif@ejemplo.test` como administrativo (ORG-06).
3. A los pocos segundos la fila queda `REINTENTABLE` con el error SMTP sanitizado. Marcá
   `REINTENTABLE` en el filtro de la pantalla: aparece, **sin** botón de reintento.
4. El backoff es 1, 5, 15 y 60 minutos: con el correo abajo, la fila llega a **`AGOTADA` unos 81
   minutos después** del primer intento. Mientras tanto corré circuitos que no dependan de correos.
5. Con la fila `AGOTADA` en la pantalla: `docker start akine-mailpit` → "Reintentar" en la fila.

```sql
SELECT id, tipo, estado, intentos, max_intentos, proxima_ejecucion_en, LEFT(error_sanitizado, 120) AS error,
       enviado_en, updated_at
FROM notification_outbox WHERE organization_id = @org_a ORDER BY id DESC LIMIT 5;
```

Esperado: `intentos` sube con cada falla y `proxima_ejecucion_en` respeta el backoff;
`error_sanitizado` **no** trae host, credenciales ni el enlace (solo la clase de error, p. ej.
`UnknownHostException`); al llegar a `max_intentos`, `AGOTADA`. Tras "Reintentar": vuelve a la
cola con el contador de intentos reiniciado y en segundos `ENVIADA`, con el correo en Mailpit y
`error_sanitizado` en `NULL` (una fila enviada no arrastra el error del intento anterior).
**Variante:** si en el paso 3 cancelás la invitación mientras la fila espera, en el próximo
intento —con el correo arriba o abajo— la fila queda `AGOTADA` con `error_sanitizado = 'No se envia: la invitacion fue cancelada'`, sin
`enviado_en`, y Mailpit no recibe nada; "Reintentar" la vuelve a dejar igual.
Reintentar una fila que no está `FALLIDA` ni `AGOTADA` es 409 `conflict`. **Auditoría:**
`NOTIFICACION_REINTENTADA` (`entity_type = 'Notificacion'`).

> Los dos defectos que la validación del 12/10 había encontrado acá —la fila `ENVIADA` que
> conservaba el error del intento anterior y el correo de una invitación cancelada que salía igual
> al volver el servidor— **están corregidos en #100** (`InvitacionAnuladaNoSeEnviaIT`). Si
> cualquiera de los dos se reproduce, es **regresión**: registralo como hallazgo nuevo y citá #100.

---

### ORG-16 · Cada invitación con su propio enlace (api #111) — `[IT InvitacionesIT]`

Hasta #111 la llave del enlace de una invitación en el `SecureLinkVault` era **solo el vencimiento
en milisegundos** (el último segmento de `invitacion:<id>:<expiraEn ms>`): dos invitaciones
emitidas en el mismo milisegundo compartían entrada y **el primer invitado recibía el enlace del
segundo**. Desde #111 la referencia es la clave entera. `InvitacionesIT` lo prueba con dos
invitaciones en el mismo instante; por pantalla no se llega al mismo milisegundo, así que a mano se
verifica la forma de la referencia y que cada correo abra **su** invitación.

**Pantalla:** admin-a → `/organizacion/colaboradores/invitaciones` → invitar seguido a
`qa.inv1@ejemplo.test` y `qa.inv2@ejemplo.test` (administrativo, sede de A). Después reenviá la
primera. En Mailpit abrí cada enlace en una ventana privada.

**Esperado:** cada enlace muestra **su** email en la vista previa de la invitación; el enlace
original de la primera, después del reenvío, ya no sirve (vista previa 404) y el nuevo sí.

```sql
SELECT n.id, n.estado, n.referencia_token_id, n.clave_idempotente,
       n.referencia_token_id = n.clave_idempotente AS referencia_es_la_clave, n.enviado_en
FROM notification_outbox n
WHERE n.organization_id = @org_a AND n.tipo = 'INVITACION_COLABORADOR' AND n.created_at >= @desde
ORDER BY n.id;
```

Esperado: `referencia_token_id` con la forma `invitacion:<id>:<ms>` e **igual** a
`clave_idempotente`, distinta en cada fila (también la del reenvío, que lleva otro vencimiento).
Una referencia que sea **solo un número** (el vencimiento) es regresión de #111. Las de activación
y recuperación de contraseña siguen siendo el id de `token_verificacion`: no cambian.
**Auditoría:** `INVITACION_EMITIDA` ×2 e `INVITACION_REENVIADA`.

> Validado sobre el stack aislado (14/10): dos invitaciones emitidas en paralelo quedaron con
> vencimientos a 1 ms de distancia (`…320763` y `…320764`) y cada enlace abrió la suya.

---

### ORG-17 · Una suspensión vale desde el pedido siguiente (api #112)

Desde #112 las lecturas de `platform_role` y `membership` se **memorizan por pedido HTTP**: el
filtro de contexto y la evaluación de permisos ya no releen las mismas filas varias veces en el
mismo pedido. La regla de ADR-0020 —revalidar contra la base **en cada pedido**— no cambia, y la
memoria se descarta al commitear cualquier escritura. La ventana aceptada es la de **un pedido**
(ver [90](90-conocido-y-aceptado.md) §1): lo que hay que comprobar es que **nunca cruza al
siguiente**. Hay unitarios (`MemoriaDelPedidoTest`, `TransaccionesConfigMemoriaTest`); ningún IT
ni E2E prueba la revocación entre pedidos.

**Pantalla:** dos navegadores (uno en ventana privada). En el primero, el administrativo con
`/pacientes` abierto. En el segundo, admin-a lo **suspende** con motivo (ORG-10). Sin recargar el
token, el administrativo: (1) busca en el padrón, (2) intenta dar de alta una persona.

**Esperado:** las **dos** acciones fallan en el primer intento posterior a la suspensión, con
**404 `not-found`** —la membership del contexto dejó de estar vigente, y el contexto vencido es 404,
no 403 ni 401 (`TenantContextFilter`)—, y la sesión **no** se cierra. Ninguna escritura entra.
Reactivá: el siguiente pedido del mismo token vuelve a responder 200. Repetí con un grant: otorgale
`paciente:manage` al profesional, revocáselo y, sin cambiar de token, intentá un alta de persona
como profesional: 403 desde el primer pedido después de la revocación.

```sql
SELECT id, estado, revoked_reason, version FROM membership WHERE id = @membership_administrativo;

SELECT COUNT(*) AS altas_despues_de_suspender
FROM persona p
WHERE p.organization_id = @org_a
  AND p.created_at >= (SELECT MAX(occurred_at) FROM audit_event
                        WHERE organization_id = @org_a AND event_type = 'MEMBERSHIP_SUSPENDED');
```

Esperado: `SUSPENDIDA` (y `ACTIVA` al reactivar); `altas_despues_de_suspender = 0` si solo probaste
con el administrativo suspendido. Un 200 en un pedido **posterior** a la respuesta de la suspensión
es hallazgo **crítico** (la memoria cruzó pedidos). **Auditoría:** `MEMBERSHIP_SUSPENDED` y
`MEMBERSHIP_REACTIVATED` con `reason`; los pedidos rechazados por contexto vencido no dejan fila.

> Validado sobre el stack aislado (14/10): con el mismo token, `GET /personas` dio 200 antes de la
> suspensión y 404 en el pedido siguiente; `POST /personas`, 404; reactivar, 200 otra vez. Con el
> grant: alta de persona como profesional 201, `DELETE .../grants/paciente:manage` 204, y la
> siguiente alta con el mismo token, 403.
