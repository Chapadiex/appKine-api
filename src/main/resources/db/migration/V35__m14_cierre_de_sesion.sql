-- =====================================================================================
-- AKINE-06.05 — Resultado, proxima conducta y cierre idempotente (M14).
--
-- RF-M14-006..008; RN-M14-002 y RN-M14-005.
--
-- ---------------------------------------------------------------------------------------
-- RECABLEADO DE DP-10: EL CORRELATIVO ES POR HISTORIA CLINICA, NO POR CASO
--
-- El plan pide un unique `(caso, numeroSesion)`. El Caso Clinico (04.03) quedo fuera del
-- Paquete B, asi que el correlativo cuelga de la Historia Clinica: es "la sesion numero 8
-- de este paciente", que ademas es lo que un kinesiologo cuenta en voz alta. Cuando 04.03
-- llegue, el numero por Caso se agrega al lado; renumerar sesiones ya cerradas seria
-- reescribir historia clinica, que ADR-0011 y la regla maestra 10 prohiben.
--
-- ---------------------------------------------------------------------------------------
-- LAS CUATRO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. `sesion_numerador` NO ES UN CACHE DE COUNT(*). Existe para poder asignar el numero de
--    forma ATOMICA: un `SELECT MAX(numero)+1` deja una ventana entre la lectura y la
--    escritura, y dos cierres concurrentes de dos sesiones del MISMO paciente se llevan el
--    mismo numero. El `UPDATE ... SET ultimo_numero = ultimo_numero + 1` toma un lock
--    exclusivo de fila y serializa, sin leer nada antes.
--
--    La fila se crea con `INSERT ... ON DUPLICATE KEY UPDATE`, no leyendo-y-despues-
--    insertando. Es la leccion de AKINE-05.02: la creacion perezosa dentro de la
--    transaccion produce un DEADLOCK entre las primeras N escrituras concurrentes, y
--    envolverla en un try/catch no alcanza porque atrapar una excepcion de persistencia no
--    des-marca la transaccion.
--
-- 2. EL UNIQUE `(historia_clinica_id, numero_sesion)` ES EL RESPALDO, NO EL MECANISMO.
--    A diferencia del solapamiento de turnos —que ningun unique puede expresar, ver V30—
--    aca la regla SI es igualdad, asi que el motor puede hacerla cumplir. Si alguna vez el
--    numerador falla, la base impide el numero repetido en vez de dejarlo pasar.
--
-- 3. `numero_sesion` NULL SIGNIFICA "ABIERTA", Y ES LA IDEMPOTENCIA DEL CIERRE. Cerrar dos
--    veces no renumera: la segunda llamada ve el numero ya asignado y devuelve el mismo
--    resultado. RN-M14-005 pide un comando idempotente con resultado estable ante retry, y
--    un profesional que aprieta dos veces "cerrar" es el caso normal.
--
-- 4. CERRAR NO COBRA. DP-06 y la regla de la etapa: "cierre clinico != cobro". Esta tabla
--    no tiene una sola columna economica y el cierre no crea ninguna obligacion. La
--    obligacion se DERIVA despues, en AKINE-07.01, leyendo las sesiones cerradas. Meter el
--    importe aca ataria el registro clinico al circuito economico y haria que un problema
--    de facturacion bloqueara una historia clinica.
-- =====================================================================================

