# AKINE E-7b — Sin precio particular no se cierra (DP-17)

**08/10/2026** · rama `akine-E-7b-cierre-sin-precio` · **sin migración** · contrato **0.76.0**
(aditivo: problem type `oferta-sin-precio` y descripción del cierre). Implementa **DP-17** y
reemplaza la regla 2 de 07.01 y la decisión (b) de E-7 (§5 de
[AKINE-E-7-particular-en-obligacion](AKINE-E-7-particular-en-obligacion.md)).

## 1. Qué cambia

| Antes (07.01, E-7) | Ahora (DP-17) |
|---|---|
| Oferta sin precio + deuda particular → el cierre pasa, no se devenga nada, `log.warn` | El cierre responde **409 `oferta-sin-precio`** antes de numerar, y no deja nada escrito |
| Particular manda sobre el convenio (E-7 a) | Igual, ahora confirmado por DP-17 |

"Deuda particular" es cualquiera de: recepción del turno resuelta como **Particular**, oferta que
**no admite obra social**, o **sin cobertura** con convenio y arancel aplicables a la práctica.
Con cobertura el coseguro sale del arancel y **no** hace falta precio particular. Sin asistencia
no hay deuda y no se exige nada. Una práctica sin cargo bajo el convenio (total cero) tampoco.

## 2. Diseño

```
encounter.SesionService#cerrar
  1. idempotencia · 2. propiedad y version · 3. minimos
  3b. hecho = hechoDelCierre(...)               <- precio del dia, practicas, Particular, numero 0
      si el precio no esta tarifado y algun PrecioParticularDelCierre#exigePrecioParticular(hecho)
          -> OfertaSinPrecioException(ofertaId, dia local de la sede, motivo)   -> 409
  4-5b. numeradores · 6. cerrar · 6b. version 1
  7. observadores.alCerrar(hecho.conNumeroSesion(numero))
```

- **`encounter.spi.PrecioParticularDelCierre`** (nuevo): `boolean exigePrecioParticular(SesionCerrada)`.
  Lo implementa **`billing.ObligacionDevengador`** con `cubiertaQueCorresponde`, que es **el mismo
  método** con el que después elige entre convenio y particular. Si fueran dos reglas, una oferta
  sin precio podría pasar la validación y caer igual a particular.
- **El hecho se arma una sola vez, antes de numerar**, y los observadores reciben ese mismo hecho
  con el número puesto (`SesionCerrada#conNumeroSesion`). La validación y el devengo ven el mismo
  precio, las mismas prácticas, la misma decisión del mostrador y el mismo instante.
- **La regla solo se consulta si falta el precio.** Con precio no hay nada que decidir, y el caso
  común no paga la búsqueda de cobertura dos veces.
- **`encounter.spi.OfertaSinPrecioException`** (nueva) lleva `ofertaId`, `dia` y `motivo`
  (`PARTICULAR_POR_RECEPCION`, `OFERTA_SIN_OBRA_SOCIAL`, `SIN_COBERTURA_APLICABLE`). Vive en `spi`
  porque la lanzan los dos lados; la mapea `EncounterProblemHandler`.
- **El camino "sin precio → no devengar" desaparece.** En `devengarParticular` queda como red: si
  se llega sin precio —una lectura viva cambió entre la validación y el devengo, por ejemplo un
  arancel dado de baja en el medio— lanza la **misma** excepción. El cierre entero se revierte
  (`CierreDeSesionObserver`: un observador que falla hace fallar el cierre), así que nunca queda
  una prestación sin deuda.

### Forma del problema

```json
{
  "type": "https://akine.app/problems/oferta-sin-precio",
  "title": "La oferta no tiene precio para ese dia",
  "status": 409,
  "detail": "La atencion se cobra al precio particular y la oferta no tiene precio vigente el 2026-10-08. Carga el precio en la oferta (precio de lista o precio particular que cubra ese dia) y volve a cerrar: la sesion sigue abierta y no se consumio nada.",
  "ofertaId": 42,
  "dia": "2026-10-08",
  "motivo": "PARTICULAR_POR_RECEPCION"
}
```

## 3. Design challenge (CLAUDE.md §3)

1. **Ownership.** Ninguna tabla nueva ni columna nueva. La decisión económica sigue en `billing`;
   `encounter` solo pregunta y traduce el 409 de su propio endpoint.
