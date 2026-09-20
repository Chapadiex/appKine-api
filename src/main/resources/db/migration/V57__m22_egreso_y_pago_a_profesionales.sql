-- =====================================================================================
-- AKINE-07.05 — Egresos y pagos a profesionales (M22).
--
-- Trazabilidad: RF-M22-001 (registrar egreso), RF-M22-002 (registrar pago a profesional),
-- RF-M22-003 (comprobante — referencia documental, ver mas abajo), RF-M22-004 (consultar
-- y filtrar), RF-M22-005 (anular con reversion trazable); RN-M22-001 (un egreso confirmado
-- es lo unico que puede mover la caja), RN-M22-002 (anular NO significa borrar), RN-M22-003
-- (pagar a un profesional no modifica sesiones historicas); RF-M24-006 (relacionar
-- operacion original y reversion).
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0005 (Problem Details), ADR-0011 (baja logica).
--
-- ---------------------------------------------------------------------------------------
-- LA REGLA QUE ESTA ETAPA EXISTE PARA NO ROMPER: DEL LADO DEL EGRESO TAMBIEN SON TRES
--
-- Del lado del paciente este repositorio ya separo OBLIGACION != COBRO != CAJA (reglas
-- maestras 6, 7 y 8). Del lado del profesional pasa exactamente lo mismo:
--
--   `egreso`          — LO QUE SE DEBE. El centro le debe plata a alguien.
--   `pago_egreso`     — EL ACTO DE SALDAR. El centro pago, total o parcialmente.
--   `movimiento_caja` — EL HECHO MONETARIO. Salio plata de un cajon. (Es de V54: SE REUSA.)
--
-- La tentacion es colapsarlas: "un egreso es sacar plata de la caja". Eso ya existe —es el
-- movimiento manual de RF-M20-003, para comprar cafe— y NO ALCANZA para M22, por tres
-- razones que se pueden nombrar:
--
--   1. UNA LIQUIDACION SE DEBE ANTES DE PAGARSE. Se cierra el mes, se calcula lo que le toca
--      al kinesiologo, se le paga el 10. Entre esas dos fechas el centro DEBE esa plata. Si
--      la unica entidad es el movimiento, esa deuda no existe en ninguna parte y el reporte
--      de "cuanto le debo a cada profesional" no se puede escribir.
--
--   2. EL PAGO PARCIAL ESTA DECLARADO COMO CASO BORDE DE LA ETAPA. Un movimiento de caja no
--      tiene saldo: si el egreso ES el movimiento, pagar 60.000 de una liquidacion de
--      100.000 produce un hecho que no sabe que le faltan 40.000.
--
--   3. EL COMPROBANTE Y EL PERIODO SON DEL COMPROMISO, NO DEL MOVIMIENTO. La factura por el
--      periodo 01/09-30/09 identifica LO QUE SE DEBE. Si se pago en dos veces, los dos
--      movimientos comparten ese comprobante y ninguno de los dos es su dueno.
--
-- Por eso `pago_egreso` NO TIENE `movimiento_caja_id`: es el movimiento el que apunta al
-- pago (`tipo_origen = 'PAGO_EGRESO'`, `referencia_origen = pago_egreso.id`), exactamente
-- como V54 decidio para el cobro. Y el unique `uk_movimiento_caja_origen` que ya existe hace
-- que un pago produzca A LO SUMO UN MOVIMIENTO, gratis.
--
-- ---------------------------------------------------------------------------------------
-- `egreso` NO TIENE `sesion_id`, NI `obligacion_id`, NI `persona_id`, NI `cobro_id`
--
-- RN-M22-003 dice que pagar a un profesional no modifica sesiones historicas, y la forma
-- fuerte de cumplir una regla asi NO es escribir un `if`: es que el modelo NO TENGA COMO
-- nombrar la sesion. Como contrapartida, esta etapa no puede calcular una liquidacion desde
-- las sesiones — y no debe: el plan dice "sin inventar regla remunerativa", y RF-M22-006 y
-- RF-M22-007 (base de pago por clase o actividad) son de la SEGUNDA ENTREGA.
--
-- ---------------------------------------------------------------------------------------
-- LAS CINCO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. EL BORRADOR NO ES DECORACION DE CRUD. Es la unica fase donde el egreso se puede editar,
--    y existe porque la liquidacion de un profesional se arma mirando papeles: se carga el
--    importe, se busca la factura, se corrige el periodo. Sin borrador, cada correccion seria
--    una anulacion mas un alta nueva, y el historico se llenaria de anulaciones QUE NO SON
--    ERRORES SINO TIPEO.
--
--    CONFIRMADO es el punto de no retorno: el egreso no se edita mas por ningun camino,
--    recien ahi admite pagos, y el beneficiario, el importe y el comprobante quedan
--    CONGELADOS. `ck_egreso_borrador_sin_pagos` lo respalda desde la base.
--
-- 2. LO QUE MUEVE LA CAJA ES EL PAGO, NO LA CONFIRMACION, Y ESO LEE RN-M22-001 ASI:
--    "solo un egreso confirmado puede mover la caja; un borrador no afecta nada".
--    La letra estricta —"egreso confirmado afecta caja"— volveria contradictorias otras dos
--    cosas del mismo documento: un egreso DEBIDO Y NO PAGADO no podria existir (que es la
--    mitad del objetivo de M22), y el PAGO PARCIAL seria irrepresentable, porque no se puede
--    sacar media vez la plata de un cajon. Una regla que vuelve inexpresable un caso borde
--    que el mismo documento declara se esta leyendo mal.
--
-- 3. EL SALDO SE MUEVE CON UNA CONDICION, NO CON UN LOCK, Y HAY DOS CONDICIONES PORQUE HAY
--    DOS INVARIANTES CON DUENOS DISTINTOS:
--
--      -- La deuda no se paga dos veces. Dueno: egreso.
--      UPDATE egreso SET saldo_pendiente = saldo_pendiente - :importe
--       WHERE id = :id AND organization_id = :org
--         AND estado = 'CONFIRMADO' AND saldo_pendiente >= :importe;
--
--      -- El cajon no queda en rojo. Dueno: jornada_caja (V54, reusado tal cual).
--      UPDATE jornada_caja SET saldo_arqueo = saldo_arqueo - :importe
--       WHERE id = :id AND estado = 'ABIERTA' AND saldo_arqueo >= :importe;
--
--    EL CASO QUE ROMPE EL DISENO: fin de mes, 80.000 en el cajon, dos administrativos
--    registran al mismo tiempo dos pagos en efectivo de 50.000. Si los dos entran, la caja
--    queda en -20.000 —un estado que el mundo fisico no admite— y el arqueo de esa noche
--    registraria una diferencia de 20.000 SOBRE UN FALTANTE QUE NUNCA EXISTIO, que alguien
--    tendria que justificar por escrito. Con la segunda condicion: el segundo afecta CERO
--    FILAS y recibe 409 `caja-saldo-insuficiente` con el saldo disponible.
--
--    CERO FILAS ES LA RESPUESTA, no un error tecnico. Sin lock, sin lectura previa, sin
--    ventana entre leer y escribir, sin deadlock posible. Es lo que 07.02 hizo para imputar,
--    04.05 para consumir y 07.03 para mover el saldo del cajon.
--
-- 4. `version` EN `egreso` SI, Y NO CONTRADICE A `jornada_caja`. Aquella no la tiene porque
--    todas sus escrituras son SQL nativo y no la incrementarian —un token optimista que no
--    avanza cuando el dato cambia promete una proteccion que no da—. Aca conviven porque
--    LAS DOS FASES NO SE SUPERPONEN: `version` gobierna solo el BORRADOR, donde las
--    escrituras son ediciones por JPA y dos administrativos si pueden pisarse; el UPDATE
--    nativo del saldo ocurre solo en CONFIRMADO, donde el egreso ya no se edita.
--
--    Y NO HAY `OPTIMISTIC_FORCE_INCREMENT` EN NINGUNA PARTE: la reciproca que 04.02 pago
--    —si el padre ademas queda sucio, la version avanza DOS VECES y el cliente come un 409
--    del que no puede salir— pasaria exactamente eso aca, porque las escrituras del saldo
--    tocan columnas del propio `egreso`.
--
-- 5. ANULAR NO ES BORRAR, Y SON DOS OPERACIONES PORQUE SON DOS HECHOS.
--    Anular el EGRESO no toca la caja: un compromiso que solo se debia nunca movio plata. Y
--    se rechaza si queda algun pago vigente (409 con `yaPagado`), igual que 07.01 rechaza
--    anular una obligacion con cobros imputados.
--    Anular el PAGO si corrige la caja: asienta una fila propia REVERSION_DE_EGRESO con
--    motivo y puntero al original —por el MISMO metodo de V54, no por un camino nuevo— y
--    devuelve el saldo al egreso. El pago queda con estado ANULADO: no se borra.
--
-- ---------------------------------------------------------------------------------------
-- RF-M22-003, CON HONESTIDAD: REFERENCIA DOCUMENTAL, NO ARCHIVO
--
-- Esta migracion guarda tipo, numero y fecha del comprobante, y NO el binario. El motivo
-- esta escrito desde 04.02: `person` (V40) y `clinical` (V46) tienen cada uno su puerto de
-- storage y su adaptador de sistema de archivos, con la condicion de salida declarada —"si
-- aparece un tercer consumidor, se extrae a platform.spi"—. Esta etapa ES ese tercer
-- consumidor, y la extraccion refactoriza dos modulos cerrados: es una etapa propia, no un
-- agregado de contrabando dentro de la que introduce el modelo de M22.
--
-- `beneficiario_clave` existe para UNA sola cosa: que el unique del comprobante pueda
-- incluir DE QUIEN es. Dos proveedores distintos emiten legitimamente su propia
-- FACTURA_B 0001-00000123, y sin la clave el segundo no se podria cargar. Con ella, el caso
-- borde "factura externa duplicada" tiene respuesta: 409 y no una fila repetida.
--
-- Y `anulado_key` con el centinela '1970-01-01' esta para que la baja logica NO SEA UNA
-- TRAMPA: sin el, anular un egreso cargado por error dejaria ese numero de factura quemado
-- para siempre y el operador no tendria forma de rehacerlo. Mismo mecanismo que `deleted_key`
-- en V30 y `owner_key` en ADR-0021.
-- =====================================================================================

