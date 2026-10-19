-- =====================================================================================
-- DP-43 / U-38 — "Hoy" de la organizacion: la zona de la organizacion es la de su primera sede.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE NO HAY COLUMNA NUEVA
--
-- `organization.timezone` existe desde V2 (NOT NULL, default America/Argentina/Cordoba) y el
-- PATCH /organizations/{orgId} ya la edita, restringido al ORG_ADMIN. Hasta DP-43 era solo "el
-- valor que se propone" a la primera sede (ver V17): ninguna regla la leia. Desde DP-43 es la
-- zona con la que se calcula "hoy" en todo lo que la organizacion hace SIN sede —padron,
-- coberturas de la persona, autorizaciones, ordenes, planes del financiador—, via
-- `organization.spi.ZonaDeOrganizacion`. Agregar otra columna con el mismo significado dejaria
-- dos copias de la misma verdad y nadie sabria cual manda.
--
-- ---------------------------------------------------------------------------------------
-- EL BACKFILL: LA ZONA DE LA SEDE MAS ANTIGUA
--
-- La decision es que, por defecto, la zona de la organizacion sea la de su PRIMERA sede. Desde
-- 02.01 la zona de una sede se edita por separado, asi que una organizacion pudo quedar con su
-- zona original y la sede corregida despues: la que vale es la de la sede. "Primera" es la de
-- menor id, este activa o dada de baja: es la sede con la que nacio el tenant, y elegir la mas
-- antigua ACTIVA haria que el resultado dependiera de bajas posteriores. Una organizacion sin
-- sedes conserva lo que tiene (el default de V2 es America/Argentina/Cordoba).
--
-- Idempotente y repetible: el WHERE excluye lo que ya coincide.
-- ---------------------------------------------------------------------------------------
UPDATE organization o
    JOIN (SELECT c.organization_id, c.timezone
          FROM consultorio c
                   JOIN (SELECT organization_id, MIN(id) AS id
                         FROM consultorio
                         GROUP BY organization_id) primera ON primera.id = c.id) sede
    ON sede.organization_id = o.id
SET o.timezone = sede.timezone
WHERE o.timezone <> sede.timezone;
