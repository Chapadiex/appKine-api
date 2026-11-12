-- =====================================================================================
-- AKINE-06.02 — Evaluacion base y modo Sesion rapida (M14).
--
-- RF-M14-003; `plan_sesiones.txt` §§8, 13.1 y 15.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE COLUMNAS TIPADAS Y NO MAS JSON
--
-- AKINE-06.01 guarda el borrador en la columna `borrador`, que es JSON opaco: en esa
-- etapa nadie sabia todavia que campos tiene una evaluacion, y darle esquema habria
-- fijado en la base un formulario no decidido. Esta etapa SI lo decide, y el requisito
-- que obliga al cambio es literal: "campos/estructura que permitan consultar dolor,
-- funcion y cambio". Un JSON no se consulta ni se indexa; una evolucion clinica que no
-- se puede comparar entre sesiones no sirve para nada.
--
-- Las dos representaciones conviven y NO se pisan, y conviene tenerlo claro:
--
--   * Las columnas de abajo son la EVALUACION, y son la fuente de verdad.
--   * `borrador` sigue siendo el bloc de notas del formulario en curso: lo que la
--     pantalla tenga y que todavia no tenga columna. El examen completo y las
--     mediciones son AKINE-06.03, que el Paquete B de DP-10 dejo afuera, asi que ese
--     espacio va a seguir usandose un tiempo.
--
-- ---------------------------------------------------------------------------------------
-- LAS CUATRO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. TODO ES NULLABLE, Y ES UNA REGLA DE NEGOCIO, NO UNA COMODIDAD. "Seguimiento no
--    exige examen completo": una sesion de seguimiento carga dolor y evolucion y nada
--    mas. Poner NOT NULL en cualquiera de estos campos obligaria a inventar datos
--    clinicos para poder guardar, que es exactamente lo que no se puede hacer.
--
-- 2. EL DOLOR SE VALIDA EN LA BASE, CON CHECK. Es una escala EVA de 0 a 10 y un valor
--    fuera de rango no es un error de tipeo: es un dato clinico falso que despues
--    alguien promedia. La validacion de aplicacion existe igual —da un mensaje que
--    nombra el problema— pero la que impide la fila corrupta es esta, y vale tambien
--    para cualquier escritura que no pase por JPA.
--
-- 3. `motivo_clinico` NO ES UN DIAGNOSTICO, y por eso es texto libre y no una FK al
--    catalogo. La regla de la etapa lo dice: "motivo clinico se distingue de diagnostico
--    medico". El motivo es lo que trae al paciente —"dolor lumbar al agacharse"— y lo
--    escribe el kinesiologo; el diagnostico es un acto medico que este sistema no
--    registra. Ponerle una FK al nomenclador los confundiria en el esquema.
--
-- 4. EL INDICE DE COMPARACION ES EL QUE HACE POSIBLE "EL CAMBIO". Comparar con la sesion
--    previa significa buscar la ultima sesion anterior de la MISMA historia clinica con
--    dolor cargado. Sin el indice esa consulta recorre toda la historia del paciente en
--    cada apertura de sesion.
-- =====================================================================================

ALTER TABLE sesion
    -- Modo de carga. RAPIDA es el de seguimiento —dolor, evolucion y poco mas— y es el
    -- caso mayoritario en un centro de kinesiologia: la mayoria de las sesiones de un
    -- tratamiento son seguimiento, no primera consulta.
    ADD COLUMN modo                 VARCHAR(16)  NULL
        COMMENT 'RAPIDA o COMPLETA. Ver el punto 1: ninguno exige mas campos que el otro.'
        AFTER estado,

    ADD COLUMN motivo_clinico       VARCHAR(500) NULL
        COMMENT 'Lo que trae al paciente, en palabras del profesional. NO es un diagnostico: ver el punto 3.'
        AFTER modo,

    ADD COLUMN dolor_eva            INT          NULL
        COMMENT 'Escala visual analogica, 0 a 10. INT y no TINYINT: el mapeo Java es Integer y `ddl-auto: validate` rechaza el tipo mas chico — el contexto de Spring ni arranca, y el sintoma es que fallan TODOS los tests de integracion a la vez.'
        AFTER motivo_clinico,

    ADD COLUMN dolor_zona           VARCHAR(120) NULL
        COMMENT 'Zona corporal referida.'
        AFTER dolor_eva,

    ADD COLUMN dolor_lateralidad    VARCHAR(16)  NULL
        COMMENT 'IZQUIERDA, DERECHA, BILATERAL o NO_APLICA.'
        AFTER dolor_zona,

    -- Evolucion percibida respecto de la sesion anterior. Es la mitad "cambio" del
    -- requisito, y viaja como enum y no como texto justamente para poder consultarla.
    ADD COLUMN evolucion            VARCHAR(16)  NULL
        COMMENT 'MEJOR, IGUAL, PEOR o SIN_REFERENCIA.'
        AFTER dolor_lateralidad,

    ADD COLUMN objetivo_sesion      VARCHAR(500) NULL
        COMMENT 'Que se busca en ESTA sesion. Distinto del objetivo del Plan de Tratamiento (M11).'
        AFTER evolucion,

    ADD COLUMN limitacion_funcional VARCHAR(500) NULL
        COMMENT 'Que no puede hacer el paciente hoy. Es la mitad "funcion" del requisito.'
        AFTER objetivo_sesion,

    ADD COLUMN evaluada_en          DATETIME(6)  NULL
        COMMENT 'Cuando se guardo la evaluacion por ultima vez. Distinto de borrador_guardado_en.'
        AFTER limitacion_funcional,

    -- Punto 2: la que impide la fila corrupta.
    ADD CONSTRAINT ck_sesion_dolor_eva
        CHECK (dolor_eva IS NULL OR (dolor_eva >= 0 AND dolor_eva <= 10)),

    ADD CONSTRAINT ck_sesion_modo
        CHECK (modo IS NULL OR modo IN ('RAPIDA', 'COMPLETA')),

    ADD CONSTRAINT ck_sesion_lateralidad
        CHECK (dolor_lateralidad IS NULL
            OR dolor_lateralidad IN ('IZQUIERDA', 'DERECHA', 'BILATERAL', 'NO_APLICA')),

    ADD CONSTRAINT ck_sesion_evolucion
        CHECK (evolucion IS NULL
            OR evolucion IN ('MEJOR', 'IGUAL', 'PEOR', 'SIN_REFERENCIA')),

    -- La lateralidad sin zona no dice nada: "derecha" de que. Al reves si es legitimo
    -- —una zona central como la lumbar no tiene lado— y por eso la implicacion va en un
    -- solo sentido.
    ADD CONSTRAINT ck_sesion_lateralidad_con_zona
        CHECK (dolor_lateralidad IS NULL OR dolor_zona IS NOT NULL);


-- Punto 4: la consulta de "la sesion previa con dolor cargado de esta historia clinica".
CREATE INDEX ix_sesion_comparacion
    ON sesion (organization_id, historia_clinica_id, iniciada_en, dolor_eva);
