-- =====================================================================================
-- AKINE-01.02 — Expandir: una cuenta puede tener MAS DE UNA membership en la misma
-- organizacion, siempre que sea en sedes distintas.
--
-- Trazabilidad: RN-M02-002 (el mismo usuario puede tener roles distintos en consultorios
-- distintos), RF-M01-002, ADR-0004 (uniques con alcance tenant), ADR-0007 (expandir-migrar-
-- contraer).
--
-- Propietario de la tabla: modulo organization.
--
-- ---------------------------------------------------------------------------------------
-- QUE ESTABA MAL
--
-- V3 creo uk_membership_org_account UNIQUE (organization_id, account_id), con el comentario
-- "en 01.01 una cuenta tiene a lo sumo una membership por organizacion; si 01.03 la
-- necesita, expandir". El diferimiento nacio vencido: RN-M02-002 ya existia cuando se
-- escribio, asi que el esquema prohibia lo que la regla exige.
--
-- Ademas tapaba un 500: los cuatro llamadores de
-- MembershipRepositoryPort.findBy...(organizationId, accountId) recibian un Optional, y la
-- segunda membership los habria roto con IncorrectResultSizeDataAccessException en la
-- resolucion de contexto, que corre en CADA request. Por eso el orden fue: primero los
-- llamadores (ahora leen una lista y eligen con MembershipSelection), despues este unique.
--
-- ---------------------------------------------------------------------------------------
-- EL DISCRIMINADOR, Y POR QUE NO ALCANZA consultorio_id A SECAS
--
-- El discriminador correcto es el ALCANCE de la membership: la sede, o "toda la
-- organizacion". Pero ese alcance se representa con consultorio_id NULL, y en MySQL —como en
-- el estandar SQL— varios NULL NO colisionan en un indice unico. Un
--     UNIQUE (organization_id, account_id, consultorio_id)
-- ingenuo aceptaria dos, tres o mil memberships de alcance ORGANIZACION para la misma cuenta
-- en la misma organizacion: exactamente el caso que mas hay que impedir, porque es el que
-- decide la administracion del tenant entero.
--
-- Se resuelve materializando el alcance en una columna que NUNCA es nula: una columna
-- generada que traduce NULL al centinela 0. Cero no es un consultorio posible —consultorio.id
-- es AUTO_INCREMENT y arranca en 1— asi que no puede chocar contra una sede real.
--
-- STORED y no VIRTUAL: InnoDB indexa las dos, pero una columna STORED se puede leer y
-- explicar en un plan sin recalcular la expresion, y esta tabla es la mas consultada del
-- sistema. El costo es una columna de 8 bytes por membership.
--
-- Alternativas descartadas:
--   * Reemplazar NULL por 0 en consultorio_id: rompe la FK a consultorio y obliga a inventar
--     una sede fantasma por organizacion. El alcance "toda la organizacion" no es una sede.
--   * Un CHECK o un trigger que impida el segundo NULL: no es atomico frente a dos INSERT
--     concurrentes, que es justo cuando importa. Quien decide tiene que ser un unique.
--   * Dejar el unique viejo y filtrar en la aplicacion: es la situacion actual, y es la que
--     contradice RN-M02-002.
--
-- ---------------------------------------------------------------------------------------
-- EXPANDIR - MIGRAR - CONTRAER (ADR-0007)
--
-- Expandir: se agrega la columna generada y el unique nuevo, mas amplio.
-- Migrar:   no hay dato que mover. La columna generada se calcula sola sobre las filas
--           existentes y el unique nuevo ACEPTA todo lo que el viejo aceptaba —es un
--           superconjunto—, asi que ninguna fila vigente puede violarlo.
-- Contraer: se borra el unique viejo en esta misma migracion, y aca si hay que decir por que
--           no espera a una release posterior. La contraccion diferida existe para proteger
--           al codigo todavia desplegado que depende de lo viejo. Aca lo viejo es una
--           PROHIBICION, no un contrato: ningun codigo depende de que la segunda membership
--           falle, y mantenerla haria que la expansion no expandiera nada. Retirarla no
--           invalida ningun dato ni ninguna consulta existente.
-- =====================================================================================

ALTER TABLE membership
    ADD COLUMN consultorio_scope BIGINT
        AS (IFNULL(consultorio_id, 0)) STORED NOT NULL
        COMMENT 'Alcance materializado de la membership: el consultorio, o 0 = toda la organizacion. Existe solo para que el unique funcione: en MySQL varios NULL no colisionan, asi que sin este centinela se podrian crear dos memberships de alcance organizacion para la misma cuenta';

ALTER TABLE membership
    DROP INDEX uk_membership_org_account;

ALTER TABLE membership
    ADD CONSTRAINT uk_membership_org_account_scope
        UNIQUE (organization_id, account_id, consultorio_scope);
