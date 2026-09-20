-- =====================================================================================
-- AKINE-07.03 — Caja diaria y movimientos reales (M20).
--
-- Trazabilidad: RF-M20-001 (abrir), RF-M20-002 (ingreso manual), RF-M20-003 (egreso),
-- RF-M20-004 (consultar movimientos), RF-M20-005 (saldo teorico), RF-M20-006 (cerrar con
-- saldo declarado y diferencia), RF-M20-007 (cierres historicos), RF-M24-006 (relacionar
-- operacion original y reversion); RN-M20-001 (la deuda NO afecta la caja), RN-M20-002
-- (solo movimientos monetarios confirmados afectan el saldo), RN-M20-003 (una caja cerrada
-- no se edita en silencio), RN-M20-004 (toda diferencia queda registrada y justificada);
-- DP-06.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0005 (Problem Details), ADR-0011 (baja logica).
--
-- ---------------------------------------------------------------------------------------
-- LA REGLA QUE ESTA ETAPA EXISTE PARA NO ROMPER: `COBRO != CAJA`
--
-- Un COBRO es un hecho COMERCIAL: alguien pago una obligacion. Un MOVIMIENTO DE CAJA es un
-- hecho MONETARIO: entro o salio plata de un lugar fisico, en una jornada, con un
-- responsable. No son lo mismo y LA RELACION NO ES UNO A UNO:
--
--   * un cobro con dos medios produce DOS movimientos;
--   * un cobro integramente con tarjeta produce un movimiento que NO afecta el arqueo;
--   * un ingreso manual produce un movimiento SIN cobro;
--   * una reversion produce un movimiento SIN cobro nuevo.
--
-- Por eso `cobro` no gana ni una columna en esta migracion y `movimiento_caja` NO TIENE
-- `obligacion_id`: la caja registra que entro plata, no por que se debia. Si la relacion
-- fuera uno a uno, el modelo estaria confesando que caja y cobro son la misma cosa — que es
-- exactamente el error del UML de 2019, donde Turno, Atencion y Cobro eran una sola tabla.
--
-- ---------------------------------------------------------------------------------------
-- LAS SEIS COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. LA CAJA ES LA SEDE Y LA UNIDAD OPERATIVA ES LA JORNADA. Una caja por responsable
--    exigiria un cajon fisico por persona, y en un centro el mostrador es uno y lo atienden
--    tres. Una caja por organizacion sumaria dos cajones que estan a 40 km. Entonces la
--    jornada cuelga de `(organization_id, consultorio_id)` y hay A LO SUMO UNA ABIERTA por
--    sede — lo hace cumplir la BASE, no un `if`: la columna generada `abierta_marca` vale 1
--    mientras el estado es ABIERTA y NULL cuando no, y varios NULL no colisionan en MySQL.
--    Es el mismo mecanismo que `deleted_key` (V30) y `owner_key` (ADR-0021), usado al
--    derecho. Al ser GENERADA no puede desincronizarse de `estado`.
--
--    Varias jornadas POR FECHA si son legitimas: manana y tarde, con responsables distintos,
--    arquean dos veces. Por eso NO hay unique sobre `fecha_negocio`.
--
-- 2. SOLO EL EFECTIVO AFECTA EL ARQUEO, Y LO DECIDE UNA COLUMNA GENERADA. Un arqueo es
--    CONTAR BILLETES. Una tarjeta de credito liquida en 18 dias a una cuenta que el sistema
--    no modela; una transferencia entra a un banco. Sumarlas al saldo del cajon garantiza
--    que el conteo no cuadre NUNCA, y una caja que nunca cuadra deja de ser un control.
--
--    Pero excluirlas del REGISTRO haria que la pantalla mintiera sobre el dia: RF-M20-004
--    pide listar la operatoria diaria, no listar efectivo. Entonces se registran todas y
--    `afecta_arqueo` —GENERADA a partir del medio, no seteada por nadie— decide cuales
--    mueven el saldo. Un medio nuevo hereda la regla sin tocar una linea de codigo.
--
-- 3. `jornada_caja_id` ES NULLABLE, Y ESA NULIDAD ES UNA DECISION. Si no hay jornada
--    abierta y alguien cobra EN EFECTIVO, el cobro se RECHAZA (409 `caja-no-abierta`): la
--    plata entra al cajon igual, y si el sistema no sabe a que jornada pertenece, el arqueo
--    de ese dia no cuadra contra nada. Abrir la caja son diez segundos; recuperar un arqueo
--    roto no.
--
--    Si el medio NO es efectivo, esa plata nunca toco el cajon y no hay nada que exigir: el
--    movimiento se asienta con `jornada_caja_id` NULL, su propia `fecha_negocio` y
--    `afecta_arqueo = 0`. Sigue apareciendo en la operatoria del dia y no participa de
--    ningun arqueo. Es tambien la respuesta al caso borde "movimiento tardio".
--
--    El `CHECK` que lo sostiene se escribe sobre `medio` y NO sobre la columna generada, a
--    proposito: no depender de la semantica de generadas dentro de un CHECK.
--
-- 4. EL SALDO SE MATERIALIZA Y SE MUEVE CON UNA CONDICION, NO CON UN LOCK.
--    `jornada_caja.saldo_arqueo` arranca igual al saldo inicial y se mueve en la MISMA
--    transaccion que cada movimiento que afecta el arqueo. El ledger es la fuente de verdad
--    y la columna es la respuesta rapida y el guardian — mismo criterio que el saldo de la
--    obligacion en V36 y que `cantidad_consumida` en V50, y vale por la misma razon: la
--    columna y las filas son del MISMO modulo y de la MISMA transaccion, asi que alguien
--    puede garantizar su coherencia.
--
--    El egreso se escribe asi, y la condicion es todo el diseno:
--
--      UPDATE jornada_caja SET saldo_arqueo = saldo_arqueo - :importe
--       WHERE id = :id AND organization_id = :org AND estado = 'ABIERTA'
--         AND saldo_arqueo >= :importe
--
--    CERO FILAS es la respuesta —la jornada cerro, o no alcanza la plata—, no un error
--    tecnico. Sin lock, sin lectura previa, sin ventana entre leer y escribir y SIN DEADLOCK
--    POSIBLE. Es literalmente lo que 07.02 hizo para imputar y 04.05 para consumir.
--    `ck_jornada_saldo_no_negativo` lo respalda: UN CAJON NO PUEDE TENER MENOS DE CERO PESOS,
--    y si un camino futuro se olvida del WHERE, la base lo frena.
--
--    NO HAY COLUMNA `version` EN `jornada_caja`, Y ES DELIBERADO. Esas escrituras son SQL
--    nativo y no la incrementarian: un token optimista que no avanza cuando el dato cambia
--    promete una proteccion que no da. Hacerlas pasar por JPA exigiria cargar la entidad
--    antes —la lectura previa que la condicion evita— o un force-increment sobre un padre
--    que ademas queda sucio, que es la trampa que 04.02 pago: la version avanzaria dos veces
--    y el cliente comeria un 409 del que no puede salir.
--
-- 5. EL CIERRE ES UN SOLO UPDATE CONDICIONAL, Y LA CONDICION QUE IMPORTA NO ES EL ESTADO.
--
--      UPDATE jornada_caja
--         SET estado = 'CERRADA', saldo_teorico_cierre = saldo_arqueo,
--             saldo_declarado = :declarado, diferencia = :declarado - saldo_arqueo, ...
--       WHERE id = :id AND estado = 'ABIERTA' AND saldo_arqueo = :saldoTeoricoEsperado
--
--    `estado = 'ABIERTA'` resuelve el cierre concurrente: el segundo afecta cero filas.
--    `saldo_arqueo = :saldoTeoricoEsperado` resuelve EL CASO QUE ROMPE EL DISENO, que es el
--    cobro en efectivo que entra ENTRE QUE EL OPERADOR CUENTA Y CONFIRMA. Sin esa condicion
--    el cierre registraria un faltante QUE NUNCA EXISTIO, y RN-M20-004 obligaria a escribir
--    un motivo para justificar un desvio inventado — y la plata ademas esta fisicamente en
--    el cajon, asi que la jornada siguiente tambien arrancaria mal. Un control que produce
--    falsos positivos deja de leerse.
--
--    Con la condicion: cero filas, 409 con el teorico actual, el operador suma los billetes
--    que estan ahi y confirma. Es control optimista sobre LA CANTIDAD QUE SIGNIFICA ALGO en
--    vez de sobre un numero de version.
--
--    `saldo_teorico_cierre` se congela aunque sea igual a `saldo_arqueo` en ese instante: un
--    cierre historico tiene que poder explicarse dentro de seis meses sin recalcular nada,
--    igual que el snapshot de precio de V36.
--
-- 6. LA DIFERENCIA SE REGISTRA. NO SE RECHAZA EL CIERRE Y NO SE AJUSTA.
--    Rechazar el cierre dejaria al centro sin poder cerrar el dia en que REALMENTE falta
--    plata, que es el dia en que el registro importa. Ajustar con un movimiento que iguale
--    borraria el hecho: el ledger pasaria a sumar el conteo declarado y la diferencia
--    desapareceria de los movimientos, o sea un numero que se corrige solo.
--
--    `motivo_diferencia` es OBLIGATORIO con diferencia distinta de cero y PROHIBIDO con
--    diferencia cero, por CHECK. Lo segundo no es purismo: un motivo que a veces adorna un
--    cierre correcto deja de leerse en los cierres que si importan.
--
--    Y LA DIFERENCIA NO ES UN MOVIMIENTO. Si lo fuera, la jornada siguiente partiria de un
--    ledger que ya "explico" el faltante y el hecho se autodestruiria. La jornada siguiente
--    declara su propio saldo inicial, contado por quien la abre.
--
-- ---------------------------------------------------------------------------------------
-- APPEND-ONLY: LO QUE `movimiento_caja` NO TIENE ES EL DISENO
--
-- Sin `version`, sin `updated_at`, sin `active`/`deleted_at`, y el puerto de persistencia NO
-- declara `update` ni `delete`. Un historial que se puede editar no es un historial. Mismo
-- diseno y mismo argumento que `turno_evento` (V38), `caso_evento` (V47), `plan_evento`
-- (V49) y `autorizacion_movimiento` (V50), y es la regla maestra 10 aplicada.
--
-- Corolario: LA CORRECCION ES COMPENSACION. Un movimiento erroneo se anula con otro de tipo
-- REVERSION_DE_INGRESO o REVERSION_DE_EGRESO, con motivo obligatorio y puntero al original.
--
-- Y LA COMPENSACION CAE EN LA JORNADA ABIERTA HOY, NUNCA REESCRIBIENDO LA CERRADA. Es la
-- decision menos obvia de la etapa y la mas correcta: la jornada original YA FUE ARQUEADA, y
-- si el error afecto el conteo, su `diferencia` ya lo registro. Reescribirla haria que su
-- saldo declarado dejara de coincidir con lo que efectivamente se conto, destruyendo la unica
-- evidencia de que hubo un desvio. Ademas, fisicamente, la plata sale del cajon HOY.
--
-- `movimiento_origen_id` cruza jornadas y eso es lo que RF-M24-006 pide: relacionar operacion
-- original y reversion.
--
-- ---------------------------------------------------------------------------------------
-- `importe` ES SIEMPRE POSITIVO: EL SIGNO LO DA EL `tipo`
--
-- Un ledger con numeros negativos obliga a todo lector a conocer el signo de cada tipo para
-- no sumar al reves. V50 lo fijo y se respeta: la cantidad es siempre positiva y la suma es
-- `SUM(CASE WHEN tipo IN ('INGRESO','REVERSION_DE_EGRESO') THEN importe ELSE -importe END)`.
-- =====================================================================================

