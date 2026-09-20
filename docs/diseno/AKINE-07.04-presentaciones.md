# AKINE-07.04 — Presentaciones y cuenta corriente de financiadores (M21)

Diseño de la etapa. **El design challenge (`AKINE-07.04-challenge.md`) manda sobre este documento
donde discrepen.**

Trazabilidad: RF-M21-001..008, RN-M21-001..004, RNF-M21-001..008.
Depende de AKINE-07.01 (obligaciones), AKINE-07.03 (caja) y de la capa de contratación de F3
(financiadores, planes, convenios, aranceles).

---

## 1. El punto de la etapa, en una frase

**RN-M21-001: prestado ≠ presentado ≠ facturado ≠ cobrado.** Son cuatro estados distintos de la
misma prestación y el sistema tiene que poder decir en cuál está cada una, por separado.

Y la consecuencia inmediata, RN-M21-002: **el pago del financiador genera caja solo cuando se
recibe.** Presentar no es cobrar. Facturar no es cobrar. Que una obra social acepte un lote de
200 sesiones no mueve un peso.

Esta etapa agrega la **cuarta cosa** a las tres que el repositorio ya separa:

| | Qué afirma | Quién la mueve | Etapa |
|---|---|---|---|
| **Obligación** | *se debe* | el cierre clínico | 07.01 |
| **Cobro** | *el paciente pagó* | el mostrador | 07.02 |
| **Caja** | *entró plata a este cajón* | el cobro y el operador | 07.03 |
| **Presentación** | *se le reclamó a un financiador* | el administrativo | **07.04** |

**La cuenta corriente de un financiador no es la caja.** La caja es un cajón en una sede con un
arqueo diario. La cuenta corriente de un financiador es una relación comercial que vive en meses:
lo presentado, lo debitado, lo facturado y lo pagado. Se tocan en **un solo punto** —cuando el
financiador paga, entra plata y eso sí es un movimiento de caja— y en ningún otro.

---

## 2. El cimiento que falta, dicho primero

**Hoy no existe una sola obligación con `responsable = FINANCIADOR`.**

`ObligacionDevengador` (07.01) devenga **una** obligación, a nombre del paciente, por el
`precio_base` de la Oferta. Lo dice su propio javadoc: DP-10 fijó cobertura PARTICULAR y dejó
afuera financiadores y convenios, así que `Responsable.FINANCIADOR` existe en el enum y en el
`CHECK` de V36 **sin productor**. Las columnas `snapshot_convenio_id` y `snapshot_arancel_id` de
V36 siguen en NULL, reservadas para "AKINE-03.05", que se implementó como el módulo `contracting`
pero **nunca volvió a tocar el devengado**.

Consecuencias, escritas para que nadie las descubra después:

1. **`GET /prestaciones-elegibles` va a devolver lista vacía en un despliegue real** hasta que
   alguien recablee el devengado. No es un bug de esta etapa: es su insumo, que no existe.
2. **La validación documental de RF-M21-003 no puede comprobar orden/autorización/credencial.**
   Esos tres requisitos viven en `ArancelCongelado` (`requeriaOrden`, `requeriaAutorizacion`,
   `requeriaCredencial`) y el consumidor que tenía que copiarlos a sus columnas es el devengado,
   que no lo hace. Lo que esta etapa **sí** valida está en §7.

**Lo que esta etapa hace al respecto, y nada más:** agrega a `obligacion` la columna
`financiador_id`, que es **estructuralmente imprescindible** —RF-M21-001 pide "listar obligaciones
financiador pendientes por período" y sin financiador en la fila no hay por dónde agrupar— con el
`CHECK` que la hace obligatoria exactamente cuando el responsable es el financiador.

**Lo que NO hace, y es una decisión del usuario:** recablear `ObligacionDevengador` para que parta
la deuda en coseguro del paciente + importe del financiador usando
`ArancelDirectory.congelar(...)`. Eso necesita, como mínimo, (a) la cobertura vigente del paciente
—que `person` no expone por `spi`—, (b) la `practicaId` de la oferta prestada —que
`SesionCerrada` no lleva— y (c) copiar el `ArancelCongelado` entero a `obligacion`. Es una etapa
propia, con migración y cambio de contrato, y adelantarla acá sería construir un módulo
consumidor antes que sus cimientos por la vía de fingir que el cimiento es un detalle.

