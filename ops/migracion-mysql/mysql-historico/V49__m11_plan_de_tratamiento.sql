-- =====================================================================================
-- AKINE-04.04 — Plan de Tratamiento (M11).
--
-- POR QUE V49
--
-- El diseño de la etapa (docs/diseno/AKINE-04.04-plan-de-tratamiento.md, seccion 12) reserva
-- V49 para estas cinco tablas ANTES de escribir codigo, y por el motivo que 02.07 y 03.01
-- pagaron caro: las dos nacieron como V26, Flyway rechaza versiones duplicadas —"Found more
-- than one migration with version N"— y la aplicacion no arranca. V47 y V48 ya las tomo
-- 04.03. Flyway no exige versiones contiguas.
--
-- Trazabilidad: RF-M11-001..008; RN-M11-001..006; reglas maestras 1 (HC != Caso != Sesion),
-- 2 (Plan != Turno != Sesion) y 10 (nada historico se borra). DP-03 / ADR-0010 (la HC es de
-- la ORGANIZACION), ADR-0011 (requisitos clinico-legales), DP-10 (se corta alcance, no
-- modelo). ADR-0003, ADR-0004, ADR-0007.
--
-- Propietario de las cinco tablas: modulo `clinical`. Ningun otro modulo las lee ni las
-- escribe (AGENT.md seccion 4, regla 1).
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19, V24, V27, V32, V40,
-- V45 y V47: ADR-0007 exige el ciclo de tres para CAMBIOS sobre datos existentes. Las cinco
-- tablas nacen vacias.
--
-- ---------------------------------------------------------------------------------------
-- 1. PLANIFICADO NO ES REALIZADO, Y POR ESO NO HAY DONDE GUARDARLO
--
-- Es RN-M11-001 y es la regla maestra 2 aplicada al lugar donde mas facil se rompe. En esta
-- migracion NO EXISTEN las columnas `cantidad_realizada` ni `cantidad_cancelada`, y la
-- ausencia es la decision central de la etapa (challenge seccion 1).
--
-- El argumento no es que un contador sea peligroso: es que su dueño real seria `encounter`
-- —el unico modulo que sabe cuando una sesion se cerro— y tendriamos una columna de
-- `clinical` que solo otro modulo puede mantener correcta. Por ahi se desincroniza un
-- contador: no por mala fe, sino porque el dueño del dato y el dueño de la fila son
-- distintos.
--
-- El avance se DERIVA al leer, contando sesiones cerradas del Caso por oferta a traves de
-- `clinical.spi.RealizadoEnElCasoProbe`, que implementa `encounter`. La ventaja concreta:
-- una sesion que se esta cerrando mientras alguien mira el avance no produce lectura sucia,
-- porque no hay contador que actualizar — entra o no entra segun haya commiteado.
--
-- Ningun DTO de entrada acepta esos numeros tampoco. La unica forma de cumplir "sin aceptar
-- contadores realizados desde frontend" que no dependa de la disciplina de quien escriba el
-- proximo endpoint es no tener donde guardarlo.
--
-- ---------------------------------------------------------------------------------------
-- 2. LOS ITEMS CUELGAN DE LA VERSION, NO DEL PLAN
--
-- RN-M11-003: modificar el plan no cambia tratamientos historicos. Si `plan_item` colgara de
-- `plan_tratamiento`, cambiar la cantidad estimada de una practica reescribiria el pasado: el
-- avance de hace dos meses se recalcularia contra un plan que entonces no existia.
--
-- Colgados de la version, cada version tiene su foto de cantidades. La consecuencia hay que
-- aceptarla y mostrarla: el avance "8 de 20" de la version 1 y el "8 de 24" de la version 2
-- son DOS NUMEROS DISTINTOS Y LOS DOS CORRECTOS (challenge seccion 8.1). La pantalla tiene
-- que decir de que version habla, y por eso la consulta de avance recibe el numero de
-- version.
--
-- El precio es duplicacion de filas al versionar. Es barato —versionar un plan es
-- excepcional— y la alternativa es la que ADR-0011 prohibe.
--
-- LA UNICA EXCEPCION, DECLARADA: editar un plan en BORRADOR reemplaza los items de su version 1
-- con un DELETE + INSERT. No contradice la regla maestra 10 porque los items de un plan que NUNCA
-- se activo no son informacion historica —son un formulario a medio llenar, nadie planifico contra
-- ellos y ninguna sesion se conto contra ellos—. En cuanto el plan pasa a ACTIVO ese camino deja
-- de ejecutarse y toda modificacion escribe filas nuevas. La alternativa era versionar cada tecleo
-- del borrador, que el diseño rechaza en su seccion 4.
--
-- `uk_plan_item_oferta` es la red que sostiene la derivacion: dos items de la MISMA oferta en
-- la misma version harian que las sesiones realizadas de esa oferta se contaran dos veces,
-- una por cada item, y el avance mentiria sin que nada falle.
--
-- ---------------------------------------------------------------------------------------
-- 3. UN SOLO PLAN ACTIVO POR CASO, Y EL CENTINELA NO PUEDE SER EL `id`
--
-- El Caso ES el problema terapeutico: dos planes activos para el mismo problema significan
-- que en realidad son dos problemas —o sea, dos Casos—. `uk_plan_activo_por_caso` lo hace
-- cumplir, y es lo que evita que dos activaciones concurrentes dejen dos planes vivos: la
-- segunda choca contra la base y recibe 409.
--
-- EL TRUCO DEL CENTINELA ES DISTINTO DEL DE V27/V32/V40/V45/V47 y hay que mirarlo de frente.
-- Alla el discriminador era `IFNULL(deleted_at, '1970-01-01')`, porque varios NULL no
-- colisionan en MySQL. Aca el predicado no es "esta dado de baja" sino "esta ACTIVO", y hay
-- que admitir varios BORRADOR y varios FINALIZADO en el mismo caso: el historico son
-- justamente esos.
--
-- Lo natural seria `IF(estado = 'ACTIVO', 0, id)`, y NO SE PUEDE: MySQL prohibe que una
-- columna generada referencie una columna AUTO_INCREMENT. El discriminador es entonces
-- `numero_plan`, que es el correlativo del plan dentro del caso: es unico por
-- (organization_id, caso_clinico_id) por `uk_plan_numero`, es inmutable, y arranca en 1, asi
-- que el 0 nunca es un `numero_plan` real y a lo sumo una fila del caso puede llevarlo.
--
-- ACTIVAR UN PLAN NUEVO FINALIZA EL ANTERIOR, en la misma transaccion. No hay ventana en la
-- que existan dos, y el unique es lo que lo garantiza cuando dos activaciones llegan juntas.
--
-- ---------------------------------------------------------------------------------------
-- 4. EL CORRELATIVO SALE DE `plan_numerador`, NUNCA DE UN `MAX + 1`
--
-- Mismo mecanismo y mismo motivo que `caso_numerador` (V47) y `sesion_numerador` (V35): un
-- `SELECT MAX(numero) + 1` deja una ventana entre la lectura y la escritura, y dos planes
-- creados a la vez para el mismo caso se llevan el mismo numero. El
-- `UPDATE ... SET ultimo_numero = ultimo_numero + 1` toma un lock exclusivo de fila y
-- serializa, sin leer nada antes.
--
-- LA FILA DEL NUMERADOR SE CREA EN UNA TRANSACCION APARTE, con `INSERT ... ON DUPLICATE KEY
-- UPDATE`. La creacion perezosa dentro de la transaccion que la bloquea produce un DEADLOCK
-- entre las primeras N escrituras concurrentes, y el try/catch no salva: atrapar una
-- excepcion de persistencia no des-marca la transaccion y Spring lanza
-- `UnexpectedRollbackException` al commitear. Ya se pago CUATRO veces en este repositorio.
--
-- La numeracion de VERSIONES es otra cosa y vive en la cabecera —`ultimo_numero_version`—,
-- igual que en `entrada_clinica` (V45): el contador de versiones de un plan cuelga del plan.
-- `uk_plan_version_numero` es la red debajo.
--
-- Y SIN `OPTIMISTIC_FORCE_INCREMENT` SOBRE LA CABECERA. Modificar un plan ensucia
-- `plan_tratamiento` —el contador vive ahi— asi que el `UPDATE ... WHERE version = N` que
-- emite JPA ya da la garantia. Forzar el incremento la haria avanzar DOS veces y la respuesta
-- devolveria `leida + 1`: el cliente manda esa version y come un 409 del que no puede salir.
-- Es el defecto que 04.02 pagó y corrigio. La regla que queda: force-increment SOLO donde la
-- escritura no toca ninguna columna del padre.
--
-- ---------------------------------------------------------------------------------------
-- 5. CUATRO ESTADOS, Y EL UNICO QUE SORPRENDE ES `SUSPENDIDO`
--
-- `BORRADOR -> ACTIVO -> SUSPENDIDO -> ACTIVO -> FINALIZADO`
--
--   * BORRADOR   se edita libremente SIN versionar. Un plan que nunca se activo no tiene
--                historia que preservar, y versionar cada tecleo llenaria la tabla de ruido.
--   * ACTIVO     toda modificacion relevante VERSIONA (RF-M11-004).
--   * SUSPENDIDO el tratamiento se discontinuo pero no se cerro. Merece estado propio:
--                finalizar y reabrir perderia la distincion entre "termino" y "se freno".
--   * FINALIZADO terminal. No se reabre — se crea un plan nuevo.
--
-- COMPLETAR LA CANTIDAD ESTIMADA NO FINALIZA EL PLAN NI CIERRA EL CASO (RN-M11-004). El
-- sistema avisa; la decision es clinica. Un cierre automatico por contador es exactamente
-- confundir planificado con realizado en la direccion contraria, y es la tentacion que mas se
-- parece a una mejora de producto (challenge seccion 4.3).
--
-- NO HAY BAJA LOGICA DE PLAN. El plan no se borra: se finaliza. Un `active` al lado de
-- `estado` daria dos formas de que un plan "no este", que es como se construye una consulta
-- que se olvida de una. Misma decision, y mismo argumento, que `caso_clinico` en V47.
--
-- LAS VERSIONES NO SE DAN DE BAJA NI SIQUIERA LOGICAMENTE. `plan_tratamiento_version` no
-- tiene `active`, igual que `entrada_clinica_version`: una version es un hecho pasado y darla
-- de baja seria reescribir historia clinica (ADR-0011). Los items de la version anterior
-- tampoco se tocan al modificar: la version nueva trae su propio juego.
--
-- ---------------------------------------------------------------------------------------
-- 6. LA CANTIDAD AUTORIZADA SE DECLARA, Y LA COLUMNA LLEGA HOY
--
-- RF-M11-003 pide registrarla. `plan_item.cantidad_autorizada` la guarda con su ORIGEN
-- (`DECLARADA` hoy; `AUTORIZACION` cuando 04.05 llegue) y su referencia nullable a la
-- autorizacion de M17.
--
-- NO SE CONECTA A `autorizacion` EN ESTA ETAPA, y no es pereza: 04.05 es "Integracion y
-- consumo de autorizaciones" y hacerlo aca seria adelantarla a medias. El registro de 03.06
-- ya declaro que `consultarElegibilidadAdministrativa` no tiene consumidor; 04.05 es quien lo
-- estrena, y meter medio consumo aca deja dos caminos que despues hay que unificar.
--
-- La columna existe desde ahora porque agregarla despues obliga a decidir que valor llevan
-- las filas viejas y ninguna respuesta es buena. Es DP-10 literal: se corta el ALCANCE, no el
-- MODELO.
--
-- ---------------------------------------------------------------------------------------
-- 7. SNAPSHOT DE LA OFERTA, IGUAL QUE EL IMPORTE DE LA OBLIGACION
--
-- `plan_item` guarda `oferta_id` Y el nombre y el servicio congelados al planificar, igual
-- que la obligacion de 07.01 congela el importe. Un plan de seis meses sobrevive al renombre
-- de una oferta y a su baja; sin snapshot, el plan de marzo se lee en septiembre con los
-- nombres de septiembre y nadie entiende que se planifico.
--
-- Que la oferta se de de baja despues NO INVALIDA EL PLAN: misma regla que 02.06 dejo fijada
-- —la baja de un servicio no cascadea, solo impide crear nuevos—.
--
-- ---------------------------------------------------------------------------------------
-- 8. `plan_evento` ES APPEND-ONLY
--
-- Sin `updated_at`, sin `version`, sin baja: un historial que se puede editar no es un
-- historial. Mismo diseño que `caso_evento` (V47) y `turno_evento` (V38). Se inserta DENTRO
-- de la misma transaccion del cambio de estado, porque un evento escrito despues del commit
-- puede perderse y dejar la transicion sin rastro.
--
-- `detalle` NUNCA lleva contenido clinico: dice que se modificaron los objetivos, no cuales.
--
-- ---------------------------------------------------------------------------------------
-- 9. TENANT EN LAS CINCO, Y EN TODOS LOS UNIQUES E INDICES
--
-- `plan_tratamiento_version`, `plan_item` y `plan_evento` llevan `organization_id` AUNQUE SEA
-- DERIVABLE, por el mismo motivo que `entrada_clinica_version` en 04.02 y `caso_evento` en
-- 04.03: derivarlo obliga a un join para filtrar por tenant, y el dia que alguien escriba la
-- consulta sin el join tiene una fuga que ningun test de la etapa ve, porque los tests de una
-- etapa usan un solo tenant.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO SE MODELA, Y POR QUE
--
--   * NINGUNA columna de sesiones realizadas ni canceladas. Ver el punto 1.
--   * NINGUNA tabla de turnos, series ni recurrencia materializada. El plan PROPONE una regla
--     —frecuencia semanal y duracion— y NO AGENDA NADA. Agendar es de `scheduling`, y este
--     modulo no lo importa ni por el `spi`. El dia que se agende desde el plan, el que llama
--     es `scheduling`, no al reves (challenge seccion 4.2).
--   * NINGUN `consultorio_id` del plan. El plan cuelga del Caso, que cuelga de la Historia,
--     que es de la ORGANIZACION (DP-03). Lo que si viaja es `oferta_consultorio_id` en el
--     item, que es la sede de la OFERTA y sin la cual `oferta_id` no se puede volver a
--     resolver — las ofertas son por sede desde V24.
--   * NINGUN `active`. Ver el punto 5.
--   * NINGUNA FK hacia `cuenta`: esa tabla es de `identity` y una FK cruzaria la propiedad de
--     datos entre modulos. `creado_por`, `activado_por`, `finalizado_por`, `registrada_por` y
--     `actor_cuenta_id` son accountId sueltos, como en V32, V40, V45 y V47.
--   * NINGUNA tabla propia de auditoria. Ya existe `audit_event` (V5, V14) con sus triggers de
--     append-only, y ahi se escribe tambien la LECTURA (DP-03). `plan_evento` es otra cosa: es
--     el historial de ESTADOS del plan, que el profesional lee, y no el registro de accesos,
--     que lee quien audita.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- plan_tratamiento — la cabecera: identidad, estado y numeracion. Ver los puntos 3, 4 y 5.
-- -------------------------------------------------------------------------------------
CREATE TABLE plan_tratamiento
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id       BIGINT       NOT NULL COMMENT 'Tenant propietario. Todo indice de esta tabla empieza por el (AGENT.md seccion 5)',
    caso_clinico_id       BIGINT       NOT NULL COMMENT 'Caso del que cuelga el plan. El Caso ES el problema terapeutico: por eso admite UN solo plan activo (punto 3)',

    numero_plan           INT          NOT NULL COMMENT 'Correlativo del plan DENTRO del caso. Sale de plan_numerador con UPDATE ultimo_numero + 1, nunca de un MAX. Ademas es el discriminador de activo_key: ver el punto 3',

    estado                VARCHAR(16)  NOT NULL DEFAULT 'BORRADOR' COMMENT 'BORRADOR, ACTIVO, SUSPENDIDO o FINALIZADO. Sin active al lado: ver el punto 5',

    ultimo_numero_version INT          NOT NULL DEFAULT 1 COMMENT 'Numero de la ultima version escrita. La modificacion numera con este contador +1 y NUNCA con MAX(numero_version): dos MAX simultaneos dan el mismo numero (punto 4)',

    activo_key            INT AS (IF(estado IN ('ACTIVO', 'SUSPENDIDO'), 0, numero_plan)) STORED NOT NULL
        COMMENT 'Discriminador de "un solo plan activo por caso". El plan VIVO —ACTIVO o SUSPENDIDO— vale 0 y los demas su propio numero_plan. SUSPENDIDO ocupa el lugar a proposito: suspender es frenar el tratamiento que hay, no abrir la puerta a otro (EstadoPlan#SUSPENDIDO), que ya es unico en el caso. No puede ser el id: MySQL prohibe que una columna generada referencie un AUTO_INCREMENT. Ver el punto 3',

    creado_en             DATETIME(6)  NOT NULL COMMENT 'Instante UTC de la creacion del plan, en BORRADOR',
    creado_por            BIGINT       NOT NULL COMMENT 'accountId de quien lo creo. Sin FK a cuenta, mismo motivo que en V47',

    activado_en           DATETIME(6)  NULL COMMENT 'Instante UTC de la PRIMERA activacion. No se limpia al suspender ni al finalizar: un plan que estuvo vigente lo estuvo',
    activado_por          BIGINT       NULL COMMENT 'accountId de quien lo activo',

    suspendido_en         DATETIME(6)  NULL COMMENT 'Instante UTC de la suspension vigente. Se limpia al reanudar; la suspension anterior queda en plan_evento, que es append-only',
    motivo_suspension     VARCHAR(500) NULL COMMENT 'Por que se discontinuo. OBLIGATORIO al suspender: sin motivo, "se freno" es indistinguible de "lo abandonaron"',

    finalizado_en         DATETIME(6)  NULL COMMENT 'Instante UTC de la finalizacion. FINALIZADO es terminal: no se reabre, se crea un plan nuevo',
    finalizado_por        BIGINT       NULL COMMENT 'accountId de quien lo finalizo. Cuando la finalizacion la produce la activacion de OTRO plan, es quien activo ese otro',
    motivo_finalizacion   VARCHAR(500) NULL COMMENT 'Por que termino. Obligatorio, por lo mismo que el motivo de cierre de un caso',

    version               BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. SIN force-increment desde la aplicacion: modificar ensucia esta fila y el UPDATE versionado ya alcanza. Ver el punto 4',
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,

    CONSTRAINT pk_plan_tratamiento PRIMARY KEY (id),

    -- El respaldo del numerador, no el mecanismo. Y lo que hace que activo_key funcione.
    CONSTRAINT uk_plan_numero
        UNIQUE (organization_id, caso_clinico_id, numero_plan),

    -- Un solo plan ACTIVO por caso. Ver el punto 3.
    CONSTRAINT uk_plan_activo_por_caso
        UNIQUE (organization_id, caso_clinico_id, activo_key),

    CONSTRAINT fk_plan_tratamiento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    -- RESTRICT (el default): no hay borrado fisico de un caso con planes.
    CONSTRAINT fk_plan_tratamiento_caso
        FOREIGN KEY (caso_clinico_id) REFERENCES caso_clinico (id),

    CONSTRAINT ck_plan_tratamiento_estado
        CHECK (estado IN ('BORRADOR', 'ACTIVO', 'SUSPENDIDO', 'FINALIZADO')),

    CONSTRAINT ck_plan_tratamiento_numero_positivo
        CHECK (numero_plan > 0),

    CONSTRAINT ck_plan_tratamiento_numeracion_version
        CHECK (ultimo_numero_version >= 1),

    -- Un plan vigente o suspendido estuvo activo alguna vez; uno en BORRADOR no. Un
    -- FINALIZADO puede no haberlo estado: un borrador que se descarta se finaliza, porque
    -- un plan no se borra (punto 5).
    CONSTRAINT ck_plan_tratamiento_activacion
        CHECK ((estado IN ('ACTIVO', 'SUSPENDIDO')
            AND activado_en IS NOT NULL AND activado_por IS NOT NULL)
            OR (estado = 'BORRADOR' AND activado_en IS NULL AND activado_por IS NULL)
            OR estado = 'FINALIZADO'),

    -- Los dos datos de la suspension van juntos, y solo en SUSPENDIDO: reanudar los limpia.
    CONSTRAINT ck_plan_tratamiento_suspension
        CHECK ((estado = 'SUSPENDIDO'
            AND suspendido_en IS NOT NULL AND motivo_suspension IS NOT NULL)
            OR (estado <> 'SUSPENDIDO'
                AND suspendido_en IS NULL AND motivo_suspension IS NULL)),

    -- Los tres datos de la finalizacion van juntos o no va ninguno.
    CONSTRAINT ck_plan_tratamiento_finalizacion
        CHECK ((estado = 'FINALIZADO' AND finalizado_en IS NOT NULL
            AND finalizado_por IS NOT NULL AND motivo_finalizacion IS NOT NULL)
            OR (estado <> 'FINALIZADO' AND finalizado_en IS NULL
                AND finalizado_por IS NULL AND motivo_finalizacion IS NULL)),

    -- Los dos caminos de lectura de la etapa: los planes de un caso —filtrando o no por
    -- estado— y la busqueda del plan ACTIVO, que es el mismo filtro acotado.
    INDEX ix_plan_tratamiento_caso (organization_id, caso_clinico_id, estado, creado_en)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Plan de Tratamiento de un Caso Clinico (M11, RF-M11-001). NO guarda realizadas ni canceladas: se derivan al leer (RN-M11-001). Propietario: modulo clinical';


