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
   │      (akine_es + akine_en, unaccent)                    │
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
| `V10__biblioteca` | knowledge (3) + configuraciones `akine_es`/`akine_en`, `texto_tsv`, GIN, HNSW |
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
   perder resultados por el filtro de organización, que va siempre en el `WHERE`. Texto en español e
   inglés (el libro no declara idioma) con semántica OR, como el modo de lenguaje natural de MySQL.
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

## Pendientes y límites

Ver la sección de resultados.
