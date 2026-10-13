# 04 · Personas, coberturas, órdenes, autorizaciones e historial — P1

Módulos `person` (M07, M08, M17) y `contracting` (M15, M16). Es el circuito que decide **quién
paga**: un error acá no rompe la pantalla, rompe la deuda que nace al cerrar la sesión ([07](07-obligaciones-cobros-caja.md))
y lo que se presenta al financiador ([08](08-presentaciones-facturacion-pagos.md)).

`[E2E cobertura-autorizacion]` recorre persona → perfil → cobertura → orden → autorización y el
vencimiento por pantalla. **No mira la base, ni el historial de la autorización (DP-23).**
Desde `appKine-web` #58 el saldo, el ledger, la alerta y la reversión **tienen pantalla** (panel
"Saldo y consumos" en `/pacientes/<id>/autorizaciones`), y `[E2E flujo-autorizaciones]` recorre
consumo → anulación de la deuda → alerta → reversión por pantalla contra el backend real; tampoco
mira la base. `[E2E flujo-practicas-y-aranceles]` cubre la importación de aranceles (PER-06b).

Tablas: `persona`, `perfil_paciente`, `adjunto_administrativo`, `financiador`, `plan_cobertura`,
`convenio`, `convenio_arancel`, `cobertura_paciente`, `orden_medica`, `autorizacion`,
`autorizacion_evento`, `autorizacion_movimiento`, `autorizacion_alerta`.

> **Una persona no es un paciente** (03.01): `persona` y `perfil_paciente` son dos tablas y no hay
> columna `es_paciente`. La persona es de la **organización**, no de la sede.

---

### PER-01 · Alta de persona y detección de duplicado

**Pantalla:** admin-a → `/pacientes` → nueva persona: `Ana QA`, DNI `30111222` (inventado), fecha
de nacimiento, email `ana.qa@ejemplo.test`. Después intentá otra con el **mismo DNI** y otro
nombre.

**Esperado:** la primera entra. La segunda avisa posible duplicado y solo sigue si lo confirmás.

```sql
SET @persona := (SELECT MAX(id) FROM persona WHERE organization_id = @org_a AND numero_documento = '30111222');
SELECT id, organization_id, tipo_documento, numero_documento, documento_clave, apellido, nombre, apellido_clave,
       fecha_nacimiento, email, active, version
FROM persona WHERE organization_id = @org_a AND numero_documento = '30111222' ORDER BY id;
SELECT COUNT(*) AS perfiles FROM perfil_paciente WHERE persona_id = @persona;
```

Esperado: la persona sin perfil de paciente (`perfiles = 0`). Las columnas `*_clave` son la forma
normalizada (sin tildes ni mayúsculas) para buscar.

**Auditoría:** `PERSONA_CREATED`.

---

### PER-02 · Activar el perfil de paciente

**Pantalla:** ficha de la persona → activar perfil de paciente (el motivo es opcional).

```sql
SELECT id, persona_id, activado_en, activado_por, motivo, active FROM perfil_paciente WHERE persona_id = @persona;
```

**Auditoría:** `PERFIL_PACIENTE_ACTIVATED`. Sin perfil, reservar un turno es rechazo
`persona-sin-perfil-paciente` (`[E2E agenda-reserva]`).

---

### PER-03 · Editar la persona y el control de concurrencia

**Pantalla:** abrir la ficha en dos pestañas, cambiar el teléfono en una y guardar; cambiar el email
en la otra y guardar.

