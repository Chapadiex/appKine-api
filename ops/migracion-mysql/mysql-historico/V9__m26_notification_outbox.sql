-- =====================================================================================
-- AKINE-01.02 — Outbox transaccional de notificaciones (modulo notification, M26).
--
-- Por que existe. La spec NO nombra el patron "outbox": lo normativo es RN-M26-001 (el
-- fallo de una notificacion no revierte la operacion principal), RF-M26-004 (registrar el
-- resultado: enviado / fallido / reintentado), RF-M26-005 y la seccion 34 (reprocesar sin
-- duplicar efectos de negocio). El outbox es la decision de DISEÑO que materializa esas
-- tres exigencias con una sola tabla: la fila se inserta en la MISMA transaccion que el
-- negocio que la origina, asi que existe si y solo si el negocio se confirmo, y el envio
-- posterior —que puede fallar cuantas veces quiera— ya no puede tocar esa transaccion.
--
-- REGLA INNEGOCIABLE (T-11 / challenge D-6, RN-M02-003): esta tabla JAMAS almacena un token
-- de activacion, de invitacion ni de reset en claro, ni un enlace que lo contenga. Se
-- consulta para diagnosticar entregas fallidas, se reintenta desde una pantalla
-- administrativa y termina en los backups: un token en claro aca es una credencial
-- persistida. Se guarda una REFERENCIA opaca al token (referencia_token_id) y el enlace se
-- reconstruye recien en el momento del envio.
--
-- Propietario: modulo notification. Los demas modulos encolan por
-- com.akine.notification.spi.NotificationOutbox y nunca escriben esta tabla.
-- =====================================================================================

CREATE TABLE notification_outbox (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id       BIGINT       NULL COMMENT 'Excepcion parcial a ADR-0004, justificada: hay notificaciones PREVIAS al tenant. La activacion de una cuenta invitada o la recuperacion de contrasena ocurren cuando la persona todavia no tiene —o no eligio— organizacion, asi que no hay tenant al que atribuir la fila. NULL = evento de identidad global, operable solo por admin de plataforma; NOT NULL = acotado a ese tenant y visible en su pantalla de reintento',
    tipo                  VARCHAR(40)  NOT NULL COMMENT 'ACTIVACION_CUENTA, INVITACION_COLABORADOR, RECUPERACION_PASSWORD, CUENTA_YA_REGISTRADA. Determina el template',
    destinatario          VARCHAR(320) NOT NULL COMMENT 'Email destino. Nunca se loguea completo: se enmascara (j***@dominio)',
    payload_sanitizado    JSON         NOT NULL COMMENT 'SOLO datos de render no sensibles: {nombre, organizacionNombre}. RN-M26-002. PROHIBIDO: tokens, enlaces, URLs, contrasenas, contenido clinico. El dominio lo valida al construir la fila y rechaza la insercion si detecta alguno',
    estado                VARCHAR(20)  NOT NULL COMMENT 'PENDIENTE -> PROCESANDO -> ENVIADA | FALLIDA | REINTENTABLE -> ... -> AGOTADA. Mapeo a RF-M26-004: enviado=ENVIADA, fallido=FALLIDA/AGOTADA, reintentado=REINTENTABLE',
    intentos              INT          NOT NULL DEFAULT 0,
    max_intentos          INT          NOT NULL DEFAULT 5 COMMENT 'Backoff exponencial acotado: 1, 5, 15, 60, 180 minutos. Agotados, la fila pasa a AGOTADA con motivo',
    proxima_ejecucion_en  DATETIME(6)  NOT NULL COMMENT 'Instante UTC a partir del cual el worker puede tomarla. Ahora al crear; ahora+backoff al fallar',
    procesando_desde      DATETIME(6)  NULL COMMENT 'Lease: una fila PROCESANDO con procesando_desde anterior a now-5min quedo huerfana (worker muerto) y vuelve a REINTENTABLE',
    clave_idempotente     VARCHAR(120) NOT NULL COMMENT 'UNIQUE. Garantia de RF-M26-005 / RN-M26-003 / seccion 34: el productor que reintenta su transaccion no crea una segunda notificacion. p.ej. activacion-cuenta:{tokenId}',
    referencia_token_id   VARCHAR(64)  NULL COMMENT 'Referencia OPACA al token de un solo uso que vive en identity (su id, jamas su valor). El adaptador de email reconstruye el enlace al enviar por el puerto SecureLinkResolver. Si el token ya fue consumido o expiro, el envio se marca AGOTADA con motivo explicito: reenviar un enlace muerto no sirve (T-11)',
    error_sanitizado      VARCHAR(500) NULL COMMENT 'Motivo del ultimo fallo, SANITIZADO. Jamas la excepcion cruda: puede traer host y credenciales del SMTP, la direccion completa o un enlace con token',
    enviado_en            DATETIME(6)  NULL COMMENT 'Instante UTC del envio exitoso',
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_notification_outbox PRIMARY KEY (id),
    -- Idempotencia: es la restriccion la que garantiza el "no duplicar", no un SELECT previo.
    -- Dos productores concurrentes con la misma clave leen ambos "no existe", los dos
    -- insertan, y el segundo choca aca. Un chequeo previo sin este unique detras es una
    -- carrera con otro nombre.
    CONSTRAINT uk_notification_outbox_clave UNIQUE (clave_idempotente),
    -- La consulta del worker, literal: estado IN (...) AND proxima_ejecucion_en <= now
    -- ORDER BY proxima_ejecucion_en. Sin este indice el tick escanea la tabla entera, que
    -- crece para siempre porque las ENVIADA no se borran.
    INDEX ix_notification_outbox_pendientes (estado, proxima_ejecucion_en),
    -- Pantalla administrativa de reintento, acotada al tenant.
    INDEX ix_notification_outbox_tenant (organization_id, estado, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Outbox transaccional de notificaciones (RN-M26-001, RF-M26-004/005). Sin FK a organization: la fila sobrevive a cualquier operacion sobre el tenant y existe incluso antes de que el tenant exista. JAMAS tokens ni enlaces en claro (T-11).';
