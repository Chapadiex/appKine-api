---
id: convenios
titulo: Convenios de la sede
grupo: Contratación
rutas: ["/contratacion", "/contratacion/convenios", "/contratacion/convenios/:convenioId/aranceles", "/contratacion/convenios/:convenioId/aranceles/importar"]
permisos: [convenio:read]
servicio: KINESIOLOGIA
relacionadas: [financiadores, arancel-efectivo, catalogo-clinico, actividades-de-la-sede, presentaciones]
palabras: [convenio, arancel, coseguro, obra social, prepaga, precio por práctica, renovar convenio, importar aranceles, planilla, nomenclador]
---

## Para qué sirve

Un **convenio** es lo que **esta sede** acordó con un **plan** de un financiador: desde cuándo,
hasta cuándo, cómo se liquida y qué requisitos pide. De cada convenio cuelgan los **aranceles**:
lo que **pone el financiador** por cada práctica. El **coseguro** (lo que paga el paciente) no se
carga: es el precio de la actividad menos lo que pone el financiador, y nunca baja de cero.

> [!importante]
> Sin convenio, todo lo que se atiende en la sede se cobra como particular. Dos sedes de la misma
> organización pueden tener convenios y precios distintos para el mismo plan: es lo normal.

## Tareas

### Dar de alta un convenio (Firmar un convenio)

![El panel Nuevo convenio de esta sede, con el financiador, el plan, el código, la modalidad, la vigencia y el botón Firmar convenio marcados](captura:convenios/firmar-convenio)

1. Entrá a :icono[contratacion]: **Contratación → Convenios de la sede** y tocá :icono[agregar]:
   **Firmar un convenio**.
2. Elegí el **Financiador** y después el **Plan**. El convenio se firma contra un plan, no contra
   el financiador entero.
3. Completá **Código** y **Nombre**. El código no se cambia después.
4. Elegí la **Modalidad de liquidación**: **Por prestación**, **Por sesión**, **Modulo** o
   **Capita**, la que figure en el contrato firmado.
5. Completá **Moneda** (tres letras, como ARS) y **Vigente desde**. **Vigente hasta (opcional)** es
   el último día en que el convenio aplica, inclusive.
6. Tocá **Firmar convenio**.

En **Requisitos y observaciones** podés declarar orden médica, autorización previa, credencial y
tope de sesiones por mes. Hoy se muestran pero no bloquean nada.

### Renovar un convenio que vence

1. En la fila del convenio actual, tocá **Editar**.
2. En **Vigente hasta**, poné el último día en que rige y tocá **Guardar cambios**.
3. Tocá **Firmar un convenio** y cargá el nuevo desde el día siguiente, con el mismo financiador y
   plan.

> [!importante]
> Renovar es **cerrar la vigencia**, no dar de baja. Dos convenios del mismo financiador y plan no
> pueden pisarse en el tiempo.

### Cargar un arancel

![El panel Nuevo arancel, con la práctica, lo que pone el financiador, la fecha de inicio y el botón Cargar arancel marcados](captura:convenios/nuevo-arancel)

1. En la fila del convenio, tocá **Aranceles** y después :icono[agregar]: **Cargar un arancel**.
2. Elegí la **Práctica**. Si el precio vale sólo dentro de una actividad, elegila en **Actividad
   (opcional)**; si no, queda **General**.
3. Completá **Pone el financiador**. El coseguro se calcula solo al cerrar cada sesión: precio de
   la actividad menos lo que pone el financiador. Si el financiador pone lo mismo o más, el
   paciente no paga nada.
4. Completá **Vigente desde**. La vigencia tiene que caer dentro de la del convenio.
5. Tocá **Cargar arancel**.

Para **subir un precio**, no lo edites: cerrale la vigencia al arancel actual y cargá otro desde el
día siguiente. **Editar** es para corregir un número mal escrito.

### Importar aranceles desde una planilla

![El paso 1 de la importación, con el botón Subir un CSV, el cuadro para pegar la planilla y el botón Vista previa marcados](captura:convenios/importar-planilla)

1. En **Aranceles** del convenio, tocá **Importar aranceles**.
2. Elegí **Subir un CSV** (desde Excel, «Guardar como» CSV) o pegá la planilla en **O pegala acá**.
   Si no sabés el formato, tocá **Empezar desde el ejemplo**.
3. Tocá **Vista previa** y revisá cada fila: **Entra** o **No entra**, con el motivo.
4. Tocá **Continuar** y después **Confirmar … aranceles**.

La importación es **todo o nada**: si una sola fila no entra, no se carga ninguna. Sólo agrega
aranceles; no edita ni cierra los vigentes.

### Dar de baja un convenio

1. En la fila, tocá **Dar de baja**.
2. Escribí el **Motivo de la baja** y confirmá.

Es definitivo, sin reactivación. Si sólo querés que deje de aplicarse desde una fecha, usá
**Editar** y cerrá la vigencia.

## Qué significa cada estado

- **Activo y vigente**: aplica en la fecha consultada.
- **Activo, todavía sin vigencia**: está cargado y empieza a aplicarse solo en su fecha de inicio.
- **Activo, vigencia terminada**: sigue activo pero ya pasó su fin. Para que vuelva a aplicar,
  editá el fin de vigencia.
- **Activo, sin vigencia en esa fecha**: la fecha elegida en **Vigencia al día** queda fuera de su
  ventana.
- **Dado de baja**: ya no aplica; lo liquidado sigue explicándose con su copia congelada.

Los aranceles usan los mismos estados.

> [!consejo]
> **Vigencia al día** no filtra: cambia la fecha contra la que se calcula el estado. Sirve para
> ver qué convenio regía el día de una prestación vieja.

## Problemas comunes

- **«Se superpone con otro convenio de esta sede»**: ya hay un convenio del mismo plan en esas
  fechas. Editá el que está, ponele fecha de fin, y cargá el nuevo desde el día siguiente.
- **«Se superpone con otro arancel de esta práctica»**: cerrale la vigencia al arancel actual antes
  de cargar el nuevo.
- **El plan no aparece al firmar**: el financiador no tiene planes activos. Cargale uno desde
  **Financiadores → Planes**.
- **El coseguro sale en cero**: la actividad no tiene precio ese día, o el financiador pone igual o
  más que el precio. Cargale precio a la actividad en **Servicios → Actividades**.
- **No veo Firmar un convenio**: hace falta permiso para administrar convenios. Pedíselo a quien
  administra el centro.
