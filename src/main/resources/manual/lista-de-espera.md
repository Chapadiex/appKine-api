---
id: lista-de-espera
titulo: Lista de espera
grupo: Atención
rutas: ["/agenda/lista-de-espera"]
permisos: [turno:read]
relacionadas: [agenda, buscar-disponibilidad, recepcion-del-dia]
palabras: [espera, anotar paciente, sin lugar, avisar cuando se libere, turno liberado, prioridad, cancelación, lista de pendientes]
---

## Para qué sirve

Reúne a quienes esperan lugar para una prestación en **la sede en la que estás**. Se ordena por
prioridad y, a igual prioridad, por quién espera hace más tiempo.

Estar en la lista **no ocupa la agenda**. Asignar crea un turno común, con los mismos controles
que cualquier reserva.

## Tareas

### Anotar a un paciente

![El panel Agregar a la lista de espera, con la prestación, las fechas, la prioridad y el botón Agregar a la lista marcados](captura:lista-de-espera/agregar)

1. Tocá :icono[agregar]: **Agregar a la lista** y elegí al paciente.
2. Elegí la **Prestación** y, si quiere, un **Profesional preferido**.
3. Completá **Fecha desde**, **Fecha hasta**, **Hora desde** y **Hora hasta**, y marcá los **Días
   posibles** (sin marcar, sirve cualquiera).
4. Elegí la **Prioridad**: **Normal**, **Alta** o **Urgente**. Si querés, agregá **Notas**, sin
   información clínica.
5. Tocá **Agregar a la lista**.

> [!consejo]
> También podés anotarlo desde **Nuevo turno** o desde **Buscar disponibilidad** cuando no hay
> lugar: los datos ya cargados pasan solos.

### Darle un turno a alguien de la lista

1. En la fila, tocá **Asignar**.
2. En **Próximos horarios compatibles**, elegí uno.

El turno queda reservado y la entrada pasa a **Asignadas**. Si el horario ya no está, la persona
sigue en la lista.

### Ver quién puede usar un turno que se canceló

Cuando cancelás un turno en la agenda y alguien de la lista puede usar ese horario, aparece un
aviso. Tocá **Ver lista de espera**: arriba se muestra **Compatibles con el horario que se
liberó**, con el teléfono para llamar. Tocá **Asignar** en la persona elegida. **Dejar de
mostrar** oculta ese bloque.

### Sacar a alguien de la lista

1. En el menú **⋯** de la fila, tocá **Descartar**.
2. Escribí un motivo si querés y tocá **Descartar**.

Sale de la lista sin turno. No se borra: queda en las descartadas, con su motivo.

## Qué significa cada estado

- **Activas**: esperan lugar.
- **Asignadas**: ya recibieron un turno. **Ver turno** lo abre.
- **Descartadas**: salieron de la lista sin turno, con su motivo.

## Problemas comunes

- **«Ya pasó el último día que le servía»**: las fechas de la entrada vencieron. Anotalo de nuevo
  con otras fechas o descartalo.
- **«No hay horarios libres que le sirvan»**: debajo se explica hasta qué fecha se buscó. Probá
  más tarde o ampliá lo que le sirve anotándolo de nuevo.
- **«Esta persona todavía no es paciente»**: tocá **Activar como paciente** y seguí.
- **No veo Agregar a la lista ni Asignar**: hace falta el permiso para gestionar turnos en esta
  sede.
