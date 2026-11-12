# AKINE — Migración integral de MySQL a PostgreSQL + pgvector

> 11–12/11/2026 · rama `akine-postgres` (worktree `wt-API-POSTGRES`) · decisión en
> [ADR-0026](../adr/0026-postgresql-unico-motor.md) · herramientas y procedimiento en
> [`ops/migracion-mysql/`](../../ops/migracion-mysql/README.md).

PostgreSQL 18 con pgvector 0.8.1 pasa a ser **el único motor** de AKINE: aplicación, desarrollo,
tests de integración y biblioteca RAG. No queda MySQL en el runtime.

## Decisiones del usuario (11/11/2026)

| Pregunta | Decisión |
|---|---|
| Sobre qué base | Rama nueva `akine-postgres` = `main` (a57a7f25) + `akine-clases-servicios` + `akine-biblioteca-rag`. Se migra también el RAG, que vivía en ramas locales sin mergear. Contrato 0.167.0 (une 0.164.0 de clases con 0.166.0 del RAG) |
| Cómo armar Flyway | **Baseline consolidado**: 13 migraciones PostgreSQL por dominio. Las 145 de MySQL pasan a archivo histórico |
| Qué datos transferir | `akine_local` y `akine_rag`, con conciliación. No `akine_qa`, `akine_turnos`, `akine_clases` ni `akine_sede` (quedan respaldadas) |

## A. Inventario inicial de MySQL

Medido sobre una base de referencia con las 145 migraciones aplicadas en orden (`akine_ref`,
MySQL 8.4.11), desde `information_schema` (`ops/migracion-mysql/entradas/mysql-schema.json`).

| Objeto | Cantidad |
|---|---:|
| Tablas | 125 |
| Columnas | 1.953 |
| Claves primarias | 125 (123 `AUTO_INCREMENT`, todas `BIGINT`) |
| Únicos | 135 |
| Claves foráneas | 325 |
| CHECK | 366 |
| Índices no únicos | 385 (1 `FULLTEXT`) |
| Columnas generadas | 63 (60 `STORED`, 3 `VIRTUAL`), 22 expresiones distintas |
| Triggers | 2 (`audit_event` append-only) |
| `ON UPDATE CURRENT_TIMESTAMP` | 24 (todas `updated_at`) |
| Vistas, procedimientos, funciones, eventos | 0 |
| Tipos | 443 `DATETIME(6)`, 91 `TINYINT(1)`, 51 `DECIMAL`, 4 `JSON`, 1 `BLOB` (vector) |
| Collation | `utf8mb4_0900_ai_ci` en las 512 columnas de texto |
| Tablas con semillas | 9 (55 filas: planes, cuenta de plataforma, especialidades, prácticas, feriados) |

Tablas por módulo: activity 9, billing 16, clinical 16, contracting 7, encounter 11, identity 6,
knowledge 3, notification 1, offering 8, organization 14, person 11, platform 2, resource 13,
scheduling 8.

**Código que dependía de MySQL** (`ops/migracion-mysql/auditar-sql.mjs`):

- 465 sentencias SQL escritas a mano en producción (183 `@Query(nativeQuery = true)` en 54
  archivos, `JdbcTemplate` en el almacén vectorial, los fragmentos y la versión de la biblioteca).
  73 con sintaxis de MySQL: 16 `ON DUPLICATE KEY UPDATE`, 1 `INSERT IGNORE`, 1 `LAST_INSERT_ID`,
  22 funciones de fecha (`UTC_TIMESTAMP`, `TIMESTAMPDIFF`, `CONVERT_TZ`, `DATE_FORMAT`), 23
  comparaciones de booleanos con 0/1, 1 `MATCH ... AGAINST`, 22 parámetros nulos sin tipo.
  Más los que el inventario no marcaba y salieron al ejecutar: `COUNT(*) ... FOR SHARE`, filtros
  -1/0/1 contra booleanos por parámetro, `locate()` sobre texto, `REGEXP_REPLACE`.
- 1.828 sentencias en los tests de integración, 750 con sintaxis de MySQL.
- `information_schema.PROCESSLIST` en los tests de carreras dirigidas.
- Ningún `@Lob`, procedimiento almacenado ni `KeyHolder` en producción.

## B. Arquitectura final

