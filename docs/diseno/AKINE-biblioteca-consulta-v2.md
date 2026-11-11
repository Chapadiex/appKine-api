# AKINE — Consulta a la biblioteca, versión 2: interpretar, repreguntar y buscar mejor

> Estado: diseño, 11/11/2026. Sigue a `AKINE-biblioteca-consulta.md` (v1, en la rama) y a la prueba
> descartable del 11/11 (`spike-charla`). Ramas: `akine-biblioteca-rag` (`appKine-api`) y
> `akine-biblioteca-web` (`appKine-web`). Lo marcado **[decisión del agente]** lo propuso el agente y el
> usuario no lo objetó; lo demás lo dijo el usuario o salió medido de la prueba.

## 1. Por qué

El chat v1 funciona y es rápido en GPU, pero **no sirve como está** (palabras del usuario). Con la biblioteca
real (Paquete de intervenciones de rehabilitación de la OMS, módulos 2 a 5):

- "lumbalgia", "como trabajar lumbalgia" o "ejercicios" responden con la **introducción epidemiológica**,
  no con el tratamiento. El tratamiento está en **tablas** (M2 págs. 9–16) que la búsqueda no prioriza.
- Entre las fuentes aparecen la **página de derechos de autor** y el **índice**.
- Una repregunta corta ("ejercicios" después de hablar de lumbalgia) **pierde el tema**.
- `qwen2.5:3b` redacta mal: repite su instrucción, mezcla portugués y a veces dice "no se menciona" con el
  material delante.

Los profesionales **escriben corto** ("lumbalgia", "kinesiofobia", "epoc ejercicio"). El modelo está para
interpretar.

## 2. Cómo conversa (lo pidió el usuario)

1. **Primera respuesta escueta y al punto**, para ver si es lo que se necesita.
2. **Si la consulta no dice qué se busca** (una palabra o un tema suelto), **repreguntar**: "¿Qué buscás sobre
   lumbalgia?", con opciones.
