---
id: horarios-de-profesionales
titulo: Horarios de profesionales
grupo: Espacios y horarios
rutas: ["/horarios", "/horarios/efectivo", "/horarios/cambios"]
permisos: [colaborador:read]
relacionadas: [calendario-de-la-sede, agenda, colaboradores, buscar-disponibilidad]
palabras: [horario, horario semanal, bloque, disponibilidad del profesional, días de atención, horario efectivo, cambios de agenda, licencia, cargar horario]
---

## Para qué sirve

Acá se carga **en qué días y horas atiende cada profesional** en la sede, y se consulta cómo queda
su agenda real. Tiene tres pestañas:

- **Horario semanal**: la regla que se repite cada semana.
- **Horario efectivo**: lo que de verdad atiende en fechas concretas, ya descontados feriados,
  cierres y la vigencia de su vínculo.
- **Cambios**: el rastro de todo lo que se tocó en su agenda.

> [!importante]
> El horario es **disponibilidad**, no turnos. Dice cuándo se pueden dar turnos; el turno es la
> reserva de un paciente dentro de ese horario. Además, el **horario general de la sede** limita
> todo: no se ofrecen turnos fuera de él aunque el profesional tenga horario cargado.

## Tareas

### Cargar el horario de un profesional

![El panel Agregar horario, con la selección rápida de días, las horas Desde y Hasta, el inicio de la vigencia y el botón Guardar horario marcados](captura:horarios-de-profesionales/agregar-horario)

1. Entrá a :icono[horarios]: **Espacios y horarios → Horarios de profesionales**, elegí al
   profesional en **Elegí un profesional** y tocá :icono[agregar]: **Agregar horario**.
2. En **Días de atención**, elegí **Lunes a viernes**, **Lunes a sábado**, **Toda la semana** o
   **Personalizado** para marcar los días uno por uno.
3. En **Horario**, elegí **Desde** y **Hasta**.
4. En **Vigencia**, dejá **Desde hoy** o tocá **Elegir fecha de inicio**. Para el fin, **Sin fecha
   de finalización** o **Hasta una fecha**.
5. Tocá **Guardar horario**.

Se carga el mismo horario en cada día elegido. Si un día no se puede guardar, no se guarda ninguno.

> [!consejo]
> La hora de fin es exclusiva: un bloque de 09:00 a 12:00 y otro de 12:00 a 15:00 no se pisan. Es
> la forma correcta de cargar mañana y tarde.

### Cambiar un bloque de horario

1. Elegí al profesional y buscá el bloque en el día que corresponde.
2. Tocá **Editar**.
3. Cambiá el día, las horas o la vigencia.
4. Revisá **Turnos afectados**: si el cambio quita horario, esos turnos quedan en conflicto. No se
   cancelan ni se mueven.
5. Tocá **Guardar los cambios** (o **Guardar igual** si hay turnos afectados).

### Dejar de atender en una franja desde una fecha

1. Tocá **Editar** en el bloque.
2. En **Vigencia**, elegí **Hasta una fecha** y poné el primer día en que ya no rige.
3. Guardá.

Así el horario viejo sigue explicando los turnos que ya se dieron. **Dar de baja** un bloque no se
deshace y se usa sólo para borrar un error de carga.

### Ver cuándo atiende de verdad en unas fechas

1. Abrí la pestaña **Horario efectivo**.
2. Elegí el **Profesional**, completá **Desde** y **Hasta (exclusivo)**.
3. Tocá **Resolver el horario**.

Cada franja dice qué regla la produjo, y cada día vacío dice por qué: feriado, cierre, vínculo
fuera de vigencia o que ese día no trabaja.

### Ver quién cambió la agenda

1. Abrí la pestaña **Cambios** y elegí el **Profesional**.
2. Si querés, filtrá por **Qué cambió**, **Cambios desde** y **Hasta (incluido)**, y tocá **Aplicar
   filtros**.

Cada fila dice quién lo hizo, cuándo y por qué. Desde un cierre de sede podés tocar **Reubicar
turnos**.

## Problemas comunes

- **El profesional no aparece en la lista**: sólo salen los que tienen vínculo vigente que los
  habilite en esta sede. Revisalo en **Colaboradores**: puede estar acotado a otra sede, suspendido
  o vencido.
- **«Este es el bloque con el que se pisa lo que pediste.»**: el horario nuevo se cruza con otro
  bloque del mismo profesional. Ajustá las horas o cerrá la vigencia del bloque viejo.
- **Las franjas quedan fuera del horario de la sede**: el horario del profesional tiene que caber
  en el horario general. Revisalo en **Calendario de la sede**.
- **Cargué el horario y no salen turnos un día**: mirá **Horario efectivo**; ahí dice si hay un
  feriado o un cierre que lo tapa.
- **No veo Agregar horario**: estás mirando la agenda de otro profesional sin permiso para
  administrarla.
