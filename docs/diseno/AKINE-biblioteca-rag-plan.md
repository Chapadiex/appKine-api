# Biblioteca del centro (RAG) — Plan de implementación del backend

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Módulo `knowledge` en `appKine-api`: libros PDF por organización, procesados en segundo plano a pedazos con embeddings locales, y búsqueda por similitud con plan B por palabras, detrás del interruptor `akine.biblioteca.enabled`.

**Architecture:** Módulo nuevo `com.akine.knowledge` con las capas de siempre (`api`, `application`, `domain`, `infrastructure`, `spi`). Worker `@Scheduled` que reclama libros con `FOR UPDATE SKIP LOCKED`, extrae con PDFBox, trocea, marca pedazos sospechosos y vectoriza con `multilingual-e5-base` en ONNX dentro del proceso. Vectores en `BLOB`, coseno en Java sobre una caché por organización invalidada por `biblioteca_version`.

**Tech Stack:** Java 21 · Spring Boot 4.1.x · MySQL 8.4 · Flyway · Apache PDFBox 3.0.x · `dev.langchain4j:langchain4j-embeddings` (ONNX Runtime) · Testcontainers.

**Spec:** `docs/diseno/AKINE-biblioteca-rag.md` (commit `4138dadc`). Leerla antes de cada tarea.

**Fuera de este plan:** la pantalla de `appKine-web`. Va en su propio plan, después de publicar el contrato (regla de coordinación entre repos: backend → contrato → cliente).

## Global Constraints

- Paquete `com.akine.knowledge`. Ningún otro módulo lo importa salvo por `com.akine.knowledge.spi`.
- Migración **`V144`** y contrato **`0.164.0`**. `V143`/`0.163.0` los tiene la rama local `akine-clases-servicios`. Al rebasear sobre `main`, subirlos por encima de lo que haya.
- Interruptor `akine.biblioteca.enabled` (default `false`). Apagado: no se crean workers ni modelo, y toda operación responde 409 `biblioteca-deshabilitada`.
- Permiso `biblioteca:manage`, sólo `ORG_ADMIN`, alcance `ORGANIZACION`.
- Troceado: 1000 caracteres, solape 200, mínimo 100. Tope de páginas 2000. Tamaño máximo del PDF 100 MB (`104857600`).
- Embeddings: prefijo `"passage: "` al material y `"query: "` a la consulta, mean pooling, 768 dimensiones, vector normalizado.
- Corte: `top_k = 8`, `min_score = 0.818` (vector), mínimo 2 pedazos sobre el corte. Corte por palabras configurable, default `0.5` hasta calibrarlo.
- Reindexado en lotes de 32 con `SKIP LOCKED`.
- Ningún DTO con nombre genérico: prefijo `Libro…` / `Biblioteca…` (`SchemaNameCollisionTest`).
- Sin `double`/`float` como campo en `..domain..`: los vectores viajan como `float[]` en parámetros y se guardan como `byte[]`.
- Preferencias del usuario: código primero y tests después, pocos. Commits locales en `akine-biblioteca-rag`, sin push ni PR hasta que lo pida. Commits en prosa normal.
- Corridas locales: sólo lo tocado (`./mvnw -o test -Dtest="com.akine.knowledge.**.*Test" -Djacoco.skip=true`; ITs de a uno con `-Dit.test=...`). La suite completa es del CI.

## Review Focus

1. **PDF cifrado, con contraseña o corrupto:** el libro termina en `ERROR` con un motivo legible ("El PDF está protegido o dañado"). No queda trabado en `PROCESANDO` ni hace caer el worker. Test en la Tarea 6.
2. **El mismo archivo subido dos veces a la vez:** una subida devuelve 202 y la otra 409 `libro-duplicado`, nunca 500. Lo garantiza un unique sobre una columna generada. Test en la Tarea 4.
3. **Eliminar o desactivar un libro mientras el worker lo procesa:** el worker no lo resucita, no inserta pedazos huérfanos y no falla. Test en la Tarea 6.
4. **Buscar en una organización sin libros `LISTO`, o con una consulta en blanco:** sin libros, 200 con lista vacía y `superaCorte` en `false`. En blanco, 400 `validation-error`. Test en la Tarea 8.
5. **Reinicio del servidor a mitad de un libro:** el barrido lo devuelve a `PENDIENTE` y se procesa de nuevo sin duplicar pedazos. Test en la Tarea 6.

---

### Task 1: Reservas, requisitos M30 y design challenge