```text
            Angular 21 (sin cambios)
                    │ HTTP / SSE
                    ▼
        Spring Boot 4.1 · Java 21 (JVM en UTC)
        JPA/Hibernate 7.4 (ddl-auto: validate) + JdbcTemplate
          │                     │                    │
          │ Flyway V1..V13      │ pgjdbc 42.7        │ ONNX e5-base (768) · Ollama qwen
          ▼                     ▼                    │
   ┌─────────────────── PostgreSQL 18.2 ───────────────────┐
   │  collation akine_ci (ICU, nivel 1)                     │
   │  125 tablas · 325 FK · 366 CHECK · 386 índices         │
   │  triggers: audit_event append-only, updated_at          │
   │  libro_fragmento.vector  vector(768)  ── HNSW coseno    │
   │  libro_fragmento.texto_tsv tsvector   ── GIN            │
   │      (akine_simple: palabras enteras, unaccent)         │
   └─────────────────────────────────────────────────────────┘
        pgvector 0.8.1 · unaccent · imagen pgvector/pgvector:0.8.1-pg18-trixie
```

## C. Objetos migrados

| Migración | Contenido |
|---|---|
| `V1__extensiones_collation_y_funciones` | `vector`, `unaccent`, collation `akine_ci`, `akine_marcar_updated_at()`, `akine_auditoria_append_only()` |
| `V2__platform_auditoria` | platform (2 tablas) |
| `V3__organizacion_identidad_y_notificaciones` | organization, identity, notification (21) |
| `V4__recursos_y_ofertas` | resource, offering (21) |
| `V5__personas_y_contratacion` | person, contracting (18) |
| `V6__agenda_y_turnos` | scheduling (8) |
| `V7__clinica_y_atencion` | clinical, encounter (27) |
| `V8__economia` | billing (16) |
| `V9__actividades_y_clases` | activity (9) |
| `V10__biblioteca` | knowledge (3) + configuración `akine_simple`, `texto_tsv`, GIN, HNSW |
| `V11__claves_foraneas` | las 325 FK, con sus `ON DELETE CASCADE/SET NULL` |
| `V12__marcas_de_modificacion` | 24 triggers `updated_at` + 2 de `audit_event` |
| `V13__datos_iniciales` | las 55 filas sembradas, con sus ids, y las secuencias ajustadas |

**Traducción de tipos** (los tipos con entidad salen del DDL que Hibernate espera para
PostgreSQL; los demás, de MySQL):

| MySQL | PostgreSQL | Por qué |
|---|---|---|
| `BIGINT AUTO_INCREMENT` | `bigint GENERATED BY DEFAULT AS IDENTITY` | `BY DEFAULT` deja copiar los ids originales |
| `DATETIME(6)` | `timestamptz(6)` | Todos los campos son `Instant` (la app escribía UTC); ninguno es `LocalDateTime` |
| `DATE` / `TIME` | `date` / `time` | `time` admite `24:00:00`, como MySQL |
| `TINYINT(1)` | `boolean` | Salvo `afecta_arqueo`, que es `TINYINT` y el SQL compara con 1 |
| `TINYINT` | `smallint` | `dia_semana` |
| `DECIMAL(p,s)` | `numeric(p,s)` | Exacto; `BigDecimal` en Java |
| `JSON` | `jsonb` | Las entidades declaran `SqlTypes.JSON` |
| `BLOB` del vector | `vector(768)` | Mismos float32 |
| `VARCHAR(n)`/`CHAR(n)` con `utf8mb4_0900_ai_ci` | `varchar(n) COLLATE akine_ci` | Ver abajo |
| Generadas `STORED`/`VIRTUAL` | `GENERATED ALWAYS AS (...) STORED` | Las 22 expresiones traducidas a mano (`IF`→`CASE`, `IFNULL`→`COALESCE`, `JSON_EXTRACT`→`->>`) |
| `ON UPDATE CURRENT_TIMESTAMP(6)` | trigger `akine_marcar_updated_at` | Sólo si el `UPDATE` no fija la columna, como MySQL |
| Triggers con `SIGNAL 45000` | trigger plpgsql con `RAISE ... ERRCODE '45000'` | Mismo mensaje |
| `FULLTEXT` | `tsvector` generado + GIN | |

## Decisiones técnicas y por qué