CREATE TABLE egreso
(
    id                        BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id           BIGINT         NOT NULL,
    consultorio_id            BIGINT         NOT NULL
        COMMENT 'Un egreso se paga desde la caja de UNA sede, igual que la jornada de V54.',

    categoria                 VARCHAR(32)    NOT NULL
        COMMENT 'HONORARIOS_PROFESIONALES, ALQUILER, SERVICIOS, INSUMOS, IMPUESTOS, MANTENIMIENTO, SUELDOS u OTRO.',

    -- --------------------------------------------------------------------------------
    -- Beneficiario. Dos formas, un solo snapshot.
    -- --------------------------------------------------------------------------------
    tipo_beneficiario         VARCHAR(16)    NOT NULL COMMENT 'COLABORADOR o EXTERNO.',

    -- MEMBERSHIP Y NO account_id, por lo mismo que V23 y V28: la misma persona puede ser
    -- profesional en un centro y administrativa en otro, y lo que la identifica DENTRO de
    -- esta organizacion es el vinculo, no la cuenta global.
    beneficiario_membership_id BIGINT        NULL,

    -- "M:<membershipId>" o "E:<documento|nombre normalizado>". Ver la cabecera.
    beneficiario_clave        VARCHAR(80)    NOT NULL,

    -- SNAPSHOT CONGELADO, mismo criterio que `obligacion.snapshot_nombre` en V36: una
    -- liquidacion de septiembre tiene que poder leerse en marzo sin depender de que la
    -- membership siga existiendo ni de que la persona no se haya casado.
    beneficiario_nombre       VARCHAR(160)   NOT NULL,
    beneficiario_documento    VARCHAR(32)    NULL,

    -- --------------------------------------------------------------------------------
    -- Que se debe.
    -- --------------------------------------------------------------------------------
    periodo_desde             DATE           NULL
        COMMENT 'Periodo liquidado. Los dos NULL o los dos presentes. NO hay unique: dos liquidaciones del mismo mes son legitimas.',
    periodo_hasta             DATE           NULL,

    concepto                  VARCHAR(280)   NOT NULL,

    importe_total             DECIMAL(12, 2) NOT NULL COMMENT 'Declarado por una persona. Esta etapa NO calcula liquidaciones.',
    saldo_pendiente           DECIMAL(12, 2) NOT NULL COMMENT 'Punto 3 de la cabecera. Arranca igual al total.',
    moneda                    CHAR(3)        NOT NULL,

    estado                    VARCHAR(16)    NOT NULL
        COMMENT 'BORRADOR, CONFIRMADO, PAGADO o ANULADO. Punto 1.',

    -- --------------------------------------------------------------------------------
    -- Comprobante: REFERENCIA, no archivo. Ver la cabecera.
    -- --------------------------------------------------------------------------------
    comprobante_tipo          VARCHAR(24)    NULL,
    comprobante_numero        VARCHAR(40)    NULL,
    comprobante_fecha         DATE           NULL,

    registrado_en             DATETIME(6)    NOT NULL,
    registrado_por_cuenta_id  BIGINT         NOT NULL,

    confirmado_en             DATETIME(6)    NULL,
    confirmado_por_cuenta_id  BIGINT         NULL,

    -- Punto 5. La fila entera se conserva: RN-M22-002.
    anulado_en                DATETIME(6)    NULL,
    anulado_por_cuenta_id     BIGINT         NULL,
    motivo_anulacion          VARCHAR(280)   NULL,

    -- Centinela para que la baja logica no queme el numero de comprobante. Ver la cabecera.
    anulado_key               DATETIME(6) AS (IFNULL(anulado_en, '1970-01-01 00:00:00')) STORED NOT NULL,

    idempotency_key           VARCHAR(80)    NULL,
    request_hash              CHAR(64)       NULL,

    version                   BIGINT         NOT NULL DEFAULT 0 COMMENT 'Punto 4: gobierna SOLO el borrador.',

    created_at                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- El caso borde "factura externa duplicada". Con `comprobante_numero` NULL el unique no
    -- participa —varios NULL no colisionan en MySQL— y un borrador sin factura sigue siendo
    -- legitimo.
    CONSTRAINT uk_egreso_comprobante
        UNIQUE (organization_id, beneficiario_clave, comprobante_tipo, comprobante_numero, anulado_key),

    -- Igual que `cobro` (V37) y `movimiento_caja` (V54): un doble click no carga dos veces
    -- la misma liquidacion.
    CONSTRAINT uk_egreso_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_egreso_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_egreso_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_egreso_membership
        FOREIGN KEY (beneficiario_membership_id) REFERENCES membership (id),

    CONSTRAINT ck_egreso_estado
        CHECK (estado IN ('BORRADOR', 'CONFIRMADO', 'PAGADO', 'ANULADO')),

    CONSTRAINT ck_egreso_categoria
        CHECK (categoria IN ('HONORARIOS_PROFESIONALES', 'ALQUILER', 'SERVICIOS', 'INSUMOS',
                             'IMPUESTOS', 'MANTENIMIENTO', 'SUELDOS', 'OTRO')),

    CONSTRAINT ck_egreso_tipo_beneficiario
        CHECK (tipo_beneficiario IN ('COLABORADOR', 'EXTERNO')),

    -- El tipo y la presencia de la membership se implican. Un COLABORADOR sin vinculo seria
    -- un nombre suelto disfrazado de referencia, y un EXTERNO con membership seria una
    -- referencia que nadie mira.
    CONSTRAINT ck_egreso_beneficiario_coherente
        CHECK ((tipo_beneficiario = 'COLABORADOR') = (beneficiario_membership_id IS NOT NULL)),

    CONSTRAINT ck_egreso_importe_positivo
        CHECK (importe_total > 0),

    -- Las dos mitades del punto 3: el saldo no pasa de cero ni supera lo que se debe.
    CONSTRAINT ck_egreso_saldo_no_negativo
        CHECK (saldo_pendiente >= 0),

    CONSTRAINT ck_egreso_saldo_acotado
        CHECK (saldo_pendiente <= importe_total),

    -- Punto 1: un borrador NO tiene pagos, y la base lo sostiene en vez de confiar en que
    -- el servicio nunca se equivoque.
    CONSTRAINT ck_egreso_borrador_sin_pagos
        CHECK (estado <> 'BORRADOR' OR saldo_pendiente = importe_total),

    -- Los dos NULL o los dos presentes, y coherentes. Un periodo con una sola punta no dice
    -- nada y el filtro por periodo lo dejaria afuera o lo traeria siempre.
    CONSTRAINT ck_egreso_periodo_coherente
        CHECK ((periodo_desde IS NULL) = (periodo_hasta IS NULL)
            AND (periodo_desde IS NULL OR periodo_desde <= periodo_hasta)),

    -- Confirmacion completa: los dos campos o ninguno, y un egreso que ya no es borrador
    -- TIENE que estar confirmado. Mismo criterio que `ck_jornada_caja_cierre_completo` (V54).
    CONSTRAINT ck_egreso_confirmacion_completa
        CHECK ((confirmado_en IS NULL) = (confirmado_por_cuenta_id IS NULL)
            AND (estado = 'BORRADOR' OR estado = 'ANULADO' OR confirmado_en IS NOT NULL)),

    -- Anulacion completa, con motivo. Mismo criterio que `ck_obligacion_anulacion_completa`
    -- (V36): una anulacion sin motivo es plata que desaparece sin explicacion.
    CONSTRAINT ck_egreso_anulacion_completa
        CHECK ((estado = 'ANULADO') = (anulado_en IS NOT NULL)
            AND (anulado_en IS NULL
                 OR (anulado_por_cuenta_id IS NOT NULL AND motivo_anulacion IS NOT NULL))),

    -- Sin el hash no se puede distinguir un reintento de un reuso de clave con otro
    -- contenido. Mismo par que `cobro` (V37).
    CONSTRAINT ck_egreso_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Lo que el centro DEBE a un beneficiario. No es el pago (pago_egreso) ni el hecho monetario (movimiento_caja).';


-- La bandeja por estado: RF-M22-004.
CREATE INDEX ix_egreso_sede_estado
    ON egreso (organization_id, consultorio_id, estado, registrado_en);

-- "Cuanto le debo a este profesional": el filtro por beneficiario de RF-M22-004, y el
-- reporte que M22 existe para poder escribir.
CREATE INDEX ix_egreso_beneficiario
    ON egreso (organization_id, beneficiario_clave, estado);

-- El filtro por periodo. Tambien es como se responde el caso borde "periodo superpuesto":
-- NO se impide, SE MUESTRA.
CREATE INDEX ix_egreso_periodo
    ON egreso (organization_id, consultorio_id, periodo_desde, periodo_hasta);


-- -------------------------------------------------------------------------------------
-- pago_egreso — el acto de saldar. UN MEDIO POR PAGO: ver abajo.
-- -------------------------------------------------------------------------------------
CREATE TABLE pago_egreso
(
    id                       BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Desnormalizado igual que `cobro_medio` (V37) y `movimiento_caja` (V54): ADR-0004 dice
    -- "sin excepcion" y es precisamente por eso —una consulta que se olvide del JOIN
    -- cruzaria tenants sin fallar.
    organization_id          BIGINT         NOT NULL,
    consultorio_id           BIGINT         NOT NULL,

    egreso_id                BIGINT         NOT NULL,

    importe                  DECIMAL(12, 2) NOT NULL,
    moneda                   CHAR(3)        NOT NULL,

    -- UN MEDIO POR PAGO, y no una tabla hija como `cobro_medio`. A diferencia del cobro
    -- —donde el paciente paga mitad efectivo mitad tarjeta en UN acto de mostrador—,
    -- pagarle a un profesional mitad en efectivo y mitad por transferencia son DOS HECHOS
    -- DISTINTOS, con dos comprobantes y probablemente dos dias. Modelarlo con tabla hija
    -- daria una hija que nunca tiene mas de una fila.
    medio                    VARCHAR(24)    NOT NULL,

    referencia               VARCHAR(80)    NULL
        COMMENT 'Numero de transferencia o de recibo. Es lo que hace conciliable un pago que no es en efectivo.',

    -- Propia, no heredada: mismo criterio que `movimiento_caja.fecha_negocio` en V54.
    fecha_negocio            DATE           NOT NULL,

    estado                   VARCHAR(16)    NOT NULL COMMENT 'CONFIRMADO o ANULADO. Anular no borra.',

    pagado_en                DATETIME(6)    NOT NULL,
    pagado_por_cuenta_id     BIGINT         NOT NULL,

    anulado_en               DATETIME(6)    NULL,
    anulado_por_cuenta_id    BIGINT         NULL,
    motivo_anulacion         VARCHAR(280)   NULL,

    idempotency_key          VARCHAR(80)    NULL,
    request_hash             CHAR(64)       NULL,

    version                  BIGINT         NOT NULL DEFAULT 0,

    created_at               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- NO HAY `movimiento_caja_id`, y es la misma decision que V54 tomo para el cobro: es el
    -- MOVIMIENTO el que apunta al pago, no al reves. Ver la cabecera.

    CONSTRAINT uk_pago_egreso_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_pago_egreso_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_pago_egreso_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_pago_egreso_egreso
        FOREIGN KEY (egreso_id) REFERENCES egreso (id),

    CONSTRAINT ck_pago_egreso_estado
        CHECK (estado IN ('CONFIRMADO', 'ANULADO')),

    CONSTRAINT ck_pago_egreso_medio
        CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'TARJETA_DEBITO', 'TARJETA_CREDITO', 'OTRO')),

    CONSTRAINT ck_pago_egreso_importe_positivo
        CHECK (importe > 0),

    CONSTRAINT ck_pago_egreso_anulacion_completa
        CHECK ((estado = 'ANULADO') = (anulado_en IS NOT NULL)
            AND (anulado_en IS NULL
                 OR (anulado_por_cuenta_id IS NOT NULL AND motivo_anulacion IS NOT NULL))),

    CONSTRAINT ck_pago_egreso_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'El acto de saldar un egreso, total o parcialmente. Anular no borra: cambia de estado.';


