# AKINE F-3 — Anticipos, imputación posterior, anulación y reintegro de cobros (M19)

> Paquete **F-3** de `docs/fases/01-trabajo-en-paralelo.md` (grupo G6, carril F). Cierra el ítem
> "07.02: anticipos, imputación posterior, anulación y reintegro" de `docs/fases/F7-economia-del-mvp.md`.
> Migración reservada: **`V69`**. Contrato: **0.54.0**.

## 1. Qué se cubre y de dónde sale

| Requisito | Qué dice | Cómo se cubre |
|---|---|---|
| Plan §"Etapa AKINE-07.02" | "Recibir dinero como cobro **o anticipo**", "anticipo no imputado, imputación posterior", "anulación/reintegro compensatorio" | Todo este documento |
| DP-06 / ADR-0013 | El dinero recibido antes de que exista la obligación es **anticipo/saldo a favor**, con movimiento real de Caja; después se imputa a una obligación compatible; las devoluciones son movimientos nuevos, el original nunca se borra | §3, §4, §6 |
| RF-M19-001..004 | Iniciar, medios, imputar, confirmar | El registro existente acepta ahora un `anticipo` explícito (§3) |
| RF-M19-003 | Distribuir el importe entre deudas | Imputación posterior del saldo a favor (§4) |
| RF-M19-007 | Anular cobro: revertir imputaciones y caja con trazabilidad | §5 |
| RN-M19-001 | Suma de medios = total | Sin cambios |
| RN-M19-003 | Un cobro confirmado no se edita silenciosamente | La imputación posterior y el reintegro **no editan** lo que ya se registró: agregan filas y mueven el saldo a favor con auditoría |
| RN-M19-004 | La anulación revierte efectos dependientes de forma consistente | §5, en una transacción |
| RF-M19-010 (paso 6) | "Registra reversión o saldo a favor según modelo" | Reintegro monetario del saldo a favor (§6) |
| Plan, Reglas | "La imputación posterior no vuelve a mover Caja"; "anulación o reintegro compensa imputaciones y movimientos sin borrar históricos" | §4, §5, §6 |

### Lo que queda AFUERA, y por qué

- **Política de prepago por consultorio/oferta (ADR-0013).** Es configuración de `organization` y de
  M27, y se evalúa en recepción: es el paquete **E-6**, que depende de este. Acá solo existe el
  primitivo (cobrar sin deuda).
- **RF-M19-010 completo** —reintegro "asociado a cancelación" de clases, packs y abonos, con
  `MovimientoPase`—. Es F9, congelada hasta el gate del MVP (DP-07). Se cubre solo el reintegro
  monetario de un saldo a favor, que es lo que el plan pide para 07.02.
- **Anular un reintegro**, y **deshacer una imputación sin anular el cobro.** Ningún RF los pide. Si
  el reintegro fue un error, el camino es un ingreso manual de caja (RF-M20-002).
- **Imputar el saldo a favor a una obligación de otra sede.** El registro de cobros ya exige que la
  deuda esté en la sede del cobro; se conserva. Decisión a revisar (§10).
- **Permiso propio de anulación** (`cobro:annul`). El plan pide "permiso reforzado"; la matriz §32 no
  tiene fila para anular cobros. Se resolvió **sin tocar `organization`**: anular y reintegrar exigen
  `cobro:register` **y** `caja:operate` (§7). Decisión a revisar (§10).

## 2. Modelo

```
                 ┌─────────────── cobro ──────────────────┐
  medios (V37) ──┤ total = Σ imputaciones + saldo_a_favor  │── movimiento_caja INGRESO (COBRO)
                 │         + Σ reintegros                   │
                 └──┬───────────────┬──────────────────────┘
                    │               │
        cobro_imputacion      cobro_reintegro ── movimiento_caja EGRESO (REINTEGRO)
        (al cobrar o después)  (devolución del saldo a favor)
```

- **Anticipo = cobro con `saldo_a_favor > 0`.** No es una entidad aparte ni una obligación: es plata
  recibida que todavía no se aplicó. El cobro la recibe una sola vez y la caja la asienta una sola
  vez, al cobrar. Un cobro puede ser anticipo puro (sin imputaciones) o con sobrante (imputa parte y
  el resto queda a favor).