1. **Collation `akine_ci` en vez de `lower()`/`citext`.** MySQL comparaba todo texto sin mayúsculas
   ni tildes, y de eso dependían únicos (correo, códigos, nombres) y búsquedas. Una collation ICU no
   determinista de nivel 1 da la misma igualdad, orden y unicidad sin tocar una consulta. `LIKE` sobre
   ella existe desde PostgreSQL 18: por eso la versión. Lo que no admite —regex, `position`/`strpos`—
   se resolvió donde aparecía (`COLLATE "C"` en el `REGEXP_REPLACE` del padrón, `LIKE` escapado en la
   búsqueda de libros).
2. **La JVM en UTC.** El driver de PostgreSQL manda la zona de la JVM en el arranque de cada conexión;
   en Windows la JVM elige `America/Buenos_Aires`, alias que el tzdata de Debian trixie ya no trae, y
   el servidor corta con *invalid value for parameter "TimeZone"*. Esa era la causa real del problema
   de zona horaria que antes se esquivaba con H2. Ningún código de dominio usa la zona de la JVM
   (todo pasa por `ZonaDeSede`/`ZonaDeOrganizacion`) y la imagen Docker ya corría en UTC.
3. **Instantes en `timestamptz`, agenda incluida.** `turno.inicio` y `fin` son `Instant` en el
   dominio: un turno es un instante, y su hora local la da la zona de la sede. Los horarios de
   atención (`profesional_disponibilidad`, `consultorio_horario`) son `time` sin zona. La conversión
   no altera ningún horario: la conciliación compara cada instante al microsegundo.
4. **Biblioteca.** Coseno de pgvector (`1 - (vector <=> consulta)`), el mismo número que el recorrido
   en Java: los cortes no cambian. Índice HNSW con `hnsw.iterative_scan = strict_order` para no
   perder resultados por el filtro de organización, que va siempre en el `WHERE`. Texto: palabras
   enteras sin tildes (`akine_simple`) y el ranking de InnoDB (TF × IDF²), que reproduce los aciertos de
   MySQL; las configuraciones con raíces (spanish/english) se midieron y recuperaban peor (ver resultados).
5. **Sin escritura doble ni compatibilidad hacia atrás en la base.** El corte es con la aplicación
   detenida; el rollback es volver a la imagen anterior contra la base MySQL intacta.

## Diferencias de semántica que aparecieron al ejecutar

Ninguna la mostraba la compilación; las destapó correr la suite de integración contra PostgreSQL.

| Diferencia | Dónde pegó | Resolución |
|---|---|---|
| Un `null` en `(:x IS NULL OR ...)` llega sin tipo y PostgreSQL no lo infiere | 9 listados nativos (egresos, cajas, presentaciones, adjuntos, financiadores, sesiones) | `CAST(:x AS tipo)` |
| No se bloquea un agregado (`COUNT(*) ... FOR SHARE`) | límites de plan, sedes y espacios vivos, jornada abierta | subconsulta que bloquea, contar afuera |
| `boolean` no se compara con `integer` | filtros -1/0/1 de 11 repositorios, renovación de abonos | `col = (:f = 1)` |
| Un `varchar` explícito no se convierte a `time` | `HoraJdbcType` (horarios con 24:00) | `setObject(..., Types.OTHER)` |
| `REGEXP_REPLACE` reemplaza la primera ocurrencia (MySQL todas) y no admite la collation | búsqueda por número de afiliado | flag `'g'` y `COLLATE "C"` |
| `AT TIME ZONE '-03:00'` usa signo POSIX (invertido) | serie temporal de reportes | el desfase como `interval` |
| `position()` no admite collation no determinista | búsqueda de libros | `LIKE` con patrón escapado |
| Un `UPDATE` cuyo `WHERE` no cumple la versión visible no bloquea | carrera inscripción contra cancelación | el código ya releía con `FOR UPDATE`; el test se adaptó |
| `information_schema` publica los NOT NULL como CHECK | tests de esquema | se filtran |
| `CAST(x AS CHAR)` es `char(1)` | tests de auditoría | `CAST(x AS text)` |
| `ts_rank_cd` no pondera por rareza; el `MATCH ... NATURAL LANGUAGE` de MySQL sí (IDF) | consulta de la biblioteca: con "epoc ejercicio" el top-20 por palabras no traía **ningún** fragmento que nombrara EPOC y la política de evidencia ("la biblioteca tiene que nombrar el tema") respondía sin material. Recall@5 bajó de 78 % a 65 % en la primera medición | la relevancia de InnoDB (TF × IDF², sin los términos que nombra más de la mitad de los fragmentos) sobre palabras enteras sin tildes (`akine_simple`): mismos aciertos que MySQL en top-3, top-5 y top-10. Las raíces de spanish/english empeoraban ("comunicación" → `comun` trae "comunidad"). El corte de palabras 0,5 no cambia |
| La serie temporal de turnos pasaba el desfase del tramo (`-03:00`) donde la consulta esperaba una zona | reporte de turnos: el turno de las 21:30 de Córdoba contaba en el día siguiente | se pasa el id IANA de la sede; dentro del tramo el desfase es constante |