---

## 3. Qué es una presentación

**Un lote administrativo dirigido a un financiador, por una sede y un período.** Es el papel (hoy
el archivo) que el centro le manda a la obra social diciendo "esto le presté a sus afiliados en
agosto, esto me debe".

`presentacion` es el agregado. Pertenece a `(organization_id, consultorio_id, financiador_id)` y
tiene `periodo_desde`/`periodo_hasta`.

| Candidato descartado | Por qué |
|---|---|
| Una presentación por **organización** | Los convenios son contextuales a la sede (RN-M16-001) y los aranceles también. Una presentación que cruza sedes mezcla convenios distintos en un total que después nadie puede desarmar. |
| Una presentación por **convenio** | El convenio cambia de versión en el medio de un período. El financiador no recibe un lote por versión de convenio: recibe uno por mes. |
| **Sin período**, sólo "las pendientes" | Todo el circuito administrativo de una obra social es mensual. Sin período no hay forma de responder "¿qué me falta presentar de agosto?", que es la única pregunta que el administrativo hace. |

El período es **un filtro declarado, no una restricción rígida**: dos presentaciones del mismo
período al mismo financiador son legítimas (una complementaria, un reenvío de rechazados). Lo que
**no** puede pasar dos veces es que la misma prestación esté viva en dos lotes, y eso lo resuelve
§5, no el período.

---

## 4. La máquina de estados, y por qué tiene cinco

```
                    ┌──────────► ANULADA        (sólo desde BORRADOR, con motivo)
                    │
BORRADOR ──confirmar──► PRESENTADA ──factura──► FACTURADA
                             │                      │
                             └──── conciliar ───────┴──► CONCILIADA
```

| Estado | Qué significa | Qué admite |
|---|---|---|
| `BORRADOR` | Se está armando. Nadie lo vio afuera. | agregar y quitar ítems, validar, anular, confirmar |
| `PRESENTADA` | **Se envió.** Tiene número y total congelado. | factura, débitos, pagos, conciliación |
| `FACTURADA` | Además tiene comprobante externo del centro. | débitos, pagos, conciliación |
| `CONCILIADA` | Cerrada: lo presentado quedó explicado por completo. | **nada** |
| `ANULADA` | El borrador se descartó con motivo. | nada |

**`FACTURADA` no es un paso obligatorio y por eso no está en serie con la conciliación.** Muchos
centros presentan y cobran sin emitir factura hasta el final del trimestre, y otros facturan al
presentar. Obligar el orden haría que la mitad de los usuarios tuvieran que mentirle al sistema
para poder registrar un pago que ya recibieron.

**`ANULADA` sólo sale de `BORRADOR`, y es deliberado.** Una presentación enviada no se anula: ya
existe del otro lado del mostrador. Si el financiador la rechaza entera, eso son **débitos sobre
todos sus ítems** (RF-M21-006), que es lo que efectivamente pasó y lo que deja rastro de por qué.
Hacer desaparecer el lote borraría la única evidencia de que se reclamó.

---

## 5. RN-M21-003, que es la regla que la base tiene que hacer cumplir

> *"Una prestación no debe duplicarse en presentaciones incompatibles."*

Una obligación puede aparecer en **varias** presentaciones a lo largo del tiempo —se presenta, la
debitan, se corrige y se vuelve a presentar— pero **en una sola a la vez**.

Lo hace cumplir el mismo mecanismo que `jornada_caja.abierta_marca` en V54 y `deleted_key` en
V30: una **columna generada** más un unique.

```sql
ocupa_marca TINYINT AS (IF(estado IN ('INCLUIDO','ACEPTADO'), 1, NULL)) STORED,
UNIQUE (organization_id, obligacion_id, ocupa_marca)
```

- `INCLUIDO` y `ACEPTADO` **ocupan**: la prestación está viva en ese lote.
- `DEBITADO` y `ANULADO` **liberan**: el financiador la rechazó, o el borrador se descartó. En los
  dos casos la prestación vuelve a estar disponible para otro lote, que es exactamente el caso
  borde "reapertura" y el "rechazo parcial" del plan.
