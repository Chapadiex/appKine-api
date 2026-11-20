---
id: atencion
titulo: Atención clínica
grupo: Pacientes
rutas: ["/atencion/turnos/:turnoId", "/atencion/sesiones/:sesionId", "/atencion/sesiones/:sesionId/enmienda", "/atencion/sesiones/:sesionId/versiones"]
permisos: [sesion:register]
servicio: KINESIOLOGIA
relacionadas: [agenda, recepcion-del-dia, atender-sin-turno, historias-clinicas, caso-clinico, glosario]
palabras: [sesión, atender, registrar sesión, evolución, dolor, cerrar atención, nota de cierre, enmienda, dictado, tratamiento aplicado]
---

## Para qué sirve

Es donde el profesional **registra la sesión**: lo que evaluó, lo que midió, el tratamiento que
aplicó, sus notas y el cierre. Se llega desde el turno o desde las sesiones de la historia clínica.

> [!importante]
> **Turno no es sesión.** El turno es la reserva en la agenda; la **sesión** es la atención que
> realmente ocurrió, y es lo que se registra acá. Marcar al paciente como llegado en recepción no
> crea la sesión.

## Tareas

### Iniciar la atención de un turno

![El panel del turno con el botón Iniciar atención marcado](captura:atencion/iniciar-atencion)

1. En :icono[operacion]: **Atención → Agenda** o **Recepción del día**, abrí el turno del paciente.
2. Tocá **Iniciar atención** (o **Abrir la atención** si ya estaba empezada).
3. Si el paciente tiene casos clínicos activos, elegí en **Caso de esta atención** a cuál
   pertenece, o **Sin caso**.
4. Tocá **Iniciar atención**.

Si la prestación exige un caso, no vas a poder seguir sin elegir uno. Mientras la atención siga
abierta, el caso se cambia desde **Caso y profesional de esta atención**; cerrada, ya no.

### Cargar la evaluación

![La pestaña Evaluación con el dolor, la evolución y el botón Guardar evaluación marcados](captura:atencion/evaluacion)

1. En la pestaña **Evaluación**, elegí el **Dolor (EVA, escala 0 a 10)**. Tocá de nuevo el mismo
   número para dejarlo sin cargar.
2. Marcá la **Zona**, el **Lado** y la **Evolución** (**Mejor**, **Igual**, **Peor** o **Sin
   referencia**).
3. Si querés, escribí el **Objetivo de esta sesión**.
4. Tocá **Guardar evaluación**.

Ningún campo es obligatorio: un seguimiento normal se carga con el dolor y la evolución. **Sesión
rápida** y **Evaluación completa** sólo cambian cuánto ves; pasar de una a otra no borra nada.
«Sin referencia» es para la primera sesión, cuando no hay con qué comparar.

### Dictar la evaluación por voz

1. En la pestaña **Evaluación**, tocá :icono[microfono]: **Dictar evaluación**.
2. Decí el dolor, la zona, el lado, la evolución o el objetivo.
3. Tocá :icono[detener]: **Terminar dictado**.
4. Revisá lo dictado, dejá tildado lo que querés usar y tocá **Aplicar a la evaluación**.
5. Tocá **Guardar evaluación**.

Aplicar sólo completa los campos: no guarda nada hasta que tocás **Guardar evaluación**. El botón
aparece si el centro tiene el dictado habilitado.

### Registrar mediciones y tratamientos

1. En la pestaña **Mediciones**, en **Registrar una medición**, elegí la **Medida**, el **Lado** y
   el valor, y tocá :icono[agregar]:.
2. En la pestaña **Tratamiento**, en **Registrar un tratamiento**, elegí la **Práctica**, el lado y
   los minutos, y tocá :icono[agregar]:.
3. Para corregir un tratamiento, tocá **Dar de baja** en su fila, escribí por qué y tocá **Confirmar
   la baja**.

Registrar un tratamiento no mueve el plan de tratamiento: lo planificado no es lo realizado.

### Cerrar la atención

![La pestaña Cierre con la asistencia, la nota de cierre y el botón Cerrar la atención marcados](captura:atencion/cerrar-atencion)

1. Tocá **Cerrar ▸** o la pestaña **Cierre**.
2. En **Asistencia**, elegí **Vino a la sesión** o **No vino**.
3. Si vino, escribí la **Nota de cierre**: qué se hizo en la sesión. Tolerancia, próxima conducta,
   respuesta al tratamiento e indicaciones son opcionales.
4. Tocá **Cerrar la atención**.

En **Notas** podés anotar lo que quieras: se guarda solo después de un momento sin escribir, o con
**Guardar ahora**.

> [!importante]
> Cerrar le da a la sesión su número dentro de la historia clínica y la deja sin poder editarse. Una
> sesión cerrada sólo se corrige con una enmienda. **Cerrar no cobra nada**: lo económico va aparte.

### Corregir una atención cerrada (enmienda)

1. Abrí la atención cerrada y andá a la pestaña **Cierre**.
2. Tocá **Enmendar esta atención**.
3. Escribí el **Motivo de la enmienda** y corregí lo que haga falta.
4. Tocá **Registrar la enmienda**.

Cada enmienda crea una versión nueva y ninguna anterior se borra: las ves con **Ver las versiones**.
Enmendar no vuelve a cobrar ni consume autorizaciones. La atención muestra hasta cuándo se puede
enmendar.

## Qué significa cada estado

- **En curso** (o **Borrador**): la atención está abierta y se puede escribir.
- **Cerrada**: terminó, tiene su número de sesión y ya no se edita; sólo se enmienda.
- **Primera sesión**: no hay una sesión anterior del paciente con qué comparar.

## Problemas comunes

- **«Otra pestaña guardó antes que vos»**: la misma atención se guardó desde otro lado. Tu texto
  sigue en pantalla: tocá **Releer y comparar**, copiá lo que falte y tocá **Ya lo revisé**.
- **«La atención ya estaba cerrada»**: alguien la cerró antes. Para corregirla, enmendala.
- **Pide un caso y no hay ninguno**: tocá **Abrir un caso en la historia clínica del paciente** y
  volvé a iniciar.
- **No deja cerrar porque falta el precio**: la actividad no tiene precio particular cargado. Tocá
  **Cargar el precio de la actividad** o pedíselo a quien administra los servicios.
- **No puedo escribir en esta atención**: es de otro profesional. Quien administra la sede puede usar
  **Reemplazar profesional**, con un motivo obligatorio.
