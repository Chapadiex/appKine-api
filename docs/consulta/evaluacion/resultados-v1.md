# Resultados de la consulta a la biblioteca

- Fecha: 2026-11-11T21:11:50.035Z
- Base: `http://localhost:8098/api/v1` · casos: 38
- "Primera palabra" es el primer evento `texto` no vacío, o la `aclaracion` si la hubo.

## Agregados

| Métrica | Valor |
|---|---|
| Recall@5 de páginas (casos "responder") | 13/23 (57 %) |
| Acierto de "repreguntar" (ambiguas que repreguntan) | 0/10 (0 %) |
| Claras que no repreguntan | 28/28 (100 %) |
| Acierto de "sin material" (temas ausentes) | 5/5 (100 %) |
| "Sin material" falso en casos "responder" | 3/23 (13 %) |
| Primer evento p50 / p95 (s) | 0.8 / 2.0 |
| Primera palabra p50 / p95 (s), respuestas con texto | 1.1 / 2.3 |
| Repregunta p50 / p95 (s) | — / — |
| Total p50 / p95 (s), todos los casos | 2.0 / 3.9 |
| Errores | 0 |

## Por caso

| Id | Consulta | Esperado | Salida | Recall | Fuentes (libro p.desde–hasta) | 1.er evento (s) | 1.ª palabra (s) | Total (s) |
|---|---|---|---|---|---|---|---|---|
| C01 | como trabajar lumbalgia | responder | texto | sí | M2 9–9, M2 4–5, M2 7–7 | 0.5 | 1.5 | 3.9 |
| C02 | ejercicios fractura cadera | responder | texto | sí | M2 60–60, M2 105–106, M2 70–70 | 0.7 | 0.9 | 2.2 |
| C03 | epoc ejercicio | responder | texto | sí | M3 165–166, M4 32–33, M4 21–22 | 0.4 | 0.6 | 1.8 |
| C04 | acv hombro doloroso | responder | sin_material ✗ | no | — | 0.5 | 0.5 | 0.5 |
| C05 | rehabilitacion cardiaca ejercicio | responder | texto | sí | M4 7–7, M4 4–5, M4 17–18 | 0.4 | 0.6 | 2.1 |
| C06 | reeducacion de la marcha en parkinson | responder | texto | no | M3 36–36, M3 35–36, M3 184–184 | 0.4 | 0.6 | 1.6 |
| C07 | ortesis artritis reumatoide | responder | texto | sí | M2 34–35, M2 31–33, M2 110–110 | 0.8 | 0.9 | 3.0 |
| C08 | sarcopenia ejercicios de fuerza | responder | texto | sí | M2 49–49, M2 47–47, M2 47–47 | 1.0 | 1.2 | 2.5 |
| C09 | ulceras por presion en lesion medular | responder | texto | sí | M3 112–113, M3 94–95, M3 95–95 | 1.0 | 1.3 | 3.3 |
| C10 | como manejar la disnea en epoc | responder | sin_material ✗ | no | — | 0.9 | 0.9 | 0.9 |
| C11 | demencia apoyo al cuidador | responder | texto | sí | M3 148–149, M4 38–38, M3 146–147 | 0.8 | 1.1 | 2.2 |
| C12 | autismo intervenciones de comunicacion | responder | sin_material ✗ | no | M5 5–7, M5 12–13, M5 8–9 | 0.8 | 1.0 | 1.1 |
| C13 | espasticidad en paralisis cerebral | responder | texto | no | M3 117–119, M3 190–190, M3 142–143 | 1.0 | 1.2 | 3.2 |
| C14 | protesis tras amputacion de miembro inferior | responder | texto | sí | M2 102–102, M2 71–73, M2 73–74 | 0.9 | 1.1 | 2.0 |
| C15 | que ejercicios para osteoartritis de rodilla | responder | texto | sí | M2 22–23, M2 19–20, M2 22–22 | 1.0 | 1.2 | 3.3 |
| C16 | evaluacion de kinesiofobia en lumbalgia | responder | texto | sí | M2 9–9, M2 10–11, M2 9–10 | 0.8 | 1.1 | 2.0 |
| A01 | lumbalgia | repreguntar | texto ✗ | — | M2 4–5, M2 5–7, M2 7–7 | 0.8 | 0.9 | 2.2 |
| A02 | kinesiofobia | repreguntar | sin_material ✗ | — | M2 30–30, M3 196–197, M3 195–196 | 1.3 | 1.6 | 1.8 |
| A03 | fracturas | repreguntar | texto ✗ | — | M2 57–57, M2 57–58, M2 60–60 | 0.9 | 1.0 | 1.9 |
| A04 | epoc | repreguntar | sin_material ✗ | — | — | 0.6 | 0.6 | 0.6 |
| A05 | parkinson | repreguntar | texto ✗ | — | M3 35–36, M3 39–40, M3 39–39 | 0.7 | 0.8 | 2.6 |
| A06 | sarcopenia | repreguntar | texto ✗ | — | M2 47–47, M2 49–49, M2 45–47 | 0.4 | 0.6 | 1.8 |
| A07 | demencia | repreguntar | texto ✗ | — | M3 145–145, M3 143–145, M3 145–145 | 0.5 | 0.6 | 2.2 |
| A08 | ejercicios | repreguntar | texto ✗ | — | M4 39–39, M4 26–27, M3 178–179 | 0.5 | 0.6 | 2.0 |
| H01 | ejercicios *(con historial)* | responder | texto | no | M3 5–6, M3 61–62, M2 98–99 | 2.0 | 2.3 | 4.2 |
| H02 | kinesiofobia *(con historial)* | repreguntar | texto ✗ | — | M2 30–30, M2 11–12, M5 44–45 | 1.0 | 1.2 | 2.0 |
| H03 | y la evaluacion? *(con historial)* | responder | texto | no | M3 92–93, M3 92–93, M3 93–94 | 1.8 | 1.9 | 3.0 |
| H04 | parkinson *(con historial)* | repreguntar | sin_material ✗ | — | M3 33–35, M3 35–35, M3 35–35 | 1.4 | 1.5 | 1.7 |
| H05 | quien lo hace? *(con historial)* | responder | texto | sí | M2 97–98, M2 57–58, M2 70–70 | 1.4 | 1.5 | 2.4 |
| P01 | Más detalle sobre lo anterior *(con historial)* | responder | texto | sí | M2 9–9, M2 11–12, M2 10–11 | 2.2 | 2.3 | 3.6 |
| P02 | más detalle de la psicoterapia cognitivo-conductual *(con historial)* | responder | texto | no | M4 40–41, M2 9–9, M3 183–184 | 1.4 | 1.5 | 2.9 |
| P03 | Más detalle sobre lo anterior *(con historial)* | responder | texto | no | M3 156–156, M2 82–82, M2 50–50 | 1.8 | 1.9 | 3.0 |
| P04 | ¿quién lo hace y cuánto dura cada sesión? *(con historial)* | responder | texto | no | M2 97–98, M4 39–39, M3 177–177 | 1.3 | 1.4 | 2.7 |
| X01 | esguince de tobillo | sin_material | sin_material | — | — | 0.6 | 0.6 | 0.6 |
| X02 | tratamiento de la fibromialgia | sin_material | sin_material | — | M2 30–30, M2 4–5, M2 44–45 | 0.6 | 0.9 | 1.1 |
| X03 | ejercicios para escoliosis idiopatica | sin_material | sin_material | — | M2 37–38, M3 22–22, M2 63–63 | 0.8 | 0.9 | 1.1 |
| X04 | rehabilitacion de ligamento cruzado anterior | sin_material | sin_material | — | M3 5–6, M3 5–5, M2 44–45 | 0.5 | 0.6 | 0.8 |
| X05 | linfedema despues de cancer de mama | sin_material | sin_material | — | M4 31–31, M2 108–109, M2 43–43 | 0.5 | 0.7 | 0.9 |
## Notas de la línea base (v1, 11/11/2026)

- Medido contra la pila `akine-api-rag-onnx` (8098) levantada desde `akine-biblioteca-rag` en `24f57459`, con
  Ollama en GPU y el modelo ya cargado. Corrida del fix round 1: el recall sólo cuenta respuestas con texto.
- v1 no repregunta nunca: las 10 ambiguas responden con texto (7) o "sin material" (A02 "kinesiofobia", A04
  "epoc", H04 "parkinson" con historial de ACV).
- Tres claras dan "sin material" falso: C04 "acv hombro doloroso", C10 disnea en EPOC y C12 autismo. Cuentan
  como fallo de recall (C12 sí traía una página esperada entre sus fuentes).
- Las repreguntas con historial y las de profundizar pierden el tema: H01 y H03 traen fuentes de M3 o de otras
  secciones, y P02–P04 no traen la fila ni el glosario pedidos.
- v1 manda a lo sumo 3 fuentes, así que Recall@5 mide en la práctica un Recall@3.