- Varios `NULL` no colisionan en MySQL, así que una obligación puede acumular cuantos ítems
  liberados haga falta y conservar todo su historial.

**Al ser generada no puede desincronizarse del estado del ítem.** Un `if` en Java sobre esta regla
sería un `if` que alguien olvida en el segundo camino que agregue un ítem.

---

## 6. Los cuatro importes de una presentación, y la condición que los mueve

```
total_presentado   la suma de los ítems al confirmar. SE CONGELA.
total_debitado     lo que el financiador rechazó, ítem por ítem.
total_cobrado      lo que efectivamente pagó.
saldo              presentado − debitado − cobrado. NUNCA NEGATIVO.
```

Todos `DECIMAL(12,2)`. `CHECK (saldo >= 0)` y `CHECK (saldo = total_presentado - total_debitado -
total_cobrado)`.

Las dos escrituras que mueven el saldo son **UPDATE condicionales**, no locks. Es literalmente lo
que 07.02 hizo para imputar, 07.03 para el arqueo y 04.05 para consumir:

```sql
-- Pago del financiador (RF-M21-007)
UPDATE presentacion
   SET total_cobrado = total_cobrado + :importe,
       saldo         = saldo - :importe
 WHERE id = :id AND organization_id = :org
   AND estado IN ('PRESENTADA','FACTURADA')
   AND saldo >= :importe;

-- Débito de un ítem (RF-M21-006)
UPDATE presentacion
   SET total_debitado = total_debitado + :importe,
       saldo          = saldo - :importe
 WHERE id = :id AND organization_id = :org
   AND estado IN ('PRESENTADA','FACTURADA')
   AND saldo >= :importe;
```

**Cero filas es la respuesta, no un error técnico**, y se distingue releyendo la presentación
después del UPDATE —seguro, porque un UPDATE de cero filas no marca la transacción para rollback—.
Dos causas: el estado ya no admite, o no alcanza el saldo.

### El pago mayor y el pago menor, que son los dos casos que el plan nombra

- **Pago mayor al saldo: se rechaza.** 409 `presentacion-saldo-insuficiente`, con el saldo actual.
  Un financiador que paga de más no está pagando esta presentación: está pagando otra cosa, o hay
  un error de imputación, y absorberlo en silencio produce una cuenta corriente que cuadra por
  casualidad. La misma decisión que 07.02 tomó con la sobreimputación.
- **Pago menor: se acepta y el saldo queda.** Es el caso normal. El saldo residual es precisamente
  lo que RF-M21-008 pide resolver, y §8 dice cómo.

---

## 7. Lo que la validación documental puede comprobar hoy (RF-M21-003)

`GET /presentaciones/{id}/validacion` devuelve, ítem por ítem, los hallazgos que **impiden
confirmar**. Es una lectura: no cambia estado y no bloquea nada por sí misma; lo que bloquea es
`confirmar`, que la vuelve a correr del lado del servidor.

Comprueba, sobre cada obligación incluida:

| Hallazgo | Por qué |
|---|---|
| `OBLIGACION_ANULADA` | No se le reclama a nadie una deuda anulada. |
| `SIN_SALDO` | Ya se cobró por otra vía. Presentarla es reclamar dos veces. |
| `FINANCIADOR_DISTINTO` | La obligación es de otro financiador. |
| `SEDE_DISTINTA` | El convenio es contextual a la sede. |
| `FUERA_DEL_PERIODO` | El devengado cae fuera de `periodo_desde`..`periodo_hasta`. |
| `MONEDA_DISTINTA` | Un total que suma pesos con dólares no significa nada. |

**Lo que NO puede comprobar, y la causa es §2:** que haya orden, autorización y credencial cuando
el convenio las exigía. Esos tres booleanos viajan en `ArancelCongelado` y el devengado no los
copia a la obligación. Está anotado en `docs/tests-diferidos.md` con etapa destino.

**Un lote vacío no se confirma** (400 `presentacion-vacia`): un reclamo por cero pesos es ruido en
la cuenta corriente del financiador.

---

