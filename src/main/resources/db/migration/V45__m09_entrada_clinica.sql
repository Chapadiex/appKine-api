-- =====================================================================================
-- AKINE-04.02 — Entrada clinica versionada (M09).
--
-- POR QUE V45
--
-- El diseño de la etapa (docs/diseno/AKINE-04.02-timeline-y-adjuntos-clinicos.md, seccion 8)
-- reserva V45 para estas dos tablas y V46 para `adjunto_clinico`, ANTES de escribir codigo y
-- por el motivo que 02.07 y 03.01 pagaron caro: las dos nacieron como V26, Flyway rechaza
-- versiones duplicadas —"Found more than one migration with version N"— y la aplicacion no
-- arranca. Esta etapa se escribe en varios carriles a la vez, asi que el numero se reserva
-- antes y no se negocia despues. Flyway no exige versiones contiguas.
--
-- Trazabilidad: RF-M09-006 (enmiendas que preservan el original y consulta de versiones),
-- RF-M09-004 (el timeline las indexa); RN-M09-004 (los cambios clinicos sensibles son
-- trazables), RN-M09-001 (la HC no es una lista plana de sesiones); DP-03 / ADR-0010 (la HC
-- es de la ORGANIZACION), ADR-0011 (requisitos clinico-legales), DP-10 (se corta alcance, no
-- modelo); reglas maestras 1 (HC != Caso != Sesion) y 10 (nada historico se borra).
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0007 (expandir-migrar-contraer).
--
-- Propietario de las dos tablas: modulo `clinical`, el mismo de `historia_clinica`. Ningun
-- otro modulo las lee ni las escribe (AGENT.md seccion 4, regla 1). El caso que habia que
-- mirar es `encounter`: la Sesion produce evolucion clinica y podria tentar a escribir una
-- entrada por cada cierre. NO lo hace —challenge seccion 1—: si lo hiciera, la evolucion
-- viviria duplicada en `sesion.evolucion` y en `entrada_clinica_version.cuerpo` con la
-- garantia de que en algun momento discrepan. `encounter` aporta al timeline desde sus
-- propias tablas, por el spi.
--
-- Migracion unica y no expandir-migrar-contraer, mismo motivo que V19, V24, V27, V32 y V40:
-- ADR-0007 exige el ciclo de tres para CAMBIOS sobre datos existentes. Las dos tablas nacen
-- vacias.
--
-- ---------------------------------------------------------------------------------------
-- DOS TABLAS Y NO UNA COLUMNA QUE SE SOBREESCRIBE
--
-- El requisito de RF-M09-006 es que la enmienda PRESERVE el original y que las versiones se
-- puedan consultar. El reflejo —una columna `cuerpo` con `@Version` optimista encima— resuelve
-- un problema distinto: protege contra dos escrituras concurrentes y BORRA el texto anterior,
-- que es exactamente lo que no se puede perder. Es el mismo argumento por el que V32 hizo
-- filas a los antecedentes en vez de cuatro columnas TEXT.
--
-- Asi que el contenido son FILAS: `entrada_clinica_version`, una por version, la numero 1 es el
-- original y cada enmienda agrega la siguiente. La cabecera `entrada_clinica` guarda lo que
-- identifica al hecho —que es, cuando ocurrio, de que historia cuelga— y eso no cambia nunca.
--
-- Las dos cosas conviven y no se pisan: la cabecera lleva `version` para el control optimista
-- de SU PROPIO ciclo de vida (la baja logica), y las versiones de contenido son filas.
--
-- ---------------------------------------------------------------------------------------
-- LA NUMERACION ES EL UNICO PUNTO CONCURRENTE, Y SE RESUELVE EN LA CABECERA
--
-- Dos profesionales enmendando la misma entrada a la vez no se pisan —cada enmienda es una
-- fila nueva, que es la ventaja de versionar en filas— pero SI pueden numerar igual. Dos
-- `MAX(numero_version) + 1` simultaneos devuelven el mismo numero, y despues hay dos "version
-- 3" distintas y ningun criterio para desempatarlas.
--
-- Por eso el numero NO sale de un MAX: sale de `entrada_clinica.ultimo_numero_version`, que se
-- incrementa sobre la fila de la cabecera dentro de la misma transaccion que inserta la
-- version. Es el patron de 06.05 (`UPDATE ... ultimo_numero + 1`, no `MAX+1`) y la regla 2 del
-- Paquete B. El unique (organization_id, entrada_clinica_id, numero_version) es la red debajo:
-- si dos transacciones llegaran igual al mismo numero, la segunda choca contra la base y
-- reintenta, en vez de dejar el problema escrito.
--
-- Y hay una leccion de 02.07 aplicada aca a proposito: un `@Version` sobre el padre NO protege
-- una escritura que solo toca tablas hijas. Aca la escritura SI toca la cabecera —el contador
-- vive en ella— y ademas la aplicacion la lee con OPTIMISTIC_FORCE_INCREMENT, asi que el
-- perdedor de la carrera recibe conflicto y no un 200 que borro el trabajo del otro.
--
-- ---------------------------------------------------------------------------------------
-- LA ENMIENDA EXIGE MOTIVO, Y EL RECHAZO ES 400
--
-- `ck_entrada_version_motivo_de_enmienda` obliga: la version 1 NO lleva motivo —no enmienda
-- nada— y toda version posterior lo lleva. Sin motivo, una enmienda es indistinguible de una
-- correccion de tipeo y el historial deja de servir para lo unico que sirve, que es entender
-- POR QUE cambio el texto.
--
-- El rechazo es 400 y no 409 porque no hay conflicto de estado: falta un dato del pedido. Un
-- 409 le diria al profesional que reintente, y reintentar sin motivo vuelve a fallar.
--
-- ---------------------------------------------------------------------------------------
-- LAS VERSIONES NO SE DAN DE BAJA. LA ENTRADA SI
--
-- `entrada_clinica_version` no tiene `active`, y la ausencia es el diseño. Una version es un
-- hecho pasado; darla de baja seria reescribir historia clinica y ADR-0011 lo prohibe.
--
-- La cabecera si tiene el cuarteto `active`/`deleted_at`/`deactivation_reason`/`deleted_key`,
-- igual que `persona`, `perfil_paciente`, `historia_clinica` y `adjunto_administrativo`. Dar
-- de baja una entrada NO borra ninguna de sus versiones: la entrada sale del timeline y sigue
-- siendo consultable por su id, que es lo que distingue "no lo muestres" de "no existio".
--
-- `deleted_key` esta presente por consistencia con el resto del esquema y no porque hoy algun
-- unique lo necesite: `entrada_clinica` no tiene ninguna clave natural —dos evoluciones del
-- mismo dia con el mismo texto son un caso clinico legitimo, no un duplicado—. Se declara
-- igual porque el dia que aparezca una regla de unicidad, agregar una columna generada STORED
-- sobre una tabla clinica ya poblada es un ALTER caro y evitable.
--
-- ---------------------------------------------------------------------------------------
-- origen / referencia_origen: LA COLUMNA LLEGA HOY AUNQUE SOLO HAYA UN VALOR
--
-- Una entrada puede nacer a mano (`MANUAL`) o ser el registro de un hecho que produjo otro
-- modulo. HOY SOLO SE ESCRIBE `MANUAL`: ningun modulo genera entradas todavia, y el CHECK
-- admite los otros valores para que el dia que lo haga no haya que tocar el esquema.
--
-- La columna esta desde ahora, y no cuando haga falta, porque agregarla despues obliga a
-- decidir que valor llevan las entradas viejas y ninguna respuesta es buena: `MANUAL` mentiria
-- sobre las importadas y NULL rompe el CHECK. Es DP-10 literal: se corta el ALCANCE, no el
-- MODELO.
--
-- `ck_entrada_clinica_origen_trazable` hace cumplir el par: `MANUAL` no tiene referencia
-- —no hay entidad de origen que referenciar— y todo otro origen la lleva. Una entrada que dice
-- venir de una sesion sin decir de cual no es trazable, y RN-M09-004 pide justamente eso.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE NO SE MODELA, Y POR QUE
--
--   * NINGUN `caso_id`. El Caso Clinico es 04.03 y la regla maestra 1 lo separa de la HC. La
--     tentacion era inventar un "caso por defecto" para que el timeline pudiera agrupar: se
--     rechaza (challenge seccion 4). Un Caso implicito es un Caso mal hecho que 04.03 despues
--     tiene que desarmar, decidiendo que hacer con todas las entradas colgadas de un caso
--     fantasma. `caso_id` llega NULLABLE cuando 04.03 llegue, y eso es un ADD COLUMN barato.
--   * NINGUNA tabla de timeline. Se calcula al leer agregando `EventoClinicoContributor`
--     (diseño seccion 2). Una tabla de timeline es una segunda copia de la verdad que miente
--     el dia que alguien enmienda una entrada sin avisarle al proyector.
--   * NINGUN `consultorio_id`. Mismo motivo que en `historia_clinica` (V32): la entrada
--     pertenece a la historia, que es de la ORGANIZACION. La sede desde la que se escribio es
--     un dato del ACCESO y viaja en `audit_event.consultorio_id`, que es donde corresponde.
--   * NINGUN titulo. El texto vive en la version; el timeline se etiqueta con `tipo`, que es
--     una clase de hecho y no contenido clinico. El challenge seccion 4 lo fija: ningun evento
--     del timeline puede llevar texto clinico.
--   * NINGUNA tabla propia de auditoria. Ya existe `audit_event` (V5, V14) con sus triggers de
--     append-only, y ahi se escribe tambien la LECTURA (DP-03).
--   * NINGUNA FK hacia `cuenta`: esa tabla es de `identity` y una FK cruzaria la propiedad de
--     datos entre modulos. `registrada_por` es un accountId suelto, como en V32 y V40.
-- =====================================================================================

