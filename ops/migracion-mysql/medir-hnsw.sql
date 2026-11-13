-- Recall@k y tiempo del indice HNSW contra la busqueda exacta, sobre vectores reales de la biblioteca.
--
-- Herramienta de la migracion (12/11/2026). Toma 50 fragmentos como consulta y, para cada uno,
-- compara los k mas cercanos que devuelve la consulta de PgAlmacenVectorial (con el indice y el
-- recorrido iterativo en orden estricto) contra los k exactos (sin indice). El filtro de
-- organizacion y de libro buscable es el mismo que usa la aplicacion.
--
--   docker exec -i akine-postgres psql -U postgres -d <base> -v k=10 < medir-hnsw.sql
\timing off
\set QUIET on
CREATE TEMP TABLE consultas AS
SELECT f.id, f.organization_id, f.vector
  FROM libro_fragmento f
 WHERE f.vector IS NOT NULL
 ORDER BY md5(f.id::text)
 LIMIT 50;

CREATE TEMP TABLE exactos (consulta bigint, fragmento bigint);
CREATE TEMP TABLE aproximados (consulta bigint, fragmento bigint);
CREATE TEMP TABLE tiempos (modo text, ms double precision);

DO $$
DECLARE
    c record;
    t0 timestamptz;
BEGIN
    FOR c IN SELECT * FROM consultas LOOP
        -- Exacta: sin indices, recorrido secuencial con el mismo filtro.
        SET LOCAL enable_indexscan = off;
        SET LOCAL enable_bitmapscan = off;
        t0 := clock_timestamp();
        INSERT INTO exactos
        SELECT c.id, f.id FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id
         WHERE f.organization_id = c.organization_id AND l.organization_id = f.organization_id
           AND l.activo AND l.estado = 'LISTO' AND NOT f.sospechoso AND f.vector IS NOT NULL
         ORDER BY f.vector <=> c.vector, f.id LIMIT 10;
        INSERT INTO tiempos VALUES ('exacta', extract(epoch FROM clock_timestamp() - t0) * 1000);

        -- Aproximada: lo que hace PgAlmacenVectorial.
        SET LOCAL enable_indexscan = on;
        SET LOCAL enable_bitmapscan = on;
        SET LOCAL enable_seqscan = off;
        PERFORM set_config('hnsw.iterative_scan', 'strict_order', true);
        PERFORM set_config('hnsw.ef_search', '100', true);
        t0 := clock_timestamp();
        INSERT INTO aproximados
        SELECT c.id, f.id FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id
         WHERE f.organization_id = c.organization_id AND l.organization_id = f.organization_id
           AND l.activo AND l.estado = 'LISTO' AND NOT f.sospechoso AND f.vector IS NOT NULL
         ORDER BY f.vector <=> c.vector, f.id LIMIT 10;
        INSERT INTO tiempos VALUES ('hnsw', extract(epoch FROM clock_timestamp() - t0) * 1000);
        SET LOCAL enable_seqscan = on;
    END LOOP;
END $$;

\set QUIET off
SELECT round(100.0 * count(a.fragmento) / count(*), 1) AS "recall@10 (%)"
  FROM exactos e LEFT JOIN aproximados a ON a.consulta = e.consulta AND a.fragmento = e.fragmento;
SELECT modo,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric, 3) AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) AS p95_ms
  FROM tiempos GROUP BY modo ORDER BY modo;
EXPLAIN (COSTS OFF)
SELECT f.id FROM libro_fragmento f JOIN libro l ON l.id = f.libro_id
 WHERE f.organization_id = (SELECT organization_id FROM consultas LIMIT 1)
   AND l.activo AND l.estado = 'LISTO' AND NOT f.sospechoso
 ORDER BY f.vector <=> (SELECT vector FROM consultas LIMIT 1) LIMIT 10;