## E. Migración de datos

Herramienta: `ops/migracion-mysql/TransferirDatos.java` (copia con FK y triggers diferidos, ids
originales, secuencias ajustadas, verificación de las 325 FK y sumas de control por columna: nulos,
ids e importes, instantes al microsegundo, fechas, booleanos, largos de texto y distribución de
`estado`). Reportes completos en [`docs/migracion/`](../migracion/).

| Base | Esquema origen | Filas origen | Filas destino | Sumas de control distintas | Huérfanos |
|---|---|---:|---:|---:|---:|
| `akine_local` | V141 | 10.411 | 10.411 | 0 | 0 |
| `akine_rag` | V145 | 1.218 | 1.218 | 0 | 0 |
| `akine_turnos` (sólo prueba de volumen, base descartable) | V128 | 54.289 | 54.289 | 0 | 0 |

- Las tablas que el origen no tenía (la biblioteca en `akine_local`) quedan vacías; las columnas
  nuevas toman su default. Ninguna columna del origen quedó sin lugar en el destino.
- `flyway_schema_history` no se copia: el destino tiene la suya (V1–V13).
- **Embeddings:** 972 de 972 iguales bit a bit (`comparar-vectores.mjs`). No se regeneró ninguno.
- **Contraseñas:** los hashes se copian tal cual; el login con la cuenta de la biblioteca funcionó
  contra PostgreSQL.
- **Archivos:** no se mueven. Los 4 PDF de la biblioteca y los 34 adjuntos clínicos de
  `akine_local` existen en disco con sus claves.
- **Respaldo de MySQL:** `AKINE/respaldos/mysql-2026-11-11/` (seis bases, con `SHA256SUMS`),
  restaurado en una base descartable y comparado tabla por tabla sin diferencias. Las bases MySQL
  originales siguen intactas.

## F. Configuración

- **PostgreSQL:** `pgvector/pgvector:0.8.1-pg18-trixie` (PostgreSQL 18.2, pgvector 0.8.1),
  `timezone=UTC`, rol de aplicación sin superusuario y dueño de su base; `vector` la crea el init o
  el DBA. `compose.yaml` con volumen `akine-postgres-data`, healthcheck `pg_isready` y puerto
  `AKINE_DB_PORT` (5432).
- **Flyway:** `classpath:db/migration`, V1–V13, `validate-on-migrate`, sin baseline.
- **Hibernate:** `ddl-auto: validate`, `jdbc.time_zone: UTC`, `hibernate-vector` para `vector(768)`.
- **Driver:** `org.postgresql:postgresql` 42.7.14 con `options=-c timezone=UTC`,
  `reWriteBatchedInserts=true` y caché de sentencias preparadas.
- **Variables:** `AKINE_DB_URL=jdbc:postgresql://host:5432/base`, `AKINE_DB_USER`, `AKINE_DB_PASSWORD`.
- **JVM:** UTC (`AkineApiApplication`, `TestcontainersConfiguration`).
- **Tests:** Testcontainers con la misma imagen; una base por contexto de Spring.

## G. Pruebas

