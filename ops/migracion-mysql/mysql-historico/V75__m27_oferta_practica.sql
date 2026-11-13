-- AKINE A-9 — Puente Oferta<->Practica (DP-11, RF-M06-008).
--
-- Que practicas del catalogo clinico (M06) puede prestar una Oferta, y cual es su PRINCIPAL.
-- Diseno: docs/diseno/AKINE-A-9-oferta-practica.md.
--
-- 1. N:M y no oferta.practica_id. RF-M06-008 paso 6: "una Oferta clinica utiliza una o mas
--    Practicas". Una columna en la oferta obligaria a duplicar ofertas (DP-11, descartada).
--
-- 2. Declara lo que la oferta PUEDE prestar, nunca lo que se presto. Lo prestado lo dicen los
--    tratamientos realizados (V55). Al devengar o consumir manda la practica realizada; la
--    principal solo si la sesion cerro sin tratamientos (DP-11).
--
-- 3. "Una sola principal por oferta" la sostiene la BASE. principal_key vale 1 solo en la fila
--    vigente marcada como principal, y NULL en todas las demas; en MySQL varios NULL no colisionan
--    en un unique, asi que uk_oferta_practica_principal admite cualquier cantidad de no principales
--    y UNA principal vigente. Mismo truco que deleted_key, sobre otra columna.
--
-- 4. Baja logica con el cuarteto active/deleted_at/deactivation_reason/deleted_key y el centinela
--    '1970-01-01': dar de baja una practica y volver a agregarla no choca, porque la fila dada de
--    baja lleva su instante de baja en deleted_key y la nueva el centinela.
--
-- 5. consultorio_id es redundante con oferta_id a proposito, como en V28: los indices por sede no
--    necesitan join.
--
-- 6. La FK a practica(id) es integridad referencial, no acceso: offering lee el catalogo solo por
--    resource.spi.CatalogoDirectory, que acota "propias del tenant mas globales". La practica puede
--    ser global (practica.organization_id NULL); por eso no hay FK compuesta con el tenant.
--
-- 7. Nombres de constraint con la tabla adelante: en MySQL los de FK y CHECK son unicos POR
--    ESQUEMA (V64 lo pago). Migracion V75: la reserva original V66 quedo debajo de migraciones ya
--    mergeadas y Flyway corre sin outOfOrder; V66 queda vacia.

CREATE TABLE oferta_practica
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Primera columna de todo unique e indice de esta tabla',
    consultorio_id      BIGINT       NOT NULL COMMENT 'Sede de la oferta. Redundante con oferta_id a proposito (punto 5 de la cabecera)',
    oferta_id           BIGINT       NOT NULL COMMENT 'Oferta que puede prestar esta practica',
    practica_id         BIGINT       NOT NULL COMMENT 'Practica del catalogo M06, global o propia del tenant (punto 6 de la cabecera)',
    principal           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'Practica por defecto de la oferta: la que se devenga o consume si la sesion cierra sin tratamientos (DP-11)',
    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica',
    deleted_at          DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras este activa',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio al dar de baja',
    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de vigencia: el instante de la baja, o el centinela mientras la fila este vigente',
    principal_key       TINYINT AS (CASE WHEN principal = 1 AND deleted_at IS NULL THEN 1 END) STORED
        COMMENT '1 solo en la principal vigente; NULL en el resto. Hace que el unique admita una sola principal por oferta (punto 3 de la cabecera)',
    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_oferta_practica PRIMARY KEY (id),
    CONSTRAINT uk_oferta_practica_vigente
        UNIQUE (organization_id, oferta_id, practica_id, deleted_key),
    CONSTRAINT uk_oferta_practica_principal
        UNIQUE (organization_id, oferta_id, principal_key),
    CONSTRAINT fk_oferta_practica_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_oferta_practica_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_oferta_practica_oferta FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),
    CONSTRAINT fk_oferta_practica_practica FOREIGN KEY (practica_id) REFERENCES practica (id),
    CONSTRAINT ck_oferta_practica_baja_coherente CHECK (
        (active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
        OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),
    CONSTRAINT ck_oferta_practica_principal_vigente CHECK (principal = 0 OR active = 1),
    INDEX ix_oferta_practica_oferta (organization_id, oferta_id, active),
    INDEX ix_oferta_practica_practica (organization_id, practica_id, active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT 'Que practicas puede prestar una oferta y cual es su principal (DP-11, RF-M06-008). Propietario: modulo offering';
