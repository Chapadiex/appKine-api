-- =====================================================================================
-- V129 — agenda_profesional: fila-lock por persona y organizacion (agenda de profesionales,
-- checklist §9.2 y §9.3).
--
-- El lock de reservas era por sede (agenda_sede) y el de horarios tambien
-- (consultorio_calendario). Con memberships por sede, la misma persona tiene un id de membership
-- distinto en cada sede, y dos escrituras en dos sedes no se serializaban entre si: se podia dar
-- un turno a la misma hora en las dos, o cargar el mismo horario habitual en las dos.
--
-- La fila es de la PERSONA (la cuenta) dentro de la organizacion, no de la membership: es lo que
-- no puede estar en dos lugares a la vez. Se crea en su propia transaccion con
-- INSERT ... ON DUPLICATE KEY UPDATE (FilaLockPrevia) y se toma FOR UPDATE SIEMPRE despues del
-- lock de la sede, nunca antes: ese orden fijo es lo que evita el deadlock entre sedes.
--
-- Propietario: resource (M05). scheduling la toma por resource.spi.BloqueoDeAgendaDeProfesional.
-- No guarda estado.
-- =====================================================================================

CREATE TABLE agenda_profesional
(
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT       NOT NULL,
    account_id      BIGINT       NOT NULL,

    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_agenda_profesional
        UNIQUE (organization_id, account_id),

    CONSTRAINT fk_agenda_profesional_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Fila por persona y organizacion que sirve unicamente para serializar con FOR UPDATE las escrituras de agenda de esa persona en cualquier sede. No guarda estado.';
