# Consulta a la biblioteca v2 — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el chat entienda consultas cortas, repregunte con opciones cuando no queda claro qué se busca, encuentre las tablas de tratamiento con búsqueda híbrida y responda breve o en profundidad con `qwen3:4b-instruct`, sin respuestas cruzadas entre preguntas.

**Architecture:** Todo en el módulo `knowledge`:
- un set de evaluación con su script mide antes y después;
- la ingesta limpia el relleno y trocea por párrafo (con reproceso por versión);
- `BusquedaHibrida` fusiona vector y FULLTEXT con RRF;
- `Interpretador` decide entre repreguntar y responder;
- `ConsultaService` y `ConsultaController` suman los eventos `aclaracion` y `etapa`.

La pantalla muestra botones y estados.

**Tech Stack:** Java 21, Spring Boot 4.1.x, MySQL 8.4 (FULLTEXT existente), PDFBox 3.0.8, e5 ONNX, Ollama `/api/chat` (`format: json`); Angular 22.

**Spec:** `docs/diseno/AKINE-biblioteca-consulta-v2.md` (aprobado 11/11/2026), sobre `docs/diseno/AKINE-biblioteca-consulta.md` (v1).

## Global Constraints

- **Ramas:** backend `C:\Users\santo\Desktop\AKINE\wt-API-RAG` (`akine-biblioteca-rag`); frontend `C:\Users\santo\Desktop\AKINE\wt-WEB-RAG` (`akine-biblioteca-web`).
- **Commits:** locales, en prosa, con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Sin push, PR ni merge.
- **Migración `V145`** (reservarla en `docs/fases/01-trabajo-en-paralelo.md`). **Contrato `0.166.0`**, aditivo.
- **Base local de evaluación:** `akine_rag` (libros 3–6 = módulos M2–M5 de la OMS). Pila: configuraciones `akine-api-rag-onnx` (8098) y `akine-web-rag` (4298) de `C:\Users\santo\Desktop\AKINE\.claude\launch.json`; Ollama `akine-ollama` en GPU (11434); cuenta en `C:\Users\santo\Desktop\AKINE\.claude\rag-cuentas.md`. **No compilar en `wt-API-RAG` mientras corre el backend de la pila:** bajarlo antes y avisarlo en el informe.
- **Sin persistencia ni caché entre consultas:**
  - no se guardan preguntas, respuestas ni búsquedas;
  - los logs y métricas llevan sólo tiempos, cantidades y resultado, nunca texto.
- **Preguntas independientes:** una consulta que nombra su tema no hereda nada de la charla y **no manda historial al modelo que redacta**. El historial sólo completa consultas sin tema.
- **Regla de ambigüedad:** sin historial, 1 a 3 palabras **sin** intención es ambigua. Intención = regex sin tildes ni mayúsculas sobre `que|como|cual|trat|interv|ejerc|eval|defin|rehab|profesion|quien|dosis|tiempo|detalle|mas|explic|profundiz`. Con historial ver la Tarea 4.
- **Aspectos de la repregunta:**

  | Clave | Texto de búsqueda | Etiqueta del botón |
  |---|---|---|
  | `definicion` | "qué es" | "¿Qué es {tema}?" |
  | `evaluacion` | "evaluación de" | "Evaluación de {tema}" |
  | `tratamiento` | "intervenciones de rehabilitación para" | "Intervenciones de rehabilitación para {tema}" |
  | `ejercicios` | "ejercicios para" | "Ejercicios para {tema}" |
  | `profesionales` | "profesionales que intervienen en" | "Profesionales que intervienen en {tema}" |

- **Búsqueda híbrida (configurable en `akine.biblioteca.consulta.busqueda.*`):**

  | Parámetro | Valor |
  |---|---|
  | `top-k-vector` | 20 |
  | `top-k-palabras` | 20 |
  | `rrf-k` | 60 |
  | `pasajes-breve` | 5 |
  | `pasajes-profundo` | 6 |
  | `bonus-libro` | 0,005 (se suma al RRF) |

