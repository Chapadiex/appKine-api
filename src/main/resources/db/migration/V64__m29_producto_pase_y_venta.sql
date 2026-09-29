-- =====================================================================================
-- AKINE-08.06 — Productos, packs y venta inicial (M29 / M27 / M18).
--
-- V64 y no V62 ni V63: V51-V61 estan tomadas, V62 la reservo 07.07 y V63 la reservo
-- 08.04, las dos en vuelo ahora mismo en otros worktrees. El numero se reserva ANTES de
-- escribir codigo porque V26 quedo vacia para siempre cuando 02.07 y 03.01 nacieron las
-- dos como V26: Flyway no arranca con versiones duplicadas.
--
-- RF-M29-001 (crear producto de pack/pase para Oferta), RF-M29-002 (comprar PaseServicio),
-- RF-M18-009 (obligacion por compra de pack o pase), RF-M18-008 (la POLITICA, no el
-- disparo); RN-M29-001..004, RN-M29-008; RN-M27-006; CA-M29-001-06, CA-M29-002-06.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer), ADR-0013 (el prepago es una politica configurable).
--
-- Propietario de las tres tablas nuevas: modulo `activity`, que AGENT.md seccion 4 define
-- como "clases, inscripciones, participacion, pases y abonos -> M28-M29".
--
-- ---------------------------------------------------------------------------------------
-- LAS SEIS COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. VENDER NO ES COBRAR, Y COBRAR NO ES QUE ENTRE PLATA AL CAJON.
--    Deuda, Cobro y Caja son tres cosas (reglas maestras 6, 7 y 8; M18/M19/M20). Esta
--    migracion NO crea una tabla de cobro ni de caja, y la venta produce exactamente UNA
--    obligacion. Quien paga usa el cobro de V37 (07.02), que imputa contra ella; el
--    movimiento de caja lo decide el medio de pago, en 07.03 —que NO esta en esta rama—.
--    El atajo comercial "vender y cobrar en un click" es el colapso del UML de 2019.
--
-- 2. UN CREDITO NO ES PLATA (RN-M29-006). movimiento_pase mueve INT, no DECIMAL, y su
--    consumo no genera movimiento de caja. Por eso NO vive en billing junto al ledger
--    monetario: son dos unidades con dos invariantes, y la tabla que las juntara tendria
--    la mitad de sus columnas siempre nulas.
--
-- 3. PRODUCTO != PASE COMPRADO. El producto se edita; el pase lleva su propio snapshot
--    comercial y no cambia cuando el producto cambia. Subirle el precio al pack manana no
--    puede cambiar lo que alguien pago hoy — es el mismo congelamiento que obligacion.
--    snapshot_precio hace desde V36.
--
-- 4. NINGUN `DROP CHECK` EN ESTA MIGRACION, Y SE VERIFICO LEYENDO V24 Y V36, NO ASUMIENDO.
--    V57 reconstruyo un check desde el estado anterior a V56 y borro el valor que V56
--    acababa de agregar; git no vio conflicto porque son archivos distintos. Aca los
--    cuatro CHECK de obligacion (V36) y los de oferta (V24) quedan intactos: solo se
--    SUMAN constraints.
--
--    Corolario en la otra direccion: los CHECK que esta migracion crea declaran YA el
--    vocabulario completo que 08.07 y 08.08 van a necesitar —los cinco tipos de movimiento
--    de RN-M29-004, el tipo ABONO, el origen VENTA_ABONO, los cuatro estados del pase—
--    aunque esta etapa emita solo una parte. Cuesta una linea y le evita a la etapa
--    siguiente la operacion peligrosa.
--
-- 5. obligacion.sesion_id ERA NOT NULL, Y UNA COMPRA DE PACK NO TIENE SESION.
--    Es el impedimento real que RF-M18-009 encuentra en el esquema de 07.01, y se resuelve
--    EXPANDIENDO obligacion, no creando una deuda paralela: una segunda tabla seria una
--    segunda cuenta corriente para la misma persona, y el cobro de V37 no veria la mitad.
--
-- 6. ESTA MIGRACION FIJA EL VOCABULARIO DE esquema_cobro, QUE V24 DEJO ABIERTO.
--    V24 lo declaro textualmente "DATO DECLARADO, NO RESUELTO... sin lista cerrada a
--    proposito, porque el vocabulario lo fija M16/M18 y esos modulos no existen". M18
--    existe desde 07.01 y M29 es esta etapa. Se cierra aca, con su politica de devengo al
--    lado, para que la pregunta que 08.03 delego sea una CONFIGURACION y no un rediseno.
-- =====================================================================================