## 8. La conciliación, que es donde se salda la deuda (RF-M21-008)

**Conciliar exige `saldo = 0`.** Si no, 409 `presentacion-no-concilia` con el residual exacto.

No hay ajuste automático, no hay "cerrar con diferencia" y no hay write-off silencioso:

| Salida descartada | Por qué |
|---|---|
| Conciliar con residual y anotarlo | El residual dejaría de estar en ninguna parte como lo que es —plata que se reclamó y no se cobró—, y la cuenta corriente pasaría a cuadrar por definición. |
| Prorratear el residual entre los ítems | Inventa un débito que el financiador nunca informó, sobre prestaciones elegidas por una fórmula. |

Lo que el sistema hace es **nombrar el residual y negarse a fingir**: el administrativo tiene dos
salidas honestas y las dos ya existen —registrar los débitos que faltan (RF-M21-006), o registrar
el pago que falta (RF-M21-007)—. Es el mismo criterio con el que 07.03 se niega a ajustar la
diferencia de arqueo con un movimiento que la iguale.

**Y recién al conciliar se salda la obligación.** Cada ítem que sigue `INCLUIDO` pasa a `ACEPTADO`
y su obligación se descuenta por `importe_presentado`, con el `UPDATE` condicional que 07.02 ya
tiene:

```java
cobros.descontarSaldo(obligacionId, importePresentado);   // WHERE saldo >= :importe
cobros.actualizarEstadoPorSaldo(obligacionId);            // PAGADA cuando llega a cero
```

**Por qué al conciliar y no al pagar.** Un pago es un importe global: no viene con el detalle de
qué prestaciones cubre. Repartirlo entre los ítems exigiría una regla de imputación —¿por orden?,
¿a prorrata?— que el financiador no informó y que produciría obligaciones "pagadas" que él nunca
aceptó. La conciliación, en cambio, es el momento en que todo el lote quedó explicado ítem por
ítem, y ahí cada obligación aceptada sí se saldó, por su importe exacto.

**Un ítem `DEBITADO` no salda nada, y su obligación queda pendiente.** RN-M21-004 con todas las
letras: rechazar una prestación no elimina la sesión, y tampoco perdona la deuda. Qué se hace con
esa deuda —re-presentarla, pasarla al paciente— es una decisión del centro que esta etapa deja
abierta y no decide por él.

---

## 9. El pago, la caja y el borde que NO se cruza (RF-M21-007)

Registrar un pago del financiador:

1. mueve el saldo de la presentación con el `UPDATE` condicional de §6,
2. asienta el `financiador_pago`,
3. y **asienta un `MovimientoCaja` de `INGRESO` en la misma transacción.**

Los tres pasos, una transacción. Es el mismo razonamiento de `CajaDeCobro`: si el asiento se
dejara para después, nada obligaría a que alguien lo hiciera, nada verificaría el importe, y la
caja y la cuenta corriente divergirían **sin que falle nada**.

**El pago del financiador NO es un `Cobro`.** No se reusó el agregado de 07.02 y es deliberado: un
`Cobro` tiene `persona_id`, imputaciones contra obligaciones de un paciente y un **comprobante
correlativo fiscal** que el centro le emite a quien pagó. Nada de eso aplica a una transferencia
mensual de una obra social contra un lote. Forzarlo obligaría a inventar una persona, imputaciones
que no existen y un comprobante que no corresponde.

**El medio es casi siempre `TRANSFERENCIA`, y eso no es un problema:** 07.03 ya decidió que sólo
`EFECTIVO` afecta el arqueo, y que un movimiento no-efectivo se asienta con `jornada_caja_id` NULL
y `afecta_arqueo = 0`. Un pago de obra social por transferencia se registra **sin exigir caja
abierta**, aparece en la operatoria del día y no participa de ningún arqueo. Si el financiador
paga en efectivo —raro pero legítimo—, rige la regla de 07.03: hace falta jornada abierta, 409
`caja-no-abierta`.

`OrigenMovimiento` gana un cuarto valor, **`PAGO_FINANCIADOR`**, con
`referencia_origen = financiador_pago.id`. No se reusó `COBRO` porque el unique
`(organization_id, tipo, tipo_origen, referencia_origen, medio)` colisionaría entre un cobro y un
pago que casualmente compartan id, y porque un ledger que no distingue quién pagó no sirve para
explicar de dónde salió la plata.

