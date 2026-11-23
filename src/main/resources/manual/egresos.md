---
id: egresos
titulo: Egresos
grupo: Economía
rutas: ["/egresos", "/egresos/:egresoId"]
permisos: [caja:operate]
relacionadas: [caja-diaria, honorarios, reportes]
palabras: [gasto, pago a proveedor, alquiler, sueldos, factura de proveedor, comprobante, pagar egreso, anular pago, salida de dinero]
---

## Para qué sirve

Registra lo que la sede **le debe a otros**: honorarios, sueldos, alquiler, servicios, insumos,
impuestos. Un egreso nace en **borrador**, se **confirma** con su comprobante y se salda con uno o
más **pagos**.

> [!importante]
> Un borrador **no mueve plata**. Sólo el **pago en efectivo** sale del cajón y exige la caja
> abierta. Un pago por transferencia nunca pasa por el cajón.

Arriba se ven los totales de **Este mes**: **Registrado** (lo comprometido), **Pagado**, **Falta
pagar** y **En borrador**.

## Tareas

### Cargar un egreso

![El panel Cargar un egreso, con el beneficiario, la categoría, el importe, el comprobante y el botón marcados](captura:egresos/cargar-egreso)

1. Entrá a :icono[economia]: **Economía → Egresos** y tocá :icono[agregar]: **Registrar egreso**.
2. En **Beneficiario**, elegí **Un colaborador del centro** o **Alguien externo (proveedor,
   locador)** y completá quién es.
3. Elegí la **Categoría** y escribí el **Concepto**.
4. Poné el **Importe total** y la **Moneda**.
5. Si ya lo tenés, completá el **Comprobante** (tipo, número y fecha). Es obligatorio para
   confirmar, no para cargar.
6. Tocá **Cargar el borrador**.

En **Servicio y período liquidado** podés imputarlo a un servicio: se resta de la rentabilidad de
ese servicio. El alquiler o los impuestos van sin imputar.

### Corregir y confirmar un egreso

1. En la tabla, tocá **Abrir el egreso**.
2. Si falta algo, abrí **Concepto, importe, período y comprobante** en **Corregir el borrador**,
   corregí y tocá **Guardar los cambios**.
3. En **Confirmar**, tocá **Confirmar el egreso**.

Confirmar no mueve plata: deja el egreso firme y habilita los pagos. Una vez confirmado ya no se
corrige, y el beneficiario no se cambia nunca (sería otro egreso).

### Registrar un pago

![La tarjeta Registrar un pago, con el importe, el medio y el botón marcados](captura:egresos/registrar-pago)

1. Abrí el egreso confirmado.
2. En **Registrar un pago**, escribí el **Importe del pago**. Puede ser parcial.
3. Elegí el **Medio**.
4. Si es transferencia o recibo, completá la **Referencia (transferencia o recibo)**.
5. Tocá **Registrar el pago**.

Un medio por pago: mitad efectivo y mitad transferencia son dos pagos. Con efectivo, la pantalla
te muestra cuánto debería haber en el cajón.

### Anular un pago o un egreso

1. Abrí el egreso.
2. Para un pago, tocá **Anular el pago** en su fila de **Pagos**. Para el egreso entero, tocá
   **Anular el egreso** en el costado.
3. Escribí el **Motivo de la anulación** y confirmá.

Nada se borra: queda **Anulado** con su motivo. Al anular un pago en efectivo, la plata vuelve al
cajón como reversión en la jornada de hoy y el saldo vuelve al egreso.

> [!importante]
> Un egreso con pagos vigentes no se anula: primero anulá cada pago y después el egreso.

## Qué significa cada estado

- **Borrador**: cargado, todavía no es compromiso. Se puede corregir.
- **Confirmado, a pagar**: firme, con comprobante. Admite pagos hasta que el saldo llegue a cero.
- **Pagado**: el saldo pendiente es cero.
- **Anulado**: quedó sin efecto, con su motivo.

Cada pago aparece como **Vigente** o **Anulado**.

## Problemas comunes

- **No se activa Confirmar el egreso**: falta el tipo o el número de comprobante. Cargalo en el
  borrador y guardá los cambios.
- **«La caja de esta sede no esta abierta»**: el pago en efectivo necesita la caja abierta. Abrila
  en **Caja diaria** o elegí otro medio.
- **«No hay tanto efectivo en el cajon»**: el cajón no puede quedar en negativo.
- **«Ese comprobante de ese beneficiario ya esta cargado en otro egreso»**: revisá el número.
- **No puedo corregir el importe**: el egreso nació de una liquidación de honorarios. Anulalo —eso
  libera las clases— y volvé a liquidar en **Honorarios**.