-- Los pagos de un egreso, en orden. RF-M22-004 (detalle) y la anulacion.
CREATE INDEX ix_pago_egreso_egreso
    ON pago_egreso (organization_id, egreso_id, pagado_en);


-- -------------------------------------------------------------------------------------
-- El UNICO cambio sobre el esquema de 07.03.
--
-- `movimiento_caja` acepta un origen nuevo: PAGO_EGRESO, sobre una tabla DEL MISMO MODULO.
--
-- UN `ALTER` QUE REEMPLAZA UN `CHECK` NO ES ADITIVO, AUNQUE AGREGUE UN VALOR. Esta migracion
-- decia "es ADITIVO, ningun valor existente deja de ser valido" y era FALSO: `DROP CHECK` mas
-- `ADD CONSTRAINT` reescribe la lista ENTERA, asi que lo que no se vuelve a nombrar
-- desaparece. La version original de este archivo listaba COBRO, MANUAL, REVERSION y
-- PAGO_EGRESO, reconstruyendo la lista desde el estado ANTERIOR a V56 y borrando el
-- PAGO_FINANCIADOR que V56 acababa de agregar. V57 corre despues: el estado final de la base
-- RECHAZABA el pago de financiador de 07.04.
--
-- No lo agarro ningun gate. Las dos migraciones son de ramas distintas, tocan archivos
-- distintos y git no vio conflicto: es el merge limpio que no compila, version esquema. Y
-- ninguna migracion de F7 corrio jamas contra un motor, asi que tampoco iba a fallar sola.
--
-- LA REGLA QUE ESTO DEJA: si dos etapas en vuelo tocan el MISMO constraint, la segunda tiene
-- que leer lo que la primera dejo, no lo que el estado base decia cuando ella empezo.
--
-- Con esto, `uk_movimiento_caja_origen (organization_id, tipo, tipo_origen,
-- referencia_origen, medio)` garantiza gratis que UN PAGO PRODUZCA A LO SUMO UN MOVIMIENTO,
-- y que el reintento no duplique la salida de plata. Exactamente lo que ya hace por el cobro.
-- -------------------------------------------------------------------------------------
ALTER TABLE movimiento_caja
    DROP CHECK ck_movimiento_caja_tipo_origen;

ALTER TABLE movimiento_caja
    ADD CONSTRAINT ck_movimiento_caja_tipo_origen
        CHECK (tipo_origen IN ('COBRO', 'MANUAL', 'REVERSION', 'PAGO_FINANCIADOR', 'PAGO_EGRESO'));
