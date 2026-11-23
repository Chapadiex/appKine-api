---
id: convenios
titulo: Precios del plan
grupo: Contratación
rutas: ["/contratacion/convenios/:convenioId/aranceles", "/contratacion/convenios/:convenioId/aranceles/importar"]
permisos: [convenio:read]
servicio: KINESIOLOGIA
relacionadas: [financiadores, catalogo-clinico, actividades-de-la-sede, presentaciones]
palabras: [precios, tope de sesiones, sesiones por mes, cuánto paga la obra social, arancel, coseguro, obra social, prepaga, precio por práctica, convenio, importar aranceles, planilla, nomenclador]
---

## Para qué sirve

Son los **precios del plan**: lo que **paga la obra social** por cada práctica. Valen para **todas
las sedes** del centro. El **coseguro** (lo que paga el paciente) no se carga: es el precio de la
actividad menos lo que paga la obra social, y nunca baja de cero.

> [!importante]
> Sin precios cargados en el plan, lo que se atiende con esa obra social se cobra como particular.

## Tareas

### Cargar un precio

![El panel Nuevo arancel, con la práctica, lo que pone el financiador, la fecha de inicio y el botón Cargar arancel marcados](captura:convenios/nuevo-arancel)

1. Entrá a :icono[contratacion]: **Contratación → Financiadores**, tocá **Planes** en la obra
   social y después **Precios** en el plan. Ahí tocá :icono[agregar]: **Cargar un arancel**.
2. Elegí la **Práctica**. Si el precio vale sólo dentro de una actividad, elegila en **Actividad
   (opcional)**; si no, queda **General**.
3. Completá **Pone el financiador**: lo que paga la obra social. El coseguro se calcula solo al
   cerrar cada sesión. Si la obra social paga lo mismo o más que el precio de la actividad, el
   paciente no paga nada.
4. Completá **Vigente desde**.
5. Tocá **Cargar arancel**.

Para **subir un precio**, no lo edites: cerrale la vigencia al actual y cargá otro desde el día
siguiente. **Editar** es para corregir un número mal escrito.

### Poner un tope de sesiones por mes

1. En los **Precios** del plan, tocá **Poner un tope** (o **Cambiar tope** si ya tiene uno).
2. Completá **Sesiones por mes que cubre el plan**. Es por paciente y cuenta las sesiones de todas
   las sedes.
3. Tocá **Guardar**.

Pasado el tope, la sesión se cobra entera como particular: el paciente paga el precio de la
actividad y la obra social no la recibe en la liquidación. Al mes siguiente vuelve a cubrir. Para
quitar el tope, dejá el campo vacío y guardá.

### Importar precios desde una planilla

![El paso 1 de la importación, con el botón Subir un CSV, el cuadro para pegar la planilla y el botón Vista previa marcados](captura:convenios/importar-planilla)

1. En los **Precios** del plan, tocá **Importar aranceles**.
2. Elegí **Subir un CSV** (desde Excel, «Guardar como» CSV) o pegá la planilla en **O pegala acá**.
   Si no sabés el formato, tocá **Empezar desde el ejemplo**.
3. Tocá **Vista previa** y revisá cada fila: **Entra** o **No entra**, con el motivo.
4. Tocá **Continuar** y después **Confirmar … aranceles**.

La importación es **todo o nada**: si una sola fila no entra, no se carga ninguna. Sólo agrega
precios; no edita ni cierra los vigentes.

## Qué significa cada estado

- **Activo y vigente**: se aplica en la fecha consultada.
- **Activo, todavía sin vigencia**: empieza a aplicarse solo en su fecha de inicio.
- **Activo, vigencia terminada**: ya pasó su fin.
- **Dado de baja**: ya no se aplica; lo liquidado sigue explicándose con su copia congelada.

## Problemas comunes

- **«Se superpone con otro arancel de esta práctica»**: cerrale la vigencia al precio actual antes
  de cargar el nuevo.
- **No veo Precios en el plan**: el plan está dado de baja. Los planes activos lo muestran en su
  fila.
- **«Este plan todavía no tiene precios cargados»**: los carga quien administra las obras sociales
  del centro; hace falta permiso para administrar convenios.
- **El coseguro sale en cero**: la actividad no tiene precio ese día, o la obra social paga igual o
  más que el precio. Cargale precio a la actividad en **Servicios → Actividades**.
