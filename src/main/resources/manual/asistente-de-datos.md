---
id: asistente-de-datos
titulo: Asistente de datos
grupo: Atención
rutas: ["/asistente"]
permisos: [asistente:use]
funcionIa: ASISTENTE
relacionadas: [reportes, recepcion-del-dia, asistencia, consultar-biblioteca]
palabras: [preguntar datos, chat, quién viene hoy, cuánto se facturó, vino el paciente, turnos cancelados, consulta en lenguaje natural, inteligencia artificial, registrar pago, cargar asistencia, cómo lo resolví, telegram, bot, audio, vincular telegram, cargar por telegram]
---

## Para qué sirve

Es una charla para preguntar en tus palabras sobre **todo el centro**, mirado desde la sede en la que
estás: si una persona vino o tiene la sesión cargada, quiénes vienen un día, qué cobertura tiene un
paciente, cuánto se facturó, cómo se usa una pantalla, o una pregunta abierta que cruce varios datos.
También podés contarle algo que pasó, como «Juan pagó 15.000 en efectivo», y te prepara el registro.

> [!importante]
> Responde con tus permisos: lo que no ves en pantalla, no lo ves acá. **No graba nada sin que
> confirmes**: antes te muestra una tarjeta con lo que entendió. La charla no se guarda: se pierde al
> salir o al cambiar de sede.

Para responderte, el asistente le manda los datos que lee a un servicio de inteligencia artificial
externo, el que eligió tu centro.

## Tareas

### Hacer una pregunta

![El asistente de datos, con las preguntas de ejemplo, el campo de la pregunta y el botón Preguntar marcados](captura:asistente-de-datos/preguntar)

1. Entrá a :icono[operacion]: **Atención → Asistente de datos**. Si no sabés por dónde empezar,
   tocá una pregunta de ejemplo, como **¿Quiénes vienen hoy?**
2. O escribí la tuya en el campo de abajo.
3. Tocá **Preguntar**, o apretá Enter.

Si tu centro tiene el dictado activado, tocá :icono[microfono]: para dictar la pregunta y
:icono[detener]: para terminar.

### Revisar la respuesta

- Si hay varias personas con ese nombre, elegí la correcta en los botones que aparecen.
- **Ver cómo lo resolví** muestra qué datos buscó el asistente para armar la respuesta.
- **Ver los datos** despliega la tabla con la que se armó la respuesta.
- Los botones del pie de la respuesta dicen adónde llevan, por ejemplo **Abrir la ficha del
  paciente** o **Abrir la caja**.

Para empezar otra conversación, tocá **Nueva charla** arriba a la derecha.

### Registrar algo con el asistente

1. Contale lo que pasó o lo que querés cargar, por ejemplo «vinieron todos menos María» o «Juan
   pagó 15.000 en efectivo».
2. Revisá la tarjeta que aparece y completá lo que falte.
3. Tocá **Confirmar** para grabarlo, o **Descartar** si no es lo que querías. Hasta que confirmás,
   no queda nada registrado.

### Cargar por Telegram

Si el centro conectó un bot de Telegram, arriba de la charla aparece la franja **Telegram**.

1. Tocá **Vincular mi Telegram**. Aparece un código como `/vincular 482913`.
2. Abrí el bot del centro en Telegram (el enlace de la franja) y mandale ese mensaje tal cual. El
   código dura 15 minutos y sirve una sola vez.
3. La franja pasa a **Vinculado**. Desde ahí, mandale al bot un audio o un mensaje contando lo que
   hiciste, por ejemplo «Juan Strudel: movilidad de hombro, dolor 4 de 10».

> [!importante]
> Por Telegram **no hay tarjeta para revisar**: si el asistente entiende de quién se trata y no hay
> nada para elegir, lo carga con tu cuenta en la sede vinculada y te contesta qué cargó, con el
> enlace. Si hay dudas sobre el paciente, te pregunta con botones; si hay algo que no puede decidir
> solo, te lo dice y no carga nada.

Para dejar de cargar desde ese Telegram, tocá **Desvincular**.

## Problemas comunes

- **El bot dice que no conoce tu cuenta**: todavía no te vinculaste, o el código venció. Pedí otro
  con **Otro código**.
- **No veo la franja Telegram**: el centro no conectó un bot. Lo conecta quien administra el centro
  en Organización → Inteligencia artificial → **Carga por Telegram**.
- **«El asistente no está habilitado en este entorno.»**: la función no está disponible. Avisá a
  quien administra el centro.
- **«El asistente no está disponible en este momento.»**: probá de nuevo en unos segundos.
- **«No tenés permiso para usar el asistente.»**: pedí el permiso a quien administra el centro.
- **La respuesta no trae un dato que sé que existe**: puede ser de otra sede o de algo que tus
  permisos no te dejan ver. Cambiá de sede o preguntá a quien administra.
- **La tarjeta venció**: pasó un rato sin confirmarla y no se grabó nada. Volvé a pedirlo.
- **No veo la pantalla en el menú**: el centro puede tener apagado el asistente en Organización →
  Inteligencia artificial.