3. **Después se puede profundizar** sobre cualquier punto ("más detalle de la psicoterapia", "¿quién la hace y
   cuánto dura?").

**[decisión del agente]**:
- Las opciones son **botones** (un clic manda la pregunta completa).
- Sólo se ofrecen aspectos que **la biblioteca tiene** para ese tema: definición, evaluación, intervenciones,
  ejercicios, profesionales.

## 3. Medido en la prueba (11/11, GPU RTX 4050)

| | Hoy (v1) | Prueba |
|---|---|---|
| "lumbalgia" | Epidemiología | Repregunta con 5 botones, en 1,6–2,6 s |
| Intervenciones para lumbalgia | Párrafo general | Viñetas con las intervenciones de la tabla y su para qué |
| "ejercicios fractura cadera" | La lista de autores | Fortalecimiento y flexibilidad (págs. 57–58), con lo que falta aclarado |
| "kinesiofobia" / "acv hombro" | "No encontré material" | Encuentra el material (M2 p.11 / M3) |

- La **ambigüedad no la detecta ningún modelo chico**: la tiene que decidir una regla.
- `qwen3:4b-instruct` redacta **claramente mejor** que `qwen2.5:3b`, a ~8 s por respuesta en GPU contra ~3,5 s.

## 4. Qué se construye

### 4.1 Interpretación de la consulta (backend, antes de buscar)

- **Regla de ambigüedad [decisión del agente]:** sin historial, una consulta de 1 a 3 palabras que no tenga
  intención es ambigua. Intención = interrogativos o verbos como qué, cómo, cuál, tratar, intervenir,
  ejercitar, evaluar, definir, rehabilitar, quién, dosis, tiempo, detalle, más. **No llama al modelo.**
- **Consulta con intención o con historial:** una llamada al modelo en modo JSON devuelve:
  - `tema`: con el tema **heredado de la charla** si la consulta no lo nombra ("ejercicios" después de
    lumbalgia → tema "lumbalgia");
  - `intencion`: definición, evaluación, tratamiento, ejercicios, profesionales u otra;
  - `pregunta_completa`: autosuficiente, sin agregar temas que no se nombran.

  Reemplaza a la reescritura de v1. Con siglas, el modelo las escribe completas. Si la llamada falla, se usa
  el respaldo de v1 (pregunta anterior + nueva).
- **Profundizar [decisión del agente]:** consulta con historial que pide más detalle (más, detalle,
  profundizá, explicá, quién, cuánto, cómo se hace) → modo **profundo**; si no → modo **breve**.

### 4.2 Repregunta

Si la consulta es ambigua:

1. Búsqueda vectorial del tema por cada aspecto ("qué es X", "evaluación de X", "intervenciones de
   rehabilitación de X", "ejercicios de X", "profesionales que intervienen en X").
2. Se ofrecen los aspectos con evidencia. El criterio lo fija el set de evaluación (§4.6); en la prueba, mejor
   puntaje ≥ 0,84.
3. Se responde con un evento nuevo `aclaracion`: `{ texto, opciones: [{ etiqueta, pregunta }] }`. Sin
   generación.
4. Si ningún aspecto tiene evidencia, se responde el aviso de "sin material".

### 4.3 Búsqueda híbrida

- **Por cada consulta de búsqueda** (la pregunta completa y "aspecto + tema"):
  - vectorial con e5, hasta **20** candidatos;
  - por palabras con el **FULLTEXT de MySQL** que ya existe, hasta **20**.
- **Fusión:** Reciprocal Rank Fusion con **k = 60**. Sin duplicados (mismo pedazo).
- **[decisión del agente]:** si la charla ya tiene fuentes, los pedazos del mismo libro suman un bonus de
  ranking. No es filtro: otro libro puede ganar.
- Todo dentro de la organización: los mismos filtros de tenant, activo, LISTO y no sospechoso que v1.
  **Nunca** se busca global para filtrar después.
- **Configurable** en `akine.biblioteca.consulta.busqueda`: topK por camino, k de RRF, cantidad de pasajes al
  modelo y bonus.

### 4.4 Evidencia (reemplaza "2 sobre 0,818")

Hay material si, después de la fusión, existen **al menos N pasajes** que cumplen alguna de estas dos
condiciones:

- superan el corte vectorial calibrado;
- tienen coincidencia por palabras con el `tema`.

N y el corte salen del set de evaluación (§4.6). **No se fija otro número a mano.**

### 4.5 Ingesta y reindexado

En el troceado (`ProcesamientoDeLibroService` / `Troceador`):

- **Fuera del índice:** páginas de relleno (derechos de autor, catalogación, índice, miembros de grupos de
  trabajo, listas de referencias) y **encabezados o pies repetidos**, es decir, líneas idénticas en más del 30 %
  de las páginas **[decisión del agente]**.
- **Tablas, en la medida de lo posible:** extraer por posición (PDFBox ordenado), unir los rótulos verticales
  partidos ("A ct iv id ad e s" → "Actividades") y cortar **por párrafo o fila** antes que cada 1.000
  caracteres. Si una tabla no se puede reparar, queda como hoy.
- **Versión del procesamiento** en el libro. Al cambiar, los libros se reprocesan con el mecanismo de reemplazo
  existente, sin perder el índice anterior hasta que el nuevo esté LISTO.

### 4.6 Set de evaluación y medición

- **~30 consultas escritas como un profesional** (cortas, con siglas), sobre M2–M5, cada una con las páginas
  esperadas.
  - Las arma el agente leyendo los módulos y **las revisa el usuario [decisión del agente]**.
  - Incluye ambiguas, repreguntas, "profundizar" y temas que no están.
- **Script repetible** (`docs/consulta/evaluacion/`) que mide:
  - Recall@5 de páginas;
  - aciertos de "repreguntar sí o no";
  - tiempos.

  Corre antes y después.
- **Tiempos por etapa** en el log `INFO` y en métricas, sin texto:
  - interpretación;
  - búsqueda vectorial y por palabras;
  - fusión;
  - primera palabra;
  - total;
  - y de Ollama: `prompt_eval`, `eval_count` y `eval_duration`.

### 4.7 Respuesta

- **Modelo de respuesta: `qwen3:4b-instruct`** por defecto (configurable; se mantiene el de 3 B como
  alternativa). La interpretación usa el mismo modelo **[decisión del agente]**: dos modelos no entran juntos en
  6 GB con margen.
- **5 pasajes** al modelo.
- **Breve:** 3 a 6 viñetas de una línea con para qué sirve, sin introducción, 250 tokens.
- **Profundo:** hasta 8 viñetas (objetivo, cómo, quién, tiempo, materiales), 450 tokens.
- **Prompt:**
  - usar sólo los pasajes, sin mezclar afecciones;
  - si falta algo de lo pedido, **decir qué falta**;
  - el rechazo total queda sólo para el control de evidencia, no para el modelo.

### 4.8 Contrato y pantalla

- **Contrato:** aditivo, minor siguiente.
  - Evento `aclaracion`.
  - Evento `etapa` (`buscando`, `redactando`), que resuelve también el "En cola" que quedaba visible (R12 de
    v1).
- **Pantalla:**
  - la repregunta con sus opciones como botones;
  - "Buscando en la biblioteca…" y "Redactando…" según `etapa`;
  - **[decisión del agente]** un botón "Profundizar" bajo cada respuesta breve, que manda "Más detalle sobre
    lo anterior".

### 4.9 Precarga

- Al arrancar con la consulta prendida, el backend pide a Ollama cargar el modelo (pedido vacío con
  `keep_alive`). Así la primera consulta no se come los 1–3 minutos.
- `keep_alive` pasa a 2 h **[decisión del agente]**.

## 5. Lo que no se hace

- Guardar conversaciones.
- Reranker.
- OCR.
- Dashboard con p50/p95: alcanzan las métricas por etapa.
- Clasificar con el modelo si repreguntar.

## 6. Cómo se sabe que funciona

Con el set de evaluación:

- Recall@5 de páginas **mejor que v1** (se mide v1 primero).
- Las ambiguas repreguntan y las claras no, en ≥ 90 %.
- Ninguna respuesta con datos que no estén en sus fuentes, revisado a mano en una muestra de 10.
- "Sin material" correcto en los temas ausentes.
- Primera palabra < 3 s y repregunta < 3 s en GPU.

## 7. Precisiones de la revisión del diseño

1. **Material de evaluación.** Los cuatro módulos están cargados en la base local `akine_rag`
   (`libro` 3 a 6; los archivos están en `wt-API-RAG/var/archivos-rag`). El texto por página se lee de
   `libro_fragmento`. El set de evaluación y la calibración se hacen sobre esa base.
2. **Latencia [decisión del agente]:** manda la calidad, porque es lo que pidió el usuario. El objetivo pasa a
   ser **primera palabra < 5 s y repregunta < 3 s** en GPU. Si no se cumple con `qwen3:4b-instruct`
   interpretando y redactando, se prueba interpretar con `qwen2.5:3b`, que entra junto al otro en 6 GB, antes de
   ceder calidad.
3. **Consulta corta con historial [decisión del agente]:** si tiene 1 a 3 palabras, no tiene intención y hay
   historial:
   - el modelo indica si el tema es el de la charla o uno nuevo (`tema_nuevo: true|false` en el JSON);
   - con el tema de la charla ("ejercicios" después de lumbalgia) se responde heredándolo;
   - con un tema nuevo ("kinesiofobia" después de lumbalgia) se repregunta como si no hubiera historial.
4. **Profundizar [decisión del agente]:** se busca con la pregunta completa del punto a profundizar, por
   ejemplo "psicoterapia cognitivo-conductual para lumbalgia". Se pasan **6 pasajes**, y los del mismo libro y
   las páginas vecinas a las fuentes de la respuesta anterior suman bonus. Así llega el detalle y no los mismos 5
   pasajes.
5. **Ambigua sin afección [decisión del agente]:** "ejercicios", "dosis" o "evaluación" sueltos, sin historial,
   repreguntan "¿Para qué afección o tema?" sin botones: no hay de dónde sacar opciones confiables.
6. **Reproceso por versión [decisión del agente]:** automático, en segundo plano. El worker existente toma un
   libro por vuelta cuando su versión de procesamiento es menor que la vigente, y mantiene el índice anterior
   hasta que el nuevo esté LISTO. La migración rellena la versión de los libros existentes con 1; la vigente
   pasa a 2.
7. **Coincidencia por palabras [decisión del agente]:** un pasaje "coincide con el tema" si aparece entre los
   20 candidatos del FULLTEXT **y** su texto contiene, sin distinguir mayúsculas ni tildes, alguna palabra del
   `tema` de 4 letras o más, o la sigla desarrollada.

## 8. Design challenge

1. **Ownership.** Todo sigue en `knowledge`. La versión de procesamiento es una columna de `libro` (migración
   nueva).
2. **Ciclos.** No cambian.
3. **Tenant.** Las dos búsquedas filtran por organización en la consulta.
4. **Reglas maestras.** No toca HC, turnos ni dinero.
5. **Baja lógica.** El reindexado usa el reemplazo existente.
6. **Contrato.** Aditivo.
7. **Ruta crítica.** Se apoya en v1, que está en la misma rama.
8. **El caso que rompe el diseño.** El FULLTEXT de MySQL con stopwords y palabras cortas ("ACV", "EPOC")
   puede no encontrar siglas de 3 o 4 letras (`innodb_ft_min_token_size` = 3 por defecto). La interpretación
   las escribe completas y la vectorial las cubre igual. Se verifica en el set de evaluación.
