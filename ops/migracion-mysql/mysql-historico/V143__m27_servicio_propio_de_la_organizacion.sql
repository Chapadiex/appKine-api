-- =====================================================================================
-- Servicio propio de la organizacion (M27 / M06).
--
-- Trazabilidad: RF-M27-001 (crear Servicio; actores: administrador de organizacion y de
-- consultorio), RF-M06-006/007, RN-M27-003 (la oferta sobrescribe defaults sin tocar el
-- catalogo global). Decision del usuario del 09/11/2026: un centro da de alta servicios que
-- ve solo su organizacion, en la misma tabla que el catalogo global de la plataforma. Es una
-- ampliacion de RN-M27-001 y supersede la fila de `servicio` en ADR-0023 (ver ADR-0025).
--
-- La tabla pasa a tener DOS poblaciones, como `especialidad` y `practica` (V20, ADR-0021):
--   organization_id NULL  -> concepto del catalogo global, lo ven todos los centros
--   organization_id = N   -> servicio propio del centro N, lo ve solo N
--
-- Por eso entra `owner_key = IFNULL(organization_id, 0)` y los uniques pasan a incluirlo. Sin
-- el centinela, `UNIQUE (organization_id, codigo, ...)` dejaria sin proteccion justo al catalogo
-- global: en MySQL varios NULL no colisionan. `0` no es id de ninguna organizacion
-- (`organization.id` es AUTO_INCREMENT y arranca en 1).
--
-- Aditiva: toda fila existente queda con organization_id NULL, que es exactamente lo que era
-- (global). Los uniques nuevos son mas permisivos que los viejos solo entre duenios distintos;
-- entre filas globales protegen lo mismo, asi que el reemplazo no puede fallar por datos.
-- =====================================================================================

ALTER TABLE servicio
    ADD COLUMN organization_id BIGINT NULL
        COMMENT 'Duenio del servicio. NULL = catalogo global de la plataforma (lo ven todos los centros); valor = servicio propio de esa organizacion, que no ve ninguna otra'
        AFTER id,
    ADD COLUMN owner_key BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL
        COMMENT 'Discriminador de los uniques: 0 para el catalogo global, organization_id para un servicio propio. Toda consulta filtra por owner_key IN (0, contexto), nunca por organization_id'
        AFTER organization_id,
    ADD CONSTRAINT fk_servicio_organization FOREIGN KEY (organization_id) REFERENCES organization (id);

ALTER TABLE servicio
    ADD CONSTRAINT uk_servicio_owner_codigo_vigente UNIQUE (owner_key, codigo, deleted_key),
    ADD CONSTRAINT uk_servicio_owner_nombre_vigente UNIQUE (owner_key, nombre, deleted_key);

ALTER TABLE servicio
    DROP INDEX uk_servicio_codigo_vigente,
    DROP INDEX uk_servicio_nombre_vigente;

-- El selector: los servicios visibles para un contexto, por estado y nombre.
CREATE INDEX ix_servicio_owner_estado_nombre ON servicio (owner_key, active, nombre);
