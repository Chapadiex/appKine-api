# AKINE — Obras sociales simples

> Estado: diseño acordado con el usuario el 21/11/2026. Paso 1 implementado en la rama
> `akine-obras-sociales`; pasos 2 a 4 pendientes.

## 1. Por qué

Para dar de alta una obra social hoy hacen falta entre 4 y 6 pantallas, repartidas en dos grupos del menú, y más de 25 campos sin
contar los aranceles. Varios de esos datos no los usa ninguna regla, y el devengo puede asignarle la sesión a una
cobertura distinta de la que se eligió en recepción. El objetivo es que el circuito se entienda sin explicación y
que la liquidación mensual sea exacta.

## 2. El modelo acordado

```
A = precio de la sesión en el centro     (precio particular de la oferta, ya existe)
B = lo que paga la obra social           (arancel del convenio)
Coseguro = A − B                         (lo paga el paciente al atenderse; nunca negativo)
```

- El paciente paga el **coseguro en el momento**. La parte **B** queda como obligación del financiador,
  **pendiente**, hasta que el centro arma la liquidación del mes.
- Si **B ≥ A**, el coseguro es 0.
- Si la sesión supera el límite (cupo de la autorización o tope mensual del convenio), se atiende como
  **particular**: el paciente paga A completo.
- El importe se congela al cerrar la sesión. Un aumento de A o de B no modifica las sesiones ya cerradas.

### Liquidación (presentación) mensual

```
Pendiente (obligación sin presentar)
   → Borrador   (se arma el lote del período)
   → Presentada (se congela, recibe número; la factura es un dato opcional)
   → Pagada     (conciliada: presentado − debitado − cobrado = 0)
```

- Una sesión no puede estar en dos liquidaciones vivas. Ya lo garantiza `uk_presentacion_item_ocupa`
  (V8): la obligación sólo se libera si la obra social la debita, y entonces puede presentarse de nuevo.
- La conciliación exige saldo cero. Si falta plata, se carga un débito con su motivo. No se cierra en silencio.
- El detalle tiene que poder leerse: paciente, DNI, número de afiliado, plan, fecha, práctica (código),
  número de autorización e importe. Además, un resumen por paciente.

## 3. Pasos

| # | Qué | Toca | Migración / contrato |
|---|---|---|---|
| 1 | **El devengo usa la cobertura elegida** en la recepción (o en el turno) | `scheduling.spi`, `encounter.spi`, `billing` | No / no |
| 2 | **Coseguro = A − B**: el arancel guarda sólo B, y el total y el coseguro se calculan | `contracting`, `billing`, web | Sí / sí (rompe `importeTotal` y `coseguro` en el request) |
| 3 | **Liquidación legible**: CSV y pantalla con nombre, DNI, afiliado, plan, práctica, autorización y resumen | `billing`, `person.spi`, web | No / aditivo |
| 4 | **Estados en pantalla**: Pendiente → Borrador → Presentada → Pagada; la factura deja de ser un paso | web (+ backend si se fusiona FACTURADA) | A definir |

Fase aparte, después de estos cuatro pasos: juntar el convenio con el plan en una sola pantalla y sacar los datos sin uso.

## 4. Paso 1 — la cobertura elegida manda

**Defecto.** `ObligacionDevengador.cubierta()` recorría las coberturas aplicables, con la principal primero, y
tomaba la primera que tuviera arancel. No leía `recepcion.cobertura_id` ni `turno.cobertura_id`. Si la
recepción validaba la cobertura secundaria, la deuda salía igual a nombre de la principal y la sesión
terminaba en la liquidación de otra obra social.

**Corrección.**
- `TurnoDirectory.coberturaElegida(org, sede, turno)` devuelve la cobertura de la recepción vigente
  resuelta como `COBERTURA`. Si no hay recepción, devuelve la que se eligió al reservar el turno.
