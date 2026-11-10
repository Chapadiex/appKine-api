# AKINE — Biblioteca del centro (RAG), proyecto 1 de 3

> Diseño acordado con el usuario el 09/11/2026 y ajustado el 10/11/2026 con el handoff
> `handoff-rag-respuestas-fundamentadas-2026-11-09.md` (de `llm-service`, Tema 07), que el usuario
> aportó como referencia. Lo marcado **[decisión del agente]** no lo decidió el usuario: es una
> elección razonable que puede revisar. Lo marcado **[handoff]** se toma de ese documento.

## 1. Intención

Los centros quieren que, al evaluar a un paciente, una IA **sugiera tratamientos** y **lea cómo
viene la evolución**, apoyándose en la bibliografía de kinesiología que el propio centro elige.
Eso es un RAG: los libros se trocean, se vectorizan, y en cada consulta se recuperan los pasajes
relevantes para que el modelo responda **citando libro y página**.

El trabajo se parte en tres proyectos, en este orden:

1. **Biblioteca del centro** (este documento): cargar libros, procesarlos y poder buscar en ellos.
2. **Sugerir tratamiento** en la sesión de evaluación, usando los datos clínicos que ya existen y
   la biblioteca. Incluye la **configuración de IA por organización** (proveedor, modelo y clave
   del modelo que redacta). Diseño propio, después.
3. **Evaluar evolución**: requiere antes catálogo de mediciones precargado, objetivos medibles,
   línea base del caso y serie de mediciones por caso (hoy no existen; relevamiento del 09/11 en la
   sección 10). Diseño propio, después.

Este proyecto **no** produce ninguna respuesta redactada por IA, **no** guarda claves de API y
**no** envía nada a un proveedor externo. Entrega la base sobre la que se construyen 2 y 3.

## 2. Objetivos, no-objetivos, anti-objetivos

**Objetivos**

- Cada organización administra **su propia** biblioteca (multi-tenant, como persona e historia
  clínica). Nada se comparte entre organizaciones.
- CRUD de libros (PDF) con procesamiento en segundo plano y estado visible.
- Panel "Probar búsqueda" para verificar que un libro quedó bien cargado.
- Embeddings **locales**, dentro del backend: los libros nunca salen del servidor.
- Búsqueda detrás de una interfaz propia, para poder cambiar el motor sin tocar el resto.

**No-objetivos (en este proyecto)**

- Sugerencias de tratamiento y evaluación de evolución (proyectos 2 y 3).
- Configuración de IA por organización, claves de API y cadena de proveedores (proyecto 2).
- OCR de PDF escaneados.
- Formatos distintos de PDF (EPUB, DOCX, Markdown). El handoff los cubre con Tika; se suman después.
- Biblioteca compartida a nivel plataforma (hoy cada centro carga la suya aunque repita libros).
- **Tope de cantidad de libros: el usuario pidió explícitamente no limitarla por ahora.**

**Anti-objetivos (sería un fracaso aunque funcione)**

- Que una organización pueda recuperar trozos o metadatos de libros de otra.
- Que un pedazo con instrucciones escondidas ("ignorá tus reglas…") llegue a la búsqueda y,
  después, al prompt del proyecto 2.
- Que la búsqueda compare vectores de modelos de embeddings distintos (devolvería basura sin error).
- Que reemplazar o reprocesar un libro deje la biblioteca sin ese material mientras procesa.
- Que la lógica quede atada a MySQL o a un modelo concreto.

## 3. Decisiones del usuario

| Tema | Decisión |
|---|---|
| Alcance de la biblioteca | **Por organización**; la administra la organización |
| Pantalla | **Opción B**: tabla de libros + panel lateral "Probar búsqueda" |
| Almacén de vectores | **MySQL** (sin Postgres/pgvector). El usuario consideró pgvector y eligió seguir con MySQL |
| Cantidad de libros | **Sin tope** por ahora |
| Proveedor del modelo que redacta | **Configurable** por organización (Claude, GPT, Grok…) — se implementa en el proyecto 2 |
| Embeddings | **Locales** (`multilingual-e5-base` en ONNX, en el proceso), un solo modelo para la plataforma — aceptado el 10/11 al proponerlo a partir del handoff |
| Orden de proyectos | Biblioteca → sugerir tratamiento → evaluar evolución |

## 4. Pantalla (frontend, `appKine-web`)

