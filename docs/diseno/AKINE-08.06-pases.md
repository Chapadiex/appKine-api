# AKINE-08.06 / 08.07 — Pases: catálogo, venta y consumo por asistencia

> Diseño corto de la implementación del 14/10/2026 (rama `akine-f9-pases-abonos`). Se apoya en
> `AKINE-08.06-productos.md` y `AKINE-08.06-challenge.md`, escritos con `V64` el 20/09: este
> documento **no los repite**, dice qué se construyó encima, qué cambió respecto de ellos y por qué.

Trazabilidad: RF-M29-001, RF-M29-002, RF-M29-003, RF-M29-009 (parcial), RF-M18-009, RN-M29-002..006,
RN-M29-008, CA-M29-002-06, CA-M29-003-06, CA-M18-009-06, CA-M19-009-06.

## 1. Qué entra y qué no

| Entra | No entra (declarado, ver §9) |
|---|---|
| Catálogo de productos de pack de la sede: alta, edición, baja lógica, vigencia comercial, precio | Abonos (08.08), vencimiento programado y anulación de pases, ajuste manual (08.07) |
| Venta idempotente de un pase con snapshot, movimiento `COMPRA` y **una obligación** por `billing.spi` | Devolución por cancelación de clase (RF-M29-004) |
| Consumo de un crédito por asistencia `PRESENTE`/`PRESENTE_TARDE`, idempotente por asistencia, con devolución si la asistencia se corrige a `AUSENTE` | Devengo por clase suelta (RF-M18-008, esquema `POR_CLASE`) |
| Lectura del pase con su ledger y de los pases de una persona en la sede | Caja de la venta (RF-M20-008): el cobro ya existe en `POST /cobros` |
| Arreglo de `esquemaCobro` en `offering`, que hoy rompe con 500 (§5) | Frontend |

## 2. Modelo — sin tablas nuevas

`V64` ya tenía `producto_servicio`, `pase_servicio`, `movimiento_pase` y la expansión de
`obligacion` (`origen`, `pase_id`). **`V96` sólo agrega** `movimiento_pase.asistencia_id` (anulable,
con FK a `asistencia_actividad`) y el índice `(organization_id, asistencia_id)`.

**Por qué esa columna.** El consumo por asistencia usa `origen_tipo = ASISTENCIA` con
`origen_id = id del evento de asistencia` (registro o corrección), no el id de la asistencia: una
asistencia corregida `PRESENTE → AUSENTE → PRESENTE` consume, devuelve y vuelve a consumir, y con
`origen_id = asistencia_id` el tercer movimiento chocaría contra `uk_movimiento_origen`. Pero
entonces "cuánto crédito tiene tomado esta asistencia y de qué pase" necesita `asistencia_id`: el
neto es `SUM(cantidad)` sobre un índice, sin join.

## 3. Las tres piezas

1. **Producto** (`ProductoService`). Cuelga de una oferta **activa** de la sede que declara
   `esquema_cobro = POR_PACK`; si no, 409. El nombre es único entre los vigentes de la sede (consulta
   que da el 409 explicable + `uk_producto_sede_nombre`). La edición cambia todo menos oferta y tipo,
   con control optimista. La baja exige motivo, es idempotente y **no cascadea**.
2. **Venta** (`VentaDePaseService`, `READ_COMMITTED`). Idempotencia **antes de todo** —misma clave y
   huella devuelve la venta con 200, otra huella es 409—; después el producto (vendible hoy en la
   zona de la sede: si no, `producto-no-vendible`), la persona (del padrón del tenant, activa; no se
   exige perfil de paciente), el pase con snapshot, el movimiento `COMPRA` y la obligación. Todo en
   una transacción. Vigencia: `vigencia_dias` días corridos **contando el de la compra**.
3. **Consumo** (`ConsumoDePaseService`, llamado por `AsistenciaService` al crear y al corregir una
   asistencia, en su misma transacción, `MANDATORY`). Ver §4.

## 4. La regla de consumo

- Se mira el **neto** de la asistencia en el ledger: vale `0` o `-1`.
- Resultado que "estuvo" y neto `0` → si la oferta de la clase es `POR_PACK`, se bloquean
  (`FOR UPDATE`) los pases elegibles —misma persona, `snapshot_oferta_id` de la clase, `ACTIVO`,
  saldo > 0, vigentes **el día de la clase en la zona de la sede**—, el que vence antes primero, y se
  descuenta uno con `UPDATE ... WHERE creditos_disponibles >= 1 ...` que sube `version` y pasa a
  `AGOTADO` al tomar el último. Movimiento `CONSUMO` con `saldo_resultante` releído del motor.
