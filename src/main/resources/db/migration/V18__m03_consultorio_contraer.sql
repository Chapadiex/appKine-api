-- =====================================================================================
-- AKINE-02.01 — Contraer: la zona pasa a obligatoria y el unique de nombre deja de
-- condenar para siempre los nombres de las sedes dadas de baja.
--
-- Paso 3 de 3 de la expansion de `consultorio` (ver la cabecera de V16, que explica por que
-- esta contraccion viaja en la misma release que su expansion y en que condiciones eso deja
-- de ser aceptable).
--
-- ---------------------------------------------------------------------------------------
-- LA TRAMPA DE LOS NULL EN LOS UNIQUE DE MySQL, TERCERA VEZ EN ESTE REPOSITORIO
--
-- El problema real: uk_consultorio_org_name (organization_id, name) cubre TODAS las filas,
-- activas e historicas. Con baja logica obligatoria (regla maestra 10), dar de baja "Sede
-- Centro" y volver a crear una "Sede Centro" es imposible para siempre. Ningun RF pide eso.
--
-- El reflejo, y por que esta ROTO:
--     UNIQUE (organization_id, name, deleted_at)
-- En MySQL —como en el estandar SQL— varios NULL NO colisionan en un indice unico. Todas las
-- filas ACTIVAS tienen deleted_at IS NULL, asi que dos sedes activas con el mismo nombre
-- dejarian de colisionar. Es la inversion exacta de lo que se busca: protegeria los
-- historicos y desprotegeria lo vigente.
--
-- Este repositorio ya tiene las dos formas de resolverlo, y NO son intercambiables:
--   * V10 (membership.consultorio_scope) usa un CENTINELA: la columna generada nunca es NULL,
--     asi que TODAS las filas entran al unique.
--   * V12 (membership_grant.grant_activo) usa el NULL A PROPOSITO: la columna vale el
--     discriminador mientras la fila esta vigente y NULL cuando no, con lo que las filas
--     historicas salen del unique solas.
--
-- Aca hace falta el CENTINELA. Con la forma de V12 —name mientras active=1, NULL si no— el
-- discriminador de las filas historicas se perderia entero, y lo que distingue dos bajas
-- homonimas del mismo tenant es justamente el INSTANTE en que ocurrieron. Con el centinela:
--   * dos sedes ACTIVAS del mismo tenant con el mismo nombre comparten
--     deleted_key = 1970-01-01 -> colisionan -> 409. Es el caso que importa y queda protegido;
--   * una activa y una dada de baja no colisionan -> el nombre se puede reusar;
--   * dos bajas del mismo nombre solo colisionan si ocurrieron en el MISMO microsegundo.
--     DATETIME(6) lo hace practicamente imposible, y el desenlace seria un 409 en una baja,
--     recuperable reintentando.
--
-- Lo que NO se puede hacer:
--   * usar `id` como discriminador: MySQL prohibe que una columna generada referencie una
--     columna AUTO_INCREMENT;
--   * un indice unico parcial (UNIQUE ... WHERE): no existe en MySQL 8.4, es de PostgreSQL.
--
-- El unique sigue empezando por organization_id: el alcance tenant no se debilita (ADR-0004).
--
-- IRREVERSIBILIDAD (riesgo R-1, se anota, no se resuelve): una vez que exista una sede dada
-- de baja y otra activa con el mismo nombre, volver al unique de dos columnas es imposible
-- sin renombrar una de las dos. Mismo tipo de irreversibilidad que la V11 de 01.03.
-- =====================================================================================

-- El CHECK de V17 cumplio su unica funcion —verificar el backfill— y se retira: la
-- restriccion definitiva es el NOT NULL de abajo, y tener las dos deja la misma regla escrita
-- en dos formas distintas.
ALTER TABLE consultorio
    DROP CHECK ck_consultorio_timezone_backfill;

ALTER TABLE consultorio
    MODIFY COLUMN timezone VARCHAR(64) NOT NULL
        COMMENT 'Zona IANA de la sede (AGENT.md seccion 5). Es la zona EFECTIVA de toda regla local; organization.timezone queda como el valor que se propone al crear una sede nueva';

ALTER TABLE consultorio
    ADD COLUMN deleted_key DATETIME(6)
        AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de nombre: el instante de la baja, o el centinela 1970-01-01 mientras la sede este activa. Existe solo para que el unique funcione: en MySQL varios NULL no colisionan, asi que un unique sobre deleted_at a secas dejaria de proteger justo el caso que importa, dos sedes activas homonimas';

ALTER TABLE consultorio
    DROP INDEX uk_consultorio_org_name;

ALTER TABLE consultorio
    ADD CONSTRAINT uk_consultorio_org_name_vigente
        UNIQUE (organization_id, name, deleted_key);