-- =====================================================================================
-- 1. producto_servicio — la definicion comercial (RF-M29-001)
-- =====================================================================================
--
-- CA-M29-001-06 lo pide con estas palabras: un Pack Pilates 8 clases queda disponible
-- como producto SIN CREAR TODAVIA SALDO A UNA PERSONA. Por eso son dos tablas.
--
-- Cuelga de una OFERTA y no de un servicio: el precio y la vigencia son de la sede, y el
-- servicio es catalogo global sin organization_id (V24, ADR-0023). Un producto sobre un
-- servicio seria un precio sin dueno.

CREATE TABLE producto_servicio
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT         NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva y todo unique e indice empieza por el.',
    consultorio_id      BIGINT         NOT NULL COMMENT 'Sede que vende el producto. Va aunque sea derivable de la oferta, por el mismo motivo que en clase_programada: derivarlo obliga a un join para filtrar por sede y habilita la consulta sin join que ningun test de una etapa detecta.',
    oferta_id           BIGINT         NOT NULL COMMENT 'La oferta cuyos creditos vende este producto (RF-M29-001 paso 6). FK RESTRICT: el borrado fisico de una oferta con productos es imposible desde el motor.',

    nombre              VARCHAR(160)   NOT NULL COMMENT 'Como se llama el producto en la cartelera: "Pack Pilates 8 clases". Unico entre los productos VIGENTES de la sede.',
    descripcion         VARCHAR(500)   NULL COMMENT 'Descripcion comercial. Nunca contenido clinico.',

    tipo                VARCHAR(16)    NOT NULL COMMENT 'PACK o ABONO. Esta etapa SOLO emite PACK; ABONO esta en el CHECK desde hoy para que 08.08 no tenga que hacer DROP CHECK — ver el punto 4 de la cabecera.',

    creditos            INT            NOT NULL COMMENT 'Cuantos creditos otorga la compra. INT y no DECIMAL: medio credito no existe.',

    precio              DECIMAL(12, 2) NOT NULL COMMENT 'Precio de venta. NOT NULL, a diferencia del precio_base anulable de la Oferta: un producto sin precio no se puede vender, asi que "vendi algo sin saber cuanto vale" no es un estado alcanzable desde el esquema. DECIMAL, nunca float.',
    moneda              CHAR(3)        NOT NULL COMMENT 'ISO 4217. Viaja siempre con el precio: nunca hay importe sin moneda.',

    vigencia_dias       INT            NOT NULL COMMENT 'Cuantos dias vale el pase DESDE LA COMPRA. Es un plazo del producto, no una fecha: dos personas que compran el mismo pack en meses distintos tienen vencimientos distintos.',

    vigencia_desde      DATE           NOT NULL COMMENT 'Desde cuando el producto se puede vender. Ventana COMERCIAL, distinta del ciclo de vida administrativo de active/deleted_at — misma separacion que oferta (V24).',
    vigencia_hasta      DATE           NULL COMMENT 'Hasta cuando se puede vender, INCLUSIVE. NULL = sin fin previsto, que es un estado real y no un valor faltante.',

    active              TINYINT(1)     NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: el producto no se vende mas y LOS PASES YA VENDIDOS NO SE TOCAN. La baja NO cascadea, igual que la de un servicio en 02.06.',
    deleted_at          DATETIME(6)    NULL,
    deactivation_reason VARCHAR(280)   NULL COMMENT 'Motivo declarado de la baja. Obligatorio al dar de baja.',

    deleted_key         DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de nombre: el instante de la baja, o el centinela mientras el producto este activo. Sin el, dos productos activos homonimos de la misma sede no colisionarian, porque en MySQL varios NULL no chocan en un unique.',

    version             BIGINT         NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno en silencio.',
    created_at          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_producto_servicio PRIMARY KEY (id),

    CONSTRAINT uk_producto_sede_nombre
        UNIQUE (organization_id, consultorio_id, nombre, deleted_key),

    CONSTRAINT fk_producto_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_producto_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_producto_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT ck_producto_tipo
        CHECK (tipo IN ('PACK', 'ABONO')),

    -- Cero creditos no es "un pack chico": es un producto que no entrega nada.
    CONSTRAINT ck_producto_creditos_positivos
        CHECK (creditos > 0),

    -- Un precio de cero es una cortesia, y una cortesia no se modela como venta.
    CONSTRAINT ck_producto_precio_positivo
        CHECK (precio > 0),

    CONSTRAINT ck_producto_vigencia_dias_positiva
        CHECK (vigencia_dias > 0),

    CONSTRAINT ck_producto_ventana_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    -- Misma forma que V24: la baja es un cuarteto o no es nada. Una fila inactiva sin
    -- instante ni motivo es una baja que nadie puede explicar seis meses despues.
    CONSTRAINT ck_producto_baja_completa
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Producto vendible de pack/pase sobre una Oferta (RF-M29-001). Define QUE se vende y a cuanto; lo que una persona compro vive en pase_servicio. Propietario: modulo activity';


