---
id: caja-diaria
titulo: Caja diaria
grupo: Economía
rutas: ["/caja"]
permisos: [caja:operate]
relacionadas: []
palabras: [abrir caja, cerrar caja, arqueo, arquear, efectivo, cajón, jornada, movimiento manual, diferencia de caja]
---

## Para qué sirve

Es el cajón de efectivo de **la sede en la que estás**. Acá lo abrís al empezar el día, anotás lo
que entra y sale a mano, y lo contás (arqueo) al cerrar.

> [!importante]
> La caja **no es la deuda ni los cobros**. Un cobro en efectivo aparece acá como movimiento, pero
> se registra y se anula desde la cuenta corriente del paciente.

## Tareas

### Abrir la caja

![La caja cerrada, con el saldo inicial y el botón Abrir la caja marcados](captura:caja-diaria/abrir-caja)

1. Entrá a :icono[economia]: **Economía → Caja diaria**.
2. En **Saldo inicial contado**, escribí cuánto efectivo hay en el cajón ahora. Cero también vale.
3. Tocá **Abrir la caja**.

La fecha de la jornada la pone el sistema, con el horario de la sede.

### Registrar un movimiento manual

![El formulario Registrar un movimiento manual, con el tipo, el importe y el botón marcados](captura:caja-diaria/registrar-movimiento)

1. Con la caja abierta, buscá **Registrar un movimiento manual**.
2. Elegí si **entró** o **salió** efectivo.
3. Completá el **importe** y el **concepto** (por ejemplo, «cambio para el día» o «compra de
   insumos»).
4. Tocá **Registrar el movimiento**.

> [!consejo]
> Los cobros a pacientes no se cargan acá: se registran desde la cuenta corriente y aparecen solos
> en la caja.

### Revertir un movimiento

1. En **Movimientos de esta jornada**, buscá la fila.
2. Tocá **Revertir**.
3. Escribí el **motivo de la reversión** y tocá **Confirmar la reversión**.

El movimiento no se borra: queda marcado **Revertido** y se suma uno nuevo que lo compensa.

### Arquear y cerrar la caja

![El panel Arquear y cerrar, con el efectivo contado y el botón Cerrar la caja marcados](captura:caja-diaria/arquear-cerrar)

1. Tocá **Arquear y cerrar**.
2. Contá el efectivo del cajón y escribilo en **Efectivo contado**.
3. Mirá la **Diferencia** contra lo que **debería haber en el cajón**. Si no da cero, explicá por
   qué en **Motivo de la diferencia**.
4. Tocá **Cerrar la caja**. Si te arrepentiste, tocá **Seguir con la caja abierta**.

> [!importante]
> Un cierre no se revierte y una caja cerrada no se reabre. Un error se compensa con movimientos en
> la jornada siguiente.

## Qué significa cada estado

- **Abierta**: la jornada está en curso y se pueden registrar movimientos.
- **Cerrada**: la jornada terminó con su arqueo. Ya no admite movimientos.
- **Revertido**: un movimiento anulado por otro que lo compensa. Sigue a la vista para que quede
  el rastro.

## Problemas comunes

- **«La caja está cerrada»**: todavía no se abrió la jornada de hoy. Abrila con el saldo que hay en
  el cajón.
- **El saldo no coincide con lo que cobré con tarjeta**: el cajón cuenta **solo efectivo**. Las
  tarjetas y transferencias se ven aparte, en **Detalle por medio de pago**.
- **No veo la pantalla**: hace falta el permiso para operar la caja. Pedíselo a quien administra el
  centro.
