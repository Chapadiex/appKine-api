-- =====================================================================================
-- AKINE-06.04 — Tratamientos realizados y espacios usados (M14 / M04 / M06).
--
-- Trazabilidad: RF-M14-005, RF-M04-005, RN-M14-004, `plan_sesiones.txt` seccion 10.
--
-- QUE DATO NO EXISTIA HASTA ACA
--
-- El sistema sabia que HUBO una atencion (V33) y que alguien PLANIFICO practicas (V49).
-- Nunca supo que se aplico REALMENTE en esa atencion: podia decir "hubo una sesion de
-- Kinesiologia deportiva" y no podia decir si hubo electroterapia, terapia manual o las dos.
-- RN-M14-004 —planificado no equivale a realizado— no se podia ni siquiera expresar.
--
-- CONSECUENCIA QUE EXCEDE A M14, Y HAY QUE SABERLA LEER: `tratamiento_realizado` es la UNICA
-- tabla del sistema que vincula una atencion con una practica del catalogo M06. La
-- autorizacion de M17 se otorga por `practica_id` (V44) y hasta hoy el consumo elegia "la que
-- vence antes" sin mirar la practica, asi que podia gastar la autorizacion equivocada. Con
-- esta tabla deja de poder.
--
-- Lo que esto NO destraba: el avance del Plan de Tratamiento sigue contandose por OFERTA.
-- `plan_item` (V49) se lleva por `oferta_id` y `servicio_id`, la autorizacion por
-- `practica_id`, y NO EXISTE tabla puente Oferta-Practica —V24 la dejo afuera—. Esta tabla es
-- un puente OBSERVADO ("en esta sesion se hicieron estas practicas"), no CONFIGURADO ("esta
-- oferta presta estas practicas"), y un plan planifica antes de que ninguna sesion exista.
-- Cerrar esa mitad exige `plan_item.practica_id`, que es una tabla de `clinical` y una
-- decision del usuario. Detalle en `docs/diseno/AKINE-06.04-challenge.md` seccion 9.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE DOS TABLAS Y NO UNA COLUMNA `json`
--
-- `plan_sesiones.txt` seccion 10.4 pide campos distintos por tipo de practica —intensidad y
-- frecuencia en electroterapia; series, repeticiones y carga en ejercicio terapeutico— y el
-- plan de implementacion nombra el caso borde de frente: "parametro legado sin tipo/unidad que
-- debe RECHAZARSE o normalizarse explicitamente".
--
-- Un `json` no puede rechazar nada: la validacion quedaria en Java y la base aceptaria
-- cualquier forma que un endpoint futuro decidiera escribir. Con tabla, `tipo_dato NOT NULL` y
-- un CHECK que exige que el valor caiga en la columna que su tipo declara, EL RECHAZO ES
-- ESTRUCTURAL: un parametro sin tipo no entra ni por error ni por un endpoint nuevo escrito
-- con prisa.
--
-- Y hay un antecedente caro: MySQL NORMALIZA el `json` al guardarlo —reordena claves y
-- reescribe numeros—, que es la causa raiz del outbox clavado. Un valor de dosificacion
-- clinica que vuelve del motor con otra representacion es exactamente lo que no se quiere.
--
-- El `borrador` de `sesion` sigue siendo `json` opaco y eso NO cambia: son cosas distintas.
-- El borrador es lo que el profesional esta tipeando; esto es lo que asento.
--
-- ---------------------------------------------------------------------------------------
-- EL ESPACIO VA EN EL TRATAMIENTO, NO EN LA SESION
--
-- RF-M04-005 pide "el espacio realmente utilizado" y `plan_sesiones.txt` lo lista entre los
-- campos base del Bloque D. Va por tratamiento porque el dato real lo exige: un paciente
-- arranca en el box de electroterapia y termina en el gimnasio, y una sola columna en `sesion`
-- obligaria a elegir cual de los dos miente.
--
-- CONSECUENCIA COLATERAL QUE CONVIENE TENER A LA VISTA: esta migracion NO hace ningun
-- ALTER TABLE sesion. Solo crea tablas nuevas de `encounter`. Por eso no colisiona con 06.03,
-- que corre en otro worktree y si escribe en `sesion`.
--
-- LO QUE NO SE VALIDA, Y ES LA DECISION MAS CONTRAINTUITIVA DE LA ETAPA: no se valida
-- ocupacion ni capacidad del espacio. El plan las nombra entre las validaciones y aplicarlas
-- aca seria la regla maestra 4 AL REVES. Un tratamiento realizado es un HECHO CONSUMADO: la
-- atencion ya ocurrio, en ese box, a esa hora. Rechazar el registro porque el box figuraba
-- ocupado no impide la sobreocupacion —ya paso— sino que impide DOCUMENTARLA, y deja la
-- historia clinica diciendo que la sesion no tuvo espacio. La ocupacion es regla de RESERVA y
-- su lugar es 05.02 y RF-M04-004.
--
-- ---------------------------------------------------------------------------------------
-- EL `orden`, Y POR QUE ACA NO HAY NUMERADOR
--
-- La regla del repositorio es que los correlativos se asignan con `UPDATE ... ultimo_numero+1`
-- y NUNCA con `MAX+1`. Esta etapa no crea numerador y asigna `orden` como el maximo de la
-- sesion mas uno. La diferencia no es de gusto:
--
--   * El `MAX+1` prohibido es el que NO ESTA SERIALIZADO POR NADA. Es el caso de
--     `numero_sesion` y `numero_en_caso`, donde el correlativo es de una Historia o de un Caso
--     y los escritores son sesiones distintas que no comparten ninguna fila.
--   * Aca TODOS los escritores del mismo `orden` comparten la fila `sesion`, y la leen con
--     OPTIMISTIC_FORCE_INCREMENT. Dos altas concurrentes: la primera commitea, la segunda
--     emite `UPDATE sesion SET version = N+1 WHERE version = N`, afecta cero filas y recibe
--     409. NUNCA LLEGAN LAS DOS.
--
-- `uk_tratamiento_orden` es el RESPALDO de ese mecanismo, no el mecanismo — mismo reparto que
-- `uk_sesion_numero` en V35.
--
-- El force-increment hace falta porque escribir un tratamiento NO TOCA NI UNA COLUMNA de
-- `sesion`, y un @Version sobre el padre no protege una escritura que solo toca tablas hijas
-- (leccion de 02.07, `b8bbc67`). Y la reciproca se respeta —la que 04.02 pago—: como la
-- escritura no ensucia la sesion por ningun otro camino, la version avanza UNA vez.
-- SI ALGUNA VEZ SE AGREGA UNA COLUMNA DE RESUMEN A `sesion` —cuantos tratamientos, minutos
-- totales— HAY QUE SACAR EL FORCE-INCREMENT, o la version avanza dos veces y el cliente come
-- un 409 del que no puede salir. Ese resumen no debe existir: se deriva al leer, como el
-- timeline de 04.02 y el avance de 04.04.
--
-- ---------------------------------------------------------------------------------------
-- BAJA LOGICA, Y LA UNICA EXCEPCION
--
-- `tratamiento_realizado` lleva el cuarteto completo con el centinela '1970-01-01', y
-- `deleted_key` entra en el unique de `orden`: en MySQL varios NULL no colisionan, asi que un
-- unique sobre `deleted_at` a secas desprotegeria justo el caso que importa. El `orden` NO SE
-- REUTILIZA: un tratamiento dado de baja no libera su numero, y por eso el proximo sale del
-- maximo sobre TODAS las filas, vivas y muertas.
--
-- `tratamiento_parametro` NO lleva baja logica y se borra fisicamente al reemplazar el
-- tratamiento. Es la excepcion y hay que defenderla: un parametro no tiene identidad ni
-- historia propias —es un atributo del tratamiento, como lo seria una columna—, nadie lo
-- referencia y ninguna auditoria lo nombra. Ademas solo se edita mientras la sesion esta en
-- BORRADOR: una sesion cerrada no se edita, se enmienda, y eso es 06.06. Cuando se cierra, los
-- parametros quedan congelados para siempre.
--   >> SI 06.06 HABILITA EDITAR UN TRATAMIENTO DE UNA SESION CERRADA, ESTA EXCEPCION DEJA DE
--      VALER Y HAY QUE VERSIONARLOS. Queda anotado aca para quien la escriba.
-- =====================================================================================


CREATE TABLE tratamiento_realizado
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id           BIGINT       NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo unique e indice de esta tabla empieza por el',
    consultorio_id            BIGINT       NOT NULL COMMENT 'Sede donde ocurrio la atencion. El hecho pertenece a una sede concreta, no a la organizacion',

    sesion_id                 BIGINT       NOT NULL COMMENT 'Atencion en la que se aplico. UNICO vinculo del tratamiento: no lleva caso_id ni historia_clinica_id porque se resuelven por la sesion, y duplicarlos habilitaria que discrepen (mismo criterio con el que la sesion no guarda persona_id)',

    orden                     INT          NOT NULL COMMENT 'Secuencia CRONOLOGICA de la intervencion dentro de la visita (plan_sesiones 10.5). Monotono y NO reutilizable: una baja no libera su numero. No hay operacion de reordenar: intercambiar dos orden bajo un unique exigiria un DEFERRABLE que MySQL no tiene, y el dato es cronologico, no una preferencia de presentacion',

    -- El id Y el snapshot. resource.spi.CatalogoSnapshot lo pide con esas palabras: "un hecho
    -- historico guarda el id y, si necesita ser legible sin resolver nada, tambien el codigo y
    -- el nombre del momento". Una sesion de marzo leida en septiembre tiene que decir que
    -- practica fue EN MARZO.
    practica_id               BIGINT       NOT NULL COMMENT 'Practica del catalogo clinico (M06) efectivamente aplicada. Es el eje que la autorizacion de M17 usa (V44) y lo que permite que el consumo deje de imputarse a la autorizacion equivocada',
    practica_codigo           VARCHAR(64)  NOT NULL COMMENT 'COPIA del codigo de la practica al registrar. RN-M06-002: los renombres son parte de la historia',
    practica_nombre           VARCHAR(160) NOT NULL COMMENT 'COPIA del nombre de la practica al registrar. Sin snapshot, la sesion de marzo se lee en septiembre con los nombres de septiembre',

    tecnica                   VARCHAR(160) NULL COMMENT 'Tecnica o maniobra utilizada (plan_sesiones 10.2). NULL legitimo: no toda practica tiene tecnica declarable',
    zona                      VARCHAR(120) NULL COMMENT 'Zona tratada (plan_sesiones 10.2). Texto porque no existe catalogo de zonas anatomicas en M06 e inventarlo aca seria decidir por el usuario',
    lateralidad               VARCHAR(16)  NULL COMMENT 'IZQUIERDA / DERECHA / BILATERAL / NO_APLICA. MISMO vocabulario que dolor_lateralidad en V34, NO_APLICA incluido: una zona central —lumbar, cervical— no tiene lado, y decirlo explicitamente distingue "no corresponde" de "no lo cargue". Sin zona no significa nada: el CHECK lo impide',

    duracion_minutos          INT          NULL COMMENT 'Duracion de ESTA intervencion (plan_sesiones 10.5). NULL legitimo: no siempre se cronometra. La duracion total de la sesion es SUM() y se calcula al leer — una columna de resumen seria una segunda copia de la verdad',

    profesional_membership_id BIGINT       NOT NULL COMMENT 'Quien aplico ESTA intervencion (co-atencion, plan_sesiones 10.5). Por defecto el profesional de la sesion. ANOTAR QUE OTRO PARTICIPO NO LE DA PERMISO DE ESCRITURA: quien escribe sigue siendo el dueño de la sesion, y escribir en la atencion ajena sigue siendo 409, no 403',

    -- RF-M04-005. Ver la cabecera: por tratamiento y no por sesion, y sin validar ocupacion.
    espacio_id                BIGINT       NULL COMMENT 'Espacio REALMENTE utilizado (RF-M04-005). NULL legitimo: no toda oferta requiere espacio. Puede diferir del reservado en el turno, que es justamente el punto del requerimiento',
    espacio_nombre            VARCHAR(160) NULL COMMENT 'COPIA del nombre del espacio al registrar. RN-M04-003 y el javadoc de EspacioSnapshot: un hecho que solo guardara el id mostraria seis meses despues el nombre ACTUAL del box y no el que tenia el dia de la atencion',

    observacion               VARCHAR(500) NULL COMMENT 'Observacion breve de la intervencion (plan_sesiones 10.5). Breve a proposito: el relato clinico va en la evolucion y en la entrada clinica, no aca',

    registrado_en             DATETIME(6)  NOT NULL COMMENT 'Instante UTC en que se asento la intervencion',
    registrado_por_cuenta_id  BIGINT       NOT NULL COMMENT 'Cuenta que lo asento. Dato de AUTORIA del registro, distinto de profesional_membership_id, que dice quien lo APLICO',

    active                    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. 0 tras la baja logica',
    deleted_at                DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras el tratamiento este vigente',
    deactivation_reason       VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio: es el mismo un dato clinico —"se suspendio la electroterapia porque el paciente refirio molestia"— y sin el la auditoria no responde por que seis meses despues',

    deleted_key               DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de orden: el instante de la baja, o el centinela 1970-01-01 mientras el tratamiento este vigente. Existe solo para que el unique funcione: en MySQL varios NULL no colisionan, asi que un unique sobre deleted_at a secas dejaria de proteger justo el caso que importa',

    version                   BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista de la propia fila. El que serializa las ALTAS es el @Version de sesion leido con OPTIMISTIC_FORCE_INCREMENT: ver la cabecera',
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,

    CONSTRAINT pk_tratamiento_realizado PRIMARY KEY (id),

    -- El RESPALDO del orden, no el mecanismo. Ver la cabecera.
    CONSTRAINT uk_tratamiento_orden
        UNIQUE (organization_id, sesion_id, orden, deleted_key),

    CONSTRAINT fk_tratamiento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_tratamiento_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_tratamiento_sesion
        FOREIGN KEY (sesion_id) REFERENCES sesion (id),

    -- FK hacia tablas de `resource`. Lo que la regla 1 de AGENT.md seccion 4 prohibe es que
    -- `encounter` LEA o ESCRIBA esas tablas —lo hace por resource.spi—, no que el motor
    -- proteja la integridad. Mismo argumento que fk_sesion_caso en V48.
    CONSTRAINT fk_tratamiento_practica
        FOREIGN KEY (practica_id) REFERENCES practica (id),
    CONSTRAINT fk_tratamiento_espacio
        FOREIGN KEY (espacio_id) REFERENCES espacio (id),

    CONSTRAINT ck_tratamiento_orden_positivo
        CHECK (orden > 0),

    -- Mismo criterio que espacio.capacidad (V19) y oferta.duracion (V24): cero no es "menos
    -- duracion", es un dato que alguien dejo a medio cargar.
    CONSTRAINT ck_tratamiento_duracion
        CHECK (duracion_minutos IS NULL
            OR (duracion_minutos > 0 AND duracion_minutos <= 1440)),

    CONSTRAINT ck_tratamiento_lateralidad
        CHECK (lateralidad IS NULL
            OR lateralidad IN ('IZQUIERDA', 'DERECHA', 'BILATERAL')),

    -- "Derecha" de que. Es la misma validacion que V34 aplica al dolor: se valida lo que seria
    -- FALSO, no lo que falta.
    CONSTRAINT ck_tratamiento_lateralidad_con_zona
        CHECK (lateralidad IS NULL OR zona IS NOT NULL),

    -- El snapshot del espacio viaja con el espacio o no viaja. Un nombre sin id no se puede
    -- volver a resolver; un id sin nombre obliga a resolverlo para mostrarlo, que es lo que el
    -- snapshot existe para evitar.
    CONSTRAINT ck_tratamiento_espacio_snapshot
        CHECK ((espacio_id IS NULL AND espacio_nombre IS NULL)
            OR (espacio_id IS NOT NULL AND espacio_nombre IS NOT NULL)),

    -- Coherencia de la baja logica: los tres campos se mueven juntos o no se mueven. Sin esto
    -- es posible active = 0 con deleted_at NULL, que ademas rompe el centinela del unique
    -- porque la fila cae en 1970 junto con las vigentes.
    CONSTRAINT ck_tratamiento_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- La lectura de la etapa: los tratamientos de una sesion, en orden. Es la unica consulta
    -- que la pantalla hace, y con orden adentro el ORDER BY sale del indice.
    INDEX ix_tratamiento_sesion (organization_id, sesion_id, orden),

    -- La consulta que hace posible cerrar el hueco de 04.05: que practicas se realizaron en
    -- esta sesion, para elegir la autorizacion correcta dentro de la transaccion del cierre.
    INDEX ix_tratamiento_practica (organization_id, practica_id, active)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Intervencion REALMENTE aplicada en una sesion (M14, RF-M14-005) con el espacio realmente utilizado (RF-M04-005). RN-M14-004: planificado no equivale a realizado, y esta tabla es el lado "realizado". UNICA tabla que vincula una atencion con una practica de M06. Propietario: modulo encounter';


-- =====================================================================================
-- tratamiento_parametro — los parametros TIPADOS de la intervencion.
--
-- Existe como tabla y no como columna `json` para que el rechazo del parametro sin tipo sea
-- ESTRUCTURAL. Ver la cabecera del archivo.
--
-- LO QUE ESTA TABLA NO SABE, Y ES UNA DECISION DECLARADA: cuales parametros son OBLIGATORIOS
-- para cada practica. `plan_sesiones.txt` seccion 10.4 los pide condicionales por tipo de
-- practica —intensidad/frecuencia/tiempo en electroterapia; series/repeticiones/carga en
-- ejercicio terapeutico— y NO EXISTE NINGUNA CONFIGURACION QUE LOS DECLARE: M06 tiene
-- practicas, no esquemas de parametros. Inventar ese catalogo aca seria decidir por el
-- usuario. Se valida que cada parametro este TIPADO, que es lo que el caso borde del plan
-- exige, y no cuales son requeridos.
-- =====================================================================================

CREATE TABLE tratamiento_parametro
(
    id                       BIGINT         NOT NULL AUTO_INCREMENT,

    organization_id          BIGINT         NOT NULL COMMENT 'Tenant propietario. Redundante con el del padre a proposito: es la regla de AGENT.md seccion 5 sin excepcion, y es lo que permite que el unique de clave sea tenant-safe sin un join',

    tratamiento_realizado_id BIGINT         NOT NULL COMMENT 'Intervencion a la que pertenece. Se borra en cascada logica desde la aplicacion al reemplazar el tratamiento: ver la cabecera del archivo',

    clave                    VARCHAR(64)    NOT NULL COMMENT 'Nombre del parametro: intensidad, frecuencia, series, repeticiones, carga. Sin lista cerrada a proposito: el vocabulario depende de la practica y M06 no lo declara',

    tipo_dato                VARCHAR(16)    NOT NULL COMMENT 'NUMERICO / TEXTO / BOOLEANO. NOT NULL es el nucleo de la etapa: el caso borde del plan pide que un parametro sin tipo SE RECHACE, y con esta columna mas el CHECK de abajo el rechazo lo hace el motor, no la disciplina de quien escriba el proximo endpoint',

    valor_numerico           DECIMAL(12, 3) NULL COMMENT 'Valor cuando tipo_dato = NUMERICO. DECIMAL y nunca float: un parametro de dosificacion clinica no puede redondear solo',
    valor_texto              VARCHAR(280)   NULL COMMENT 'Valor cuando tipo_dato = TEXTO',
    valor_booleano           TINYINT(1)     NULL COMMENT 'Valor cuando tipo_dato = BOOLEANO',

    unidad                   VARCHAR(24)    NULL COMMENT 'Unidad del valor numerico: mA, Hz, min, kg, seg. NULL legitimo y no es el agujero del caso borde: hay numericos genuinamente adimensionales —series, repeticiones—. Lo que el caso borde exige es el TIPO, y ese es NOT NULL. Exigir unidad a todo numerico obligaria a inventar una para "3 series"',

    orden                    INT            NOT NULL DEFAULT 0 COMMENT 'Orden de presentacion del parametro dentro de la intervencion. Sin unique: dos parametros pueden compartir posicion sin que nada se rompa, y lo que identifica es la clave',

    created_at               DATETIME(6)    NOT NULL,

    CONSTRAINT pk_tratamiento_parametro PRIMARY KEY (id),

    -- Un parametro por clave dentro de la intervencion. Dos "intensidad" en el mismo
    -- tratamiento no son dos datos: son uno mal cargado dos veces, y cual gana seria
    -- indefinido.
    CONSTRAINT uk_tratamiento_parametro_clave
        UNIQUE (organization_id, tratamiento_realizado_id, clave),

    CONSTRAINT fk_tratamiento_parametro_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_tratamiento_parametro_tratamiento
        FOREIGN KEY (tratamiento_realizado_id) REFERENCES tratamiento_realizado (id),

    CONSTRAINT ck_tratamiento_parametro_tipo
        CHECK (tipo_dato IN ('NUMERICO', 'TEXTO', 'BOOLEANO')),

    -- EL CHECK QUE HACE ESTRUCTURAL EL RECHAZO. El valor cae en la columna que su tipo declara
    -- y en ninguna otra: un parametro NUMERICO con el numero guardado como texto —que es
    -- exactamente el "parametro legado sin tipo" del caso borde— no se puede insertar.
    CONSTRAINT ck_tratamiento_parametro_valor
        CHECK ((tipo_dato = 'NUMERICO'
                    AND valor_numerico IS NOT NULL
                    AND valor_texto IS NULL AND valor_booleano IS NULL)
            OR (tipo_dato = 'TEXTO'
                    AND valor_texto IS NOT NULL
                    AND valor_numerico IS NULL AND valor_booleano IS NULL)
            OR (tipo_dato = 'BOOLEANO'
                    AND valor_booleano IS NOT NULL
                    AND valor_numerico IS NULL AND valor_texto IS NULL)),

    -- Una unidad sin valor numerico no significa nada: "mA" de que.
    CONSTRAINT ck_tratamiento_parametro_unidad
        CHECK (unidad IS NULL OR tipo_dato = 'NUMERICO'),

    INDEX ix_tratamiento_parametro_tratamiento (organization_id, tratamiento_realizado_id, orden)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Parametro TIPADO de una intervencion realizada (M14, plan_sesiones 10.4). Es tabla y no json para que el rechazo del parametro sin tipo lo haga el motor. NO declara que parametros exige cada practica: M06 no tiene esquemas de parametros y inventarlos seria decidir por el usuario. Propietario: modulo encounter';