| Prueba | Resultado |
|---|---|
| Flyway sobre base vacía | 13 migraciones aplicadas; `ddl-auto: validate` sin errores |
| Unitarias (`./mvnw -o test`) | **4.662, 0 fallos** |
| Integración contra PostgreSQL 18 (`./mvnw -o verify`, Testcontainers) | **1.150, 0 fallos**: 1.149 en la corrida completa, y `OpenApiContractIT` después de regenerar el contrato, que sólo tenía el drift del merge de las dos ramas. Los 50 de la biblioteca se repitieron con la búsqueda por palabras final |
| Contrato OpenAPI | 397 operaciones = unión exacta de clases (386) y biblioteca (396); la migración no cambia la API |
| Respaldo y restauración (`ops/`) | backup → verificación en PostgreSQL efímero → restore con rol sin superusuario: filas de las 126 tablas, triggers, funciones y collation idénticos |
| Búsqueda vectorial HNSW contra exacta | recall@10 **100 %** (50 consultas reales, filtro por organización); ~5 ms p50 en los dos modos con 972 vectores |
| Búsqueda por palabras contra MySQL (16 casos, determinista) | top-3 13/16, top-5 15/16, top-10 15/16: **igual que MySQL** |
| Consulta RAG de punta a punta (38 casos, ONNX e5 + qwen3:4b-instruct, SSE) | Recall@5 **18/23 (78 %)**, repregunta **10/10**, sin material **5/5**, "sin material" falso 0/23, 0 errores: **igual que MySQL (v2)**. Medido con la relevancia InnoDB antes de agregar el filtro de términos comunes (por rendimiento); con el filtro, la comparación determinista da los mismos aciertos. [Resultados](../consulta/evaluacion/resultados-postgres.md) |

### Rendimiento (`ops/migracion-mysql/comparar-rendimiento.sh`)

`EXPLAIN ANALYZE` con los mismos datos (`akine_turnos`, 54 mil filas), sesión caliente, mediana de 7.
Con este volumen todo queda por debajo de 2,1 ms: la comparación dice más de los planes que del motor.

| Consulta | MySQL 8.4 (ms) | PostgreSQL 18 (ms) |
|---|---:|---:|
| Turnos de la sede en un mes (agenda) | 0,125 | 0,086 |
| Reporte: turnos por día y estado (zona de la sede) | 0,111 | 0,197 |
| Padrón: búsqueda "gar" sin mayúsculas ni tildes | 0,040 | 0,202 |
| Auditoría de la organización por período (página 1) | 2,080 | 0,110 |
| Historial de agenda (eventos de turnos) | 1,850 | 0,212 |
| Disponibilidad vigente de los profesionales | 0,051 | 0,062 |

- PostgreSQL gana claro en auditoría; pierde en el `LIKE '%gar%'` del padrón, que sobre una collation
  no determinista compara con ICU.
- La primera comparación con `akine_ci` en cada conexión carga el collator ICU (~10 ms, una vez por
  conexión del pool).
- **Biblioteca:** vectorial ~5–11 ms; por palabras 12–40 ms contra 3–7 ms de MySQL con 972
  fragmentos. La primera versión por palabras tardaba **5 s** (pesos recalculados por fila) y se
  corrigió; lo que queda es descomprimir cada `tsvector` que el planificador compara sin el índice GIN
  a esta escala.
- **Latencia de la consulta:** el primer evento SSE (después de la recuperación) sale a 0,0–0,2 s,
  igual que con MySQL. La primera palabra del modelo midió 12 s p50 contra 2,8 s en la medición de
  MySQL, pero la GPU estaba al 83 % ocupada por otra sesión: no es comparable y no se atribuye a la base.

## H. Pendientes y límites

- **Nada está en `main` ni en GitHub.** Todo vive en la rama local `akine-postgres`; integrarla trae
  también las ramas de clases y de biblioteca, que no estaban mergeadas.
- **El frontend no se tocó** y sus E2E no se corrieron contra este backend.
- **No se construyó la imagen Docker** del backend: `ops/simulacro-restore.sh` y `perf/stack.sh` se
  probaron hasta la base, sin el backend.
- **El QA manual (U-01) sigue siendo del usuario**, ahora contra PostgreSQL.
- **Las bases MySQL siguen vivas** (`akine-mysql`) como contingencia; borrarlas es decisión del
  usuario después de aceptar la migración.
- No se transfirieron `akine_qa`, `akine_turnos`, `akine_clases` ni `akine_sede` (decisión del
  usuario): están respaldadas y se pasan con la misma herramienta.
- La biblioteca no guarda idioma, capítulo ni fecha de indexación por libro: agregarlos es
  funcionalidad nueva, no migración.
- La medición de punta a punta del RAG con el filtro final queda por repetir con la GPU libre.
- `docs/carga.md` describe todavía el stack de carga con MySQL (historia de G-10).
- El rol de backup con `pg_read_all_data` está documentado y no probado.
