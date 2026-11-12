-- =====================================================================================
-- V139 — audit_event: turno de origen de un traslado como columna filtrable (historial de agenda,
-- docs/diseno/AKINE-agenda-historial.md, "Un traslado, una fila").
--
-- Un cambio de sede deja tres eventos: TURNO_CANCELADO y TURNO_TRASLADADO sobre el turno original
-- y TURNO_RESERVADO sobre el nuevo. El historial muestra uno solo, el TRASLADADO, y para eso tiene
-- que poder decir "esta reserva es la mitad de un traslado" y "este traslado llego a la agenda de tal
-- profesional en tal sede" sin leer el JSON fila por fila.
--
-- El vinculo ya estaba escrito en todas las filas, viejas y nuevas: la reserva del turno nuevo lleva
-- `turnoOrigenId` en details (DetallesDeAuditoriaDeTurno, la imagen de turno.turno_origen_id). Igual
-- que V134, columna VIRTUAL y no backfill: audit_event es append-only por trigger (V14), y una columna
-- generada alcanza las filas viejas sin escribir ninguna. Los emisores no cambian.
--
-- Texto y no numero, como las de V134: un CAST a entero de un valor raro haria fallar el INSERT de
-- auditoria en modo estricto. LEFT(..., 32) por la misma razon.
--
-- Propietario: platform. La lee scheduling por platform.spi.audit.AuditQuery#deAgenda.
-- =====================================================================================

ALTER TABLE audit_event
    ADD COLUMN agenda_turno_origen_ref VARCHAR(32)
        GENERATED ALWAYS AS (LEFT(
            JSON_UNQUOTE(JSON_EXTRACT(details, '$.turnoOrigenId')), 32)) VIRTUAL
        COMMENT 'Turno de origen de un turno trasladado, derivado de details. No se escribe.';

CREATE INDEX ix_audit_event_agenda_turno_origen
    ON audit_event (organization_id, agenda_turno_origen_ref);