- **Evidencia:** hay material si hay ≥ `minimo-pasajes` pasajes fusionados que superan el corte vectorial **o** coinciden por palabras con el tema. "Coincide" = está en el top FULLTEXT **y** su texto contiene, sin tildes ni mayúsculas, alguna palabra del tema de ≥ 4 letras. Valores iniciales: `minimo-pasajes=2`, `corte-vector=0.818`; se calibran en la Tarea 7.
- **Modelo:**
  - por defecto `qwen3:4b-instruct`;
  - `keep-alive` 2 h;
  - interpretación con temperatura 0 y `format: json`;
  - modo breve: 3 a 6 viñetas de una línea con para qué sirve, sin introducción, 250 tokens, temperatura 0,2;
  - modo profundo: hasta 8 viñetas (objetivo, cómo, quién, tiempo, materiales), 450 tokens.
- **Textos exactos:**
  - sin material: `No encontré material en la biblioteca para responder eso.`
  - repregunta: `¿Qué buscás sobre {tema}?`
  - ambigua sin afección: `¿Para qué afección o tema?`
  - etapas en pantalla: `Buscando en la biblioteca…` / `Redactando…`
  - botón: `Profundizar`, que manda `Más detalle sobre lo anterior`.
- **Objetivos de latencia en GPU:** primera palabra < 5 s, repregunta < 3 s.
- **Tests:** pocos, sin TDD obligatorio.

  | Repo | Comando |
  |---|---|
  | Backend, unitarios | `./mvnw -o test -Dtest="com.akine.knowledge.**.*Test,ModuleArchitectureTest,CodingConventionsTest,SchemaNameCollisionTest" -Djacoco.skip=true` |
  | Backend, ITs | `./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<IT> -Djacoco.skip=true` |
  | Frontend | `npx ng test --watch=false --no-coverage --include "src/app/features/biblioteca/**/*.spec.ts"` |

## Review Focus

1. **Respuestas cruzadas:** "qué es lumbalgia" seguido de "ejercicios para manguito rotador" no trae fuentes ni texto de lumbalgia, ni siquiera con las dos consultas en paralelo desde dos sesiones (Tarea 5, IT).
2. **Repregunta indebida:** "como trabajar lumbalgia" o "ejercicios fractura cadera" **no** repreguntan; "lumbalgia" y "kinesiofobia" sí (Tarea 4, unitarios, y Tarea 7, set).
3. **Relleno en las fuentes:** después del reproceso, ninguna fuente es la página de derechos de autor, el índice, los miembros de grupos ni las referencias (Tarea 2, unitarios, y Tarea 7, medición).
4. **Reproceso que deja la biblioteca vacía:** durante el reproceso por versión, la búsqueda sigue devolviendo el índice anterior (Tarea 2, IT).
5. **Ollama caído o JSON inválido en la interpretación:** se responde igual, con la pregunta tal cual (Tarea 4, unitario).

---

### Tarea 1: Set de evaluación y medición de v1 (línea base)

**Files (backend):**
- Create:
  - `docs/consulta/evaluacion/consultas.json`
  - `docs/consulta/evaluacion/medir.mjs` (Node 24, sin dependencias)
  - `docs/consulta/evaluacion/README.md`
  - `docs/consulta/evaluacion/resultados-v1.md`
- Modify: `docs/fases/01-trabajo-en-paralelo.md`: reservar `V145` (consulta v2, reproceso por versión).

**Interfaces:**
- Produces: `consultas.json` = lista de `{ "id", "consulta", "historial": [{"rol","texto"}], "esperado": "responder|repreguntar|sin_material", "libro": "M2|M3|M4|M5", "paginas": [n,…], "nota" }`, con al menos 30 casos:
  - ≥ 12 claras y cortas (por ejemplo "como trabajar lumbalgia", "ejercicios fractura cadera", "epoc ejercicio", "acv hombro doloroso");
  - ≥ 6 ambiguas (una palabra o un tema suelto);
  - ≥ 4 repreguntas con historial (de las cuales 2 cambian de tema, como "kinesiofobia" después de lumbalgia);
  - ≥ 3 de "profundizar";
  - ≥ 4 temas ausentes.

  Las páginas salen de **leer el texto real** en `akine_rag.libro_fragmento`, nunca de memoria.
