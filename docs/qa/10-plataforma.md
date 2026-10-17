# 10 · Plataforma — P2

El rol `PLATFORM_ADMIN` (M01, M06, DP-14, DP-18, A-4, A-7). Alcance global **sin contexto de
tenant**: sus accesos a datos de un centro son los más restringidos de la matriz, solo por acceso de
soporte con motivo, acotado en el tiempo y auditado en el centro. `[E2E cuenta-ciclo-real]` cubre el
alta de una organización por plataforma y el slug repetido; lo demás no lo cubre nada por pantalla.

Pantalla en `main`: `/plataforma/solicitudes` (solicitudes de catálogo) y el catálogo de servicios
(`/servicios/catalogo`). El resto —alta de organización, cambio de plan, suspensión, soporte, rol de
plataforma, bloqueo de cuentas— por la API ([README §5](README.md)).

Tablas: `cuenta`, `platform_role`, `support_access`, `servicio`, `catalogo_solicitud`,
`organization`, `subscription`, `subscription_transition`, `refresh_token`.

---

### PLA-01 · Tomar posesión de la plataforma (bootstrap DP-14) — primer paso de la corrida

Con el backend arrancado con `AKINE_BOOTSTRAP_ADMIN_EMAIL=qa.plataforma@ejemplo.test` sobre la base
nueva (README §2.2).

**Pantalla:** Mailpit → el correo de activación para `qa.plataforma@` → abrir el enlace → fijar
contraseña. Ingresar en `/auth/ingresar`: no hay selector de contexto (no tiene ninguno).

```sql
SELECT c.id, c.email, c.estado, c.password_hash IS NOT NULL AS con_clave, pr.role_code, pr.rol_activo, pr.valid_until
FROM platform_role pr JOIN cuenta c ON c.id = pr.account_id
WHERE pr.active = 1;

SELECT id, event_type, entity_id, organization_id, details, occurred_at
FROM audit_event WHERE event_type IN ('PLATFORM_ADMIN_BOOTSTRAP', 'PLATFORM_ROLE_GRANTED', 'PLATFORM_ROLE_REVOKED') ORDER BY id;
```

Esperado: **una** cuenta con rol activo, el email de la variable, `ACTIVA` y con contraseña;
`PLATFORM_ADMIN_BOOTSTRAP` con `emailAnterior = plataforma@akine.app` y `emailNuevo` (sin
organización). **Reiniciá el backend**: el bootstrap **no** vuelve a actuar ni manda otro correo (ya
hay un admin con credencial).

---

### PLA-02 · Servicio global

**Pantalla:** plataforma → `/servicios/catalogo` → nuevo servicio `Kinesiología` (terapéutico,
individual).

```sql
SET @servicio := (SELECT MAX(id) FROM servicio);
SELECT id, codigo, nombre, naturaleza, modalidad_default, requiere_caso_clinico_default, genera_registro_clinico_default, active
FROM servicio ORDER BY id;
```

Esperado: la fila **sin `organization_id`** (el catálogo de servicios es global). Un nombre repetido
—sin distinguir mayúsculas ni acentos— es 409 `servicio-nombre-taken`. **Auditoría:**
`SERVICIO_CREATED` con `organization_id` NULL. Dar de baja un servicio **no cascadea**: las ofertas
que lo usan siguen operando (02.06).

---

### PLA-03 · Sin soporte, la plataforma no ve los datos de un centro

**API** (README §5), con el token de plataforma:

```powershell
$X = Entrar 'qa.plataforma@ejemplo.test'
(Api GET "/api/v1/organizations/<org_a>" $X).Status            # 403 tenant:read
(Api GET "/api/v1/organizations/<org_a>/memberships" $X).Status # 403
(Api GET "/api/v1/personas" $X).Status                          # 403: no tiene contexto de tenant
```