**Files:**
- Modify: `docs/fases/01-trabajo-en-paralelo.md` §6 "Migraciones reservadas". Agregar la fila `V144 | biblioteca (M30) | libro, libro_fragmento, biblioteca_version` y corregir la fila de `V141`, que figura vacía y existe.
- Modify: `docs/producto/AKINE_Requerimientos_Integrados.md`: insertar `# M30 — Biblioteca del centro` antes de la sección 30.
- Modify: `docs/seguridad/matriz-permisos-minima.md`: nueva sección `## 22. Enmiendas — Biblioteca (M30)`.
- Modify: `docs/diseno/AKINE-biblioteca-rag.md`: nueva sección `## 11. Design challenge`.

- [ ] **Step 1: Redactar M30** con la estructura de M29 (Objetivo, Actores, Dependencias, Reglas, RF, RNF, Casos borde).
  - Reglas mínimas:
    - RN-M30-001: biblioteca por organización.
    - RN-M30-002: un archivo no se carga dos veces entre los libros activos.
    - RN-M30-003: un pedazo sospechoso nunca se recupera.
    - RN-M30-004: sólo se comparan vectores del modelo vigente.
    - RN-M30-005: un reemplazo no deja hueco.
  - RF, uno por operación:
    - RF-M30-001: subir.
    - RF-M30-002: listar y filtrar.
    - RF-M30-003: ver detalle.
    - RF-M30-004: editar datos.
    - RF-M30-005: reemplazar archivo.
    - RF-M30-006: reprocesar.
    - RF-M30-007: activar o desactivar.
    - RF-M30-008: eliminar.
    - RF-M30-009: probar búsqueda.
    - RF-M30-010: procesamiento en segundo plano.
    - RF-M30-011: reindexado.
  - Cada RF lleva sus `CA-M30-NNN-NN`, que salen de las secciones 4 y 5 de la spec.
- [ ] **Step 2: Escribir el design challenge.** Responder las ocho preguntas de `CLAUDE.md` §3. Respuestas esperadas:
  - Ownership: las tres tablas son de `knowledge`.
  - Dependencias: `knowledge → organization.spi` y `knowledge → platform.spi`, sin ciclos.
  - Tenant: `organization_id` en todas las tablas y en los índices.
  - Reglas maestras: no hay datos clínicos.
  - Borrado físico: sólo en "Eliminar", porque no es información clínica.
  - Contrato aditivo, minor `0.164.0`.
  - No adelanta ningún cimiento.
  - Caso que rompe el diseño: una organización con 100.000 pedazos. Respuesta: tope de memoria con desalojo, y ése es el disparador de pgvector.
- [ ] **Step 3: Commit** `docs: reserva V144 y redacta M30 (biblioteca del centro)`.

### Task 2: Esqueleto del módulo, interruptor, permiso y errores

**Files:**
- Create: `knowledge/infrastructure/config/BibliotecaProperties.java`
- Create: `knowledge/infrastructure/config/KnowledgeConfig.java`
- Create: `knowledge/domain/PermissionCodes.java`
- Create: `knowledge/api/KnowledgeApiActor.java`
- Create: `knowledge/application/OperatingActor.java`
- Create: `knowledge/api/KnowledgeProblemHandler.java`
- Create: `knowledge/domain/exception/*`
- Create: `knowledge/application/AuditEvents.java`
- Create: `knowledge/application/BibliotecaAcceso.java`
- Modify: `organization/domain/PermissionCode.java`
- Modify: `organization/domain/RolePermissions.java` (fila ORG_ADMIN, cerca de la línea 332)
- Modify: `platform/spi/problem/ProblemType.java`
- Modify: `src/main/resources/application.yml`
- Test: `organization/domain/RolePermissionsTest.java`
- Test: `knowledge/api/KnowledgeProblemHandlerTest.java`

Todas las rutas van bajo `src/main/java/com/akine/` o `src/test/java/com/akine/`.

