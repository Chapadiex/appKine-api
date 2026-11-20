---
id: series-de-turnos
titulo: Series de turnos
grupo: Atención
rutas: ["/agenda/series", "/agenda/series/:serieId"]
permisos: [turno:read]
relacionadas: [agenda, caso-clinico, buscar-disponibilidad, recepcion-del-dia]
palabras: [turnos semanales, turnos fijos, serie, varias sesiones, turnos recurrentes, cancelar serie, mover serie, programar sesiones]
---

## Para qué sirve

Lista las series de turnos semanales de **la sede en la que estás**: por ejemplo, «lunes y jueves
a las 10, diez sesiones». Desde acá ves cuánto le queda a cada serie y movés o cancelás varios
turnos de una vez.

Cada turno de una serie sigue siendo una reserva propia: se puede cancelar o mover solo, o junto
con los demás.

## Tareas

### Crear una serie

1. Tocá :icono[agregar]: **Nuevo turno** y, en el panel, **Programar varias sesiones**.
2. Elegí la **Prestación**, el **Profesional**, el **Horario preferido**, la **Fecha inicial**, la
   **Cantidad de sesiones** y los **Días**.
3. Tocá **Buscar disponibilidad**. Cada sesión aparece como **Disponible**, **Horario cambiado** o
   sin lugar, con un horario sugerido si hay.
4. Para las que no tienen lugar, tocá **Usar el sugerido**, **Buscar alternativa** o **Excluir**.
   Los feriados se omiten solos.
5. Tocá **Confirmar** con la cantidad de turnos.

También se programa desde el plan de tratamiento, con **Programar sesiones restantes**.

### Encontrar una serie

![La tabla de series, con el botón Ver serie de la primera fila marcado](captura:series-de-turnos/bandeja)

1. Entrá a :icono[operacion]: **Atención → Series de turnos**. Por defecto se ven las vigentes.
2. Buscá por paciente, documento, actividad o profesional. En :icono[filtro]: **Filtros** podés
   ver **Finalizadas**, **Canceladas** o **Todas**.
3. Tocá **Ver serie** en la fila.

### Cancelar o mover turnos de una serie

![La tabla de turnos de una serie, con el menú de más acciones de una fila marcado](captura:series-de-turnos/turnos-de-la-serie)

1. Abrí la serie.
2. En **Turnos**, tocá el menú **⋯** del turno desde el que querés cambiar.
3. Elegí **Reprogramar**, **Cancelar este** o **Cancelar este y los siguientes**.
4. Revisá la lista de turnos que se moverían o cancelarían, escribí el motivo y confirmá.

Para toda la serie, usá **Reprogramar toda la serie** o **Cancelar toda la serie**, arriba a la
derecha. **Ver o mover** abre el panel de un turno suelto.

### Reprogramar una serie

1. Elegí **Que turnos se mueven**: **Solo este turno**, **Este turno y los siguientes** o **Toda la
   serie**.
2. Elegí el horario nuevo del turno de partida. Los demás se corren lo mismo.
3. Si querés que pasen al profesional del horario elegido, marcá **Pasar los turnos al profesional
   del horario elegido**.
4. Escribí el **Motivo del cambio** y tocá **Mover** con la cantidad de turnos.

> [!importante]
> Se mueven todos o ninguno. Los turnos confirmados vuelven a quedar reservados.

## Qué significa cada estado

- **Vigente**: a la serie le queda al menos un turno pendiente.
- **Finalizada**: ya no le quedan turnos pendientes.
- **Cancelada**: la serie se canceló.

Los turnos de la serie usan los mismos estados que la agenda (**Reservado**, **Confirmado**,
**Ausente**, **Cancelado**, etc.).

## Problemas comunes

- **«No hay ningún turno pendiente en ese alcance»**: los turnos elegidos ya pasaron o están
  cancelados. No hay nada que mover ni cancelar.
- **Aparecen «Fechas omitidas al reservar»**: al crear la serie esas fechas no se reservaron, por
  ejemplo por feriado. Dale esos turnos aparte si hacen falta.
- **«Quedan como estan»**: algunos turnos no se tocan, por ejemplo porque ya se atendieron.
- **No veo los botones de cancelar ni reprogramar**: hace falta el permiso para gestionar turnos en
  esta sede.
