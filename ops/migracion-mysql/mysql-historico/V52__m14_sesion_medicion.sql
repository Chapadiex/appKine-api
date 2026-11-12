-- =====================================================================================
-- AKINE-06.03 — El valor medido en una sesion (M14).
--
-- Trazabilidad: RF-M14-004 (examen fisico, mediciones y comparacion con la sesion anterior);
-- RN-M14-001 y DP-05 (Turno != Sesion: ninguna transicion administrativa prueba que una
-- prestacion ocurrio); regla maestra 1 (HC != Caso != Sesion) y 10 (nada historico se borra).
-- DP-03 / ADR-0010 (la HC es de la ORGANIZACION), ADR-0011 (requisitos clinico-legales).
--
-- ADR-0003, ADR-0004, ADR-0007.
--
-- Propietario: modulo `encounter`, que ya es dueño de `sesion`. `resource` NO SABE que existen
-- mediciones tomadas: la dependencia es de un solo sentido, `encounter -> resource.spi`, y esa
-- arista se VERIFICO con una clase sonda contra `ModuleArchitectureTest` antes de escribir una
-- linea de servicio — no se supuso. El challenge de 04.05 dio por buena una arista de memoria
-- y era falsa; `SlicesRuleDefinition` busca ciclos de CUALQUIER longitud y la cabeza solo
-- encuentra los de dos saltos.
--
-- POR QUE V52: reservada junto con V51 en la seccion 11 del diseño, antes de escribir codigo.
-- Migracion unica, no expandir-migrar-contraer: la tabla nace vacia (mismo criterio que V20,
-- V45, V47, V49 y V51).
--
-- ---------------------------------------------------------------------------------------
-- POR QUE UNA FILA POR MEDICION Y NO COLUMNAS NI JSON
--
-- 06.02 (V34) convirtio la evaluacion base en COLUMNAS de `sesion` porque son siete campos
-- fijos que toda sesion tiene. El examen fisico no es eso: son decenas de medidas, distintas
-- por especialidad y EXTENSIBLES POR EL CENTRO. Una columna por medida es un ALTER TABLE por
-- cada test nuevo, y para un test que solo usa un centro seria una columna vacia en todos los
-- demas tenants.
--
-- Y la respuesta no es volver al JSON de 06.01: el registro de 06.02 ya lo dejo fijado —"un
-- JSON no se consulta ni se indexa"— y la comparacion entre sesiones es exactamente una
-- consulta. Una fila por medicion, contra un catalogo.
--
-- ---------------------------------------------------------------------------------------
-- EL SNAPSHOT ES LA CONDICION DURA DE LA ETAPA, NO UNA OPTIMIZACION
--
-- `definicion_codigo`, `definicion_nombre`, `definicion_unidad`, `definicion_tipo` y
-- `definicion_version` son COPIAS de lo que decia `medicion_definicion` en el instante del
-- registro. Sin ellas, un UPDATE sobre el catalogo —cambiar la unidad de cm a mm, corregir el
-- nombre de un test— REESCRIBE EL SIGNIFICADO DE TODAS LAS MEDICIONES PASADAS sin tocar una
-- sola fila de esta tabla. Es reescritura de historia clinica por la puerta de atras, y no la
-- veria ningun test de la etapa porque el dato cambia en otra tabla.
--
-- Es el mismo snapshot que congela el importe en la obligacion (V36, 07.01) y el nombre de la
-- oferta en el item del plan (V49, 04.04). El `definicion_id` se conserva igual, para poder
-- agrupar la misma medida a lo largo del tiempo; lo que NO se hace es resolver el significado
-- por join al leer.
--
-- Consecuencia asumida y visible en la comparacion: si la unidad cambio entre dos sesiones,
-- las dos filas dicen unidades DISTINTAS y la API muestra las dos SIN calcular delta. Los 90
-- de marzo y los 90 de septiembre no son el mismo numero.
--
-- ---------------------------------------------------------------------------------------
-- `BILATERAL` NO EXISTE, Y ES UNA DECISION DE MODELO
--
-- `lateralidad` toma IZQUIERDA, DERECHA o NO_APLICA. Una medicion bilateral son DOS FILAS.
--
-- El valor de los dos lados ES DISTINTO —ese es el punto de medirlos— asi que una unica fila
-- BILATERAL obligaria a guardar dos numeros en un campo o a promediarlos, que es perder
-- exactamente la informacion que la medicion existe para capturar. NO_APLICA es para lo que no
-- tiene lado: frecuencia cardiaca, Borg, saturacion.
--
-- OJO: `sesion.dolor_lateralidad` (V34) SI admite BILATERAL y no es una incoherencia. Alla es
-- la descripcion de UN sintoma referido —"me duelen las dos rodillas"— y aca es la identidad
-- de UNA medida. Son dos enums distintos a proposito.
--
-- Por eso `lateralidad` entra al UNIQUE: es lo que impide que el lado derecho pise al
-- izquierdo, y lo que hace del PUT una operacion idempotente por (sesion, definicion, lado)
-- — repetir el autosave ACTUALIZA, no inserta.
--
-- ---------------------------------------------------------------------------------------
-- EL CHECK DE VALOR: EXACTAMENTE UNO, NI CERO NI DOS
--
-- Un valor numerico guardado en la columna de texto es el principio de una medicion que
-- despues nadie puede comparar, y una fila con las tres columnas en NULL es una medicion que
-- no mide nada. El CHECK exige la columna que corresponde al `definicion_tipo` COPIADO en la
-- fila —no al tipo actual del catalogo, que puede haber cambiado—, y ninguna otra.
--
-- `valor_numerico` es DECIMAL(10,3) y jamas float: AGENT.md seccion 5, y aca importa igual que
-- en los importes.
--
-- ---------------------------------------------------------------------------------------
-- SIN BAJA LOGICA, Y NO CONTRADICE LA REGLA MAESTRA 10
--
-- Esta tabla no lleva el cuarteto `active`/`deleted_at`/`deactivation_reason`/`deleted_key`:
-- el borrado es FISICO y esta acotado a la sesion EN CURSO, que es el "borre una medicion que
-- cargue por error" del autosave. Sobre una sesion CERRADA la operacion no existe, y eso lo
-- decide el servicio, no la pantalla.
--
-- Es el mismo criterio con el que 04.04 admitio borrar items de un plan en BORRADOR: lo que
-- nunca se cerro no es informacion historica. Enmendar una sesion cerrada es 06.06.
--
-- ---------------------------------------------------------------------------------------
-- LA COMPARACION NO SE GUARDA
--
-- No hay columna de delta ni FK a "la medicion anterior". La comparacion se CALCULA AL LEER,
-- igual que el timeline de 04.02, el avance del plan de 04.04 y la disponibilidad efectiva de
-- 02.04. Guardarla seria una segunda copia de la verdad que miente el dia que alguien enmiende
-- la sesion anterior.
--
-- El indice `ix_sesion_medicion_definicion` es el que la sostiene: dada una sesion anterior y
-- un conjunto de definiciones, trae sus valores sin recorrer la historia del paciente.
-- =====================================================================================

