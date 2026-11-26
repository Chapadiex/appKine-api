# AKINE — Carga por Telegram

> 26/11/2026 · contrato 0.197.0 · migración `V51` · rama `akine-telegram`

## 1. Qué es

Otra forma de cargar información: el profesional le manda al bot de Telegram de su centro un audio
o un mensaje («Juan Strudel: movilidad de hombro y electro, dolor 4 de 10») y queda cargado en
AKINE con su cuenta. Es el asistente de siempre con otra entrada, no un camino de escritura nuevo.

## 2. Por qué Telegram y no WhatsApp

WhatsApp no tiene API oficial para leer grupos ni chats personales; las librerías no oficiales
violan sus términos y bloquean el número. La API oficial (Cloud API de Meta) exige número de
empresa verificado, plantillas y cobra por conversación. La Bot API de Telegram es oficial,
gratis, y cada centro crea su bot en minutos.

## 3. Cómo se usa

1. **El centro crea su bot** en Telegram con @BotFather (`/newbot`) y le desactiva el modo
   privado (`/setprivacy` → Disable) si lo va a usar en un grupo.
2. **El ORG_ADMIN pega el token** en Organización → Inteligencia artificial → Telegram
   (`PUT /organizations/{orgId}/telegram`). Se valida con `getMe` y se guarda cifrado.
3. **Cada profesional se vincula** desde Asistente de datos → Telegram: pide un código de seis
   dígitos (15 minutos, un uso) y se lo manda al bot como `/vincular 123456`.
4. **Manda audios o mensajes**, en privado o en el grupo del centro.

## 4. Qué pasa con cada mensaje

`SondeoDeTelegram` → `MensajesDeTelegram`:

1. Sólo se atiende a usuarios vinculados. En un grupo, a quien no lo está no se le contesta.
2. Se resuelve la membership de la cuenta en la sede vinculada, igual que `TenantContextFilter`
   (sin acceso → aviso; suscripción suspendida → no carga) y se publica el contexto de tenant en
   el hilo, porque los servicios de los módulos lo leen de ahí.
3. La nota de voz pasa por `DictadoService.transcribir` (Whisper local, `dictado:use`).
4. El texto entra a `AsistenteService.consultar` con la ruta `telegram`, que le agrega al agente
   una línea de contexto: si nombran al paciente sólo por el nombre, buscarlo primero entre los
   turnos de hoy de quien escribe y después entre sus atendidos.
5. **Lo que el asistente propone se confirma solo** con `AccionesDelAsistente.confirmar`, salvo
   que la tarjeta tenga bloqueos o algo para elegir (varios turnos, varios casos, posible
   duplicado): entonces el bot lo dice y no carga. Los tratamientos no reconocidos se sacan de la
   tarjeta; lo dicho queda completo en la nota de cierre, que es la frase entera.
6. Si el agente no sabe de qué persona se habla, el bot pregunta con botones. El texto se
   recuerda en memoria 15 minutos; sólo quien mandó el mensaje puede elegir.

Confirmar solo es seguro por lo mismo que en la pantalla: la confirmación revalida todo por el
camino normal de cada módulo, con los permisos de la cuenta, y queda auditada.

## 5. Decisiones

- **Polling, no webhook.** No necesita URL pública ni HTTPS: anda igual en una PC que en el
  despliegue. Con dos instancias, `telegram_bot.tomado_hasta` es un alquiler: una sola sondea cada
  bot (Telegram responde 409 a dos `getUpdates` cruzados).
- **A lo sumo una vez.** El offset avanza antes de procesar cada mensaje: si el proceso cae a
  mitad de una carga, el mensaje se pierde y la persona lo reenvía, pero nunca se carga dos veces
  una atención.
- **Token cifrado** con AES-256-GCM; la clave es `AKINE_TELEGRAM_CLAVE` o, si falta, se deriva de
  `AKINE_JWT_SECRET`. Nunca vuelve por la API. Un token que no se descifra cuenta como bot sin
  token.
- **Nada del mensaje se guarda**: ni audio, ni texto, ni transcripción. La auditoría es la de las
  acciones del asistente (tipo e ids) más `TELEGRAM_BOT_CONECTADO/DESCONECTADO` y
  `TELEGRAM_VINCULADO/DESVINCULADO`.
- **El código lleva la sede**: pedirlo desde otra sede no mueve la carga hasta usarlo.
- **Un usuario de Telegram, una cuenta por centro**: vincularlo a otra cuenta suelta la anterior.
- **Permisos:** conectar el bot, `tenant:update`; vincularse, `asistente:acciones` en la sede. El
  controller exige el adicional de IA contratado, como el dictado.

## 6. Interruptores

`akine.telegram.enabled` (apagado por defecto: la API responde 409 `telegram-deshabilitado` y no
se sondea nada). Para que cargue hacen falta además `akine.asistente.enabled`,
`akine.asistente.acciones.enabled`, la IA prendida en el centro y, para audios,
`akine.dictado.enabled`.

## 7. Límites conocidos

- Un solo hilo de sondeo para todos los centros: con muchos mensajes simultáneos se encolan
  (~10 s por mensaje con el agente). Paralelizar por bot es el paso siguiente si hace falta.
- No hay «deshacer» desde Telegram: una atención cerrada se corrige con la enmienda, desde el
  enlace que manda el bot.
- Telegram ve el contenido de los mensajes (los bots no tienen cifrado de punta a punta): entra en
  el consentimiento de IA del centro.
- Las aclaraciones con botones viven en memoria de la instancia: si otra instancia toma el bot,
  el botón responde «mandá el mensaje de nuevo».