2. **Ciclos.** `billing → encounter.spi` ya existía (`CierreDeSesionObserver`, `SesionCerrada`); la
   interfaz nueva va por la misma arista. `encounter` no conoce a `billing`. `ModuleArchitectureTest`
   5/5 sin tocar reglas.
3. **Tenant.** Sin consultas nuevas: la regla reusa las lecturas de F-4/B-3 (cobertura aplicable,
   arancel congelado, prácticas de la oferta), todas con `organization_id` y sede.
4. **Reglas maestras.** Cierre ≠ cobro sigue intacto: no se exige ningún pago, se exige que la
   **deuda** se pueda valorizar. Turno ≠ Recepción ≠ Sesión: la recepción no se toca. Cobertura ≠
   Convenio ≠ Obligación: con cobertura no se pide precio particular.
5. **Baja lógica.** Nada se borra. El 409 revierte la transacción entera; no hay escrituras
   compensatorias.
6. **Contrato.** Aditivo, **0.76.0**: un `problemType` nuevo en el enum y la descripción del cierre
   y de su 409. Ninguna forma de respuesta cambia.
7. **Ruta crítica.** Cimientos en `main`: precio del día (B-3), devengo por convenio (F-4),
   Particular por recepción (E-7), turno en el hecho (E-6). No se adelanta nada.
8. **El caso que rompe el diseño.**
   - *Un cierre que antes pasaba ahora se bloquea en un centro que todavía no cargó precios.* Es
     exactamente lo que DP-17 pide: el profesional ve un 409 accionable, la sesión queda abierta
     con su borrador, y alguien con permiso sobre la oferta carga el precio. El cierre se reintenta
     tal cual y toma el número que le tocaba: no hay hueco, porque el 409 sale antes de numerar.
     **La pantalla de cierre tiene que mostrar el `detail`**; hoy un 409 desconocido cae al
     mensaje genérico.
   - *El precio cambia, o el arancel se da de baja, entre la validación y el devengo.* Mismo
     `READ_COMMITTED`, dos lecturas vivas. Si el devengo cae a particular sin precio, lanza el
     mismo 409 y revierte todo (incluidos los números ya tomados): nunca queda una prestación sin
     deuda. Si el precio desaparece pero había cobertura, cierra por convenio, que no lo necesita.
   - *Medianoche.* El instante del cierre se fija una sola vez antes de validar, y el día local de
     la sede se calcula igual en la validación, en el precio (`OfertaDirectory#precioEn`) y en el
     devengo.
   - *Sesión sin asistencia sobre una oferta sin precio.* Cierra: sin asistencia no hay deuda
     (regla 1 de 07.01).

## 4. Verificación

- Unitarios: `SesionServiceTest` (Particular sin precio → 409 sin tocar numeradores, versión ni
  observadores; sin cobertura sin precio → 409; con cobertura sin precio → cierra y los
  observadores reciben el mismo hecho con número; con precio → cierra sin consultar la regla) y
  `ObligacionDevengadorTest` (la regla: Particular por recepción, sin cobertura, oferta sin obra
  social → exige; con cobertura, total cero o ausente → no exige; la red lanza 409 en vez de
  tragarse el faltante).
- IT contra MySQL: `CierreSinPrecioIT` — paciente con cobertura, convenio, arancel y autorización
  vigente, oferta sin `precio_base`, recepción Particular → 409 con la sesión en `BORRADOR` y sin
  número, `sesion_numerador` sin mover, ninguna `sesion_version`, `cantidad_consumida = 0`, ningún
  movimiento de consumo y ninguna obligación. Cargado el precio, el mismo cierre pasa con el
  **número 1** y una `PARTICULAR` de 8500. Control: la misma fixture sin Particular cierra por
  convenio (financiador + coseguro) y consume 1.

## 5. Decisiones a revisar

1. **El día del 409 es el local de la sede** (como el precio por vigencia de B-3), no el UTC.
2. **Total cero bajo convenio no exige precio**: no es un dato faltante sino lo que el convenio
   pacta. Si DP-17 se leyera literal ("toda prestación genera deuda") habría que bloquear también
   estas, y el convenio no tiene cómo expresarlo.
