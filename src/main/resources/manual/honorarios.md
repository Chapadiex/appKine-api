---
id: honorarios
titulo: Honorarios por clase
grupo: Economía
rutas: ["/egresos/honorarios", "/egresos/honorarios/liquidaciones/:liquidacionId"]
permisos: [caja:operate]
relacionadas: [egresos, clases, asistencia, caja-diaria]
palabras: [liquidar honorarios, pago a instructor, honorario por clase, liquidación, tarifa por participante, profesor, instructores, sueldo por clase]
---

## Para qué sirve

Calcula cuánto le corresponde a un instructor por las **clases que dictó** en un período: la
tarifa de la actividad por cada participante presente, repartida en partes iguales entre los
instructores de la clase.

Confirmar la liquidación crea un **egreso en borrador**, imputado al servicio. **No mueve la
caja**: el pago se registra después, desde el egreso.

## Tareas

### Calcular el borrador de una liquidación

![La tarjeta Qué liquidar, con el colaborador, la fecha desde, el servicio y el botón Calcular el borrador marcados](captura:honorarios/calcular)

1. Entrá a :icono[economia]: **Economía → Honorarios** y elegí el **Colaborador**.
2. Completá **Clases desde** y **Clases hasta**.
3. Si querés uno solo, elegí el **Servicio (opcional)**. Si no, quedan **Todos los servicios
   grupales**.
4. Tocá **Calcular el borrador**.

Aparece el **Total a liquidar**, cuántas clases y presentes entran y, por cada servicio, una tabla
con lo que **Le corresponde** en cada clase.

### Confirmar la liquidación

1. Revisá el borrador. Si querés, escribí el **Concepto del egreso (opcional)**.
2. Tocá **Confirmar la liquidación** (o **Confirmar la liquidación de** el servicio, si hay varios).
3. Tocá **Ir al egreso** para cargarle el comprobante, confirmarlo y pagarlo.

> [!importante]
> Cada liquidación es de **un solo servicio**, porque su egreso se imputa a él. Si las clases son
> de varios servicios, se confirma una liquidación por cada uno.

### Revisar las clases que no entran

1. Después de calcular, bajá hasta **Clases que no entran**.
2. Mirá la columna **Por qué no entra**.

Los motivos posibles: **Ya liquidada a este colaborador** (con enlace a su egreso), **La actividad
no tenia tarifa de honorario ese día**, **Ningún participante presente** o **La parte de este
instructor da cero**.

### Ver una liquidación ya hecha

1. Desde el egreso, en **Liquidación de honorarios**, tocá el enlace **Ver la liquidación … y sus
   clases**. Justo después de confirmar, también está el botón **Ver la liquidación**.
2. Mirá **Total liquidado**, **Pagado**, **Falta pagar** y el **Detalle por clase**.

El detalle es la foto del momento de confirmar: una tarifa nueva o una asistencia corregida
después no la cambian.

## Qué significa cada estado

- **Vigente**: la liquidación tiene su egreso activo.
- **Liberada**: se anuló su egreso. Sus clases vuelven a poder liquidarse.

## Problemas comunes

- **«No se confirmó la liquidación»**: el borrador cambió desde que lo miraste (otra liquidación o
  una asistencia corregida). Ya se volvió a calcular: revisalo y confirmá de nuevo.
- **El importe está mal después de confirmar**: anulá el egreso desde **Egresos**, que libera las
  clases, y volvé a liquidar.
- **«No hay clases para liquidar a este colaborador en el período»**: probá otro período o mirá
  **Clases que no entran**.
- **No puedo cambiar los instructores de una clase**: ya tiene honorarios liquidados y cambiarlos
  cambiaría el reparto.