**Interfaces:**
- Produce:
  - `record BibliotecaProperties(boolean enabled, long maxBytes, int maxPaginas, Troceo troceo, Busqueda busqueda, Modelo modelo, Worker worker, String baseDir)`, con `@ConfigurationProperties(prefix = "akine.biblioteca")`.
  - Defaults (records anidados):
    - `troceo(1000, 200, 100)`;
    - `busqueda(8, 0.818, 0.5, 2, 512 MB de tope de caché)`;
    - `modelo(tipo: "onnx" | "prueba", id, rutaModelo, rutaTokenizer)`;
    - `worker(fixedDelayMs 5000, reindexLote 32, leaseMinutos 15)`;
    - `baseDir "./var/biblioteca"`.
  - `PermissionCodes.BIBLIOTECA_MANAGE = "biblioteca:manage"`.
  - `OperatingActor(long accountId, boolean platformAdmin, Long contextOrganizationId)`, igual que el de `notification`.
  - `BibliotecaAcceso.exigir(OperatingActor actor, long orgId)`:
    - lanza `BibliotecaDeshabilitadaException` si está apagada;
    - lanza `AccessDeniedException` si no hay contexto;
    - lanza `LibroNoEncontradoException` si `orgId` no coincide con el contexto (404, nunca 403);
    - si no, llama a `PermissionGuard.requirePermission(PermissionQuery.of(actor.accountId(), BIBLIOTECA_MANAGE, orgId, ahora))`.
  - `ProblemType` nuevos, en un bloque `// --- Biblioteca (M30) ---`:
    - `BIBLIOTECA_DESHABILITADA("biblioteca-deshabilitada")` → 409;
    - `LIBRO_NO_ENCONTRADO("libro-no-encontrado")` → 404;
    - `LIBRO_DUPLICADO("libro-duplicado")` → 409, con la propiedad `libroId`;
    - `LIBRO_ARCHIVO_NO_ACEPTADO("libro-archivo-no-aceptado")` → 400, con la propiedad `causa` (`NO_ES_PDF`, `MUY_GRANDE`, `VACIO`);
    - `LIBRO_EN_PROCESO("libro-en-proceso")` → 409.
  - Constantes de `AuditEvents`: `LIBRO_SUBIDO`, `LIBRO_EDITADO`, `LIBRO_REEMPLAZADO`, `LIBRO_REPROCESADO`, `LIBRO_ACTIVADO`, `LIBRO_DESACTIVADO`, `LIBRO_ELIMINADO`.

- [ ] **Step 1: Implementar.**
  - `KnowledgeConfig` lleva `@EnableConfigurationProperties(BibliotecaProperties.class)` y `@EnableScheduling`.
  - En `application.yml`, bloque `akine.biblioteca` con `enabled: ${AKINE_BIBLIOTECA_ENABLED:false}`.
  - `BIBLIOTECA_MANAGE` va en la fila ORG_ADMIN con `PermissionScope.ORGANIZACION`, y **no** va en `GRANT_POR_ROL`.
- [ ] **Step 2: Tests.**
  - En `RolePermissionsTest`, sumar la fila `ORG_ADMIN, BIBLIOTECA_MANAGE, ORGANIZACION` y el caso negativo para `PROFESIONAL`.
  - En `KnowledgeProblemHandlerTest`, verificar `type`, status y propiedades de cada excepción.
  - Revisar si `MatrizDePermisosIT` y `PermisosEsquemaIT` necesitan la entrada nueva. Correrlos.
- [ ] **Step 3: Correr ArchUnit y lo tocado.**
  - `./mvnw -o test -Dtest="com.akine.architecture.*Test,com.akine.organization.domain.RolePermissionsTest,com.akine.knowledge.**.*Test" -Djacoco.skip=true` → BUILD SUCCESS.
- [ ] **Step 4: Commit** `feat(knowledge): módulo, interruptor, permiso biblioteca:manage y errores`.

### Task 3: Esquema y persistencia

**Files:**
- Create: `src/main/resources/db/migration/V144__m30_biblioteca.sql`
- Create: `knowledge/domain/Libro.java`
- Create: `knowledge/domain/EstadoLibro.java`
- Create: `knowledge/domain/LibroFragmento.java`
- Create: `knowledge/domain/port/LibroRepositoryPort.java`
- Create: `knowledge/domain/port/LibroFragmentoRepositoryPort.java`
- Create: `knowledge/domain/port/BibliotecaVersionPort.java`
- Create: `knowledge/infrastructure/LibroRepository.java`
- Create: `knowledge/infrastructure/LibroFragmentoRepository.java`
- Create: `knowledge/infrastructure/BibliotecaVersionRepository.java`
- Test: `knowledge/infrastructure/BibliotecaMigracionIT.java`

