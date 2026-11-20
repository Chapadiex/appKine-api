---
id: agenda
titulo: Agenda
grupo: Atención
rutas: ["/agenda", "/agenda/turnos/:turnoId"]
permisos: []
relacionadas: [buscar-disponibilidad, recepcion-del-dia, series-de-turnos, lista-de-espera, horarios-de-profesionales, atencion]
palabras: [dar turno, reservar turno, agenda del día, calendario de turnos, sobreturno, reprogramar turno, cancelar turno, bloquear agenda, turnos de la semana, grilla]
---

## Para qué sirve

Es la agenda de **la sede en la que estás**: los turnos del día, la semana o el mes, en columnas
por profesional o por box. Desde acá das turnos, los movés, los cancelás y bloqueás horarios.

> [!importante]
> Un **turno** es la reserva del horario. La **sesión** es la atención clínica, y se registra
> aparte, en la pantalla de atención. Que un turno diga **Confirmado** no quiere decir que el
> paciente vino ni que pagó.

## Tareas

### Moverte por la agenda

![La barra de la agenda, con el período, el navegador de fecha y el botón Filtros marcados](captura:agenda/barra-de-la-agenda)

1. Entrá a :icono[operacion]: **Atención → Agenda**. Elegí cómo verla: **Día**, **Semana** o
   **Mes**.
2. Usá :icono[chevron-izquierda]: y :icono[chevron-derecha]: para ir para atrás o para adelante,
   **Hoy** para volver al día de hoy, o tocá la fecha :icono[calendario]: para elegir un día.
3. Tocá :icono[filtro]: **Filtros** para ver solo un **Profesional**, una **Prestación** o un
   **Estado**. **Limpiar** los saca todos.

En la vista **Día** podés elegir columnas por profesional (:icono[pacientes]:) o por box
(:icono[edificio]:). En **Mes** cada día muestra cuántos turnos tiene; tocalo para abrir ese día.
Para encontrar a alguien, escribí su nombre o DNI en **Buscar paciente o DNI**.

### Dar un turno

![El panel Nuevo turno, con el paciente, la prestación, los horarios disponibles y el botón Confirmar turno marcados](captura:agenda/nuevo-turno)

1. Tocá un horario libre de la grilla (al pasar el mouse dice **Dar turno**), o tocá
   :icono[agregar]: **Nuevo turno** arriba a la derecha.
2. Buscá y elegí al paciente.
3. Elegí la **Prestación** y, si hace falta, el **Profesional** (o dejá **Cualquier
   profesional**).
4. Elegí un horario en **Horarios disponibles**, o tocá **Primer disponible**. Si tocaste una
   celda, el horario ya viene cargado.
5. Tocá **Confirmar turno**.

En **Más opciones** podés anotar la cobertura con la que se piensa atender y **Observaciones**.
Las observaciones se ven en la agenda: no escribas información clínica.

> [!consejo]
> Si el paciente necesita varias sesiones iguales por semana, tocá **Programar varias sesiones**
> dentro del mismo panel: se arma una serie de turnos de una vez.

### Ver un turno y registrar lo que pasó

![El panel de un turno, con las secciones Seguimiento y Gestión y el botón Cancelar turno marcados](captura:agenda/detalle-de-turno)

1. Tocá el turno en la grilla. Se abre su panel con el cuándo, la prestación, quién atiende y dónde.
2. En **Seguimiento** están las acciones del momento: **Confirmar**, **Paciente presente**,
   **Iniciar atención** o **Marcar ausente**.
3. En **Gestión** podés **Reprogramar**, **Cambiar de sede**, **Ver historial**, **Ver paciente** y,
   si tiene, **Ver caso**, **Ver plan** o **Ver la serie**.
4. Para cancelarlo, tocá **Cancelar turno**, elegí **Quién cancela** y el **Motivo**, y confirmá con
   **Cancelar turno**.

Cancelar libera el lugar, que vuelve a la agenda. El turno no se borra: queda con su motivo y su
historial. Si alguien de la lista de espera puede usar ese horario, aparece un aviso con **Ver
lista de espera**.

### Reprogramar un turno

1. Abrí el turno y tocá **Reprogramar**.
2. Elegí desde qué fecha buscar y, si querés, otro **Profesional**. **¿Quién está libre a esta
   hora?** muestra quién más puede atender en el mismo horario.
3. Elegí el horario nuevo, el **Motivo** y, si hace falta, una **Observación**.
4. Tocá **Reprogramar**.

Es el mismo turno: conserva paciente, caso, plan e historial. Si estaba confirmado, vuelve a quedar
**Reservado**. En la agenda queda marcado con ↻.

### Marcar que el paciente no vino

1. Abrí el turno y tocá **Marcar ausente**.
2. Si el turno es de un plan, elegí si **esta ausencia consume una sesión del plan**.
3. Escribí un motivo si querés y tocá **Registrar la ausencia**.

> [!importante]
> La ausencia no libera el lugar: la hora se consumió igual.

### Bloquear la agenda

1. Tocá :icono[candado]: **Bloquear** arriba a la derecha.
2. Elegí **A quién afecta** (un profesional o **Toda la sede**) y el **Tipo**: **Licencia**,
   **Vacaciones**, **Capacitación**, **Personal**, **Institucional** u **Otro**.
3. Completá **Fecha desde** y **Fecha hasta**, y las horas o **Todo el día**.
4. Tocá **Revisar**. Si hay turnos en ese período, se listan.
5. Elegí qué hacer con ellos: **Reprogramar turnos**, cancelarlos con **Cancelar en lote**, o
   **Crear bloqueo igualmente**.

Si creás el bloqueo con turnos adentro, esos turnos **no se cancelan**: quedan marcados **A
reubicar** hasta que los muevas o los canceles.

## Qué significa cada estado

- **Reservado**: el lugar está tomado, falta confirmarlo.
- **Confirmado**: la reserva quedó confirmada. No dice que vino ni que pagó.
- **Paciente presente**: llegó al centro y está en la recepción.
- **En atención**: el profesional abrió la atención clínica.
- **Atendido**: la atención clínica se registró.
- **Se retiró**: llegó, pero se fue sin ser atendido. Ya no se puede marcar ausente ni mover.
- **Ausente**: no vino y la hora se consumió igual.
- **Cancelado**: el lugar se liberó y volvió a la agenda.
- **A reubicar**: el turno quedó fuera del horario del profesional, por ejemplo por un bloqueo.
- **Sobreturno**: un turno dado encima de otro, con motivo registrado.

## Problemas comunes

- **«No hay turnos para este día»**: si además dice que no hay horarios de atención cargados, el
  profesional no tiene horario ese día. Se carga en **Cargar horarios**. **Próximo día con
  turnos** salta al siguiente día con reservas.
- **«Ese horario ya no está disponible»** o **«Este horario acaba de ser reservado»**: alguien lo
  tomó mientras elegías. Elegí otra de las **Opciones disponibles**, o tocá **Agregar a lista de
  espera**.
- **«Esta persona todavía no es paciente»**: tocá **Activar como paciente** y seguí con el turno.
- **No veo el botón Nuevo turno**: hace falta el permiso para gestionar turnos en esta sede.
  Pedíselo a quien administra el centro.
- **«Este turno es de otro profesional»**: podés verlo, pero solo operás los turnos que atendés.
- **No puedo crear un sobreturno**: hace falta un permiso propio. Una clase no admite sobreturno.