- Produces: `medir.mjs --base http://localhost:8098 --salida <archivo>`.
  - Entra con la cuenta de `.claude/rag-cuentas.md`.
  - Corre cada caso contra `POST /biblioteca/consultas` con lector SSE.
  - Registra: tipo de salida (texto, aclaración o sin material), páginas de las fuentes, ms hasta el primer evento, ms hasta la primera palabra y ms totales.
  - Calcula: **Recall@5 de páginas** (alguna página esperada ±1 entre las fuentes), **acierto de "repreguntar"**, **acierto de "sin material"** y los percentiles 50/95 de los tiempos.
  - Escribe una tabla Markdown. Sin v2 (eventos `aclaracion`/`etapa` ausentes), una aclaración cuenta como "no repreguntó".

- [ ] **Paso 1:** Leer los módulos en la base, por ejemplo `SELECT libro_id, pagina_desde, LEFT(texto, 400) FROM libro_fragmento WHERE libro_id IN (3,4,5,6) …`, y escribir `consultas.json`. El `README.md` explica cómo correr y cómo agregar casos. **El usuario revisa el set:** marcarlo en el informe como pendiente de su revisión.
- [ ] **Paso 2:** `medir.mjs`. Correrlo contra la pila v1 de hoy y guardar `resultados-v1.md`.
- [ ] **Paso 3:** Commit: "docs(knowledge): set de evaluación de la consulta y medición de v1".

### Tarea 2: Ingesta v2 — sin relleno, troceado por párrafo, reproceso por versión

**Files (backend):**
- Create:
  - `src/main/resources/db/migration/V145__m30_version_de_procesamiento.sql`: `ALTER TABLE libro ADD version_procesamiento INT NOT NULL DEFAULT 1`.
  - `src/main/java/com/akine/knowledge/domain/LimpiadorDePaginas.java`
- Modify:
  - `domain/Troceador.java`
  - `infrastructure/PdfBoxExtractorDeTexto.java`
  - `domain/Libro.java` (campo `versionProcesamiento`)
  - `application/ProcesamientoDeLibroService.java`
  - `infrastructure/ProcesamientoWorker.java`, o un worker nuevo `ReprocesoPorVersionWorker` si queda más claro
  - `infrastructure/config/BibliotecaProperties.java` (`version-procesamiento-vigente` = 2)
- Test:
  - `LimpiadorDePaginasTest`
  - `TroceadorTest` (ampliado)
  - `ReprocesoPorVersionIT`

**Interfaces:**
- Produces: `List<PaginaDeTexto> LimpiadorDePaginas.limpiar(List<PaginaDeTexto> paginas)`.
  - **Descarta** las páginas de relleno: derechos reservados, catalogación (CIP), ISBN, "notas de descargo", índice (≥ 5 líneas que terminan en número de página), "miembros de los grupos de trabajo" y listas de referencias (≥ 50 % de líneas que empiezan con número y autor).
  - **Quita** las líneas repetidas en más del 30 % de las páginas (encabezados y pies).
  - **Une** los rótulos verticales partidos: una secuencia de fragmentos de 1 a 3 letras separados por espacio pasa a ser una palabra ("A ct iv id ad e s d e la v id a d ia ri a" → "Actividades de la vida diaria", con un umbral de ≥ 5 fragmentos consecutivos).
- Produces: `Troceador.trocear` corta **en límite de párrafo o renglón**, con el tamaño y el solape actuales como máximos. Nunca corta una palabra; sólo corta una oración si el párrafo supera el máximo.
- Produces: `PdfBoxExtractorDeTexto` usa `setSortByPosition(true)` y conserva los saltos de línea.
- Produces: el reproceso por versión.
  - Un libro LISTO con `versionProcesamiento < vigente` se reprocesa en segundo plano, **uno por vuelta**, con el **mecanismo de reemplazo existente** (archivo idéntico, `reemplaza_libro_id`).
  - El índice anterior responde hasta que el nuevo queda LISTO.
  - El libro nuevo nace con `versionProcesamiento = vigente`.
  - Si el reproceso falla, el libro viejo sigue y no se reintenta más de `intentos` veces.