CREATE INDEX ix_producto_oferta
    ON producto_servicio (organization_id, consultorio_id, oferta_id, active);


-- =====================================================================================
-- 2. pase_servicio — lo que una persona compro (RF-M29-002, RN-M29-002)
-- =====================================================================================
--
-- NO LLEVA deleted_at NI deleted_key, y es una desviacion consciente de la forma de
-- V24/V58/V60 — la misma que asistencia_actividad hizo en V61 y por el mismo tipo de
-- motivo. Un pase no se da de baja: SE ANULA, y eso es un estado del ciclo con motivo,
-- actor y rastro. Si tuviera baja logica, uk_pase_idempotency necesitaria deleted_key
-- para no estorbar al historico y entonces DOS VENTAS VIVAS con la misma clave de
-- idempotencia podrian coexistir apenas alguien anulara una: la idempotencia dejaria de
-- serlo justo en el caso que existe para cubrir.

CREATE TABLE pase_servicio
(
    id                    BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id       BIGINT         NOT NULL,
    consultorio_id        BIGINT         NOT NULL COMMENT 'Sede donde se vendio. El consumo puede ocurrir en otra sede de la misma organizacion: eso lo decide 08.07.',
    persona_id            BIGINT         NOT NULL COMMENT 'El TITULAR. Persona, no Paciente: comprar un pack de Pilates no es entrar al circuito clinico (RF-M07-010). La FK la hace cumplir el motor; activity no lee la tabla persona, la consulta por person.spi.',
    producto_id           BIGINT         NOT NULL COMMENT 'Que producto se compro. FK RESTRICT: borrar fisicamente un producto vendido es imposible.',

    -- EL SNAPSHOT SE CONGELA. Editar el producto manana no puede cambiar lo que alguien
    -- compro hoy: eso es reescribir una venta. Mismo criterio que obligacion (V36).
    snapshot_nombre       VARCHAR(160)   NOT NULL,
    snapshot_precio       DECIMAL(12, 2) NOT NULL,
    snapshot_moneda       CHAR(3)        NOT NULL,
    snapshot_creditos     INT            NOT NULL,
    snapshot_oferta_id    BIGINT         NOT NULL COMMENT 'Sobre que oferta valian los creditos AL COMPRAR. Va congelado y no se lee del producto: 08.07 decide elegibilidad contra lo que se vendio, no contra lo que el producto dice hoy.',

    creditos_iniciales    INT            NOT NULL COMMENT 'Lo que la compra otorgo. Nunca cambia.',
    creditos_disponibles  INT            NOT NULL COMMENT 'Saldo vigente. Lo escribe el INSERT de la compra y despues SOLO se mueve por UPDATE condicional (08.07): la entidad lo mapea updatable=false para que no haya otro camino. Si JPA pudiera actualizarlo, cualquier save() lo pisaria con un valor leido antes y el saldo negativo volveria por la puerta de atras — es la leccion de cupo_ocupado en V60.',

    vigencia_desde        DATE           NOT NULL,
    vigencia_hasta        DATE           NOT NULL COMMENT 'vigencia_desde + producto.vigencia_dias, INCLUSIVE. NOT NULL, a diferencia de la oferta: un pase sin vencimiento es un credito eterno, y RN-M29-002 exige vigencia.',

    estado                VARCHAR(16)    NOT NULL COMMENT 'ACTIVO al comprar. AGOTADO, VENCIDO y ANULADO los escribe 08.07; estan en el CHECK desde hoy para que no tenga que hacer DROP CHECK.',

    idempotency_key       VARCHAR(80)    NULL COMMENT 'Clave del comando de compra (CA-M29-002-06). NULL admitido: varios NULL no colisionan en un unique de MySQL, asi que las ventas sin clave no se estorban.',
    request_hash          CHAR(64)       NULL COMMENT 'Huella del pedido. Misma clave con huella distinta es 409, no una segunda venta silenciosa.',

    vendido_por_cuenta_id BIGINT         NOT NULL COMMENT 'Quien vendio. Trazabilidad de CA-M29-002-05.',
    vendido_en            DATETIME(6)    NOT NULL,

    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_pase_servicio PRIMARY KEY (id),

    -- La idempotencia de la compra. Alcance ORGANIZACION y no sede, igual que el unique de
    -- clases y el de turnos: una clave reusada apuntando a otra sede sigue siendo la misma
    -- clave. Cubre la carrera entre dos requests simultaneos que la consulta previa no
    -- puede cubrir: los dos leen vacio, los dos insertan, el motor mata al segundo.
    CONSTRAINT uk_pase_idempotency
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_pase_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_pase_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_pase_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),
    CONSTRAINT fk_pase_producto
        FOREIGN KEY (producto_id) REFERENCES producto_servicio (id),

    CONSTRAINT ck_pase_estado
        CHECK (estado IN ('ACTIVO', 'AGOTADO', 'VENCIDO', 'ANULADO')),

    CONSTRAINT ck_pase_creditos_iniciales_positivos
        CHECK (creditos_iniciales > 0),

    -- RN-M29-008: no se permiten saldos negativos. El CHECK impide el estado imposible; el
    -- UPDATE condicional de 08.07 impide llegar a el sin un error de constraint.
    CONSTRAINT ck_pase_saldo_no_negativo
        CHECK (creditos_disponibles >= 0 AND creditos_disponibles <= creditos_iniciales),

    CONSTRAINT ck_pase_vigencia_coherente
        CHECK (vigencia_hasta >= vigencia_desde),

    -- Una huella sin clave no identifica nada, y una clave sin huella no puede detectar el
    -- reintento con pedido distinto.
    CONSTRAINT ck_pase_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'PaseServicio comprado por una persona (RF-M29-002, RN-M29-002). Propietario: modulo activity';


