# AKINE-08.09 — Integración económica de la segunda entrega

> 16/10/2026 · rama `akine-08.09-integracion-economica` · contrato **0.114.0** · migración
> reservada **V104, no usada** (el cambio no toca el esquema).
>
> Fuente: plan §13, etapa 08.09 (RF-M21-009..010, RF-M22-006..007, RF-M24-007..008), DP-07 y
> DP-10 (§7), la ficha `docs/fases/F9-segunda-entrega.md` y los diseños de 07.04, 07.05, 08.06 y
> 08.07–08.08.

## 1. Lo que la etapa pide, y lo que ya estaba

El plan describe la etapa como *"integrar clases/productos con presentaciones a financiadores,
pagos profesionales, auditoría y conciliación final"*. Antes de escribir nada se inventarió el
código de `main` (`1361be3`) contra cada RF. Resultado:

| RF / pedido | Estado al empezar | Qué hace esta etapa |
|---|---|---|
| Venta de pase o abono → deuda cobrable (RF-M18-009/010) | **Andaba.** `DevengoDeVentas` devenga `VENTA_PASE`/`VENTA_ABONO` en la transacción de la venta, `PACIENTE`/`PARTICULAR`, importe congelado del pase | Nada |
| Cuenta corriente con la venta | **Andaba.** `ObligacionResponse.origen` y `paseId` desde 0.102.0 | Nada |
| Cobro y caja de esa deuda (M19, M20) | **Andaba sin probar.** `CobroService` no distingue origen: cualquier deuda del paciente se cobra, entra a la jornada y se revierte al anular el cobro. **Ningún test lo ejercía**: el único caso "con cobros" de 08.07 simula el cobro con un `UPDATE` sobre el saldo | IT contra MySQL del circuito entero (§4) |
| Anulación de la venta con su deuda (08.07) | **Andaba.** `POST /pases/{id}/anulacion` anula pase y deuda juntos; con cobros, 409 `pase-con-cobros` | Nada; se prueba con un cobro real |
| **Anular la deuda de una venta desde la cuenta corriente** | **Defecto.** `DELETE /obligaciones/{id}` anulaba la deuda sola y dejaba el pase `ACTIVO` con sus créditos: un pack regalado sin rastro. RF-M18-006 exige que *"los datos derivados queden consistentes con la entidad principal"* | **409 `obligacion-de-venta`** con el `paseId` (§3) |
| RF-M21-010 — excluir consumos no financiables de la presentación (CA-M21-010-06) | **Andaba por construcción.** La venta nace `PACIENTE`; la bandeja de elegibles filtra `responsable = 'FINANCIADOR'` y `agregarItem` rechaza con `DEUDA_DEL_PACIENTE`. No se infiere por nombre del servicio | IT que lo prueba contra el motor |
| RF-M21-009 — prestaciones grupales en presentaciones (CA-M21-009-06: renglones por paciente) | **Depende de 08.05** (atención clínica grupal), en curso en paralelo. Toda sesión cerrada con cobertura ya devenga la obligación del financiador (F-4), sea de la oferta que sea | **Afuera** (§5) |
| RF-M22-006/007 — base de pago por clase y trazabilidad egreso↔servicio | **No existe el cimiento.** El RF pide aplicar *"el esquema de honorario configurado: por clase, hora, participante"*, y **no hay ninguna configuración de honorarios** en el modelo ni en la especificación. 07.05 lo dejó explícito: el importe del egreso lo declara una persona | **Afuera**, decisión nueva **U-41** (§5) |
| RF-M24-007 — auditar ciclo de clase e inscripción (CA-M24-007-06) | **Andaba.** `clase_evento` append-only; `InscripcionService` audita reserva, espera, confirmación, cancelación (actor, estado anterior y motivo) y promoción en `audit_event` | Nada |
| RF-M24-008 — auditar créditos y abonos (CA-M24-008-06) | **Andaba.** `movimiento_pase` es append-only (`updatable = false`), lleva cantidad, saldo resultante, origen, asistencia, actor y motivo; las ITs de 08.07/08.08 verifican `creditos_disponibles == SUM(cantidad)` después de cada paso | Nada |
| U-39 (`POR_CLASE` y asistencia sin pase en `POR_PACK`) | Abierta | **No se toca.** Nada de esta etapa depende de ella |

**Conclusión:** de la etapa queda implementable sin decisiones nuevas un defecto de consistencia y la
verificación real del circuito. Lo demás espera a 08.05 o a una decisión del usuario.

## 2. Reglas maestras que se respetan

- **Deuda, cobro y caja son tres cosas.** La venta devenga deuda y nada más; el cobro descuenta la
  deuda y produce el movimiento de caja; anular el cobro revierte la caja y devuelve la deuda. El IT
  mira las tres tablas por separado en cada paso.
- **Importe congelado, `DECIMAL`.** No cambia: la deuda copia el precio del pase al venderse.
- **La deuda se devenga dentro de la transacción del hecho** (la venta). No cambia.
- **Nada se borra.** La anulación deja `ANULADA` con motivo; el cobro anulado conserva su
  imputación y su comprobante.

## 3. El cambio: la deuda de una venta no se anula sola

`ObligacionService.anular` rechaza, antes de mirar la versión, toda obligación cuyo `origen` no sea
`SESION`, con `ObligacionDeVentaException` → **409 `obligacion-de-venta`** y las propiedades
`obligacionId`, `paseId` y `origen`, para que la pantalla ofrezca la acción correcta.

