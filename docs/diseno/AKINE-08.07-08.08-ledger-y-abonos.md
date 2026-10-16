# AKINE-08.07 / 08.08 — Ledger de créditos y abonos

> Diseño de la implementación del 15/10/2026 (rama `akine-m29-abonos-y-ajustes`). Se apoya en
> `AKINE-08.06-pases.md` (PR #129) y no lo repite: dice qué se construyó encima y por qué.

Trazabilidad: RF-M29-004, RF-M29-005, RF-M29-006, RF-M29-007, RF-M29-008, RF-M18-010,
RN-M29-002..009, CA-M29-004-06, CA-M29-005-06, CA-M29-006-06, CA-M29-007-06, CA-M29-008-06.

## 1. Qué entra y qué no

| Entra | No entra (§8) |
|---|---|
| Abono: producto `ABONO` sobre oferta `POR_ABONO`, venta por periodo, obligación `VENTA_ABONO`, cobertura de la asistencia | Renovación automática, notificación de vencimiento (RF-M26-008), abono "ilimitado" |
| Ajuste manual del saldo con motivo y permiso reforzado | Prorrateo de un pase usado al anularlo |
| Anulación del pase sin uso, con su deuda | Reintegro monetario (ya existe en `billing`, RF-M19-010) |
| Vencimiento programado que deja `VENCIDO` y un movimiento `VENCIMIENTO` | Consulta paginada del ledger |
| Devolución del crédito al cancelar una clase | Devengo `POR_CLASE` (U-39, abierta) |
| `esquemaCobro` como lista cerrada en el contrato | Frontend |

## 2. Esquema — `V101`, sin tablas nuevas

- `pase_servicio.tipo` (`PACK`/`ABONO`, snapshot del producto) + `ck_pase_servicio_tipo`.
- `pase_servicio.anulado_en`, `anulado_por_cuenta_id`, `motivo_anulacion` +
  `ck_pase_servicio_anulacion_completa` (el trío existe si y sólo si `estado = ANULADO`).
- `ix_pase_vencimiento (estado, vigencia_hasta, organization_id)` para el job.

**No reescribe ningún CHECK** de V64: los cinco tipos y los cinco orígenes del ledger ya estaban.
La anulación descarta el saldo con un `AJUSTE` de origen `AJUSTE_MANUAL`; un origen propio
exigía `DROP CHECK` + `ADD`, la operación que V57 mostró que borra valores ajenos.
**`V102` queda sin usar.**

## 3. Las cinco piezas

### 3.1 Abono (RF-M29-007, RF-M29-008, RF-M18-010)

V64 ya pensó el abono como una fila de `pase_servicio` (`producto.tipo = ABONO`, `obligacion.origen
= VENTA_ABONO` con `pase_id`). Un abono es un pase cuya vigencia es un **periodo elegido al vender**
y cuyos créditos son **las asistencias que el periodo permite** (CA-M29-007-06). El producto
`ABONO` exige oferta `POR_ABONO`; `vigenciaDias` es la duración máxima del periodo.

- Venta: `periodoDesde` obligatorio, `periodoHasta` por defecto `desde + vigenciaDias − 1`. 400 si
  el fin antecede al inicio; 409 `producto-no-vendible` si el periodo ya terminó o dura más que el
  producto; 409 `abono-periodo-superpuesto` si la persona ya tiene ese abono vivo en un periodo
  que se pisa. **Un periodo, una obligación**: la superposición se evalúa con el producto
  bloqueado (`FOR UPDATE`), porque dos periodos superpuestos no comparten un valor de columna que
  un unique pueda ver; `uk_obligacion_venta` sigue siendo una obligación por fila.
- Cobertura: la asistencia a una oferta `POR_ABONO` toma un crédito del abono vigente el día de la
  clase, igual que un pack. Ninguna deuda nace por la asistencia (RN-M29-007, CA-M29-008-06).
- Renovar es vender el periodo siguiente. El abono se activa al vender: "según política de pago"
  no tiene política declarada (decisión a confirmar, §9).

### 3.2 Ajuste manual (RF-M29-005)

`POST /pases/{id}/ajustes {cantidad, motivo, version}`, permiso **`pase:adjust`** (reforzado:
`ORG_ADMIN` y `CONSULTORIO_ADMIN`, no el mostrador). Pase bloqueado, versión comparada, saldo
final en `[0, creditosIniciales]` (RN-M29-008 y no regalar lo no vendido), sólo `ACTIVO`/`AGOTADO`
dentro de su vigencia. `UPDATE` nativo que repite versión y rango en el `WHERE` y sube `version`.
Movimiento `AJUSTE` con actor y motivo, auditado `PASE_SALDO_AJUSTADO`.

**Idempotencia sin columna nueva:** `origen_id` del ajuste es la versión del pase sobre la que se
aplicó. Cada escritura mueve la versión, así que dos ajustes nunca comparten una; el reintento del
mismo pedido encuentra su movimiento y devuelve el estado actual.

### 3.3 Anulación

`POST /pases/{id}/anulacion {motivo, version}`, permiso `pase:adjust`. Sólo un pase **sin créditos
usados** (`SUM` de `CONSUMO`+`DEVOLUCION` = 0) y con una deuda **sin cobros**. En una transacción:
`billing.spi.DevengoDeVentas.anularDeudaDeVenta` anula la obligación con las reglas de siempre de
`billing` (no se borra: queda `ANULADA` con motivo, auditada) — si tiene cobros **no escribe** y lo
informa, y `activity` responde 409 `pase-con-cobros`; después el pase pasa a `ANULADO` y el saldo
remanente se descarta con un `AJUSTE`. Idempotente por estado.

**Por qué así y no "anular el pase y dejar la deuda":** en `billing` una deuda con cobros no se
anula (lo que corresponde es anular o reintegrar el cobro, RF-M19-010), y un pase anulado con la
deuda viva es un cargo por algo que no existe. Con consumos, anular pase y deuda regalaría las
clases tomadas; prorratear es una política que ningún RF define, así que queda el ajuste.

### 3.4 Vencimiento programado (RF-M29-006)

**El estado se persiste, y la regla "la efectiva se calcula al leer" no se rompe.** La lectura
sigue calculando (`Pase.vigente`, y el consumo nunca toma un pase fuera de fecha). Lo que la
lectura no puede dar es lo que RF-M29-006 pide literalmente: **que los créditos remanentes queden
explicados por un movimiento `VENCIMIENTO`** (CA-M29-006-06) y que el saldo mostrado coincida con
la reconstrucción por movimientos (RF-M29-009). Un saldo de 5 en un pase vencido es un ledger que
no explica adónde fueron esos 5.

`VencimientoDePasesWorker` (`@Scheduled`, cada hora, `akine.activity.pases.vencimiento.*`, no
expuesto por HTTP) llama a `VencimientoDePases`: candidatos `ACTIVO` con vigencia anterior a
UTC+1 día, y por cada uno, "hoy" **en la zona de su sede**, una transacción corta en
`READ_COMMITTED` (pase bloqueado, `UPDATE ... WHERE estado = 'ACTIVO' AND vigencia_hasta < hoy`,
movimiento con `origen_id` = versión). Dos instancias no vencen dos veces. Sin `REQUIRES_NEW`
anidado: el lote no es transaccional.

`devolverUno` ahora pasa `VENCIDO` a `ACTIVO`: una corrección tardía devuelve el crédito y el
próximo tick lo vence con su movimiento, así queda explicado.

### 3.5 Devolución por cancelación de clase (RF-M29-004, RF-M28-006 paso 7)

`ClaseService.cancelar` (sólo cuando hubo transición) llama a
`ConsumoDePaseService.devolverPorCancelacion`: por cada asistencia de la clase con neto −1, un
`DEVOLUCION` de origen `CANCELACION`, `origen_id` = clase, con la asistencia (su neto vuelve a 0).
Tres capas contra la doble devolución (CA-M29-004-06): cancelar es idempotente, sólo se devuelve
neto ≠ 0, y `uk_movimiento_origen (pase, CANCELACION, clase)`.

**La carrera que había que cerrar:** registrar asistencia no bloqueaba la clase. Una asistencia que
consumía mientras se cancelaba podía commitear después de la lectura de consumos de la
cancelación: crédito perdido sobre una clase cancelada. Ahora:

- La asistencia lee la clase con `FOR SHARE` **antes** de tocar la inscripción o un pase
  (`lockCompartidoByIdInScope`); el ingreso sin inscripción, que después escribe el cupo, la toma
  `FOR UPDATE` de entrada (dos S que piden X son un deadlock).
- La cancelación ya tomaba la clase `FOR UPDATE`; pasó a `READ_COMMITTED` para que la lectura de
  consumos vea lo que las asistencias commitearon mientras esperaba el lock.
- Orden de locks: `clase_programada → inscripcion_clase → pase_servicio`, el mismo en los dos
  caminos. Una asistencia que llega después de la cancelación ve `CANCELADA` y es 409.

## 4. `esquemaCobro` en el contrato

`CreateOferta.esquemaCobro` y `UpdateOferta.esquemaCobro` publican `enum: [POR_SESION, POR_PACK,
POR_ABONO]` (lo que el backend acepta); `Oferta.esquemaCobro` publica los cuatro, `POR_CLASE`
incluido, porque el esquema lo admite y no se quiere romper la respuesta el día que U-39 se
decida. Sigue siendo `type: string`; **el cliente TypeScript generado pasa de `string` a un enum**
y hay que regenerarlo (§7).

## 5. Permisos

| Código | ORG_ADMIN | CONSULTORIO_ADMIN | ADMINISTRATIVO | PROFESIONAL |
|---|---|---|---|---|
| `pase:adjust` (nuevo) | ORGANIZACION | CONSULTORIO | — | — |

Sin fila en la matriz §32; el reparto es el de `producto:manage`. Vender sigue siendo
`pase:manage`; ver, `pase:read`.

## 6. Design challenge

1. **Ownership.** Todas las columnas nuevas son de `pase_servicio` (`activity`). La obligación la
   sigue escribiendo `billing` (`DevengoDeVentasAdapter`): `activity` pide anular por
   `billing.spi`, nunca toca `obligacion`. La lectura de consumos de una clase hace join
   `movimiento_pase`–`asistencia_actividad`, las dos de `activity`.
2. **Ciclos.** Ninguna arista nueva entre módulos: `activity → billing.spi` ya existía (#129) y
   sólo crece el contrato del `spi`. Ningún módulo depende de `activity`. `ModuleArchitectureTest`
   en verde.
3. **Tenant.** No hay tabla nueva. Todo `UPDATE`/`SELECT` nuevo lleva `organization_id` en el
   `WHERE`, y los de comando además la sede. La única consulta sin tenant es la de candidatos del
   job, que corre sin contexto; cada pase se vence después con `organization_id`. El índice nuevo
   lleva el tenant al final por eso. Cross-tenant → 404 (`PaseNotAccessibleException`).
4. **Reglas maestras.** Crédito ≠ plata: ajustar, vencer y devolver por cancelación no tocan la
   obligación ni la caja (RN-M29-006). Anular el pase anula la **deuda** y nada más: el reintegro
   del cobro es de `billing`/caja (RF-M19-010). La cancelación de una clase devuelve créditos, no
   dinero.
5. **Baja lógica.** Nada se borra: el pase se anula con trío de auditoría, la obligación queda
   `ANULADA`, el ledger compensa (la devolución no borra el consumo, el vencimiento y la anulación
   son movimientos).
6. **Contrato.** 0.104.0 → **0.109.0**, minor. Aditivo salvo dos puntos declarados en §7.
7. **Ruta crítica.** Cimientos presentes: pase, ledger, asistencia, cancelación de clase,
   obligación y su anulación. F9 sigue abierta sin el gate del MVP (DU-12, decisión pendiente 8):
   esto se hizo porque el usuario abrió F9.
8. **El caso que rompe el diseño.** *Cancelar una clase mientras el mostrador toma lista.* Sin el
   `FOR SHARE`, la asistencia lee la clase `EN_CURSO` con lectura consistente, la cancelación
   bloquea la clase, lee los consumos (todavía no está el de la asistencia), devuelve y commitea; la
   asistencia commitea su consumo después: un crédito perdido sobre una clase cancelada, sin error.
   Con el `FOR SHARE` la asistencia o termina antes (y la cancelación, en `READ_COMMITTED`, la ve y
   la devuelve) o espera y encuentra la clase `CANCELADA` (409). Lo prueba
   `DevolucionYAjusteConcurrenteIT.cancelacion_contra_asistencias`.
   *Segundo caso: ajuste y consumo del mismo pase a la vez.* Los dos bloquean el pase; el que llega
   segundo ve la versión movida (ajuste → 409) o el pase `AGOTADO` (consumo → sin pase). El ledger
   cuadra en los dos órdenes (`ajuste_contra_consumo`).

## 7. Contrato 0.109.0 — para el frontend

- **Operaciones nuevas (2):** `ajustarSaldoDePase` (`POST /consultorios/{c}/pases/{p}/ajustes`,
  body `AjusteDeSaldoDePase`, responde `PaseDetalle`) y `anularPase`
  (`POST /consultorios/{c}/pases/{p}/anulacion`, body `AnulacionDePase`, responde
  `ResultadoDeAnulacionDePase`).
- **Campos nuevos:** `CrearProducto.tipo` (`PACK` por defecto | `ABONO`); `VenderPase.periodoDesde`
  y `periodoHasta`; `Pase.tipo`, `anuladoEn`, `anuladoPorCuentaId`, `motivoAnulacion`.
- **Problem types nuevos:** `abono-periodo-superpuesto`, `pase-no-ajustable` (con `motivo`),
  `pase-con-cobros` (con `obligacionId` y `cobrado`).
- **Cambios de forma, declarados:** `esquemaCobro` pasa a enum en los tres schemas de oferta (el
  tipo generado cambia de `string` a enum); y la cancelación de una clase devuelve créditos y la
  asistencia sobre una clase que se cancela en ese instante responde 409 en vez de registrarse.

## 8. Lo que queda fuera, con su dueño

- ~~**08.08:** renovación automática de abonos y aviso de vencimiento (RF-M26-008)~~ — **hechos
  el 16/10/2026**, §10.
- ~~**08.07:** consulta paginada del ledger~~ — **hecha el 16/10/2026**, §10.
- **08.09:** caja y reportes de pases y abonos.
- **U-39:** `POR_CLASE`.

## 9. Decisiones a confirmar

1. **Créditos del abono.** Un abono lleva un tope de asistencias por periodo (`creditos`, que el
   esquema exige `> 0`). Un abono "libre" hoy se modela con un tope alto. ¿Se quiere un abono sin
   tope real? Cambiaría `ck_producto_creditos_positivos` y `ck_pase_saldo_no_negativo`.
2. **"Activa el abono según política de pago" (RF-M29-007 paso 7).** Hoy se activa al vender,
   como el pack. ¿Debe esperar al cobro (prepago, ADR-0013)?
3. **Anular un pase usado.** Hoy es 409; el camino es ajustar el saldo. ¿Se quiere anulación con
   prorrateo de la deuda?
4. **Tope del ajuste.** El saldo final no puede superar los créditos comprados. ¿Hace falta una
   "bonificación" que lo supere? Cambiaría `ck_pase_saldo_no_negativo`.
5. **Pase vencido por la noche.** El job corre cada hora: una asistencia registrada después del
   vencimiento escrito no consume aunque la clase haya sido dentro de la vigencia (RF-M29-006: "no
   consumir créditos luego del vencimiento"). ¿Se quiere una ventana de gracia?

## 10. Lo que faltaba de 08.08 — renovación, avisos y ledger paginado (16/10/2026)

> Rama `akine-08.08-renovacion-y-avisos`, migración **`V103`**, contrato **0.116.0**. Trazabilidad:
> plan 08.08 ("renovación idempotente", "un período genera como máximo una obligación", "fallo de
> notificación no revierte renovación", "jobs no expuestos públicamente"), RF-M29-007, RF-M29-009,
> RF-M18-010, RF-M26-008 (CA-M26-008-06), RNF-M26-010, RNF-M29-002.

### 10.1 Qué dice la fuente, y qué no

**M29 no tiene un RF de renovación**: el RF-M29-007 vende un período y nada más. Lo que respalda la
renovación es el **plan de 08.08** —"renovación idempotente", "generar obligaciones recurrentes",
"un período genera como máximo una obligación", casos borde "job duplicado", "renovación tras
cancelación", "cambio de precio"— y el RF-M18-010 ("crear la obligación correspondiente a un
período"). Con eso se construyó lo mínimo que esas frases piden, y lo que ninguna dice se resolvió
del lado conservador:

- **Opt-in por abono.** Nada se renueva solo: hay que pedirlo al vender
  (`VenderPase.renovacionAutomatica`) o después (`PUT .../pases/{p}/renovacion`). Generar deuda todos
  los meses sin que nadie lo haya pedido es la "renovación tras cancelación" que el plan nombra como
  caso borde.
- **Se activa al vender, como #135 (U-40 b, abierta).** La renovación **no mira si el período
  anterior se pagó**. Decidir que un abono impago no se renueva sería resolver U-40 por la puerta de
  atrás; si U-40 se decide "al cobrarse", cambian la venta y la renovación juntas.
- **El precio es el del producto el día de la renovación** ("cambio de precio"): el período nuevo
  es una venta más con su snapshot; los vendidos no cambian.

### 10.2 Renovación

`RenovacionDeAbonosWorker` (`@Scheduled`, cada hora; `akine.activity.abonos.renovacion.*`, no
expuesto por HTTP) llama a `RenovacionDeAbonos.renovarLosQueTerminan`: candidatos con
`renovacion_automatica = 1` y período que termina dentro de `anticipacion-dias` (3 por defecto, para
que la deuda del período siguiente exista antes de que empiece), "hoy" **en la zona de la sede**, y
**una transacción corta en `READ_COMMITTED` por abono**, sin `REQUIRES_NEW` anidado. En cada una:

1. Lock `producto_servicio` → `pase_servicio`, el orden de la venta: el producto bloqueado serializa
   contra una venta manual del mismo producto, que es lo que hace valer el control de superposición.
2. Relectura con el lock (lectura actual): sigue con la renovación prendida, es abono, no está
   anulado y nadie lo renovó (`renovacionDe`).
3. Período siguiente: **un mes calendario completo se renueva por el mes siguiente** (noviembre por
   diciembre, no por "31 días") si el producto lo cubre; cualquier otro período dura `vigenciaDias`
   desde el día siguiente al fin, sin hueco ni superposición.
4. Las mismas validaciones que la venta manual: producto vendible hoy, período coherente (en
   particular, no ya terminado: un job caído más que un período entero no vende el pasado), persona
   activa, sin superposición. **Lo que no se puede renovar se apaga con su motivo**
   (`renovacion_omitida_motivo`, auditado `ABONO_RENOVACION_OMITIDA`) en vez de reintentarse para
   siempre.
5. La venta: fila nueva con `renueva_pase_id`, `COMPRA`, **una** obligación `VENTA_ABONO` por
   `billing.spi.DevengoDeVentas`, a nombre de quien autorizó la renovación
   (`renovacion_autorizada_por_cuenta_id`): el job no es una persona y la deuda tiene que tener a
   alguien que la haya pedido. El período nuevo **hereda** la renovación; en el viejo se apaga **en
   la misma transacción**. Auditado `ABONO_RENOVADO` con actor nulo (sistema) y el autorizante en los
   detalles. No cobra (DP-06).

**Idempotencia, en tres capas**: el lock y la relectura; la marca del período viejo apagada junto
con la venta del nuevo (una segunda pasada no lo encuentra); y `uk_pase_servicio_renovacion
(organization_id, renueva_pase_id)`: un período se renueva una sola vez. Lo prueban
`AbonoRenovacionYAvisosIT.renovacion_idempotente` (dos pasadas) y `.renovacion_concurrente` (tres
instancias a la vez sobre tres abonos: tres renovaciones, seis obligaciones).

Configurar (`configurarRenovacionDeAbono`) pide `pase:manage` —prenderla es autorizar ventas—, va
con el pase bloqueado y la versión leída, es idempotente por estado, y responde 409
`pase-no-ajustable` con `causa = ESTADO` (anulado) o la nueva `causa = YA_RENOVADO` (se configura
sobre el período nuevo). Apagarla no anula un período ya renovado: para eso, `anularPase`.

### 10.3 Avisos de vencimiento y saldo bajo (RF-M26-008)

`AvisosDePasesWorker` (cada hora, `akine.activity.pases.avisos.*`) llama a `AvisosDePases`:

- **Vencimiento próximo**: pases `ACTIVO` con saldo cuya vigencia termina dentro de
  `dias-antes-del-vencimiento` (3 por defecto), "hoy" de la sede.
- **Saldo reducido**: pases `ACTIVO` que ya usaron créditos y tienen entre 1 y `saldo-bajo`
  (1 por defecto; 0 lo apaga).

Un aviso por pase y por tipo: la marca (`aviso_vencimiento_en`, `aviso_saldo_bajo_en`, V103) la pone
un `UPDATE ... WHERE marca IS NULL` que sube `version`, y **el aviso se encola por el outbox en la
misma transacción** (`NotificationOutbox.enqueue`, `MANDATORY`): nunca desde el servicio, y si el
relay está caído reintenta el worker del outbox sin deshacer nada (RN-M26-001). La clave idempotente
del outbox (`pase-por-vencer:<id>`, `pase-saldo-bajo:<id>`) es el cinturón. Sin correo, el pase
queda marcado igual (resuelto) y no se encola. Un abono ya renovado no recibe "tu abono vence".
`VigenciaDeAvisoDePase` le dice al outbox que no mande el aviso de un pase que se anuló —o que ya
venció, en el de vencimiento— mientras esperaba en la cola. **Avisar no toca saldo, estado, vigencia
ni renovación** (CA-M26-008-06), y lo verifica el IT.

Tipos nuevos: `PASE_POR_VENCER`, `PASE_SALDO_BAJO`. Claves nuevas de la lista blanca:
`productoNombre`, `paseVencimiento`, `creditosDisponibles`, `renovacionAutomatica` — el nombre
comercial del producto, no un dato clínico ni un id.

"Avisos configurables" quedó **por despliegue** (propiedades): un umbral por organización o por
producto necesita una pantalla que ningún RF describe. "Respetar preferencias y canales
habilitados": hoy el único canal es el correo y el padrón no tiene preferencias de notificación; se
respeta lo que hay —sin correo no se avisa—.

### 10.4 Ledger paginado (RF-M29-009, RNF-M26-010)

`GET /consultorios/{c}/pases/{p}/movimientos?page&size` (`listarMovimientosDePase`), con el patrón
de paginado de la API (`page` base cero, `size` 20 por defecto y acotado a 100, respuesta
`content/page/size/totalElements/totalPages`). Del movimiento más viejo al más nuevo, el orden en
que se lee `saldoResultante`, con desempate por id y el índice `ix_movimiento_pase_orden` de V64.
`verPase` sigue trayendo el ledger entero: no se rompe.

### 10.5 Esquema — `V103`, sin tablas nuevas

`pase_servicio`: `renovacion_automatica`, `renovacion_autorizada_por_cuenta_id`,
`renovacion_omitida_motivo`, `renueva_pase_id` (+ FK y `uk_pase_servicio_renovacion`),
`aviso_vencimiento_en`, `aviso_saldo_bajo_en`; `ck_pase_servicio_renovacion_de_abono` (renovación
solo en abonos y con autorizante) e `ix_pase_renovacion (renovacion_automatica, vigencia_hasta,
organization_id)`. No reescribe ningún CHECK. Las dos marcas de aviso no se mapean en la entidad:
solo las escribe el `UPDATE` nativo.

### 10.6 Contrato 0.116.0 — para el frontend

- **Operaciones nuevas (2):** `listarMovimientosDePase` (`GET .../pases/{p}/movimientos`, responde
  `PaginaDeMovimientosDePase`) y `configurarRenovacionDeAbono` (`PUT .../pases/{p}/renovacion`, body
  `ConfiguracionDeRenovacionDeAbono {activa, version}`, responde `Pase`).
- **Campos nuevos:** `VenderPase.renovacionAutomatica`; `Pase.renovacionAutomatica`,
  `renuevaPaseId`, `renovacionOmitidaMotivo`.
- **Problem type:** ninguno nuevo; `pase-no-ajustable` gana `causa = YA_RENOVADO`.
- Todo aditivo.

### 10.7 Design challenge

1. **Ownership.** Todas las columnas nuevas son de `pase_servicio` (`activity`). La obligación la
   escribe `billing` por `DevengoDeVentas`, como en la venta; el outbox lo escribe `notification` por
   su `spi`. Nadie más toca las columnas nuevas.
2. **Ciclos.** Ninguna arista nueva: `activity → billing.spi`, `→ notification.spi`,
   `→ person.spi` y `→ organization.spi` ya existían (venta y avisos de clase).
   `VigenciaDeAvisoDePase` implementa `notification.spi.VigenciaDeNotificacion`: la flecha es
   `activity → notification.spi`, la misma que la de `scheduling`. ArchUnit en verde.
3. **Tenant.** No hay tabla nueva; el unique nuevo lleva `organization_id` adelante. Las tres
   consultas de candidatos son las únicas sin tenant (los jobs corren sin contexto, como el de
   vencimiento); cada pase se procesa después con `organization_id` en el `WHERE`. Cross-tenant del
   ledger paginado → 404.
4. **Reglas maestras.** Renovar es **vender**: obligación, no cobro ni caja (DP-06). Avisar no
   cambia créditos (CA-M26-008-06) ni deuda. Crédito ≠ plata sigue igual.
5. **Baja lógica.** Nada se borra: apagar la renovación deja el último autorizante, la omisión deja
   el motivo, cada período es una fila con su obligación y la cadena se lee por `renueva_pase_id`.
6. **Contrato.** 0.114.0 (08.09, #139) → **0.116.0** (0.115.0 es de 08.05), minor y aditivo.
7. **Ruta crítica.** Cimientos presentes: venta de abono, ledger, obligación `VENTA_ABONO`, outbox,
   job de vencimiento. F9 sigue abierta sin el gate del MVP (DU-12): se hizo porque el usuario la
   abrió.
8. **El caso que rompe el diseño.** *Dos instancias del job y el mostrador a la vez sobre el mismo
   abono.* Sin el lock del producto, el job A y el mostrador leen "no hay período superpuesto" y los
   dos venden diciembre: dos obligaciones por el mismo mes. Con el lock `producto → pase`, el que
   llega segundo ve la venta del primero (`READ_COMMITTED`, lectura después del lock): el mostrador
   recibe 409 `abono-periodo-superpuesto`, o el job omite con motivo "se superpone". El job B espera
   el lock del pase, relee y encuentra la renovación apagada. Y si todo eso fallara,
   `uk_pase_servicio_renovacion` no deja dos períodos que renueven al mismo.
   *Segundo caso: el job estuvo caído dos meses.* El período siguiente ya terminó: se omite con
   motivo en vez de vender noviembre en enero. La renovación no se pone al día sola; se vende a mano
   el período actual.

### 10.8 Decisiones a confirmar (se suman a §9)

6. **¿Renovar un abono impago?** Hoy sí (sigue a U-40 b). Si U-40 se decide "al cobrarse", la
   renovación cambia con la venta.
7. **El vendedor del período renovado** es quien prendió la renovación. Alternativa: una cuenta
   técnica de sistema, que hoy no existe (`vendido_por_cuenta_id` es `NOT NULL`).
8. **Umbrales de aviso por despliegue**, no por organización: ¿hace falta configurarlos por centro o
   por producto?
9. **El mes calendario** es la única regla de período "natural"; cualquier otro período se renueva
   por `vigenciaDias` y puede correrse (30 días desde el 5 de noviembre termina el 4 de diciembre).
