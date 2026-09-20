-- =====================================================================================
-- AKINE-07.04 — Presentaciones, facturacion y cuenta corriente de financiadores (M21).
--
-- RF-M21-001..008; RN-M21-001..004.
--
-- ---------------------------------------------------------------------------------------
-- LA CUARTA COSA
--
-- El repositorio ya separa tres hechos que el UML de 2019 confundia: la Obligacion dice
-- que SE DEBE (V36), el Cobro dice que el paciente PAGO (V37), y la Caja dice que entro
-- plata a un cajon concreto (V54). Esta migracion agrega la cuarta:
--
--   La PRESENTACION dice que se le RECLAMO a un financiador.
--
-- RN-M21-001 con todas las letras: prestado, presentado, facturado y cobrado son cuatro
-- estados distintos. Confirmar una presentacion NO mueve un peso, NO toca el saldo de
-- ninguna obligacion y NO genera un movimiento de caja. Que una obra social reciba un lote
-- de 200 sesiones no significa que haya pagado nada.
--
-- Y la cuenta corriente de un financiador TAMPOCO es la caja. La caja es un cajon de una
-- sede, con jornada, arqueo y fecha de negocio: se cuenta. La cuenta corriente de un
-- financiador es una relacion comercial que vive en meses y que nadie arquea. Se tocan en
-- UN solo punto —cuando el financiador paga, entra plata de verdad (RN-M21-002)— y ese
-- punto es `financiador_pago`, que asienta su movimiento en la misma transaccion.
--
-- ---------------------------------------------------------------------------------------
-- LAS SEIS COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. EL CIMIENTO QUE FALTA, DICHO PRIMERO. Hoy NO EXISTE ninguna obligacion con
--    `responsable = 'FINANCIADOR'`. `ObligacionDevengador` devenga una sola, a nombre del
--    paciente, por el precio_base de la Oferta: es lo que DP-10 recorto y lo que el
--    comentario de V36 dejo escrito al reservar `snapshot_convenio_id` en NULL —"es donde
--    03.05 se enchufa"—. 03.05 se implemento como el modulo `contracting` y nunca volvio a
--    tocar el devengado. Consecuencia medible: la bandeja de elegibles devuelve lista
--    vacia hasta que alguien recablee el devengado contra convenios y aranceles.
--    ESO ES UNA ETAPA PROPIA y no se hace aca: necesita la cobertura vigente del paciente
--    —que `person` no expone por spi—, la practicaId de la oferta —que `SesionCerrada` no
--    lleva— y copiar el ArancelCongelado entero a `obligacion`.
--
-- 2. `obligacion.financiador_id` ES LA UNICA COLUMNA QUE ESTA ETAPA AGREGA ALLI, y no es
--    opcional: RF-M21-001 pide listar obligaciones de financiador POR PERIODO, y sin el
--    financiador en la fila no hay por donde agrupar. Se descarto deducirlo navegando
--    `snapshot_convenio_id -> convenio -> financiador`: obligaria a billing a recorrer el
--    agregado de contracting, la columna esta en NULL igual, y una lectura viva sobre un
--    convenio dado de baja dejaria deudas historicas sin financiador — que es exactamente
--    lo que ArancelCongelado existe para impedir.
--
-- 3. RN-M21-003 LA HACE CUMPLIR LA BASE, NO UN IF. "Una prestacion no debe duplicarse en
--    presentaciones incompatibles". Una obligacion puede aparecer en VARIAS presentaciones
--    a lo largo del tiempo —se presenta, la debitan, se corrige y se vuelve a presentar—
--    pero EN UNA SOLA A LA VEZ. Lo garantiza una columna GENERADA sobre el estado del item
--    mas un unique, el mismo mecanismo de `jornada_caja.abierta_marca` (V54) y
--    `deleted_key` (V30): INCLUIDO y ACEPTADO ocupan, DEBITADO y ANULADO liberan, y varios
--    NULL no colisionan en MySQL. Al ser generada NO PUEDE DESINCRONIZARSE del estado, y
--    un camino futuro que debite o anule libera la obligacion sin acordarse de hacerlo.
--
-- 4. EL SALDO SE MUEVE CON UNA CONDICION, NO CON UN LOCK.
--      UPDATE ... SET saldo = saldo - :importe WHERE ... AND saldo >= :importe
--    No hay ventana entre leer y escribir porque NO SE LEE. Es lo que 07.02 hizo para
--    imputar, 07.03 para el arqueo y 04.05 para consumir. El caso que rompe el diseño es
--    el aviso de debito y la transferencia cargados a la vez sobre el mismo lote: sin la
--    condicion el saldo queda en negativo y la presentacion concilia sola. Con ella, el
--    segundo afecta cero filas y recibe un 409 que lleva el saldo actual.
--    Los dos CHECK de abajo son el respaldo: si un camino futuro se olvida del WHERE, la
--    base lo frena en vez de dejar pasar el estado imposible.
--
-- 5. DECIMAL, NUNCA FLOAT. Igual que V36, V37 y V54: `DECIMAL(12,2)` en la base y
--    `BigDecimal` en Java, y la API no expone un solo numero de punto flotante.
--
-- 6. EL NUMERO DE LOTE SE ASIGNA CON UPDATE ... ultimo_numero + 1, NUNCA CON MAX + 1, y la
--    fila del numerador se crea en una transaccion aparte con INSERT ... ON DUPLICATE KEY.
--    Las dos reglas ya se pagaron en este repositorio: el MAX+1 deja una ventana entre leer
--    y escribir, y la creacion perezosa DENTRO de la transaccion que la bloquea produce
--    deadlock —y el try/catch no salva, porque atrapar una excepcion de persistencia no
--    des-marca la transaccion y Spring lanza UnexpectedRollbackException al commitear—.
-- =====================================================================================


