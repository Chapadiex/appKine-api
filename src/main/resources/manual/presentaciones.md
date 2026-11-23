---
id: presentaciones
titulo: Presentaciones a financiadores
grupo: Economía
rutas: ["/presentaciones", "/presentaciones/:presentacionId"]
permisos: [cobro:register]
servicio: KINESIOLOGIA
relacionadas: [financiadores, convenios, cuenta-corriente, caja-diaria, reportes]
palabras: [lote, presentar a la obra social, facturar prepaga, débito, rechazo, liquidación del financiador, conciliar, pagada, pendientes de presentar, reparo, observación, obra social]
---

## Para qué sirve

Arma los **lotes** con los que la sede le reclama a una obra social o prepaga (el
**financiador**) las prestaciones de un período, y sigue cada lote hasta que se cobra.

- Un **lote** agrupa las prestaciones de un financiador en un período.
- Un **débito** es lo que el financiador no reconoce al liquidar. La deuda sigue pendiente y se
  puede volver a presentar.
- Una **observación** (reparo) es un problema que impide presentar una prestación, como la falta
  de orden o de autorización.

> [!importante]
> **Presentar no es cobrar.** La deuda de cada prestación se salda recién cuando el lote queda
> pagado, y la plata entra cuando se registra el pago del financiador.

> [!consejo]
> Para saber qué falta liquidar, filtrá por el **Financiador**: arriba aparecen las prestaciones
> **pendientes de presentar** de esa obra social y cuánto suman.

## Tareas

### Abrir un lote nuevo

![El panel Abrir un borrador, con el financiador, el período y el botón marcados](captura:presentaciones/abrir-lote)

1. Entrá a :icono[economia]: **Economía → Presentaciones** y tocá :icono[agregar]: **Nuevo lote**.
2. Elegí el **Financiador**.
3. Completá **Período desde** y **Período hasta**.
4. Revisá la **Moneda**.
5. Tocá **Abrir el borrador**.

El período filtra qué prestaciones se pueden agregar. Dos lotes del mismo período son válidos, por
ejemplo para reenviar las debitadas.

### Armar y presentar el lote

![La tarjeta Revisión antes de presentar, con el botón Confirmar y presentar marcado](captura:presentaciones/revision)

1. En la tabla de lotes, tocá **Abrir el lote**.
2. En **Prestaciones elegibles**, tocá **Agregar** en cada prestación que quieras reclamar. Para
   sacar una, tocá **Quitar** en **Prestaciones del lote**.
3. Mirá **Revisión antes de presentar**. Si hay observaciones, corregilas en la ficha del paciente
   o quitá esas prestaciones, y tocá **Volver a revisar**. Cuando no queden, tocá **Confirmar y
   presentar**.

La orden y la autorización que exigía el convenio son obligatorias para presentar. La credencial
vencida o sin número de afiliado es sólo un aviso: no impide confirmar, pero el financiador puede
debitar esa prestación.

### Registrar la factura del lote (opcional)

1. Abrí el lote presentado.
2. En **Factura del lote (opcional)**, completá el **Número de factura** y la **Fecha**.
3. Tocá **Registrar la factura**.

### Registrar un débito

1. Cuando llega la liquidación del financiador, abrí el lote.
2. En **Prestaciones del lote**, tocá **Registrar débito** en la prestación que no reconoció.
3. Completá el **Importe debitado** y el **Motivo informado por el financiador**.
4. Tocá **Confirmar el débito**.

### Registrar el pago y marcar el lote como pagado

1. En **Pago del financiador**, completá el **Importe**, el **Medio**, la **Fecha del pago** y, si
   querés, la **Referencia**.
2. Tocá **Registrar el pago**.
3. Cuando el **Saldo** llegue a cero, tocá **Marcar como pagada**.

Un pago en efectivo entra a la caja del día y necesita la caja abierta; transferencia o tarjeta no
tocan el arqueo. Un pago mayor que el saldo se rechaza.

> [!consejo]
> **Exportar CSV**, en la cabecera del lote, descarga las prestaciones para mandarlas o revisarlas
> fuera del sistema. Cada renglón trae paciente, documento, número de afiliado, plan, fecha,
> práctica con su código y autorización, y al final un resumen por paciente con sus sesiones.

## Qué significa cada estado

Del lote:

- **Borrador**: se está armando. Se agregan y quitan prestaciones.
- **Presentada**: se le envió al financiador.
- **Presentada con factura**: se le envió y ya tiene la factura cargada. La factura es opcional: débitos y pagos se registran igual sin ella.
- **Pagada**: el saldo llegó a cero; todo lo presentado quedó pagado o debitado con motivo.
- **Anulada**: se descartó el borrador; sus prestaciones volvieron a la bandeja.

De cada prestación: **Incluida**, **Aceptada**, **Aceptada en parte**, **Debitada**, **Debitada en
parte** o **Anulada**.

## Problemas comunes

- **No aparece Nuevo lote, o dice que no tenés permiso para consultar los financiadores**: hace
  falta además el permiso de convenios. Los lotes existentes se ven igual.
- **«No hay prestaciones para agregar en este período»**: no hay deudas a cargo de ese financiador
  en el período, o ya están en otro lote vivo.
- **No se puede cerrar el lote**: todavía queda saldo. Todo lo presentado tiene que quedar
  explicado por débitos o pagos.
- **«Ese número de factura ya esta en otro lote del mismo financiador»**: revisá el número.
- **Descartar el borrador**: queda anulado con su motivo y no se deshace. Las prestaciones vuelven
  a estar disponibles para otro lote.
