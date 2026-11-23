---
id: productos-de-pack
titulo: Productos de pack y abonos
grupo: Actividades
rutas: ["/productos", "/pacientes/:personaId/pases"]
permisos: [producto:read]
servicio: ACTIVIDADES
relacionadas: [clases, asistencia, padron, cuenta-corriente, actividades-de-la-sede]
palabras: [pack de clases, abono, pase, créditos, vender pase, saldo del pase, renovar abono, anular pase, abono mensual, producto]
---

## Para qué sirve

Es el catálogo de **packs** y **abonos** que vende la sede. Acá se definen el precio, los créditos
y la vigencia de cada uno.

- Un **pack** son clases (créditos) que valen una cantidad de días desde la compra, para una
  actividad que cobra **POR_PACK**.
- Un **abono** cubre un período que se elige al venderlo, con un tope de asistencias, para una
  actividad que cobra **POR_ABONO**.
- Un **pase** es lo que tiene la persona después de comprar: su pack o abono con su saldo.

> [!importante]
> Crear un producto no le da saldo a nadie. El saldo nace al **vender un pase** a una persona,
> desde **Pacientes**, con **Pases** en el menú :icono[mas]: de su fila.

## Tareas

### Crear un producto

![El panel Nuevo producto, con el tipo, la actividad, el precio y el botón Crear producto marcados](captura:productos-de-pack/nuevo-producto)

1. Entrá a :icono[actividades]: **Actividades → Productos de pack** y tocá :icono[agregar]:
   **Nuevo producto**.
2. Elegí el **Tipo**: **Pack de creditos** o **Abono por periodo**.
3. Elegí la **Actividad**. Sólo aparecen las actividades activas que cobran con ese esquema.
4. Escribí el **Nombre** y, si querés, una **Descripción (opcional)**. Completá los **Creditos**
   (en un abono, **Asistencias por periodo**).
5. Poné el **Precio** y la **Moneda** (tres letras, como ARS).
6. Completá los **Dias de vigencia** (en un abono, **Dias maximos del periodo**), **Se vende
   desde** y, si tiene fin, **Se vende hasta (opcional)**.
7. Tocá **Crear producto**.

### Editar un producto

1. Buscá el producto en la tabla. Podés escribir en **Buscar por nombre o actividad...**.
2. Tocá :icono[mas]: en su fila y elegí **Editar**.
3. Cambiá lo que haga falta y tocá **Guardar cambios**.

El tipo y la actividad no se cambian. Los pases ya vendidos conservan los créditos, el precio y la
vigencia con los que se vendieron.

### Dar de baja un producto

1. Tocá :icono[mas]: en la fila del producto y elegí **Dar de baja**.
2. Escribí el **Motivo de la baja**.
3. Tocá **Dar de baja**.

El producto deja de venderse. Los pases ya vendidos conservan su saldo y su vigencia, y las clases
los siguen consumiendo. Para volver a verlo en la tabla, marcá **Mostrar también los dados de
baja**.

### Vender un pase a una persona

![La tarjeta Vender un pase, con el producto y el botón Vender pase marcados](captura:productos-de-pack/vender-pase)

1. Entrá a :icono[pacientes]: **Pacientes → Padrón**, tocá :icono[mas]: en la fila de la persona y
   elegí **Pases**.
2. En **Vender un pase**, elegí el **Producto**.
3. Si es un abono, completá **Periodo desde** y, si querés, **Periodo hasta (opcional)**. Podés
   marcar **Renovar automaticamente**.
4. Tocá **Vender pase**.

Vender crea el pase con sus créditos y **una deuda** a nombre de la persona. **No cobra**: el pago
se registra después, desde **Cuenta corriente**.

### Ver el saldo y los movimientos de un pase

1. En los **Pases** de la persona, mirá la columna **Saldo** (por ejemplo, 6 de 8) y la
   **Vigencia**.
2. Tocá **Ver movimientos** para ver cada compra, consumo, ajuste, devolución o vencimiento.

Cada asistencia a una clase de la actividad consume un crédito. Si la asistencia se corrige a
ausente o la clase se cancela, el crédito vuelve.

### Ajustar, renovar o anular un pase

1. En los **Pases** de la persona, tocá :icono[mas]: en la fila del pase.
2. Elegí **Ajustar saldo**, escribí los **Creditos a sumar o restar** (2 suma dos, -1 resta uno) y
   el **Motivo**, y tocá **Ajustar saldo**. No toca la deuda ni la caja.
3. En un abono, **Activar renovacion** o **Desactivar renovacion** prende o apaga la venta
   automática del período siguiente.
4. Para dejarlo sin efecto, elegí **Anular**, escribí el **Motivo de la anulacion** y tocá
   **Anular pase**.

> [!importante]
> Anular un pase descarta su saldo y anula también la deuda de la venta. Es la única forma de
> anular esa deuda: desde la cuenta corriente no se puede.

## Qué significa cada estado

- **Vigente**: el producto se puede vender dentro de sus fechas.
- **Dado de baja**: ya no se vende. Muestra el motivo debajo.

Cada pase vendido tiene su propio estado:

- **Activo**: tiene créditos y se puede usar dentro de su vigencia.
- **Agotado**: se usaron todos los créditos.
- **Vencido**: terminó su vigencia; los créditos que quedaban se pierden.
- **Anulado**: quedó sin efecto, con su motivo.

## Problemas comunes

- **No aparece la actividad en la lista**: la actividad tiene que estar activa y declarar el
  esquema de cobro **POR_PACK** o **POR_ABONO**. Se configura en **Servicios**, en la actividad.
- **«Ese producto no se puede vender hoy»**: está fuera de las fechas **Se vende desde** y **Se
  vende hasta**. Revisalas en **Editar**.
- **«Esta persona ya tiene ese abono en un periodo que se superpone»**: elegí otro **Periodo
  desde**.
- **«Esta sede no tiene productos vigentes para vender»**: primero creá el producto acá.
- **No veo Nuevo producto ni Editar**: hace falta el permiso para gestionar productos. Pedíselo a
  quien administra el centro.
