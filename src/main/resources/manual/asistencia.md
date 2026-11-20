---
id: asistencia
titulo: Asistencia a clases
grupo: Actividades
rutas: ["/asistencia", "/asistencia/:claseId"]
permisos: [inscripcion:read]
servicio: ACTIVIDADES
relacionadas: [clases, productos-de-pack, cuenta-corriente, honorarios]
palabras: [tomar lista, presentes, ausentes, asistencia, iniciar clase, cerrar clase, llegó tarde, corregir asistencia, pase de clases]
---

## Para qué sirve

Es la lista de las clases de un día para **tomar lista**. Elegís la clase, la iniciás, marcás
quién vino y la cerrás.

Marcar a alguien presente registra que estuvo en la clase: **no abre una atención clínica**. Si
la persona tiene un pase, la asistencia le descuenta un crédito; si la actividad se cobra por
clase, genera la deuda de esa clase.

## Tareas

### Elegir la clase del día

1. Entrá a :icono[actividades]: **Actividades → Asistencia**.
2. Si no es hoy, cambiá la fecha. Va en la zona horaria de la sede.
3. Buscá la clase en la tabla y tocá **Tomar asistencia**.

También llegás desde el detalle de una clase, con el botón **Tomar asistencia**.

### Iniciar la clase

![La banda de la clase con sus contadores y el botón Iniciar clase marcado](captura:asistencia/iniciar-clase)

1. Abrí la clase con **Tomar asistencia**.
2. Tocá **Iniciar clase**.

La lista se toma con la clase en curso: mientras esté **Programada** no se puede marcar a nadie.

### Tomar lista

![La tabla Participantes, con el botón para marcar a todos, las opciones de una fila y Guardar asistencia marcados](captura:asistencia/tomar-lista)

1. Con la clase **En curso**, mirá la tabla **Participantes**.
2. Si vino casi todo el grupo, tocá **Marcar presentes a los sin marcar**.
3. En cada fila, elegí **Presente**, **Llego tarde** o **Ausente**. Si hace falta, escribí
   **Observaciones (opcional)**.
4. Tocá **Guardar asistencia**. Si te equivocaste antes de guardar, tocá **Descartar cambios**.

Abajo de la tabla se ve cuántos cambios hay **sin guardar**. Después de guardar aparece el
**Resultado**: quién se guardó, quién no, qué créditos de **Pases** se consumieron y qué **Deudas
de la clase** se generaron.

> [!importante]
> Las observaciones son operativas: no anotes datos de salud.

### Cerrar la clase

1. Guardá o descartá los cambios pendientes.
2. Tocá **Cerrar clase**.

Al cerrar, quienes tenían lugar y nadie marcó quedan **ausentes**.

### Corregir una asistencia

1. Abrí la clase **Realizada** desde **Asistencia**.
2. Cambiá la marca de la persona.
3. Escribí el **Motivo de la correccion**.
4. Tocá **Guardar asistencia**.

El resultado anterior no se pierde: tocá **Historial** en la fila para ver cada registro y cada
corrección. **Cerrar historial** lo oculta.

> [!consejo]
> Si a una persona derivada a un caso hay que atenderla clínicamente, usá **Atención clínica de la
> clase** en la cabecera.

## Qué significa cada estado

- **Programada**: todavía no se inició. Hay que tocar **Iniciar clase** para tomar lista.
- **En curso**: se está tomando lista.
- **Realizada**: la clase cerró. Se puede corregir una asistencia, con motivo.
- **Cancelada**: la clase se suspendió y no tuvo asistentes.
- **Sin marcar**: a esa persona todavía no se le registró asistencia.
- **Sin lugar en la clase**: la inscripción está cancelada o en lista de espera; no se marca.

## Problemas comunes

- **«Faltan motivos de corrección»**: cambiar una asistencia ya registrada es una corrección y
  necesita un motivo en cada fila corregida.
- **«No tiene un pase vigente con créditos para esta clase»**: la asistencia quedó registrada
  igual, pero no se consumió nada. Hay que venderle un pase desde **Pacientes**.
- **«La actividad no tiene precio vigente el día de la clase»**: no se generó deuda. La
  asistencia quedó registrada igual.
- **«La clase no tiene inscriptos»**: se inscribe desde el detalle de la clase, en **Clases**.
- **No puedo ver otra página de participantes**: guardá o descartá los cambios primero.
