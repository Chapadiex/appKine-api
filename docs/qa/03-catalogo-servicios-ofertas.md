# 03 · Catálogo, servicios, ofertas, prácticas y precios — P2

Módulos `resource` (M04, catálogo clínico, disponibilidad, horario de sede) y `offering` (M06). Es
la configuración de la que cuelga todo lo demás: si una práctica no queda atada a la oferta o el
precio no queda vigente, el circuito económico falla después **sin decir por qué** (DP-17).

Tablas: `espacio`, `especialidad`, `practica`, `nomenclador`, `catalogo_solicitud`, `servicio`
(global, sin `organization_id`), `oferta_servicio_consultorio`, `oferta_profesional_habilitado`,
`oferta_espacio_habilitado`, `oferta_practica`, `oferta_precio_particular`,
`profesional_disponibilidad`, `disponibilidad_excepcion`, `consultorio_calendario`,
`consultorio_horario`.

> **El catálogo clínico se filtra por `owner_key`, no por `organization_id`** (ADR-0021):
> `owner_key = 0` es global y `owner_key = <id de la organización>` es propio. Varios `NULL` no
> colisionan en MySQL y por eso existe esa columna.

Requisito: [PLA-02](10-plataforma.md) (hay al menos un servicio global activo).

---

### CAT-01 · Espacios: alta y baja de un box

**Pantalla:** admin-a → `/espacios/nuevo`: `Box QA`, tipo box, capacidad 1. Después darlo de baja
con motivo desde `/espacios`.

```sql
SELECT id, name, tipo, capacidad, active, deleted_at, deactivation_reason, deleted_key, version
FROM espacio WHERE consultorio_id = @sede_a ORDER BY id;
```

Esperado: alta `active = 1`; tras la baja `active = 0`, `deleted_at` y `deactivation_reason` llenos.
**La fila no desaparece.** Con "mostrar dados de baja" la pantalla la lista.

**Auditoría:** `ESPACIO_CREATED`, `ESPACIO_DEACTIVATED`. Volvé a crear un box activo para los
circuitos siguientes.

---

### CAT-02 · Especialidad y práctica propias de la organización

**Pantalla:** `/catalogo/especialidades` → nueva `Kinesiología QA`. `/catalogo/practicas` → nueva
`Fisioterapia de rodilla QA` dentro de esa especialidad (la especialidad es obligatoria).

```sql
SELECT id, owner_key, codigo, name, active FROM especialidad WHERE owner_key IN (0, @org_a) ORDER BY owner_key, id;
SET @practica := (SELECT MAX(id) FROM practica WHERE owner_key = @org_a);
SELECT id, owner_key, especialidad_id, codigo, name, valid_from, valid_until, active FROM practica WHERE id = @practica;
```

Esperado: `owner_key = @org_a` en las dos. Un código repetido dentro de la organización es 409; el
mismo código en B no choca.

**Auditoría:** dos `CATALOGO_CREATED`.

---

### CAT-03 · Pedir un concepto al catálogo de plataforma

**Pantalla:** `/catalogo/solicitudes` → pedir una práctica global con nombre, código propuesto y
justificación.

```sql
SET @solicitud := (SELECT MAX(id) FROM catalogo_solicitud WHERE organization_id = @org_a);
SELECT id, tipo, nombre_propuesto, codigo_propuesto, estado, solicitada_por_account_id, concepto_id, version
FROM catalogo_solicitud WHERE id = @solicitud;
```

Esperado: `PENDIENTE`, `concepto_id` NULL. Se resuelve en [PLA-04](10-plataforma.md).

**Auditoría:** `CATALOGO_SOLICITUD_CREATED`.

---

### CAT-04 · Oferta de la sede sobre un servicio global

**Pantalla:** `/servicios/catalogo` muestra los servicios globales. `/servicios/ofertas` → nueva
oferta: `Kinesio QA`, duración 30 min, capacidad 1, requiere profesional, **admite obra social**.

```sql
SET @oferta := (SELECT MAX(id) FROM oferta_servicio_consultorio WHERE consultorio_id = @sede_a);
SELECT o.id, o.servicio_id, s.nombre AS servicio_global, o.nombre_comercial, o.modalidad, o.duracion_minutos,
       o.capacidad, o.admite_obra_social, o.exige_prepago, o.requiere_profesional, o.requiere_espacio,
       o.vigencia_desde, o.vigencia_hasta, o.active, o.version
FROM oferta_servicio_consultorio o JOIN servicio s ON s.id = o.servicio_id
WHERE o.id = @oferta;
```

Esperado: `organization_id`/`consultorio_id` de A, `admite_obra_social = 1`, `exige_prepago = 0`.

**Auditoría:** `OFERTA_CREATED`.

---

### CAT-05 · Habilitar al profesional en la oferta

**Pantalla:** `/servicios/ofertas/<id>/habilitaciones` → habilitar al profesional. Abrí la misma
pantalla en dos pestañas, guardá en una y después en la otra.

**Esperado:** la segunda pestaña rebota con 409 `concurrent-modification` y un mensaje que pide
recargar (DP-21). Recargar y guardar funciona.

```sql
SELECT h.membership_id, h.valid_from, h.valid_until, h.active
FROM oferta_profesional_habilitado h WHERE h.oferta_id = @oferta ORDER BY h.id;
SELECT version FROM oferta_servicio_consultorio WHERE id = @oferta;
```

