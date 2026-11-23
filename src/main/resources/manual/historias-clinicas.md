---
id: historias-clinicas
titulo: Historias clínicas
grupo: Pacientes
rutas: ["/historia-clinica", "/historia-clinica/personas/:personaId"]
permisos: [hc:read]
servicio: KINESIOLOGIA
relacionadas: [padron, ficha-de-la-persona, caso-clinico, atencion, atender-sin-turno, glosario]
palabras: [historia clínica, HC, antecedentes, motivo de acceso, sesiones anteriores, timeline, adjunto clínico, entrada clínica, resumen clínico, datos declarados]
---

## Para qué sirve

Desde acá llegás a la **historia clínica** de cualquier paciente del centro y a todas sus sesiones,
sin necesidad de un turno. Adentro ves sus antecedentes, sus casos, sus sesiones y todo lo registrado
en orden de fecha.

> [!importante]
> La **historia clínica** es una sola por persona y es del centro. Adentro tiene **casos clínicos**
> (un problema a tratar: una rodilla y un hombro son dos casos) y **sesiones** (cada atención
> realizada). Lo escrito en la historia no se borra: se enmienda.

## Tareas

### Buscar a un paciente y abrir su historia

![El buscador de historias clínicas y el botón Historia y sesiones de una tarjeta marcados](captura:historias-clinicas/buscar-paciente)

1. Entrá a :icono[pacientes]: **Pacientes → Historias clínicas**.
2. En el buscador :icono[buscar]:, escribí apellido, nombre o documento.
3. En la tarjeta del paciente, tocá **Historia y sesiones**.

Sólo aparecen las personas con perfil de paciente. Para ver sus datos administrativos, tocá
**Ficha**.

### Declarar el motivo del acceso

![El pedido de motivo, con el campo Motivo del acceso y el botón Ver la historia clínica marcados](captura:historias-clinicas/motivo-de-acceso)

1. Si no figura que atiendas a esta persona en tu sede, aparece **Declará el motivo del acceso**.
2. Escribí el **Motivo del acceso**, por ejemplo «interconsulta pedida por el profesional tratante».
3. Tocá **Ver la historia clínica**.

Se pide porque la historia clínica es información sensible: si no atendés a esa persona, tenés que
decir por qué entrás. El motivo **queda registrado con cada lectura** que hagas en esta visita, y se
vuelve a pedir la próxima vez que entres. Si sí la atendés, entrás directo (acceso por relación
asistencial). En los dos casos, cada lectura queda auditada.

### Abrir una historia que todavía no existe

1. Entrá a la historia del paciente.
2. Si dice **Todavía no tiene historia clínica**, tocá **Abrir la historia clínica**.

No hace falta: la historia se abre sola con la primera sesión. Abrirla antes sirve para cargar
antecedentes o adjuntos antes de atenderlo.

### Cargar antecedentes y el resumen clínico

1. En la tarjeta **Historia clínica**, buscá **Antecedentes vigentes** y tocá :icono[agregar]:
   **Agregar**.
2. Elegí el **Tipo**, escribí la **Descripción** y, si es quirúrgico, la zona, el año y dónde.
3. Tocá **Guardar antecedente**.
4. Para el **Resumen clínico**, tocá **Escribir** (o **Editar**), escribí y tocá **Guardar
   resumen**.

### Registrar una entrada o subir un adjunto

1. En la tarjeta **Registrar en la historia**, abrí **Nueva entrada clínica** o **Subir un adjunto**.
2. Para una entrada, elegí el **Tipo de entrada**, escribí el **Texto** y tocá **Registrar
   entrada**.
3. Para un adjunto, elegí la **Categoría**, el **Archivo** y tocá **Subir adjunto**.

Lo registrado aparece en el **Timeline clínico**, del hecho más reciente al más antiguo. Tocá una
fila para ver su contenido; usá :icono[filtro]: **Filtros** para ver un caso, un tipo de hecho o
unas fechas.

### Revisar lo que declaró el paciente

1. Si aparece el aviso **Datos declarados por el paciente sin revisar**, leé cada dato.
2. Tocá :icono[tilde]: **Revisado** en cada uno que confirmaste.

Lo que cuenta el paciente lo carga recepción y puede no ser exacto: revisalo antes de usarlo.

## Qué significa cada estado

- **Sin revisar** o **Declarado · sin revisar**: un dato que trajo el paciente y que todavía no
  confirmó un profesional.
- **Con motivo declarado**: entraste declarando un motivo; se ve cuál.
- **Por relación asistencial**: entraste porque atendés a esta persona en tu sede.

## Problemas comunes

- **«Todavía no hay pacientes»**: una persona aparece acá recién cuando se le activa el perfil de
  paciente en el padrón.
- **«Sin motivo no se puede seguir»**: el motivo del acceso es obligatorio cuando no atendés a esa
  persona.
- **No encuentro a alguien**: probá con el documento sin puntos o con parte del apellido. Si no es
  paciente, no aparece: activale el perfil desde el padrón.
- **No veo esta pantalla en el menú**: hace falta el permiso para leer historias clínicas y que el
  centro tenga contratada la parte clínica.
