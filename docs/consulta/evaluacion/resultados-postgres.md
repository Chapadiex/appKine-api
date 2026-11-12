# Resultados de la consulta a la biblioteca sobre PostgreSQL

- Fecha: 2026-11-12T05:00:11.384Z
- Base: akine_rag en PostgreSQL 18 + pgvector (datos transferidos de MySQL, embeddings sin regenerar)
- Corrido en dos tandas (A: C01..A03, B: A04..X05) para que no venza el token de acceso.
- Linea base MySQL: resultados-v2.md (Recall@5 18/23, repreguntar 10/10, sin material 5/5).

## Agregados

| Metrica | PostgreSQL | MySQL (v2) |
|---|---|---|
| Recall@5 de paginas | 18/23 (78 %) | 18/23 (78 %) |
| Acierto de repreguntar | 10/10 | 10/10 |
| Acierto de sin material | 5/5 | 5/5 |
| Sin material falso en responder | 0/23 | 0/23 |
| Errores | 0 | 0 |
| Primera palabra p50 / p95 (s), respuestas con texto | 12.3 / 19.1 | 2.8 / 3.7 |

## Por caso


| Id | Consulta | Esperado | Salida | Recall | Fuentes (libro p.desde–hasta) | 1.er evento (s) | 1.ª palabra (s) | Total (s) |
|---|---|---|---|---|---|---|---|---|
| C01 | como trabajar lumbalgia | responder | texto | sí | M2 1–7, M2 7–8, M2 10–11, M2 9–10, M2 7–7 | 0.2 | 10.3 | 13.8 |
| C02 | ejercicios fractura cadera | responder | texto | sí | M2 53–57, M2 57–57, M3 139–140, M2 58–59, M3 124–125 | 0.0 | 10.0 | 12.5 |
| C03 | epoc ejercicio | responder | texto | sí | M4 17–21, M4 21–21, M4 21–22, M3 176–177, M2 97–97 | 0.0 | 6.3 | 10.7 |
| C04 | acv hombro doloroso | responder | texto | sí | M3 9–10, M3 7–8, M3 37–38, M3 7–7, M2 74–74 | 0.0 | 15.1 | 17.8 |
| C05 | rehabilitacion cardiaca ejercicio | responder | texto | no | M3 178–178, M3 100–101, M5 52–52, M3 156–156, M2 97–97 | 0.0 | 13.1 | 17.2 |
| C06 | reeducacion de la marcha en parkinson | responder | texto | sí | M3 184–184, M3 36–37, M3 39–40, M3 36–36, M3 35–35 | 0.0 | 23.7 | 26.8 |
| C07 | ortesis artritis reumatoide | responder | texto | sí | M2 34–35, M2 29–33, M2 36–36, M2 43–43, M3 130–130 | 0.0 | 16.9 | 21.4 |
| C08 | sarcopenia ejercicios de fuerza | responder | texto | sí | M2 47–48, M2 43–47, M2 48–49, M2 49–50, M2 47–47 | 0.0 | 11.7 | 14.7 |
| C09 | ulceras por presion en lesion medular | responder | texto | sí | M3 113–113, M3 95–95, M3 86–89, M3 111–112, M3 90–91 | 0.0 | 16.3 | 20.5 |
| C10 | como manejar la disnea en epoc | responder | texto | sí | M4 41–42, M4 21–21, M4 17–21, M4 22–22, M4 21–22 | 0.0 | 17.2 | 21.2 |
| C11 | demencia apoyo al cuidador | responder | texto | sí | M3 146–146, M3 148–149, M3 146–146, M3 145–145, M3 145–146 | 0.0 | 3.9 | 6.7 |
| C12 | autismo intervenciones de comunicacion | responder | texto | no | M3 152–153, M3 16–17, M5 7–7, M3 42–43, M3 15–16 | 0.0 | 19.0 | 21.8 |
| C13 | espasticidad en paralisis cerebral | responder | texto | sí | M3 115–119, M3 119–120, M3 119–119, M3 119–119, M3 124–125 | 0.0 | 12.6 | 15.7 |
| C14 | protesis tras amputacion de miembro inferior | responder | texto | sí | M2 74–74, M2 77–77, M2 69–73, M2 102–102, M2 73–74 | 0.0 | 12.3 | 15.2 |
| C15 | que ejercicios para osteoartritis de rodilla | responder | texto | sí | M2 22–22, M2 19–20, M2 15–19, M2 19–20, M2 20–21 | 0.0 | 13.9 | 16.9 |
| C16 | evaluacion de kinesiofobia en lumbalgia | responder | texto | sí | M2 10–11, M2 1–7, M2 7–8, M2 9–10, M2 105–105 | 0.0 | 19.1 | 22.7 |
| A01 | lumbalgia | repreguntar | aclaracion | — | — | 0.0 | 0.4 | 0.4 |
| A02 | kinesiofobia | repreguntar | aclaracion | — | — | 0.0 | 0.2 | 0.3 |
| A03 | fracturas | repreguntar | aclaracion | — | — | 0.0 | 0.3 | 0.3 |
| A04 | epoc | repreguntar | aclaracion | — | — | 0.0 | 0.3 | 0.3 |
| A05 | parkinson | repreguntar | aclaracion | — | — | 0.0 | 0.3 | 0.3 |
| A06 | sarcopenia | repreguntar | aclaracion | — | — | 0.0 | 0.2 | 0.2 |
| A07 | demencia | repreguntar | aclaracion | — | — | 0.0 | 0.2 | 0.2 |
| A08 | ejercicios | repreguntar | aclaracion | — | — | 0.0 | 0.0 | 0.0 |
| H01 | ejercicios *(con historial)* | responder | texto | sí | M2 1–7, M2 7–8, M2 9–10, M2 10–11, M2 7–7 | 0.0 | 6.2 | 8.7 |
| H02 | kinesiofobia *(con historial)* | repreguntar | aclaracion | — | — | 0.1 | 1.7 | 1.7 |
| H03 | y la evaluacion? *(con historial)* | responder | texto | no | M3 148–148, M4 17–21, M3 121–122, M3 166–166, M4 21–21 | 0.0 | 12.0 | 15.0 |
| H04 | parkinson *(con historial)* | repreguntar | aclaracion | — | — | 0.0 | 2.2 | 2.2 |
| H05 | quien lo hace? *(con historial)* | responder | texto | no | M2 53–57, M2 57–57, M2 58–59, M3 139–140, M3 124–125, M2 68–73 | 0.0 | 5.4 | 7.6 |
| P01 | Más detalle sobre lo anterior *(con historial)* | responder | texto | sí | M2 7–7, M2 7–8, M3 121–122, M2 1–7, M2 10–11, M2 9–10 | 0.0 | 7.6 | 11.9 |
| P02 | más detalle de la psicoterapia cognitivo-conductual *(con historial)* | responder | texto | sí | M2 101–101, M3 183–184, M4 40–41, M5 54–54, M5 54–54, M3 40–41 | 0.0 | 9.2 | 15.2 |
| P03 | Más detalle sobre lo anterior *(con historial)* | responder | texto | no | M4 17–21, M4 35–35, M5 47–47, M3 166–166, M4 22–22, M2 91–91 | 0.0 | 8.3 | 15.6 |
| P04 | ¿quién lo hace y cuánto dura cada sesión? *(con historial)* | responder | texto | sí | M2 49–50, M2 47–48, M2 43–47, M2 43–47, M4 36–37, M2 48–49 | 0.0 | 13.5 | 18.0 |
| X01 | esguince de tobillo | sin_material | sin_material | — | — | 0.0 | 6.2 | 6.2 |
| X02 | tratamiento de la fibromialgia | sin_material | sin_material | — | — | 0.0 | 17.9 | 17.9 |
| X03 | ejercicios para escoliosis idiopatica | sin_material | sin_material | — | — | 0.0 | 4.7 | 4.7 |
| X04 | rehabilitacion de ligamento cruzado anterior | sin_material | sin_material | — | — | 0.0 | 12.3 | 12.3 |
| X05 | linfedema despues de cancer de mama | sin_material | sin_material | — | — | 0.0 | 11.1 | 11.1 |
