-- =====================================================================================
-- AKINE-04.05 — Consumo de autorizaciones: el ledger que mueve `cantidad_consumida`.
--
-- Trazabilidad: RF-M17-004 (consumir al atender), RF-M17-005 (revertir el consumo con
-- motivo), RF-M17-003 (consultar saldo), RF-M17-006 (alertar vencimiento/agote),
-- RF-M17-007 (elegibilidad administrativa, ahora con saldo y vigencia), RF-M11-007
-- (vincular la autorizacion al item del plan); RN-M17-001 (autorizado != consumido),
-- RN-M17-002 (el saldo nunca es negativo), RN-M17-008 (la reversion compensa, no borra);
-- DP-05 (turno != sesion: ninguna transicion administrativa prueba una prestacion),
-- DP-06 (cerrar una sesion no cobra ni condiciona el cierre a lo economico).
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0005 (Problem Details), ADR-0007 (expandir-migrar-contraer).
--
-- ---------------------------------------------------------------------------------------
-- POR QUE UNA TABLA Y NO UN `UPDATE` A LA COLUMNA QUE YA EXISTE
--
-- `autorizacion.cantidad_consumida` existe desde V44 y nadie la mueve. La forma mas corta de
-- cerrar RF-M17-004 seria incrementarla al cerrar la sesion y listo. Eso deja un NUMERO SIN
-- HISTORIA: nadie puede decir QUE la consumio, CUANDO, ni deshacer una sola unidad sin
-- recalcular a mano. Y RF-M17-005 pide exactamente lo contrario — revertir un consumo
-- concreto, con motivo.
--
-- Entonces el hecho se asienta como FILA y la columna queda como SALDO MATERIALIZADO,
-- escrito en la MISMA transaccion. El ledger es la fuente de verdad; la columna es la
-- respuesta rapida a "cuanto queda" que la elegibilidad de 03.06 ya consume.
--
-- Esto PARECE contradecir a 04.04, que se nego a guardar `cantidad_realizada` en `plan_item`,
-- y no la contradice. Alla el dueño del hecho era OTRO modulo —solo `encounter` sabe cuando
-- se cierra una sesion— y la columna habria sido de `clinical`: un contador que solo un
-- vecino puede mantener correcto. Aca el ledger y la autorizacion son la MISMA tabla del
-- MISMO modulo: se escriben juntos o no se escribe ninguno. Es el mismo criterio con el que
-- V36 materializo el saldo de la obligacion. La diferencia no es de gusto, es QUIEN PUEDE
-- GARANTIZAR LA COHERENCIA DENTRO DE UNA TRANSACCION.
--
-- ---------------------------------------------------------------------------------------
-- APPEND-ONLY: LO QUE ESTA TABLA NO TIENE ES EL DISEÑO
--
-- Sin `version`, sin `updated_at`, sin `active`/`deleted_at`, y el puerto de persistencia NO
-- declara `update` ni `delete`. Un historial que se puede editar no es un historial. Mismo
-- diseño y mismo argumento que `turno_evento` (V38), `caso_evento` (V47) y `plan_evento`
-- (V49), y es la regla maestra 10 aplicada: la informacion historica relevante no se elimina.
--
-- Corolario: LA REVERSION NO BORRA NADA. Compensa con una fila propia de tipo REVERSION —con
-- motivo obligatorio— y devuelve el saldo con el UPDATE inverso, en la misma transaccion. El
-- consumo original queda donde estaba, diciendo que ocurrio.
--
-- ---------------------------------------------------------------------------------------
-- EL SALDO NUNCA NEGATIVO LO DECIDE LA BASE, NO UN `if`
--
-- El consumo se escribe asi, y la condicion del WHERE es toda la idea:
--
--   UPDATE autorizacion
--      SET cantidad_consumida = cantidad_consumida + :n
--    WHERE id = :id AND organization_id = :org
--      AND cantidad_autorizada - cantidad_consumida >= :n
--
-- CERO FILAS AFECTADAS significa "no hay saldo", y lo decide el motor. No hace falta leer
-- antes —que es donde se cuela la ventana entre lectura y escritura— y NO SE TOMA NINGUN
-- LOCK, asi que no hay deadlock posible. Es literalmente lo que 07.02 hizo para imputar un
-- cobro (`SET saldo = saldo - :importe WHERE saldo >= :importe`), y resuelve el caso borde
-- que el plan nombra: "ultima unidad concurrente". Dos sesiones peleando por la ultima unidad:
-- una gana, la otra ve cero filas.
--
-- Por eso esta tabla NO tiene fila-lock propia y NO reusa `autorizacion_persona_lock`: aquel
-- serializa las APROBACIONES, que es una invariante de intervalos que ningun unique expresa.
-- Esta es una resta que no puede pasar de cero, y eso lo expresa una condicion.
--
-- `ck_autorizacion_consumo_coherente` de V44 sigue siendo la red de seguridad del motor: si
-- alguna vez alguien escribe el decremento por otro camino, choca ahi en vez de dejar saldo
-- fantasma.
--
-- ---------------------------------------------------------------------------------------
-- LA IDEMPOTENCIA ES UN UNIQUE, NO UN CHEQUEO PREVIO
--
--   UNIQUE (organization_id, autorizacion_id, tipo, tipo_origen, referencia_origen)
--
-- Cerrar dos veces la misma sesion es idempotente por RN-M14-005, asi que el consumo tambien
-- tiene que serlo. El reintento del mismo cierre encuentra su propia fila y se responde con
-- ella, no con un 409 ni con un segundo descuento.
--
-- `tipo` entra en la clave A PROPOSITO: una REVERSION del mismo origen es OTRA fila y tiene
-- que poder entrar; una SEGUNDA reversion del mismo origen si es un duplicado y choca. Eso es
-- lo que hace a la reversion idempotente en vez de meramente segura.
--
-- `organization_id` encabeza el unique aunque sea derivable de la autorizacion, por el mismo
-- motivo de V45, V47 y V49: derivarlo obliga a un join para filtrar por tenant y habilita la
-- consulta sin join que ningun test de una etapa detecta, porque los tests de una etapa usan
-- un solo tenant.
--
-- ---------------------------------------------------------------------------------------
-- CUATRO TIPOS, Y DOS NO LAS EMITE NADIE TODAVIA
--
-- CONSUMO y REVERSION son las que esta etapa produce. RESERVA y LIBERACION_DE_RESERVA existen
-- en el CHECK y NINGUN CAMINO LAS EMITE: el modelo las contempla para no tener que migrarlo
-- el dia que exista la pre-reserva de unidades al agendar. Se corta el ALCANCE, no el MODELO
-- —mismo criterio con el que V49 dejo `origen_autorizacion = 'AUTORIZACION'` declarado y sin
-- escribirse—.
--
-- Y hay una razon dura para que la reserva NO exista hoy: DP-05. NO SE CONSUME AL RESERVAR UN
-- TURNO. Ninguna transicion administrativa prueba que una prestacion ocurrio, y un turno que
-- despues se cancela habria comido una unidad que el paciente nunca uso. Se consume AL CERRAR
-- LA SESION, que es el hecho que si la prueba.
--
-- ---------------------------------------------------------------------------------------
-- `cantidad` ES SIEMPRE POSITIVA: EL SIGNO LO DA EL `tipo`
--
-- Un ledger con numeros negativos obliga a todo lector a saber el signo de cada tipo para
-- sumar, y el primero que se olvide produce un saldo que nadie entiende. Con la cantidad
-- siempre positiva, la suma es `SUM(CASE WHEN tipo IN ('CONSUMO','RESERVA') THEN cantidad
-- ELSE -cantidad END)` escrita UNA vez y el resto de las consultas leen el numero tal cual.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE ESTA MIGRACION NO RESUELVE Y HAY QUE SABER
--
-- Si el UPDATE del saldo y el INSERT del movimiento divergieran alguna vez —por un camino que
-- escriba uno y no el otro— NADA LO DETECTA: no hay nadie recalculando la columna desde el
-- ledger. La mitigacion posible es un test de integracion que, despues de N movimientos,
-- compare la suma del ledger contra la columna, y NO ESTA IMPLEMENTADA porque Docker no
-- arranca en esta maquina. Queda escrito como lo primero a cubrir cuando haya motor.
-- =====================================================================================

