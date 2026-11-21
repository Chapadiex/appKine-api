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

## 5. Datos sin uso detectados (para la fase aparte)

- `copago` en `plan_catalogo`, `plan_cobertura` y `cobertura_paciente`: ningún devengo lo lee.
- `requiere_autorizacion` y `requiere_credencial` del plan: la elegibilidad usa los del convenio.
- `convenio.modalidad`: el devengo es siempre por prestación.
- `moneda` en plan, convenio y arancel: siempre ARS.
- `convenio.limite_sesiones_mensual`: se informa y nadie lo controla. El paso 2 lo va a aplicar.
- `codigo` y `nombre` del convenio: se pueden derivar del plan.
