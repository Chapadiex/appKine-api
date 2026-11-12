# ADR-0026 — PostgreSQL + pgvector es el único motor de base de datos

- **Estado:** Aceptado
- **Fecha:** 2026-11-12
- **Decisión del usuario:** 11/11/2026, pedido de "migración integral de MySQL a PostgreSQL + pgvector"
- **Supersede:** la fila "Base de datos: MySQL 8.4 LTS" de [ADR-0016](0016-versiones-tecnicas-y-slo.md) y
  la decisión "MySQL, no pgvector" de la biblioteca RAG (`docs/diseno/AKINE-biblioteca-rag.md`)
- **Migraciones:** baseline `V1`–`V13` (reemplaza `V1`–`V145` de MySQL) · **Contrato:** sin cambios de API
- **Diseño y resultados:** [`docs/diseno/AKINE-migracion-postgres.md`](../diseno/AKINE-migracion-postgres.md)

## Contexto

AKINE corría sobre MySQL 8.4 desde AKINE-00.01. La biblioteca del centro (M30, el primer módulo de
RAG) guardaba los embeddings como `BLOB` y los recorría en memoria en Java, con el `FULLTEXT` de
MySQL para las palabras. El usuario decidió centralizar toda la persistencia en PostgreSQL y usar
pgvector para la recuperación, sin mantener dos motores ni sincronizar bases.

## Decisión

1. **PostgreSQL 18 con pgvector 0.8.1** (`pgvector/pgvector:0.8.1-pg18-trixie`) es el único motor, en
   ejecución, en desarrollo (`compose.yaml`) y en los tests de integración (Testcontainers con la misma
   imagen). El runtime no tiene driver de MySQL.
2. **Flyway sigue siendo la única autoridad del esquema** (ADR-0003). Se reemplazan las 145
   migraciones de MySQL por un **baseline consolidado** de 13 migraciones por dominio, generado desde
   el esquema final de MySQL y revisado. Las de MySQL quedan como historia en
   `ops/migracion-mysql/mysql-historico`. `ddl-auto: validate` sin cambios.
3. **Se conserva la semántica de MySQL donde el dominio dependía de ella**:
   - igualdad, orden y `UNIQUE` sin mayúsculas ni tildes: collation ICU no determinista `akine_ci`
     en toda columna de texto (por eso PostgreSQL 18, el primero con `LIKE` sobre ellas);
   - las claves `*_key` generadas que sostienen los únicos de la baja lógica (centinela 1970-01-01);
   - `audit_event` append-only por trigger; `updated_at` automático por trigger.
4. **Biblioteca:** `vector(768)` (multilingual-e5-base) con índice HNSW coseno y recorrido iterativo;
   texto completo con `tsvector` de palabras enteras sin tildes y el ranking de InnoDB (TF × IDF²). La fusión RRF y la política de
   evidencia no cambian: cambia el almacén (`PgAlmacenVectorial`).
5. **La JVM corre en UTC** en cualquier máquina: el driver de PostgreSQL manda la zona de la JVM al
   conectar, y el alias `America/Buenos_Aires` que elige Windows no existe en el tzdata de la imagen.
   Esa era la causa del error de zona horaria que antes se esquivaba con H2.

## Alternativas descartadas

- **MySQL para lo transaccional y PostgreSQL sólo para el RAG.** Dos motores, dos respaldos y datos de
  la biblioteca (organización, cuenta) que tendrían que sincronizarse. El usuario lo descartó.
- **Traducir las 145 migraciones una por una.** Conserva la historia, pero arrastra decenas de
  `ALTER` que se pisan entre sí y triplica el trabajo; el usuario eligió el baseline.
- **Comparar texto con `lower()`/`citext` en vez de una collation.** Cambia cada consulta y cada
  único; `citext` no ignora tildes. La collation preserva la semántica sin tocar el código.
- **`stringtype=unspecified` en el driver** para aceptar texto donde va una fecha o un número. Hubiera
  ocultado, en los tests, los mismos parámetros mal tipados que en producción fallan.

## Consecuencias

### Positivas
- Un solo motor, un respaldo y un procedimiento de restauración.
- Búsqueda vectorial en la base, con filtro de organización en el `WHERE`, sin cargar los vectores
  en memoria por organización.
- El error de zona horaria queda explicado y corregido en su causa.

### Negativas
- Una collation no determinista no admite regex ni búsquedas de subcadena (`position`, `locate`).
- PostgreSQL aborta la transacción ante el primer error: toda relectura posterior a un choque tiene
  que ir en otra transacción (en este código ya era así).
- Un `UPDATE` cuyo `WHERE` no cumple la versión visible no toma lock (InnoDB sí): los tests que
  coreografiaban un bloqueo ahí se adaptaron.

### Qué obliga a hacer
- Reglas de SQL nativo nuevas en `AGENT.md` §5 (booleanos, nulos tipados, `ON CONFLICT`, agregados
  con lock).
- Respaldo y restauración con `pg_dump`/`pg_restore` (`ops/`).
- La base MySQL original y su respaldo se conservan hasta que el usuario acepte la migración.