- **El anticipo se declara.** El pedido trae `anticipo` explícito y la invariante pasa a ser
  `Σ imputaciones + anticipo = total`. **No** se relajó a "lo que no se imputa queda a favor": eso
  convertiría un error de tipeo (un cero de menos en una imputación) en un saldo a favor silencioso,
  que es justo lo que `cobro-no-cuadra` existe para atajar.
- **El saldo a favor se materializa** en `cobro.saldo_a_favor`, por la misma razón que el saldo de
  la obligación (V36 §4): imputarlo tiene que ser una resta protegida por un lock y un `CHECK`, no
  una suma de filas dentro del lock. La invariante de arriba es de la aplicación (MySQL no admite
  subconsultas en un `CHECK`); el `CHECK` sostiene el rango `0 ≤ saldo_a_favor ≤ total`.
- **Imputación posterior** = una fila nueva en `cobro_imputacion`, con `imputada_en` e
  `imputada_por_cuenta_id`, que descuenta `saldo_a_favor` del cobro y `saldo` de la obligación.
  **No mueve caja**: la plata ya entró cuando se cobró.
- **Anulación** = el cobro queda con `deleted_at` (que V37 reservó para esto), actor y motivo. Cada
  imputación devuelve su importe al saldo de su obligación y cada movimiento de caja del cobro se
  **revierte** con `MovimientoCajaService.revertir`. Nada se borra.
- **Reintegro** = una fila en `cobro_reintegro` que descuenta `saldo_a_favor` y asienta un
  movimiento `EGRESO` con `tipo_origen = 'REINTEGRO'`. Un reintegro **no es una reversión**: es
  parcial, puede ir por otro medio que el cobro y puede ocurrir varias veces.

## 3. Registro de cobro con anticipo (endpoint existente, cambio aditivo)

`POST /api/v1/consultorios/{consultorioId}/cobros` acepta dos campos nuevos y opcionales:

| Campo | Regla |
|---|---|
| `anticipo` | `≥ 0`, default 0. `Σ imputaciones + anticipo = total` o 400 `cobro-no-cuadra` |
| `moneda` | Obligatoria si no hay imputaciones (no hay deuda de la que tomarla): sin ella, 400 `validation-error`. Si hay imputaciones y viene, tiene que coincidir con la de las deudas |

`imputaciones` deja de ser obligatoria. Sin imputaciones, la persona se valida contra el padrón del
tenant por `person.spi.PacienteDirectory` (otro tenant → 404): con imputaciones ya la validaban sus
deudas. La huella de idempotencia suma `anticipo` y `moneda` **solo cuando vienen**, así los
reintentos de cobros viejos siguen dando la misma huella.

## 4. Imputación posterior

`POST /api/v1/consultorios/{consultorioId}/cobros/{cobroId}/imputaciones` — `{obligacionId, importe, idempotencyKey?}` → 200 `Cobro`.

Una deuda por pedido, así la clave de idempotencia es de la fila (`uk_cobro_imputacion_idempotencia`).
Orden: contexto → sede → `cobro:register` → **lock de fila del cobro** (`SELECT … FOR UPDATE`, en
`READ_COMMITTED`) → idempotencia (después del lock, para que el perdedor de una carrera vea la fila
del ganador) → cobro vigente → deuda de la sede, de la persona del cobro, cobrable y en la misma
moneda → `cobro.imputar` (saldo a favor suficiente, deuda no imputada antes por este cobro) →
`UPDATE` condicional del saldo de la obligación → auditoría `COBRO_SALDO_IMPUTADO`.

`uk_cobro_imputacion (cobro_id, obligacion_id)` de V37 **se conserva**: imputar dos veces el mismo
cobro a la misma deuda es 409 `obligacion-no-cobrable`. Se imputa todo lo que corresponde de una vez.

## 5. Anulación

`POST /api/v1/consultorios/{consultorioId}/cobros/{cobroId}/anulacion` — `{motivo}` → 200 `Cobro`.