**Interfaces:**
- Produce:
  - **Tabla `libro`.** Columnas:
    - `id`, `organization_id`, `titulo` (varchar 300), `autores`, `edicion`, `area`;
    - `storage_key`, `nombre_archivo`, `content_type`, `tamano_bytes`;
    - `hash_sha256` (char 64), `paginas`;
    - `estado` con CHECK `PENDIENTE|PROCESANDO|LISTO|ERROR`;
    - `progreso` (0–100), `motivo_error`;
    - `reemplaza_libro_id` (FK a `libro`, `ON DELETE SET NULL`);
    - `activo`;
    - `hash_activo` (`GENERATED ALWAYS AS (IF(activo AND reemplaza_libro_id IS NULL, hash_sha256, NULL)) STORED`);
    - `procesando_desde`, `declaracion_derechos_en`, `declaracion_derechos_por_cuenta_id`, auditoría y `version`.
  - Índices y unique de `libro`:
    - `uk_libro_hash_activo (organization_id, hash_activo)`;
    - `ix_libro_estado (estado, procesando_desde)`.
  - **Tabla `libro_fragmento`.** Columnas:
    - `id`, `organization_id`, `libro_id` (FK `ON DELETE CASCADE`), `orden`;
    - `pagina_desde`, `pagina_hasta`, `texto` (TEXT);
    - `vector` (BLOB nullable), `modelo_embeddings` (varchar 100 nullable), `sospechoso`.
  - Índices de `libro_fragmento`:
    - `ix_libro_fragmento_libro (organization_id, libro_id)`;
    - `ix_libro_fragmento_modelo (modelo_embeddings)`;
    - `FULLTEXT ft_libro_fragmento_texto (texto)`.
  - **Tabla `biblioteca_version`:** `organization_id` PK y `version BIGINT`.
  - Constraints nombradas con la tabla adelante, según la regla de §3.4.
  - Puertos:
    - `LibroRepositoryPort`: `save`, `findByIdAndOrganization(long id, long orgId)`, `buscar(long orgId, String q, String area, EstadoLibro estado, Pageable)`, `delete`.
    - `Optional<Libro> reclamarPendiente(Instant ahora)`. Es nativa con `SELECT … WHERE estado='PENDIENTE' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED`, siguiendo `NotificationOutboxRepository.reclamarLote`.
    - `int devolverTrabados(Instant limite)`.
    - `LibroFragmentoRepositoryPort`: `reemplazarDeLibro(long libroId, List<LibroFragmento>)` y `List<Long> reclamarSinVector(String modeloVigente, int lote)` (`SKIP LOCKED`).
    - `actualizarVector(long id, byte[] vector, String modelo)`, `contarPorLibro(long libroId)` y `sospechososDe(long libroId)`.
    - `BibliotecaVersionPort.avanzar(long orgId)` (`INSERT … ON DUPLICATE KEY UPDATE version = version + 1`) y `long actual(long orgId)`.
  - `Libro.estadoVisible()` devuelve `INACTIVO` cuando `!activo`. Es un método del dominio, no una columna.

- [ ] **Step 1: Escribir la migración y las entidades.** Los `saveAll` de los puertos llevan `default` que delega, por la trampa del 29/09.
- [ ] **Step 2: IT.**
  - `V144` aplica.
  - Un segundo libro activo con el mismo hash da `DataIntegrityViolationException`. Uno inactivo, o uno de reemplazo, no la da.
  - Borrar un libro cascadea sus fragmentos.
  - `reclamarPendiente` desde dos transacciones devuelve dos libros distintos.
  - Correr: `./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BibliotecaMigracionIT -Djacoco.skip=true` → PASS.
- [ ] **Step 3: Commit** `feat(knowledge): V144 libro, libro_fragmento y biblioteca_version`.

### Task 4: Almacenamiento compartido y ABM de libros

**Files:**
- Create: `platform/spi/archivo/AlmacenDeArchivos.java`
- Create: `platform/infrastructure/archivo/LocalFileSystemAlmacenDeArchivos.java`
- Create: `knowledge/application/LibroService.java`
- Create: `knowledge/api/LibroController.java`
- Create: `knowledge/api/dto/LibroResponse.java`
- Create: `knowledge/api/dto/LibroPageResponse.java`
- Create: `knowledge/api/dto/LibroDetalleResponse.java`
- Create: `knowledge/api/dto/LibroDatosRequest.java`
- Modify: `application.yml`. `spring.servlet.multipart.max-file-size` pasa a `101MB` y `max-request-size` a `102MB`. Los límites propios de `person` y `clinical` siguen en 10 MB.
- Test: `knowledge/api/LibroControllerTest.java`
- Test: `knowledge/application/LibroServiceTest.java`

**Interfaces:**
- Consume: lo de las Tareas 2 y 3, más `person/domain/TipoDeArchivo` como referencia (copiar la firma PDF `%PDF-`; no importarla).
- Produce:
  - `AlmacenDeArchivos`:
    - `void guardar(String espacio, String clave, byte[] contenido)`;
    - `Optional<byte[]> leer(String espacio, String clave)`;
    - `void borrar(String espacio, String clave)`.
  - `LocalFileSystemAlmacenDeArchivos` copia el comportamiento de `LocalFileSystemContenidoClinicoStorage`: clave validada, carpetas `ab/cd/`, archivo temporal y `ATOMIC_MOVE`. Su base sale de `akine.archivos.base-dir`.
  - Decisión: es el tercer consumidor previsto en el Javadoc del storage clínico. Se extrae **sólo el nuevo**; migrar `person` y `clinical` queda como deuda declarada en el registro.
  - Rutas bajo `/api/v1/organizations/{orgId}/biblioteca`:
    - `GET /libros?q&area&estado&page&size&sort` → `LibroPageResponse`. Orden por `titulo`, `creadoEn` o `estado`.
    - `POST /libros`, multipart `archivo` con `@RequestParam titulo`, `autores?`, `edicion?`, `area?` y `declaracionDerechos` (boolean, tiene que ser `true`) → **202** `LibroResponse`.
    - `GET /libros/{id}` → `LibroDetalleResponse`: los datos, `fragmentos`, `fragmentosConVector` y `sospechosos: [{paginaDesde, extracto (200 chars)}]`.
    - `PUT /libros/{id}`, con `LibroDatosRequest(titulo, autores, edicion, area, long version)` → 200.
    - `POST /libros/{id}/reemplazo`, multipart como el alta → 202 con el `LibroResponse` del libro nuevo.
    - `POST /libros/{id}/reproceso` → 202.
    - `POST /libros/{id}/activacion` y `POST /libros/{id}/desactivacion` → 200.
    - `DELETE /libros/{id}` → 204. Los `produces` incluyen `application/json`.
  - `LibroResponse(id, titulo, autores, edicion, area, paginas, estado, progreso, motivoError, reemplazaLibroId, version)`.
  - En cada mutación, `LibroService` audita, y `biblioteca_version` avanza cuando cambia lo buscable: activar, desactivar o eliminar.