CREATE TABLE jornada_caja
(
    id                     BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id        BIGINT         NOT NULL,
    consultorio_id         BIGINT         NOT NULL
        COMMENT 'UNA CAJA PERTENECE A UNA SEDE. Punto 1 de la cabecera.',

    -- Derivada, no pedida: se calcula al abrir con el instante actual proyectado a la zona
    -- IANA de la sede (`consultorio.timezone`, V16, cuyo propio comentario ya nombra el
    -- "corte de caja"). No se puede abrir una jornada para ayer: eso es un ajuste contable
    -- disfrazado de operacion.
    fecha_negocio          DATE           NOT NULL,

    moneda                 CHAR(3)        NOT NULL
        COMMENT 'Fija al abrir. Un arqueo que suma pesos con dolares no se puede contar.',

    estado                 VARCHAR(16)    NOT NULL COMMENT 'ABIERTA o CERRADA. No hay reapertura.',

    saldo_inicial          DECIMAL(12, 2) NOT NULL
        COMMENT 'Lo que habia en el cajon al abrir, declarado por quien abre.',

    -- Punto 4. Derivado del ledger pero materializado, y es tambien el guardian del egreso.
    saldo_arqueo           DECIMAL(12, 2) NOT NULL
        COMMENT 'Saldo EN EFECTIVO. Arranca igual al inicial y solo lo mueven los movimientos con afecta_arqueo = 1.',

    abierta_en             DATETIME(6)    NOT NULL,
    abierta_por_cuenta_id  BIGINT         NOT NULL,

    -- Punto 5 y 6. Los cinco viajan juntos o ninguno.
    cerrada_en             DATETIME(6)    NULL,
    cerrada_por_cuenta_id  BIGINT         NULL,
    saldo_teorico_cierre   DECIMAL(12, 2) NULL COMMENT 'Congelado al cerrar. Un cierre historico se explica sin recalcular.',
    saldo_declarado        DECIMAL(12, 2) NULL COMMENT 'Lo que se conto fisicamente.',
    diferencia             DECIMAL(12, 2) NULL COMMENT 'declarado - teorico. Positiva sobra, negativa falta. La calcula el servidor.',
    motivo_diferencia      VARCHAR(280)   NULL,

    -- Punto 1. NULL cuando la jornada esta cerrada, y varios NULL no colisionan en MySQL:
    -- eso es lo que convierte el unique de abajo en "a lo sumo una abierta por sede".
    abierta_marca          TINYINT AS (IF(estado = 'ABIERTA', 1, NULL)) STORED,

    created_at             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- Punto 1. NO hay unique sobre fecha_negocio: manana y tarde son dos jornadas del mismo dia.
    CONSTRAINT uk_jornada_caja_abierta
        UNIQUE (organization_id, consultorio_id, abierta_marca),

    CONSTRAINT fk_jornada_caja_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_jornada_caja_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT ck_jornada_caja_estado
        CHECK (estado IN ('ABIERTA', 'CERRADA')),

    CONSTRAINT ck_jornada_caja_saldo_inicial
        CHECK (saldo_inicial >= 0),

    -- Punto 4. Un cajon no puede tener menos de cero pesos.
    CONSTRAINT ck_jornada_caja_saldo_no_negativo
        CHECK (saldo_arqueo >= 0),

    -- Cierre completo: los cinco campos o ninguno. Sin esto una fila a medio cerrar pasaria
    -- por abierta para unas consultas y por cerrada para otras. Mismo criterio que
    -- `ck_obligacion_anulacion_completa` en V36.
    CONSTRAINT ck_jornada_caja_cierre_completo
        CHECK ((estado = 'ABIERTA'
                AND cerrada_en IS NULL AND cerrada_por_cuenta_id IS NULL
                AND saldo_teorico_cierre IS NULL AND saldo_declarado IS NULL
                AND diferencia IS NULL AND motivo_diferencia IS NULL)
            OR (estado = 'CERRADA'
                AND cerrada_en IS NOT NULL AND cerrada_por_cuenta_id IS NOT NULL
                AND saldo_teorico_cierre IS NOT NULL AND saldo_declarado IS NOT NULL
                AND diferencia IS NOT NULL)),

    -- Punto 6. RN-M20-004 hecho cumplir por el esquema: motivo obligatorio con diferencia,
    -- y prohibido sin ella.
    CONSTRAINT ck_jornada_caja_motivo_de_diferencia
        CHECK (diferencia IS NULL
            OR (diferencia = 0 AND motivo_diferencia IS NULL)
            OR (diferencia <> 0 AND motivo_diferencia IS NOT NULL)),

    CONSTRAINT ck_jornada_caja_saldo_declarado
        CHECK (saldo_declarado IS NULL OR saldo_declarado >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Turno de caja de una sede. No es el cobro (M19) ni la deuda (M18).';


-- RF-M20-007: los cierres historicos de una sede, por fecha de negocio.
CREATE INDEX ix_jornada_caja_sede_fecha
    ON jornada_caja (organization_id, consultorio_id, fecha_negocio, estado);


-- -------------------------------------------------------------------------------------
-- movimiento_caja — el ledger. APPEND-ONLY: ver la cabecera.
-- -------------------------------------------------------------------------------------
CREATE TABLE movimiento_caja
(
    id                      BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Desnormalizado igual que `cobro_medio` en V37: ADR-0004 dice "sin excepcion" y es
    -- precisamente por eso —una consulta que se olvide del JOIN cruzaria tenants sin fallar—.
    -- Y aca hay un motivo extra: `jornada_caja_id` ES NULLABLE, asi que para los movimientos
    -- no-efectivo sin jornada esta columna no es una redundancia, es el unico camino al tenant.
    organization_id         BIGINT         NOT NULL,
    consultorio_id          BIGINT         NOT NULL,

    -- Punto 3. NULL = plata que nunca toco el cajon.
    jornada_caja_id         BIGINT         NULL,

    -- Propia, no heredada de la jornada: es lo que permite listar la operatoria de un dia que
    -- incluye movimientos sin jornada, y lo que hace que "movimiento tardio" tenga respuesta.
    fecha_negocio           DATE           NOT NULL,

    tipo                    VARCHAR(24)    NOT NULL
        COMMENT 'INGRESO, EGRESO, REVERSION_DE_INGRESO o REVERSION_DE_EGRESO. El signo lo da el tipo, no el numero.',

    medio                   VARCHAR(24)    NOT NULL
        COMMENT 'Mismo catalogo que cobro_medio (V37). Decide afecta_arqueo.',

    importe                 DECIMAL(12, 2) NOT NULL COMMENT 'SIEMPRE POSITIVO. Ver la cabecera.',
    moneda                  CHAR(3)        NOT NULL,

    concepto                VARCHAR(160)   NULL COMMENT 'Que fue. Obligatorio en los manuales, lo pone el sistema en los automaticos.',
    motivo                  VARCHAR(280)   NULL COMMENT 'Obligatorio en las reversiones: una plata que se saca sin explicacion es lo que una auditoria busca.',

    -- Que clase de hecho lo produjo, y su id dentro de esa clase. Es la mitad del unique que
    -- hace idempotente el reintento del cobro y que impide revertir dos veces.
    tipo_origen             VARCHAR(16)    NOT NULL COMMENT 'COBRO, MANUAL o REVERSION.',
    referencia_origen       BIGINT         NULL
        COMMENT 'cobro.id cuando es COBRO; movimiento_caja.id cuando es REVERSION; NULL cuando es MANUAL —y varios NULL no colisionan, que es justo lo que se quiere—.',

    -- Trazabilidad directa de RF-M24-006. Sin el, ligar la reversion a su movimiento obligaria
    -- a reconstruirlo por tipo_origen + referencia_origen. CRUZA JORNADAS a proposito.
    movimiento_origen_id    BIGINT         NULL,

    registrado_en           DATETIME(6)    NOT NULL,
    registrado_por_cuenta_id BIGINT        NOT NULL,

    -- Solo para los MANUALES: un cobro ya es idempotente por su propia clave (V37) y una
    -- reversion lo es por el unique. Mismo par que `cobro`: sin el hash no se puede distinguir
    -- un reintento de un reuso de clave con otro contenido.
    idempotency_key         VARCHAR(80)    NULL,
    request_hash            CHAR(64)       NULL,

    -- Punto 2. GENERADA: no puede divergir del medio, y un medio nuevo hereda la regla.
    afecta_arqueo           TINYINT AS (medio = 'EFECTIVO') STORED NOT NULL,

    created_at              DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    -- El reintento del cobro no duplica el movimiento, y un movimiento se revierte UNA SOLA
    -- VEZ. `tipo` entra en la clave A PROPOSITO, igual que en V50: la reversion del mismo
    -- origen es OTRA fila y tiene que poder existir al lado del movimiento que compensa.
    -- `medio` tambien: un cobro con efectivo y tarjeta son dos movimientos del mismo cobro.
    CONSTRAINT uk_movimiento_caja_origen
        UNIQUE (organization_id, tipo, tipo_origen, referencia_origen, medio),

    -- Igual que `cobro` en V37: un doble click no carga dos veces el mismo ingreso manual.
    CONSTRAINT uk_movimiento_caja_idempotencia
        UNIQUE (organization_id, idempotency_key),

    CONSTRAINT fk_movimiento_caja_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_movimiento_caja_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_movimiento_caja_jornada
        FOREIGN KEY (jornada_caja_id) REFERENCES jornada_caja (id),

    CONSTRAINT fk_movimiento_caja_origen
        FOREIGN KEY (movimiento_origen_id) REFERENCES movimiento_caja (id),

    CONSTRAINT ck_movimiento_caja_tipo
        CHECK (tipo IN ('INGRESO', 'EGRESO', 'REVERSION_DE_INGRESO', 'REVERSION_DE_EGRESO')),

    CONSTRAINT ck_movimiento_caja_medio
        CHECK (medio IN ('EFECTIVO', 'TRANSFERENCIA', 'TARJETA_DEBITO', 'TARJETA_CREDITO', 'OTRO')),

    CONSTRAINT ck_movimiento_caja_tipo_origen
        CHECK (tipo_origen IN ('COBRO', 'MANUAL', 'REVERSION')),

    CONSTRAINT ck_movimiento_caja_importe_positivo
        CHECK (importe > 0),

    -- Punto 3. El efectivo SIEMPRE pertenece a una jornada; lo demas puede no pertenecer a
    -- ninguna. Se escribe sobre `medio` y no sobre la columna generada a proposito.
    CONSTRAINT ck_movimiento_caja_efectivo_con_jornada
        CHECK (medio <> 'EFECTIVO' OR jornada_caja_id IS NOT NULL),

    -- Una reversion sin motivo y sin puntero al original seria un movimiento suelto que nadie
    -- puede explicar. RF-M24-006 pide exactamente poder relacionarlos.
    CONSTRAINT ck_movimiento_caja_reversion_completa
        CHECK (tipo NOT IN ('REVERSION_DE_INGRESO', 'REVERSION_DE_EGRESO')
            OR (motivo IS NOT NULL AND movimiento_origen_id IS NOT NULL
                AND tipo_origen = 'REVERSION')),

    -- Y a la inversa: solo una reversion apunta a un original.
    CONSTRAINT ck_movimiento_caja_origen_solo_en_reversion
        CHECK (movimiento_origen_id IS NULL
            OR tipo IN ('REVERSION_DE_INGRESO', 'REVERSION_DE_EGRESO')),

    CONSTRAINT ck_movimiento_caja_idempotencia_completa
        CHECK ((idempotency_key IS NULL AND request_hash IS NULL)
            OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Movimiento monetario REAL (RN-M20-002). Append-only: se compensa, no se edita.';


-- La vista operativa de una jornada: sus movimientos en orden. RF-M20-004.
CREATE INDEX ix_movimiento_caja_jornada
    ON movimiento_caja (organization_id, jornada_caja_id, registrado_en);

-- La operatoria de un dia de la sede, con o sin jornada. RF-M20-004 y el "movimiento tardio".
CREATE INDEX ix_movimiento_caja_sede_fecha
    ON movimiento_caja (organization_id, consultorio_id, fecha_negocio, tipo);

-- RF-M24-006: de un movimiento a su reversion.
CREATE INDEX ix_movimiento_caja_origen
    ON movimiento_caja (movimiento_origen_id);
