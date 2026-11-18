-- DP-47: datos que declara el paciente en la Historia Clinica.
--
-- Lo declarado vive en las mismas tablas, marcado por su origen. Las filas existentes las cargo
-- un profesional: quedan PROFESIONAL y sin revision. La revision la escribe un UPDATE
-- condicionado (WHERE origen = 'DECLARADO' AND revisado_en IS NULL) que avanza la version.
-- Los nombres de constraint llevan la tabla adelante: en PostgreSQL son unicos por esquema.

ALTER TABLE historia_clinica_antecedente
    ADD COLUMN origen varchar(16) NOT NULL DEFAULT 'PROFESIONAL',
    ADD COLUMN revisado_por bigint,
    ADD COLUMN revisado_en timestamptz;

ALTER TABLE historia_clinica_antecedente
    ADD CONSTRAINT ck_hc_antecedente_origen
        CHECK (origen IN ('PROFESIONAL', 'DECLARADO')),
    ADD CONSTRAINT ck_hc_antecedente_revision_solo_declarado
        CHECK (origen = 'DECLARADO' OR (revisado_por IS NULL AND revisado_en IS NULL)),
    ADD CONSTRAINT ck_hc_antecedente_revision_coherente
        CHECK ((revisado_por IS NULL) = (revisado_en IS NULL));

CREATE INDEX ix_hc_antecedente_declarado_sin_revisar
    ON historia_clinica_antecedente (organization_id, historia_clinica_id)
    WHERE origen = 'DECLARADO' AND revisado_en IS NULL AND active;

ALTER TABLE adjunto_clinico
    ADD COLUMN origen varchar(16) NOT NULL DEFAULT 'PROFESIONAL',
    ADD COLUMN revisado_por bigint,
    ADD COLUMN revisado_en timestamptz;

ALTER TABLE adjunto_clinico
    ADD CONSTRAINT ck_adjunto_clinico_origen
        CHECK (origen IN ('PROFESIONAL', 'DECLARADO')),
    ADD CONSTRAINT ck_adjunto_clinico_revision_solo_declarado
        CHECK (origen = 'DECLARADO' OR (revisado_por IS NULL AND revisado_en IS NULL)),
    ADD CONSTRAINT ck_adjunto_clinico_revision_coherente
        CHECK ((revisado_por IS NULL) = (revisado_en IS NULL));

CREATE INDEX ix_adjunto_clinico_declarado_sin_revisar
    ON adjunto_clinico (organization_id, historia_clinica_id)
    WHERE origen = 'DECLARADO' AND revisado_en IS NULL AND active;