CREATE TABLE entrada_clinica
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id       BIGINT       NOT NULL COMMENT 'Tenant propietario. Todo indice de esta tabla empieza por el (AGENT.md seccion 5)',
    historia_clinica_id   BIGINT       NOT NULL COMMENT 'Historia de la que cuelga la entrada. NO cuelga de un Caso: el Caso es 04.03 y un caso por defecto seria un Caso mal hecho (challenge seccion 4)',

    tipo                  VARCHAR(24)  NOT NULL COMMENT 'Clase de hecho clinico. Lista cerrada del producto, no del tenant: es lo que etiqueta la entrada en el timeline, y su interpretacion tiene que ser la misma en todo el sistema',

    ocurrio_en            DATETIME(6)  NOT NULL COMMENT 'Instante UTC en que ocurrio el hecho clinico. NO es el de registro: una evolucion se puede cargar al final del dia y el timeline tiene que ordenarla por cuando paso, no por cuando se tipeo',

    origen                VARCHAR(24)  NOT NULL DEFAULT 'MANUAL' COMMENT 'Como nacio la entrada. Hoy solo se escribe MANUAL; el resto de los valores existen para que el dia que otro modulo genere entradas no haya que tocar el esquema (DP-10)',
    referencia_origen     BIGINT       NULL COMMENT 'Id de la entidad que origino la entrada dentro de su modulo. NULL cuando el origen es MANUAL, obligatorio en cualquier otro caso',

    ultimo_numero_version INT          NOT NULL DEFAULT 1 COMMENT 'Numero de la ultima version escrita. La enmienda numera con este contador +1 y NUNCA con MAX(numero_version): dos MAX simultaneos dan el mismo numero. Ver la cabecera',

    registrada_en         DATETIME(6)  NOT NULL COMMENT 'Instante UTC en que se registro la entrada (RN-M09-004)',
    registrada_por        BIGINT       NOT NULL COMMENT 'accountId del profesional que la registro. Sin FK a cuenta, mismo motivo que en V32',

    active                TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Vigencia de la entrada. La baja es LOGICA: la entrada sale del timeline y sigue siendo consultable por su id (regla maestra 10)',
    deleted_at            DATETIME(6)  NULL,
    deactivation_reason   VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio: una entrada clinica que desaparece sin explicacion es lo que RN-M09-004 quiere impedir',

    deleted_key           DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador de unicidad, con el mismo centinela que V27/V32/V40. Hoy ningun unique lo usa —la entrada no tiene clave natural— y se declara igual: ver la cabecera',

    version               BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista de la cabecera. La aplicacion lo fuerza a avanzar en cada enmienda (OPTIMISTIC_FORCE_INCREMENT) para que dos enmiendas concurrentes no numeren igual',
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,

    CONSTRAINT pk_entrada_clinica PRIMARY KEY (id),

    CONSTRAINT fk_entrada_clinica_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    -- RESTRICT (el default): no hay borrado fisico de una historia con entradas.
    CONSTRAINT fk_entrada_clinica_historia
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id),

    CONSTRAINT ck_entrada_clinica_tipo
        CHECK (tipo IN ('EVOLUCION', 'INDICACION', 'INTERCONSULTA', 'OBSERVACION', 'OTRO')),

    CONSTRAINT ck_entrada_clinica_origen
        CHECK (origen IN ('MANUAL', 'SESION', 'ORDEN', 'EXTERNO')),

    -- Una entrada que dice venir de otro modulo sin decir de que fila no es trazable.
    CONSTRAINT ck_entrada_clinica_origen_trazable
        CHECK ((origen = 'MANUAL' AND referencia_origen IS NULL)
            OR (origen <> 'MANUAL' AND referencia_origen IS NOT NULL)),

    CONSTRAINT ck_entrada_clinica_numeracion
        CHECK (ultimo_numero_version >= 1),

    CONSTRAINT ck_entrada_clinica_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- Los dos caminos de lectura de la etapa: las entradas de una historia y el aporte al
    -- timeline, que es el mismo filtro mas un tope por `ocurrio_en`. Empieza por
    -- organization_id como todo indice de este esquema (ADR-0004).
    INDEX ix_entrada_clinica_historia (organization_id, historia_clinica_id, active, ocurrio_en)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Cabecera de un hecho clinico registrado en una Historia Clinica (M09, RF-M09-006). El contenido vive en entrada_clinica_version, una fila por version. Propietario: modulo clinical';


