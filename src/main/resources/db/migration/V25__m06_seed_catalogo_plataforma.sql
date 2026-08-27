-- =====================================================================================
-- AKINE-02.06 — Seed del catalogo clinico GLOBAL de plataforma.
--
-- Por que existe: la pantalla de catalogo le promete al usuario que los conceptos de la
-- plataforma son "la base comun para que un centro nuevo pueda trabajar el primer dia".
-- V20 creo las tablas y NINGUNA migracion las poblo, asi que hasta hoy un centro que se
-- registraba abria el catalogo y no encontraba nada: la promesa de la pantalla no se
-- cumplia. Mismo criterio que V4 con los planes y V15 con el PLATFORM_ADMIN — el catalogo
-- que el producto necesita el dia uno se siembra, no se espera.
--
-- LA SPEC NO ENUMERA CONCEPTOS. RF-M06-001 a RF-M06-003 definen como se administran, no
-- cuales son. Los de abajo son DECISION de diseno: un piso chico y defendible del dominio
-- de kinesiologia y rehabilitacion, elegido para que sea util sin imponer criterio. La
-- lista definitiva es una decision de negocio y se ajusta por la API de plataforma, NUNCA
-- editando esta migracion (ADR-0003).
--
-- ---------------------------------------------------------------------------------------
-- LAS TRES COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. organization_id NULL SIGNIFICA "DE PLATAFORMA", no "falta el dato" (ADR-0021). Es lo
--    que hace global a cada fila de aca. `owner_key` y `deleted_key` son GENERATED ALWAYS
--    y por eso NO se insertan: MySQL las calcula sola, y son las que sostienen los UNIQUE
--    —sin `owner_key`, dos especialidades globales homonimas no chocarian, porque en MySQL
--    varios NULL no colisionan entre si—.
--
-- 2. UNA PRACTICA GLOBAL SOLO PUEDE COLGAR DE UNA ESPECIALIDAD GLOBAL. La FK compara ids,
--    no alcances: no puede impedir que una practica global cuelgue de una especialidad de
--    un tenant. Aca eso se respeta porque cada subselect filtra por
--    `organization_id IS NULL`, y esa condicion no es cosmetica.
--
-- 3. `valid_from` en UTC_TIMESTAMP(6) y `valid_until` NULL: en servicio desde que se aplica
--    la migracion y sin fin previsto. NULL es un estado real, no un valor faltante.
--
-- Datos: sinteticos y de configuracion. Ningun dato personal.
-- =====================================================================================

-- --------------------------------------------------------------------------------------
-- Especialidades globales.
-- --------------------------------------------------------------------------------------
INSERT INTO especialidad
    (organization_id, codigo, name, descripcion, valid_from, valid_until,
     active, version, created_at, updated_at)
