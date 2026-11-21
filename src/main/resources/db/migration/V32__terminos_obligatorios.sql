-- 19/11/2026 - Terminos obligatorios y reaceptacion (docs/diseno/AKINE-terminos-obligatorios.md).

-- El pedido de prueba de la landing tambien acepta los terminos: queda la version y el instante.
ALTER TABLE solicitud_de_prueba ADD COLUMN version_terminos varchar(40);
ALTER TABLE solicitud_de_prueba ADD COLUMN terminos_aceptados_en timestamptz(6);

COMMENT ON COLUMN solicitud_de_prueba.version_terminos IS 'Version de los terminos aceptada al pedir la prueba. NULL en los pedidos anteriores al 19/11/2026';
COMMENT ON COLUMN solicitud_de_prueba.terminos_aceptados_en IS 'Instante UTC en que se aceptaron los terminos al pedir la prueba. NULL en los pedidos anteriores al 19/11/2026';

-- Una cuenta que ya existia acepta la version nueva despues de iniciar sesion.
ALTER TABLE aceptacion_terminos DROP CONSTRAINT ck_aceptacion_terminos_origen;
ALTER TABLE aceptacion_terminos ADD CONSTRAINT ck_aceptacion_terminos_origen
    CHECK (origen IN ('REGISTRO', 'INVITACION', 'REACEPTACION'));

COMMENT ON COLUMN aceptacion_terminos.origen IS 'REGISTRO | INVITACION: la cuenta acepto al nacer. REACEPTACION: acepto una version nueva despues de iniciar sesion';