CREATE TABLE entrada_clinica_version
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id    BIGINT        NOT NULL COMMENT 'Tenant propietario. Derivable de la entrada por la FK y va IGUAL: derivarlo obliga a un join para filtrar por tenant, y el dia que alguien escriba la consulta sin el join tiene una fuga que ningun test de la etapa ve, porque los tests usan un solo tenant (challenge seccion 3)',
    entrada_clinica_id BIGINT        NOT NULL COMMENT 'Entrada de la que esta version es contenido',

    numero_version     INT           NOT NULL COMMENT 'Orden de la version dentro de la entrada. La 1 es el original; cada enmienda agrega la siguiente. Sale del contador de la cabecera, nunca de un MAX: ver la cabecera de la migracion',
    cuerpo             VARCHAR(8000) NOT NULL COMMENT 'Contenido clinico de esta version, tal como lo escribio el profesional. Es texto clinico: NO se copia a la auditoria ni viaja en el timeline',
    motivo_enmienda    VARCHAR(280)  NULL COMMENT 'Por que se enmendo. NULL en la version 1 —no enmienda nada— y obligatorio en toda posterior: sin motivo, una enmienda es indistinguible de una correccion de tipeo',

    registrada_en      DATETIME(6)   NOT NULL COMMENT 'Instante UTC en que se escribio esta version (RN-M09-004)',
    registrada_por     BIGINT        NOT NULL COMMENT 'accountId del autor de esta version. Es POR VERSION y no por entrada: quien enmienda no suele ser quien escribio el original, y perder eso vaciaria el historial',

    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,

    CONSTRAINT pk_entrada_clinica_version PRIMARY KEY (id),

    -- La red debajo de la numeracion: si dos enmiendas concurrentes llegaran al mismo numero,
    -- la segunda choca contra la base en vez de dejar dos "version 3" sin criterio de
    -- desempate. La serializacion la hace el contador de la cabecera; esto la verifica.
    CONSTRAINT uk_entrada_version_numero
        UNIQUE (organization_id, entrada_clinica_id, numero_version),

    CONSTRAINT fk_entrada_version_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_entrada_version_entrada
        FOREIGN KEY (entrada_clinica_id) REFERENCES entrada_clinica (id),

    CONSTRAINT ck_entrada_version_numero
        CHECK (numero_version >= 1),

    -- RF-M09-006: la enmienda exige motivo. El original no lo lleva porque no enmienda nada.
    CONSTRAINT ck_entrada_version_motivo_de_enmienda
        CHECK ((numero_version = 1 AND motivo_enmienda IS NULL)
            OR (numero_version > 1 AND motivo_enmienda IS NOT NULL))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Contenido de una version de entrada clinica (M09, RF-M09-006). SIN active a proposito: una version es un hecho pasado y darla de baja seria reescribir historia clinica (ADR-0011). Propietario: modulo clinical';