`cobro:register` + `caja:operate` → lock del cobro → vigente (si no, 409 `cobro-anulado`) → sin
reintegros (si no, 409 `cobro-con-reintegros`: el saldo ya devuelto saldría dos veces del cajón) →
cada imputación devuelve su importe a la obligación con un `UPDATE` condicional que **recalcula el
estado** (`PENDIENTE` si vuelve al importe original, `PARCIAL` si no) → cada movimiento `INGRESO` de
origen `COBRO` se revierte con `MovimientoCajaService.revertir`, que ya sabe que la compensación cae
en la jornada abierta **hoy** y que el efectivo la exige (sin caja abierta: 409 `caja-no-abierta`;
sin plata en el cajón: 409 `caja-saldo-insuficiente`) → `cobro.anular` → auditoría `COBRO_ANULADO`.

El comprobante **no se libera**: el número queda usado por el cobro anulado, y la reimpresión
(`GET …/cobros/{id}`) lo devuelve con `estado = ANULADO`. Una numeración fiscal con huecos es peor
que un comprobante anulado.

## 6. Reintegro del saldo a favor

`POST /api/v1/consultorios/{consultorioId}/cobros/{cobroId}/reintegros` — `{importe, medio, motivo, referencia?, idempotencyKey?}` → 201 `ReintegroDeCobro`.

`cobro:register` + `caja:operate` → lock del cobro → idempotencia → vigente → `cobro.reintegrar`
(si el saldo a favor no alcanza, 409 `saldo-a-favor-insuficiente`) → fila en `cobro_reintegro` →
movimiento `EGRESO`/`REINTEGRO` por `CajaDeCobro`, con las reglas de siempre: solo el efectivo
mueve el arqueo, el efectivo exige jornada abierta y el cajón no queda negativo → auditoría
`COBRO_REINTEGRADO`.

## 7. Tablas, columnas y dueño

Todas de **`billing`**, todas con `organization_id`.

| Objeto | Cambio en `V69` |
|---|---|
| `cobro` | `saldo_a_favor DECIMAL(12,2) NOT NULL DEFAULT 0`, `anulado_por_cuenta_id`, `motivo_anulacion`. `ck_cobro_saldo_a_favor` (rango), `ck_cobro_anulacion_completa` (`deleted_at`, actor y motivo: los tres o ninguno) |
| `cobro_imputacion` | `imputada_en`, `imputada_por_cuenta_id` (las existentes se rellenan con los del cobro), `idempotency_key`, `request_hash`; `uk_cobro_imputacion_idempotencia (organization_id, idempotency_key)` |
| `cobro_reintegro` | Tabla nueva, append-only. `uk_cobro_reintegro_idempotencia (organization_id, idempotency_key)`, índice `(organization_id, cobro_id)` |
| `movimiento_caja` | `ck_movimiento_caja_tipo_origen` suma `REINTEGRO` (mismo patrón que V56 y V57) |

Problem types nuevos: `cobro-anulado` (409), `cobro-con-reintegros` (409),
`saldo-a-favor-insuficiente` (409, con `disponible` e `importeIntentado`). Se reusan
`cobro-no-cuadra`, `saldo-insuficiente`, `obligacion-no-cobrable`, `idempotency-key-conflict`,
`caja-no-abierta`, `caja-saldo-insuficiente`, `validation-error` y `not-found`. Todos los mapea
`BillingProblemHandler`.

## 8. Reporte

`sumarCobradoEnElReporte` ya excluía `deleted_at`, así que lo **cobrado** pasa a ser neto de
anulaciones sin tocarlo. La **conciliación** (`cobro_medio` efectivo contra `movimiento_caja`
`INGRESO`/`COBRO`) compara dos cifras brutas: la de caja no resta reversiones —es deliberado, lo dice
`MovimientoCajaRepository`—, así que la de M19 tiene que **incluir** los cobros anulados o la
diferencia deja de dar cero el primer día que alguien anula un cobro en efectivo. Se saca el filtro
de `deleted_at` solo de esa suma.

