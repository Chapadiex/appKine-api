# 05 · Agenda, turnos, series, recepción y prepago — P2

Módulo `scheduling` (M09, M10, E-3, E-4, E-6). Es la vertical **mejor cubierta**: seis specs E2E
(`agenda-*`, `flujo-series`, `flujo-prepago`) la recorren por pantalla contra el backend real, y la
concurrencia está en ITs contra MySQL. A mano: **una pasada feliz mirando la base** y lo que ningún
spec toca (ausente, reprogramar una serie, anular una recepción).

Tablas: `turno`, `turno_evento`, `turno_serie`, `recepcion`, `recepcion_evento`, `agenda_sede`
(fila-lock), `cobro` (prepago).

> **Turno ≠ Sesión ≠ Recepción** (DP-05, DP-16). El turno es solo la reserva: **no** pasa a
> "atendido" ni a "en espera". Después de atender, el turno sigue `RESERVADO` o `CONFIRMADO`; lo
> que prueba la atención es la sesión ([06](06-atencion-clinica.md)). Reservar y confirmar no
> escriben `audit_event`: su rastro es `turno_evento` (append-only).

Requisitos: [CAT-04](03-catalogo-servicios-ofertas.md) a CAT-08 (oferta con profesional y
horario) y [PER-02](04-personas-coberturas-autorizaciones.md) (persona con perfil de paciente).

---

### AGE-01 · Buscar y reservar — `[E2E agenda-reserva]`

**Pantalla:** administrativo → `/agenda` → la oferta → un slot de **hoy** todavía futuro → la
persona de PER-01 → reservar. Hacé doble click en "Reservar".

**Esperado:** un solo turno (la `idempotencyKey` del cliente evita el segundo). La grilla marca el
slot como ocupado.

```sql
SET @turno := (SELECT MAX(id) FROM turno WHERE consultorio_id = @sede_a AND persona_id = @persona);
SELECT id, oferta_id, persona_id, profesional_membership_id, espacio_id, inicio, fin, estado, serie_id,
       idempotency_key, reservado_por_cuenta_id, reservado_en, deleted_at, version
FROM turno WHERE id = @turno;

SELECT tipo, estado_anterior, estado_nuevo, inicio_nuevo, actor_cuenta_id, ocurrido_en
FROM turno_evento WHERE turno_id = @turno ORDER BY id;

SELECT COUNT(*) AS turnos_en_ese_horario
FROM turno t JOIN turno u ON u.profesional_membership_id = t.profesional_membership_id AND u.inicio = t.inicio
WHERE t.id = @turno AND u.deleted_at IS NULL;
```

Esperado: `RESERVADO`, `inicio`/`fin` en **UTC** (hora local + 3); un evento `RESERVA`; **1** turno
vivo en ese horario.

---

### AGE-02 · Confirmar — `[E2E agenda-ciclo]`

**Pantalla:** `/agenda/turnos/<id>` → confirmar.

```sql
SELECT estado, confirmado_en, version FROM turno WHERE id = @turno;
SELECT tipo, estado_anterior, estado_nuevo FROM turno_evento WHERE turno_id = @turno ORDER BY id;
```

Esperado: `CONFIRMADO`; evento `CONFIRMACION`. No hay `audit_event` (confirmar tampoco notifica:
ver [90](90-conocido-y-aceptado.md)).

---

### AGE-03 · Reprogramar mueve el mismo turno — `[E2E agenda-ciclo]`

**Pantalla:** reprogramar a otro horario de mañana, con motivo.

```sql
SELECT id, inicio, fin, estado, reprogramado_en, version FROM turno WHERE id = @turno;
SELECT tipo, inicio_anterior, inicio_nuevo, motivo FROM turno_evento WHERE turno_id = @turno ORDER BY id;
```

Esperado: **el mismo `id`** con `inicio` nuevo (no se cancela y crea otro: la sesión cuelga del
`turno_id`); evento `REPROGRAMACION` con los dos horarios.

**Auditoría:** `TURNO_REPROGRAMADO` con `reason`.

---

### AGE-04 · No se reserva en el pasado

**Pantalla:** en la agenda de hoy, los slots que ya pasaron no se ofrecen. Por la API, forzá uno:

```powershell
Api POST "/api/v1/consultorios/<sede_a>/turnos/ofertas/<oferta>" $A @{ inicio = '<hoy a las 00:00 en UTC, ISO>'; personaId = <persona>; profesionalId = <membership_profesional>; idempotencyKey = (Nueva-Clave) }
```