- [ ] **Step 1: Implementar el servicio y el controlador.**
  - Alta:
    1. Validar con `exigir`.
    2. El archivo no puede estar vacío, tiene que pesar como mucho `maxBytes` y tiene que ser PDF por sus bytes.
    3. Calcular SHA-256.
    4. Si ya hay un libro activo con ese hash, 409 `libro-duplicado`.
    5. Guardar el archivo y después la fila `PENDIENTE`.
    6. Si choca contra `uk_libro_hash_activo`, traducirlo a 409. Releer en `READ_COMMITTED`, por la regla de la integración del 29/09.
  - Reemplazo y reproceso de un libro `PENDIENTE` o `PROCESANDO`: 409 `libro-en-proceso`.
  - Eliminar: borrar la fila (cascadea) y después el archivo.
- [ ] **Step 2: Tests.**
  - Slice: 202 en el alta, 400 con `causa=NO_ES_PDF` para un `.exe` renombrado y `declaracionDerechos=false` → 400.
  - Slice: el `orgId` de otra organización da 404, sin permiso da 403, y con el interruptor apagado da 409.
  - Unitario: dos altas con el mismo hash dan 202 y 409 (Review Focus 2). Hacerlo también en un IT de concurrencia con `CarreraDirigida` si el unitario no alcanza para el unique.
- [ ] **Step 3: Correr lo tocado** y ArchUnit → BUILD SUCCESS.
- [ ] **Step 4: Commit** `feat(knowledge): subida, listado, detalle, edición y bajas de libros`.

### Task 5: Extracción, troceado y pedazos sospechosos (dominio puro)

**Files:**
- Modify: `pom.xml`. Agregar `org.apache.pdfbox:pdfbox` con la 3.0.x estable más reciente. Verificar que pase `dependency-check`.
- Create: `knowledge/domain/port/ExtractorDeTexto.java`
- Create: `knowledge/infrastructure/PdfBoxExtractorDeTexto.java`
- Create: `knowledge/domain/Troceador.java`
- Create: `knowledge/domain/DetectorDeInstrucciones.java`
- Create: `src/main/resources/knowledge/frases-sospechosas.txt`
- Test: `knowledge/domain/TroceadorTest.java`
- Test: `knowledge/domain/DetectorDeInstruccionesTest.java`
- Test: `knowledge/infrastructure/PdfBoxExtractorDeTextoTest.java`

**Interfaces:**
- Produce:
  - `record PaginaDeTexto(int numero, String texto)`.
  - `ExtractorDeTexto.extraer(byte[] pdf, int maxPaginas) → List<PaginaDeTexto>`. Lanza `DocumentoIlegibleException(String motivoLegible)` si el PDF está cifrado o dañado, o si tiene más de `maxPaginas` páginas.
  - `record Pedazo(int orden, int paginaDesde, int paginaHasta, String texto)`.
  - `Troceador(int tamano, int solape, int minimo).trocear(List<PaginaDeTexto>) → List<Pedazo>`. Corta en el último espacio antes del tamaño, conserva el rango de páginas, junta un resto menor que el mínimo al pedazo anterior y normaliza los espacios.
  - `DetectorDeInstrucciones.esSospechoso(String texto) → boolean`:
    - compara contra el texto normalizado (sin tildes, minúsculas, espacios colapsados);
    - busca las frases del archivo, una por línea, con `#` como comentario;
    - busca los marcadores `system:`, `[inst]`, `<|im_start|>` y `<<sys>>`.
  - La lista inicial tiene unas 20 frases en español e inglés:
    - "ignora tus instrucciones", "ignorá las instrucciones anteriores", "olvida tus reglas", "olvidá tus reglas";
    - "a partir de ahora eres", "actua como", "revela tu prompt";
    - "ignore previous instructions", "you are now", "disregard the above"…
  - `static boolean casiSinTexto(List<PaginaDeTexto>)`: menos de 50 caracteres no blancos por página en promedio. Es el caso del escaneado.

