# 12 · Egresos y pagos de la sede — P1

Módulo `billing`, M22 (AKINE-07.05). Pantalla nueva desde `appKine-web` **#63**: `/egresos`
(listado con filtros y alta) y `/egresos/<id>` (detalle: corregir el borrador, confirmar, pagar,
anular el pago, anular el egreso). Las dos rutas exigen `caja:operate` (DU-10: no hay
`egreso:manage`), así que las ven `ORG_ADMIN`, `CONSULTORIO_ADMIN` y `ADMINISTRATIVO`; el
`PROFESIONAL` recibe 403 y no tiene la entrada en el menú.

`[E2E flujo-caja]` (tercer test, "alta → confirmación → pago en efectivo → movimiento en la caja →
anulación del pago") recorre por pantalla el camino feliz en efectivo contra el backend real. **No
mira la base**, ni prueba honorarios a un colaborador, el pago parcial por transferencia, el
borrador sin comprobante, la anulación del egreso, los filtros ni los rechazos. `[IT
EgresoAislamientoTenantIT]` cubre el aislamiento entre organizaciones.

Tablas: `egreso`, `pago_egreso`, `movimiento_caja` (`tipo_origen = 'PAGO_EGRESO'`).

> **Qué es y qué no es un egreso.** Es una salida de plata **cargada a mano**: el importe lo
> escribe quien la registra. "Honorarios profesionales" a un colaborador **no es una liquidación**:
> nada calcula cuánto se le debe a un profesional a partir de sus sesiones, ni lo descuenta de lo
> cobrado. Ver [90](90-conocido-y-aceptado.md) §3.

Requisitos: la caja de la sede abierta ([ECO-04](07-obligaciones-cobros-caja.md)) para los pasos en
efectivo, y el profesional vinculado ([ORG-04](02-organizacion-sedes-colaboradores.md)).

---

### EGR-01 · Alta de un egreso a un proveedor externo — `[E2E flujo-caja]`

**Pantalla:** administrativo → `/egresos` → "Registrar un egreso" → beneficiario **externo**
`Proveedor QA`, categoría Insumos, concepto `Vendas QA`, importe `1500`, comprobante `FACTURA_B`
número `B-0001-QA` con fecha de hoy → registrar.

**Esperado:** abre el detalle en `/egresos/<id>`, en estado **Borrador**. Un segundo alta con el
**mismo comprobante del mismo beneficiario** se rechaza con 409 `egreso-comprobante-duplicado`, y el
mensaje nombra el egreso que ya lo tiene.

```sql
SET @egreso := (SELECT MAX(id) FROM egreso WHERE consultorio_id = @sede_a AND tipo_beneficiario = 'EXTERNO');
SELECT id, categoria, tipo_beneficiario, beneficiario_membership_id, beneficiario_nombre, beneficiario_clave,
       concepto, importe_total, saldo_pendiente, moneda, estado,
       comprobante_tipo, comprobante_numero, comprobante_fecha, registrado_en, registrado_por_cuenta_id, idempotency_key
FROM egreso WHERE id = @egreso;
```

Esperado: `BORRADOR`, `saldo_pendiente = importe_total`, `beneficiario_membership_id` NULL,
`registrado_por_cuenta_id` = la cuenta del administrativo, una `idempotency_key` (doble click en
"Registrar" no crea dos). **Auditoría:** `EGRESO_REGISTRADO`.

---

### EGR-02 · Borrador sin comprobante: se guarda, no se confirma

**Pantalla:** otro egreso externo **sin** comprobante → registrar → en el detalle, "Confirmar".

**Esperado:** el alta pasa (queda `BORRADOR`); confirmar se rechaza con **400
`egreso-sin-comprobante`** y el mensaje lo dice. "Corregir el borrador" → cargar el comprobante →
ahora confirma. Una vez confirmado, el formulario de corrección desaparece; por la API, editarlo es
409 `egreso-no-editable`.

```sql
SET @egreso_borrador := (SELECT MAX(id) FROM egreso WHERE consultorio_id = @sede_a AND comprobante_numero IS NULL);
SELECT id, estado, comprobante_tipo, comprobante_numero, confirmado_en, version
FROM egreso WHERE id = @egreso_borrador;
```

Esperado mientras está rechazado: `BORRADOR`, `confirmado_en` NULL. Si ya lo corregiste, la consulta
no devuelve filas (ya tiene número): mirá el egreso por id. **Auditoría:** el rechazo no deja nada;
la corrección no deja evento propio (sube `version`); confirmar deja `EGRESO_CONFIRMADO`.

---

### EGR-03 · Confirmar y pagar en efectivo — `[E2E flujo-caja]`

**Pantalla:** el egreso de EGR-01 → "Confirmar" → "Registrar un pago": `1500` en efectivo.

```sql
SELECT id, estado, saldo_pendiente, confirmado_en, confirmado_por_cuenta_id FROM egreso WHERE id = @egreso;
SELECT id, importe, moneda, medio, referencia, fecha_negocio, estado, pagado_en, pagado_por_cuenta_id
FROM pago_egreso WHERE egreso_id = @egreso ORDER BY id;
SELECT m.id, m.jornada_caja_id, m.tipo, m.medio, m.importe, m.tipo_origen, m.referencia_origen, m.afecta_arqueo
FROM movimiento_caja m JOIN pago_egreso p ON p.id = m.referencia_origen
WHERE m.tipo_origen = 'PAGO_EGRESO' AND p.egreso_id = @egreso ORDER BY m.id;
```

Esperado: egreso `PAGADO` con `saldo_pendiente = 0`; un pago `CONFIRMADO` en `EFECTIVO`; **un**
movimiento `EGRESO` con `tipo_origen = PAGO_EGRESO`, la jornada abierta y `afecta_arqueo = 1`. En
`/caja` el movimiento aparece como "Pago de egreso <id>" y **no tiene botón de revertir**: se
revierte anulando el pago (EGR-05). **Auditoría:** `EGRESO_CONFIRMADO`, `EGRESO_PAGADO`.

Con la caja **cerrada**, el mismo pago en efectivo es 409 `caja-no-abierta` y la pantalla ofrece ir
a la caja.

---

### EGR-04 · Honorarios a un colaborador, pago parcial por transferencia

**Pantalla:** `/egresos` → beneficiario **colaborador** → el profesional (el selector ofrece solo
vínculos `ACTIVA`) → categoría Honorarios profesionales, período del mes anterior, concepto
`Honorarios QA`, importe `4000`, comprobante `RECIBO` → registrar, confirmar, pagar `1500` por
**transferencia** con referencia `TRF-QA-EGR`.

**Esperado:** el nombre del beneficiario lo resuelve el backend desde la cuenta del colaborador (no
se tipea). Pagar más que el saldo pendiente es 409 `egreso-saldo-insuficiente`. El pago por
transferencia **pasa con la caja cerrada** (no toca el cajón).

```sql
SET @egreso_honorarios := (SELECT MAX(id) FROM egreso WHERE consultorio_id = @sede_a AND tipo_beneficiario = 'COLABORADOR');
SELECT id, categoria, beneficiario_membership_id, beneficiario_nombre, periodo_desde, periodo_hasta,
       importe_total, saldo_pendiente, estado
FROM egreso WHERE id = @egreso_honorarios;
SELECT id, importe, medio, referencia, estado FROM pago_egreso WHERE egreso_id = @egreso_honorarios;
SELECT m.jornada_caja_id, m.tipo, m.medio, m.importe, m.afecta_arqueo
FROM movimiento_caja m JOIN pago_egreso p ON p.id = m.referencia_origen
WHERE m.tipo_origen = 'PAGO_EGRESO' AND p.egreso_id = @egreso_honorarios;
```

Esperado: `beneficiario_membership_id = @membership_profesional`; estado **`CONFIRMADO`** (no hay
estado "parcial": el pago parcial se ve en `saldo_pendiente = 2500`); el movimiento `TRANSFERENCIA`
con `jornada_caja_id` NULL y `afecta_arqueo = 0`.

Un colaborador **de otra organización** (o con el vínculo revocado) se rechaza con 409
`beneficiario-no-vinculado`; por la API, con el `membershipId` de admin-a en el centro B:

```powershell
Api POST "/api/v1/consultorios/<sede_a>/egresos" $A @{ categoria = 'HONORARIOS_PROFESIONALES'; tipoBeneficiario = 'COLABORADOR'; beneficiarioMembershipId = <membership de admin-a en B>; concepto = 'QA: ajeno'; importeTotal = 10; moneda = 'ARS'; idempotencyKey = (Nueva-Clave) }
```

---

### EGR-05 · Anular un pago — `[E2E flujo-caja]` (solo efectivo)

**Pantalla:** el egreso de EGR-03 → tabla de pagos → anular el pago con motivo `QA: pago duplicado`.

```sql
SELECT id, estado, saldo_pendiente FROM egreso WHERE id = @egreso;
SELECT id, estado, anulado_en, anulado_por_cuenta_id, motivo_anulacion FROM pago_egreso WHERE egreso_id = @egreso;
SELECT id, tipo, medio, importe, tipo_origen, movimiento_origen_id, motivo
FROM movimiento_caja WHERE consultorio_id = @sede_a AND tipo = 'REVERSION_DE_EGRESO' ORDER BY id DESC LIMIT 3;
```

Esperado: el egreso vuelve a `CONFIRMADO` con `saldo_pendiente = 1500`; el pago `ANULADO` con motivo
y actor; una `REVERSION_DE_EGRESO` que apunta (`movimiento_origen_id`) al movimiento del pago. Anular
otra vez el mismo pago es 409 `pago-egreso-ya-anulado`. Anular el pago **por transferencia** de
EGR-04 no genera reversión en la caja (no había movimiento de arqueo que revertir) y **no lo cubre
ningún E2E**. **Auditoría:** `EGRESO_PAGO_ANULADO` con `reason`.

---

### EGR-06 · Anular el egreso

**Pantalla:** primero intentá anular el de honorarios (EGR-04, tiene un pago vigente): rechazo
**409 `egreso-con-pagos`**. Después, un egreso sin pagos (el de EGR-05 tras anular su pago, o uno
nuevo) → "Anular el egreso" con motivo.

```sql
SELECT id, estado, saldo_pendiente, anulado_en, anulado_por_cuenta_id, motivo_anulacion, anulado_key
FROM egreso WHERE consultorio_id = @sede_a AND estado = 'ANULADO' ORDER BY id DESC LIMIT 3;
```

Esperado: `ANULADO` con motivo, actor y `anulado_key` lleno; la fila **sigue ahí**. Pagar un egreso
anulado es 409 `egreso-no-pagable`. **Auditoría:** `EGRESO_ANULADO` con `reason` y
`details.estado = ANULADO`.

---

### EGR-07 · Filtros del listado, el período por defecto y el día de la sede (api #105) — `[IT EgresoFechaYVersionIT]`

**Pantalla:** `/egresos` sin tocar nada.

**Esperado:** "Desde" arranca en el **primer día del mes de la sede** (no del navegador, web #60) y
"Hasta" vacío; la lista trae los egresos del mes, del más nuevo al más viejo. Filtrá por estado
`Anulado` y por categoría `Honorarios profesionales`: cada filtro lo aplica el servidor.

Desde #105 "Desde" y "Hasta" son **días de la sede**, inclusivos: el backend compara el instante de
registro contra `[00:00 de "Desde", 00:00 del día siguiente a "Hasta")` en la zona de la sede.
Hasta el 12/10 comparaba el día **UTC** y un egreso cargado entre las 21 y las 24 de Córdoba
quedaba afuera de "Hasta = hoy" (estaba en [90](90-conocido-y-aceptado.md) §4, ya corregido). Para
probarlo sin esperar a la noche, mové la sede a una zona en la que **ya sea otro día** que en UTC
(ORG-02: `Pacific/Kiritimati`, UTC+14, si corrés después de las 10 UTC —las 7 de Córdoba—; `Pacific/Pago_Pago`, UTC−11,
si corrés antes de las 11 UTC) y cargá un egreso **después** del cambio; filtrá "Desde = Hasta = hoy de la sede". Volvé la zona a la
original al terminar.

```sql
SELECT id, estado, categoria, registrado_en,
       DATE(registrado_en) AS dia_utc,
       DATE(CONVERT_TZ(registrado_en, '+00:00', (SELECT timezone FROM consultorio WHERE id = @sede_a))) AS dia_de_la_sede
FROM egreso WHERE consultorio_id = @sede_a ORDER BY id DESC LIMIT 10;
```

Esperado: con "Desde = Hasta = X", la pantalla lista exactamente las filas con `dia_de_la_sede = X`,
**también** las que tienen `dia_utc` distinto. Una fila que aparece o falta según `dia_utc` es
regresión de #105. El mismo criterio vale para la bandeja de prestaciones elegibles de
presentaciones ([PRE-11](08-presentaciones-facturacion-pagos.md)).

> Validado sobre el stack aislado (14/10): con la sede en `Pacific/Kiritimati` (hoy de la sede
> 15/10, hoy UTC 14/10), "Desde = Hasta = 15/10" trajo los cuatro egresos del día y
> "Desde = Hasta = 14/10", ninguno.

---

### EGR-08 · Quién ve los egresos

**Pantalla:** ingresar con el profesional: el menú **no** tiene "Egresos" y `/egresos` escrito a
mano no muestra datos. Por la API, `GET /api/v1/consultorios/<sede_a>/egresos` con el profesional
es **403** (sin `caja:operate`); con el token del centro B sobre la sede de A, **404**
([AIS-01](11-aislamiento-y-rol-paciente.md)).

```sql
SELECT event_type, entity_id, actor_account_id, reason, LEFT(details, 160) AS details, occurred_at
FROM audit_event
WHERE organization_id = @org_a AND entity_type = 'Egreso' AND occurred_at >= @desde
ORDER BY id;
```

Esperado: un `EGRESO_REGISTRADO` por alta, `EGRESO_CONFIRMADO`, `EGRESO_PAGADO` por pago,
`EGRESO_PAGO_ANULADO` y `EGRESO_ANULADO` con `reason`; ninguno del profesional. El 403 deja un
`PERMISSION_DENIED`.

---

### EGR-09 · Corregir un borrador con la versión leída (api #105, web #73) — `[IT EgresoFechaYVersionIT]`

Hasta el contrato 0.91.0 `PUT .../egresos/{id}` no llevaba versión y **dos correcciones simultáneas
del mismo borrador se pisaban en silencio**. Desde #105 el cuerpo es `EditarEgreso` con la
`version` leída, **requerida**; si otro la cambió antes, 409 `concurrent-modification`, y la
respuesta trae la versión nueva para encadenar la siguiente corrección. El beneficiario, la moneda
y la clave ya no viajan en la corrección. El IT prueba la versión vieja; la pantalla, no.

**Pantalla:** dos ventanas con el detalle del **mismo** borrador de EGR-02 (`/egresos/<id>`). En
la primera, "Corregir el borrador" → cambiar el concepto → guardar. En la segunda (que leyó antes),
cambiar el importe → guardar.

**Esperado:** la primera guarda y el formulario queda con los datos nuevos (y su versión: una
segunda corrección desde esa misma ventana también guarda). La segunda **no** pisa nada: muestra
"Alguien cambio este egreso despues de que lo abriste, asi que no guardamos la correccion sobre
datos viejos. No se piso nada: recarga el egreso y volve a corregirlo." y ofrece recargar. Recargada,
la corrección entra.

```sql
SET @egreso_borrador := (SELECT MAX(id) FROM egreso WHERE consultorio_id = @sede_a AND estado = 'BORRADOR');
SELECT id, estado, concepto, importe_total, moneda, tipo_beneficiario, beneficiario_nombre, version, updated_at
FROM egreso WHERE id = @egreso_borrador;
```

Esperado: `concepto` de la primera ventana y el importe de la segunda **solo** después de recargar;
`version` sube **una** vez por corrección que entró; moneda y beneficiario, los del alta. Por la
API: sin `version`, **400 `validation-error`**; con una vieja, **409 `concurrent-modification`**;
sobre un egreso ya confirmado con su versión vigente, **409 `egreso-no-editable`** (con una versión
vieja gana el 409 de versión). **Auditoría:** la corrección no deja evento propio (EGR-02); el 409
tampoco.

> Validado sobre el stack aislado (14/10): versión leída 200 (`version` 0 → 1), la misma versión
> otra vez 409, sin versión 400, encadenada con la de la respuesta 200, y dos correcciones
> simultáneas con la misma versión: una 200 y otra 409.
