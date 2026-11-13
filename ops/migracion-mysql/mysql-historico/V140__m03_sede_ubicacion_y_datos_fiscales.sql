-- =====================================================================================
-- V140 — Sede: direccion estructurada, ubicacion geografica, flags de agenda y datos fiscales
-- propios o heredados de la organizacion (M03, RF-M03-003).
--
-- Propietario de las dos tablas: modulo organization. Ningun otro modulo las toca; scheduling y
-- notification leen la direccion publica por organization.spi.ConsultorioSnapshot.
--
-- ---------------------------------------------------------------------------------------
-- QUE CAMBIA EN `consultorio`
--
--   * La direccion FISICA pasa a ser estructurada (pais, provincia, ciudad, calle, numero, codigo
--     postal, piso, referencias) para poder geocodificarla y mostrarla formateada. La columna
--     `address_line` de V16 NO se borra ni se renombra: desde ahora es la direccion FISCAL de la
--     sede, y se usa solo cuando `datos_fiscales_propios = 1`.
--   * `latitud`/`longitud` en DECIMAL(9,6): seis decimales son ~11 cm, mas que suficiente para
--     "como llegar", y DECIMAL y no DOUBLE por la regla de la casa (AGENT.md §5). Van las dos o
--     ninguna; `ubicacion_origen` dice si las puso un geocodificador o una persona a mano.
--   * `agenda_habilitada`: una sede recien creada puede existir sin agenda hasta que se la termine
--     de configurar. En 0 la agenda no reserva, no crea series ni recibe traslados (409
--     consultorio-sin-agenda). Default 1 para que todas las sedes existentes sigan igual.
--   * `direccion_publica`: si la direccion viaja en los avisos de turno. Default 1.
--   * `datos_fiscales_propios`: 0 = la sede factura con los datos de la organizacion; 1 = con los
--     suyos (legal_name, tax_id, address_line). El backfill pone 1 en toda sede que ya tenia
--     alguno cargado, para no cambiarle los datos efectivos a nadie.
--
-- CHECK y FK se nombran con la tabla adelante: en MySQL son unicos POR ESQUEMA, no por tabla.
--
-- ---------------------------------------------------------------------------------------
-- QUE CAMBIA EN `organization`
--
--   Nace el dato fiscal del tenant: razon social, identificacion fiscal y domicilio fiscal. Hasta
--   hoy vivian solo en la sede (D-5 de V16), y una organizacion con varias sedes los repetia.
-- =====================================================================================

ALTER TABLE consultorio
    ADD COLUMN alias VARCHAR(80) NULL
        COMMENT 'Nombre corto para la agenda y los listados. Opcional',
    ADD COLUMN pais CHAR(2) NULL
        COMMENT 'Pais de la direccion fisica, ISO 3166-1 alpha-2 en mayusculas',
    ADD COLUMN provincia VARCHAR(80) NULL
        COMMENT 'Provincia o estado de la direccion fisica',
    ADD COLUMN ciudad VARCHAR(120) NULL
        COMMENT 'Ciudad o localidad de la direccion fisica',
    ADD COLUMN calle VARCHAR(160) NULL
        COMMENT 'Calle de la direccion fisica',
    ADD COLUMN numero VARCHAR(20) NULL
        COMMENT 'Altura de la direccion fisica. Texto: admite "S/N" o "1234 bis"',
    ADD COLUMN codigo_postal VARCHAR(16) NULL
        COMMENT 'Codigo postal de la direccion fisica',
    ADD COLUMN piso VARCHAR(60) NULL
        COMMENT 'Piso, oficina o departamento',
    ADD COLUMN referencias VARCHAR(255) NULL
        COMMENT 'Indicaciones para llegar ("frente a la plaza"). Texto libre no clinico',
    ADD COLUMN latitud DECIMAL(9,6) NULL
        COMMENT 'Latitud WGS84. Va junto con longitud: las dos o ninguna',
    ADD COLUMN longitud DECIMAL(9,6) NULL
        COMMENT 'Longitud WGS84. Va junto con latitud: las dos o ninguna',
    ADD COLUMN ubicacion_origen VARCHAR(16) NULL
        COMMENT 'GEOCODIFICADA (la puso el geocodificador) o MANUAL (la fijo una persona). NULL sin ubicacion',
    ADD COLUMN agenda_habilitada TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '0: la sede todavia no admite turnos nuevos, series ni traslados hacia ella',
    ADD COLUMN direccion_publica TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '1: la direccion viaja en los avisos de turno al paciente',
    ADD COLUMN datos_fiscales_propios TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '0: factura con los datos fiscales de la organizacion; 1: con legal_name, tax_id y address_line propios',
    ADD CONSTRAINT ck_consultorio_latitud
        CHECK (latitud IS NULL OR (latitud >= -90 AND latitud <= 90)),
    ADD CONSTRAINT ck_consultorio_longitud
        CHECK (longitud IS NULL OR (longitud >= -180 AND longitud <= 180)),
    ADD CONSTRAINT ck_consultorio_ubicacion_completa
        CHECK ((latitud IS NULL AND longitud IS NULL) OR (latitud IS NOT NULL AND longitud IS NOT NULL)),
    ADD CONSTRAINT ck_consultorio_ubicacion_origen
        CHECK (ubicacion_origen IS NULL OR ubicacion_origen IN ('GEOCODIFICADA', 'MANUAL'));

ALTER TABLE consultorio
    MODIFY COLUMN address_line VARCHAR(240) NULL
        COMMENT 'Direccion FISCAL de la sede (desde V140). Se usa solo con datos_fiscales_propios = 1; la direccion fisica es la estructurada';

-- Backfill: toda sede que ya tenia algun dato fiscal cargado sigue facturando con los suyos.
UPDATE consultorio
   SET datos_fiscales_propios = 1
 WHERE legal_name IS NOT NULL
    OR tax_id IS NOT NULL
    OR address_line IS NOT NULL;

ALTER TABLE organization
    ADD COLUMN legal_name VARCHAR(200) NULL
        COMMENT 'Razon social del tenant. La heredan las sedes sin datos fiscales propios',
    ADD COLUMN tax_id VARCHAR(32) NULL
        COMMENT 'Identificacion fiscal del tenant (CUIT en Argentina). Idem',
    ADD COLUMN fiscal_address_line VARCHAR(240) NULL
        COMMENT 'Domicilio fiscal del tenant. Idem';