- [ ] **Paso 1:** `LimpiadorDePaginas` y su test.
  - `descartaLaPaginaDeDerechos`: con el texto real de M2 pág. 4.
  - `descartaElIndice`: con M2 pág. 5.
  - `quitaElEncabezadoRepetido`: 10 páginas con "Paquete de intervenciones de rehabilitación Módulo 2" arriba.
  - `uneRotulosVerticales`.
  - `conservaPaginasDeContenido`: con M2 pág. 9.
- [ ] **Paso 2:** El troceado por párrafo, con `TroceadorTest.noPartePalabrasYRespetaParrafos`, y el extractor por posición.
- [ ] **Paso 3:** `V145`, el campo y el reproceso. `ReprocesoPorVersionIT`: libro con versión 1 → mientras se reprocesa, `BibliotecaDirectory.buscar` sigue devolviendo pasajes del libro viejo → al final, un solo libro activo, con versión 2 y sin fragmentos de la página de derechos.
- [ ] **Paso 4:** Unitarios de `knowledge`, `ReprocesoPorVersionIT`, `ProcesamientoDeLibroIT` y `BibliotecaMigracionIT` en verde. Commit: "feat(knowledge): ingesta sin relleno, troceado por párrafo y reproceso por versión".

### Tarea 3: Búsqueda híbrida con RRF y política de evidencia

**Files (backend):**
- Create:
  - `src/main/java/com/akine/knowledge/application/BusquedaHibrida.java`
  - `application/PasajeFusionado.java`
  - `application/ResultadoHibrido.java`
- Modify: `infrastructure/config/BibliotecaProperties.java` (`Consulta.Busqueda`: los valores de Global Constraints más `minimo-pasajes` y `corte-vector`).
- Test:
  - `BusquedaHibridaTest` (con un `AlmacenVectorial` falso)
  - `BusquedaHibridaIT` (MySQL real, dos organizaciones)

**Interfaces:**
- Consumes: `AlmacenVectorial.porVector(orgId, modelo, float[], k)` y `porPalabras(orgId, consulta, k)`, que devuelven `Coincidencia(fragmentoId, BigDecimal puntaje)`; el modelo de embeddings vigente (`ModeloEmbeddings`); la hidratación de fragmentos que ya usa `BusquedaService`.
- Produces:
  ```java
  public record PasajeFusionado(PasajeRecuperado pasaje, double rrf, boolean superaCorte, boolean coincidePalabra) {}
  public record ResultadoHibrido(CaminoBusqueda camino, List<PasajeFusionado> pasajes, boolean hayEvidencia) {}
  // BusquedaHibrida
  ResultadoHibrido buscar(long orgId, List<String> consultas, String tema, Set<Long> librosPreferidos);
  ```
  - Por consulta: vector top 20 y palabras top 20.
  - Fusión RRF con `Σ 1/(k + posición)`, posición desde 1, sin duplicados por `fragmentoId`.
  - `bonus-libro` para `librosPreferidos`.
  - Orden por RRF descendente.
  - `hayEvidencia` según Global Constraints.
  - Si el modelo de embeddings falla: sólo palabras, camino `PALABRAS`.
  - Filtros de tenant, activo, LISTO y no sospechoso **dentro** de las dos consultas SQL, como hoy.
  - El embedding de cada consulta se calcula una sola vez.

- [ ] **Paso 1:** `BusquedaHibridaTest`.
  - `fusionaPorPosicionSinDuplicados`: el mismo fragmento en las dos listas suma.
  - `bonusDeLibroSoloReordena`.
  - `evidenciaConPalabrasAunqueNoSupereElCorte`.
  - `sinNadaNoHayEvidencia`.
  - `sinModeloCaeAPalabras`.
