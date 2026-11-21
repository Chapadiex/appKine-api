---
id: recepcion-del-dia
titulo: Recepción del día
grupo: Atención
rutas: ["/agenda/recepcion"]
permisos: [turno:read]
relacionadas: [agenda, atender-sin-turno, atencion, caja-diaria, ficha-de-la-persona, clases]
palabras: [check-in, llegada, mostrador, sala de espera, quién viene hoy, registrar llegada, validar cobertura, llamar paciente, particular, prepago]
---

## Para qué sirve

Es la lista del mostrador: quién viene hoy a **la sede en la que estás** y en qué punto está.
Llegó, se validó cómo se atiende, espera en la sala o ya lo llamaron.

> [!importante]
> Ninguno de estos pasos registra la atención. La recepción sigue la llegada al mostrador; la
> **sesión** clínica la abre el profesional desde la pantalla de atención.

La lista **incluye los turnos cancelados**: si alguien se presenta con un turno cancelado, ves el
motivo y se lo podés explicar.

## Tareas

### Seguir la llegada de un paciente

![La tabla de turnos del día, con la acción de la fila y el menú de más acciones marcados](captura:recepcion-del-dia/acciones-de-la-fila)

1. Entrá a :icono[operacion]: **Atención → Recepción del día** y buscá al paciente en la tabla.
2. Tocá el botón de la fila. Muestra el paso que sigue, en este orden: **Registrar la llegada**,
   **Validar cobertura**, **Pasar a espera** y **Llamar**.
3. Las demás acciones están en el menú **⋯** de la fila.

La hora de llegada la pone el sistema. Tocar dos veces **Registrar la llegada** no duplica nada.

### Encontrar un turno en la lista

![La barra de la recepción, con la búsqueda, los filtros por estado y el botón Actualizar la lista marcados](captura:recepcion-del-dia/barra-de-la-recepcion)

1. Escribí el nombre o el documento en **Buscar paciente o documento**.
2. Filtrá por estado de la recepción: **Todos**, **Sin llegar**, **En mostrador**, **En espera**,
   **Llamados** o **Retirados o cerrados**. Cada uno muestra cuántos hay.
3. Tocá **Actualizar la lista** para traer los últimos cambios. La lista también se actualiza sola
   cada minuto.

Para ver otro día, usá :icono[chevron-izquierda]:, :icono[chevron-derecha]: o **Hoy**. En
:icono[filtro]: **Filtros** podés elegir una **Prestación**.

### Atender a alguien como Particular

1. En el menú **⋯** de la fila, tocá **Atender como Particular**.
2. Escribí el **Motivo (obligatorio)**.
3. Tocá **Atender como Particular**.

Queda escrito en la recepción. No cambia la cobertura de la ficha del paciente.

### Registrar que se fue sin ser atendido

1. En el menú **⋯** de la fila, tocá **Se retiro sin ser atendido**.
2. Escribí el **Motivo (obligatorio)** y tocá **Registrar el retiro**.

La llegada queda registrada. El turno ya no se puede marcar ausente ni mover, pero la atención se
puede abrir igual.

### Corregir una llegada cargada por error

1. En el menú **⋯** de la fila, tocá **Anular la llegada**.
2. Escribí un motivo si querés y tocá **Anular la llegada**.

> [!importante]
> Anular dice que el check-in fue un error y la llegada no vale. Queda en el historial y no se
> puede anular dos veces.

### Cobrar un prepago

Si la prestación exige un anticipo, la fila muestra **Prepago pendiente**. Tocá **Registrar
prepago** para ir al cobro. Se puede cobrar antes de que llegue el paciente. El prepago avisa,
pero no impide atender.

Cancelar, reprogramar o ver el historial se hace desde **Ver el turno**, en el menú **⋯**.

## Qué significa cada estado

La columna **Turno** es la reserva; la columna **Recepción** es el paso en el mostrador.

- **Llegó**: falta validar cómo se atiende.
- **Validada**: ya se sabe cómo se atiende (con cobertura o Particular).
- **Observada**: la validación encontró algo, por ejemplo una orden que falta. Advierte, pero no
  impide pasar a espera.
- **En espera**: llegó y aguarda ser llamado.
- **Llamada**: lo llamaron desde la sala. La atención es otro registro.
- **Se retiró**: se fue sin ser atendido. La llegada consta.
- **Anulada**: el check-in fue un error y la llegada no vale.
- **Cerrada**: el turno se canceló con la persona presente.
- **Sin recepción** / **Todavía no llegó**: el paciente aún no pasó por el mostrador.

## Problemas comunes

- **«Solo podés mirar la lista»**: registrar llegadas necesita el permiso para gestionar turnos en
  esta sede. Pedíselo a quien administra el centro.
- **No veo un paciente que dice tener turno**: revisá el día y que no tengas un filtro activo.
  **Limpiar filtros** los saca.
- **Las horas dicen «UTC»**: no se pudo averiguar la zona horaria de la sede. Avisá a quien
  administra el centro.
- **Un paciente viene sin turno**: tocá **Atender sin turno** arriba a la derecha.
