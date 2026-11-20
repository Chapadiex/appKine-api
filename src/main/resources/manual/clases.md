---
id: clases
titulo: Clases
grupo: Actividades
rutas: ["/clases", "/clases/nueva", "/clases/:claseId"]
permisos: [clase:read]
servicio: ACTIVIDADES
relacionadas: [asistencia, productos-de-pack, actividades-de-la-sede, espacios-y-boxes, honorarios]
palabras: [clase grupal, programar clase, inscribir, cupo, lista de espera, reprogramar clase, cancelar clase, yoga, pilates, grilla semanal]
---

## Para qué sirve

Muestra las clases grupales de **la sede en la que estás**, por semana, en lista o por mes. Desde
acá se programan clases nuevas y, al abrir una, se inscribe gente, se ve la lista de espera, se
reprograma o se cancela.

Una clase es **un evento único con su cupo**: no es un turno por persona. Cada clase tiene su
color, el mismo en todos sus días, y las canceladas se ven tachadas.

## Tareas

### Ver las clases de la semana

1. Entrá a :icono[actividades]: **Actividades → Clases**.
2. Con :icono[chevron-izquierda]: y :icono[chevron-derecha]: pasás de semana; **Hoy** te trae de
   vuelta a la actual. Tocando la fecha elegís cualquier día en el calendario.
3. Cambiá la vista con **Semana**, **Lista** o **Mes**. En **Mes**, tocar un día abre su semana.
4. Para achicar la lista, escribí en **Buscar clase, profesional o lugar…** o filtrá por estado
   (**Programadas**, **En curso**, **Realizadas**, **Canceladas**). En :icono[filtro]: tenés
   **Profesional**, **Actividad** y **Lugar**.

Cada tarjeta muestra el horario, quién la dicta, el lugar y los lugares ocupados sobre el cupo
(por ejemplo, 8/10). Cuando no queda lugar dice **Completa**.

### Programar una clase

![El formulario Programar clase, con la actividad, la hora de inicio, el cupo y el botón final marcados](captura:clases/programar-clase)

1. En **Clases**, tocá :icono[agregar]: **Programar clase**.
2. Elegí la **Actividad**.
3. En **¿Cuántas clases?** elegí **Una clase** (un día puntual) o **Todas las semanas** (los días
   que marques, entre **Desde** y **Hasta**).
4. Completá la fecha o los **Días de la semana**, la **Hora de inicio** y la **Hora de fin**. Si
   la actividad lo pide, elegí también el **Profesional a cargo**.
5. Poné el **Cupo** y, si querés, el **Box**. Si no elegís box y la actividad lo necesita, el
   sistema toma uno libre.
6. Revisá la frase de resumen y tocá **Programar clase** (o **Programar clases**).

Fechas y horas van siempre en la zona de la sede.

> [!importante]
> Con **Todas las semanas**, cada fecha se revisa por separado. Si un día cae feriado o el box
> está ocupado, ese día no se programa y te lo decimos con su motivo; los demás sí se programan.

### Inscribir a una persona en una clase

![El panel Inscribir a una persona, con la opción de lista de espera y el botón Inscribir marcados](captura:clases/inscribir)

1. Abrí la clase: tocá su tarjeta en la semana o **Ver clase** en la lista.
2. En **Inscribir a una persona**, escribí documento, apellido o nombre en el buscador del padrón.
3. Tocá a la persona en la lista. Si te equivocaste, tocá **Elegir otra**.
4. Si querés, marcá **Si no hay lugar, anotarla en la lista de espera**.
5. Tocá **Inscribir**.

Inscribir **reserva un lugar**: no registra que la persona haya venido. Eso se hace al tomar
asistencia. Si la clase está llena, aparece **Anotar en la lista de espera**.

### Confirmar o dar de baja una inscripción

1. En la clase, buscá a la persona en **Participantes**.
2. Tocá :icono[mas]: en su fila.
3. Elegí **Confirmar** o **Dar de baja**.
4. Para dar de baja, escribí el **Motivo de la baja** y confirmá con **Dar de baja**.

Dar de baja libera el lugar: si había lista de espera, entra la primera persona de la cola. Para
sacar a alguien de la cola, tocá **Sacar de la espera** en **Lista de espera**.

### Reprogramar o cancelar una clase

1. Abrí la clase y tocá :icono[mas]: en la cabecera.
2. Para moverla, elegí **Reprogramar**, cambiá **Fecha**, **Hora de inicio**, **Hora de fin**,
   **Cupo** o **Box**, y tocá **Guardar el cambio**. Si te arrepentiste, **Volver sin cambiar**.
3. Para suspenderla, elegí **Cancelar la clase**, escribí el **Motivo de la cancelacion** y tocá
   **Cancelar la clase**.

Reprogramar mantiene la misma clase, con sus inscriptos y su historial, y cada inscripto recibe un
aviso del cambio.

> [!importante]
> Cancelar la clase cancela todas sus inscripciones y avisa a cada inscripto. Sólo se puede
> reprogramar, cancelar o inscribir mientras la clase está **Programada**.

## Qué significa cada estado

- **Programada**: todavía no empezó. Admite inscribir, reprogramar y cancelar.
- **En curso**: se inició desde **Tomar asistencia** y se está tomando lista.
- **Realizada**: ya se cerró. Las asistencias se pueden corregir con motivo.
- **Cancelada**: se suspendió con un motivo. Se sigue viendo tachada para que quede el rastro.

Cada inscripción también tiene su estado: **Reservada**, **Confirmada**, **En lista de espera**,
**Asistio**, **Ausente** o **Cancelada**.

## Problemas comunes

- **«Esta sede todavía no tiene actividades grupales»**: falta cargar el lugar (un box con su
  capacidad), la actividad de modalidad grupal en el catálogo y publicarla en la sede.
- **«Ese horario no está libre»**: el box o el profesional ya tienen un turno u otra clase que se
  cruza. Elegí otro horario, otro box u otro profesional.
- **«Ese cupo no se puede usar»**: el cupo no puede superar la capacidad de la actividad ni la del
  box, ni quedar por debajo de los ya inscriptos.
- **«La clase no tiene lugar»**: está completa. Anotá a la persona en la lista de espera.
- **No veo Programar clase ni Reprogramar**: hace falta el permiso para gestionar clases. Pedíselo
  a quien administra el centro.
