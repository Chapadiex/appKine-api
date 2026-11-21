# Manual de usuario — medición de COMO_SE_HACE

Set: `manual-preguntas.json` (34 preguntas: 26 con pasaje esperado, 8 que tienen que abstenerse). Cada
pregunta corre con la cuenta que usa esa pantalla: las de atención clínica, con la profesional (la
administradora no tiene `sesion:register` y no ve esa ficha, como corresponde).
Runner: `manual-evaluar.mjs` contra el backend levantado con Ollama (`qwen3:4b-instruct`, RTX 4050),
cuenta ORG_ADMIN sintética en `akine_main`. Búsqueda sólo por palabras (biblioteca apagada, sin
modelo de embeddings), que es la configuración por defecto.

| Fecha | Cambio | Correctas | Abstenciones bien | Mediana | p90 |
|---|---|---|---|---|---|
| 20/11/2026 | Primera medición | 28/33 (85 %) | 5/8 | 1,9 s | 2,2 s |
| 20/11/2026 | El elector se abstiene ante otros sistemas y distingue sesión clínica de sesión del usuario | 29/33 (88 %) | 8/8 | 2,1 s | 2,5 s |
| 20/11/2026 | Sinónimos de conceptos (paciente/persona, prepaga/financiador, cierro/cerrar, reprogramar/cambiar) | 31/33 | 8/8 | 2,1 s | 2,6 s |
| 20/11/2026 | El vector pesa 0,3 en la fusión; el elector ve el comienzo de cada tarea; el glosario sólo compite en preguntas de definición; cuentas por pantalla; dos títulos más descriptivos | **34/34** (dos corridas) | 8/8 | 2,0 s | 2,2 s |

Criterio de aceptación del plan: 24/30 (80 %) y ningún pasaje de una ficha invisible. Cumple.

**Probado y descartado:** ponderar las palabras por IDF empeoró dos preguntas (arqueo, llegada):
las secciones fijas de cada ficha llevan los sinónimos de toda la ficha y el IDF las favorecía. Se
revirtió.

## Set de control (no usado para afinar)

`manual-preguntas-control.json`: 14 preguntas nuevas escritas después de cerrar los ajustes, medidas
una sola vez y sin tocar nada a partir del resultado (`AKINE_PREGUNTAS=manual-preguntas-control.json`).
Sirve para saber cuánto del 34/34 es sobreajuste al set de arriba.

| Fecha | Correctas (tarea exacta) | Ficha correcta | Abstenciones bien | Mediana |
|---|---|---|---|---|
| 20/11/2026 | 11/14 (79 %) | 12/14 (86 %) | 3/3 | 2,0 s |

Fallas, sin corregir a propósito para que el set siga midiendo:

- «¿dónde veo quién viene hoy al consultorio?» → «Para qué sirve» de la Recepción del día: la ficha
  es la correcta y la respuesta sirve, pero no es la tarea esperada.
- «¿cómo saco un box de servicio porque se rompió la camilla?» → el interpretador la clasifica
  FUERA_DE_ALCANCE y no llega al manual.
- «¿cómo corrijo una sesión que ya cerré?» → «Cerrar la atención» en vez de la enmienda.

Lectura: el set afinado sobreestima; lo esperable en uso real es ~80 % de tarea exacta y ~86 % de
ficha correcta, sin citar nunca algo inventado ni algo que la persona no puede ver. Mejorarlo es
sumar preguntas reales al set de arriba y volver a medir este de control.