Esperado: 403 en las tres; ningún `SUPPORT_ACCESS_USED`. Ver [PLA-07](#pla-07--acceso-de-soporte-con-rastro-en-el-centro) para el
camino con soporte.

---

### PLA-04 · Resolver una solicitud de catálogo: aprobar publica (DP-18)

**Pantalla:** plataforma → `/plataforma/solicitudes` → la solicitud de [CAT-03](03-catalogo-servicios-ofertas.md)
→ aprobar (podés ajustar código, nombre y descripción; la nota es obligatoria). Una práctica global
cuelga de una especialidad **global**: si no hay ninguna, creala antes como plataforma en
`/catalogo/especialidades` con alcance global. Elegir la especialidad propia de A responde 404 (no
existe en el catálogo global). Pedí otra solicitud desde A y **rechazala** con nota.

```sql
SELECT id, organization_id, tipo, nombre_propuesto, estado, resuelta_por_account_id, resuelta_at, resolucion_nota, concepto_id
FROM catalogo_solicitud ORDER BY id DESC LIMIT 5;

SELECT p.id, p.owner_key, p.organization_id, p.codigo, p.name, p.active
FROM practica p JOIN catalogo_solicitud s ON s.concepto_id = p.id
WHERE s.estado = 'APROBADA';
```

Esperado: la aprobada con `concepto_id` apuntando a una práctica **global** (`owner_key = 0`,
`organization_id` NULL) creada en el mismo acto; la rechazada sin concepto. Desde A, la práctica nueva
aparece en `/catalogo/practicas` como global. Aprobar sin ningún código posible es 400. **Auditoría:**
`CATALOGO_SOLICITUD_RESOLVED` (en la organización que pidió) y `CATALOGO_CREATED` (sin organización).

---

### PLA-05 · Alta de una organización por plataforma — `[E2E cuenta-ciclo-real]`

```powershell
Api POST '/api/v1/organizations' $X @{ name = 'Centro QA C'; slug = 'centro-qa-c'; planCode = 'BASICO' } @{ 'Idempotency-Key' = (Nueva-Clave) }
```

Repetí con el mismo slug, con **otra** `Idempotency-Key` o con la **misma** clave y el mismo cuerpo:
en los dos casos 409 `organization-slug-taken`, sin otra organización. Este camino administrativo
**no registra idempotencia** (la clave solo correlaciona el log y la auditoría); el duplicado lo
cierra la unicidad del slug, no un replay con la misma respuesta.

```sql
SELECT o.id, o.name, o.slug, o.timezone, s.status, p.code
FROM organization o JOIN subscription s ON s.organization_id = o.id JOIN plan p ON p.id = s.plan_id
WHERE o.slug = 'centro-qa-c';
SELECT COUNT(*) AS organizaciones_con_ese_slug FROM organization WHERE slug = 'centro-qa-c';
```

**Auditoría:** `ORGANIZATION_CREATED`.

---

### PLA-06 · Cambiar el plan de A a `PROFESIONAL`

Destraba [ORG-03b](02-organizacion-sedes-colaboradores.md) (segunda sede) y el tope de miembros.

```powershell
$sub = (Api GET "/api/v1/organizations/<org_a>/subscription" $X).Cuerpo
Api POST "/api/v1/organizations/<org_a>/subscription/plan-changes" $X @{ planCode = 'PROFESIONAL'; expectedVersion = $sub.version }
```

```sql
SELECT p.code, s.status, s.version FROM subscription s JOIN plan p ON p.id = s.plan_id WHERE s.organization_id = @org_a;
SELECT from_status, to_status, from_plan_id, to_plan_id, reason, actor_account_id, occurred_at
FROM subscription_transition WHERE organization_id = @org_a ORDER BY id;
```

Esperado: `PROFESIONAL`, una transición con los dos planes. Con una versión vieja, 409
`concurrent-modification` (DP-21). **Auditoría:** `SUBSCRIPTION_PLAN_CHANGED`. Desde la pantalla de
A, `/organizacion/suscripcion` muestra el plan nuevo; el `ORG_ADMIN` no puede cambiarlo (decisión
pendiente, [90](90-conocido-y-aceptado.md)).

---

### PLA-07 · Acceso de soporte con rastro en el centro

```powershell
$sa = (Api POST "/api/v1/platform/organizations/<org_a>/support-access" $X @{ reason = 'QA G-12: ticket de soporte sintetico'; durationMinutes = 30 }).Cuerpo
(Api GET "/api/v1/organizations/<org_a>" $X).Status              # 200
(Api GET "/api/v1/organizations/<org_a>/memberships" $X).Status  # 200
(Api GET "/api/v1/organizations/<org_b>" $X).Status              # 403: el soporte es de A
(Api GET "/api/v1/personas" $X).Status                           # 403: sigue sin contexto de tenant
Api DELETE "/api/v1/platform/support-access/$($sa.id)" $X
(Api GET "/api/v1/organizations/<org_a>" $X).Status              # 403 otra vez
```

```sql
SELECT id, account_id, reason, valid_from, valid_until, revoked_at, revoked_by_account_id, active
FROM support_access WHERE organization_id = @org_a ORDER BY id;

SELECT id, event_type, actor_account_id, reason, details, occurred_at
FROM audit_event
WHERE organization_id = @org_a AND event_type LIKE 'SUPPORT_ACCESS%' AND occurred_at >= @desde
ORDER BY id;
```

Esperado: el acceso con motivo y vencimiento; tras revocarlo, `revoked_at` lleno. En la auditoría
**del centro A** (la que ve su `ORG_ADMIN` en `/organizacion/auditoria`): `SUPPORT_ACCESS_GRANTED`
con el motivo, **un `SUPPORT_ACCESS_USED` por cada lectura** (`details.permissionCode`) y
`SUPPORT_ACCESS_REVOKED`. Un acceso vencido deja de servir solo, sin revocarlo.

---

### PLA-08 · Suspender la suscripción de un centro

```powershell
Api POST "/api/v1/organizations/<org_b>/subscription/transitions" $X @{ toStatus = 'SUSPENDIDA'; reason = 'QA G-12: falta de pago sintetica' }
```

Con admin-b en la pantalla: el padrón **se lee**, pero dar de alta una persona falla con 409
`subscription-suspended` ("se permiten lecturas y administración, no mutaciones de negocio").
Reactivá con `toStatus = 'ACTIVA'`.

```sql
SELECT s.status, s.version FROM subscription s WHERE s.organization_id = @org_b;
SELECT from_status, to_status, reason, occurred_at FROM subscription_transition WHERE organization_id = @org_b ORDER BY id;
SELECT COUNT(*) AS personas_creadas_durante_la_suspension FROM persona WHERE organization_id = @org_b AND created_at >= @desde;
```

Esperado: `SUSPENDIDA` → `ACTIVA`, dos transiciones con motivo, y ninguna persona creada mientras
estuvo suspendida. **Auditoría:** dos `SUBSCRIPTION_TRANSITIONED` en B.

---

### PLA-09 · Rol de plataforma y bloqueo de cuentas

```powershell
$c = <id de la cuenta qa.invitado@, de la consulta de abajo>
$r = (Api POST '/api/v1/platform/roles' $X @{ accountId = $c; reason = 'QA G-12: segundo operador' }).Cuerpo
Api DELETE "/api/v1/platform/roles/$($r.id)?reason=QA" $X
Api POST "/api/v1/accounts/$c/block" $X @{ reason = 'QA G-12: bloqueo sintetico' }
Api POST "/api/v1/accounts/$c/unblock" $X @{ reason = 'QA G-12: desbloqueo' }
```

Con el rol de plataforma **otorgado**, el invitado pierde sus permisos de tenant (conocido: otorgar
`PLATFORM_ADMIN` le quita los de su membership). Bloqueado, no ingresa y su sesión abierta muere al
refrescar (el access token vive hasta 10 minutos más: riesgo R6 aceptado).

```sql
SELECT c.id, c.email, c.estado, c.bloqueada_en, c.bloqueada_motivo FROM cuenta c WHERE c.email_normalizado = 'qa.invitado@ejemplo.test';
SELECT pr.id, pr.role_code, pr.reason, pr.active, pr.revoked_reason
FROM platform_role pr JOIN cuenta c ON c.id = pr.account_id WHERE c.email_normalizado = 'qa.invitado@ejemplo.test';
SELECT r.revocado_en, r.motivo_revocacion
FROM refresh_token r JOIN cuenta c ON c.id = r.cuenta_id
WHERE c.email_normalizado = 'qa.invitado@ejemplo.test' ORDER BY r.id DESC LIMIT 3;
```

Esperado: el rol `active = 0` con motivo tras revocarlo; al bloquear, `BLOQUEADA` con motivo y los
refresh vivos revocados con `motivo_revocacion = BLOQUEO`; al desbloquear, `ACTIVA`. **Auditoría:**
`PLATFORM_ROLE_GRANTED`, `PLATFORM_ROLE_REVOKED`, `CUENTA_BLOQUEADA`, `CUENTA_DESBLOQUEADA`.