-- -------------------------------------------------------------------------------------
-- plan_tratamiento_version — el contenido, una fila por version. Ver el punto 2.
-- -------------------------------------------------------------------------------------
CREATE TABLE plan_tratamiento_version
(
    id                   BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id      BIGINT        NOT NULL COMMENT 'Derivable del plan y va IGUAL: ver el punto 9',
    plan_tratamiento_id  BIGINT        NOT NULL COMMENT 'Plan del que esta version es contenido',

    numero_version       INT           NOT NULL COMMENT 'Orden de la version dentro del plan. La 1 es la original; cada modificacion agrega la siguiente. Sale del contador de la cabecera, nunca de un MAX (punto 4)',

    objetivos            VARCHAR(2000) NOT NULL COMMENT 'Objetivos terapeuticos de esta version. Es contenido clinico: NO se copia a la auditoria ni al historial de estados',
    indicaciones         VARCHAR(2000) NULL COMMENT 'Indicaciones generales del tratamiento. Opcional',

    frecuencia_semanal   INT           NULL COMMENT 'Sesiones por semana PROPUESTAS. Es una regla de recurrencia sugerida y NO AGENDA NADA: agendar es de scheduling (regla maestra 2)',
    duracion_semanas     INT           NULL COMMENT 'Duracion estimada del tratamiento, en semanas. Estimada: el plan no vence solo',

    motivo_modificacion  VARCHAR(500)  NULL COMMENT 'Por que se modifico. NULL en la version 1 —no modifica nada— y obligatorio en toda posterior: sin motivo, una modificacion es indistinguible de una correccion de tipeo',

    registrada_en        DATETIME(6)   NOT NULL COMMENT 'Instante UTC en que se escribio esta version',
    registrada_por       BIGINT        NOT NULL COMMENT 'accountId del autor de ESTA version. Es por version y no por plan: quien modifica no suele ser quien escribio la original',

    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,

    CONSTRAINT pk_plan_tratamiento_version PRIMARY KEY (id),

    -- La red debajo de la numeracion: si dos modificaciones concurrentes llegaran al mismo
    -- numero, la segunda choca contra la base en vez de dejar dos "version 3" sin criterio
    -- de desempate. La serializacion la hace el contador de la cabecera; esto la verifica.
    CONSTRAINT uk_plan_version_numero
        UNIQUE (organization_id, plan_tratamiento_id, numero_version),

    CONSTRAINT fk_plan_version_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_plan_version_plan
        FOREIGN KEY (plan_tratamiento_id) REFERENCES plan_tratamiento (id),

    CONSTRAINT ck_plan_version_numero
        CHECK (numero_version >= 1),

    CONSTRAINT ck_plan_version_motivo_de_modificacion
        CHECK ((numero_version = 1 AND motivo_modificacion IS NULL)
            OR (numero_version > 1 AND motivo_modificacion IS NOT NULL)),

    CONSTRAINT ck_plan_version_frecuencia
        CHECK (frecuencia_semanal IS NULL
            OR (frecuencia_semanal > 0 AND frecuencia_semanal <= 21)),

    CONSTRAINT ck_plan_version_duracion
        CHECK (duracion_semanas IS NULL OR (duracion_semanas > 0 AND duracion_semanas <= 520))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Contenido de una version de Plan de Tratamiento (M11, RF-M11-004). SIN active a proposito: una version es un hecho pasado y darla de baja seria reescribir historia clinica (ADR-0011). Propietario: modulo clinical';


-- -------------------------------------------------------------------------------------
-- plan_item — las practicas planificadas, COLGADAS DE LA VERSION. Ver los puntos 1, 2, 6 y 7.
-- -------------------------------------------------------------------------------------
CREATE TABLE plan_item
(
    id                          BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id             BIGINT       NOT NULL COMMENT 'Derivable de la version y va IGUAL: ver el punto 9',
    plan_tratamiento_version_id BIGINT       NOT NULL COMMENT 'VERSION de la que cuelga, no plan. Es la decision del punto 2: modificar el plan crea una version nueva con su propio juego de items y los de la anterior quedan intactos',

    oferta_id                   BIGINT       NOT NULL COMMENT 'Oferta planificada. Tiene que estar vigente AL PLANIFICAR (02.07); que despues se de de baja NO invalida el plan (punto 7)',
    oferta_consultorio_id       BIGINT       NOT NULL COMMENT 'Sede de la OFERTA, no del plan. Las ofertas son por sede (V24) y sin esta columna oferta_id no se puede volver a resolver',
    oferta_nombre               VARCHAR(160) NOT NULL COMMENT 'COPIA del nombre comercial al planificar. Un plan de seis meses sobrevive al renombre de la oferta; sin snapshot, el plan de marzo se lee en septiembre con los nombres de septiembre',
    servicio_id                 BIGINT       NOT NULL COMMENT 'COPIA del servicio que la oferta prestaba al planificar. Identidad estable de la practica, para trazabilidad',

    cantidad_planificada        INT          NOT NULL COMMENT 'Sesiones que la DECISION CLINICA estima. Es el dato de la etapa. NO es lo realizado: eso no existe como columna (punto 1)',
    cantidad_autorizada         INT          NULL COMMENT 'Sesiones que la cobertura otorgo. DECLARADA a mano en esta etapa; la costura real con M17 es 04.05 (punto 6). NULL = sin tope declarado, que es distinto de cero',
    origen_autorizacion         VARCHAR(16)  NOT NULL DEFAULT 'DECLARADA' COMMENT 'DECLARADA (hoy) o AUTORIZACION (04.05). La columna llega hoy porque agregarla despues obliga a decidir que valor llevan las filas viejas',
    autorizacion_id             BIGINT       NULL COMMENT 'Autorizacion de M17 que respalda la cantidad. NULL mientras el origen sea DECLARADA',

    created_at                  DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NOT NULL,

    CONSTRAINT pk_plan_item PRIMARY KEY (id),

    -- Sin esto, dos items de la misma oferta en una version harian que la derivacion del
    -- avance contara las mismas sesiones dos veces. Ver el punto 2.
    CONSTRAINT uk_plan_item_oferta
        UNIQUE (organization_id, plan_tratamiento_version_id, oferta_id),

    CONSTRAINT fk_plan_item_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_plan_item_version
        FOREIGN KEY (plan_tratamiento_version_id) REFERENCES plan_tratamiento_version (id),

    CONSTRAINT fk_plan_item_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    CONSTRAINT fk_plan_item_autorizacion
        FOREIGN KEY (autorizacion_id) REFERENCES autorizacion (id),

    CONSTRAINT ck_plan_item_cantidad_planificada
        CHECK (cantidad_planificada > 0 AND cantidad_planificada <= 9999),

    CONSTRAINT ck_plan_item_cantidad_autorizada
        CHECK (cantidad_autorizada IS NULL
            OR (cantidad_autorizada >= 0 AND cantidad_autorizada <= 9999)),

    CONSTRAINT ck_plan_item_origen
        CHECK (origen_autorizacion IN ('DECLARADA', 'AUTORIZACION')),

    -- Un item que dice venir de una autorizacion sin decir de cual no es trazable.
    CONSTRAINT ck_plan_item_origen_trazable
        CHECK ((origen_autorizacion = 'DECLARADA' AND autorizacion_id IS NULL)
            OR (origen_autorizacion = 'AUTORIZACION' AND autorizacion_id IS NOT NULL)),

    INDEX ix_plan_item_version (organization_id, plan_tratamiento_version_id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Practica planificada dentro de una VERSION de Plan de Tratamiento (M11, RF-M11-002). NO tiene cantidad_realizada ni cantidad_cancelada: se derivan al leer por RealizadoEnElCasoProbe (RN-M11-001). Propietario: modulo clinical';


-- -------------------------------------------------------------------------------------
-- plan_numerador — asignacion atomica del correlativo del plan. Ver el punto 4.
-- -------------------------------------------------------------------------------------
CREATE TABLE plan_numerador
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT      NOT NULL,
    caso_clinico_id BIGINT      NOT NULL,

    ultimo_numero   INT         NOT NULL DEFAULT 0
        COMMENT 'Ultimo correlativo entregado. Se incrementa con UPDATE, nunca con SELECT MAX + 1.',

    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_plan_numerador
        UNIQUE (organization_id, caso_clinico_id),

    CONSTRAINT fk_plan_numerador_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_plan_numerador_caso
        FOREIGN KEY (caso_clinico_id) REFERENCES caso_clinico (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Secuencia de planes por Caso Clinico. No es un cache de COUNT(*): existe para asignar de forma atomica. Gemelo de caso_numerador (V47) y sesion_numerador (V35)';


-- -------------------------------------------------------------------------------------
-- plan_evento — historial inmutable de estados. Ver el punto 8.
-- -------------------------------------------------------------------------------------
CREATE TABLE plan_evento
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id     BIGINT       NOT NULL COMMENT 'Derivable del plan y va IGUAL: ver el punto 9',
    plan_tratamiento_id BIGINT       NOT NULL,

    tipo                VARCHAR(24)  NOT NULL
        COMMENT 'CREACION, EDICION, ACTIVACION, MODIFICACION, SUSPENSION, REANUDACION o FINALIZACION.',

    estado_anterior     VARCHAR(16)  NULL COMMENT 'NULL solo en CREACION: antes no habia estado.',
    estado_nuevo        VARCHAR(16)  NOT NULL,

    motivo              VARCHAR(500) NULL
        COMMENT 'Obligatorio en SUSPENSION, MODIFICACION y FINALIZACION; no aplica al resto. Ver el CHECK.',

    detalle             VARCHAR(500) NULL
        COMMENT 'Resumen NO clinico de que cambio. NUNCA lleva objetivos ni indicaciones: este historial se lee al lado de la ficha y su contenido no pasa por el acceso clinico de la version.',

    numero_version      INT          NULL
        COMMENT 'Version que produjo el evento, cuando aplica. Es lo que ata una MODIFICACION a su contenido sin copiarlo aca.',

    ocurrio_en          DATETIME(6)  NOT NULL COMMENT 'Instante UTC de la transicion',
    actor_cuenta_id     BIGINT       NOT NULL COMMENT 'accountId de quien la produjo. Sin FK a cuenta',

    created_at          DATETIME(6)  NOT NULL,

    CONSTRAINT fk_plan_evento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_plan_evento_plan
        FOREIGN KEY (plan_tratamiento_id) REFERENCES plan_tratamiento (id),

    CONSTRAINT ck_plan_evento_tipo
        CHECK (tipo IN ('CREACION', 'EDICION', 'ACTIVACION', 'MODIFICACION', 'SUSPENSION',
                        'REANUDACION', 'FINALIZACION')),

    CONSTRAINT ck_plan_evento_estados
        CHECK (estado_nuevo IN ('BORRADOR', 'ACTIVO', 'SUSPENDIDO', 'FINALIZADO')
            AND (estado_anterior IS NULL
                OR estado_anterior IN ('BORRADOR', 'ACTIVO', 'SUSPENDIDO', 'FINALIZADO'))),

    -- Suspender, modificar y finalizar exigen motivo. Sin el, el historial registra que algo
    -- paso y no por que, que es lo unico que despues se quiere leer.
    CONSTRAINT ck_plan_evento_motivo
        CHECK (tipo NOT IN ('SUSPENSION', 'MODIFICACION', 'FINALIZACION')
            OR motivo IS NOT NULL),

    CONSTRAINT ck_plan_evento_creacion
        CHECK ((tipo = 'CREACION' AND estado_anterior IS NULL)
            OR (tipo <> 'CREACION' AND estado_anterior IS NOT NULL)),

    CONSTRAINT ck_plan_evento_numero_version
        CHECK (numero_version IS NULL OR numero_version >= 1),

    INDEX ix_plan_evento_plan (organization_id, plan_tratamiento_id, ocurrio_en)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Historial inmutable de estados de un Plan de Tratamiento (M11, RF-M11-007). APPEND-ONLY: sin updated_at, sin version, sin baja. Mismo diseño que caso_evento (V47). Propietario: modulo clinical';