CREATE TABLE sesion_medicion
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,

    organization_id    BIGINT        NOT NULL COMMENT 'Tenant. Se lleva en la fila aunque sea derivable de la sesion, por el mismo motivo que todas las tablas de F4: encabeza el unique y todo indice, y un unique sin alcance de tenant es un bug de aislamiento (AGENT.md seccion 5)',
    sesion_id          BIGINT        NOT NULL COMMENT 'La ATENCION en la que se tomo la medida. Cuelga de la sesion y no del turno: DP-05, un turno es una reserva y no prueba que nada haya ocurrido',

    definicion_id      BIGINT        NOT NULL COMMENT 'Que se midio. Se conserva para poder agrupar la misma medida a lo largo del tiempo; el SIGNIFICADO, en cambio, esta copiado en las columnas de abajo y no se resuelve por join',

    -- El snapshot. Ver la cabecera: sin esto, un UPDATE al catalogo reescribe el pasado.
    definicion_codigo  VARCHAR(48)   NOT NULL COMMENT 'Copia del codigo del catalogo en el instante del registro',
    definicion_nombre  VARCHAR(160)  NOT NULL COMMENT 'Copia del nombre en el instante del registro. Es lo que hace legible la medicion sin resolver nada, y lo que sobrevive a un renombre del test',
    definicion_unidad  VARCHAR(24)   NULL COMMENT 'Copia de la unidad. NULL solo cuando el tipo copiado es TEXTO o BOOLEANO. Si el catalogo migra de cm a mm, las mediciones viejas siguen diciendo cm',
    definicion_tipo    VARCHAR(16)   NOT NULL COMMENT 'Copia del tipo. Es el que manda sobre el CHECK de valor: si el catalogo cambia de tipo, las filas viejas siguen siendo coherentes con el tipo que tenian',
    definicion_version BIGINT        NOT NULL COMMENT 'Version de la definicion vigente al registrar. Es lo que permite decir contra QUE redaccion del test se tomo esta medicion, incluidos su rango y su unidad de entonces',

    lateralidad        VARCHAR(16)   NOT NULL COMMENT 'IZQUIERDA, DERECHA o NO_APLICA. BILATERAL NO EXISTE: una medicion bilateral son dos filas. Ver la cabecera',

    valor_numerico     DECIMAL(10,3) NULL COMMENT 'Valor de los tipos NUMERICO y ESCALA. DECIMAL y jamas float: un ROM de 92,5 en binario flotante deja de ser comparable consigo mismo',
    valor_texto        VARCHAR(500)  NULL COMMENT 'Valor del tipo TEXTO: hallazgo descriptivo del examen',
    valor_booleano     TINYINT(1)    NULL COMMENT 'Valor del tipo BOOLEANO: un signo presente o ausente',

    nota               VARCHAR(280)  NULL COMMENT 'Aclaracion del profesional sobre como se tomo la medida —"con dolor", "asistido"—. No reemplaza al valor y no entra en ninguna comparacion',

    registrada_en      DATETIME(6)   NOT NULL COMMENT 'Ultima escritura de esta medicion. El autosave la mueve; el instante del hecho clinico es el de la sesion',
    registrada_por_cuenta_id BIGINT  NOT NULL COMMENT 'Quien la cargo. Nunca NULL: una medicion no la escribe el sistema',

    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,

    CONSTRAINT pk_sesion_medicion PRIMARY KEY (id),

    -- Una medicion por test y por lado en cada sesion. Es lo que hace idempotente al PUT del
    -- autosave y lo que impide que un lado pise al otro.
    CONSTRAINT uk_sesion_medicion
        UNIQUE (organization_id, sesion_id, definicion_id, lateralidad),

    CONSTRAINT fk_sesion_medicion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_sesion_medicion_sesion
        FOREIGN KEY (sesion_id) REFERENCES sesion (id),
    CONSTRAINT fk_sesion_medicion_definicion
        FOREIGN KEY (definicion_id) REFERENCES medicion_definicion (id),

    CONSTRAINT ck_sesion_medicion_lateralidad
        CHECK (lateralidad IN ('IZQUIERDA', 'DERECHA', 'NO_APLICA')),

    CONSTRAINT ck_sesion_medicion_tipo
        CHECK (definicion_tipo IN ('NUMERICO', 'ESCALA', 'TEXTO', 'BOOLEANO')),

    -- Exactamente el valor que corresponde al tipo COPIADO, y ninguno mas. Ver la cabecera.
    CONSTRAINT ck_sesion_medicion_valor_tipado
        CHECK ((definicion_tipo IN ('NUMERICO', 'ESCALA')
                    AND valor_numerico IS NOT NULL
                    AND valor_texto IS NULL
                    AND valor_booleano IS NULL)
            OR (definicion_tipo = 'TEXTO'
                    AND valor_texto IS NOT NULL
                    AND valor_numerico IS NULL
                    AND valor_booleano IS NULL)
            OR (definicion_tipo = 'BOOLEANO'
                    AND valor_booleano IS NOT NULL
                    AND valor_numerico IS NULL
                    AND valor_texto IS NULL)),

    -- La unidad acompaña al numero, igual que en V51 y por el mismo motivo.
    CONSTRAINT ck_sesion_medicion_unidad
        CHECK ((definicion_tipo IN ('NUMERICO', 'ESCALA') AND definicion_unidad IS NOT NULL)
            OR (definicion_tipo IN ('TEXTO', 'BOOLEANO') AND definicion_unidad IS NULL)),

    -- Listar las mediciones de una sesion en un orden estable, que es la consulta de la
    -- pantalla de examen y la de la comparacion.
    INDEX ix_sesion_medicion_sesion (organization_id, sesion_id, definicion_id, lateralidad),
    -- El camino de la comparacion y de cualquier futura evolucion de una medida en el tiempo.
    INDEX ix_sesion_medicion_definicion (organization_id, definicion_id, sesion_id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Valor medido en una sesion, con el significado de la definicion CONGELADO en la fila (M14, RF-M14-004). Propietario: modulo encounter';