### La factura externa (RF-M21-005) y el duplicado

`factura_numero` es el comprobante **del centro**, emitido fuera de AKINE. El sistema no lo genera
ni lo numera: lo **registra**. Y lo protege de lo único que puede protegerlo:

```sql
UNIQUE (organization_id, financiador_id, factura_numero)
```

sobre `presentacion`, que hace imposible el caso borde "factura externa duplicada": el mismo
número no puede quedar asociado a dos lotes del mismo financiador. Varios `NULL` no colisionan, así
que las presentaciones sin factura conviven sin problema.

---

## 10. Esquema — `V56`

Tres tablas nuevas, un numerador, y **dos `ALTER` mínimos** sobre tablas existentes.

### `ALTER TABLE obligacion`

`financiador_id BIGINT NULL`, FK a `financiador`, con
`CHECK (responsable <> 'FINANCIADOR' OR financiador_id IS NOT NULL)` y su recíproca
`CHECK (financiador_id IS NULL OR responsable = 'FINANCIADOR')`. Índice
`(organization_id, consultorio_id, financiador_id, estado, devengada_en)`, que es literalmente la
consulta de RF-M21-001. Ver §2: es la única columna que esta etapa agrega ahí, y no se agrega
"por si acaso" — sin ella la etapa no tiene por dónde agrupar.

### `ALTER TABLE movimiento_caja`

Se reemplaza `ck_movimiento_caja_tipo_origen` para admitir `PAGO_FINANCIADOR`. §9.

### `presentacion`

`organization_id`, `consultorio_id`, `financiador_id`, `numero INT`,
`periodo_desde DATE`, `periodo_hasta DATE`, `moneda CHAR(3)`, `estado VARCHAR(16)`,
`total_presentado`, `total_debitado`, `total_cobrado`, `saldo` (los cuatro `DECIMAL(12,2)`),
`factura_numero VARCHAR(40)`, `factura_fecha DATE`, `factura_registrada_en`,
`confirmada_en`, `confirmada_por_cuenta_id`, `conciliada_en`, `conciliada_por_cuenta_id`,
`anulada_en`, `anulada_por_cuenta_id`, `motivo_anulacion`,
`creada_en`, `creada_por_cuenta_id`, `created_at`, `updated_at`,
`deleted_at` + `deleted_key` (mismo mecanismo que V36: el centinela `'1970-01-01'` hace que
varios NULL no desprotejan lo vigente), `version`.

- `UNIQUE (organization_id, consultorio_id, financiador_id, numero)` — el correlativo del lote.
  `numero` es NULL mientras está en borrador y se asigna al confirmar; varios NULL no colisionan.
- `UNIQUE (organization_id, financiador_id, factura_numero)` — §9.
- `CHECK` de estado, `CHECK (saldo >= 0)`, `CHECK (total_* >= 0)`,
  `CHECK (saldo = total_presentado - total_debitado - total_cobrado)`,
  `CHECK (periodo_hasta >= periodo_desde)`,
  `CHECK` de confirmación completa (número, instante y actor: los tres o ninguno),
  `CHECK` de anulación completa (mismo criterio que `ck_obligacion_anulacion_completa` de V36),
  `CHECK (estado <> 'BORRADOR' OR numero IS NULL)`.
- Índices: `(organization_id, consultorio_id, estado, periodo_hasta)` para las bandejas,
  `(organization_id, financiador_id, estado)` para la cuenta corriente.
- **Baja lógica**: el cuarteto completo. Una presentación no se borra nunca.

### `presentacion_item`

`organization_id`, `presentacion_id`, `obligacion_id`, `estado VARCHAR(16)`,
`importe_presentado DECIMAL(12,2)`, `importe_debitado DECIMAL(12,2) NOT NULL DEFAULT 0`,
`motivo_debito VARCHAR(280)`, `debitado_en`, `debitado_por_cuenta_id`,
snapshots (`snapshot_persona_id`, `snapshot_sesion_id`, `snapshot_fecha_prestacion DATE`,
`snapshot_concepto VARCHAR(160)`), `incluido_en`, `created_at`, `updated_at`, `version`,
`ocupa_marca` (generada, §5).

