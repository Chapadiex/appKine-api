-- =====================================================================================
-- AKINE-02.03 — Ciclo de vida de colaboradores: la invitacion (M05).
--
-- Trazabilidad: RF-M05-001 (invitar colaborador), RF-M05-002 (aceptar/rechazar sin
-- duplicar usuarios), RF-M05-006 (desvincular), RF-M26-001 (enviar activacion/invitacion);
-- RN-M05-003 (desvincular no elimina autoria historica), RN-M05-004 (los turnos futuros
-- afectados quedan visibles para resolucion).
--
-- ADR-0003 (Flyway unica autoridad), ADR-0004 (persistencia multi-tenant), ADR-0007
-- (expandir-migrar-contraer), ADR-0018 (anti-enumeracion uniforme).
--
-- Propietario de la tabla: modulo `identity`, igual que `onboarding_registro` (V8) y por el
-- mismo motivo — el flujo arranca con un EMAIL, que es dato de `identity`, y termina creando
-- una membership, que crea `organization` por su SPI `MembershipProvisioning`. La flecha
-- inversa no existe: ArchUnit la rechaza.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE ESTA TABLA NO ES UNA EXCEPCION A ADR-0004
--
-- `organization_id` va NOT NULL. Una invitacion siempre la emite una organizacion concreta:
-- no existen invitaciones de plataforma, igual que no existen solicitudes de catalogo de
-- plataforma (ADR-0021 lo dice de `catalogo_solicitud`). El unico nullable de alcance es
-- `consultorio_id`, y significa lo mismo que en `membership`: vinculo de alcance
-- ORGANIZACION, no un hueco.
--
-- ---------------------------------------------------------------------------------------
-- EL TOKEN NO VIVE EN `token_verificacion`, Y NO ES UN OLVIDO
--
-- `token_verificacion` (V7) tiene `cuenta_id NOT NULL`: sus tres tipos cuelgan de una cuenta
-- que ya existe. Una invitacion es exactamente el caso contrario — se emite ANTES de que la
-- cuenta exista, y en el caso mas comun la crea al aceptarse—, asi que su token vive aca,
-- con la misma forma: solo el SHA-256 en hex, nunca el token en claro.
--
-- El token en claro existe en una variable local del servicio que lo emite y en el enlace que
-- viaja al outbox por el campo de transporte, jamas en el payload consultable (T-11).
--
-- ---------------------------------------------------------------------------------------
-- LA TRAMPA DE LOS NULL EN LOS UNIQUE DE MySQL, SEXTA VEZ EN ESTE REPOSITORIO
--
-- Lo que hay que garantizar: **una sola invitacion PENDIENTE por (organizacion, alcance,
-- email)**. Invitar dos veces a la misma persona al mismo lugar mientras la primera sigue sin
-- responder emite dos enlaces validos y deja al invitado eligiendo cual usar.
--
-- Lo que NO sirve:
--
--   * `UNIQUE (organization_id, consultorio_id, email_normalizado)` a secas: prohibiria
--     volver a invitar a alguien que rechazo o a quien se desvinculo despues, que es un caso
--     legitimo y frecuente.
--   * `UNIQUE (..., resuelta_en)`: es la inversion exacta de lo que se busca. Todas las
--     invitaciones PENDIENTES tienen `resuelta_en IS NULL`, y en MySQL varios NULL no
--     colisionan: protegeria el historico y desprotegeria justo lo vigente.
--   * un indice unico parcial (`UNIQUE ... WHERE`): no existe en MySQL 8.4, es de PostgreSQL.
--   * `consultorio_id` a secas dentro del unique: una invitacion de alcance ORGANIZACION lo
--     lleva NULL, con lo que dos invitaciones de alcance organizacion al mismo email NO
--     colisionarian — el mismo agujero, en el otro eje.
--
-- Lo que se usa, y son los dos centinelas que este repositorio ya tiene:
--
--   * `consultorio_key = IFNULL(consultorio_id, 0)` — centinela numerico, la forma de V10 y
--     de V20. `0` no es ni puede ser el id de ninguna sede: `consultorio.id` es
--     AUTO_INCREMENT y arranca en 1.
--   * `resuelta_key = IFNULL(resuelta_en, '1970-01-01')` — centinela de fecha, la forma de
--     V18 y V19. Todas las pendientes comparten 1970-01-01 y por lo tanto colisionan entre
--     si, que es exactamente lo buscado; cada resuelta lleva su instante y sale del camino.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE NO HAY ESTADO `EXPIRADA` EN LA COLUMNA
--
-- Expirar no es una decision de nadie: es el reloj pasando. Un estado almacenado obligaria a
-- un job que lo escriba, y entre que el token vence y el job corre la base diria PENDIENTE
-- sobre algo que ya no se puede aceptar. La expiracion se deriva de `expira_en` en cada
-- lectura, que no puede desincronizarse porque no hay nada que sincronizar.
--
-- El `estado` almacenado tiene entonces cuatro valores y los tres ultimos son terminales:
-- PENDIENTE, ACEPTADA, RECHAZADA, CANCELADA.
-- =====================================================================================

