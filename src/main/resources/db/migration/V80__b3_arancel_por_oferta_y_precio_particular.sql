-- =====================================================================================
-- AKINE B-3 — Arancel por oferta dentro del convenio y precio particular por vigencia.
--
-- Trazabilidad: RF-M16-008 (asociar una Oferta de Servicio a un convenio), RF-M16-009
-- (arancel particular por Oferta, por vigencia), RF-M08-006 (resolver la cobertura aplicable
-- por Oferta, que lee las dos cosas); RN-M16-002 (vigencias superpuestas se controlan),
-- RN-M16-003 (cambiar un arancel o un precio no recalcula lo historico), RN-M16-006 (los
-- convenios aplican a Ofertas concretas y no solo a practicas aisladas), RN-M16-007 (una
-- Oferta mantiene precio particular y arancel financiador a la vez), RN-M16-008.
--
-- Diseno y design challenge: docs/diseno/AKINE-B-3-cobertura-por-oferta.md.
--
-- Dos cambios en una migracion y DOS PROPIETARIOS, uno por tabla, que no se cruzan:
--
--   convenio_arancel.oferta_id      modulo `contracting` (dueno de convenio_arancel desde V43)
--   oferta_precio_particular        modulo `offering`    (dueno de la oferta desde V24)
--
-- Van juntos porque los dos son la mitad de RF-M08-006 y nacieron en el mismo paquete; ninguno
-- de los dos modulos lee la tabla del otro. Ninguno es expandir-migrar-contraer: la columna es
-- NULLABLE y nace en NULL para todas las filas existentes, que conservan exactamente su
-- significado anterior (arancel general de la practica), y la tabla nace vacia.
--
-- ---------------------------------------------------------------------------------------
-- convenio_arancel.oferta_id — EL ARANCEL DE UNA PRACTICA CUANDO SE PRESTA EN UNA OFERTA
--
-- NULL  = arancel GENERAL de la practica en el convenio: lo que habia hasta V79, intacto.
-- valor = arancel ESPECIFICO de esa practica cuando se presta dentro de esa oferta.
--
-- La practica sigue siendo NOT NULL tambien en el especifico: al financiador se le factura por
-- practica (codigo de nomenclador), y la obligacion del financiador (V77) congela practica y
-- arancel. Un arancel "de la oferta" sin practica no se podria presentar.
--
-- Resolucion (ResolutorDeArancel): con oferta, el especifico vigente de esa oferta manda; si no
-- hay, el general. Sin oferta, solo el general. NO es un desempate entre candidatas iguales:
-- son dos niveles de especificidad, y la respuesta a "por que salio este" sigue siendo una sola
-- frase verificable.
--
-- El no-solapamiento (RN-M16-002) pasa a ser por (convenio, practica, oferta_id), con NULL como
-- un grupo propio: el general y el especifico de una oferta conviven en el mismo periodo, que es
-- justamente el punto. Lo hace cumplir el lock de convenio_lock como desde V43; ningun indice lo
-- expresa, y tampoco aca.
--
-- Sin FK compuesta con la sede de la oferta, mismo criterio que V43: la oferta se valida en la
-- aplicacion por offering.spi con organizacion Y sede en el WHERE.
-- ---------------------------------------------------------------------------------------

ALTER TABLE convenio_arancel
    ADD COLUMN oferta_id BIGINT NULL
        COMMENT 'NULL = arancel general de la practica. Con valor: arancel de la practica cuando se presta dentro de esa oferta (RF-M16-008). Manda sobre el general al resolver con oferta. La oferta es de la misma sede: lo valida contracting por offering.spi'
        AFTER practica_id,
    ADD CONSTRAINT fk_convenio_arancel_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),
    ADD INDEX ix_convenio_arancel_oferta (organization_id, convenio_id, oferta_id, practica_id, active);

-- ---------------------------------------------------------------------------------------
-- oferta_precio_particular — PRECIO PARTICULAR DE UNA OFERTA POR VIGENCIA (RF-M16-009)
--
-- `oferta_servicio_consultorio.precio_base` sigue siendo el precio de lista SIN vigencia. Esta
-- tabla agrega precios ESPECIFICOS con vigencia: el de Pilates desde el 01/03. Resolver el
-- precio particular de un dia es: el especifico activo que cubre ese dia, y si no hay, el
-- precio_base. Una oferta sin filas aca se comporta exactamente como antes de V80.
--
-- Dos precios activos de la misma oferta no se pueden solapar. Mismo problema que V43 —ningun
-- UNIQUE detecta la interseccion de dos periodos— y la misma clase de solucion, con un lock que
-- ya existe: el SELECT ... FOR UPDATE sobre la fila de la oferta (OfertaRepositoryPort
-- #bloquearParaConfigurar, A-9), tomado ANTES de leer el conjunto y en READ_COMMITTED.
--
-- CAMBIAR EL PRECIO NO TOCA LO YA DEVENGADO (CA-M16-009-06): la obligacion copia el importe a sus
-- columnas al devengar (07.01). Por eso el importe no se edita: subir un precio es cerrar la
-- vigencia del actual y crear otro, y corregir una carga es darla de baja y cargarla de nuevo.
-- Baja logica, nunca borrado (regla maestra 10).
-- ---------------------------------------------------------------------------------------

CREATE TABLE oferta_precio_particular
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT         NOT NULL COMMENT 'Tenant propietario (AGENT.md §5). Todo indice de esta tabla empieza por el',
    consultorio_id      BIGINT         NOT NULL COMMENT 'Sede de la oferta. Redundante con la oferta y necesario para que toda lectura lleve sede en el WHERE',
    oferta_id           BIGINT         NOT NULL COMMENT 'Oferta cuyo precio particular fija esta fila. No se muda',

    importe             DECIMAL(12, 2) NOT NULL COMMENT 'Precio particular en esa vigencia. DECIMAL, nunca float (§37). No se edita: ver la cabecera',
    moneda              CHAR(3)        NOT NULL COMMENT 'ISO 4217',

    vigencia_desde      DATE           NOT NULL COMMENT 'Primer dia en que rige',
    vigencia_hasta      DATE           NULL COMMENT 'ULTIMO dia en que rige, INCLUSIVO. NULL = sin fin previsto',

    active              TINYINT(1)     NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica: deja de resolver. Lo ya devengado guardo su importe y no se toca',
    deleted_at          DATETIME(6)    NULL,
    deactivation_reason VARCHAR(280)   NULL COMMENT 'Motivo declarado de la baja. Obligatorio',

    version             BIGINT         NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista',
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,

    CONSTRAINT pk_oferta_precio_particular PRIMARY KEY (id),

    CONSTRAINT fk_oferta_precio_particular_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_oferta_precio_particular_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_oferta_precio_particular_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT ck_oferta_precio_particular_vigencia
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    CONSTRAINT ck_oferta_precio_particular_importe
        CHECK (importe >= 0),

    CONSTRAINT ck_oferta_precio_particular_baja
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    INDEX ix_oferta_precio_particular_resolucion
        (organization_id, oferta_id, active, vigencia_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Precio particular de una oferta por vigencia (RF-M16-009). Manda sobre precio_base el dia que cubre. Dos precios activos de la misma oferta no se solapan, y eso lo hace cumplir el lock de la fila de la oferta, NUNCA un indice. Propietario: modulo offering';