- `UNIQUE (organization_id, obligacion_id, ocupa_marca)` — RN-M21-003.
- `UNIQUE (presentacion_id, obligacion_id)` — la misma obligación no entra dos veces al **mismo**
  lote, ni siquiera como ítem debitado y otro vivo. Sin esto, "quitar y volver a agregar" crearía
  filas gemelas que hacen que `total_presentado` cuente doble.
- `CHECK (importe_presentado > 0)`, `CHECK (importe_debitado >= 0 AND importe_debitado <=
  importe_presentado)`,
  `CHECK (estado <> 'DEBITADO' OR (motivo_debito IS NOT NULL AND debitado_en IS NOT NULL AND
  debitado_por_cuenta_id IS NOT NULL))`.
- `organization_id` en la hija **no es negociable** aunque se llegue por `presentacion_id`:
  ADR-0004 dice "sin excepción", y una consulta que se olvide del JOIN cruzaría tenants sin fallar.
  Misma convención que `cobro_medio` (V37) y `movimiento_caja` (V54).
- **Los snapshots se congelan al incluir.** `snapshot_concepto` y `snapshot_fecha_prestacion` se
  copian de la obligación: un lote impreso en agosto tiene que poder reimprimirse idéntico en
  diciembre, y leer la obligación viva no lo garantiza. Mismo criterio que `ArancelCongelado`.
- **Sin baja lógica y sin `DELETE` para los ítems confirmados.** Quitar un ítem sólo existe en
  `BORRADOR`, donde no hay información histórica relevante que preservar: el lote todavía no
  existió para nadie. Un ítem de una presentación confirmada **no se quita**: se debita.

### `financiador_pago` — append-only

`organization_id`, `consultorio_id`, `financiador_id`, `presentacion_id`,
`importe DECIMAL(12,2)`, `moneda CHAR(3)`, `medio VARCHAR(24)`, `fecha_pago DATE`,
`referencia VARCHAR(80)` (el número de transferencia o de recibo del financiador),
`movimiento_caja_id BIGINT`, `registrado_en`, `registrado_por_cuenta_id`,
`idempotency_key`, `request_hash`, `created_at`.

- **Sin `version`, sin `updated_at`, sin `deleted_at`, y el puerto no declara `update` ni
  `delete`.** Un historial de pagos que se puede editar no es un historial. Mismo diseño que
  `movimiento_caja` (V54), `turno_evento` (V38) y `autorizacion_movimiento` (V50). Un pago
  registrado mal se compensa revirtiendo su movimiento de caja y registrando el correcto.
- `UNIQUE (organization_id, idempotency_key)` con `request_hash`, igual que `cobro` en V37 y
  `movimiento_caja` en V54: un doble click no cobra dos veces la misma transferencia.
- `CHECK (importe > 0)`, `CHECK` de idempotencia completa.
- Índice `(organization_id, financiador_id, fecha_pago)` — la cuenta corriente.

### `presentacion_numerador`

`(organization_id, consultorio_id, financiador_id, ultimo_numero)`, unique sobre las tres primeras.

**`UPDATE ... SET ultimo_numero = ultimo_numero + 1`, nunca `MAX+1`**, y la fila se crea en una
transacción aparte con `INSERT ... ON DUPLICATE KEY UPDATE id = id`. Las dos reglas ya se pagaron
en este repositorio: el `MAX+1` deja una ventana entre leer y escribir, y la creación perezosa
**dentro** de la transacción que la bloquea produce deadlock —y el `try/catch` no salva, porque
atrapar una excepción de persistencia no des-marca la transacción—. Mismo patrón que
`ComprobanteNumeradorRepository` y `SesionNumeradorRepository`.

**El numerador es por financiador** y no por sede: cada obra social recibe su propia serie de
lotes, que es como se numeran en la práctica y lo que permite que el número que el centro le dice
por teléfono signifique algo.

---

## 11. API — contrato `0.38.0`

Trece operaciones bajo `/api/v1/consultorios/{consultorioId}`. Todas aditivas.