**Por qué rechazar y no anular las dos cosas desde `billing`:** `activity` ya depende de
`billing.spi` (`DevengoDeVentas`); que `billing` llamara a `activity` cerraría un ciclo que
`ModuleArchitectureTest` prohíbe. Y no hace falta: el camino que anula las dos mitades juntas ya
existe (`POST /pases/{paseId}/anulacion` → `DevengoDeVentas#anularDeudaDeVenta`, misma
transacción). Ese camino no pasa por `ObligacionService`, así que no se ve afectado.

**Por qué antes del control de versión:** con una versión vieja la respuesta sería
`concurrent-modification` y el operador reintentaría algo que nunca va a poder hacer.

## 4. Tests

- `ObligacionServiceTest`: la deuda de un pack y la de un abono responden la excepción con el pase y
  el origen, no persisten, no auditan y no alertan consumos.
- `VentaDePaseEnElCircuitoEconomicoIT` (MySQL, corre en el CI):
  1. Venta → cuenta corriente `VENTA_PASE` sin cobro ni caja → cobro parcial en efectivo (deuda
     `PARCIAL`, un `INGRESO` en la jornada con el cobro como referencia) → la venta no se anula
     (`pase-con-cobros` con lo cobrado) → la deuda sola tampoco (`obligacion-de-venta`, sin
     escribir) → anular el cobro revierte la caja y devuelve la deuda entera → la venta ahora se
     anula con su deuda, sin borrar la imputación histórica ni los dos movimientos.
  2. Pack pagado entero: `PAGADA`, no anulable, créditos intactos. Abono (`POR_ABONO`): su
     `VENTA_ABONO` pasa por la misma caja y tampoco se anula desde la cuenta corriente.
  3. CA-M21-010-06: la deuda de la venta no aparece entre los elegibles del financiador y
     agregarla a mano a un borrador es `obligacion-no-presentable` (`DEUDA_DEL_PACIENTE`), sin fila.

## 5. Lo que queda afuera, y por qué

- **RF-M21-009 (renglones individuales de una atención grupal).** Su insumo es la atención clínica
  grupal de 08.05, que se construye en paralelo. Si 08.05 cierra una `Sesion` por participante —lo
  que pide la regla maestra 4—, cada una devenga su obligación del financiador por el camino de F-4
  y entra sola a la bandeja: no habría nada que escribir acá. Si 08.05 elige otro modelo, la
  integración se diseña sobre el modelo real, no sobre una suposición.
- **RF-M22-006/007 (liquidación por clase).** El flujo del RF es *"aplicar el esquema de honorario
  configurado"*, y ese esquema no existe: ni en el modelo, ni en M22, ni en M27/M28 hay dónde
  declarar *por clase*, *por hora* o *por participante*, con qué importe, para qué profesional u
  oferta y con qué vigencia. Inventarlo es decidir una regla remunerativa —lo que 07.05 se prohibió
  explícitamente—. Queda como **U-41** en `docs/fases/decisiones-pendientes.md`, con lo que la
  decisión tiene que fijar y la dirección de módulos que la hace posible sin ciclo: la base se
  calcula en `activity` (que conoce clases, instructor y asistencias) y entra a `billing` por un
  `spi` como detalle del egreso, con snapshot de los importes (CA-M22-007-06). Ni tabla ni
  migración se adelantan.
- **Frontend.** El 409 nuevo necesita que la cuenta corriente ofrezca "anular el pase" en lugar de
  "anular la deuda" para las filas de venta. Va al repo web cuando regenere el cliente 0.114.0.

## 6. Design challenge

1. **Ownership.** Ninguna tabla nueva. `obligacion` es de `billing` y sólo `billing` la escribe;
   `pase_servicio` y `movimiento_pase` son de `activity`. El IT lee las dos por JDBC, que es lo que
   hace todo IT del repo.
2. **Ciclos.** No se agrega ninguna arista entre módulos. La razón de rechazar en vez de cascadear
   es justamente no crear `billing → activity`. ArchUnit corrido.
3. **Tenant.** Sin tablas ni consultas nuevas; `findByIdInScope` ya filtra por organización y sede.
4. **Reglas maestras.** Al revés: el cambio impide que una anulación de deuda (M18) se use como
   anulación de una venta (M29), que son actos distintos con efectos distintos. Cobro y caja no se
   tocan.
5. **Baja lógica.** Nada se borra; el IT lo verifica (`deleted_at` nulo, imputación y movimientos
   conservados).
6. **Contrato.** Aditivo en la forma —un problem type nuevo en el enum y una descripción—, pero
   **cambia una respuesta**: `DELETE /obligaciones/{id}` sobre una deuda de venta respondía 200 y
   ahora 409. Es la corrección de un defecto, no una ruptura de un uso legítimo: el uso anterior
   dejaba datos inconsistentes. Minor **0.114.0**, declarado en el PR.
7. **Ruta crítica.** Los cimientos (venta, deuda, cobro, caja, anulación) están en `main`. Lo que no
   tiene cimiento (honorarios, atención grupal) no se adelanta.
8. **El caso que rompe el diseño.** Una deuda de venta ya anulada por el pase y alguien que intenta
   anularla de nuevo desde la cuenta corriente: responde `obligacion-de-venta` y no
   `obligacion-already-anulada`. Es aceptable —la acción sigue siendo "mirá el pase", y el pase dice
   `ANULADO`—. El otro: una deuda de venta con cobros. Antes respondía `obligacion-con-cobros`; ahora
   `obligacion-de-venta`, que es más útil porque el camino correcto pasa por el pase igual (y el
   pase responde `pase-con-cobros` con lo cobrado).