Ruta propuesta `/configuracion/biblioteca` **[decisión del agente]**. Sigue la norma de pantalla
vigente (`appKine-web/docs/ux/00-plan-refactor-ux-ui.md` §3.2/§3.4 y `DESIGN.md`): alta en el
título, buscador + embudo, tabla ordenable y paginada, menú "⋯" en filas con 3+ acciones, "?"
para lo explicativo.

- **Cabecera:** "Biblioteca" + cantidad de libros + botón "Subir libro".
- **Buscador + embudo:** búsqueda `q` por título/autor; filtros por área y estado.
- **Tabla:** título, autor, edición, área, páginas, estado, menú "⋯".
  - Estado: `PENDIENTE`, `PROCESANDO` (con %), `LISTO`, `ERROR` (con motivo legible, ej. "El PDF
    no tiene texto: parece escaneado"), `INACTIVO`. Un libro `LISTO` con pedazos todavía sin
    vector del modelo vigente muestra "Indexando N %" al lado del estado.
  - Menú "⋯": ver detalle, editar datos, reemplazar archivo, reprocesar, desactivar/activar, eliminar.
  - Mientras haya libros en `PENDIENTE`/`PROCESANDO`, la pantalla refresca el estado (polling
    cada pocos segundos **[decisión del agente]**). Ojo: los componentes están en `OnPush` real.
- **Panel lateral "Probar búsqueda":** caja de texto → lista de los mejores pasajes (top 5
  **[decisión del agente]**) con libro, página, fragmento y puntaje. Los que no superan el corte de
  respaldo (§5.5) se muestran atenuados con "debajo del corte", para que el admin vea si la
  biblioteca alcanza para esa pregunta. No hay IA redactando; sólo recuperación.
- **Diálogo "Subir libro":** PDF (tamaño máximo configurable por despliegue, 100 MB por defecto
  **[decisión del agente]**), título (obligatorio), autor/es, edición/año, área; casilla
  obligatoria "Declaro que el centro tiene derecho a usar este material".
- **Detalle del libro:** cantidad de pedazos y cuántos quedaron **marcados como sospechosos**
  (excluidos de la búsqueda), con la página de cada uno, para que el admin pueda revisarlo.

## 5. Backend (`appKine-api`)

### 5.1 Módulo

Módulo nuevo `knowledge` (o `biblioteca`) **[decisión del agente; el nombre lo fija el
constructor]**, con la estructura de los demás (`api`, `application`, `domain`, `infrastructure`)
y respetando ArchUnit. Consume `organization`/`identity`/`platform` sólo por sus `spi`, y expone un
`spi` de búsqueda para que lo consuman los proyectos 2 y 3. Recordar las reglas del CLAUDE.md raíz:
`ApiActor` con nombre propio, excepciones en su propio advice, `produces` con `application/json`
en operaciones 204.

### 5.2 Permisos

- `biblioteca:manage` — subir, editar, reemplazar, reprocesar, desactivar, eliminar, probar
  búsqueda. `ORG_ADMIN` **[decisión del agente]**.
- Profesionales no ven esta pantalla: usarán la biblioteca a través de los proyectos 2 y 3.
- Actualizar la matriz de permisos (`docs/seguridad/`) y el catálogo de permisos otorgables.

### 5.3 Datos (MySQL)

Nombres orientativos; el constructor reserva versiones de migración según
`docs/fases/01-trabajo-en-paralelo.md` antes de escribir.

- **`libro`**: `organization_id`, título, autores, edición, área, `storage_key`, nombre de archivo,
  `content_type`, tamaño, hash SHA-256 del archivo, páginas, `estado`, `progreso` (0–100),
  `motivo_error`, `reemplaza_libro_id` (versión anterior que reemplaza), `activo`,
  `declaracion_derechos_en`/`_por_cuenta_id`, auditoría y `version`.
  Subir dos veces el mismo archivo (mismo hash) en la misma organización, entre libros activos →
  **409** [handoff].
- **`libro_fragmento`**: `organization_id`, `libro_id`, orden, página inicial y final, texto,
  vector (`BLOB`, float32 empaquetado, normalizado), `modelo_embeddings` (identificador del modelo
  que lo generó; `NULL` si todavía no tiene vector), `sospechoso` (booleano) [handoff]. Índices por
  `(organization_id, libro_id)` y por `(modelo_embeddings)` para el reindexado. Un índice
  `FULLTEXT` sobre el texto para la búsqueda por palabras.
- **`biblioteca_version`**: una fila por organización con un contador que avanza cada vez que
  cambia algo buscable (libro listo, reemplazado, desactivado, eliminado, reindexado). Sirve para
  invalidar la caché en varias instancias (§5.5) **[decisión del agente]**.

Bajas:

- **Desactivar**: el libro deja de participar en búsquedas al instante; se conserva todo.
- **Eliminar**: borra archivo, fragmentos y fila. No es información clínica, así que no aplica la
  regla de baja lógica obligatoria **[decisión del agente]**. Se audita.

### 5.4 Procesamiento

1. **Subida:** el archivo va a almacenamiento con un puerto propio siguiendo el patrón de
   `clinical/domain/port/ContenidoClinicoStoragePort` + `LocalFileSystemContenidoClinicoStorage`
   (disco local). No reutilizar la clase de `clinical` directamente: ArchUnit y la condición de
   salida del storage duplicado (registro de 04.02) — el constructor decide si es momento de
   extraerlo a `platform.spi` o si se suma una copia acotada.
   Se valida que sea PDF **por los bytes**, no por la extensión [handoff y regla de 03.02].
2. **Cola:** el libro queda `PENDIENTE`. Un worker `@Scheduled` (mismo patrón que
   `notification/infrastructure/OutboxWorker`: **nunca propaga** excepciones) reclama un libro con
   `SELECT … FOR UPDATE SKIP LOCKED` (MySQL 8 lo soporta) [handoff] y lo pasa a `PROCESANDO`.
   Un barrido devuelve a `PENDIENTE` los `PROCESANDO` sin actividad reciente (el proceso murió).
3. **Extracción:** texto por página con Apache PDFBox [handoff]. Si casi no hay texto → `ERROR`
   "PDF escaneado sin texto". Tope de páginas configurable (el handoff usa 2000).
4. **Troceado:** pedazos de **1000 caracteres con 200 de solape**, mínimo 100, sin cortar en mitad
   de palabra, conservando página inicial y final [handoff; valores ajustables por configuración].
5. **Pedazo sospechoso [handoff]:** cada pedazo se revisa contra una lista de frases de jailbreak
   normalizadas (sin tildes, minúsculas) y roles falsos (`system:`, `[INST]`, `<|im_start|>`,
   `<<SYS>>`); si coincide, se guarda con `sospechoso = true` y **nunca** sale en la búsqueda. La
   lista vive en un archivo de recursos versionado, no en el código.
6. **Embeddings:** `"passage: " + texto` por el modelo local (§5.6), en lotes; `progreso` se
   actualiza por lote. **Si el vector falla, el pedazo se guarda igual** con
   `modelo_embeddings = NULL`: se encuentra por palabras y el reindexado lo completa [handoff].
7. **Atomicidad:** los fragmentos de un reprocesado reemplazan a los viejos en una sola
   transacción al final; nunca hay un libro mitad viejo y mitad nuevo.
8. **Reemplazar archivo [handoff]:** crea un libro nuevo con `reemplaza_libro_id` y lo procesa por
   la cola. Mientras tanto sigue respondiendo el anterior. Recién cuando el nuevo queda `LISTO`, en
   la misma transacción, el anterior pasa a inactivo. Si el nuevo queda en `ERROR`, el anterior
   sigue activo.
9. **Reindexado [handoff]:** un trabajo en segundo plano toma pedazos con `modelo_embeddings` nulo
   o distinto del modelo vigente, de a 32 con `SKIP LOCKED`, y los vuelve a vectorizar. Cubre los
   vectores que fallaron y **el cambio de modelo de embeddings**, que es una decisión de la
   plataforma (configuración + deploy), no del centro: mientras dura, esos pedazos se encuentran
   por palabras y la búsqueda nunca queda vacía.

### 5.5 Búsqueda

- Interfaz propia (`AlmacenVectorial` o similar) con `buscar(organización, vector, k)`, "guardar
  fragmentos de un libro" y "borrar los de un libro". La implementación inicial es MySQL; la lógica
  no conoce el motor, para poder pasar a pgvector si hiciera falta [handoff, paso 1 bis opción 1].
- **Implementación MySQL:** traer los vectores de la organización y calcular coseno en Java (con
  vectores normalizados, producto punto), con **caché en memoria por organización**. Alcanza hasta
  ~100.000 pedazos [handoff].
- **Siempre filtrar por `organization_id`** del contexto, libro activo y `LISTO`, pedazo **no**
  sospechoso y `modelo_embeddings` igual al vigente. Primero se acota el universo y después se
  ordena: nunca traer el top-k global y filtrar después [handoff, paso 4].
- **Plan B por palabras [handoff]:** si el modelo de embeddings no está disponible (falló al
  cargar), la búsqueda usa el índice `FULLTEXT` con los mismos filtros y devuelve qué camino usó
  (`VECTOR` o `PALABRAS`). Pedazos todavía sin vector también se alcanzan por esta vía.
- **Corte de respaldo [handoff, paso 5]:** `top_k = 8`, `min_score = 0,818` calibrado para
  `multilingual-e5-base`, mínimo 2 pedazos por encima del corte. **Cambiar de modelo obliga a
  recalibrar.** El corte por palabras se calibra aparte (otra escala). En este proyecto el corte
  sólo se informa en la respuesta y en el panel; quien decide "no figura en el material" es el
  proyecto 2.
- Endpoint de prueba: `POST .../biblioteca/busquedas` con `{ consulta, k }` → pasajes con libro,
  páginas, fragmento, puntaje, si supera el corte, y el camino usado. Es el mismo servicio que
  expone el `spi` para los proyectos 2 y 3.
- **Varias instancias [decisión del agente]:** el destino del deploy no está definido (DP-32), así
  que el diseño no asume una sola. Cada instancia compara `biblioteca_version` antes de usar su
  caché. El archivo va por el puerto de almacenamiento: disco local en desarrollo, volumen
  compartido u objeto en un despliegue con varias instancias.
- **Memoria [decisión del agente]:** la caché tiene un tope global configurable y descarta las
  organizaciones menos usadas.
- **Riesgo aceptado por el usuario:** sin tope de libros, una organización con mucha bibliografía
  hace la búsqueda lenta y pesada en memoria. Ese es el disparador para migrar a pgvector.

### 5.6 Modelo de embeddings local

- **`intfloat/multilingual-e5-base`** en ONNX (por ejemplo `Xenova/multilingual-e5-base`), 768
  dimensiones, hasta 512 tokens por texto; corre dentro del proceso con ONNX Runtime, **mean
  pooling** y prefijos `"passage: "` al material y `"query: "` a la consulta [handoff]. Sin clave,
  sin costo por uso, el material no sale del servidor.
- Librería: `langchain4j-embeddings` (`OnnxEmbeddingModel`) u ONNX Runtime directo con el
  tokenizer; lo decide el constructor verificando compatibilidad con Spring Boot 4.1.x y Java 21.
- Detrás de una interfaz propia **`ModeloEmbeddings`** (texto → vector, con identificador y
  dimensión), para que cambiar de modelo sea configuración y no código.
- **El modelo se descarga una vez** (en la imagen Docker o un volumen), nunca por pedido; la ruta
  va en configuración. Peso: ~1,1 GB en fp32, ~280 MB cuantizado: el constructor elige y mide la
  memoria del backend con el modelo cargado **[decisión del agente: preferir el cuantizado salvo
  que la calidad de búsqueda caiga]**. Verificar la licencia en la página del modelo antes de
  incorporarlo.
- Con el interruptor `akine.biblioteca.enabled` apagado (§6), el modelo **no se carga**: no cuesta
  memoria a quien no usa la biblioteca.
- Los tests no deben depender de descargar el modelo: un `ModeloEmbeddings` falso para unitarios e
  integración, y una prueba aparte (perfil propio) con el modelo real.

### 5.7 Contrato

Operaciones nuevas en `openapi/akine-api.yaml` (cambio aditivo, minor nuevo): CRUD de libros
(multipart para la subida), reemplazar archivo, reprocesar, activar/desactivar, detalle con
pedazos sospechosos y búsqueda de prueba. Errores con `problemType` propios (ej. libro duplicado,
archivo que no es PDF, PDF sin texto, biblioteca deshabilitada). Después: regenerar el cliente
TypeScript en `appKine-web`.

## 6. Restricciones

- Stack vigente: Java 21, Spring Boot 4.1.x, MySQL 8.4, Flyway; Angular 22.
- Un worktree y una rama por repo; la preferencia del usuario es commits locales en una rama y un
  único merge cuando lo pida.
- Bundle inicial del frontend sin margen (≈446 kB de tope): la pantalla nueva va en ruta diferida.
- Tests: los que la etapa exige, sin inflar (preferencia del usuario). El handoff pide tests por
  paso y un E2E de 16 casos; esos casos son del chat completo y van al proyecto 2. Lo que no puede
  faltar acá: aislamiento entre organizaciones en la búsqueda, pedazo sospechoso excluido, que
  el reemplazo no deje hueco, y que la búsqueda no mezcle modelos.
- **Sin RF que lo respalde todavía.** La regla del repo es "nada de código sin RF/RN"; la biblioteca
  no está en `AKINE_Requerimientos_Integrados.md`. **Primera tarea del plan [decisión del
  agente]:** redactar el módulo M30 con sus RF a partir de este documento, en la misma rama; el
  usuario lo revisa junto con el resto en el merge. Después, el *design challenge* del `CLAUDE.md`
  del repo.
- **Disponibilidad [decisión del agente]:** para todas las organizaciones, sin límite por plan.
  Detrás de un interruptor global del despliegue (`akine.biblioteca.enabled`, apagado por defecto)
  para que `main` siga desplegable mientras la tanda no esté completa.

## 7. Lo que queda a criterio del constructor

- Nombre del módulo, de tablas y de endpoints.
- `langchain4j-embeddings` u ONNX Runtime directo; modelo fp32 o cuantizado.
- Extraer el storage a `platform.spi` o duplicarlo acotado.
- Valores exactos de troceado, top-k, polling, tamaño máximo y tope de páginas.
- Cómo se disparan el worker y el reindexado (intervalo, tamaño de lote).
- La calibración del corte por palabras.

## 8. Preguntas abiertas para más adelante

- Si los profesionales deberían poder ver la lista de libros del centro (sólo lectura).
- Biblioteca compartida de plataforma para no repetir los mismos libros en cada centro.
- OCR para PDF escaneados; otros formatos (DOCX, Markdown) con Tika.
- Cuándo migrar a pgvector (umbral de fragmentos o de latencia).

## 9. Lo que el proyecto 2 y 3 van a necesitar (fuera de alcance, para no perderlo)

Del usuario y del relevamiento:

- Configuración de IA **por organización**: proveedor, modelo y clave del modelo que redacta.
  Claves cifradas (AES-GCM con clave maestra del despliegue), nunca devueltas por la API (sólo los
  últimos 4 caracteres), nunca en logs, cambios auditados. **Lista cerrada de proveedores**: nada
  de URL libre desde la pantalla, por SSRF.
- Envío de datos del paciente al proveedor **seudonimizado** (sin nombre, DNI, contacto; edad en
  rango) y cada consulta auditada como acceso clínico (DP-03, justificación declarada). **Al
  responder, los pedazos y la pregunta sí salen al proveedor de chat** [handoff]: revisar las
  condiciones de uso de datos de cada proveedor.
- Respuesta siempre con citas, nunca guardada sola en la historia clínica; aviso de que es apoyo y
  no reemplaza el criterio clínico.

Del handoff, para diseñar el proyecto 2:

- Gateway con cadena de candidatos y respaldo (Groq principal, Gemini de respaldo), tope por intento
  y plazo total, breaker y presupuesto diario.
- Controles de entrada contra inyección, material entre `<material_no_confiable>`, prompt del
  sistema versionado, validación de que cada cita exista entre los pedazos usados y de que no se
  filtre el prompt.
- Respuesta extractiva sin modelo cuando el chat no está disponible.
- Los 16 casos de punta a punta del handoff como criterio de aceptación.

## 10. Relevamiento del 09/11/2026 (por qué la evaluación de evolución va después)

Lo que ya existe y sirve: EVA 0–10 por sesión, motivo, limitación funcional, evolución
(mejor/igual/peor), tolerancia, próxima conducta; mediciones tipadas con unidad y lateralidad
(`medicion_definicion` + `sesion_medicion`, V51/V52); diagnóstico presuntivo del caso; plan con
objetivos, frecuencia y duración; tratamientos realizados con práctica y parámetros; antecedentes;
comparación sesión actual vs anterior (`SesionMedicionController`, `/mediciones/comparacion`).

Lo que falta para evaluar evolución con números: catálogo de mediciones vacío (sin goniometría,
MRC, Oswestry, DASH…), objetivos sólo en texto libre (sin valor meta ni plazo), sin línea base
marcada, sin endpoint de serie de mediciones/EVA por caso. No hay nada de IA, embeddings ni
extracción de texto de PDF en el repositorio.