- `AUSENTE` y neto `-1` → `devolverUno` (vuelve `AGOTADO` a `ACTIVO`) y movimiento `DEVOLUCION`
  con el motivo de la corrección. **No borra el consumo**: lo compensa.
- Cualquier otro caso → no escribe (`SIN_CAMBIO` o `NO_APLICA`).
- **Sin pase elegible** en una oferta `POR_PACK`: la asistencia **se registra igual**
  (CA-M19-009-06: asistencia y economía son independientes; RN-M29-005 sólo dice que no se consume),
  el resultado lo informa como `SIN_PASE_ELEGIBLE` y queda en la auditoría
  (`PASE_SIN_CREDITO_ELEGIBLE`). Ningún RF pide devengar una clase suelta en una oferta `POR_PACK`.

El resultado viaja en `ResultadoDeAsistencia.consumoDeCredito` (aditivo).

## 5. El defecto de `esquemaCobro` que se corrige al pasar

`V64` cerró `esquema_cobro` con un CHECK y agregó `momento_devengo` con
`ck_oferta_politica_devengo`, pero **`offering` nunca se enteró**: `EsquemaCobro` aceptaba texto
libre y la entidad no mapeaba `momento_devengo`. Resultado: declarar **cualquier** esquema por la API
—incluso `POR_SESION`— violaba el CHECK en el `INSERT` y respondía 500. Ahora el vocabulario es
cerrado (400 con mensaje), y `momento_devengo` se deriva: `POR_SESION → ASISTENCIA`,
`POR_PACK`/`POR_ABONO → VENTA`. **`POR_CLASE` responde 400**: es el único con dos momentos posibles
y elegir es la decisión del usuario que 08.03 delegó (U-39). Sin esto ningún producto se podía crear,
porque la oferta nunca podía quedar en `POR_PACK`.

## 6. Permisos

La matriz §32 no tiene fila de productos ni de pases; se tomó la más cercana y se documenta en
`PermissionCode`:

| Código | ORG_ADMIN | CONSULTORIO_ADMIN | ADMINISTRATIVO | PROFESIONAL |
|---|---|---|---|---|
| `producto:read` | ORGANIZACION | CONSULTORIO | CONSULTORIO | — |
| `producto:manage` | ORGANIZACION | CONSULTORIO | — (como `convenio:manage`) | — |
| `pase:read` | ORGANIZACION | CONSULTORIO | CONSULTORIO | — (dato económico) |
| `pase:manage` | ORGANIZACION | CONSULTORIO | CONSULTORIO (como `cobro:register`) | — |

El consumo **no tiene permiso propio**: lo dispara el registro de asistencia, que ya exige
`asistencia:manage`. El diseño del 20/09 daba `producto:read` al profesional; se dejó afuera porque
no lo necesita para operar y el precio es dato comercial.

## 7. Contrato — 0.102.0, aditivo salvo un punto declarado

> `V95` y `0.100.0` los reservan los borradores #122 y #123, y `0.101.0` el #125 (ninguno en `main`): por eso
> esta rama usa `V96` y `0.102.0`. Si alguno no entra, el número queda vacío y no se reusa.

Ocho operaciones nuevas en `/api/v1/consultorios/{consultorioId}`: `crearProducto`,
`listarProductos`, `verProducto`, `editarProducto`, `darDeBajaProducto`, `venderPase`, `verPase`,
`listarPasesDePersona`. Problem types nuevos: `producto-no-vendible`, `producto-nombre-en-uso`.
`ResultadoDeAsistencia` gana `consumoDeCredito`; `Obligacion` gana `origen` y `paseId`.

**El punto no aditivo:** `Obligacion.sesionId` deja de ser obligatorio (una deuda de venta no tiene
sesión). Un cliente generado que lo trate como `number` no anulable tiene que regenerarse; hasta que
haya una venta, ninguna respuesta real lo trae vacío. El otro cambio de forma es el 400 de
`esquemaCobro` fuera de vocabulario, que antes era un 500.

## 8. Design challenge

