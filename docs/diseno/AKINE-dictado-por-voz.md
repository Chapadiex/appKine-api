# AKINE — Dictado por voz

> 16/11/2026. Pedido del usuario: desde la tablet, apretar un botón, hablar y que lo dicho quede
> **exactamente** en ese campo. Sirve en cualquier campo de texto (el chat del asistente, las notas,
> la evaluación de la sesión), y en la evaluación también para dictar todo de una vez.

## 1. Decisión

**Whisper local, servido por el backend.** El audio no sale del servidor del centro, igual que con el
asistente y la biblioteca (Ollama local). Se descartaron el dictado del teclado y la Web Speech API
del navegador porque mandan la voz, que en la historia clínica es dato de salud, a Google o Apple
(Ley 26.529 y Ley 25.326).

| Pieza | Qué hace |
|---|---|
| Servicio `whisper` (compose) | Servidor de transcripción compatible con la API de OpenAI (`POST /v1/audio/transcriptions`), modelo `faster-whisper` *large-v3-turbo*: en CPU con int8, o en una GPU NVIDIA sumando `compose.gpu.yaml` |
| `POST /api/v1/consultorios/{id}/dictado/transcripciones` | Recibe el audio (multipart `audio`) y devuelve `{ texto }`. No guarda nada |
| `POST /api/v1/consultorios/{id}/dictado/evaluaciones` | Recibe un texto dictado y devuelve una **propuesta** de campos de la evaluación. No escribe en la sesión |
| `[appDictado]` (web) | Directiva para cualquier `input`/`textarea`: pone un botón de micrófono al lado, graba, transcribe y agrega el texto en el campo |
| "Dictar evaluación" (web) | En la tarjeta Evaluación: graba, transcribe, pide la propuesta y la muestra para confirmar campo por campo |

## 2. Reglas

1. **Un botón por campo.** El campo ya está elegido: no hay que decir "en zona poné…". El texto se
   agrega **al final** de lo que ya había (con un espacio), nunca lo reemplaza. Se dispara el evento
   `input`, así que cada pantalla lo procesa igual que si lo hubieran tecleado.
2. **Lo dictado es lo escrito.** La transcripción va tal cual al campo. No se resume, no se corrige
   con un modelo de lenguaje y no se guarda en ningún lado: el texto se guarda recién cuando el
   profesional guarda la pantalla, por el camino de siempre (con su auditoría).
3. **La propuesta de evaluación nunca se aplica sola.** El modelo local (`qwen3:4b-instruct`, el del
   asistente) sólo reparte el texto en campos. El backend descarta lo que no valide (dolor fuera de
   0–10, lateralidad o evolución fuera del catálogo, textos más largos que el campo) y la pantalla
   muestra cada campo propuesto con una casilla. Se aplica sólo lo que el profesional marca, y
   después tiene que guardar la evaluación como siempre.
4. **Textos copiados, no redactados.** El prompt pide copiar las palabras dichas. Lo que el modelo no
   pudo ubicar en ningún campo va a `restante`, y la pantalla ofrece ponerlo en las notas.
5. **Ni audio ni texto van al log.** Se registra sólo el tamaño, la duración de la llamada y el
   resultado.

## 3. Permisos, interruptor y errores

- Permiso **`dictado:use`**, por base para `ORG_ADMIN`, `CONSULTORIO_ADMIN`, `PROFESIONAL` y
  `ADMINISTRATIVO` (los mismos que `asistente:use`), sin grant. Vive en código: no lleva migración.
- Interruptor **`akine.dictado.enabled`**, **apagado por defecto**. Apagado → 409 `dictado-deshabilitado`.
- Whisper no responde o se vence el plazo → 503 `dictado-no-disponible`. Lo mismo para la propuesta
  de evaluación si el modelo del asistente no está prendido (`akine.asistente.enabled`) o no responde.
- Audio vacío, más grande que `akine.dictado.max-bytes` (10 MB, unos 10 minutos de opus) o que no es
  `audio/*` (se acepta también `video/webm` y `video/mp4`, que es como lo etiquetan algunos
  navegadores) → 400 `validation-error`.
- Sin migración. Contrato **0.175.0** (la 0.174.0 la tiene la fase 2 de evolución, sin mergear).

## 4. Límites conocidos

- **El micrófono exige HTTPS.** `getUserMedia` sólo funciona en un contexto seguro: `localhost` o
  HTTPS. Una tablet que entra por `http://192.168.x.x` no ve el botón. El despliegue tiene que
  servir HTTPS, cosa que ya hacía falta por el login.
- **Modelo: large-v3-turbo, no small.** El 17/11/2026, con la voz real del usuario, *small*
  escribió cualquier cosa ("Felicidades y toro… chupetales") con el audio llegando entero; *turbo*
  transcribió bien el mismo tipo de dictado. Por eso el valor por defecto es turbo
  (`deepdml/faster-whisper-large-v3-turbo-ct2`, 1,6 GB).
- **Latencia**, medida en la máquina de desarrollo (i5-13420H, 12 hilos; RTX 4050 Laptop 6 GB),
  por cada 10 s de audio:

  | | CPU int8 | GPU float16 |
  |---|---|---|
  | small | ~4 s (5 s por el backend) | — |
  | large-v3-turbo | ~9 s (10,6 s por el backend) | **~1,1 s** (~1 s por el backend) |

  En CPU turbo tarda casi lo mismo que el audio: 2 minutos de grabación son ~110 s, por eso el
  plazo por defecto es 180 s. La primera transcripción después de arrancar el contenedor carga el
  modelo (24 s en CPU, 52 s en GPU con el arranque de CUDA); speaches no tiene opción de precarga.
  En la GPU turbo ocupa ~2 GB de memoria de video. Proponer la evaluación tarda ~2,3 s más con
  Ollama caliente. **El destino hay que medirlo (U-02)**: sin GPU, turbo es lento y small no sirve.
- **El modelo queda cargado.** speaches descarga el modelo de memoria a los 5 minutos sin uso, y
  el primer dictado después de una pausa pasaba de ~7 s a ~15 s. El compose fija
  `WHISPER__TTL=-1` (y `WHISPER__COMPUTE_TYPE=int8`); el despliegue tiene que hacer lo mismo.
- **Audio que Whisper no decodifica** (speaches responde 415) es 400 `validation-error` con
  "Volvé a grabar", no 503: reintentar el mismo archivo no sirve.
- **Vocabulario.** Se le pasa a Whisper un `prompt` con términos frecuentes (EVA, lumbalgia,
  cervicalgia, isquiotibiales, etc.) para que los escriba bien. Es una sugerencia, no una garantía.
- El formato de audio lo decide el navegador: Chrome graba `audio/webm;codecs=opus` y Safari
  `audio/mp4`. `faster-whisper` lee los dos con ffmpeg.
