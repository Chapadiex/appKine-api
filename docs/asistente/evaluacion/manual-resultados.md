# Manual de usuario — medición de COMO_SE_HACE

Set: `manual-preguntas.json` (33 preguntas: 25 con pasaje esperado, 8 que tienen que abstenerse).
Runner: `manual-evaluar.mjs` contra el backend levantado con Ollama (`qwen3:4b-instruct`, RTX 4050),
cuenta ORG_ADMIN sintética en `akine_main`. Búsqueda sólo por palabras (biblioteca apagada, sin
modelo de embeddings), que es la configuración por defecto.

| Fecha | Cambio | Correctas | Abstenciones bien | Mediana | p90 |
|---|---|---|---|---|---|
| 20/11/2026 | Primera medición | 28/33 (85 %) | 5/8 | 1,9 s | 2,2 s |
| 20/11/2026 | El elector se abstiene ante otros sistemas y distingue sesión clínica de sesión del usuario | 29/33 (88 %) | 8/8 | 2,1 s | 2,5 s |

Criterio de aceptación del plan: 24/30 (80 %) y ningún pasaje de una ficha invisible. Cumple.

**Lo que falla todavía** (elige mal el pasaje, no inventa):

- «¿cómo cambio un turno de día?» → horarios de profesionales, en vez de reprogramar un turno.
- «¿dónde cargo una prepaga nueva?» → cargar un arancel, en vez de dar de alta un financiador.
- «¿cómo agrego un paciente nuevo?» → darle un turno nuevo, en vez de dar de alta una persona.
- «¿cómo cierro la atención de la sesión?» → cargar un cierre del calendario.

Las cuatro son de recuperación por palabras sin vectores: con la biblioteca prendida entra la
búsqueda híbrida. Mejorables también sumando `palabras` a esas fichas.
