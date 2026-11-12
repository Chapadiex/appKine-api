-- =====================================================================================
-- V134 — audit_event: profesional de agenda como columna filtrable (historial de agenda por
-- profesional, docs/diseno/AKINE-agenda-historial.md).
--
-- Los eventos de agenda (horario habitual, excepciones y turnos) guardan el profesional dentro de
-- `details`, que es JSON: `profesionalMembershipId` los de turno, `membershipId` los de horario y
-- excepciones, y `profesionalAnteriorId` la reprogramacion que cambia de profesional. Filtrar por
-- un campo del JSON es un scan del tenant entero.
--
-- POR QUE COLUMNAS GENERADAS Y NO COLUMNAS COMUNES CON BACKFILL
--
-- audit_event es append-only y lo sostiene un trigger (V14): un UPDATE de backfill exigiria
-- borrar el trigger, actualizar y volver a crearlo, es decir abrir la auditoria a escritura dentro
-- de una migracion. Una columna VIRTUAL no se escribe nunca: MySQL la calcula del JSON al leer, y
-- el indice secundario la materializa al insertar. Las filas viejas quedan alcanzadas sin tocar una
-- sola, y los emisores no cambian: nadie puede olvidarse de llenarla.
--
-- Guardan el id de la membership como TEXTO, tal como esta en el JSON (los detalles son un mapa de
-- texto a texto). LEFT(..., 32) evita que un valor raro de otro modulo con la misma clave haga
-- fallar un INSERT de auditoria por largo: la columna solo indexa, nunca decide nada.
--
-- Propietario: platform. La lee scheduling por platform.spi.audit.AuditQuery#deAgenda.
-- =====================================================================================

ALTER TABLE audit_event
    ADD COLUMN agenda_membership_ref VARCHAR(32)
        GENERATED ALWAYS AS (LEFT(COALESCE(
            JSON_UNQUOTE(JSON_EXTRACT(details, '$.profesionalMembershipId')),
            JSON_UNQUOTE(JSON_EXTRACT(details, '$.membershipId'))), 32)) VIRTUAL
        COMMENT 'Membership del profesional del evento de agenda, derivada de details. No se escribe.',
    ADD COLUMN agenda_membership_anterior_ref VARCHAR(32)
        GENERATED ALWAYS AS (LEFT(
            JSON_UNQUOTE(JSON_EXTRACT(details, '$.profesionalAnteriorId')), 32)) VIRTUAL
        COMMENT 'Membership del profesional ANTES de una reprogramacion, derivada de details. No se escribe.';

CREATE INDEX ix_audit_event_agenda
    ON audit_event (organization_id, agenda_membership_ref, occurred_at);

CREATE INDEX ix_audit_event_agenda_anterior
    ON audit_event (organization_id, agenda_membership_anterior_ref, occurred_at);