- [ ] **Paso 2:** `BusquedaHibridaIT`: con dos organizaciones con libros, buscar en A nunca devuelve fragmentos de B, ni por vector ni por palabras.
- [ ] **Paso 3:** Tests en verde. Commit: "feat(knowledge): búsqueda híbrida con fusión RRF y política de evidencia".

### Tarea 4: Interpretación de la consulta y repregunta

**Files (backend):**
- Create:
  - `src/main/java/com/akine/knowledge/application/Interpretador.java`
  - `application/Interpretacion.java`
  - `application/OpcionDeAclaracion.java`
  - `src/main/resources/knowledge/prompt-interpretacion.txt`
- Modify:
  - `domain/port/OpcionesGeneracion.java` (`boolean json`)
  - `infrastructure/OllamaModeloDeLenguaje.java` (`"format":"json"` si `json`)
  - `infrastructure/ModeloDeLenguajeDePrueba.java` (JSON de prueba configurable)
- Test: `InterpretadorTest`

**Interfaces:**
- Consumes: `ModeloDeLenguaje.completar`, `BusquedaHibrida` (para las opciones), `TurnoDeConsulta`.
- Produces:
  ```java
  public enum Intencion { DEFINICION, EVALUACION, TRATAMIENTO, EJERCICIOS, PROFESIONALES, OTRA }
  public record OpcionDeAclaracion(String etiqueta, String pregunta) {}
  public sealed interface Interpretacion {
      record Aclarar(String texto, List<OpcionDeAclaracion> opciones) implements Interpretacion {}
      record Buscar(String tema, Intencion intencion, String preguntaCompleta, List<String> consultas,
                    boolean independiente, boolean profundo) implements Interpretacion {}
  }
  // Interpretador
  Interpretacion interpretar(long orgId, String pregunta, List<TurnoDeConsulta> historial, Instant limite);
  ```
- Reglas:
  - **Sin historial, ambigua por la regla:** sin llamar al modelo, `tema` = la consulta. Las opciones son los aspectos (Global Constraints) cuya búsqueda vectorial `"{texto} {tema}"` tiene el mejor puntaje ≥ `corte-vector`; la `pregunta` del botón es la etiqueta.
    - Sin opciones → `Buscar` normal: el control de evidencia decide "sin material".
    - Si la consulta es un aspecto sin afección ("ejercicios", "dosis", "evaluación") → `Aclarar("¿Para qué afección o tema?", [])`.
  - **Con intención o con historial:** `completar` con `prompt-interpretacion.txt` (temperatura 0, JSON, 160 tokens). Devuelve `{tema, tema_nuevo, intencion, pregunta_completa}`.
    - Con `tema_nuevo=true` y 1 a 3 palabras sin intención → se aplica la regla de ambigüedad sobre el tema nuevo.
    - `independiente` = `tema_nuevo` **o** sin historial **o** la consulta nombra un tema distinto del de la charla.
  - `consultas` = `[preguntaCompleta, "{texto del aspecto de la intención} {tema}"]`.
  - `profundo` = hay historial **y** la consulta matchea `mas|detalle|profundiz|explic|quien|cuanto|como se hace`.
  - **JSON inválido, modelo caído o plazo vencido:** `Buscar(tema=pregunta, OTRA, pregunta, [pregunta], independiente=true, profundo=false)`, el mismo respaldo concatenado de v1 si hay historial.
  - El intérprete **nunca agrega temas que la consulta y la charla no nombran**: el prompt lo dice y un test lo controla con un modelo de prueba que agrega "ACV".

- [ ] **Paso 1:** `InterpretadorTest`.
  - `palabraSueltaRepreguntaSinLlamarAlModelo` (llamadas = 0).
  - `ofreceSoloAspectosConEvidencia`.
  - `consultaConIntencionNoRepregunta` ("como trabajar lumbalgia").
  - `aspectoSinAfeccionPideTema`.
  - `repreguntaDeMismoTemaHereda` ("ejercicios" después de lumbalgia → tema "lumbalgia", independiente=false).
  - `temaNuevoEsIndependiente` ("kinesiofobia" después de lumbalgia → `Aclarar`).
  - `jsonInvalidoUsaRespaldo`.
  - `profundizarDetectado`.