ALTER TABLE sesion
    ADD COLUMN numero_sesion        INT          NULL
        COMMENT 'Correlativo por historia clinica. NULL = abierta. Ver los puntos 2 y 3.'
        AFTER estado,

    -- RF-M14-006: como respondio el paciente a lo que se le hizo hoy.
    ADD COLUMN respuesta_tratamiento VARCHAR(500) NULL
        COMMENT 'Como respondio a lo realizado en esta sesion.'
        AFTER evaluada_en,

    ADD COLUMN tolerancia           VARCHAR(16)  NULL
        COMMENT 'BUENA, REGULAR o MALA.'
        AFTER respuesta_tratamiento,

    -- RF-M14-007: que se le indica al paciente hasta la proxima.
    ADD COLUMN indicaciones         VARCHAR(1000) NULL
        COMMENT 'Lo que el paciente se lleva: ejercicios, cuidados, pautas de alarma.'
        AFTER tolerancia,

    -- RF-M14-008: que sigue. Es lo que convierte una sesion suelta en un tratamiento.
    ADD COLUMN proxima_conducta     VARCHAR(16)  NULL
        COMMENT 'CONTINUA, ALTA, DERIVA o REEVALUA.'
        AFTER indicaciones,

    ADD COLUMN nota_de_cierre       VARCHAR(2000) NULL
        COMMENT 'Nota equivalente al detalle de tratamientos, que es AKINE-06.04 y quedo cortada. Ver la nota de alcance abajo.'
        AFTER proxima_conducta,

    ADD COLUMN asistencia           VARCHAR(16)  NULL
        COMMENT 'PRESENTE o AUSENTE. Una sesion se puede cerrar con el paciente ausente: eso tambien es un hecho clinico.'
        AFTER nota_de_cierre,

    ADD COLUMN cerrada_en           DATETIME(6)  NULL
        COMMENT 'Instante del cierre. Junto con numero_sesion define que la sesion esta cerrada.'
        AFTER asistencia,

    ADD COLUMN cerrada_por_cuenta_id BIGINT      NULL
        COMMENT 'Quien cerro. Puede no ser quien inicio si alguna vez existe el reemplazo autorizado.'
        AFTER cerrada_en,

    -- Punto 2: el respaldo del numerador.
    ADD CONSTRAINT uk_sesion_numero
        UNIQUE (historia_clinica_id, numero_sesion),

    ADD CONSTRAINT ck_sesion_numero_positivo
        CHECK (numero_sesion IS NULL OR numero_sesion > 0),

    ADD CONSTRAINT ck_sesion_tolerancia
        CHECK (tolerancia IS NULL OR tolerancia IN ('BUENA', 'REGULAR', 'MALA')),

    ADD CONSTRAINT ck_sesion_proxima_conducta
        CHECK (proxima_conducta IS NULL
            OR proxima_conducta IN ('CONTINUA', 'ALTA', 'DERIVA', 'REEVALUA')),

    ADD CONSTRAINT ck_sesion_asistencia
        CHECK (asistencia IS NULL OR asistencia IN ('PRESENTE', 'AUSENTE')),

    -- Una sesion cerrada tiene numero, instante y actor: los tres o ninguno. Sin este
    -- CHECK, una fila a medio cerrar pasaria por abierta para unas consultas y por cerrada
    -- para otras, y ninguna de las dos lecturas estaria mal.
    ADD CONSTRAINT ck_sesion_cierre_completo
        CHECK ((numero_sesion IS NULL AND cerrada_en IS NULL AND cerrada_por_cuenta_id IS NULL)
            OR (numero_sesion IS NOT NULL AND cerrada_en IS NOT NULL
                AND cerrada_por_cuenta_id IS NOT NULL));


-- -------------------------------------------------------------------------------------
-- sesion_numerador — asignacion atomica del correlativo. Ver el punto 1.
-- -------------------------------------------------------------------------------------
CREATE TABLE sesion_numerador
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id     BIGINT      NOT NULL,
    historia_clinica_id BIGINT      NOT NULL,

    ultimo_numero       INT         NOT NULL DEFAULT 0
        COMMENT 'Ultimo correlativo entregado. Se incrementa con UPDATE, nunca con SELECT MAX + 1.',

    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_sesion_numerador
        UNIQUE (organization_id, historia_clinica_id),

    CONSTRAINT fk_sesion_numerador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_sesion_numerador_historia
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Secuencia de sesiones por historia clinica. No es un cache de COUNT(*): existe para asignar de forma atomica.';


-- Las sesiones cerradas de un paciente, en orden. Es la lectura del timeline y la que
-- AKINE-07.01 va a usar para derivar obligaciones de lo efectivamente prestado.
CREATE INDEX ix_sesion_cerradas
    ON sesion (organization_id, historia_clinica_id, cerrada_en);


-- ---------------------------------------------------------------------------------------
-- NOTA DE ALCANCE: `nota_de_cierre` Y LO QUE FALTA
--
-- La etapa valida "tratamiento o nota equivalente". El detalle estructurado de tratamientos
-- realizados y espacios usados es AKINE-06.04, que el Paquete B dejo afuera, asi que lo que
-- queda es la NOTA equivalente — y por eso el cierre la exige junto con el resultado. No es
-- un campo de descarte: mientras 06.04 no exista, es lo unico que registra que se hizo.
-- ---------------------------------------------------------------------------------------
