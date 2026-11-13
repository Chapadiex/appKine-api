-- =====================================================================================
-- AKINE-02.07 — Habilitacion de profesionales y espacios por Oferta.
--
-- V28 y no V26: AKINE-03.01 se estaba construyendo en paralelo en este mismo repo y fue tomando
-- V26 y despues V27 para persona_y_perfil_paciente. Dos migraciones con el mismo numero impiden
-- que Flyway arranque, y el orden entre estas dos no importa —no se tocan— asi que correrse es
-- suficiente.
--
-- Que responden estas dos tablas: QUIEN puede prestar cada oferta y DONDE puede
-- prestarse. RF-M27-006, RF-M04-008 y RF-M05-007.
--
-- ---------------------------------------------------------------------------------------
-- LAS CINCO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. DOS TABLAS Y NO UNA POLIMORFICA. Las columnas se parecen y el tipo de cosa no: un
--    profesional se resuelve contra membership con su rol, un espacio contra espacio con
--    su capacidad fisica. Una tabla con tipo_recurso obligaria a una FK nullable por
--    destino, haria imposible el unique que impide habilitar dos veces el mismo recurso, y
--    convertiria cada consulta de la agenda en un filtro por discriminador. Se ahorra una
--    tabla y se pierden las tres garantias.
--
-- 2. LISTA VACIA SIGNIFICA "TODOS", NO "NINGUNO". Es la decision con mas consecuencias de
--    la etapa. Una oferta recien creada no tiene filas aca; si eso significara "nadie puede
--    prestarla", toda oferta naceria inutilizable y el alta de 02.06 —que a proposito no
--    pide quince datos— quedaria rota. En cuanto se agrega la primera fila la oferta pasa a
--    estar restringida. El riesgo esta en el otro lado y por eso la API devuelve
--    `restringida` explicito: quien borra la ultima habilitacion creyendo que restringe,
--    abre la oferta a todos.
--
-- 3. HABILITACION NO ES PERMISO. Estas tablas NO otorgan acceso a nada y no se consultan
--    jamas desde PermissionGuard. Un profesional habilitado sigue necesitando su membership
--    vigente para entrar; un ORG_ADMIN sin habilitacion sigue pudiendo administrar la oferta
--    aunque no pueda prestarla. Habilitacion responde "puede prestar esto", permiso responde
--    "puede tocar esto". Se cruzan recien en la agenda, que va a exigir las dos.
--
-- 4. EL PROFESIONAL SE IDENTIFICA POR MEMBERSHIP, NO POR CUENTA. La misma persona puede ser
--    profesional en un centro y administrativa en otro: habilitar la cuenta habilitaria a
--    alguien que en esta organizacion no atiende. Es la misma decision que tomo V23 para la
--    disponibilidad.
--
-- 5. consultorio_id ES REDUNDANTE CON LA OFERTA, Y ESTA A PROPOSITO. Se podria llegar a la
--    sede por oferta_id. Se desnormaliza porque la agenda va a preguntar "quien esta
--    habilitado en esta sede" sin querer pasar por oferta, y sin esta columna ese indice no
--    se puede construir. La coherencia la sostiene la aplicacion, que copia el
--    consultorio_id de la oferta al crear.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO SE MODELA
--
-- La disciplina del profesional por habilitacion. RF-M05-007 nombra "disciplinas y
-- servicios", y la disciplina ya vive en el catalogo clinico de 02.05: atarla aca la
-- duplicaria. Lo que se habilita es la OFERTA, que ya cuelga de un servicio.
--
-- La prioridad u orden entre habilitados. Ningun RF la pide y seria la semilla de un
-- asignador automatico que nadie especifico.
--
-- Datos: sinteticos. Ningun dato personal.
-- =====================================================================================