1. **Ownership.** Las tres tablas de M29 son de `activity`; la columna nueva también. `obligacion`
   sigue siendo de `billing` y la escribe `billing` (`DevengoDeVentasAdapter`), no `activity`. La
   política de la oferta la escribe `offering` y `activity` sólo la **lee** por
   `OfertaDirectory.esquemaDeCobro`.
2. **Ciclos.** Arista nueva `activity → billing.spi` (primer paquete `spi` de `billing`). Ningún
   módulo depende de `activity` (verificado con `grep`), así que no puede cerrar un ciclo de ninguna
   longitud; `ModuleArchitectureTest` en verde. La dirección es la inversa de 07.01 a propósito:
   vender es un acto comercial y CA-M29-002-04 exige pase y deuda en la misma transacción.
3. **Tenant.** Toda consulta nueva filtra por `organization_id` (y por sede donde la hay); los
   UPDATE del saldo llevan `organization_id` en el `WHERE`. Cross-tenant → 404. La lista de pases de
   una persona resuelve la persona por el padrón del tenant antes de leer.
4. **Reglas maestras.** Vender devenga una obligación y **nada más**: ni cobro ni caja (DP-06).
   Consumir un crédito no toca la obligación ni la caja (RN-M29-006, CA-M18-009-06). Asistencia ≠
   economía: la falta de pase no impide registrar la asistencia.
5. **Baja lógica.** El producto la tiene y no cascadea. El pase no se borra ni se da de baja. El
   ledger no tiene update ni delete en el puerto; la devolución compensa, no borra.
6. **Contrato.** 0.99.0 → 0.102.0, minor. El único cambio de forma es `sesionId` opcional (§7).
7. **Ruta crítica.** Cimientos presentes: oferta, persona, asistencia (08.03), obligación y cobro.
   Caja (07.03) existe, pero la venta no la toca: se paga por `POST /cobros`. F9 sigue abierta sin
   el gate del MVP (decisión pendiente 8, DU-12); esto se hizo porque el usuario abrió F9.
8. **El caso que rompe el diseño.** *Dos asistencias de la misma persona, a dos clases distintas,
   registradas a la vez con un pase de un solo crédito.* Las dos transacciones ejecutan
   `lockElegibles` con `FOR UPDATE`: la segunda espera a que la primera commitee, y entonces la
   lectura actual ya no ve el pase (está `AGOTADO`, saldo 0). Aunque se saltara el lock, el
   `UPDATE ... WHERE creditos_disponibles >= 1` evaluado bajo el lock exclusivo de la fila deja pasar
   a una sola, y `ck_pase_saldo_no_negativo` es el último cinturón. Resultado: un `CONSUMIDO`, un
   `SIN_PASE_ELEGIBLE`, saldo 0 y ledger que suma 0. Lo prueba `ConsumoDePaseConcurrenteIT`.
   *Segundo caso: el reintento de la venta.* Idempotencia antes de devengar, `uk_pase_idempotency`
   y `uk_obligacion_venta` (tres capas, ver el challenge del 20/09 §8.1).

## 9. Lo que queda fuera, con su dueño

> **15/10/2026:** la devolución por cancelación, el ajuste, la anulación, el job de vencimiento y
> los abonos entraron en la rama `akine-m29-abonos-y-ajustes`
> ([`AKINE-08.07-08.08-ledger-y-abonos.md`](AKINE-08.07-08.08-ledger-y-abonos.md)). Siguen fuera la
> consulta paginada del ledger, la renovación automática y el aviso de vencimiento.

- **08.07:** devolución por cancelación de clase (RF-M29-004), ajuste manual (RF-M29-005), job de
  vencimiento que escriba `VENCIDO` (RF-M29-006; hoy el vencimiento se evalúa por fecha), anulación
  del pase, consulta paginada del ledger, si el no-show consume crédito.
- **08.08:** abonos (`tipo = ABONO`, `origen = VENTA_ABONO`).
- **08.09:** integración con caja y reportes de pases.
- **U-39 (nueva):** cómo se devenga `POR_CLASE` —asistencia o inscripción— y si el no-show cobra.
  Hasta decidirla, `POR_CLASE` es 400 en la oferta y RF-M18-008 no tiene disparador.
- **Sin pase en `POR_PACK`:** hoy se informa y se audita; si el centro quiere bloquear la asistencia
  o devengar una clase suelta, es decisión de producto.