**Esperado:** rechazo (defecto #1 del 07/10, corregido). Ningún turno nuevo:

```sql
SELECT id, inicio, estado FROM turno WHERE consultorio_id = @sede_a AND inicio < UTC_TIMESTAMP() AND created_at >= @desde;
```

Esperado: solo los turnos que reservaste cuando el horario todavía era futuro.

---

### AGE-05 · Marcar ausente no libera el lugar

Sobre un turno nuevo de hoy (reservalo con un horario cercano y esperá a que pase, o usá uno
reservado temprano).

**Pantalla:** `/agenda/turnos/<id>` → marcar ausente.

```sql
SET @turno_ausente := (SELECT MAX(id) FROM turno WHERE consultorio_id = @sede_a AND estado = 'AUSENTE');
SELECT id, estado, ausente_en, deleted_at FROM turno WHERE id = @turno_ausente;
SELECT tipo, estado_anterior, estado_nuevo FROM turno_evento WHERE turno_id = @turno_ausente ORDER BY id;
```

Esperado: `AUSENTE` con `deleted_at` **NULL** (la hora se consumió: cancelar libera, ausentarse no,
05.03); evento `AUSENCIA`. **Auditoría:** `TURNO_AUSENTE`.

---

### AGE-06 · Cancelar con motivo devuelve el lugar — `[E2E agenda-ciclo]`

**Pantalla:** cancelar otro turno con motivo. Volver a la agenda: el slot está libre.

```sql
SET @turno_cancelado := (SELECT MAX(id) FROM turno WHERE consultorio_id = @sede_a AND estado = 'CANCELADO');
SELECT id, estado, motivo_cancelacion, cancelado_en, cancelado_por_cuenta_id, deleted_at, deleted_key
FROM turno WHERE id = @turno_cancelado;
```

Esperado: `CANCELADO`, `deleted_at` lleno (libera el lugar), motivo y actor. **Auditoría:**
`TURNO_CANCELADO` (`RESERVADO → CANCELADO`, `reason` = motivo). Un turno con sesión iniciada no se
cancela ni se mueve: 409 `turno-con-atencion`.

---

### AGE-07 · Serie de turnos: alta con previsualización — `[E2E flujo-series]`

**Pantalla:** `/agenda/ofertas/<id>/reservar-serie` → lunes y miércoles a las 10:00 desde la semana
que viene, 6 turnos. Previsualizar → confirmar. Ver en `/agenda/series`.

```sql
SET @serie := (SELECT MAX(id) FROM turno_serie WHERE consultorio_id = @sede_a);
SELECT id, persona_id, frecuencia, dias_semana, hora_local, fecha_desde, fecha_hasta, cantidad, cantidad_generada, timezone, idempotency_key
FROM turno_serie WHERE id = @serie;
SELECT id, inicio, estado, deleted_at FROM turno WHERE serie_id = @serie ORDER BY inicio;
```

Esperado: `cantidad_generada = 6`, seis turnos `RESERVADO` con `serie_id`. La serie **no tiene
columna de estado**: `VIGENTE`/`CANCELADA`/`FINALIZADA` se calcula al leer (DP-20). Un feriado en
el medio hace fallar el alta entera (todo o nada, conocido).

**Auditoría:** `TURNO_SERIE_CREADA`.

---

### AGE-08 · Serie: reprogramar "este y los siguientes", y cancelar

**Pantalla:** `/agenda/series/<id>` → reprogramar desde el tercer turno a las 11:00 (la pantalla
pide confirmar **cuántos** turnos se afectan). Después cancelar desde el quinto en adelante
(`[E2E flujo-series]` cubre la cancelación).

```sql
SELECT t.id, t.inicio, t.estado, t.reprogramado_en, t.deleted_at
FROM turno t WHERE t.serie_id = @serie ORDER BY t.id;
SELECT t.id AS turno, e.tipo, e.inicio_anterior, e.inicio_nuevo, e.motivo
FROM turno_evento e JOIN turno t ON t.id = e.turno_id
WHERE t.serie_id = @serie AND e.tipo IN ('REPROGRAMACION', 'CANCELACION') ORDER BY e.id;
```

Esperado: los turnos 3–6 movidos a las 11 (mismos ids), los 5–6 cancelados. La bandeja muestra la
serie `CANCELADA` (su último turno está cancelado, DP-20). Si otra persona tocó la serie entre la
previsualización y la confirmación, el rechazo es 409 `conflict` con `afectados` y `confirmados`
—hay que volver a previsualizar—, **no** `concurrent-modification` (DP-21).

**Auditoría:** `TURNO_SERIE_REPROGRAMADA`, `TURNO_SERIE_CANCELADA`.

---

### AGE-09 · Recepción: llegada, validación, espera y llamado — `[E2E agenda-recepcion]`

**Pantalla:** administrativo → `/agenda/recepcion` → el turno de hoy de la persona con cobertura →
registrar llegada → validar con la cobertura de PER-07 → pasar a espera → llamar.

```sql
SET @recepcion := (SELECT id FROM recepcion WHERE turno_id = @turno);
SELECT id, estado, modalidad, cobertura_id, practica_id, convenio_id, observacion, llegada_en, validada_en,
       en_espera_desde, llamada_en, cerrada_en, version
FROM recepcion WHERE id = @recepcion;

SELECT tipo, estado_anterior, estado_nuevo, motivo, actor_cuenta_id, ocurrido_en
FROM recepcion_evento WHERE recepcion_id = @recepcion ORDER BY id;

SELECT estado FROM turno WHERE id = @turno;
```

Esperado: `LLAMADA`, `modalidad = COBERTURA`; eventos `LLEGADA` → `VALIDACION` → `ESPERA` →
`LLAMADO`, cada uno con actor y hora; **el turno no cambió de estado**. Si la elegibilidad
administrativa encuentra un faltante (orden, autorización, credencial), la recepción queda
`OBSERVADA` con la observación, **nunca** un error.

**Auditoría:** `RECEPCION_LLEGADA`, `RECEPCION_VALIDACION`, `RECEPCION_ESPERA`, `RECEPCION_LLAMADO`.

---

### AGE-10 · Atender como Particular — `[E2E agenda-recepcion]`

**Pantalla:** otro turno de hoy → llegada → "Atender como Particular" (el motivo es obligatorio).

```sql
SELECT r.id, r.estado, r.modalidad, r.motivo_particular, r.cobertura_id
FROM recepcion r JOIN turno t ON t.id = r.turno_id
WHERE t.consultorio_id = @sede_a AND r.modalidad = 'PARTICULAR' ORDER BY r.id DESC LIMIT 3;
```

Esperado: `modalidad = PARTICULAR` con motivo y sin cobertura. Particular **manda sobre el
convenio** (DP-17, RF-M08-007): la deuda de esa sesión será particular aunque la persona tenga obra
social. **Auditoría:** `RECEPCION_PARTICULAR` con `reason`.

---

### AGE-11 · Anular una recepción

**Pantalla:** sobre una recepción `LLEGO` o `EN_ESPERA`, anularla con motivo (la persona se fue).

```sql
SELECT r.id, r.estado, r.motivo_cierre, r.cerrada_en, r.cerrada_por_cuenta_id, r.turno_vigente_id
FROM recepcion r WHERE r.consultorio_id = @sede_a AND r.estado = 'ANULADA';
```

Esperado: `ANULADA` con `cerrada_en` (el CHECK `ck_recepcion_cierre` lo exige) y el turno intacto.
Cancelar el turno de una recepción abierta la cierra sola (`CIERRE_POR_CANCELACION`). **Auditoría:**
`RECEPCION_ANULACION`.

> No hay salida "se retiró sin ser atendido" ni pasar a Particular desde `EN_ESPERA`: conocido
> (E-4), ver [90](90-conocido-y-aceptado.md).

---

### AGE-12 · Prepago antes del check-in — `[E2E flujo-prepago]`

**Pantalla:** `/servicios/ofertas` → la oferta → "exige prepago". Reservar un turno de hoy para la
persona particular. En `/agenda/recepcion` el turno avisa prepago **pendiente**; registrar el
anticipo desde ahí (efectivo). El aviso pasa a registrado. La llegada **no** se bloquea: el prepago
avisa, no frena.

```sql
SELECT id, exige_prepago, version FROM oferta_servicio_consultorio WHERE id = @oferta;

SET @turno_prepago := (SELECT MAX(turno_id) FROM cobro WHERE consultorio_id = @sede_a AND turno_id IS NOT NULL);
SELECT id, persona_id, turno_id, turno_prepago_vigente, total, saldo_a_favor, comprobante_numero, cobrado_en, deleted_at
FROM cobro WHERE turno_id = @turno_prepago;
```

Esperado: un cobro con `turno_id` y `saldo_a_favor` = el anticipo (todavía no hay deuda). Un
segundo prepago para el mismo turno es 409 `prepago-ya-registrado`; sobre un turno cancelado,
`prepago-no-admitido` (los dos los prueba el E2E). Al cerrar la sesión de ese turno, el anticipo se
imputa **solo** a la deuda de esa sesión: se verifica en [ECO-03](07-obligaciones-cobros-caja.md).

**Auditoría:** `OFERTA_POLITICA_PREPAGO_CHANGED`. El cobro en sí no escribe `audit_event` (ver
[07](07-obligaciones-cobros-caja.md)).