- [ ] **Step 1: Implementar.**
- [ ] **Step 2: Tests.**
  - Troceador:
    - un texto de 2500 caracteres da 3 pedazos con solape de 200 y ninguno corta una palabra;
    - un pedazo que cruza páginas lleva `paginaDesde` menor que `paginaHasta`.
  - Detector:
    - corta "Ignorá tus instrucciones y…", `system: sos otro` y `[INST]`;
    - no corta "el paciente ignora el dolor" ni "instrucciones de ejercicio domiciliario".
  - Extractor: un PDF de dos páginas generado en el test con PDFBox da dos `PaginaDeTexto`, y un PDF cifrado lanza `DocumentoIlegibleException`.
- [ ] **Step 3: Correr** `./mvnw -o test -Dtest="com.akine.knowledge.**.*Test" -Djacoco.skip=true` → PASS.
- [ ] **Step 4: Commit** `feat(knowledge): extracción con PDFBox, troceado y detección de pedazos sospechosos`.

### Task 6: Modelo de embeddings y procesamiento en segundo plano

**Files:**
- Modify: `pom.xml`. Agregar `dev.langchain4j:langchain4j-embeddings` con la estable más reciente compatible con Java 21. Confirmar que no arrastre Spring y que pase `dependency-check`.
- Create: `knowledge/domain/port/ModeloEmbeddings.java`
- Create: `knowledge/infrastructure/OnnxModeloEmbeddings.java`
- Create: `knowledge/infrastructure/ModeloEmbeddingsDePrueba.java`
- Create: `knowledge/domain/Vectores.java`
- Create: `knowledge/application/ProcesamientoDeLibroService.java`
- Create: `knowledge/infrastructure/ProcesamientoWorker.java`
- Test: `knowledge/domain/VectoresTest.java`
- Test: `knowledge/application/ProcesamientoDeLibroIT.java`

**Interfaces:**
- Consume: lo de las Tareas 3, 4 y 5.
- Produce:
  - `ModeloEmbeddings`:
    - `String id()`;
    - `int dimension()`;
    - `List<float[]> pasajes(List<String> textos)`, que antepone `"passage: "`;
    - `float[] consulta(String texto)`, que antepone `"query: "`.
  - Los vectores salen normalizados.
  - `OnnxModeloEmbeddings`:
    - usa `OnnxEmbeddingModel(rutaModelo, rutaTokenizer, PoolingMode.MEAN)`;
    - el bean va con `@ConditionalOnProperty(akine.biblioteca.enabled=true)` y `akine.biblioteca.modelo.tipo=onnx`;
    - falla al arrancar con un mensaje claro si falta el archivo del modelo.
  - `ModeloEmbeddingsDePrueba` (`tipo=prueba`, `id "prueba-hash-768"`): vector determinista por bolsa de palabras con hashing a 768 dimensiones, normalizado. Así textos con palabras en común quedan cerca. Lo usan todos los IT.
  - `Vectores`: `byte[] aBytes(float[])`, `float[] deBytes(byte[])` (float32 little-endian), `float[] normalizar(float[])` y `float coseno(float[] a, float[] b)` (producto punto).
  - `ProcesamientoDeLibroService.procesarSiguiente(Instant ahora) → boolean`. Devuelve `false` si no había nada. Pasos:
    1. Reclamar en una transacción `REQUIRES_NEW` y pasar a `PROCESANDO` con `procesando_desde = ahora`.
    2. Fuera de la transacción: leer el archivo, extraer, trocear, detectar y vectorizar en lotes de 16, actualizando `progreso` por lote en transacciones cortas.
    3. Una transacción final releyendo el libro. Si ya no existe o ya no está `PROCESANDO`, descartar sin error (Review Focus 3). Si sigue, ejecutar `reemplazarDeLibro`, poner `LISTO` con `paginas`, avanzar `biblioteca_version` y, si `reemplaza_libro_id` no es nulo, desactivar el anterior **en la misma transacción**.
  - Casos de falla:
    - `DocumentoIlegibleException` o `casiSinTexto` → `ERROR` con motivo legible. Si era un reemplazo, el anterior sigue activo.
    - Si falla un lote de vectores, sus pedazos se guardan con `vector = NULL` y `modelo_embeddings = NULL`, y el libro queda `LISTO` igual.
  - `ProcesamientoWorker` sigue el patrón de `OutboxWorker`:
    - `@ConditionalOnProperty(name="akine.biblioteca.enabled", havingValue="true")`;
    - `@Scheduled(fixedDelayString="${akine.biblioteca.worker.fixed-delay-ms:5000}")`;
    - `tick()` captura `RuntimeException` y la loguea sin el texto del libro;
    - antes de procesar llama a `devolverTrabados(ahora - leaseMinutos)`.