- [ ] **Paso 2:** Tests en verde. Commit: "feat(knowledge): interpretación de la consulta y repregunta con opciones".

### Tarea 5: ConsultaService/Controller v2, contrato 0.166.0, modelo y precarga

**Files (backend):**
- Modify:
  - `application/ConsultaService.java`
  - `application/PresupuestoDeContexto.java`
  - `api/ConsultaController.java`
  - `api/ConsultaEventosEnElContrato.java`
  - `src/main/resources/knowledge/prompt-consulta.txt`
- Create:
  - `src/main/resources/knowledge/prompt-profundo.txt`
  - `api/dto/ConsultaEventoAclaracion.java`
  - `api/dto/OpcionDeAclaracionResponse.java`
  - `api/dto/ConsultaEventoEtapa.java`
  - `infrastructure/PrecargaDelModelo.java`
- Modify:
  - `infrastructure/config/BibliotecaProperties.java` (defaults `modelo=qwen3:4b-instruct`, `keep-alive=2h`)
  - `infrastructure/OllamaModeloDeLenguaje.java` (devolver `prompt_eval_count`, `prompt_eval_duration`, `eval_count`, `eval_duration` de la última respuesta del stream, en un record `EstadisticasDeGeneracion`)
  - `pom.xml` y `application.yml` (`0.166.0`)
  - `openapi/akine-api.yaml` (regenerado)
  - `compose.yaml`, el runbook R-consulta y el diseño v2 (`qwen3:4b-instruct`)
- Test: `ConsultaServiceTest` (ampliado) y `ConsultaIT` (ampliado)

**Interfaces:**
- Consumes: `Interpretador.interpretar`, `BusquedaHibrida.buscar` y `ModeloDeLenguaje.generar`.
- Produces: los eventos SSE nuevos.
  - `aclaracion` = `ConsultaEventoAclaracion(texto, List<OpcionDeAclaracionResponse(etiqueta, pregunta)>)`, seguido de `fin(sinMaterial=false, camino)`. **Sin** `fuentes` ni `texto`.
  - `etapa` = `ConsultaEventoEtapa(String etapa)`, con `buscando` al tomar el turno y `redactando` antes del primer `texto`.

  Los eventos de v1 siguen iguales.
- Flujo de `preparar`:
  1. `interpretar`.
  2. Si `Aclarar`, se emite y termina.
  3. `buscar(orgId, consultas, tema, librosPreferidos)`, con `librosPreferidos` = los libros de las fuentes del último turno **sólo si `!independiente`**. El cliente no manda fuentes: se deducen buscando el último turno del asistente con la misma búsqueda, o se dejan vacíos si es costoso.
  4. Sin evidencia → el texto exacto de "sin material".
  5. Con evidencia → los pasajes (5 en breve, 6 en profundo) y `mensajes` = system (breve o profundo) + material + **pregunta completa**. El historial va **sólo si `!independiente`**, y sólo las preguntas del usuario.
- `PrecargaDelModelo`: al arrancar con la consulta prendida, un pedido sin mensajes con `keep_alive`, en segundo plano. Si Ollama no responde, sólo un `warn`.
- Log `INFO` por consulta: `resultado`, `ms_cola`, `ms_interpretar`, `ms_buscar`, `ms_primera_palabra`, `ms_total`, `pasajes`, `tokens_entrada`, `tokens_salida` y `tokens_por_segundo`. Métricas Micrometer con los mismos tiempos (timers `akine.consultas.etapa{etapa}`). Sin texto.

