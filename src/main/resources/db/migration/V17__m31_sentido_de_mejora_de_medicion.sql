-- M31, RF-M31-003: sentido de mejora de una medida.
-- Opcional. NULL significa "sin definir": la serie se muestra y no se califica (RN-M31-009).
ALTER TABLE medicion_definicion ADD COLUMN mejora_cuando varchar(8) COLLATE akine_ci;

ALTER TABLE medicion_definicion ADD CONSTRAINT ck_medicion_definicion_mejora_cuando
    CHECK ((mejora_cuando is null) or (mejora_cuando in ('SUBE','BAJA')));

ALTER TABLE medicion_definicion ADD CONSTRAINT ck_medicion_definicion_mejora_solo_numerico
    CHECK ((mejora_cuando is null) or (tipo in ('NUMERICO','ESCALA')));

COMMENT ON COLUMN medicion_definicion.mejora_cuando IS 'SUBE o BAJA: hacia donde se mueve el valor cuando el paciente mejora. NULL si no se declaro. Solo en los tipos numericos. No se copia a sesion_medicion: cambiarlo reinterpreta la serie entera';

-- M31: la evolucion del caso lee los turnos del caso, cancelados incluidos (TurnosDelCasoDirectory).
-- turno no tenia indice por caso_id: sin el, cada lectura recorre los turnos de la organizacion.
CREATE INDEX ix_turno_caso ON turno (organization_id, caso_id, inicio);