CREATE INDEX ix_pase_persona
    ON pase_servicio (organization_id, persona_id, id);

CREATE INDEX ix_pase_producto
    ON pase_servicio (organization_id, producto_id);


-- =====================================================================================
-- 3. movimiento_pase — el ledger (RN-M29-003, RN-M29-004)
-- =====================================================================================
--
-- ESTA ES LA UNICA TABLA DE ESTA MIGRACION QUE SE ACERCA AL BORDE DE 08.07, Y SE CREA A
-- PROPOSITO. RN-M29-003 dice que TODO cambio de creditos genera un movimiento inmutable, y
-- crear un pase con 8 creditos ES un cambio de creditos; RF-M29-002 paso 7 lo pide con
-- esas palabras. No escribir la COMPRA dejaria el saldo sin explicar desde la fila uno y
-- obligaria a 08.07 a backfillear un movimiento por cada pase existente antes de poder
-- afirmar su invariante.
--
-- ESTA ETAPA EMITE EXCLUSIVAMENTE 'COMPRA'. Los otros cuatro tipos estan en el CHECK y no
-- tienen escritor: los escribe 08.07.
--
-- No lleva consultorio_id: un movimiento de credito no es un hecho de sede —el pase se
-- compra en una y se puede consumir en otra de la misma organizacion— y ponerlo obligaria
-- a decidir cual guardar en el consumo, que es una decision de 08.07.