CREATE TABLE autorizacion_movimiento
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id   BIGINT       NOT NULL COMMENT 'Tenant propietario. Derivable de la autorizacion y guardado igual: sin el, filtrar por tenant exige un join y habilita la consulta sin join que ningun test de una sola organizacion detecta',
    autorizacion_id   BIGINT       NOT NULL COMMENT 'Autorizacion cuyo saldo mueve este hecho',
    persona_id        BIGINT       NOT NULL COMMENT 'Paciente. Redundante con autorizacion.persona_id y guardado igual: es la columna por la que se listan los movimientos de un paciente sin pasar por sus autorizaciones',
    consultorio_id    BIGINT       NULL COMMENT 'Sede donde ocurrio el hecho. NULL admitido: una reversion administrativa puede hacerse desde otra sede de la misma organizacion, y la autorizacion ya declara la sede que la gestiono',

    tipo              VARCHAR(24)  NOT NULL COMMENT 'CONSUMO, REVERSION, RESERVA o LIBERACION_DE_RESERVA. Las dos ultimas existen en el modelo y NINGUN camino las emite hoy: ver la cabecera',
    cantidad          INT          NOT NULL COMMENT 'Unidades que mueve el hecho. SIEMPRE POSITIVA: el signo lo da el tipo, no el numero',

    tipo_origen       VARCHAR(16)  NOT NULL COMMENT 'Que clase de hecho lo produjo. SESION hoy; MANUAL para el ajuste administrativo que no nace de una atencion',
    referencia_origen BIGINT       NOT NULL COMMENT 'Id del hecho dentro de su tipo_origen: la sesion cerrada, tipicamente. Es la mitad del unique que hace idempotente al reintento del cierre',

    motivo            VARCHAR(280) NULL COMMENT 'Por que se revirtio. OBLIGATORIO en REVERSION y lo verifica ck_movimiento_motivo_exigido: una reversion sin motivo deja al que audita sin poder distinguir un error de carga de un fraude',

    movimiento_origen_id BIGINT    NULL COMMENT 'Movimiento que esta fila compensa, cuando es una REVERSION. Trazabilidad directa: sin el, ligar la reversion a su consumo obliga a reconstruirlo por tipo_origen + referencia_origen',

    ocurrio_en        DATETIME(6)  NOT NULL COMMENT 'Instante UTC del hecho. Para el CONSUMO es el cierre de la sesion, no el momento del INSERT: el hecho es la atencion',
    actor_cuenta_id   BIGINT       NULL COMMENT 'Cuenta que produjo el hecho. Viaja tambien en el CONSUMO —es quien cerro la sesion— y por eso SesionCerrada se agrando para traerlo. NULL solo para hechos sin actor humano',

    created_at        DATETIME(6)  NOT NULL COMMENT 'Marca de insercion. NO hay updated_at: una fila de historial que declara haber sido modificada es una contradiccion',

    CONSTRAINT pk_autorizacion_movimiento PRIMARY KEY (id),

    -- LA IDEMPOTENCIA. Ver la cabecera: el reintento del mismo cierre encuentra su fila y se
    -- responde con ella; una REVERSION del mismo origen es otra fila y entra; una SEGUNDA
    -- reversion del mismo origen choca, que es lo que se quiere.
    CONSTRAINT uk_autorizacion_movimiento_origen
        UNIQUE (organization_id, autorizacion_id, tipo, tipo_origen, referencia_origen),

    CONSTRAINT fk_movimiento_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_movimiento_autorizacion FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id),
    CONSTRAINT fk_movimiento_persona      FOREIGN KEY (persona_id) REFERENCES persona (id),
    CONSTRAINT fk_movimiento_consultorio  FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_movimiento_origen       FOREIGN KEY (movimiento_origen_id) REFERENCES autorizacion_movimiento (id),

    CONSTRAINT ck_movimiento_tipo
        CHECK (tipo IN ('CONSUMO', 'REVERSION', 'RESERVA', 'LIBERACION_DE_RESERVA')),

    CONSTRAINT ck_movimiento_tipo_origen
        CHECK (tipo_origen IN ('SESION', 'MANUAL')),

    -- Cero unidades no es un movimiento: es una fila que no explica nada y que ensucia la
    -- suma del ledger. Mismo criterio que cantidad_autorizada en V44.
    CONSTRAINT ck_movimiento_cantidad_positiva
        CHECK (cantidad > 0),

    -- RF-M17-005 pide motivo al revertir, y aca lo exige la BASE y no solo el servicio: el
    -- dia que aparezca un segundo camino de reversion, la regla sigue valiendo sin que nadie
    -- tenga que acordarse de repetirla.
    CONSTRAINT ck_movimiento_motivo_exigido
        CHECK (tipo <> 'REVERSION' OR motivo IS NOT NULL),

    -- Solo una REVERSION compensa a otro movimiento. Un CONSUMO que apunte a otro movimiento
    -- seria una cadena que nadie sabe leer.
    CONSTRAINT ck_movimiento_origen_solo_en_reversion
        CHECK (movimiento_origen_id IS NULL OR tipo = 'REVERSION'),

    -- El listado de movimientos de una autorizacion, del mas viejo al mas nuevo: esto es una
    -- linea de tiempo y se lee en el orden en que ocurrio. Empieza por organization_id como
    -- todo indice del esquema (ADR-0004).
    INDEX ix_movimiento_autorizacion (organization_id, autorizacion_id, ocurrio_en),

    -- Los movimientos de un paciente, para el panel administrativo y para auditar una
    -- reversion sin tener que pasar por cada una de sus autorizaciones.
    INDEX ix_movimiento_persona (organization_id, persona_id, ocurrio_en)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Ledger APPEND-ONLY de los hechos que mueven el saldo de una autorizacion (M17, RF-M17-004/005). Es la fuente de verdad; autorizacion.cantidad_consumida es el saldo materializado y se escribe en la MISMA transaccion. La reversion COMPENSA, no borra. Propietario: modulo person';