- [ ] **Paso 1:** Servicio y controller como se describe. Contrato regenerado con `./mvnw verify -Dakine.contract.update=true -Dit.test=OpenApiContractIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- [ ] **Paso 2:** `ConsultaServiceTest`.
  - `independienteNoMandaHistorialAlModelo`.
  - `repreguntaDeMismoTemaUsaHistorialYLibroPreferido`.
  - `aclaracionNoLlamaAGenerar`.
  - `profundoUsaSeisPasajesYPromptProfundo`.
- [ ] **Paso 3:** `ConsultaIT` (modelo de prueba).
  - `palabraSueltaDevuelveAclaracion`: evento `aclaracion` con opciones y sin `fuentes`.
  - `etapasEnOrden`: `etapa(buscando)` … `etapa(redactando)` … `texto` … `fin`.
  - `sinRespuestasCruzadas`: "qué es lumbalgia" y después "ejercicios para manguito rotador" con historial, en libros sembrados de lumbalgia y de hombro. Las fuentes de la segunda son todas del libro de hombro. Además, las dos consultas lanzadas en paralelo con dos tokens no comparten fuentes.
  - Los tests de v1 siguen en verde.
- [ ] **Paso 4:** Unitarios, `ConsultaIT`, `OpenApiContractIT` y `ModuleArchitectureTest` en verde. Commit: "feat(knowledge): consulta v2 con repregunta, búsqueda híbrida y qwen3:4b-instruct; contrato 0.166.0".

### Tarea 6: Frontend — repregunta con botones, etapas y Profundizar

**Files (frontend, `wt-WEB-RAG`):**
- Regenerar el cliente 0.166.0 desde `../wt-API-RAG/openapi/akine-api.yaml` (`contractVersion` en los `environment*.ts`).
- Modify:
  - `src/app/features/biblioteca/services/consulta-api.ts`: `EventoConsulta` suma `{tipo:'aclaracion', texto, opciones}` y `{tipo:'etapa', etapa}`.
  - `src/app/features/biblioteca/pages/consulta/consulta-page.ts`, `.html`, `.css` y `.spec.ts`.

**Interfaces:**
- Consumes: los eventos de la Tarea 5.
- Produces, en la pantalla:
  - `aclaracion` muestra el texto y las opciones como botones. Un clic envía `opcion.pregunta` como pregunta nueva **sin historial**.
  - `etapa` cambia el estado visible: `Buscando en la biblioteca…` o `Redactando…`. Reemplaza el "En cola" que quedaba visible.
  - Bajo cada respuesta `listo` con fuentes y no profunda, un botón `Profundizar` que envía `Más detalle sobre lo anterior` con el historial de esa respuesta.
  - Las aclaraciones no entran al historial.

- [ ] **Paso 1:** Cliente, `EventoConsulta` y la pantalla.
- [ ] **Paso 2:** `consulta-page.spec.ts`.
  - `aclaracionMuestraBotonesYUnClicPregunta`: el clic manda la pregunta sin historial.
  - `etapaCambiaElEstado`.
  - `profundizarMandaElHistorialDeEsaRespuesta`.
- [ ] **Paso 3:** Tests de la feature y `npx ng build` (anotar `Initial total`, tope 448 kB). Commit: "Consulta v2: repregunta con botones, etapas y Profundizar".

### Tarea 7: Reproceso real, calibración y medición final (controlador)

- [ ] **Paso 1:** Levantar la pila con la v2. El reproceso por versión corre solo; esperar a que M2–M5 queden en versión 2 (consultar `libro`).
- [ ] **Paso 2:** Correr `medir.mjs` y guardar `resultados-v2.md`. Ajustar `minimo-pasajes` y `corte-vector` con el set: elegir los valores que maximizan Recall@5 y aciertos de "sin material" sin repreguntar de más. Volver a medir.
- [ ] **Paso 3:** Revisar a mano 10 respuestas contra sus fuentes. Probar en la pantalla las consultas cruzadas y dos pestañas a la vez.
- [ ] **Paso 4:** Escribir la comparación v1 contra v2 (tabla de §6 del diseño) en `docs/diseno/AKINE-biblioteca-consulta-v2.md` §9 y commitear.
