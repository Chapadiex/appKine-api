# 11 · Aislamiento entre organizaciones y rol PACIENTE — P1

La segunda regla innegociable del §6: **un token del centro B no ve nada del centro A**. Por HTTP lo
prueban `AislamientoDeTenantIT`, `AislamientoPorContextoIT` (#70), `EgresoAislamientoTenantIT` y
`PacienteReadIT` (DP-22), y por pantalla `[E2E contexto-sin-fuga]` y una parte de
`[E2E cuenta-ciclo-real]`. Lo que queda para el QA manual es **recorrer con datos reales de las dos
organizaciones** —los que fuiste creando en 01 a 10— y que la base confirme que nada se cruzó.

Reglas que tienen que cumplirse siempre (threat model §2.2):

- Fuera de alcance es **404**, nunca 403 ni 200 vacío: un 403 confirmaría que el recurso existe.
- La organización sale **del contexto del token**, no de la URL. Cambiar un id en la ruta no cambia
  de organización.
- Falta de contexto es **403**, nunca 401 (un 401 haría que el frontend borre el token).

Requisitos: datos en A (circuitos 03 a 08) y al menos una persona, un turno y un cobro en B (hacelos
con admin-b como en PER-01, AGE-01 y ECO-08). Anotá los ids de B: `@persona_b`, etc.

```sql
SET @persona_b := (SELECT MAX(id) FROM persona WHERE organization_id = @org_b);
SELECT @org_a, @sede_a, @org_b, @sede_b, @persona, @persona_b, @turno, @sesion, @cobro, @presentacion, @autorizacion, @historia;
```

---

### AIS-01 · El token de B contra los ids de A — `[IT AislamientoDeTenantIT]`

Con admin-b, cada ruta con un id de A (README §5):

```powershell
$B = Entrar 'qa.admin-b@ejemplo.test'
$rutas = @(
  "/api/v1/personas/<persona>",
  "/api/v1/personas/<persona>/coberturas",
  "/api/v1/personas/<persona>/autorizaciones",
  "/api/v1/personas/<persona>/resumen",
  "/api/v1/personas/<persona>/adjuntos",
  "/api/v1/consultorios/<sede_a>/turnos/<turno>",
  "/api/v1/consultorios/<sede_a>/sesiones/<sesion>",
  "/api/v1/consultorios/<sede_a>/obligaciones?personaId=<persona>",
  "/api/v1/consultorios/<sede_a>/cobros/<cobro>",
  "/api/v1/consultorios/<sede_a>/presentaciones/<presentacion>",
  "/api/v1/consultorios/<sede_a>/caja/jornadas",
  "/api/v1/consultorios/<sede_a>/reportes/ECONOMICO?desde=2026-10-01&hasta=2026-10-31",
  "/api/v1/autorizaciones/<autorizacion>/saldo",
  "/api/v1/historias-clinicas/<historia>/timeline",
  "/api/v1/organizations/<org_a>",
  "/api/v1/organizations/<org_a>/memberships"
)
foreach ($r in $rutas) { $x = Api GET $r $B $null @{ 'X-Justificacion-Acceso' = 'QA aislamiento' }; "{0}  {1}  {2}" -f $x.Status, $x.Tipo, $r }
```

**Esperado:** **404** en todas, con `not-found` y sin datos. Un 200 es **hallazgo crítico**; un 403
es hallazgo alto (oráculo de existencia).

```sql
SELECT a.id, a.organization_id, a.event_type, a.entity_type, a.entity_id, a.occurred_at
FROM audit_event a
WHERE a.organization_id = @org_a AND a.occurred_at >= @desde
  AND a.actor_account_id = (SELECT id FROM cuenta WHERE email_normalizado = @email_admin_b);
```

Esperado: **cero filas**. Ninguna acción de admin-b quedó registrada como hecho dentro de A (ni
siquiera un `PERMISSION_DENIED`: fuera de alcance no llega a evaluar permisos).

---

### AIS-02 · Lo mismo por pantalla

**Pantalla:** admin-b ingresado. Pegá en la barra de direcciones las URLs de A:
`/pacientes/<persona>`, `/pacientes/<persona>/coberturas`, `/agenda/turnos/<turno>`,
`/presentaciones/<presentacion>`, `/atencion/turnos/<turno>`.

**Esperado:** "no encontrado" o vuelta a la lista, **nunca** un dato de A en pantalla (ni el nombre
de la persona en el encabezado mientras carga). Mirá la consola del navegador: ningún dato de A en
las respuestas (`[E2E errores-sin-internals]` cubre los internals, no los datos ajenos).

---

### AIS-03 · Una cuenta administradora en las dos organizaciones (#70) — `[IT AislamientoPorContextoIT]`

**Preparación:** admin-b → `/organizacion/colaboradores/nuevo` → vincular `qa.admin-a@` como
`ORG_ADMIN` de B. Ahora admin-a tiene dos contextos.

```sql
SELECT m.id, m.organization_id, m.role_code, m.estado
FROM membership m JOIN cuenta c ON c.id = m.account_id
WHERE c.email_normalizado = @email_admin_a ORDER BY m.organization_id;
SET @membership_admin_b := (SELECT m.id FROM membership m JOIN cuenta c ON c.id = m.account_id
                            WHERE m.organization_id = @org_b AND c.email_normalizado = @email_admin_b AND m.is_founder = 1);
```

**Con el contexto de A**, apuntando a B por la URL:

```powershell
$AA = Entrar 'qa.admin-a@ejemplo.test' <org_a>
(Api GET "/api/v1/organizations/<org_b>" $AA).Status
(Api GET "/api/v1/organizations/<org_b>/audit-events" $AA).Status
(Api GET "/api/v1/organizations/<org_b>/memberships" $AA).Status
(Api GET "/api/v1/organizations/<org_b>/memberships/<membership_admin_b>" $AA).Status
(Api GET "/api/v1/organizations/<org_b>/consultorios/<sede_b>" $AA).Status
(Api POST "/api/v1/organizations/<org_b>/memberships/<membership_admin_b>/suspend" $AA @{ reason = 'QA #70: no deberia pasar' }).Status
```

**Esperado:** **404** las seis. La suspensión **no escribe nada**:

```sql
SELECT id, estado, revoked_reason, version, updated_at FROM membership WHERE id = @membership_admin_b;
SELECT COUNT(*) AS suspensiones_en_b FROM audit_event
WHERE organization_id = @org_b AND event_type = 'MEMBERSHIP_SUSPENDED' AND occurred_at >= @desde;
```

Esperado: `ACTIVA` y **0**. **Con el contexto de B** (`Entrar 'qa.admin-a@ejemplo.test' <org_b>`), las
mismas lecturas responden 200 (no suspendas a nadie). **Por pantalla**
(`[E2E cuenta-ciclo-real]`): con el contexto de A, `/organizacion/auditoria` muestra **solo** la
auditoría de A; al cambiar a B, solo la de B, sin restos de A (`[E2E contexto-sin-fuga]`).

---

### AIS-04 · Escribir con ids de la otra organización

Con el contexto de A, mandar ids de B en el **cuerpo**:

```powershell
Api POST "/api/v1/consultorios/<sede_a>/cobros" $AA @{ personaId = <persona_b>; total = 100; anticipo = 100; moneda = 'ARS'; medios = @(@{ medio = 'TRANSFERENCIA'; importe = 100 }); idempotencyKey = (Nueva-Clave) }
Api POST "/api/v1/consultorios/<sede_a>/turnos/ofertas/<oferta>" $AA @{ inicio = '<un slot futuro de A>'; personaId = <persona_b>; profesionalId = <membership_profesional>; idempotencyKey = (Nueva-Clave) }
Api POST "/api/v1/personas/<persona_b>/coberturas" $AA @{ tipo = 'PARTICULAR'; vigenciaDesde = '2026-01-01' }
Api POST "/api/v1/consultorios/<sede_b>/cobros" $AA @{ personaId = <persona_b>; total = 100; anticipo = 100; moneda = 'ARS'; medios = @(@{ medio = 'TRANSFERENCIA'; importe = 100 }) }
```

**Esperado:** rechazo en todas (404 la persona o la sede ajena). Nada escrito:

```sql
SELECT 'cobro' AS tabla, COUNT(*) AS filas FROM cobro WHERE persona_id = @persona_b AND organization_id <> @org_b
UNION ALL SELECT 'turno', COUNT(*) FROM turno WHERE persona_id = @persona_b AND organization_id <> @org_b
UNION ALL SELECT 'cobertura', COUNT(*) FROM cobertura_paciente WHERE persona_id = @persona_b AND organization_id <> @org_b;
```

Esperado: 0 en las tres.

---

### AIS-05 · Barrido de integridad entre tenants

Al final de la corrida: **ninguna fila apunta a una fila de otra organización**. Es la prueba de que
nada se cruzó en todo lo que hiciste, por cualquier camino.

```sql
SELECT 'turno->persona' AS relacion, COUNT(*) AS cruzadas FROM turno t JOIN persona p ON p.id = t.persona_id WHERE t.organization_id <> p.organization_id
UNION ALL SELECT 'turno->oferta', COUNT(*) FROM turno t JOIN oferta_servicio_consultorio o ON o.id = t.oferta_id WHERE t.organization_id <> o.organization_id
UNION ALL SELECT 'turno->sede', COUNT(*) FROM turno t JOIN consultorio c ON c.id = t.consultorio_id WHERE t.organization_id <> c.organization_id
UNION ALL SELECT 'sesion->turno', COUNT(*) FROM sesion s JOIN turno t ON t.id = s.turno_id WHERE s.organization_id <> t.organization_id
UNION ALL SELECT 'sesion->historia', COUNT(*) FROM sesion s JOIN historia_clinica h ON h.id = s.historia_clinica_id WHERE s.organization_id <> h.organization_id
UNION ALL SELECT 'historia->persona', COUNT(*) FROM historia_clinica h JOIN persona p ON p.id = h.persona_id WHERE h.organization_id <> p.organization_id
UNION ALL SELECT 'obligacion->sesion', COUNT(*) FROM obligacion o JOIN sesion s ON s.id = o.sesion_id WHERE o.organization_id <> s.organization_id
UNION ALL SELECT 'obligacion->persona', COUNT(*) FROM obligacion o JOIN persona p ON p.id = o.persona_id WHERE o.organization_id <> p.organization_id
UNION ALL SELECT 'cobro->persona', COUNT(*) FROM cobro c JOIN persona p ON p.id = c.persona_id WHERE c.organization_id <> p.organization_id
UNION ALL SELECT 'imputacion->obligacion', COUNT(*) FROM cobro_imputacion i JOIN obligacion o ON o.id = i.obligacion_id WHERE i.organization_id <> o.organization_id
UNION ALL SELECT 'imputacion->cobro', COUNT(*) FROM cobro_imputacion i JOIN cobro c ON c.id = i.cobro_id WHERE i.organization_id <> c.organization_id
UNION ALL SELECT 'movimiento->jornada', COUNT(*) FROM movimiento_caja m JOIN jornada_caja j ON j.id = m.jornada_caja_id WHERE m.organization_id <> j.organization_id
UNION ALL SELECT 'item->obligacion', COUNT(*) FROM presentacion_item i JOIN obligacion o ON o.id = i.obligacion_id WHERE i.organization_id <> o.organization_id
UNION ALL SELECT 'presentacion->financiador', COUNT(*) FROM presentacion p JOIN financiador f ON f.id = p.financiador_id WHERE p.organization_id <> f.organization_id
UNION ALL SELECT 'cobertura->persona', COUNT(*) FROM cobertura_paciente c JOIN persona p ON p.id = c.persona_id WHERE c.organization_id <> p.organization_id
UNION ALL SELECT 'autorizacion->cobertura', COUNT(*) FROM autorizacion a JOIN cobertura_paciente c ON c.id = a.cobertura_id WHERE a.organization_id <> c.organization_id
UNION ALL SELECT 'convenio->financiador', COUNT(*) FROM convenio c JOIN financiador f ON f.id = c.financiador_id WHERE c.organization_id <> f.organization_id
UNION ALL SELECT 'membership->sede', COUNT(*) FROM membership m JOIN consultorio c ON c.id = m.consultorio_id WHERE m.organization_id <> c.organization_id
UNION ALL SELECT 'recepcion->turno', COUNT(*) FROM recepcion r JOIN turno t ON t.id = r.turno_id WHERE r.organization_id <> t.organization_id
UNION ALL SELECT 'adjunto->persona', COUNT(*) FROM adjunto_administrativo a JOIN persona p ON p.id = a.persona_id WHERE a.organization_id <> p.organization_id;
```

Esperado: **0 en todas**. Cualquier número distinto de cero es hallazgo crítico.

---

### PAC-01 · El rol PACIENTE no ve el padrón (DP-22) — `[IT PacienteReadIT]`

**Pantalla:** ingresar con `qa.paciente@` (membership `PACIENTE` en A, ORG-04). El menú no ofrece el
padrón; `/pacientes` y `/pacientes/<persona>` llevan a `/sin-permiso`.

**API**, con el token del paciente:

```powershell
$PA = Entrar 'qa.paciente@ejemplo.test' <org_a>
foreach ($r in @("/api/v1/personas?size=5", "/api/v1/personas?q=AF-QA-001", "/api/v1/personas/<persona>",
                 "/api/v1/personas/<persona>/resumen", "/api/v1/personas/<persona>/coberturas",
                 "/api/v1/personas/<persona>/cobertura-aplicable?ofertaId=<oferta>", "/api/v1/personas/<persona>/ordenes",
                 "/api/v1/personas/<persona>/autorizaciones", "/api/v1/personas/<persona>/elegibilidad?coberturaId=<cobertura>&practicaId=<practica>",
                 "/api/v1/personas/<persona>/adjuntos", "/api/v1/autorizaciones/<autorizacion>/saldo")) {
  $x = Api GET $r $PA; "{0}  {1}  {2}" -f $x.Status, $x.Tipo, $r }
```

**Esperado:** **403** en todas las lecturas de `person`. Un 200 es hallazgo crítico (era el hueco
alto de 07.07). Un **400** quiere decir que faltó un parámetro obligatorio: la validación del pedido
corre antes que el permiso; completalo y repetí.

```sql
SELECT JSON_UNQUOTE(JSON_EXTRACT(a.details, '$.permissionCode')) AS permiso, COUNT(*) AS rechazos
FROM audit_event a JOIN cuenta c ON c.id = a.actor_account_id
WHERE a.organization_id = @org_a AND a.event_type = 'PERMISSION_DENIED' AND a.occurred_at >= @desde
  AND c.email_normalizado = @email_paciente
GROUP BY permiso;
```

Esperado: los rechazos con `paciente:read` (y el permiso de su familia en los de otros módulos).

---

### PAC-02 · El paciente tampoco entra a los otros módulos

Con `$PA`: agenda del día (`GET /api/v1/consultorios/<sede_a>/turnos?fecha=<hoy>`), una sesión, la historia
clínica, obligaciones y cobros, caja, presentaciones, reportes y colaboradores.

**Esperado:** **403** en todo: cada módulo pide su permiso (`turno:read`, `sesion:register`,
`hc:read`, `cobro:register`, `caja:operate`, `reporte:read`, `colaborador:read`) y el `PACIENTE` no
tiene ninguno. El autoservicio del paciente sobre **sus propios** datos (alcance `OWN`) es
**post-MVP** (DP-22): que hoy no vea ni lo suyo **no es un hallazgo**.

---

### PAC-03 · El personal sigue leyendo, y la persona ajena sigue siendo 404

Con el profesional y el administrativo (contexto A): `GET /api/v1/personas/<persona>` → **200**
(`paciente:read` por base, DP-22). Con los mismos tokens, `GET /api/v1/personas/<persona_b>` →
**404**, no 403.

```sql
SELECT r.role_code, COUNT(*) AS rechazos_paciente_read
FROM audit_event a
JOIN membership r ON r.account_id = a.actor_account_id AND r.organization_id = a.organization_id AND r.estado = 'ACTIVA'
WHERE a.organization_id = @org_a AND a.event_type = 'PERMISSION_DENIED' AND a.occurred_at >= @desde
  AND JSON_UNQUOTE(JSON_EXTRACT(a.details, '$.permissionCode')) = 'paciente:read'
GROUP BY r.role_code;
```

Esperado: rechazos de `paciente:read` **solo** para `PACIENTE`. Una fila de `PROFESIONAL` o
`ADMINISTRATIVO` es hallazgo alto (el personal perdió el padrón).
