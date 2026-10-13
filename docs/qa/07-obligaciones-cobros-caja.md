# 07 · Obligaciones, cobros, anulación, reintegro y caja — P1

Módulo `billing` (M18 deuda, M19 cobro, M20 caja). **Plata**: el circuito donde un `200` que no
persistió hace más daño. `[E2E flujo-caja]` cubre abrir, movimiento manual, revertir, el 409
`caja-saldo-cambio`, cerrar con arqueo y un cobro en efectivo de una sesión cerrada.
`[E2E flujo-cobros-posteriores]` (web #64) cubre por pantalla, **todo por transferencia**, imputar
el saldo a favor, anular un cobro y reintegrar parte de otro. **Nada automático cubre por
pantalla** el coseguro, DP-17, la anulación de una deuda, el cobro parcial ni lo que esos pasos
hacen **en efectivo** sobre la caja. Ninguno de los dos specs mira la base. Los egresos tienen
circuito propio: [12](12-egresos-y-pagos.md).

**Deuda, cobro y caja son tres cosas** (07.01). La deuda (`obligacion`) nace al cerrar la sesión;
el cobro (`cobro` + `cobro_medio` + `cobro_imputacion`) la salda; la caja (`jornada_caja` +
`movimiento_caja`) registra el efectivo. Importes en `DECIMAL`, nunca `float`.

> **Auditoría de cobros y deudas (desde appKine-api #86):** registrar un cobro deja
> `COBRO_REGISTRADO`, devengar una deuda al cerrar la sesión deja `OBLIGACION_DEVENGADA` (a nombre
> de quien cerró) y anular una deuda deja `OBLIGACION_ANULADA` con `reason`. También auditan abrir
> y cerrar caja, movimiento manual, reversión, anular, reintegrar e imputar un cobro, y los egresos.
> El reintento idempotente de un cobro no audita: no hay hecho nuevo.

Requisitos: sesiones cerradas de [CLI-05](06-atencion-clinica.md) (con cobertura) y
[CLI-06](06-atencion-clinica.md) (particular).

---

### ECO-01 · La cuenta corriente muestra solo lo que debe el paciente

**Pantalla:** administrativo → `/pacientes/<persona>/cuenta-corriente` (la persona con obra social).

**Esperado:** aparece la deuda de **coseguro** (2000). La parte del financiador (6000) **no** está:
la debe la obra social, no el paciente (defecto #4 del 07/10, corregido).

```sql
SELECT id, sesion_id, responsable, concepto, financiador_id, importe_original, saldo, moneda, estado, devengada_en
FROM obligacion WHERE persona_id = @persona ORDER BY id;
```

Esperado: dos filas de la sesión; la pantalla muestra solo la `PACIENTE`.

---

### ECO-02 · DP-17: sin precio particular, la sesión no cierra — `[IT CierreSinPrecioIT]`

**Pantalla:** crear una segunda oferta **sin precio** (CAT-04 sin CAT-07), habilitar al profesional,
reservar un turno de hoy para la persona particular, recepción → Particular → espera → llamado. El
profesional inicia y **cierra** presente.

**Esperado:** el cierre se rechaza con **409 `oferta-sin-precio`** y un mensaje que dice que falta el
precio. La sesión sigue abierta. Cargá el precio (CAT-07) y cerrá: ahora sí.

Antes de cargar el precio, con la sesión rechazada:

```sql
SET @sesion_sin_precio := (SELECT MAX(id) FROM sesion WHERE consultorio_id = @sede_a AND estado = 'BORRADOR');
SELECT id, estado, numero_sesion, cerrada_en FROM sesion WHERE id = @sesion_sin_precio;
SELECT COUNT(*) AS obligaciones FROM obligacion WHERE sesion_id = @sesion_sin_precio;
SELECT n.ultimo_numero FROM sesion_numerador n JOIN sesion s ON s.historia_clinica_id = n.historia_clinica_id
WHERE s.id = @sesion_sin_precio;
```

Esperado: `BORRADOR`, `numero_sesion` NULL, **cero** obligaciones, y el numerador **sin avanzar**
(anotá el número antes del intento): el 409 se decide antes de numerar. Ninguna autorización
consumida.

---

### ECO-03 · Prepago imputado al cerrar

Requiere [AGE-12](05-agenda-turnos-recepcion.md): turno con anticipo y la sesión de ese turno
cerrada.

```sql
SELECT c.id AS cobro, c.total, c.saldo_a_favor, i.obligacion_id, i.importe, o.saldo AS saldo_deuda, o.estado
FROM cobro c
LEFT JOIN cobro_imputacion i ON i.cobro_id = c.id
LEFT JOIN obligacion o ON o.id = i.obligacion_id
WHERE c.turno_id = @turno_prepago;
```

Esperado: el anticipo imputado a la deuda **de esa sesión** y nada más; `saldo_a_favor` baja en lo
imputado. Si el anticipo supera la deuda, lo que sobra **queda a favor**: no va a deudas viejas
(E-6).

---

### ECO-04 · Abrir la caja — `[E2E flujo-caja]`

**Pantalla:** `/caja` → abrir con saldo inicial `0` (o el que quieras arquear).

```sql
SET @jornada := (SELECT id FROM jornada_caja WHERE consultorio_id = @sede_a AND estado = 'ABIERTA');
SELECT id, fecha_negocio, moneda, estado, saldo_inicial, saldo_arqueo, abierta_en, abierta_por_cuenta_id
FROM jornada_caja WHERE id = @jornada;
```

Esperado: **una** jornada `ABIERTA` por sede. Abrir otra es 409. **Auditoría:** `CAJA_ABIERTA`.

---

### ECO-05 · Cobrar el coseguro en efectivo — `[E2E flujo-caja]`

**Pantalla:** cuenta corriente → cobrar → la deuda de coseguro → efectivo 2000. Ver el comprobante.

```sql
SET @cobro := (SELECT MAX(id) FROM cobro WHERE consultorio_id = @sede_a AND persona_id = @persona AND deleted_at IS NULL);
SELECT id, persona_id, total, saldo_a_favor, moneda, comprobante_numero, cobrado_en, cobrado_por_cuenta_id, idempotency_key
FROM cobro WHERE id = @cobro;
SELECT medio, importe, referencia FROM cobro_medio WHERE cobro_id = @cobro;
SELECT obligacion_id, importe, imputada_en FROM cobro_imputacion WHERE cobro_id = @cobro;
SELECT id, saldo, estado, version FROM obligacion WHERE persona_id = @persona AND responsable = 'PACIENTE';
SELECT id, tipo, medio, importe, tipo_origen, referencia_origen, afecta_arqueo
FROM movimiento_caja WHERE tipo_origen = 'COBRO' AND referencia_origen = @cobro;
SELECT ultimo_numero FROM comprobante_numerador WHERE consultorio_id = @sede_a;
```

Esperado: `SUM(medios) = total`; la imputación por 2000; la obligación `PAGADA` con `saldo = 0`;
**un** movimiento `INGRESO` `COBRO` en efectivo con `afecta_arqueo = 1`; `comprobante_numero` igual
al `ultimo_numero` del numerador (correlativo por sede, sin huecos). Doble click en "Cobrar" no crea
dos cobros (idempotencia).

Las dos sumas son invariantes **de la aplicación** (MySQL no admite subconsultas en un `CHECK`): que
no haya ningún cobro que las rompa:

```sql
SELECT c.id, c.total, c.saldo_a_favor,
       (SELECT COALESCE(SUM(m.importe), 0) FROM cobro_medio m WHERE m.cobro_id = c.id) AS suma_medios,
       (SELECT COALESCE(SUM(i.importe), 0) FROM cobro_imputacion i WHERE i.cobro_id = c.id) AS suma_imputaciones,
       (SELECT COALESCE(SUM(r.importe), 0) FROM cobro_reintegro r WHERE r.cobro_id = c.id) AS suma_reintegros
FROM cobro c
WHERE c.organization_id = @org_a AND c.deleted_at IS NULL
HAVING suma_medios <> c.total OR suma_imputaciones + suma_reintegros + c.saldo_a_favor <> c.total;
```

Esperado: **cero filas**: todo lo cobrado está imputado, reintegrado o a favor. Los cobros anulados
quedan afuera (`deleted_at`): sus imputaciones siguen en la tabla como historia, pero ya no cuentan.

---

### ECO-06 · Anular una deuda

**Pantalla:** cuenta corriente → una deuda **sin cobros** (p. ej. la de una sesión de prueba) →
anular con motivo.

```sql
SET @obligacion_anulada := (SELECT MAX(id) FROM obligacion WHERE organization_id = @org_a AND estado = 'ANULADA');
SELECT id, sesion_id, estado, saldo, anulada_en, anulada_por_cuenta_id, motivo_anulacion, deleted_at, version
FROM obligacion WHERE id = @obligacion_anulada;
SELECT COUNT(*) AS imputaciones FROM cobro_imputacion WHERE obligacion_id = @obligacion_anulada;
```

Esperado: `ANULADA` con motivo y actor; la fila **sigue ahí**. Una deuda con un cobro imputado no se
anula sin antes anular el cobro. Si la sesión consumió autorización, sigue en
[PER-12](04-personas-coberturas-autorizaciones.md) (alerta, no reversión).

---

### ECO-07 · Cobro parcial

**Pantalla:** sobre la deuda particular de CLI-06 (10000), cobrar 4000 con transferencia
(referencia `TRF-QA-1`).

```sql
SET @obligacion := (SELECT MAX(id) FROM obligacion WHERE sesion_id = @sesion_part AND responsable = 'PACIENTE');
SELECT importe_original, saldo, estado FROM obligacion WHERE id = @obligacion;
SELECT m.tipo, m.medio, m.importe, m.afecta_arqueo
FROM movimiento_caja m JOIN cobro_imputacion i ON i.cobro_id = m.referencia_origen AND m.tipo_origen = 'COBRO'
WHERE i.obligacion_id = @obligacion;
```

Esperado: `PARCIAL`, `saldo = 6000`; el movimiento de transferencia con `afecta_arqueo = 0` (no
entra al arqueo del efectivo).

---

### ECO-08 · Anticipo e imputación posterior — `[E2E flujo-cobros-posteriores]`

**Pantalla:** `/pacientes/<id>/cuenta-corriente/cobrar` → un anticipo de 3000 en efectivo sin
imputar (queda a favor). Después, desde `/pacientes/<id>/cuenta-corriente/cobros`, imputar 3000 de
ese saldo a la deuda de ECO-07.

```sql
SET @cobro_anticipo := (SELECT MAX(id) FROM cobro WHERE persona_id = @persona_part AND deleted_at IS NULL);
SELECT id, total, saldo_a_favor FROM cobro WHERE id = @cobro_anticipo;
SELECT obligacion_id, importe, imputada_en, imputada_por_cuenta_id FROM cobro_imputacion WHERE cobro_id = @cobro_anticipo;
SELECT saldo, estado FROM obligacion WHERE id = @obligacion;
```

Esperado: el anticipo nace con `saldo_a_favor = 3000`; tras imputar, `saldo_a_favor = 0`, una
imputación y la deuda en `saldo = 3000`. Imputar más que el saldo a favor es 409
`saldo-a-favor-insuficiente`. **Auditoría:** `COBRO_SALDO_IMPUTADO`.

---

### ECO-09 · Reintegrar saldo a favor — `[E2E flujo-cobros-posteriores]` (por transferencia)

**Pantalla:** otro anticipo de 1000; reintegrar 400 en efectivo con motivo.

```sql
SET @cobro_reintegrado := (SELECT MAX(cobro_id) FROM cobro_reintegro WHERE organization_id = @org_a);
SELECT id, total, saldo_a_favor FROM cobro WHERE id = @cobro_reintegrado;
SELECT importe, medio, motivo, reintegrado_en, reintegrado_por_cuenta_id FROM cobro_reintegro WHERE cobro_id = @cobro_reintegrado;
SELECT tipo, medio, importe, tipo_origen FROM movimiento_caja WHERE tipo_origen = 'REINTEGRO' ORDER BY id DESC LIMIT 3;
```

Esperado: `saldo_a_favor = 600`; un reintegro; un movimiento `EGRESO` con `tipo_origen = REINTEGRO`.
**Auditoría:** `COBRO_REINTEGRADO`.

---

### ECO-10 · Anular un cobro — `[E2E flujo-cobros-posteriores]` (por transferencia)

**Pantalla:** `/pacientes/<id>/cuenta-corriente/cobros` → anular el cobro de ECO-07 con motivo. Intentá
también anular el de ECO-09 (tiene reintegro).

```sql
SELECT id, deleted_at, anulado_por_cuenta_id, motivo_anulacion FROM cobro WHERE persona_id = @persona_part ORDER BY id;
SELECT saldo, estado FROM obligacion WHERE id = @obligacion;
SELECT m.id, m.tipo, m.importe, m.tipo_origen, m.movimiento_origen_id, m.motivo
FROM movimiento_caja m WHERE m.consultorio_id = @sede_a AND m.tipo LIKE 'REVERSION%' ORDER BY m.id DESC LIMIT 5;
```

Esperado: el cobro con `deleted_at` y motivo (baja lógica); la deuda **vuelve** a tener el saldo que
ese cobro había cancelado; una `REVERSION_DE_INGRESO` que apunta al movimiento original. El de ECO-09
se rechaza con 409 `cobro-con-reintegros`. **Auditoría:** `COBRO_ANULADO` con
`details.movimientosRevertidos` e `imputacionesRevertidas`.

**El cobro anulado desaparece del listado** `/pacientes/<id>/cuenta-corriente/cobros`: el listado
del backend filtra `deleted_at IS NULL`, y el comprobante anulado solo se ve abriéndolo por id
(`GET .../cobros/<id>` sí trae `estado`, `anuladoEn` y `motivoAnulacion`). Es el defecto conocido de
[90](90-conocido-y-aceptado.md) §4: vinculalo, no lo registres como nuevo. Compará:

```sql
SELECT id, comprobante_numero, total, deleted_at IS NOT NULL AS anulado, motivo_anulacion
FROM cobro WHERE persona_id = @persona_part ORDER BY id;
```

Esperado por la base: todos los comprobantes, anulados incluidos (los números de comprobante no
tienen huecos). Lo que hoy muestra la pantalla: solo los de `anulado = 0`.

---

### ECO-11 · Movimiento manual y reversión — `[E2E flujo-caja]`

**Pantalla:** `/caja` → egreso manual de 500 "Compra de insumos QA" → revertirlo con motivo.

```sql
SELECT id, tipo, medio, importe, concepto, motivo, tipo_origen, movimiento_origen_id, registrado_por_cuenta_id
FROM movimiento_caja WHERE jornada_caja_id = @jornada AND tipo_origen IN ('MANUAL', 'REVERSION') ORDER BY id;
```

Esperado: el `EGRESO` `MANUAL` y su `REVERSION_DE_EGRESO` con motivo y `movimiento_origen_id`.
Ninguno se borra. **Auditoría:** `CAJA_MOVIMIENTO_MANUAL`, `CAJA_MOVIMIENTO_REVERTIDO` (con
`reason`).

---

### ECO-12 · Cerrar la caja con arqueo — `[E2E flujo-caja]`

**Pantalla:** `/caja` → cerrar declarando **100 menos** que el teórico; el motivo de la diferencia
es obligatorio.

Antes de cerrar, el saldo teórico tiene que salir de los movimientos:

```sql
SELECT j.saldo_inicial, j.saldo_arqueo AS teorico_guardado,
       j.saldo_inicial + COALESCE(SUM(CASE WHEN m.tipo IN ('INGRESO', 'REVERSION_DE_EGRESO') THEN m.importe ELSE -m.importe END), 0) AS teorico_calculado
FROM jornada_caja j LEFT JOIN movimiento_caja m ON m.jornada_caja_id = j.id AND m.afecta_arqueo = 1
WHERE j.id = @jornada
GROUP BY j.id, j.saldo_inicial, j.saldo_arqueo;
```

Esperado: `teorico_guardado = teorico_calculado` y igual al que muestra la pantalla. Después de
cerrar:

```sql
SELECT estado, saldo_teorico_cierre, saldo_declarado, diferencia, motivo_diferencia, cerrada_en, cerrada_por_cuenta_id
FROM jornada_caja WHERE id = @jornada;
```

Esperado: `CERRADA`, `diferencia = -100` con el motivo. Si otra persona movió la caja mientras
mirabas, el cierre rebota con 409 `caja-saldo-cambio` (lo prueba el E2E). **Auditoría:**
`CAJA_CERRADA`.

---

### ECO-13 · Egresos y pagos → circuito [12](12-egresos-y-pagos.md)

Desde `appKine-web` #63 los egresos **tienen pantalla** (`/egresos`) y pasaron a su propio
circuito, [12 · Egresos y pagos](12-egresos-y-pagos.md) (EGR-01 a EGR-08). Lo de abajo queda como
camino por la API, para repetir un caso sin pasar por la pantalla.

```powershell
$e = (Api POST "/api/v1/consultorios/<sede_a>/egresos" $A @{ categoria = 'INSUMOS'; tipoBeneficiario = 'EXTERNO'; beneficiarioNombre = 'Proveedor QA'; concepto = 'Vendas QA'; importeTotal = 1500; moneda = 'ARS'; comprobanteTipo = 'FACTURA_B'; comprobanteNumero = 'B-0001-QA'; comprobanteFecha = '<hoy>' }).Cuerpo
Api POST "/api/v1/consultorios/<sede_a>/egresos/$($e.id)/confirmacion" $A
$pg = (Api POST "/api/v1/consultorios/<sede_a>/egresos/$($e.id)/pagos" $A @{ importe = 1500; medio = 'EFECTIVO'; idempotencyKey = (Nueva-Clave) }).Cuerpo
Api POST "/api/v1/consultorios/<sede_a>/egresos/$($e.id)/pagos/$($pg.id)/anulacion" $A @{ motivo = 'QA G-12: pago duplicado' }
```

Confirmar **sin** comprobante es 400. Con la caja cerrada, todo lo que es efectivo (pago, cobro) es
409 `caja-no-abierta`; por transferencia pasa sin caja (no afecta el arqueo).

```sql
SET @egreso := (SELECT MAX(id) FROM egreso WHERE consultorio_id = @sede_a);
SELECT id, categoria, estado, importe_total, saldo_pendiente, comprobante_tipo, comprobante_numero, confirmado_en
FROM egreso WHERE id = @egreso;
SELECT id, importe, medio, estado, pagado_en, anulado_en, motivo_anulacion FROM pago_egreso WHERE egreso_id = @egreso;
SELECT tipo, medio, importe, tipo_origen, referencia_origen FROM movimiento_caja
WHERE tipo_origen = 'PAGO_EGRESO' OR (tipo LIKE 'REVERSION%' AND motivo LIKE 'QA G-12: pago%') ORDER BY id DESC LIMIT 4;
```

Esperado: el egreso `CONFIRMADO` → `PAGADO` con el pago, y tras anular el pago vuelve a
`CONFIRMADO` con `saldo_pendiente = 1500`; el pago `ANULADO`; un `EGRESO` `PAGO_EGRESO` y su
reversión. **Auditoría:** `EGRESO_REGISTRADO`, `EGRESO_CONFIRMADO`, `EGRESO_PAGADO`,
`EGRESO_PAGO_ANULADO`.

> El egreso reusa `caja:operate` (no hay `egreso:manage`) y el comprobante es una **referencia**
> —tipo, número, fecha—, no un archivo adjunto. Las dos son decisiones abiertas, ver [90](90-conocido-y-aceptado.md).

---

### ECO-14 · Auditoría de devengo, cobro y anulación (api #86)

Hasta `appKine-api` #86, registrar un cobro, devengar una deuda y anular una deuda **no escribían
`audit_event`** (lo encontró la validación de esta guía). Se corre al final del circuito, cuando ya
pasaron CLI-05/CLI-06 (devengo), ECO-05 y ECO-07 (cobros), ECO-06 (anulación de deuda) y ECO-10
(anulación de cobro).

```sql
SELECT a.id, a.event_type, a.entity_type, a.entity_id, a.actor_account_id, a.reason,
       LEFT(a.details, 220) AS details, a.occurred_at
FROM audit_event a
WHERE a.organization_id = @org_a AND a.occurred_at >= @desde
  AND a.event_type IN ('OBLIGACION_DEVENGADA', 'OBLIGACION_ANULADA', 'COBRO_REGISTRADO', 'COBRO_ANULADO',
                       'COBRO_SALDO_IMPUTADO', 'COBRO_REINTEGRADO')
ORDER BY a.id;
```

Esperado:

- **Un `OBLIGACION_DEVENGADA` por obligación** (`entity_type = 'Obligacion'`), a nombre de quien
  **cerró la sesión** (el profesional, no el administrativo), con `details.concepto`,
  `details.responsable`, `details.importe` y `details.sesionId`; la del financiador trae además
  `details.financiadorId`. Una sesión con obra social deja **dos** (FINANCIADOR + COSEGURO); una
  particular, **una** (PARTICULAR).
- **Un `COBRO_REGISTRADO` por cobro** con `details.total`, `details.comprobanteNumero` y
  `details.imputaciones` (y `details.anticipo` si fue anticipo). El reintento idempotente (doble
  click) **no** deja un segundo evento.
- `OBLIGACION_ANULADA` y `COBRO_ANULADO` con el motivo en `reason`.

Que cada hecho tenga su evento, por aritmética:

```sql
SELECT (SELECT COUNT(*) FROM obligacion WHERE organization_id = @org_a AND devengada_en >= @desde) AS obligaciones,
       (SELECT COUNT(*) FROM audit_event WHERE organization_id = @org_a AND occurred_at >= @desde
          AND event_type = 'OBLIGACION_DEVENGADA') AS devengos_auditados,
       (SELECT COUNT(*) FROM cobro WHERE organization_id = @org_a AND cobrado_en >= @desde) AS cobros,
       (SELECT COUNT(*) FROM audit_event WHERE organization_id = @org_a AND occurred_at >= @desde
          AND event_type = 'COBRO_REGISTRADO') AS cobros_auditados;
```

Esperado: `obligaciones = devengos_auditados` y `cobros = cobros_auditados` (los cobros anulados
cuentan en los dos lados: la anulación es un evento aparte). Una diferencia es hallazgo **alto**.