| Verbo | Ruta | RF |
|---|---|---|
| `GET` | `/prestaciones-elegibles` | RF-M21-001 |
| `POST` | `/presentaciones` | RF-M21-002 crear borrador |
| `GET` | `/presentaciones` | bandejas por estado (filtros `estado`, `financiadorId`, `desde`, `hasta`; paginado) |
| `GET` | `/presentaciones/{id}` | detalle con ítems |
| `POST` | `/presentaciones/{id}/items` | RF-M21-002 agregar |
| `DELETE` | `/presentaciones/{id}/items/{itemId}` | RF-M21-002 quitar (sólo borrador) |
| `GET` | `/presentaciones/{id}/validacion` | RF-M21-003 |
| `POST` | `/presentaciones/{id}/confirmacion` | RF-M21-004 |
| `POST` | `/presentaciones/{id}/factura` | RF-M21-005 |
| `POST` | `/presentaciones/{id}/items/{itemId}/debito` | RF-M21-006 |
| `POST` | `/presentaciones/{id}/pagos` | RF-M21-007 |
| `POST` | `/presentaciones/{id}/conciliacion` | RF-M21-008 |
| `POST` | `/presentaciones/{id}/anulacion` | descartar borrador |

> **`DELETE .../items/{itemId}` responde 204 sin cuerpo, así que declara `application/json` en sus
> `produces` además de `application/problem+json`.** Sin eso el cliente generado manda
> `Accept: application/problem+json` y el `produces` de clase lo corta con **406 antes de entrar al
> método**. Rompió activación de cuenta y recuperación de contraseña, y ningún test lo agarró.

**Tipos de problema nuevos** en `platform.spi.problem.ProblemType`, mapeados en
**`BillingProblemHandler`** —el advice del propio módulo, nunca en `GlobalExceptionHandler`—:

| Tipo | Status | Cuándo |
|---|---|---|
| `presentacion-no-editable` | 409 | ya no es borrador |
| `presentacion-estado-invalido` | 409 | la transición no sale de este estado |
| `presentacion-vacia` | 400 | confirmar un lote sin ítems |
| `presentacion-con-hallazgos` | 409 | confirmar con ítems inválidos; lleva la lista |
| `obligacion-ya-presentada` | 409 | RN-M21-003; lleva la presentación que la tiene |
| `obligacion-no-presentable` | 409 | anulada, sin saldo, de otro financiador o de otra sede |
| `presentacion-saldo-insuficiente` | 409 | pago o débito mayor que el saldo |
| `presentacion-no-concilia` | 409 | conciliar con residual; lleva el residual |
| `item-no-debitable` | 409 | el ítem ya fue debitado o anulado |
| `factura-duplicada` | 409 | el número ya está en otro lote del mismo financiador |

Se reusan `NOT_FOUND` e `IDEMPOTENCY_KEY_CONFLICT`.

**Nada de `float` en la API**, ni en DTO de entrada ni de salida: `BigDecimal` en todos los
importes, como en 07.01, 07.02 y 07.03.

**PHI mínima en las listas.** El ítem expone `snapshot_concepto`, la fecha de prestación y el
`personaId` — **no el nombre del paciente ni su documento**, que el lote real sí lleva pero que el
listado no necesita. RNF-M21-001 y §32.

---

## 12. Permisos

**`cobro:register`, alcance `CONSULTORIO`.** No se crea un permiso nuevo.

El catálogo de la matriz §5 es cerrado y no tiene "Presentar a financiador". `cobro:register` ya
está declarado como *"ver y administrar deuda y cobros"* y su asignación base —`ORG_ADMIN`
(`ORGANIZACION`), `CONSULTORIO_ADMIN` y `ADMINISTRATIVO` (`CONSULTORIO`)— coincide **exactamente**
con los actores que M21 §2 declara: administrativo y administrador. `PROFESIONAL` no lo tiene, que
es lo correcto: presentar a una obra social no es un acto clínico.

Inventar `presentacion:manage` habría exigido una enmienda a la matriz —como la que 03.03 tuvo que
escribir— para repartir exactamente los mismos roles con exactamente el mismo alcance. Un permiso
que nunca discrimina a nadie no es un control: es una línea más que mantener.

