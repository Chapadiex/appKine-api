-- =====================================================================================
-- DP-43 (19/10/2026) — tres decisiones de la atencion clinica (M14, F6).
--
-- Trazabilidad: U-24 / C-8 (RF-M14-002, atencion sin turno; reemplazo autorizado del
-- profesional, 06.01) y U-25 / C-6 (RF-M14-010, ventana para enmendar, 06.06). U-09
-- (RF-M10-007, caso obligatorio por oferta) NO necesita esquema: lee
-- `oferta_servicio_consultorio.requiere_caso_clinico`, que existe desde V24, y no toca las
-- sesiones viejas sin caso (no hay migracion de datos).
--
-- Propietario de todo lo de esta migracion: modulo encounter.
--
-- ---------------------------------------------------------------------------------------
-- 1. ATENCION SIN TURNO (`sesion.motivo_sin_turno`, `idempotency_key`, `request_hash`)
--
-- `turno_id` es nullable desde V33 porque RF-M14-002 admite la atencion sin turno, pero el
-- unico camino para iniciar era `POST .../sesiones/turnos/{turnoId}`. DP-43 abre el otro con
-- MOTIVO OBLIGATORIO: sin turno no hay reserva que explique por que el paciente estaba ahi,
-- y el motivo es esa explicacion. Es administrativo ("urgencia", "paciente sin turno que
-- espero"), no clinico, pero se guarda aca y no en la auditoria por la misma regla de 06.06:
-- `audit_event` se lee con `auditoria:read`, que no es un permiso clinico, y un texto libre
-- sobre un paciente puede terminar diciendo algo clinico.
--
-- `ck_sesion_motivo_solo_sin_turno`: el motivo es SOLO de la atencion sin turno ni
-- derivacion. No se exige al reves (sin turno => motivo) a proposito: la regla la hace
-- cumplir la aplicacion, y un CHECK en esa direccion tendria que verificarse contra las filas
-- existentes en produccion, cuando DP-43 dice que las sesiones viejas no se tocan.
--
-- IDEMPOTENCIA. Una atencion de turno es idempotente por `uk_sesion_turno`; una sin turno no
-- tiene esa ancla, y el doble click del profesional —el caso normal, no el raro— abriria dos
-- atenciones del mismo paciente. `Idempotency-Key` opcional con huella del pedido, igual que
-- la reserva de turno (V30, punto 4): misma clave y mismo pedido devuelve la sesion ya
-- creada; misma clave y otro pedido es 409 `idempotency-key-conflict`. El unique es el que
-- resuelve la carrera de dos pedidos simultaneos: el perdedor relee en READ_COMMITTED.
-- Varios NULL no colisionan en MySQL, asi que las sesiones sin clave no se estorban.
--
-- ---------------------------------------------------------------------------------------
-- 2. REEMPLAZO DEL PROFESIONAL (`sesion_reemplazo_profesional`)
--
-- "Escribir en la atencion ajena es 409" sigue: el reemplazo NO es un permiso que deja
-- escribir en la sesion de otro, es una operacion explicita que CAMBIA quien es el dueño.
-- Solo `sesion:reassign` (CONSULTORIO_ADMIN), con motivo, sobre una sesion ABIERTA, y deja
-- esta fila: quien reemplazo a quien, quien lo decidio, cuando y por que. Es append-only —un
-- hecho pasado no se da de baja (regla maestra 10)— y por eso no lleva active ni deleted_at.
-- `sesion.profesional_membership_id` pasa a ser escribible solo desde esta operacion; la
-- columna no cambia.
--
-- ---------------------------------------------------------------------------------------
-- 3. VENTANA PARA ENMENDAR (`politica_de_enmienda`)
--
-- Una fila por organizacion con los dias desde el cierre en que una sesion se puede enmendar.
-- SIN fila rige el valor por defecto, 30 dias (DP-43): asi ninguna organizacion existente
-- necesita backfill y el default vive en un solo lugar del codigo. Rango 1..3650: cero
-- dejaria a la organizacion sin poder corregir nada, que es otra decision y no esta tomada.
--
-- Nombres de constraint con la tabla adelante: en MySQL los nombres de FK y de CHECK son
-- unicos POR ESQUEMA (la leccion de V64).
-- =====================================================================================

ALTER TABLE sesion
    ADD COLUMN motivo_sin_turno VARCHAR(500) NULL
        COMMENT 'DP-43. Por que se atendio sin turno. Obligatorio en la atencion sin turno ni derivacion; NULL en las demas.'
        AFTER clase_id,
    ADD COLUMN idempotency_key VARCHAR(80) NULL
        COMMENT 'DP-43. Clave del cliente para el inicio sin turno. NULL en las demas.'
        AFTER motivo_sin_turno,
    ADD COLUMN request_hash CHAR(64) NULL
        COMMENT 'DP-43. SHA-256 del pedido de inicio sin turno, para distinguir el reintento del pedido distinto con la misma clave.'
        AFTER idempotency_key,

    ADD CONSTRAINT uk_sesion_idempotencia
        UNIQUE (organization_id, idempotency_key),

    ADD CONSTRAINT ck_sesion_idempotencia_con_huella
        CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),

    ADD CONSTRAINT ck_sesion_motivo_solo_sin_turno
        CHECK (motivo_sin_turno IS NULL OR (turno_id IS NULL AND derivacion_id IS NULL));