CREATE TABLE oferta_profesional_habilitado
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT      NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (ADR-0004) y todo unique e indice de esta tabla empieza por el. Aca NO hay excepcion que declarar: a diferencia de servicio, una habilitacion pertenece siempre a un tenant porque la oferta pertenece a uno',
    consultorio_id      BIGINT      NOT NULL COMMENT 'Sede de la oferta. Redundante con oferta_id A PROPOSITO: ver el punto 5 de la cabecera',
    oferta_id           BIGINT      NOT NULL COMMENT 'Oferta que este profesional puede prestar. FK RESTRICT: una oferta con habilitaciones no se puede borrar fisicamente, que es lo que RF-M27-002 pide',
    membership_id       BIGINT      NOT NULL COMMENT 'Vinculo del profesional con esta organizacion. NO es account_id: ver el punto 4 de la cabecera',

    valid_from          DATETIME(6) NOT NULL COMMENT 'Instante UTC desde el que la habilitacion rige. Eje de VIGENCIA, distinto del ciclo de vida de active/deleted_at',
    valid_until         DATETIME(6) NULL COMMENT 'Instante UTC hasta el que rige, EXCLUSIVO. NULL = sin fin previsto, que es un estado real y no un valor faltante. Un profesional que se va tres meses NO se borra de aca: se le pone fin de vigencia y el historico sigue explicando por que atendio lo que atendio',

    active              TINYINT(1)  NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica. Quitar una habilitacion no borra: cierra',
    deleted_at          DATETIME(6) NULL COMMENT 'Instante UTC de la baja logica. NULL mientras este activa',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio al dar de baja: sin el, la auditoria no responde por que seis meses despues',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique: el instante de la baja, o el centinela 1970-01-01 mientras la fila este vigente. Sin el, dos habilitaciones activas del mismo profesional para la misma oferta no colisionarian, porque en MySQL varios NULL no colisionan en un unique',

    version             BIGINT      NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista',
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,

    CONSTRAINT pk_oferta_profesional_habilitado PRIMARY KEY (id),

    CONSTRAINT uk_oferta_profesional_vigente
        UNIQUE (organization_id, oferta_id, membership_id, deleted_key),

    CONSTRAINT fk_oph_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_oph_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_oph_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),
    CONSTRAINT fk_oph_membership FOREIGN KEY (membership_id) REFERENCES membership (id),

    CONSTRAINT ck_oph_baja_coherente CHECK (
        (active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
        OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    CONSTRAINT ck_oph_vigencia_coherente CHECK (valid_until IS NULL OR valid_until > valid_from),

    INDEX ix_oph_oferta (organization_id, oferta_id, active),
    INDEX ix_oph_profesional (organization_id, membership_id, active),
    INDEX ix_oph_sede (organization_id, consultorio_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT 'Que profesionales pueden prestar una oferta (RF-M27-006, RF-M05-007). Lista vacia = sin restringir. Propietario: modulo offering';


CREATE TABLE oferta_espacio_habilitado
(
    id                  BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT      NOT NULL COMMENT 'Tenant propietario. Ver la cabecera de oferta_profesional_habilitado',
    consultorio_id      BIGINT      NOT NULL COMMENT 'Sede de la oferta. Redundante a proposito: punto 5 de la cabecera',
    oferta_id           BIGINT      NOT NULL COMMENT 'Oferta que puede prestarse en este espacio',
    espacio_id          BIGINT      NOT NULL COMMENT 'Recurso fisico habilitado (RF-M04-008). Que un espacio sirva para una oferta NO se infiere de su tipo ni de su nombre: elegir "Pileta" no autoriza hidroterapia ahi, lo autoriza esta fila',

    valid_from          DATETIME(6) NOT NULL COMMENT 'Instante UTC desde el que rige',
    valid_until         DATETIME(6) NULL COMMENT 'Instante UTC hasta el que rige, EXCLUSIVO. NULL = sin fin previsto',

    active              TINYINT(1)  NOT NULL DEFAULT 1,
    deleted_at          DATETIME(6) NULL,
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,

    CONSTRAINT pk_oferta_espacio_habilitado PRIMARY KEY (id),

    CONSTRAINT uk_oferta_espacio_vigente
        UNIQUE (organization_id, oferta_id, espacio_id, deleted_key),

    CONSTRAINT fk_oeh_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_oeh_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_oeh_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),
    CONSTRAINT fk_oeh_espacio FOREIGN KEY (espacio_id) REFERENCES espacio (id),

    CONSTRAINT ck_oeh_baja_coherente CHECK (
        (active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
        OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    CONSTRAINT ck_oeh_vigencia_coherente CHECK (valid_until IS NULL OR valid_until > valid_from),

    INDEX ix_oeh_oferta (organization_id, oferta_id, active),
    INDEX ix_oeh_espacio (organization_id, espacio_id, active),
    INDEX ix_oeh_sede (organization_id, consultorio_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT 'En que espacios puede prestarse una oferta (RF-M04-008). Lista vacia = sin restringir. La capacidad efectiva sale del minimo entre la de la oferta y las de estos espacios, y se calcula AL LEER: materializarla obligaria a recalcular cada vez que cambia la capacidad de un espacio, en filas de otro modulo. Propietario: modulo offering';
