-- =====================================================================================
-- AKINE-01.02 — Identidad: tokens de un solo uso y sesiones de refresh.
--
-- Propietario: modulo identity.
--
-- Trazabilidad: RF-M02-002 (emitir sesion segura), RF-M02-003 (enlace temporal, nueva
-- credencial, invalidar el token utilizado), RN-M02-003 (nada en texto plano),
-- T-11 / D-6 (el enlace de activacion nunca queda en claro en una tabla consultable).
--
-- REGLA QUE SOSTIENE LAS DOS TABLAS: el valor plano del token no se persiste NUNCA.
-- Se guarda unicamente su SHA-256 en hexadecimal. La busqueda se hace hasheando lo que
-- llega y comparando contra el unique, asi que no hace falta el valor original para
-- nada. SHA-256 sin salt alcanza porque estos tokens son 32 bytes de SecureRandom: no
-- son adivinables por diccionario, que es lo unico contra lo que protegeria un salt.
-- Consecuencia practica: un volcado de esta tabla —o un backup filtrado— no permite
-- activar una cuenta ni resetear una contrasena.
--
-- Sin organization_id, por el mismo motivo que cuenta (ver V6): cuelgan de la cuenta, que
-- es global. El contexto que el refresh recuerda (context_organization_id /
-- context_consultorio_id) NO es ownership de tenant: es el alcance del access token que
-- se va a re-emitir, y por eso es nullable.
--
-- Sin FK fisica hacia organization ni consultorio: el ownership de datos entre modulos se
-- sostiene en la aplicacion (ADR-0001). Una FK fisica acoplaria los esquemas y ademas
-- impediria conservar el rastro de un consultorio dado de baja.
-- =====================================================================================

CREATE TABLE token_verificacion (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    cuenta_id     BIGINT      NOT NULL,
    tipo          VARCHAR(20) NOT NULL COMMENT 'ACTIVACION | RESET',
    token_hash    CHAR(64)    NOT NULL COMMENT 'SHA-256 hex del token aleatorio. El valor plano jamas se persiste (RN-M02-003)',
    expira_en     DATETIME(6) NOT NULL COMMENT 'Instante UTC. RESET: +30 min. ACTIVACION: +7 dias',
    usado_en      DATETIME(6) NULL COMMENT 'Un solo uso: se setea al consumir (RF-M02-003)',
    invalidado_en DATETIME(6) NULL COMMENT 'Se setea al emitir un token nuevo del mismo tipo para la misma cuenta: un solo token vigente por tipo, para no dejar una superficie que crece con cada reintento',
    created_at    DATETIME(6) NOT NULL,
    CONSTRAINT pk_token_verificacion PRIMARY KEY (id),
    CONSTRAINT uk_token_verificacion_hash UNIQUE (token_hash),
    CONSTRAINT fk_token_verificacion_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id),
    -- "Los tokens vigentes de esta cuenta y este tipo": la consulta de la invalidacion.
    INDEX ix_token_verificacion_cuenta (cuenta_id, tipo, usado_en)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Tokens de un solo uso de activacion y de reset. Append-only salvo las marcas de consumo e invalidacion: la fila se conserva para poder explicar por que un enlace dejo de funcionar.';

CREATE TABLE refresh_token (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    cuenta_id               BIGINT      NOT NULL,
    familia_id              CHAR(36)    NOT NULL COMMENT 'UUID de la sesion: la cadena completa de rotaciones. Revocar por familia es lo que expulsa a un atacante que robo la cookie',
    token_hash              CHAR(64)    NOT NULL COMMENT 'SHA-256 hex del token opaco. Nunca el valor plano',
    context_organization_id BIGINT      NULL COMMENT 'Ultimo contexto seleccionado. Alcance del token, no ownership de tenant. Referencia logica, sin FK',
    context_consultorio_id  BIGINT      NULL,
    emitido_en              DATETIME(6) NOT NULL,
    expira_en               DATETIME(6) NOT NULL COMMENT 'ABSOLUTO desde el login: la rotacion NO lo extiende. Una sesion robada no se vuelve eterna por usarla',
    usado_en                DATETIME(6) NULL COMMENT 'Marcado al rotar. Presentar de nuevo un token ya usado es REUSO y revoca la familia entera',
    revocado_en             DATETIME(6) NULL,
    motivo_revocacion       VARCHAR(40) NULL COMMENT 'LOGOUT | ROTACION_REUSO | BLOQUEO | DESACTIVACION | RESET_PASSWORD | EXPIRACION',
    reemplazado_por_id      BIGINT      NULL COMMENT 'Cadena de rotacion auditable',
    ip                      VARCHAR(45) NULL COMMENT 'Best effort, truncada. IPv6 entra en 45 caracteres',
    user_agent              VARCHAR(200) NULL COMMENT 'Truncado. Para que el usuario pueda reconocer una sesion ajena',
    created_at              DATETIME(6) NOT NULL,
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_cuenta FOREIGN KEY (cuenta_id) REFERENCES cuenta (id),
    -- "Revocar todo lo vivo de esta cuenta": bloqueo, desactivacion y reset lo ejecutan
    -- dentro de la transaccion de negocio, asi que tiene que ser un index seek.
    INDEX ix_refresh_token_cuenta (cuenta_id, revocado_en),
    -- "Revocar la familia entera": la respuesta a la deteccion de reuso.
    INDEX ix_refresh_token_familia (familia_id, revocado_en)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Sesion de refresh server-side. Es estado y no un JWT a proposito: sin estado no hay revocacion real, y bloquear una cuenta tiene que cortar las sesiones vivas. Purgable (son artefactos de sesion, la traza vive en audit_event); el job de purga es deuda de la etapa de infraestructura.';