Esperado: la habilitación activa; la `version` de la oferta avanzó **una** vez por reemplazo
(force-increment: el reemplazo solo toca la tabla hija).

**Auditoría:** `HABILITACION_PROFESIONAL_GRANTED`.

> Sin ninguna habilitación explícita, la oferta la presta **cualquier** `PROFESIONAL` de la sede.
> No es un hueco: es la regla.

---

### CAT-06 · Prácticas de la oferta (A-9, DP-11) — `[E2E flujo-practicas-y-aranceles]`

**Pantalla:** `/servicios/ofertas/<id>/practicas` → agregar la práctica de CAT-02 y marcarla
principal.

```sql
SELECT op.practica_id, p.name, op.principal, op.active, op.deleted_at
FROM oferta_practica op JOIN practica p ON p.id = op.practica_id
WHERE op.oferta_id = @oferta ORDER BY op.id;
```

Esperado: una fila `principal = 1`, `active = 1`. Quitar una práctica la da de baja (`active = 0`),
no la borra. **Una oferta sin prácticas no es un error de configuración**: la lista vacía significa
"la oferta no declara prácticas" (ver [90](90-conocido-y-aceptado.md)), pero sin práctica no hay
arancel de convenio posible.

**Auditoría:** `OFERTA_PRACTICA_ADDED`, `OFERTA_PRACTICA_PRINCIPAL_CHANGED`.

---

### CAT-07 · Precio particular vigente

**Pantalla:** `/servicios/ofertas/<id>/precios` → precio `10000 ARS` desde hoy. Después, cerrar la
vigencia de un precio anterior (si lo hay).

```sql
SELECT id, importe, moneda, vigencia_desde, vigencia_hasta, active, version
FROM oferta_precio_particular WHERE oferta_id = @oferta ORDER BY vigencia_desde;
```

Esperado: un precio vigente hoy. **El importe no se edita**: solo se cierra o reabre la vigencia
(decisión de B-3). Dos precios que se solapan son 409 `precio-particular-solapado`.

**Auditoría:** `OFERTA_PRECIO_PARTICULAR_CREATED` (y `..._UPDATED` al cerrar la vigencia).

> Sin precio vigente el día del cierre, una sesión **particular** no cierra (DP-17, 409
> `oferta-sin-precio`). Se prueba en [ECO-02](07-obligaciones-cobros-caja.md).

---

### CAT-08 · Horario semanal del profesional

**Pantalla:** `/horarios` → el profesional → bloques lunes a viernes 08:00–20:00 (y sábado si vas
a correr el QA ese día). Editar uno: la pantalla muestra el impacto (turnos afectados) antes de
confirmar.

```sql
SELECT id, membership_id, dia_semana, hora_desde, hora_hasta, vigencia_desde, vigencia_hasta, active
FROM profesional_disponibilidad
WHERE consultorio_id = @sede_a AND membership_id = @membership_profesional
ORDER BY dia_semana, hora_desde;
```

**Auditoría:** `DISPONIBILIDAD_BLOQUE_CREATED` / `_UPDATED` / `_DEACTIVATED`.

> El impacto informado es una **cota superior** (cuenta turnos de otro bloque del mismo
> profesional). Conocido, ver [90](90-conocido-y-aceptado.md).

---

### CAT-09 · Cierre por ausencia

**Pantalla:** `/horarios/excepciones` → cierre del profesional por licencia mañana.

```sql
SELECT id, membership_id, tipo, motivo, fecha_desde, fecha_hasta, hora_desde, hora_hasta, active
FROM disponibilidad_excepcion WHERE consultorio_id = @sede_a ORDER BY id DESC LIMIT 5;
```

Esperado: `tipo = CIERRE`, `motivo = LICENCIA` (`fecha_hasta` es **exclusiva**: un día de licencia va de mañana a
pasado mañana). En `/agenda` ese día aparece **sin slots y con su
motivo**, no vacío sin explicación (05.01). Dalo de baja al terminar.

**Auditoría:** `DISPONIBILIDAD_EXCEPCION_CREATED`, `..._DEACTIVATED`.

---

### CAT-10 · El horario general de la sede limita la agenda (DP-19) — `[IT HorarioDeSedeLimitaLaAgendaIT]`

**Pantalla:** `/horarios/calendario` → horario general lunes a viernes 09:00–18:00. En `/agenda`,
buscar la oferta un día hábil.

**Esperado:** aunque el profesional tenga 08:00–20:00, **no** se ofrecen slots antes de las 09:00 ni
desde las 18:00. Sin horario general cargado, no se limita nada.

```sql
SELECT dia_semana, hora_desde, hora_hasta, active, deleted_at
FROM consultorio_horario WHERE consultorio_id = @sede_a ORDER BY dia_semana, hora_desde;
SELECT pais, cierra_por_feriado, version FROM consultorio_calendario WHERE consultorio_id = @sede_a;
```

**Auditoría:** `HORARIO_GENERAL_UPDATED` (y `CALENDARIO_SEDE_UPDATED` si cambiaste feriados).

> Para no frenar los circuitos de agenda fuera de horario, dejá el horario general **amplio** (o
> sin cargar) mientras corrés 05 a 07.