- [ ] **Step 1: Implementar.**
- [ ] **Step 2: Tests.**
  - `VectoresTest`: ida y vuelta de bytes, y coseno de un vector consigo mismo da 1.
  - `ProcesamientoDeLibroIT` (perfil `local`, `tipo=prueba`, worker apagado, llamando `procesarSiguiente` a mano):
    - **Caso feliz:** un PDF de 3 páginas termina `LISTO`, con pedazos que tienen vector y `modelo_embeddings = "prueba-hash-768"`.
    - **PDF con orden escondida:** el pedazo de la página 2 queda `sospechoso = true`.
    - **PDF cifrado:** `ERROR` con "El PDF está protegido o dañado" (Review Focus 1).
    - **Libro eliminado entre el reclamo y el cierre:** no deja fragmentos y no lanza excepción (Review Focus 3).
    - **Libro `PROCESANDO` con `procesando_desde` vencido:** el barrido lo devuelve a `PENDIENTE` y el reprocesado deja la misma cantidad de pedazos, sin duplicar (Review Focus 5).
    - **Reemplazo:** mientras el nuevo está `PENDIENTE`, el viejo sigue activo. Al quedar `LISTO`, el viejo pasa a inactivo. Si el nuevo termina en `ERROR`, el viejo sigue activo.
- [ ] **Step 3: Correr** el IT y los unitarios del módulo → PASS.
- [ ] **Step 4: Commit** `feat(knowledge): embeddings e5 locales y procesamiento de libros en segundo plano`.

### Task 7: Reindexado

**Files:**
- Create: `knowledge/application/ReindexadoService.java`
- Create: `knowledge/infrastructure/ReindexadoWorker.java`
- Test: `knowledge/application/ReindexadoIT.java`

**Interfaces:**
- Consume: `LibroFragmentoRepositoryPort.reclamarSinVector`, `actualizarVector` y `ModeloEmbeddings`.
- Produce:
  - `ReindexadoService.reindexarLote() → int` (cantidad reindexada).
  - Reclama hasta 32 pedazos no sospechosos, de libros activos, con `modelo_embeddings` nulo o distinto de `modelo.id()`. Los vectoriza, los actualiza y avanza `biblioteca_version` de cada organización tocada.
  - El worker va con el mismo interruptor, `fixedDelay` 30 s, y no propaga.

- [ ] **Step 1: Implementar.**
- [ ] **Step 2: IT:** forzar `modelo_embeddings = 'modelo-viejo'` en los pedazos de un libro `LISTO`. Después de llamar `reindexarLote()` hasta que dé 0, todos tienen el id vigente y la versión de la biblioteca avanzó.
- [ ] **Step 3: Correr** → PASS.
- [ ] **Step 4: Commit** `feat(knowledge): reindexado de pedazos sin vector o de otro modelo`.

### Task 8: Búsqueda, plan B por palabras y `spi`

**Files:**
- Create: `knowledge/domain/port/AlmacenVectorial.java`
- Create: `knowledge/infrastructure/MySqlAlmacenVectorial.java`
- Create: `knowledge/application/BusquedaService.java`
- Create: `knowledge/spi/BibliotecaDirectory.java`
- Create: `knowledge/spi/PasajeRecuperado.java`
- Create: `knowledge/api/BusquedaController.java`
- Create: `knowledge/api/dto/BibliotecaBusquedaRequest.java`
- Create: `knowledge/api/dto/BibliotecaBusquedaResponse.java`
- Create: `knowledge/api/dto/BibliotecaPasajeResponse.java`
- Test: `knowledge/application/BusquedaServiceTest.java`
- Test: `knowledge/application/BusquedaIT.java`