-- =====================================================================================
-- 1. Los dos ALTER sobre tablas del propio modulo.
-- =====================================================================================

-- Punto 2. NULL para toda obligacion de paciente, que hoy son todas.
ALTER TABLE obligacion
    ADD COLUMN financiador_id BIGINT NULL
        COMMENT 'Quien es el financiador cuando responsable = FINANCIADOR. Ver el punto 1: hoy no lo escribe nadie.'
        AFTER responsable,

    ADD CONSTRAINT fk_obligacion_financiador
        FOREIGN KEY (financiador_id) REFERENCES financiador (id),

    -- Las dos direcciones. Sin la segunda, una obligacion de paciente podria quedar con un
    -- financiador colgado y aparecer en una bandeja de elegibles que no le corresponde.
    ADD CONSTRAINT ck_obligacion_financiador_presente
        CHECK (responsable <> 'FINANCIADOR' OR financiador_id IS NOT NULL),

    ADD CONSTRAINT ck_obligacion_financiador_solo_de_financiador
        CHECK (financiador_id IS NULL OR responsable = 'FINANCIADOR');

-- Literalmente la consulta de RF-M21-001: las pendientes de un financiador en una sede,
-- por fecha de devengado. El orden de las columnas es el de la selectividad de esa query.
CREATE INDEX ix_obligacion_financiador
    ON obligacion (organization_id, consultorio_id, financiador_id, estado, devengada_en);


-- El movimiento que genera un pago de financiador necesita su propio origen. No se reusa
-- COBRO porque el unique (organization_id, tipo, tipo_origen, referencia_origen, medio) de
-- V54 colisionaria entre un cobro y un pago que casualmente compartan id, y porque un
-- ledger que no distingue quien pago no sirve para explicar de donde salio la plata.
--
-- Se hace desde aca y NO editando V54: una migracion no se edita despues de aplicada, y
-- dos arboles con la misma version y checksums distintos es como Flyway deja de arrancar.
ALTER TABLE movimiento_caja
    DROP CHECK ck_movimiento_caja_tipo_origen;

ALTER TABLE movimiento_caja
    ADD CONSTRAINT ck_movimiento_caja_tipo_origen
        CHECK (tipo_origen IN ('COBRO', 'MANUAL', 'REVERSION', 'PAGO_FINANCIADOR'));


-- =====================================================================================
-- 2. La presentacion: el lote administrativo dirigido a un financiador.
-- =====================================================================================