CREATE TABLE colaborador_invitacion
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id      BIGINT       NOT NULL COMMENT 'Tenant que invita. NOT NULL: no existen invitaciones de plataforma. Todo unique e indice de esta tabla empieza por el',
    consultorio_id       BIGINT       NULL COMMENT 'Sede del vinculo propuesto, o NULL para alcance ORGANIZACION. Mismo significado que en membership: NULL es un alcance, no un hueco',

    email_normalizado    VARCHAR(320) NOT NULL COMMENT 'Direccion a la que se invita, ya normalizada por EmailNormalizado. Se guarda aunque la cuenta exista: la invitacion es al EMAIL, y la cuenta puede crearse recien al aceptar',
    role_code            VARCHAR(32)  NOT NULL COMMENT 'Rol con el que quedaria vinculada la persona. Se valida contra RoleCode al emitir y otra vez al aceptar: entre las dos cosas pueden pasar semanas',

    token_hash           CHAR(64)     NOT NULL COMMENT 'SHA-256 en hex del token del enlace. El token en claro no se persiste, no se loguea y no viaja al payload consultable del outbox (T-11)',
    expira_en            DATETIME(6)  NOT NULL COMMENT 'Instante UTC hasta el que el enlace sirve. La expiracion NO se materializa como estado: se deriva de aca en cada lectura',

    estado               VARCHAR(20)  NOT NULL COMMENT 'PENDIENTE, ACEPTADA, RECHAZADA o CANCELADA. Los tres ultimos son terminales. EXPIRADA no esta a proposito: ver la cabecera',
    resuelta_en          DATETIME(6)  NULL COMMENT 'Instante UTC en que dejo de estar pendiente. NULL mientras lo este. Es ademas el discriminador del unique',
    resolucion_nota      VARCHAR(280) NULL COMMENT 'Motivo declarado del rechazo o de la cancelacion. Opcional al rechazar -el invitado no le debe una explicacion a nadie- y obligatorio al cancelar, que es una decision del administrador y tiene que responder por que',

    invitada_por_account_id BIGINT    NOT NULL COMMENT 'Cuenta del administrador que emitio la invitacion. Sobrevive a su desvinculacion: la autoria historica no se borra (RN-M05-003)',
    aceptada_por_account_id BIGINT    NULL COMMENT 'Cuenta que acepto. Puede no existir al emitir la invitacion: se crea al aceptar cuando el invitado no tenia cuenta',
    membership_id        BIGINT       NULL COMMENT 'Vinculo creado al aceptar. NULL en cualquier otro estado. Es lo que permite responder que membership salio de que invitacion sin cruzar por email',

    version              BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Cancelar reenvia la version: dos administradores resolviendo la misma invitacion dan 409 concurrent-modification en vez de pisarse',
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,

    consultorio_key      BIGINT AS (IFNULL(consultorio_id, 0)) STORED NOT NULL
        COMMENT 'Centinela del alcance para el unique. 0 significa alcance ORGANIZACION: consultorio.id es AUTO_INCREMENT y arranca en 1, asi que no puede chocar con ninguna sede real. Sin el, dos invitaciones de alcance organizacion al mismo email no colisionarian, porque en MySQL varios NULL no colisionan',
    resuelta_key         DATETIME(6) AS (IFNULL(resuelta_en, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Centinela de resolucion para el unique. Todas las PENDIENTES comparten 1970-01-01 y por eso colisionan entre si, que es lo buscado; cada resuelta lleva su instante y sale del camino, de modo que volver a invitar a quien rechazo sigue siendo posible',

    CONSTRAINT pk_colaborador_invitacion PRIMARY KEY (id),

    -- Una sola invitacion PENDIENTE por organizacion, alcance y email. Ver la cabecera para
    -- por que los dos discriminadores son columnas generadas y no las columnas originales.
    CONSTRAINT uk_colaborador_invitacion_pendiente
        UNIQUE (organization_id, consultorio_key, email_normalizado, resuelta_key),

    -- El token es la credencial: dos invitaciones no pueden compartirlo ni por accidente.
    CONSTRAINT uk_colaborador_invitacion_token
        UNIQUE (token_hash),

    CONSTRAINT fk_colaborador_invitacion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_colaborador_invitacion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_colaborador_invitacion_invitada_por
        FOREIGN KEY (invitada_por_account_id) REFERENCES cuenta (id),
    CONSTRAINT fk_colaborador_invitacion_aceptada_por
        FOREIGN KEY (aceptada_por_account_id) REFERENCES cuenta (id),
    CONSTRAINT fk_colaborador_invitacion_membership
        FOREIGN KEY (membership_id) REFERENCES membership (id),

    CONSTRAINT ck_colaborador_invitacion_estado
        CHECK (estado IN ('PENDIENTE', 'ACEPTADA', 'RECHAZADA', 'CANCELADA')),

    -- Coherencia del ciclo de vida: los campos de resolucion se mueven junto con el estado.
    -- Sin esto es posible una invitacion ACEPTADA sin fecha de resolucion —que ademas rompe
    -- el centinela del unique, porque cae en 1970 junto con las pendientes y bloquea invitar
    -- de nuevo a esa persona para siempre— y una PENDIENTE con membership ya creada.
    CONSTRAINT ck_colaborador_invitacion_resolucion_coherente
        CHECK ((estado = 'PENDIENTE' AND resuelta_en IS NULL
                AND aceptada_por_account_id IS NULL AND membership_id IS NULL)
            OR (estado = 'ACEPTADA' AND resuelta_en IS NOT NULL
                AND aceptada_por_account_id IS NOT NULL AND membership_id IS NOT NULL)
            OR (estado IN ('RECHAZADA', 'CANCELADA') AND resuelta_en IS NOT NULL
                AND aceptada_por_account_id IS NULL AND membership_id IS NULL)),

    -- Cancelar es una decision de un administrador y tiene que responder por que seis meses
    -- despues. Rechazar no: al invitado no se le exige explicar que no quiere entrar.
    CONSTRAINT ck_colaborador_invitacion_cancelacion_con_motivo
        CHECK (estado <> 'CANCELADA' OR resolucion_nota IS NOT NULL)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT ='Invitaciones a colaborar en una organizacion (M05, RF-M05-001/002). El token vive aca y no en token_verificacion porque se emite antes de que exista la cuenta';

-- El listado del administrador siempre filtra por tenant y casi siempre por estado: es la
-- pantalla de "quien falta responder". Sin este indice, esa consulta escanea todas las
-- invitaciones de la instalacion apenas haya mas de un tenant grande.
CREATE INDEX ix_colaborador_invitacion_tenant_estado
    ON colaborador_invitacion (organization_id, estado, created_at);