- `SesionCerrada` suma `coberturaElegidaId`, que se lee una vez al cerrar, como el resto del hecho.
- El devengo prueba **primero** la cobertura elegida. Si al cerrar no aplica (credencial vencida, convenio de
  baja), vuelve al orden anterior y deja un `warn`. Así se mantiene la regla 3 de la cabecera, que dice que el
  devengo nunca lanza excepción.

**Fuera de alcance.** `ConsumoDeAutorizacionEnCierre` sigue eligiendo la autorización por práctica y caso, no por
cobertura. Revisarlo queda para el paso 2.

## 5. Paso 2: coseguro = A − B

**Regla.** El arancel del convenio guarda **sólo B** (`importe_financiador`). El coseguro se calcula al
**cerrar la sesión**:

```
A = precio de la oferta el día del cierre      (SesionCerrada.precioDeLaOferta, ya se lee hoy)
B = importe_financiador del arancel congelado
coseguro = max(A − B, 0)
snapshot_importe_total = B + coseguro          (= max(A, B); mantiene ck_obligacion_snapshot_partes_suman)
```

- Si la oferta **no tiene precio** el día del cierre, el coseguro es 0 y queda un `warn`: el paciente
  no paga nada. Con cobertura no se exige precio (igual que hoy), así que no se rompe ningún cierre.
- Si A y B son cero, no se devenga nada: las partes en cero no generan fila. Desaparece la regla
  especial de "total cero", porque el total ya no se carga.
- El snapshot de la obligación sigue igual: total, financiador y coseguro **del día**, congelados.

**Migración `V33`** (reservada: `V29` acciones, `V30` cobro, `V31` planes, `V32` términos):
- se borra `ck_arancel_partes_suman_total`;
- `importe_total` y `coseguro` de `convenio_arancel` pasan a admitir `NULL`, quedan como **históricos** y
  nadie los lee para calcular;
- los aranceles existentes conservan sus valores, pero el devengo ya no los usa.

**Contrato `0.185.0`** (sobre el `0.184.0` de `akine-api-correo-duenio`; renumerar al rebasear):
- en el alta, la edición y la importación de aranceles, `importeTotal` y `coseguro` pasan a ser
  **opcionales y se ignoran** (`deprecated`). Un cliente viejo que los manda no se rompe;
- en las respuestas (`Arancel`, `ArancelEfectivo`, cobertura aplicable), `importeTotal` y `coseguro`
  pasan a calcularse con el precio de la oferta cuando se conoce la oferta. Si no se conoce, viajan
  **nulos**. Es un cambio incompatible declarado para el cliente generado (campos que dejan de ser
  obligatorios).

### Design challenge

1. **Ownership.** No hay tablas nuevas. `convenio_arancel` es de `contracting`; el cálculo vive en
   `billing` (devengo) y en `contracting` (simulador), y la fórmula es una sola, en
   `contracting.spi.Coseguro`.
2. **Ciclos.** No hay aristas nuevas: `contracting` y `person` ya dependen de `offering.spi` para el
   precio, y `billing` ya recibe A en `SesionCerrada`.
3. **Tenant.** No hay tablas ni índices nuevos.
4. **Reglas maestras.** Obligación, cobro y caja siguen separados. El coseguro sigue siendo una obligación
   del paciente; cobrarlo es otro acto.
5. **Baja lógica.** No se borran datos: los importes históricos de los aranceles quedan en la tabla.
6. **Contrato.** El request es compatible: los campos pasan a opcionales. La respuesta tiene campos que
   pasan a nulos, un cambio incompatible para el cliente TypeScript, declarado; el frontend tiene que
   regenerar y adaptar el formulario del arancel en el mismo tramo.
