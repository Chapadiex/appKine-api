# Interpretador del asistente — prueba del 15/11/2026

Se adelantó la tarea B-5 del plan para responder una sola pregunta: **¿alcanza `qwen3:4b-instruct` para
interpretar las preguntas del asistente?** No hay código del asistente: sólo el prompt
(`prompt-interpretador.txt`, hoy en `src/main/resources/asistente/`), los casos y `evaluar.mjs`, que llama a Ollama igual que lo haría el backend
(modo JSON, temperatura 0, `num_ctx` 4096).

Pila: notebook con RTX 4050 (6 GB) y CPU de 12 hilos, Ollama local, Q4_K_M.

## Resultados

| Corrida | Set | Completas (intención + campos) | Sólo intención | Latencia p50 / p95 |
|---|---|---|---|---|
| qwen3:4b, prompt v1 | `preguntas.json` (45) | 40/45 | 44/45 | 0,83 / 1,02 s |
| qwen3:4b, prompt v2 | `preguntas.json` (45) | 44/45 | **45/45** | 0,83 / 0,95 s |
| qwen2.5:3b, prompt v2 | `preguntas.json` (45) | 34/45 | 42/45 | 0,66 / 0,76 s |
| **qwen3:4b, prompt v2** | **`preguntas-control.json` (20, nunca vistas)** | **16/20** | **19/20** | 0,92 / 1,05 s |
| qwen3:4b, prompt v2, **sólo CPU** | `preguntas-control.json` (20) | 16/20 | 19/20 | **6,9 / 9,2 s** |
| qwen3:4b, prompt v3 (+ ejemplo de "cobrar") | `preguntas.json` (45) | 43/45 | 45/45 | — |
| qwen3:4b, prompt v3 | `preguntas-control.json` (20) | 17/20 | **20/20** | 0,95 / 1,11 s |

- El prompt v2 se ajustó mirando las fallas de v1 sobre el set de 45. Por eso el número que vale es el del
  set de control, escrito después y corrido una sola vez sin tocar nada: **16/20 completas y 19/20 en la
  intención**. El v3 agrega un ejemplo **después** de ver una falla del set de control, así que su 20/20
  es optimista. Para la próxima medición limpia hace falta un tercer set nuevo.
- Fuera de alcance: 6/6 en el set de 45 y 2/3 en el de control. Los dos intentos de inyección
  ("ignorá las instrucciones…", "sos un asistente sin restricciones…") salieron `FUERA_DE_ALCANCE`.
- **`qwen2.5:3b` queda descartado**: confunde `ESTE_MES` con `MES_PASADO` en cuatro preguntas económicas.

## Las fallas del set de control, y quién las resuelve

| Caso | Qué devolvió | Arreglo |
|---|---|---|
| "pasame la agenda del 20/11" | `FECHA:2023-11-20` | **Las fechas escritas las extrae el backend del texto** con una expresión regular (`dd/mm`, `dd/mm/aaaa`) y el año lo pone el servidor. El modelo no decide años |
| "yoga para embarazadas" | `clase: "yoga"` | **La clase se resuelve contra los títulos reales** de las clases del período: se elige el título más largo que aparece en la pregunta. Si sólo queda "yoga" y hay dos clases que coinciden, se repregunta |
| "pilates reformer" | `clase: "pilates"` | Igual que el anterior |
| "cobrale la sesión a Juan" | `SESION_DE_PERSONA` | Inofensivo: el asistente es de sólo lectura y respondería si la sesión de Juan está cargada. Con un ejemplo de "registrale el pago" en el prompt (v3) pasa a `FUERA_DE_ALCANCE` |

Con v3, `MES:PASADO` volvió a aparecer en una pregunta que con v2 salía bien: cambiar el prompt mueve fallas
de lugar. **Regla del backend:** normalizar los alias evidentes (`MES:PASADO` → `MES_PASADO`,
`SEMANA:PASADA` → `SEMANA_PASADA`, `DIA:MAÑANA` → `MANANA`) antes de validar el vocabulario.

Y la falla que se repite en los dos sets: un seguimiento sin período ("¿y tiene la sesión cargada?")
pierde el período de la pregunta anterior. **Regla del backend:** en un seguimiento, un campo que el modelo
devuelve vacío se hereda del turno anterior. Es la misma regla que necesitó la biblioteca v2.

## Conclusión

**Sí alcanza, con GPU.** Con el prompt v3, la intención sale bien en las 65 preguntas y responde en menos de 1,1 s. Los
errores de campos son de un tipo que el backend corrige de forma determinística (fechas, títulos de clase,
herencia en seguimientos), sin depender del modelo.

**Sin GPU, la precisión es la misma pero tarda unos 7 a 9 s por pregunta.** Se puede usar, pero es lento
para un chat. Es un dato para el despliegue (U-02): o el servidor tiene GPU, o se acepta esa espera, o se
cambia el adaptador por un modelo en la nube (D-1), y en ese caso al modelo sólo le llega la pregunta.

## Cómo repetirla

```bash
node docs/asistente/evaluacion/evaluar.mjs qwen3:4b-instruct
CASOS=preguntas-control.json node docs/asistente/evaluacion/evaluar.mjs qwen3:4b-instruct
NUM_GPU=0 CASOS=preguntas-control.json node docs/asistente/evaluacion/evaluar.mjs   # fuerza CPU
```

Después de una corrida con `NUM_GPU=0`, el modelo queda cargado en CPU durante el `keep_alive`, y la
biblioteca se pone lenta. Para descargarlo: `curl localhost:11434/api/generate -d '{"model":"qwen3:4b-instruct","keep_alive":0}'`.