CREATE TABLE presentacion
(
    id                       BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id          BIGINT         NOT NULL,
    consultorio_id           BIGINT         NOT NULL
        COMMENT 'El convenio es contextual a la sede (RN-M16-001): un lote que cruza sedes mezcla convenios distintos en un total que despues nadie puede desarmar.',
    financiador_id           BIGINT         NOT NULL,

    -- NULL mientras es borrador; se asigna al confirmar con el numerador del punto 6.
    -- Varios NULL no colisionan, asi que un mismo financiador puede tener cuantos
    -- borradores haga falta sin que ninguno ocupe un numero de la serie.
    numero                   INT            NULL
        COMMENT 'Correlativo del lote por (sede, financiador). Se asigna al confirmar, nunca antes.',

    -- El periodo es un filtro DECLARADO, no una restriccion rigida: dos presentaciones del
    -- mismo periodo al mismo financiador son legitimas (una complementaria, un reenvio de
    -- rechazados). Lo que no puede pasar dos veces lo resuelve el punto 3, no el periodo.
    periodo_desde            DATE           NOT NULL,
    periodo_hasta            DATE           NOT NULL,

    moneda                   CHAR(3)        NOT NULL,

    estado                   VARCHAR(16)    NOT NULL
        COMMENT 'BORRADOR, PRESENTADA, FACTURADA, CONCILIADA o ANULADA. ANULADA solo sale de BORRADOR.',

    -- Punto 4 y punto 5. `saldo` es derivado pero materializado, por el mismo motivo que el
    -- saldo de la obligacion en V36: calcularlo al leer obligaria a sumar los items DENTRO
    -- de la transaccion que los mueve, y a nadie a garantizar que dos escrituras
    -- concurrentes no lo dejen en negativo.
    total_presentado         DECIMAL(12, 2) NOT NULL DEFAULT 0.00
        COMMENT 'Suma de los items. SE CONGELA al confirmar.',
    total_debitado           DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'Lo que el financiador rechazo.',
    total_cobrado            DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'Lo que efectivamente pago.',
    saldo                    DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'presentado - debitado - cobrado. Nunca negativo.',

    -- RF-M21-005. El comprobante es DEL CENTRO y se emite fuera de AKINE: el sistema no lo
    -- genera ni lo numera, lo REGISTRA. Y lo protege de lo unico que puede protegerlo.
    factura_numero           VARCHAR(40)    NULL,
    factura_fecha            DATE           NULL,
    factura_registrada_en    DATETIME(6)    NULL,
    factura_registrada_por_cuenta_id BIGINT NULL,

    confirmada_en            DATETIME(6)    NULL,
    confirmada_por_cuenta_id BIGINT         NULL,

    conciliada_en            DATETIME(6)    NULL,
    conciliada_por_cuenta_id BIGINT         NULL,

    anulada_en               DATETIME(6)    NULL,
    anulada_por_cuenta_id    BIGINT         NULL,
    motivo_anulacion         VARCHAR(280)   NULL,

    creada_en                DATETIME(6)    NOT NULL,
    creada_por_cuenta_id     BIGINT         NOT NULL,

    created_at               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Baja LOGICA. Una presentacion NO SE BORRA nunca: el descarte de un borrador es
    -- estado ANULADA con motivo, y un lote enviado no se descarta de ninguna forma porque
    -- ya existe del otro lado del mostrador.
    deleted_at               DATETIME(6)    NULL,
    deleted_key              DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL,

    version                  BIGINT         NOT NULL DEFAULT 0,

    -- El correlativo del lote. Por financiador y no solo por sede: cada obra social recibe
    -- su propia serie, que es como se numeran en la practica y lo que hace que el numero
    -- que el administrativo dice por telefono signifique algo.
    CONSTRAINT uk_presentacion_numero
        UNIQUE (organization_id, consultorio_id, financiador_id, numero),

    -- El caso borde "factura externa duplicada": el mismo numero no puede quedar asociado a
    -- dos lotes del mismo financiador.
    CONSTRAINT uk_presentacion_factura
        UNIQUE (organization_id, financiador_id, factura_numero),

    CONSTRAINT fk_presentacion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_presentacion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_presentacion_financiador
        FOREIGN KEY (financiador_id) REFERENCES financiador (id),

    CONSTRAINT ck_presentacion_estado
        CHECK (estado IN ('BORRADOR', 'PRESENTADA', 'FACTURADA', 'CONCILIADA', 'ANULADA')),

    CONSTRAINT ck_presentacion_periodo
        CHECK (periodo_hasta >= periodo_desde),

    CONSTRAINT ck_presentacion_totales_positivos
        CHECK (total_presentado >= 0 AND total_debitado >= 0 AND total_cobrado >= 0),

    -- Punto 4: el respaldo de la condicion del WHERE.
    CONSTRAINT ck_presentacion_saldo
        CHECK (saldo >= 0),

    CONSTRAINT ck_presentacion_saldo_cuadra
        CHECK (saldo = total_presentado - total_debitado - total_cobrado),

    -- Un borrador no tiene numero, y algo que ya salio del centro lo tiene siempre. Sin
    -- esto, una fila a medio confirmar pasaria por enviada en unas consultas y por borrador
    -- en otras. Mismo criterio que ck_obligacion_anulacion_completa de V36.
    CONSTRAINT ck_presentacion_confirmacion_completa
        CHECK ((estado IN ('BORRADOR', 'ANULADA')
                AND numero IS NULL AND confirmada_en IS NULL AND confirmada_por_cuenta_id IS NULL)
            OR (estado IN ('PRESENTADA', 'FACTURADA', 'CONCILIADA')
                AND numero IS NOT NULL AND confirmada_en IS NOT NULL
                AND confirmada_por_cuenta_id IS NOT NULL)),

    -- La factura es opcional y por eso no entra en el CHECK de estado: muchos centros
    -- presentan y cobran sin emitirla hasta el cierre del trimestre. Lo que si se exige es
    -- que sus cuatro campos viajen juntos.
    CONSTRAINT ck_presentacion_factura_completa
        CHECK ((factura_numero IS NULL AND factura_fecha IS NULL
                AND factura_registrada_en IS NULL AND factura_registrada_por_cuenta_id IS NULL)
            OR (factura_numero IS NOT NULL AND factura_fecha IS NOT NULL
                AND factura_registrada_en IS NOT NULL
                AND factura_registrada_por_cuenta_id IS NOT NULL)),

    CONSTRAINT ck_presentacion_conciliacion_completa
        CHECK ((conciliada_en IS NULL AND conciliada_por_cuenta_id IS NULL)
            OR (conciliada_en IS NOT NULL AND conciliada_por_cuenta_id IS NOT NULL)),

    CONSTRAINT ck_presentacion_anulacion_completa
        CHECK ((anulada_en IS NULL AND anulada_por_cuenta_id IS NULL AND motivo_anulacion IS NULL)
            OR (anulada_en IS NOT NULL AND anulada_por_cuenta_id IS NOT NULL
                AND motivo_anulacion IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Lote reclamado a un financiador. NO es un cobro (RN-M21-001) ni es la caja.';


-- Las bandejas por estado, que es la pantalla principal de M21.
CREATE INDEX ix_presentacion_bandeja
    ON presentacion (organization_id, consultorio_id, estado, periodo_hasta);

-- La cuenta corriente de un financiador, que cruza sedes a proposito: la relacion
-- comercial es de la organizacion aunque cada lote se arme en una sede.
CREATE INDEX ix_presentacion_cuenta_corriente
    ON presentacion (organization_id, financiador_id, estado, periodo_hasta);


-- =====================================================================================
-- 3. El item: una obligacion dentro de un lote.
-- =====================================================================================

CREATE TABLE presentacion_item
(
    id                    BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Desnormalizado igual que `cobro_medio` (V37) y `movimiento_caja` (V54): ADR-0004 dice
    -- "sin excepcion" y es precisamente por eso — una consulta que se olvide del JOIN
    -- cruzaria tenants sin fallar.
    organization_id       BIGINT         NOT NULL,
    presentacion_id       BIGINT         NOT NULL,
    obligacion_id         BIGINT         NOT NULL,

    estado                VARCHAR(16)    NOT NULL
        COMMENT 'INCLUIDO, ACEPTADO, DEBITADO o ANULADO. Los dos primeros OCUPAN la obligacion; los dos ultimos la liberan.',

    importe_presentado    DECIMAL(12, 2) NOT NULL COMMENT 'Lo que se reclama por esta prestacion. Congelado al incluir.',
    importe_debitado      DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    motivo_debito         VARCHAR(280)   NULL
        COMMENT 'RF-M21-006: el debito se mantiene CON MOTIVO y sin borrar la prestacion (RN-M21-004).',
    debitado_en           DATETIME(6)    NULL,
    debitado_por_cuenta_id BIGINT        NULL,

    -- Se congelan al incluir. Un lote impreso en agosto tiene que poder reimprimirse
    -- identico en diciembre, y leer la obligacion viva no lo garantiza. Mismo criterio que
    -- ArancelCongelado y que el snapshot de precio de V36.
    snapshot_persona_id   BIGINT         NOT NULL,
    snapshot_sesion_id    BIGINT         NOT NULL
        COMMENT 'Referencia de trazabilidad CONGELADA, no un puntero vivo: lo que se presenta es la obligacion, no la sesion.',
    snapshot_fecha_prestacion DATE       NOT NULL,
    snapshot_concepto     VARCHAR(160)   NOT NULL,

    incluido_en           DATETIME(6)    NOT NULL,

    created_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    version               BIGINT         NOT NULL DEFAULT 0,

    -- Punto 3. GENERADA: no puede divergir del estado.
    ocupa_marca           TINYINT AS (IF(estado IN ('INCLUIDO', 'ACEPTADO'), 1, NULL)) STORED,

    -- RN-M21-003, hecha cumplir por la base.
    CONSTRAINT uk_presentacion_item_ocupa
        UNIQUE (organization_id, obligacion_id, ocupa_marca),

    -- La misma obligacion no entra dos veces al MISMO lote, ni siquiera como item debitado
    -- mas otro vivo. Sin esto, "quitar y volver a agregar" crearia filas gemelas y
    -- total_presentado contaria doble.
    --
    -- No lleva organization_id, y es correcto: presentacion_id ya es unico en todo el
    -- sistema —es un AUTO_INCREMENT global—, asi que agregarlo no acota nada. Mismo
    -- criterio que uk_obligacion_prestacion en V36.
    CONSTRAINT uk_presentacion_item_en_el_lote
        UNIQUE (presentacion_id, obligacion_id),

    CONSTRAINT fk_presentacion_item_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_presentacion_item_presentacion
        FOREIGN KEY (presentacion_id) REFERENCES presentacion (id),

    CONSTRAINT fk_presentacion_item_obligacion
        FOREIGN KEY (obligacion_id) REFERENCES obligacion (id),

    CONSTRAINT ck_presentacion_item_estado
        CHECK (estado IN ('INCLUIDO', 'ACEPTADO', 'DEBITADO', 'ANULADO')),

    CONSTRAINT ck_presentacion_item_importe_positivo
        CHECK (importe_presentado > 0),

    CONSTRAINT ck_presentacion_item_debito_acotado
        CHECK (importe_debitado >= 0 AND importe_debitado <= importe_presentado),

    -- Un debito sin motivo, sin instante y sin actor es una prestacion que desaparecio del
    -- reclamo sin que nadie pueda explicar por que. Es lo que una auditoria busca.
    CONSTRAINT ck_presentacion_item_debito_completo
        CHECK (estado <> 'DEBITADO'
            OR (motivo_debito IS NOT NULL AND debitado_en IS NOT NULL
                AND debitado_por_cuenta_id IS NOT NULL AND importe_debitado > 0))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Una obligacion dentro de un lote. Incluirla NO le mueve el saldo: eso pasa al conciliar.';


-- El detalle de un lote, en orden estable.
CREATE INDEX ix_presentacion_item_lote
    ON presentacion_item (presentacion_id, id);

-- "¿Donde se presento esta prestacion?", que es la pregunta del 409 obligacion-ya-presentada
-- y la que permite explicarle al administrativo a que lote ir a mirar.
CREATE INDEX ix_presentacion_item_obligacion
    ON presentacion_item (organization_id, obligacion_id);


-- =====================================================================================
-- 4. El pago del financiador: el unico punto donde M21 toca la caja.
-- =====================================================================================

CREATE TABLE financiador_pago
(
    id                     BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id        BIGINT         NOT NULL,
    consultorio_id         BIGINT         NOT NULL,
    financiador_id         BIGINT         NOT NULL,
    presentacion_id        BIGINT         NOT NULL,

    importe                DECIMAL(12, 2) NOT NULL,
    moneda                 CHAR(3)        NOT NULL,
    medio                  VARCHAR(24)    NOT NULL
        COMMENT 'Mismo catalogo que cobro_medio (V37) y movimiento_caja (V54). Casi siempre TRANSFERENCIA.',
    fecha_pago             DATE           NOT NULL COMMENT 'Cuando pago el financiador, que puede no ser cuando se cargo.',
    referencia             VARCHAR(80)    NULL COMMENT 'Numero de transferencia o de recibo del financiador.',

    -- RN-M21-002: el pago genera caja. El movimiento se asienta en la MISMA transaccion y
    -- esta columna apunta a el. La direccion importa: el movimiento existe porque entro
    -- plata, y la plata entro porque el financiador pago — no al reves.
    movimiento_caja_id     BIGINT         NOT NULL,

    registrado_en          DATETIME(6)    NOT NULL,
    registrado_por_cuenta_id BIGINT       NOT NULL,

    -- Mismo par que `cobro` (V37) y `movimiento_caja` (V54): sin el hash no se puede
    -- distinguir un reintento de un reuso de clave con otro contenido.
    idempotency_key        VARCHAR(80)    NULL,
    request_hash           CHAR(64)       NULL,

    created_at             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- SIN version, SIN updated_at, SIN deleted_at, y el puerto no declara update ni delete.
    -- Un historial de pagos que se puede editar no es un historial (regla maestra 10). Un
    -- pago mal registrado se compensa revirtiendo su movimiento de caja y registrando el
    -- correcto. Mismo diseño que turno_evento (V38), caso_evento (V47),
    -- autorizacion_movimiento (V50) y movimiento_caja (V54).

    CONSTRAINT uk_financiador_pago_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_financiador_pago_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_financiador_pago_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_financiador_pago_financiador
        FOREIGN KEY (financiador_id) REFERENCES financiador (id),

    CONSTRAINT fk_financiador_pago_presentacion
        FOREIGN KEY (presentacion_id) REFERENCES presentacion (id),

    CONSTRAINT fk_financiador_pago_movimiento
        FOREIGN KEY (movimiento_caja_id) REFERENCES movimiento_caja (id),

    CONSTRAINT ck_financiador_pago_importe_positivo
        CHECK (importe > 0),

    CONSTRAINT ck_financiador_pago_medio
        CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'TARJETA_DEBITO', 'TARJETA_CREDITO', 'OTRO')),

    CONSTRAINT ck_financiador_pago_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Plata que el financiador pago (RF-M21-007). NO es un Cobro: no hay paciente, ni imputaciones, ni comprobante fiscal.';


-- La cuenta corriente: lo que un financiador pago, en el tiempo.
CREATE INDEX ix_financiador_pago_cuenta_corriente
    ON financiador_pago (organization_id, financiador_id, fecha_pago);

-- Los pagos de un lote, para su detalle.
CREATE INDEX ix_financiador_pago_presentacion
    ON financiador_pago (presentacion_id, id);


-- =====================================================================================
-- 5. El numerador. Punto 6.
-- =====================================================================================

CREATE TABLE presentacion_numerador
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT      NOT NULL,
    consultorio_id  BIGINT      NOT NULL,
    financiador_id  BIGINT      NOT NULL,

    ultimo_numero   INT         NOT NULL DEFAULT 0
        COMMENT 'Se incrementa con UPDATE, nunca con SELECT MAX + 1: ver el punto 6.',

    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_presentacion_numerador
        UNIQUE (organization_id, consultorio_id, financiador_id),

    CONSTRAINT fk_presentacion_numerador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_presentacion_numerador_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_presentacion_numerador_financiador
        FOREIGN KEY (financiador_id) REFERENCES financiador (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Serie de lotes por (sede, financiador). Cada obra social recibe su propia numeracion.';