CREATE TABLE movimiento_pase
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id  BIGINT       NOT NULL,
    pase_id          BIGINT       NOT NULL,

    tipo             VARCHAR(16)  NOT NULL COMMENT 'Los cinco de RN-M29-004. Esta etapa emite solo COMPRA.',

    cantidad         INT          NOT NULL COMMENT 'Creditos que este movimiento suma o resta, CON SIGNO. Positivo en COMPRA y DEVOLUCION, negativo en CONSUMO y VENCIMIENTO, cualquiera de los dos en AJUSTE. Con signo y no con una columna "sentido" aparte porque el invariante que 08.07 tiene que poder afirmar es una suma: creditos_disponibles == creditos_iniciales + SUM(cantidad).',
    saldo_resultante INT          NOT NULL COMMENT 'El saldo DESPUES de este movimiento. Redundante con la suma y por eso mismo util: es lo que permite detectar una divergencia sin reconstruir el ledger entero.',

    origen_tipo      VARCHAR(24)  NOT NULL COMMENT 'Que hecho produjo el movimiento. VENTA es el unico que esta etapa emite; los otros los emite 08.07.',
    origen_id        BIGINT       NOT NULL COMMENT 'Id del hecho dentro de su tipo. Para VENTA es el propio pase_id: exactamente un movimiento de compra por pase, garantizado por el unique de abajo.',

    actor_cuenta_id  BIGINT       NULL COMMENT 'Quien lo produjo. NULL cuando lo produce el sistema —el vencimiento programado de 08.07 no tiene actor humano—.',
    motivo           VARCHAR(280) NULL COMMENT 'Obligatorio en AJUSTE (RF-M29-005, CA-M29-005-06). La regla la hara cumplir 08.07, que es quien emite ese tipo.',

    ocurrido_en      DATETIME(6)  NOT NULL,

    CONSTRAINT pk_movimiento_pase PRIMARY KEY (id),

    -- LA IDEMPOTENCIA DEL LEDGER. Para la COMPRA garantiza un movimiento por pase; cuando
    -- 08.07 consuma por asistencia, ('ASISTENCIA', asistenciaId) le da gratis que un
    -- reintento no descuente dos veces.
    CONSTRAINT uk_movimiento_origen
        UNIQUE (organization_id, pase_id, origen_tipo, origen_id),

    -- El nombre lleva la tabla adelante y no es cosmetico: InnoDB exige que el nombre de una
    -- foreign key sea unico en TODO el esquema, no por tabla, y `fk_movimiento_organization`
    -- ya lo usa `autorizacion_movimiento` desde V50. Con el nombre corto esta migracion falla
    -- con "Duplicate foreign key constraint name" recien al ejecutarse contra el motor.
    CONSTRAINT fk_movimiento_pase_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_movimiento_pase
        FOREIGN KEY (pase_id) REFERENCES pase_servicio (id),

    -- Igual que la foreign key de arriba: en MySQL 8 el nombre de un CHECK tambien es unico
    -- por ESQUEMA. `autorizacion_movimiento` ya tiene su `ck_movimiento_tipo` desde V50, asi
    -- que los cuatro de esta tabla llevan `movimiento_pase` en el nombre.
    CONSTRAINT ck_movimiento_pase_tipo
        CHECK (tipo IN ('COMPRA', 'CONSUMO', 'AJUSTE', 'DEVOLUCION', 'VENCIMIENTO')),

    CONSTRAINT ck_movimiento_pase_origen_tipo
        CHECK (origen_tipo IN ('VENTA', 'ASISTENCIA', 'CANCELACION', 'AJUSTE_MANUAL', 'VENCIMIENTO')),

    -- Un movimiento de cero no cambia nada y ensucia el ledger con filas que no explican.
    CONSTRAINT ck_movimiento_pase_cantidad_no_cero
        CHECK (cantidad <> 0),

    CONSTRAINT ck_movimiento_pase_saldo_no_negativo
        CHECK (saldo_resultante >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Ledger inmutable de creditos de un pase (RN-M29-003). No se actualiza ni se borra. Propietario: modulo activity';


CREATE INDEX ix_movimiento_pase_orden
    ON movimiento_pase (organization_id, pase_id, ocurrido_en, id);


-- =====================================================================================
-- 4. obligacion — expandir para que una deuda pueda nacer de una venta (RF-M18-009)
-- =====================================================================================
--
-- ADR-0007 exige el ciclo expandir-migrar-contraer para CAMBIOS sobre datos existentes.
-- Aca solo hay EXPANDIR: las columnas nuevas nacen anulables o con DEFAULT, el unique
-- viejo no se toca y no hay una sola fila que migrar en esta rama. La contraccion no
-- aplica porque nada queda obsoleto: sesion_id sigue siendo la columna del devengo por
-- prestacion.
--
-- NINGUN DROP CHECK. Los cuatro CHECK de V36 —responsable, estado, saldo, importe
-- positivo— y el de anulacion completa quedan exactamente como estaban.

ALTER TABLE obligacion
    MODIFY COLUMN sesion_id BIGINT NULL
        COMMENT 'La prestacion que origino la deuda. ANULABLE desde AKINE-08.06: una obligacion por compra de pack no tiene sesion. Sigue siendo NOT NULL de hecho para origen = SESION, y ck_obligacion_origen lo hace cumplir.';

ALTER TABLE obligacion
    ADD COLUMN origen VARCHAR(16) NOT NULL DEFAULT 'SESION'
        COMMENT 'Que hecho devengo la deuda. El DEFAULT es lo que hace que la expansion no toque ninguna fila existente: toda obligacion anterior a esta migracion nacio de una sesion. VENTA_ABONO se declara hoy y NO se emite, para que 08.08 no tenga que hacer DROP CHECK.'
        AFTER sesion_id;

ALTER TABLE obligacion
    ADD COLUMN pase_id BIGINT NULL
        COMMENT 'El pase comprado que origino la deuda (RF-M18-009). NULL para origen = SESION.'
        AFTER origen;

ALTER TABLE obligacion
    ADD CONSTRAINT fk_obligacion_pase
        FOREIGN KEY (pase_id) REFERENCES pase_servicio (id);

-- La idempotencia del devengo por venta, gemela de uk_obligacion_prestacion. Es el
-- cinturon del lado de billing: aunque manana alguien escribiera un segundo camino de
-- devengo por venta sin pasar por DevengoDeVentas, NO puede crear dos deudas por el mismo
-- pase. deleted_key ya existe en la tabla desde V36.
ALTER TABLE obligacion
    ADD CONSTRAINT uk_obligacion_venta
        UNIQUE (pase_id, responsable, deleted_key);

-- Exactamente un origen, y el origen concuerda con la columna que lo materializa. Sin
-- esto, una fila con origen = VENTA_PASE y sesion_id lleno seria una deuda que dice venir
-- de dos hechos distintos, y las dos consultas de idempotencia la contarian.
ALTER TABLE obligacion
    ADD CONSTRAINT ck_obligacion_origen
        CHECK (
            (origen = 'SESION' AND sesion_id IS NOT NULL AND pase_id IS NULL)
                OR (origen IN ('VENTA_PASE', 'VENTA_ABONO') AND sesion_id IS NULL AND pase_id IS NOT NULL)
            );


-- =====================================================================================
-- 5. oferta_servicio_consultorio — la politica de devengo (RN-M27-006, RF-M18-008)
-- =====================================================================================
--
-- V24 dejo esquema_cobro como VARCHAR(32) sin lista cerrada, declarandolo "DATO DECLARADO,
-- NO RESUELTO... el vocabulario lo fija M16/M18 y esos modulos no existen". M18 existe
-- desde 07.01 y M29 es esta etapa: SE CIERRA ACA.
--
-- La pregunta que 08.03 delego —cuando se devenga el cargo de una clase, y si el no-show
-- cobra— NO se responde en esta migracion. Se convierte en dos valores de configuracion,
-- para que las tres respuestas posibles sean tres filas distintas y no tres disenos.

-- PASO 1: normalizar. Cualquier valor fuera del vocabulario pasa a NULL ANTES de agregar
-- el CHECK, porque la columna fue texto libre durante seis etapas. En esta rama la
-- sentencia no va a tocar ninguna fila —ninguna migracion de F5 en adelante se aplico
-- jamas contra un motor— pero escribirla es lo que hace que la migracion sea segura en un
-- despliegue donde si haya datos.
UPDATE oferta_servicio_consultorio
SET esquema_cobro = NULL
WHERE esquema_cobro IS NOT NULL
  AND esquema_cobro NOT IN ('POR_SESION', 'POR_CLASE', 'POR_PACK', 'POR_ABONO');

ALTER TABLE oferta_servicio_consultorio
    ADD COLUMN momento_devengo VARCHAR(16) NULL
        COMMENT 'Cuando nace la deuda de esta oferta: ASISTENCIA, INSCRIPCION o VENTA. NULL si y solo si esquema_cobro es NULL. Para POR_CLASE admite ASISTENCIA o INSCRIPCION, y ESA es la decision que el usuario toma: una fila, no un rediseno.'
        AFTER esquema_cobro;

ALTER TABLE oferta_servicio_consultorio
    ADD COLUMN devenga_no_show TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'Si una ausencia devenga igual. Solo lo lee la rama momento_devengo = ASISTENCIA. Default 0 porque cobrar un no-show es una politica de centro y aplicarla por defecto seria decidirla por el usuario — es textual del devengador de 07.01.'
        AFTER momento_devengo;

-- PASO 2: backfill del momento a partir del esquema, para las ofertas que ya lo declaraban
-- con un valor del vocabulario. POR_SESION y POR_PACK/POR_ABONO tienen un solo momento
-- posible; POR_CLASE NO se backfillea, porque elegir entre ASISTENCIA e INSCRIPCION es
-- justamente lo que no se decide sin el usuario.
UPDATE oferta_servicio_consultorio
SET momento_devengo = 'ASISTENCIA'
WHERE esquema_cobro = 'POR_SESION';

UPDATE oferta_servicio_consultorio
SET momento_devengo = 'VENTA'
WHERE esquema_cobro IN ('POR_PACK', 'POR_ABONO');

-- PASO 3: cerrar el vocabulario. SE AGREGAN DOS CHECK NUEVOS; ninguno de los de V24 se
-- toca —modalidad, duracion, capacidad, precio/moneda, baja completa siguen intactos—.
ALTER TABLE oferta_servicio_consultorio
    ADD CONSTRAINT ck_oferta_esquema_cobro
        CHECK (esquema_cobro IS NULL
            OR esquema_cobro IN ('POR_SESION', 'POR_CLASE', 'POR_PACK', 'POR_ABONO'));

-- La coherencia entre esquema y momento. Sin esto, POR_PACK con momento ASISTENCIA seria
-- una oferta que cobra el pack Y ademas cada clase: el doble cobro que RN-M29-007 prohibe
-- para el abono y que nadie querria para el pack.
ALTER TABLE oferta_servicio_consultorio
    ADD CONSTRAINT ck_oferta_politica_devengo
        CHECK (
            (esquema_cobro IS NULL AND momento_devengo IS NULL)
                OR (esquema_cobro = 'POR_SESION' AND momento_devengo = 'ASISTENCIA')
                OR (esquema_cobro = 'POR_CLASE' AND momento_devengo IN ('ASISTENCIA', 'INSCRIPCION'))
                OR (esquema_cobro IN ('POR_PACK', 'POR_ABONO') AND momento_devengo = 'VENTA')
            );