**Interfaces:**
- Consume: `ModeloEmbeddings.consulta`, `BibliotecaVersionPort.actual`, `Vectores.coseno` y `deBytes`.
- Produce:
  - `AlmacenVectorial`:
    - `List<Coincidencia> porVector(long orgId, String modelo, float[] consulta, int k)`;
    - `List<Coincidencia> porPalabras(long orgId, String consulta, int k)`;
    - con `record Coincidencia(long fragmentoId, double puntaje)`.
  - `MySqlAlmacenVectorial.porVector`:
    - Caché por organización: arreglos de ids y vectores, cargados sólo con libro activo, `LISTO`, no sospechoso y modelo igual al vigente. Primero se acota y después se ordena.
    - Se recarga si `biblioteca_version.actual(orgId)` cambió.
    - Tope global en bytes con desalojo LRU por organización (`LinkedHashMap` con `accessOrder`, sincronizado).
  - `porPalabras`: nativa con `MATCH(texto) AGAINST (:q IN NATURAL LANGUAGE MODE)` y los mismos filtros. El puntaje se lleva a `[0,1)` con `s/(s+1)`.
  - `BusquedaService.buscar(OperatingActor, long orgId, String consulta, int k) → ResultadoBusqueda(camino VECTOR|PALABRAS, double corte, boolean alcanza, List<PasajeRecuperado>)`.
    - `k` va de 1 a 20 y por defecto es 5. Una consulta en blanco o de más de 600 caracteres da 400.
    - Camino `VECTOR`, o `PALABRAS` si `ModeloEmbeddings` no está o lanza.
    - Los textos y títulos se completan con una sola consulta por ids.
    - `alcanza` es verdadero cuando hay al menos 2 pasajes con `puntaje >= corte`.
  - `PasajeRecuperado(long libroId, String titulo, int paginaDesde, int paginaHasta, String texto, double puntaje, boolean superaCorte)`.
  - `BibliotecaDirectory.buscar(long orgId, String consulta, int k) → ResultadoBusqueda`. Lo usan los proyectos 2 y 3 y no exige el permiso (lo exige quien llama).
  - Ruta `POST /api/v1/organizations/{orgId}/biblioteca/busquedas` con `BibliotecaBusquedaRequest(String consulta, Integer k)` → 200 `BibliotecaBusquedaResponse(camino, corte, alcanza, pasajes[])`. El fragmento se recorta a 500 caracteres en la respuesta.

- [ ] **Step 1: Implementar.**
- [ ] **Step 2: Tests.**
  - Unitario: el corte se elige por camino; sin `ModeloEmbeddings`, el camino es `PALABRAS`.
  - `BusquedaIT`, con dos organizaciones A y B, modelo de prueba, y libros procesados con `procesarSiguiente`:
    - **Aislamiento:** una consulta desde A nunca devuelve pasajes de B, aunque B tenga el texto exacto.
    - **Sospechosos fuera:** el pedazo sospechoso no aparece, aunque sea el más parecido.
    - **Sin mezclar modelos:** un pedazo con `modelo_embeddings = 'otro'` no sale por `VECTOR` y sí por `PALABRAS`.
    - **Reemplazo sin hueco:** durante el reemplazo, la consulta devuelve pasajes del viejo. Al terminar, los del nuevo y ya no los del viejo.
    - **Desactivar invalida la caché:** desactivar un libro lo saca del resultado en la consulta siguiente.
    - **Biblioteca vacía y consulta en blanco:** sin libros, 200 con `pasajes = []` y `alcanza = false`. En blanco, 400 (Review Focus 4).
- [ ] **Step 3: Correr** → PASS.
- [ ] **Step 4: Commit** `feat(knowledge): búsqueda por similitud con caché por organización y plan B por palabras`.

### Task 9: Contrato, modelo en la imagen y cierre

**Files:**
- Modify: `pom.xml:47` y `application.yml:178` → `0.164.0`.
- Modify: `openapi/akine-api.yaml`. Regenerado, no a mano.
- Modify: `Dockerfile`. Etapa que copia el modelo ONNX cuantizado y su `tokenizer.json` a `/opt/akine/modelos/e5-base/`, sólo si `AKINE_BIBLIOTECA_MODELO_URL` está definido al construir. Sin él, la imagen queda igual.
- Modify: `docs/diseno/AKINE-biblioteca-rag.md`: nueva sección `## 12. Registro de avance`.
- Modify: `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`: registro al final.

- [ ] **Step 1: Regenerar el contrato.**
  - Correr `./mvnw verify -Dakine.contract.update=true -Dit.test=OpenApiContractIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
  - Revisar el diff del YAML: tienen que aparecer **10 operaciones nuevas** y los 5 `ProblemType`, y nada más.
- [ ] **Step 2: Probar el modelo real una vez, a mano.**
  - Bajar `Xenova/multilingual-e5-base` (cuantizado) y verificar su licencia.
  - Arrancar con `AKINE_BIBLIOTECA_ENABLED=true` y `tipo=onnx`, y subir un PDF de kinesiología real del usuario.
  - Comprobar que "ejercicios para lumbalgia crónica" trae pasajes pertinentes con puntaje mayor que 0,818.
  - Anotar en el registro la memoria del proceso con el modelo cargado.
- [ ] **Step 3: Cierre.**
  - Correr `/simplify` y después `/code-review` sobre la rama.
  - Correr una vez los IT de `knowledge` y ArchUnit.
  - Completar el registro de avance con lo hecho, lo no hecho (migrar `person`/`clinical` al almacén compartido, calibrar el corte por palabras, el plan del frontend) y la memoria medida.
- [ ] **Step 4: Commit** `feat(knowledge): contrato 0.164.0 con la biblioteca del centro`.