## 9. Design challenge (CLAUDE.md §3)

1. **Ownership.** `cobro`, `cobro_imputacion`, `cobro_reintegro` y `movimiento_caja` son de
   `billing`. La obligación también, así que devolverle saldo no cruza ningún borde. Ningún otro
   módulo las toca.
2. **Ciclos.** La única arista nueva es `billing → person.spi.PacienteDirectory`, y `billing` ya
   dependía de `person.spi` (`EconomiaEnElResumenDePersona`). `person` no depende de `billing`.
   ArchUnit lo verifica en `ModuleArchitectureTest`.
3. **Tenant.** `cobro_reintegro` lleva `organization_id` y `consultorio_id`; los dos uniques nuevos
   empiezan por `organization_id`; el índice nuevo también. Todas las lecturas y escrituras filtran
   por tenant y sede, y el lock se toma con `findByIdInScope…` acotado al tenant: un cobro de otro
   tenant es 404.
4. **Reglas maestras.** Deuda ≠ cobro ≠ caja se mantiene: el anticipo no crea obligación, la
   imputación posterior no toca la caja, el reintegro no toca la deuda y la anulación toca las tres
   pero cada una por su propio primitivo (UPDATE condicional de la obligación, flag del cobro,
   reversión de caja). Nada de clínica.
5. **Baja lógica.** Nada se borra: la anulación marca, la reversión agrega filas, el reintegro
   agrega filas. `cobro_reintegro` es append-only (sin `update` ni `delete` en el puerto).
6. **Contrato.** Aditivo: tres operaciones nuevas, campos opcionales nuevos en el pedido y nuevos
   en la respuesta, `imputaciones` deja de ser obligatoria (relaja, no restringe), tres problem
   types nuevos. **0.54.0**, minor.
7. **Ruta crítica.** Cimientos construidos: obligación (07.01), cobro (07.02), caja con reversión
   (07.03). F-3 no depende de ningún otro paquete; E-6 (prepago de recepción) depende de este.
8. **El caso que rompe el diseño.** *Un anticipo de 5.000 que dos operadores imputan a la vez a dos
   deudas de 5.000, mientras un tercero lo anula.* Sin lock del cobro, las dos imputaciones leen
   saldo 5.000 y las dos pasan, y la anulación lee las imputaciones antes de que existan y devuelve
   saldo que no se descontó. Con `SELECT … FOR UPDATE` del cobro en `READ_COMMITTED`, las tres se
   serializan: la primera imputación entra, la segunda ve saldo 0 y recibe
   `saldo-a-favor-insuficiente`, y la anulación —si llega después— ve la imputación ya commiteada y
   la revierte; si llega antes, las imputaciones ven el cobro anulado y reciben `cobro-anulado`.
   `ck_cobro_saldo_a_favor` impide el negativo aunque el lock fallara. Lo verifica `AnticipoConcurrenteIT`.
   *Segundo caso, el que destapó un defecto existente:* una deuda que se anula mientras otro la
   cobra. `descontarSaldo` era un UPDATE nativo que no avanzaba `version`, así que la anulación
   —que leyó la deuda antes del cobro— escribía `estado = ANULADA, saldo = 0` con su `WHERE version`
   intacto y la deuda quedaba anulada **con un cobro imputado**: plata en la caja sin deuda que la
   explique. Arreglado: los UPDATE nativos del saldo avanzan `version`.

## 10. Decisiones para revisar

1. Anular y reintegrar exigen `cobro:register` **y** `caja:operate`, en vez de un permiso propio.
   Hoy los dos los tienen los mismos tres roles; si se quiere que un administrativo cobre pero no
   anule, hace falta `cobro:annul` en la matriz.
2. El anticipo se declara explícito (`anticipo`), no se infiere del sobrante.
3. La imputación posterior exige que la deuda sea de la sede del cobro.
4. Un cobro con reintegros no se anula.
5. Un cobro anulado deja de aparecer en el listado por persona (como antes, que filtraba
   `deleted_at`) pero sigue recuperable por id con `estado = ANULADO`.
