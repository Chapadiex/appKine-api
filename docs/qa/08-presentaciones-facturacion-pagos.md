# 08 · Presentaciones a financiadores, facturación y pagos — P1

Módulo `billing` (M21). `[E2E flujo-presentaciones]` recorre por pantalla borrador → agregar →
revisar → confirmar → factura → pago → conciliar. **No mira la base, no prueba el débito** (el
defecto #7 del 07/10 respondía `DEBITADO` y dejaba `INCLUIDO` en la base) **ni la anulación del
lote**.

Tablas: `presentacion`, `presentacion_item`, `presentacion_numerador`, `financiador_pago`,
`movimiento_caja` (`tipo_origen = PAGO_FINANCIADOR`), `obligacion`.

> **Cuándo se salda la deuda del financiador:** al **conciliar**, ítem por ítem y por el importe
> exacto presentado. **No** al registrar el pago: un pago es un importe global y no dice qué
> prestaciones cubre (diseño 07.04 §8). Hasta conciliar, la obligación del financiador sigue
> `PENDIENTE` aunque el lote esté pagado. No es bug.

Requisito: la obligación `FINANCIADOR` de [CLI-05](06-atencion-clinica.md). Para probar débito y
aceptación a la vez, cerrá **dos** sesiones con cobertura (dos obligaciones del financiador).

---

### PRE-01 · Bandeja de prestaciones elegibles

**Pantalla:** administrativo → `/presentaciones` → elegir el financiador de PER-05.

**Esperado:** aparecen las obligaciones `FINANCIADOR` pendientes y no presentadas, con el convenio
**congelado** (código, arancel, coseguro, requisitos). Las obligaciones del paciente no aparecen.

```sql
SELECT o.id, o.sesion_id, o.persona_id, o.importe_original, o.saldo, o.estado, o.snapshot_convenio_codigo,
       o.snapshot_requeria_orden, o.snapshot_requeria_autorizacion, o.snapshot_requeria_credencial, o.snapshot_credencial_vencida
FROM obligacion o
WHERE o.organization_id = @org_a AND o.responsable = 'FINANCIADOR' AND o.financiador_id = @financiador
  AND o.estado IN ('PENDIENTE', 'PARCIAL')
  AND NOT EXISTS (SELECT 1 FROM presentacion_item i WHERE i.obligacion_id = o.id AND i.estado IN ('INCLUIDO', 'ACEPTADO'));
```

Esperado: lo mismo que la bandeja, fila por fila.

---

### PRE-02 · Crear el borrador y agregar ítems — `[E2E flujo-presentaciones]`

**Pantalla:** nuevo lote del período actual con las obligaciones elegibles.

```sql
SET @presentacion := (SELECT MAX(id) FROM presentacion WHERE consultorio_id = @sede_a AND financiador_id = @financiador);
SELECT id, numero, estado, periodo_desde, periodo_hasta, moneda, total_presentado, total_debitado, total_cobrado, saldo, creada_en
FROM presentacion WHERE id = @presentacion;
SELECT id, obligacion_id, estado, importe_presentado, snapshot_persona_id, snapshot_sesion_id, snapshot_fecha_prestacion, ocupa_marca
FROM presentacion_item WHERE presentacion_id = @presentacion ORDER BY id;
```

Esperado: `BORRADOR` **sin número** (`numero` NULL hasta confirmar); un ítem `INCLUIDO` por
obligación con `ocupa_marca = 1`; `total_presentado` = suma de los ítems y `saldo = total_presentado`.
La misma obligación en un segundo borrador es 409 `obligacion-ya-presentada` (el unique sobre
`ocupa_marca` lo impide).

**Auditoría:** `PRESENTACION_CREADA`.

---

### PRE-03 · Revisar antes de confirmar

**Pantalla:** "Revisar" el lote: lista hallazgos por ítem (orden, autorización, credencial,
financiador distinto, deuda del paciente).

**Esperado:** los hallazgos **avisan y no bloquean**: RF-M21-003 todavía no bloquea por orden,
autorización ni credencial (decisión abierta, ver [90](90-conocido-y-aceptado.md)). Solo lectura.

---

### PRE-04 · Confirmar: número correlativo por financiador — `[E2E flujo-presentaciones]`

**Pantalla:** confirmar el lote.

```sql
SELECT numero, estado, confirmada_en, confirmada_por_cuenta_id, version FROM presentacion WHERE id = @presentacion;
SELECT financiador_id, ultimo_numero FROM presentacion_numerador WHERE consultorio_id = @sede_a;
```

Esperado: `PRESENTADA` con `numero` = `ultimo_numero` del numerador **de ese financiador en esa
sede**; ya no se agregan ni quitan ítems. **Auditoría:** `PRESENTACION_CONFIRMADA`.

---

### PRE-05 · Registrar la factura — `[E2E flujo-presentaciones]`

**Pantalla:** factura `FC-0001-QA` con fecha de hoy.

```sql
SELECT estado, factura_numero, factura_fecha, factura_registrada_en, factura_registrada_por_cuenta_id
FROM presentacion WHERE id = @presentacion;
```

Esperado: `FACTURADA`. Facturar **no** mueve caja. **Auditoría:** `PRESENTACION_FACTURADA`.

---

### PRE-06 · Debitar un ítem: que la base diga lo mismo que la pantalla

**Pantalla:** en el lote facturado, debitar **un** ítem por **el total** de ese ítem con motivo
"Falta firma en la orden".

```sql
SELECT id, obligacion_id, estado, importe_presentado, importe_debitado, motivo_debito, debitado_en, debitado_por_cuenta_id, ocupa_marca
FROM presentacion_item WHERE presentacion_id = @presentacion ORDER BY id;
SELECT total_presentado, total_debitado, total_cobrado, saldo FROM presentacion WHERE id = @presentacion;
```

Esperado: el ítem **`DEBITADO` en la base** (no solo en la respuesta: defecto #7 del 07/10), con
importe, motivo y actor; `ocupa_marca` NULL (la obligación queda libre para otro lote);
`total_debitado` sube y `saldo = presentado − debitado − cobrado`. Un débito mayor que lo presentado
es 400. **Auditoría:** `PRESENTACION_ITEM_DEBITADO` con `reason`.

> **Débito parcial:** si debitás **menos** que lo presentado (p. ej. 1000 de 6000), el ítem queda
> `DEBITADO` entero y, al conciliar, su obligación **no se salda en nada**: queda `PENDIENTE` por
> el total aunque el financiador haya pagado el resto. Está escrito en el diseño (07.04 §8: "un
> ítem DEBITADO no salda nada") y se reprodujo al validar esta guía. Si lo ves, registralo como
> **pregunta al dueño del producto**, no como defecto nuevo: ver [90](90-conocido-y-aceptado.md).

---

### PRE-07 · Registrar el pago del financiador — `[E2E flujo-presentaciones]`

**Pantalla:** pago por transferencia por el saldo del lote, referencia `TRF-OS-QA`.

```sql
SELECT id, presentacion_id, importe, medio, fecha_pago, referencia, registrado_por_cuenta_id, idempotency_key
FROM financiador_pago WHERE presentacion_id = @presentacion;
SELECT total_cobrado, saldo, estado FROM presentacion WHERE id = @presentacion;
SELECT jornada_caja_id, tipo, medio, importe, afecta_arqueo
FROM movimiento_caja WHERE tipo_origen = 'PAGO_FINANCIADOR' AND referencia_origen IN (SELECT id FROM financiador_pago WHERE presentacion_id = @presentacion);
SELECT id, saldo, estado FROM obligacion WHERE id IN (SELECT obligacion_id FROM presentacion_item WHERE presentacion_id = @presentacion);
```

Esperado: el pago; `saldo` del lote en 0; **un** `INGRESO` `PAGO_FINANCIADOR` con `afecta_arqueo = 0`
(transferencia: fue al banco, no al cajón); las obligaciones **todavía** `PENDIENTE`. Pagar más que
el saldo es 409. **Auditoría:** `PRESENTACION_PAGO_REGISTRADO`.

---

### PRE-08 · Conciliar: recién acá se salda la deuda — `[E2E flujo-presentaciones]`

**Pantalla:** conciliar el lote (exige saldo 0: no hay cierre con diferencia).

```sql
SELECT estado, conciliada_en, conciliada_por_cuenta_id FROM presentacion WHERE id = @presentacion;
SELECT i.id, i.estado AS item, o.id AS obligacion, o.saldo, o.estado
FROM presentacion_item i JOIN obligacion o ON o.id = i.obligacion_id
WHERE i.presentacion_id = @presentacion ORDER BY i.id;
```

Esperado: `CONCILIADA`; los ítems que seguían `INCLUIDO` pasan a `ACEPTADO` y **su** obligación queda
`PAGADA` con `saldo = 0`; el ítem `DEBITADO` sigue así y su obligación sigue `PENDIENTE` (RN-M21-004:
rechazar no perdona la deuda). **Auditoría:** `PRESENTACION_CONCILIADA`.

---

### PRE-09 · Anular un lote y la cuenta corriente del financiador

**Pantalla:** crear otro borrador con la obligación debitada en PRE-06 (vuelve a estar elegible) y
anularlo con motivo **sin confirmarlo**: solo un borrador se anula; un lote ya presentado responde 409
("ya no es un borrador"). Después mirar la cuenta corriente del financiador desde
`/presentaciones`.

```sql
SET @presentacion_anulada := (SELECT MAX(id) FROM presentacion WHERE consultorio_id = @sede_a AND estado = 'ANULADA');
SELECT id, numero, estado, anulada_en, anulada_por_cuenta_id, motivo_anulacion FROM presentacion WHERE id = @presentacion_anulada;
SELECT id, obligacion_id, estado, ocupa_marca FROM presentacion_item WHERE presentacion_id = @presentacion_anulada;
```

Esperado: `ANULADA` con motivo y sin número (nunca se confirmó); los ítems `ANULADO` y la obligación
libre otra vez. La cuenta corriente del financiador suma lo presentado, debitado y cobrado de **todas**
las sedes de la organización (defecto 2 del 06/10). **Auditoría:** `PRESENTACION_ANULADA`.

```sql
SELECT p.financiador_id, COUNT(*) AS lotes, SUM(p.total_presentado) AS presentado, SUM(p.total_debitado) AS debitado,
       SUM(p.total_cobrado) AS cobrado, SUM(p.saldo) AS saldo
FROM presentacion p
WHERE p.organization_id = @org_a AND p.financiador_id = @financiador AND p.estado <> 'ANULADA'
GROUP BY p.financiador_id;
```

Esperado: los mismos totales que muestra la pantalla.
