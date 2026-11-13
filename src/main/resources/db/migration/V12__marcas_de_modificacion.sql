-- =====================================================================================
-- AKINE — updated_at automatico (equivalente de ON UPDATE CURRENT_TIMESTAMP)
--
-- Baseline PostgreSQL consolidado (11/11/2026). Reemplaza las 145 migraciones de MySQL, que quedan
-- como archivo historico en ops/migracion-mysql/mysql-historico. Generado por
-- ops/migracion-mysql/generar-baseline.mjs desde el esquema final de MySQL y revisado a mano.
--
-- Convenciones que vienen de MySQL y se conservan:
--   * Textos con COLLATE akine_ci: comparacion sin mayusculas ni tildes, como utf8mb4_0900_ai_ci.
--   * Instantes en timestamptz (UTC); fechas en date; horas de agenda en time.
--   * Las claves *_key generadas sostienen los UNIQUE de la baja logica (centinela 1970-01-01).
--   * Las claves foraneas se crean todas juntas en V11, despues de todas las tablas.
-- =====================================================================================

CREATE TRIGGER trg_recepcion_updated_at_al_modificar BEFORE UPDATE ON recepcion
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_turno_updated_at_al_modificar BEFORE UPDATE ON turno
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_turno_serie_updated_at_al_modificar BEFORE UPDATE ON turno_serie
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_caso_numerador_updated_at_al_modificar BEFORE UPDATE ON caso_numerador
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_caso_sesion_numerador_updated_at_al_modificar BEFORE UPDATE ON caso_sesion_numerador
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_plan_numerador_updated_at_al_modificar BEFORE UPDATE ON plan_numerador
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_politica_de_enmienda_updated_at_al_modificar BEFORE UPDATE ON politica_de_enmienda
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_sesion_updated_at_al_modificar BEFORE UPDATE ON sesion
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_sesion_numerador_updated_at_al_modificar BEFORE UPDATE ON sesion_numerador
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_sesion_version_updated_at_al_modificar BEFORE UPDATE ON sesion_version
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_cobro_updated_at_al_modificar BEFORE UPDATE ON cobro
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_comprobante_numerador_updated_at_al_modificar BEFORE UPDATE ON comprobante_numerador
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_egreso_updated_at_al_modificar BEFORE UPDATE ON egreso
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_jornada_caja_updated_at_al_modificar BEFORE UPDATE ON jornada_caja
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_obligacion_updated_at_al_modificar BEFORE UPDATE ON obligacion
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_pago_egreso_updated_at_al_modificar BEFORE UPDATE ON pago_egreso
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_presentacion_updated_at_al_modificar BEFORE UPDATE ON presentacion
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_presentacion_item_updated_at_al_modificar BEFORE UPDATE ON presentacion_item
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_presentacion_numerador_updated_at_al_modificar BEFORE UPDATE ON presentacion_numerador
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_asistencia_actividad_updated_at_al_modificar BEFORE UPDATE ON asistencia_actividad
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_clase_programada_updated_at_al_modificar BEFORE UPDATE ON clase_programada
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_inscripcion_clase_updated_at_al_modificar BEFORE UPDATE ON inscripcion_clase
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_pase_servicio_updated_at_al_modificar BEFORE UPDATE ON pase_servicio
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

CREATE TRIGGER trg_producto_servicio_updated_at_al_modificar BEFORE UPDATE ON producto_servicio
    FOR EACH ROW EXECUTE FUNCTION akine_marcar_updated_at();

-- audit_event es append-only en la base (RN-M24-001): los dos triggers que tenia MySQL.
CREATE TRIGGER trg_audit_event_no_update BEFORE UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION akine_auditoria_append_only();
CREATE TRIGGER trg_audit_event_no_delete BEFORE DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION akine_auditoria_append_only();
