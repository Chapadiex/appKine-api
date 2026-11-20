---
id: cuenta-corriente
titulo: Cuenta corriente del paciente
grupo: Economía
rutas: ["/pacientes/:personaId/cuenta-corriente", "/pacientes/:personaId/cuenta-corriente/cobrar", "/pacientes/:personaId/cuenta-corriente/cobros"]
permisos: [cobro:register]
relacionadas: [padron, caja-diaria, presentaciones, productos-de-pack, recepcion-del-dia]
palabras: [deuda del paciente, cuánto debe, cobrar, registrar cobro, comprobante, anular cobro, saldo a favor, anticipo, reintegro, coseguro]
---

## Para qué sirve

Muestra lo que una persona **debe en todo el centro**, no sólo en esta sede, y desde acá se le
cobra.

> [!importante]
> **Deuda, cobro y caja son tres cosas.** La **deuda** nace sola al cerrar una atención, al
> asistir a una clase que se cobra por clase o al vender un pase. El **cobro** es la plata que
> entra y se aplica a esa deuda. La **caja** es el cajón de efectivo del día. Por eso los cobros
> se ven en otra pantalla y no como una deuda negativa.

En las prestaciones con cobertura acá se ve sólo el **coseguro**, que es lo que paga la persona.
La parte de la obra social se reclama en **Presentaciones**.

## Tareas

### Abrir la cuenta corriente de una persona

1. Entrá a :icono[pacientes]: **Pacientes → Padrón** y buscá a la persona.
2. Tocá :icono[mas]: en su fila y elegí **Cuenta corriente**.

La tabla muestra cada deuda con su **Importe**, su **Saldo** y su **Estado**. El **Precio al
devengar** es el que regía ese día: cambiar el precio de la actividad después no cambia una deuda
ya generada. La pantalla no muestra un total a propósito.

### Registrar un cobro

![El formulario del cobro, con una deuda elegida, el medio de pago y el importe recibido marcados](captura:cuenta-corriente/registrar-cobro)

1. En la cuenta corriente, tocá :icono[agregar]: **Registrar un cobro**.
2. En **Deudas a las que se imputa**, marcá las deudas que paga. Se propone el saldo completo;
   podés bajarlo en **Importe a imputar a esta deuda**.
3. En **Cómo entró la plata**, elegí el **Medio de pago**.
4. Escribí el **Importe recibido**. **Completar con el total imputado** lo llena solo. Si pagó con
   dos medios, tocá **Agregar otro medio de pago**.
5. Revisá **Totales**: **Imputado a deuda** y **Recibido por medios** tienen que dar lo mismo.
   Tocá **Registrar el cobro**.

Sale un comprobante con número correlativo por sede: es lo que se lleva el paciente. El efectivo
necesita la caja abierta. Un pago parcial deja la deuda **Pagada en parte**.

### Ver, anular o reintegrar un cobro

1. En la cuenta corriente, tocá **Ver los cobros registrados**.
2. Tocá **Ver el comprobante** para ver cómo entró la plata y contra qué deudas se aplicó.
3. Para dejarlo sin efecto, tocá :icono[mas]: y elegí **Anular**. Escribí el **Motivo de la
   anulacion** y tocá **Anular el cobro**.
4. Para devolver plata que quedó a favor, elegí **Reintegrar saldo a favor**, completá el
   **Importe a devolver**, **Por donde sale la plata** y el **Motivo**, y tocá **Reintegrar**.

Anular no borra el comprobante: queda **Anulado**. Cada deuda recupera su saldo y los movimientos
de caja se revierten **hoy**; si entró en efectivo, hace falta la caja abierta y la plata en el
cajón.

### Anular una deuda

1. En la cuenta corriente, tocá **Anular** en la fila de la deuda.
2. Escribí el **Motivo de la anulacion**.
3. Tocá **Anular la deuda**.

La deuda no se borra: queda **Anulada** con su motivo.

> [!importante]
> Una deuda que ya tiene cobros no se anula: lo que corresponde es una devolución, que todavía se
> resuelve por caja. La deuda de un pase vendido se anula desde los pases de la persona, con
> **Anular desde los pases**: se anulan el pase y la deuda juntos.

### Imputar un saldo a favor

1. En **Saldo a favor**, tocá **Imputar a deudas** en el comprobante.
2. Escribí cuánto aplicar a cada deuda; las que quedan vacías no se tocan.
3. Tocá **Imputar**.

El saldo a favor (anticipos y sobrantes) sólo se aplica a deudas de la misma sede y la misma
moneda. No mueve la caja.

## Qué significa cada estado

- **Pendiente**: la deuda no tiene cobros.
- **Pagada en parte**: tiene cobros y todavía queda saldo.
- **Pagada**: el saldo es cero.
- **Anulada**: quedó sin efecto, con su motivo.
- **Vigente** / **Anulado**: el estado de cada cobro en **Cobros registrados**.

## Problemas comunes

- **«Esta persona no tiene ninguna deuda registrada»**: las deudas se generan al cerrar una
  atención o al vender un pase. No se cargan a mano.
- **«Revisar: la práctica facturada no está habilitada en la actividad»**: es una alerta, no un
  rechazo. La deuda existe y se cobra igual; conviene revisar la configuración de la actividad.
- **«La caja de esta sede no esta abierta»**: el efectivo necesita la caja abierta. Abrila en
  **Caja diaria** o elegí otro medio.
- **«El servidor sumo los importes y no dan el total declarado»**: lo imputado y lo recibido no
  coinciden. Revisá los importes.
- **La deuda ya no tiene el saldo que figuraba**: alguien cobró antes. Tocá **Recargar los saldos
  y volver a armar el cobro**.
- **No veo Registrar un cobro**: hace falta el permiso para registrar cobros.