**El pago NO exige `caja:operate`.** Misma decisión y mismo motivo que el cobro en 07.03: el
movimiento que genera es una consecuencia, no una operación de caja, y exigirlo haría que poder
registrar un pago dependiera del medio con que el financiador transfirió.

**Auditoría dentro de la transacción de negocio**, con `AuditTrail`:
`PRESENTACION_CREADA`, `PRESENTACION_CONFIRMADA`, `PRESENTACION_FACTURADA`,
`PRESENTACION_ITEM_DEBITADO`, `PRESENTACION_PAGO_REGISTRADO`, `PRESENTACION_CONCILIADA`,
`PRESENTACION_ANULADA`. Agregar y quitar ítems de un borrador **no** se audita: el borrador todavía
no es un hecho, y auditar cada clic del armado enterraría los siete eventos que sí importan.

---

## 13. Módulos y ciclos

`presentacion`, `presentacion_item`, `financiador_pago` y `presentacion_numerador` son de
**`billing`**, que ya es propietario de `obligacion`, `cobro`, `jornada_caja` y `movimiento_caja`.
Ningún otro módulo las lee ni las escribe.

**Arista nueva: `billing → contracting.spi`**, para `CoberturaCatalogoDirectory.findFinanciador` —
validar que el financiador existe, es del tenant y está operable, y congelar su nombre en la
respuesta—.

`contracting` depende hoy de `organization.spi`, `platform.spi` y `resource.spi`, y ninguno de los
tres depende de `billing`. **Pero eso está razonado, no verificado, y el precedente de 04.05 es
exactamente ese error**: el challenge dio por cerrada de memoria una arista que sí cerraba ciclo, y
lo destapó poner una clase sonda y dejar que ArchUnit hablara. `SlicesRuleDefinition` busca ciclos
de cualquier longitud; la cabeza encuentra los de dos. **Se comprueba con `ModuleArchitectureTest`
antes de comprometer el import.**

---

## 14. Lo que esta etapa NO hace

- **No recablea el devengado** (§2). Es la decisión pendiente del usuario y sin ella la bandeja de
  elegibles queda vacía.
- **No valida orden, autorización ni credencial** (§7). Mismo origen.
- **No reparte el pago entre ítems.** §8 explica por qué.
- **No re-factura al paciente una prestación debitada.** Convertir una deuda de financiador en
  deuda de paciente es un cambio de responsable sobre una obligación devengada, con su propio
  circuito y su propia auditoría. Queda nombrado y afuera.
- **No exporta el lote.** "Listados exportables" del plan es un formato por financiador —cada obra
  social pide el suyo— y no hay ninguno definido. Es 07.06 o una etapa propia.
- **No concilia tarjetas ni modela cuentas bancarias.** 07.05.
- **No hay frontend.** El cliente TypeScript se genera desde el contrato y el contrato no se puede
  regenerar sin Docker.
- **No hay tests de integración.** Docker no arranca. Lo que queda sin verificar va a
  `docs/tests-diferidos.md` con etapa destino.

---

## 15. Bloques de implementación

1. **`V56`** — los dos `ALTER`, las tres tablas y el numerador, con cabecera al nivel de
   `V36`/`V37`/`V54`.
2. **Dominio** — `Presentacion`, `PresentacionItem`, `FinanciadorPago`, `PresentacionNumerador`,
   `EstadoPresentacion`, `EstadoItemPresentacion`, `HallazgoDeValidacion`, excepciones, puertos.
3. **Persistencia** — repositorios con los `UPDATE` condicionales y el numerador.
4. **Aplicación** — `PresentacionService` (armado, validación, confirmación, factura, débito,
   conciliación, anulación), `FinanciadorPagoService` (pago + caja), `PresentacionAcceso`.
5. **API** — `PresentacionController`, DTOs, tipos de problema y handlers.
6. **Contrato** — `0.38.0` en `pom.xml`, `application.yml` y el `info.version` del YAML. **Los
   `paths` no se tocan: sin Docker no se regeneran.**
7. **Tests unitarios** — un solo archivo, los casos que la etapa exige y nada más.