7. **Ruta crítica.** El precio por día de la oferta y el devengo por convenio ya existen.
8. **El caso que rompe el diseño.** El centro sube A a mitad de mes. Las sesiones ya cerradas conservan
   su coseguro (snapshot) y las nuevas toman el A nuevo: es lo acordado. Otro caso adverso: una obra
   social con B mayor que A. El paciente paga 0 y la obra social paga B entero, que puede ser más de lo
   que el centro cobra en particular: es correcto, la obra social paga lo pactado.

**Fuera del paso 2:** aplicar el tope mensual o el cupo de la autorización para pasar la sesión a particular
(paso 2b), y la vista `V23` del asistente, que va a mostrar `coseguro` nulo en los aranceles nuevos.

## 6. Paso 3: liquidación legible

**Qué falta hoy.** El renglón de la presentación (`presentacion_item`) congela persona, sesión,
fecha, concepto e importe. El CSV exporta los **ids** de persona y sesión, y la pantalla también.
La obra social pide nombre, documento, número de afiliado, plan, código de práctica y número de
autorización.

**Qué se agrega, sin migración:**
- Cada renglón de `PresentacionItemView` suma `pacienteNombre`, `pacienteDocumento`,
  `numeroAfiliado`, `planNombre`, `practicaCodigo`, `practicaNombre` y `autorizacionNumero`. Se
  calculan al leer (`RenglonesLegibles`) y no se persisten, igual que el timeline y el 360:
  - nombre y documento: `person.spi.PacienteDirectory.findAll`, que billing ya usa;
  - afiliado y plan: la **copia congelada** de `cobertura_paciente` (nombre del plan al firmar), por
    el `coberturaId` del snapshot de la obligación;
  - práctica: `resource.spi.CatalogoDirectory.findPractica` sobre la práctica de la obligación;
  - autorización: el consumo del ledger (`autorizacion_movimiento`, `CONSUMO` con origen `SESION`)
    de esa sesión, sin revertir.
- El CSV suma esas columnas y, al final, un **resumen por paciente**: nombre, documento,
  afiliado, plan, cantidad de sesiones, fechas e importe presentado.
- Contrato: campos **opcionales nuevos** en `PresentacionItem` (cambio aditivo, minor 0.186.0).

**PHI.** El CSV de 07.04 salió con PHI mínima a propósito. Este paso la amplía porque la obra social
la exige para liquidar. Sigue igual de restringida: `cobro:register` y la sede del lote. No se agrega
ningún dato clínico (diagnóstico, evolución).

### Design challenge
1. **Ownership.** No hay tablas nuevas. La lectura nueva de afiliado y autorización vive en `person`
   (`person.spi.DatosDeLiquidacionDirectory`), que es dueño de `cobertura_paciente` y del ledger.
2. **Ciclos.** Se agrega una arista nueva, `billing → resource.spi`. `resource` no depende de
   `billing`. `billing → person.spi` ya existía.
3. **Tenant.** Las dos consultas nuevas filtran por `organization_id`.
4. **Reglas maestras.** Liquidar no es cobrar: sólo se lee.
5. **Baja lógica.** No aplica.
6. **Contrato.** Es aditivo.
7. **Ruta crítica.** El ledger y la cobertura congelada existen desde 04.05 y 03.04.
8. **El caso que rompe el diseño.** Un lote de 500 renglones. Se resuelve con una consulta por
   fuente, en lote (personas, coberturas, consumos), y una por práctica distinta (pocas). No es una
   consulta por renglón.

## 7. Datos sin uso detectados (para la fase aparte)

- `copago` en `plan_catalogo`, `plan_cobertura` y `cobertura_paciente`: ningún devengo lo lee.
- `requiere_autorizacion` y `requiere_credencial` del plan: la elegibilidad usa los del convenio.
- `convenio.modalidad`: el devengo es siempre por prestación.
- `moneda` en plan, convenio y arancel: siempre ARS.
- `convenio.limite_sesiones_mensual`: se informa y nadie lo controla. El paso 2 lo va a aplicar.
- `codigo` y `nombre` del convenio: se pueden derivar del plan.