CREATE TABLE sesion_reemplazo_profesional
(
    id                                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id                     BIGINT       NOT NULL,
    consultorio_id                      BIGINT       NOT NULL,
    sesion_id                           BIGINT       NOT NULL,

    profesional_anterior_membership_id  BIGINT       NOT NULL COMMENT 'Quien atendia hasta el reemplazo.',
    profesional_nuevo_membership_id     BIGINT       NOT NULL COMMENT 'Quien atiende desde el reemplazo.',

    motivo                              VARCHAR(500) NOT NULL COMMENT 'Por que se reemplazo. Obligatorio (DP-43).',

    reemplazado_por_cuenta_id           BIGINT       NOT NULL COMMENT 'Quien decidio el reemplazo (CONSULTORIO_ADMIN).',
    reemplazado_en                      DATETIME(6)  NOT NULL,

    created_at                          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_sesion_reemplazo_profesional_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_sesion_reemplazo_profesional_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_sesion_reemplazo_profesional_sesion
        FOREIGN KEY (sesion_id) REFERENCES sesion (id),

    CONSTRAINT ck_sesion_reemplazo_profesional_distinto
        CHECK (profesional_anterior_membership_id <> profesional_nuevo_membership_id),
    CONSTRAINT ck_sesion_reemplazo_profesional_motivo
        CHECK (CHAR_LENGTH(TRIM(motivo)) > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'DP-43. Reemplazos del profesional de una sesion abierta: quien a quien, quien lo decidio y por que. Append-only. Propietario: modulo encounter';

CREATE INDEX ix_sesion_reemplazo_profesional_sesion
    ON sesion_reemplazo_profesional (organization_id, sesion_id, reemplazado_en);


CREATE TABLE politica_de_enmienda
(
    organization_id           BIGINT      NOT NULL PRIMARY KEY,

    ventana_dias              INT         NOT NULL COMMENT 'Dias desde el cierre en que una sesion se puede enmendar (DP-43).',

    actualizada_en            DATETIME(6) NOT NULL,
    actualizada_por_cuenta_id BIGINT      NOT NULL,

    version                   BIGINT      NOT NULL DEFAULT 0,

    created_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_politica_de_enmienda_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT ck_politica_de_enmienda_ventana
        CHECK (ventana_dias BETWEEN 1 AND 3650)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'DP-43. Ventana para enmendar una sesion cerrada, por organizacion. Sin fila rigen 30 dias. Propietario: modulo encounter';