VALUES
    (NULL, 'KINESIOLOGIA', 'Kinesiologia',
     'Rehabilitacion del movimiento y tratamiento de la funcion motora.',
     UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (NULL, 'FISIOTERAPIA', 'Fisioterapia',
     'Agentes fisicos aplicados al tratamiento del dolor y la inflamacion.',
     UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (NULL, 'TERAPIA_OCUPACIONAL', 'Terapia ocupacional',
     'Recuperacion de la autonomia en las actividades de la vida diaria.',
     UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (NULL, 'FONOAUDIOLOGIA', 'Fonoaudiologia',
     'Evaluacion y tratamiento del habla, la voz, la audicion y la deglucion.',
     UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (NULL, 'NUTRICION', 'Nutricion',
     'Valoracion nutricional y plan alimentario.',
     UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
    (NULL, 'PSICOLOGIA', 'Psicologia',
     'Atencion psicologica ambulatoria.',
     UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

-- --------------------------------------------------------------------------------------
-- Practicas globales.
--
-- El `organization_id IS NULL` del WHERE es la regla del punto 2 de la cabecera escrita en
-- SQL: sin el, una organizacion que ya tuviera una especialidad propia con el mismo codigo
-- podria capturar el subselect y colgarle una practica global.
-- --------------------------------------------------------------------------------------

-- Kinesiologia.
INSERT INTO practica
    (organization_id, especialidad_id, codigo, name, descripcion, valid_from, valid_until,
     active, version, created_at, updated_at)
SELECT NULL, e.id, p.codigo, p.name, p.descripcion,
       UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM especialidad e
JOIN (
    SELECT 'KIN_EVAL'      AS codigo, 'Evaluacion kinesica'          AS name,
           'Primera consulta: anamnesis, examen funcional y plan.'   AS descripcion
    UNION ALL SELECT 'KIN_SESION',    'Sesion de kinesiologia',
           'Sesion de tratamiento kinesico individual.'
    UNION ALL SELECT 'KIN_RESPIRAT',  'Kinesiologia respiratoria',
           'Tecnicas de higiene bronquial y reeducacion respiratoria.'
    UNION ALL SELECT 'KIN_DOMICILIO', 'Kinesiologia a domicilio',
           'Sesion de tratamiento kinesico en el domicilio del paciente.'
) p
WHERE e.codigo = 'KINESIOLOGIA' AND e.organization_id IS NULL;

-- Fisioterapia.
INSERT INTO practica
    (organization_id, especialidad_id, codigo, name, descripcion, valid_from, valid_until,
     active, version, created_at, updated_at)
SELECT NULL, e.id, p.codigo, p.name, p.descripcion,
       UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM especialidad e
JOIN (
    SELECT 'FIS_MAGNETO'  AS codigo, 'Magnetoterapia'    AS name,
           'Campos magneticos de baja frecuencia.'       AS descripcion
    UNION ALL SELECT 'FIS_ULTRASONIDO', 'Ultrasonido terapeutico',
           'Ultrasonido aplicado a tejidos blandos.'
    UNION ALL SELECT 'FIS_ELECTRO',     'Electroterapia',
           'Corrientes analgesicas y de estimulacion muscular.'
    UNION ALL SELECT 'FIS_LASER',       'Laserterapia',
           'Laser de baja potencia con fin analgesico y antiinflamatorio.'
) p
WHERE e.codigo = 'FISIOTERAPIA' AND e.organization_id IS NULL;

-- Terapia ocupacional.
INSERT INTO practica
    (organization_id, especialidad_id, codigo, name, descripcion, valid_from, valid_until,
     active, version, created_at, updated_at)
SELECT NULL, e.id, p.codigo, p.name, p.descripcion,
       UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM especialidad e
JOIN (
    SELECT 'TO_EVAL'   AS codigo, 'Evaluacion de terapia ocupacional' AS name,
           'Valoracion funcional de las actividades de la vida diaria.' AS descripcion
    UNION ALL SELECT 'TO_SESION', 'Sesion de terapia ocupacional',
           'Sesion individual de reeducacion funcional.'
) p
WHERE e.codigo = 'TERAPIA_OCUPACIONAL' AND e.organization_id IS NULL;

-- Fonoaudiologia.
INSERT INTO practica
    (organization_id, especialidad_id, codigo, name, descripcion, valid_from, valid_until,
     active, version, created_at, updated_at)
SELECT NULL, e.id, p.codigo, p.name, p.descripcion,
       UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM especialidad e
JOIN (
    SELECT 'FON_EVAL'   AS codigo, 'Evaluacion fonoaudiologica' AS name,
           'Valoracion del habla, la voz y la deglucion.'        AS descripcion
    UNION ALL SELECT 'FON_SESION', 'Sesion de fonoaudiologia',
           'Sesion individual de tratamiento fonoaudiologico.'
) p
WHERE e.codigo = 'FONOAUDIOLOGIA' AND e.organization_id IS NULL;

-- Nutricion.
INSERT INTO practica
    (organization_id, especialidad_id, codigo, name, descripcion, valid_from, valid_until,
     active, version, created_at, updated_at)
SELECT NULL, e.id, p.codigo, p.name, p.descripcion,
       UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM especialidad e
JOIN (
    SELECT 'NUT_PRIMERA' AS codigo, 'Consulta nutricional inicial' AS name,
           'Valoracion antropometrica y plan alimentario.'          AS descripcion
    UNION ALL SELECT 'NUT_CONTROL', 'Control nutricional',
           'Seguimiento de la evolucion y ajuste del plan.'
) p
WHERE e.codigo = 'NUTRICION' AND e.organization_id IS NULL;

-- Psicologia.
INSERT INTO practica
    (organization_id, especialidad_id, codigo, name, descripcion, valid_from, valid_until,
     active, version, created_at, updated_at)
SELECT NULL, e.id, p.codigo, p.name, p.descripcion,
       UTC_TIMESTAMP(6), NULL, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM especialidad e
JOIN (
    SELECT 'PSI_PRIMERA' AS codigo, 'Primera entrevista psicologica' AS name,
           'Entrevista de admision y encuadre.'                      AS descripcion
    UNION ALL SELECT 'PSI_SESION', 'Sesion de psicoterapia',
           'Sesion individual de psicoterapia.'
) p
WHERE e.codigo = 'PSICOLOGIA' AND e.organization_id IS NULL;