**Esperado:** la segunda rebota con 409 `concurrent-modification` ("otra persona modificó…
recargá"), DP-21. **No** pisa el teléfono.

```sql
SELECT telefono, email, version, updated_at FROM persona WHERE id = @persona;
```

**Auditoría:** un solo `PERSONA_UPDATED`.

---

### PER-04 · Documento de la persona: el tipo lo deciden los bytes

**Pantalla:** `/pacientes/<id>/documentos` → subir un PDF o PNG real como "Documento de identidad".
Después renombrá un `.txt` a `.pdf` y subilo.

**Esperado:** el real entra; el disfrazado es rechazo (el tipo sale de los bytes, no de la
extensión). Descargar el real debería bajar el archivo como adjunto.

```sql
SELECT id, categoria, titulo, nombre_archivo, content_type, tamano_bytes, checksum_sha256, storage_key, estado, active
FROM adjunto_administrativo WHERE persona_id = @persona ORDER BY id;
```

Esperado: una sola fila `DISPONIBLE` con `checksum_sha256` de 64 caracteres.

**Auditoría:** `ADJUNTO_UPLOADED` y, al descargar, `ADJUNTO_DOWNLOADED`.

> **Corregido en appKine-api #82 (`a09a596`):** la descarga del documento administrativo respondía
> **500** (`AdjuntoService.contenido` auditaba dentro de una transacción `readOnly`; lo destapó G-13
> y se reprodujo al validar esta guía). Desde ese merge tiene que responder **200** con los bytes
> subidos y dejar `ADJUNTO_DOWNLOADED`. Si vuelve a dar 500, es una regresión.

---

### PER-05 · Financiador y plan

**Pantalla:** admin-a → `/contratacion/financiadores` → `Obra Social QA` (tipo obra social). Planes →
`Plan QA` vigente desde el 1/1, requiere autorización.

```sql
SET @financiador := (SELECT MAX(id) FROM financiador WHERE organization_id = @org_a);
SET @plan := (SELECT MAX(id) FROM plan_cobertura WHERE financiador_id = @financiador);
SELECT id, codigo, nombre, tipo, active, version FROM financiador WHERE id = @financiador;
SELECT id, codigo, nombre, vigencia_desde, vigencia_hasta, requiere_autorizacion, requiere_credencial, copago, moneda
FROM plan_cobertura WHERE id = @plan;
```

**Auditoría:** `FINANCIADOR_CREATED`, `PLAN_COBERTURA_CREATED`. Una moneda que no es ISO 4217
(`PESOS`) es rechazo 400, no error de base.

---

### PER-06 · Convenio y arancel por oferta

**Pantalla:** `/contratacion/convenios` → convenio con el plan de PER-05, modalidad por prestación,
moneda ARS, **requiere autorización**. Aranceles → la práctica de CAT-02, **la oferta de CAT-04**,
total `8000`, financiador `6000`, coseguro `2000`.

```sql
SET @convenio := (SELECT MAX(id) FROM convenio WHERE consultorio_id = @sede_a);
SELECT id, financiador_id, plan_id, codigo, modalidad, moneda, vigencia_desde, vigencia_hasta,
       requiere_orden, requiere_autorizacion, requiere_credencial, active
FROM convenio WHERE id = @convenio;

SELECT id, practica_id, oferta_id, importe_total, importe_financiador, coseguro, moneda, vigencia_desde, vigencia_hasta, active
FROM convenio_arancel WHERE convenio_id = @convenio;
```

Esperado: `importe_total = importe_financiador + coseguro`. Un segundo arancel solapado para la
misma práctica y oferta es rechazo (lo hace cumplir `convenio_lock`, **ningún índice lo expresa**).
`/contratacion/arancel-efectivo` muestra el arancel que aplicaría hoy.

**Auditoría:** `CONVENIO_CREATED`, `ARANCEL_CREATED`.

---

### PER-06b · Importar aranceles con vista previa (B-7, web #28) — `[E2E flujo-practicas-y-aranceles]`

**Pantalla:** `/contratacion/convenios/<convenio>/aranceles` → "Importar aranceles" (ruta
`.../aranceles/importar`). Pegá (o subí como CSV) una planilla con dos filas:

```text
codigoPractica;ofertaId;importeTotal;importeFinanciador;coseguro;vigenciaDesde
<código de la práctica de CAT-02>;;9000;7000;2000;2026-01-01
<código de la práctica de CAT-02>;<id de la oferta de CAT-04>;8000;6000;2000;2026-01-01
```

**Esperado:** "Vista previa" no escribe nada y marca cada fila: la primera **ALTA** (arancel
general de la práctica, sin oferta); la segunda **RECHAZADA** con el mismo `problemType` que daría
el alta unitaria —`arancel-solapado`, "Se pisa con el arancel vigente <id>"—, porque se pisa con el
arancel por oferta de PER-06. "Confirmar" con una fila
rechazada es **todo o nada**: 409 `importacion-aranceles-rechazada` y **ninguna** fila escrita.
Quitá la segunda fila, volvé a previsualizar y confirmá: entra. Confirmar otra vez el mismo lote es
409 (sus filas chocan contra lo que ella misma creó), no duplica. Más de 500 filas o un lote vacío,
400.

Antes y después de confirmar:

```sql
SELECT id, practica_id, oferta_id, importe_total, importe_financiador, coseguro, vigencia_desde, vigencia_hasta,
       active, created_at
FROM convenio_arancel WHERE convenio_id = @convenio ORDER BY id;
```

Esperado: tras el 409, la misma cantidad de filas que antes; tras confirmar, **una** fila nueva con
`oferta_id` NULL y `importe_total = importe_financiador + coseguro`. **Auditoría:** un solo
`ARANCELES_IMPORTADOS` sobre el convenio (`entity_type = 'Convenio'`) con `details.cantidad`,
`details.practicas` y `details.rango` —no un `ARANCEL_CREATED` por fila—; el 409 no deja nada.

---

### PER-07 · Cobertura del paciente — `[E2E cobertura-autorizacion]`

**Pantalla:** `/pacientes/<id>/coberturas` → cobertura financiada con el plan de PER-05, número de
afiliado `AF-QA-001`, principal, credencial vigente hasta fin de año.

```sql
SET @cobertura := (SELECT MAX(id) FROM cobertura_paciente WHERE persona_id = @persona AND tipo = 'FINANCIADA');
SELECT id, tipo, financiador_id, financiador_nombre, plan_id, plan_nombre, numero_afiliado, credencial_vigencia_hasta,
       vigencia_desde, vigencia_hasta, principal, requeria_autorizacion, referencia_capturada_el, active
FROM cobertura_paciente WHERE persona_id = @persona ORDER BY id;
```

Esperado: la fila guarda una **copia** del financiador y el plan (`financiador_nombre`,
`plan_nombre`, `requeria_*`, `referencia_capturada_el`): renombrar el financiador después **no**
cambia esta fila (03.03/03.04, referencias históricas estables). Probalo: renombrá el financiador
y volvé a correr la consulta.

**Auditoría:** `COBERTURA_CREATED` (y `COBERTURA_PRINCIPAL_CHANGED` si cambiaste la principal).

---

### PER-08 · Cobertura aplicable a la oferta (B-3)

**Pantalla:** en la ficha 360 de la persona, la sección de coberturas y la cobertura aplicable a la
oferta de CAT-04.

**Esperado:** aplica la cobertura de PER-07 con el arancel de PER-06 (coseguro 2000). El número de
afiliado se ve **enmascarado** en el 360. Solo lectura: no escribe nada. Por la API:

```powershell
Api GET "/api/v1/personas/<persona>/cobertura-aplicable?ofertaId=<oferta>" $A
```

---

### PER-09 · Orden médica — `[E2E cobertura-autorizacion]`

**Pantalla:** `/pacientes/<id>/autorizaciones` → orden de `Dra. Sintética`, 10 sesiones, emitida hoy.

```sql
SET @orden := (SELECT MAX(id) FROM orden_medica WHERE persona_id = @persona);
SELECT id, cobertura_id, numero, profesional_emisor, fecha_emision, sesiones_prescriptas, vigencia_desde, vigencia_hasta, active
FROM orden_medica WHERE id = @orden;
```

**Auditoría:** `ORDEN_MEDICA_CREATED`.

---

### PER-10 · Autorización pendiente → observada → aprobada, con historial propio (DP-23)

**Pantalla:** en la misma pantalla, autorización `AUT-QA-001` para la práctica de CAT-02, la
cobertura y la orden, 10 sesiones, **pendiente**. Resolverla como **observada** (el motivo es
obligatorio) y después **aprobada**. Mirá el historial en la pantalla.

```sql
SET @autorizacion := (SELECT MAX(id) FROM autorizacion WHERE persona_id = @persona);
SELECT id, estado, motivo, numero, practica_id, cantidad_autorizada, cantidad_consumida, vigencia_desde, vigencia_hasta,
       convenio_id, requeria_orden, requeria_autorizacion, requeria_credencial, version
FROM autorizacion WHERE id = @autorizacion;

SELECT id, tipo, estado_anterior, estado_nuevo, cantidad, motivo, actor_cuenta_id, ocurrido_en
FROM autorizacion_evento WHERE autorizacion_id = @autorizacion ORDER BY id;
```

Esperado: `APROBADA`; en `autorizacion_evento`, **en orden**: `ALTA` (→ `PENDIENTE`),
`OBSERVACION` (`PENDIENTE → OBSERVADA`, con motivo), `APROBACION` (`OBSERVADA → APROBADA`). El
historial que muestra la pantalla sale de esta tabla, **no de `audit_event`** (DP-23): el mostrador
lo ve sin `auditoria:read`.

**Auditoría:** `AUTORIZACION_CREATED`, dos `AUTORIZACION_RESUELTA`. La auditoría y el historial son
**dos** datos con dos lectores: tienen que estar los dos.

---

### PER-11 · Consumo de la autorización al cerrar la sesión (DP-12)

Se corre **después** de [CLI-05](06-atencion-clinica.md) (cierre de una sesión con cobertura).

**Esperado:** el cierre consume **una** unidad por autorización involucrada, no una por práctica
realizada. El saldo de la pantalla baja en 1.

```sql
SELECT cantidad_autorizada, cantidad_consumida, version FROM autorizacion WHERE id = @autorizacion;

SELECT id, tipo, cantidad, tipo_origen, referencia_origen, movimiento_origen_id, ocurrio_en
FROM autorizacion_movimiento WHERE autorizacion_id = @autorizacion ORDER BY id;

SELECT tipo, cantidad, movimiento_id, estado_anterior, estado_nuevo
FROM autorizacion_evento WHERE autorizacion_id = @autorizacion AND tipo IN ('CONSUMO', 'REVERSION_DE_CONSUMO') ORDER BY id;
```

Esperado: un movimiento `CONSUMO` con `tipo_origen = SESION` y la sesión en `referencia_origen`, y
el mismo hecho en `autorizacion_evento`.

**El ledger tiene que cuadrar** (`[IT LedgerCoherenteIT]`, pero vale mirarlo con datos de pantalla):

```sql
SELECT a.id, a.cantidad_consumida,
       COALESCE(SUM(CASE m.tipo WHEN 'CONSUMO' THEN m.cantidad WHEN 'REVERSION' THEN -m.cantidad ELSE 0 END), 0) AS segun_ledger
FROM autorizacion a LEFT JOIN autorizacion_movimiento m ON m.autorizacion_id = a.id
WHERE a.organization_id = @org_a
GROUP BY a.id, a.cantidad_consumida
HAVING a.cantidad_consumida <> segun_ledger;
```

Esperado: **cero filas**. Una fila es hallazgo crítico (el saldo dice una cosa y el ledger otra).

**Auditoría:** `AUTORIZACION_CONSUMIDA` con `details.sesionId` y `movimientoId`.

---

### PER-12 · Anular la deuda deja una alerta; la reversión es manual (DP-13) — `[E2E flujo-autorizaciones]`

Se corre después de [ECO-06](07-obligaciones-cobros-caja.md) (anulación de la obligación de esa
sesión).

**Esperado:** anular la deuda **no** devuelve la unidad: genera una alerta "consumo a revisar" sobre
la autorización. Revertir es un acto aparte, con motivo.

**Pantalla (web #58) — `[E2E flujo-autorizaciones]`:** `/pacientes/<id>/autorizaciones` → en la
fila de la autorización, "Saldo y consumos". El panel muestra **Saldo** (otorgadas, consumidas,
disponibles y consumos a revisar), el **ledger** (consumo y reversión, con origen —sesión o
manual—, efecto y motivo; marca los consumos ya revertidos) y **Alertas**, cada una con su texto y
un solo botón: "Revertir el consumo #<movimiento>" con motivo obligatorio. **No hay botón de
descartar** la alerta (C-4 §9.1, criterio provisorio en [90](90-conocido-y-aceptado.md)). Revertir
sin motivo lo rechaza la pantalla y, por la API, 400 `reversion-sin-motivo`; revertir dos veces el
mismo consumo, 409 `movimiento-ya-revertido`.

Si el saldo de la columna y la suma del ledger no coinciden, el panel muestra una **advertencia**
(`coherente = false`) y **no** corrige nada: eso es un hallazgo crítico, igual que una fila en la
consulta del ledger de PER-11.

Por la API, lo mismo:

```powershell
Api GET "/api/v1/autorizaciones/<autorizacion>/alertas" $A
Api GET "/api/v1/autorizaciones/<autorizacion>/saldo" $A
Api POST "/api/v1/autorizaciones/<autorizacion>/reversiones" $A @{ movimientoId = <id del CONSUMO>; motivo = 'QA G-12: la sesion se anulo por error de carga' }
```

```sql
SELECT id, tipo, sesion_id, obligacion_id, motivo_origen, generada_en, resuelta_en, resolucion
FROM autorizacion_alerta WHERE autorizacion_id = @autorizacion ORDER BY id;

SELECT cantidad_consumida FROM autorizacion WHERE id = @autorizacion;

SELECT id, tipo, cantidad, tipo_origen, movimiento_origen_id, motivo
FROM autorizacion_movimiento WHERE autorizacion_id = @autorizacion ORDER BY id;
```

Esperado: tras la anulación, una alerta `CONSUMO_A_REVISAR` sin resolver y `cantidad_consumida`
**igual que antes**. Tras la reversión: un movimiento `REVERSION` que apunta al `CONSUMO`
(`movimiento_origen_id`), `cantidad_consumida` −1, la alerta resuelta (`resolucion = REVERTIDO`) y un evento
`REVERSION_DE_CONSUMO`. Volvé a correr la consulta del ledger de PER-11: cero filas.

**Auditoría:** `AUTORIZACION_CONSUMO_A_REVISAR`, `AUTORIZACION_CONSUMO_REVERTIDO`.

---

### PER-13 · Baja de la persona

Al final de la corrida, sobre una persona descartable (no la de los circuitos clínicos).

**Pantalla:** ficha → dar de baja con motivo.

```sql
SELECT p.id, p.active, p.deleted_at, p.deactivation_reason, pp.active AS perfil_activo, pp.deleted_at AS perfil_baja
FROM persona p LEFT JOIN perfil_paciente pp ON pp.persona_id = p.id
WHERE p.organization_id = @org_a AND p.active = 0;
```

Esperado: persona **y** perfil dados de baja lógica (la baja de la persona da de baja su perfil,
03.02); nada se borra. **Auditoría:** `PERSONA_DEACTIVATED`, `PERFIL_PACIENTE_DEACTIVATED`.

---

### PER-14 · Selector explicable: de qué autorización se descontaría (RF-M17-007, web #58)

**Pantalla:** `/pacientes/<persona>/autorizaciones` → sección del selector ("de qué autorización se
descontaría"): elegí una fecha y una práctica. Para que tenga sentido, cargá antes una **segunda**
autorización aprobada de la misma persona y práctica que venza **antes** que la de PER-10, y una
tercera de **otra** práctica.

**Esperado:** las candidatas del día, en el orden del backend; arriba "la que se usaría" (la de la
misma práctica que vence antes, con saldo), y para cada una de las otras el **por qué no** (otra
práctica, sin saldo, vencida o fuera de vigencia, no aprobada). La pantalla avisa que la cobertura
vigente y el caso **no** los evalúa esta consulta sino el cierre. Es una **consulta**: no consume ni
escribe nada (`GET /api/v1/personas/<id>/autorizaciones/elegibles?fecha=…`).

```sql
SELECT id, practica_id, numero, estado, cantidad_autorizada, cantidad_consumida,
       cantidad_autorizada - cantidad_consumida AS disponibles, vigencia_desde, vigencia_hasta, active
FROM autorizacion WHERE persona_id = @persona ORDER BY vigencia_hasta, id;
```

Esperado: la que la pantalla dice que "se usaría" es, entre las `APROBADA` vigentes en la fecha, de
la práctica elegida y con `disponibles > 0`, la de `vigencia_hasta` más próxima. Cerrá después
una sesión de esa práctica (CLI-05) y comprobá con la consulta de PER-11 que el `CONSUMO` cayó
**en esa misma** autorización: si el cierre elige otra, es hallazgo (el defecto de 06.04 que
imputaba a la autorización equivocada). **Auditoría:** nada (es una lectura).
