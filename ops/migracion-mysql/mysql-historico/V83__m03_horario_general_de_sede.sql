-- =====================================================================================
-- AKINE A-8 — Horario general de la sede (RF-M03-002, CA-M03-002, RN-M03-004).
--
-- Trazabilidad: RF-M03-002 ("crear consultorio, primer box, horario general e intervalo
-- inicial"), RF-M03-003 ("modificar datos, horarios y parametros sin alterar historicos"),
-- RN-M03-004 ("el horario general no reemplaza la disponibilidad individual de profesionales").
--
-- Propietario: modulo `resource`, el mismo que ya es dueno de la politica de calendario de la
-- sede (`consultorio_calendario`, V23). El horario general es parte de esa politica —cuando
-- abre la sede, igual que si cierra los feriados— y por eso se lee y se edita por
-- `/consultorios/{id}/calendario`. No es un modelo paralelo ni una columna de `consultorio`:
-- esa tabla es de `organization`, y escribirla desde aca rompe la propiedad de tablas.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE AHORA, SI V16 LO DIFIRIO
--
-- V16 dejo afuera el horario general para que la agenda no lo tomara como fuente de verdad
-- antes de que existiera la disponibilidad profesional (RN-M03-004). Esa disponibilidad existe
-- desde 02.04 (V23) y la agenda la consume desde F5. Este horario es INFORMATIVO: la agenda
-- sigue calculando slots con la disponibilidad de cada profesional y NO lee esta tabla. Que la
-- lea —por ejemplo para ofertas sin profesional, hoy `SIN_HORARIO`— es una decision aparte.
--
-- ---------------------------------------------------------------------------------------
-- FORMA: misma recurrencia que profesional_disponibilidad (V23)
--
-- dia_semana ISO-8601 (1 = lunes) + franja local [hora_desde, hora_hasta) con '24:00:00'
-- admitido y sin cruzar medianoche. Varias franjas por dia (cortado de mediodia). El
-- solapamiento entre franjas del mismo dia lo valida la aplicacion: MySQL 8.4 no tiene
-- exclusion constraints (ver la cabecera de V23).
--
-- ---------------------------------------------------------------------------------------
-- HISTORIA: editar reemplaza, y lo reemplazado no se borra (regla maestra 10)
--
-- Editar el horario da de baja logica todas las franjas vigentes (`active = 0`, `deleted_at`)
-- e inserta las nuevas en la misma transaccion. Sin unique: dos franjas iguales de dos
-- versiones distintas del horario son historia legitima.
-- =====================================================================================

CREATE TABLE consultorio_horario
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id BIGINT      NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo indice de esta tabla empieza por el',
    consultorio_id  BIGINT      NOT NULL COMMENT 'Sede cuyo horario general describe esta franja',

    dia_semana      TINYINT     NOT NULL COMMENT 'ISO-8601: lunes = 1 .. domingo = 7',
    hora_desde      TIME        NOT NULL COMMENT 'Hora LOCAL de la sede (consultorio.timezone). Nunca UTC',
    hora_hasta      TIME        NOT NULL COMMENT 'Hora LOCAL, EXCLUSIVA. Admite 24:00:00 y no cruza medianoche, igual que V23',

    active          TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '0 cuando una edicion posterior reemplazo el horario. La fila queda como historia',
    deleted_at      DATETIME(6) NULL COMMENT 'Instante UTC del reemplazo. NULL mientras la franja este vigente',

    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,

    CONSTRAINT pk_consultorio_horario PRIMARY KEY (id),

    CONSTRAINT fk_consultorio_horario_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_consultorio_horario_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT ck_consultorio_horario_dia_semana
        CHECK (dia_semana BETWEEN 1 AND 7),
    CONSTRAINT ck_consultorio_horario_franja
        CHECK (hora_hasta > hora_desde AND hora_hasta <= '24:00:00'),
    CONSTRAINT ck_consultorio_horario_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL) OR (active = 0 AND deleted_at IS NOT NULL)),

    -- La lectura siempre es "el horario vigente de esta sede", ordenado por dia y hora.
    INDEX ix_consultorio_horario_sede_vigente
        (organization_id, consultorio_id, active, dia_semana, hora_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Horario general de una sede (RF-M03-002). Propietario: modulo resource. Informativo: no sustituye la disponibilidad profesional (RN-M03-004)';
