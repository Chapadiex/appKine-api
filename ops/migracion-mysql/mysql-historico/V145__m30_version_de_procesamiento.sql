-- =====================================================================================
-- AKINE M30 — Version del procesamiento de cada libro (consulta a la biblioteca v2)
--
-- Con que version del troceado y la limpieza se armo el indice del libro. Los libros que ya
-- existen quedan en 1; el worker reprocesa en segundo plano los LISTO con una version menor
-- que la vigente (akine.biblioteca.version-procesamiento-vigente), con el mecanismo de reemplazo:
-- el indice anterior responde hasta que el nuevo queda LISTO.
-- =====================================================================================

ALTER TABLE libro
    ADD COLUMN version_procesamiento INT NOT NULL DEFAULT 1
        COMMENT 'Version de la ingesta con la que se armo el indice; menor que la vigente = a reprocesar';
