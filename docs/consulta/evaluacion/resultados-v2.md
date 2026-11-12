# Resultados de la consulta a la biblioteca

- Fecha: 2026-11-12T01:15:14.274Z
- Base: `http://localhost:8098/api/v1` · casos: 38
- "Primera palabra" es el primer evento `texto` no vacío, o la `aclaracion` si la hubo.

## Agregados

| Métrica | Valor |
|---|---|
| Recall@5 de páginas (casos "responder") | 18/23 (78 %) |
| Acierto de "repreguntar" (ambiguas que repreguntan) | 10/10 (100 %) |
| Claras que no repreguntan | 28/28 (100 %) |
| Acierto de "sin material" (temas ausentes) | 5/5 (100 %) |
| "Sin material" falso en casos "responder" | 1/23 (4 %) |
| Primer evento p50 / p95 (s) | 0.1 / 0.2 |
| Primera palabra p50 / p95 (s), respuestas con texto | 2.5 / 3.6 |
| Repregunta p50 / p95 (s) | 1.2 / 3.1 |
| Total p50 / p95 (s), todos los casos | 4.8 / 8.3 |
| Errores | 0 |

## Por caso

| Id | Consulta | Esperado | Salida | Recall | Fuentes (libro p.desde–hasta) | 1.er evento (s) | 1.ª palabra (s) | Total (s) |
|---|---|---|---|---|---|---|---|---|
| C01 | como trabajar lumbalgia | responder | texto | sí | M2 1–7, M2 7–8, M2 10–11, M2 9–10, M2 7–7 | 0.2 | 4.3 | 8.3 |
| C02 | ejercicios fractura cadera | responder | texto | sí | M2 53–57, M2 57–57, M3 139–140, M3 124–125, M2 58–59 | 0.1 | 2.5 | 5.2 |
| C03 | epoc ejercicio | responder | texto | sí | M4 17–21, M4 21–21, M4 21–22, M4 21–21, M3 148–148 | 0.1 | 2.1 | 4.7 |
| C04 | acv hombro doloroso | responder | texto | sí | M3 9–10, M3 17–18, M3 7–8, M3 82–84, M2 58–59 | 0.1 | 3.0 | 6.7 |
| C05 | rehabilitacion cardiaca ejercicio | responder | texto | no | M3 100–101, M3 178–178, M3 156–156, M4 7–7, M2 98–98 | 0.0 | 2.6 | 6.1 |
| C06 | reeducacion de la marcha en parkinson | responder | texto | sí | M3 184–184, M3 36–37, M3 39–40, M3 36–36, M3 35–35 | 0.1 | 2.8 | 6.3 |
| C07 | ortesis artritis reumatoide | responder | texto | sí | M2 34–35, M2 36–36, M2 29–33, M2 43–43, M2 105–106 | 0.1 | 2.7 | 5.1 |
| C08 | sarcopenia ejercicios de fuerza | responder | texto | sí | M2 47–48, M2 43–47, M2 48–49, M2 49–50, M2 47–47 | 0.1 | 2.5 | 5.5 |
| C09 | ulceras por presion en lesion medular | responder | texto | sí | M3 95–95, M3 113–113, M3 86–89, M3 82–84, M3 90–91 | 0.1 | 2.7 | 7.0 |
| C10 | como manejar la disnea en epoc | responder | texto | sí | M4 41–42, M3 177–177, M4 21–21, M4 17–21, M4 22–22 | 0.1 | 2.5 | 7.3 |
| C11 | demencia apoyo al cuidador | responder | texto | sí | M3 146–146, M3 146–146, M3 148–149, M3 145–145, M3 145–146 | 0.1 | 2.1 | 4.8 |
| C12 | autismo intervenciones de comunicacion | responder | texto | no | M3 16–17, M3 15–16, M5 7–7, M3 152–153, M5 7–7 | 0.0 | 2.5 | 5.2 |
| C13 | espasticidad en paralisis cerebral | responder | texto | sí | M3 115–119, M3 119–120, M3 119–119, M3 119–119, M3 124–125 | 0.1 | 3.6 | 7.0 |
| C14 | protesis tras amputacion de miembro inferior | responder | texto | sí | M2 74–74, M2 77–77, M2 69–73, M2 102–102, M2 73–74 | 0.1 | 2.7 | 5.7 |
| C15 | que ejercicios para osteoartritis de rodilla | responder | texto | sí | M2 22–22, M2 19–20, M2 15–19, M2 19–20, M2 20–21 | 0.0 | 2.1 | 5.6 |
| C16 | evaluacion de kinesiofobia en lumbalgia | responder | texto | sí | M2 10–11, M2 1–7, M2 7–8, M2 9–10, M2 105–105 | 0.1 | 3.0 | 6.4 |
| A01 | lumbalgia | repreguntar | aclaracion | — | — | 0.1 | 1.0 | 1.0 |
| A02 | kinesiofobia | repreguntar | aclaracion | — | — | 0.1 | 1.2 | 1.2 |
| A03 | fracturas | repreguntar | aclaracion | — | — | 0.1 | 1.2 | 1.2 |
| A04 | epoc | repreguntar | aclaracion | — | — | 0.2 | 1.2 | 1.3 |
| A05 | parkinson | repreguntar | aclaracion | — | — | 0.1 | 1.5 | 1.5 |
| A06 | sarcopenia | repreguntar | aclaracion | — | — | 0.2 | 1.5 | 1.5 |
| A07 | demencia | repreguntar | aclaracion | — | — | 0.1 | 1.1 | 1.2 |
| A08 | ejercicios | repreguntar | aclaracion | — | — | 0.1 | 0.1 | 0.1 |
| H01 | ejercicios *(con historial)* | responder | texto | no | M2 98–98, M3 177–177, M3 177–177, M2 98–98, M4 39–39 | 0.1 | 2.7 | 5.2 |
| H02 | kinesiofobia *(con historial)* | repreguntar | aclaracion | — | — | 0.1 | 3.1 | 3.1 |
| H03 | y la evaluacion? *(con historial)* | responder | sin_material ✗ | no | — | 0.1 | 1.5 | 1.6 |
| H04 | parkinson *(con historial)* | repreguntar | aclaracion | — | — | 0.1 | 2.2 | 2.2 |
| H05 | quien lo hace? *(con historial)* | responder | texto | sí | M2 68–73, M5 22–27, M2 110–111 | 0.1 | 1.6 | 3.9 |
| P01 | Más detalle sobre lo anterior *(con historial)* | responder | texto | sí | M2 7–7, M2 7–8, M3 121–122, M2 1–7, M2 10–11, M2 9–10 | 0.1 | 2.2 | 6.2 |
| P02 | más detalle de la psicoterapia cognitivo-conductual *(con historial)* | responder | texto | sí | M2 101–101, M3 183–184, M5 54–54, M4 40–41, M5 54–54, M3 40–41 | 0.1 | 2.5 | 7.5 |
| P03 | Más detalle sobre lo anterior *(con historial)* | responder | texto | no | M4 22–22, M4 17–21, M4 21–22, M4 21–21, M4 21–21, M4 21–21 | 0.0 | 1.9 | 8.5 |
| P04 | ¿quién lo hace y cuánto dura cada sesión? *(con historial)* | responder | texto | sí | M2 49–50, M2 47–48, M2 43–47, M2 43–47, M4 36–37, M2 48–49 | 0.0 | 2.3 | 6.2 |
| X01 | esguince de tobillo | sin_material | sin_material | — | — | 0.1 | 0.5 | 0.5 |
| X02 | tratamiento de la fibromialgia | sin_material | sin_material | — | — | 0.1 | 2.7 | 2.7 |
| X03 | ejercicios para escoliosis idiopatica | sin_material | sin_material | — | — | 0.1 | 1.7 | 1.7 |
| X04 | rehabilitacion de ligamento cruzado anterior | sin_material | sin_material | — | — | 0.1 | 1.9 | 1.9 |
| X05 | linfedema despues de cancer de mama | sin_material | sin_material | — | — | 0.1 | 1.9 | 1.9 |
