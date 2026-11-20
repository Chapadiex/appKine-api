---
id: calendario-de-la-sede
titulo: Calendario de la sede
grupo: Espacios y horarios
rutas: ["/horarios/calendario", "/horarios/calendario/horario", "/horarios/calendario/feriados", "/horarios/calendario/cierres", "/horarios/excepciones"]
permisos: [colaborador:read]
relacionadas: [horarios-de-profesionales, agenda, sedes, recepcion-del-dia]
palabras: [horario general, horario de la sede, feriados, cierre, apertura, licencia, vacaciones, política de feriados, sede cerrada, excepción]
---

## Para qué sirve

Define cuándo **está abierta la sede** y sus excepciones. Tiene cuatro pestañas:

- **Resumen y política**: el horario configurado y si la sede cierra los feriados.
- **Horario general**: los días y horas en que la sede abre.
- **Feriados**: los feriados del calendario nacional que caen en un período.
- **Cierres y aperturas**: excepciones puntuales, de toda la sede o de un profesional.

> [!importante]
> El **horario general limita la agenda**: no se ofrece ni se reserva ningún turno fuera de él,
> aunque el profesional tenga horario cargado. Sin horario general no se limita nada.

## Tareas

### Configurar el horario general de la sede

![La sección Horario general de la sede, con los modelos de horario y el botón Guardar el horario marcados](captura:calendario-de-la-sede/horario-general)

1. Entrá a :icono[horarios]: **Espacios y horarios → Calendario de la sede** y abrí la pestaña
   **Horario general**.
2. En **Empezá desde un modelo**, elegí uno o marcá día por día cuáles abren. Podés sumar **+ Corte
   al mediodía** o **+ Otra franja**, y **Copiar el horario del lunes a los demás días abiertos**.
3. Tocá **Guardar el horario**.

Guardar reemplaza el horario entero. Si cerrás todos los días y guardás, el horario se borra.

Si algún profesional queda con franjas afuera, aparece un aviso antes de guardar. Elegí
**Recortarlas al nuevo horario**, **Guardar sin recortar y ajustar a mano** o **Cancelar**.

### Decidir si la sede cierra los feriados

1. En la pestaña **Resumen y política**, buscá **Política de la sede**.
2. Marcá o desmarcá **La sede cierra los feriados de su calendario**.
3. Tocá **Guardar la politica**.

Con la casilla marcada, los feriados se descuentan del horario de todos los profesionales.

> [!consejo]
> Para abrir **un** feriado puntual no apagues la política: cargá una apertura de sede para ese
> día en **Cierres y aperturas**.

### Cargar un cierre o una licencia

![El formulario Cargar un cierre o una apertura, con el tipo, el motivo, el alcance, las fechas y el botón Cargar la excepción marcados](captura:calendario-de-la-sede/cargar-cierre)

1. Abrí la pestaña **Cierres y aperturas** y tocá :icono[agregar]: **Cargar un cierre o una
   apertura**.
2. En **Que se carga**, elegí **Cierre — deja de atenderse**.
3. Elegí el **Motivo**: **Ausencia**, **Licencia**, **Vacaciones**, **Capacitación**, **Feriado**,
   **Bloqueo** u **Otro**.
4. En **A quien alcanza**, elegí al profesional o **Toda la sede — todos los profesionales**.
5. Completá **Primer día** y **Fin (exclusivo)**: para un solo día, el fin es el día siguiente.
6. Tocá **Cargar la excepción**.

Si no es el día entero, desmarcá **Día completo** y cargá las horas. Antes de guardar se muestran
los **Turnos afectados**: no se cancelan ni se mueven, quedan para revisar en la agenda.

### Abrir un día en que normalmente no se atiende

1. En **Cierres y aperturas**, tocá **Cargar un cierre o una apertura**.
2. En **Que se carga**, elegí **Apertura — se atiende donde normalmente no**.
3. Completá alcance, fechas y franja, y tocá **Cargar la excepción**.

Una apertura de **toda la sede** en un feriado **reemplaza** el horario habitual de todos ese día:
sólo se atiende en la franja de la apertura. El sistema te avisa antes; confirmá con **Entiendo:
cargar igual**.

### Ver los feriados de un período

1. Abrí la pestaña **Feriados**.
2. Completá **Desde** y **Hasta (exclusivo)** y tocá **Consultar**.

Si la sede no cierra los feriados, la lista es sólo informativa.

## Problemas comunes

- **«La sede no declaro horario general.»**: no significa que esté cerrada; significa que la
  agenda no tiene límite de sede. Configuralo en **Horario general**.
- **Cargué un cierre de un día y no cubre nada**: el fin es exclusivo. Con la misma fecha en los
  dos campos se cubren cero días; poné el día siguiente.
- **No aparecen turnos en un horario que el profesional tiene cargado**: puede estar fuera del
  horario general de la sede o tapado por un cierre. Revisalo en **Horario efectivo**.
- **El feriado no aparece en la lista**: un feriado que cae justo en la fecha **Hasta** no se
  incluye.
- **No veo los botones para guardar**: tu cuenta sólo puede consultar el calendario.
