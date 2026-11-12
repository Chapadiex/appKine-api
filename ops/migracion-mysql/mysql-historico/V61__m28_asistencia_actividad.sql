-- =====================================================================================
-- AKINE-08.03 — Asistencia y operacion de clases (M28 / M13).
--
-- V61 y no V51..V60: V51-V60 estan tomadas por etapas en vuelo en otros worktrees. El
-- numero se reserva ANTES de escribir codigo porque V26 quedo vacia para siempre cuando
-- 02.07 y 03.01 nacieron las dos como V26: Flyway no arranca con versiones duplicadas.
--
-- RF-M28-007, RF-M28-009; RF-M13-007, RF-M13-008; RN-M28-006, RN-M28-007, RN-M28-009;
-- RNF-M28-001..005.
--
-- ---------------------------------------------------------------------------------------
-- LAS CINCO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. TOMAR ASISTENCIA NO ES PRESTAR UNA ATENCION CLINICA, Y UNA CLASE NO ES N SESIONES.
--    Es la regla maestra que el proyecto sostiene desde 06.01 —Sesion != Turno, DP-05—
--    trasladada al mundo grupal. Hay TRES hechos distintos y la tentacion es fundirlos:
--
--        inscripcion_clase      la persona tiene RESERVADO un lugar        (08.02)
--        asistencia_actividad   la persona ESTUVO en esta clase            (esta etapa)
--        sesion                 se le presto una atencion clinica          (08.04/08.05)
--
--    RN-M28-007 lo dice sin rodeos: una asistencia no clinica NO crea Sesion Clinica.
--    Ninguna columna de estas tablas apunta a clinical ni a encounter, y no es un olvido.
--
-- 2. LA ASISTENCIA ES UNA FILA PROPIA Y NO UN ESTADO DE LA INSCRIPCION, y hay tres
--    motivos que no son de gusto. Un estado no tiene actor, instante ni observacion
--    —RF-M28-007 paso 6 los pide—. Un estado no admite correccion trazable, y "la marque
--    ausente y habia venido" es la operacion mas frecuente de un mostrador. Y la spec
--    declara la entidad: §30.6 enumera AsistenciaActividad y §30.7 pone
--    ClaseProgramada + InscripcionClase + AsistenciaActividad como la Fase 2 obligatoria.
--
--    El estado de la inscripcion NO sobra: es la proyeccion administrativa del hecho, y
--    se escribe en LA MISMA TRANSACCION. La funcion que las une vive en un solo lugar
--    —ResultadoAsistencia.estadoDeInscripcion()— por el mismo motivo por el que
--    EstadoInscripcion.consumeCupo() vive en uno solo.
--
-- 3. EL CUPO NO SE MUEVE EN ESTA ETAPA. RESERVADA, CONFIRMADA, ASISTIO y AUSENTE
--    consumen cupo los cuatro (V60 y EstadoInscripcion): marcar asistencia va de un
--    estado que consume a otro que consume. Ninguna operacion de aca llama a tomarCupo ni
--    a liberarCupo, SALVO el ingreso sin inscripcion, que crea una inscripcion y por lo
--    tanto si pide lugar — con el MISMO UPDATE condicional de V60, para que el techo
--    LEAST(capacidad, :capacidadEfectiva) siga siendo infranqueable.
--
--    Corolario: esta etapa no agrega contencion por clase. Marcar cuarenta asistencias no
--    serializa nada contra clase_programada, porque no la escribe.
--
-- 4. LA REFERENCIA ECONOMICA UNICA ES uk_asistencia_clase_persona, Y POR ESO ESTA TABLA
--    NO TIENE deleted_at. Es una desviacion consciente de la forma de V30/V58/V60, y el
--    motivo es concreto: con baja logica, el unique necesitaria deleted_key para no
--    estorbar al historico —es lo que hace uk_inscripcion_clase_persona— y entonces DOS
--    FILAS VIVAS podrian coexistir para la misma persona en la misma clase apenas alguien
--    diera de baja una. Eso rompe justamente lo que el plan pide: que cerrar dos veces no
--    duplique obligaciones.
--
--    Una asistencia no se da de baja: SE CORRIGE. El valor vigente se pisa y el anterior
--    se APENDEA en asistencia_evento con motivo obligatorio, actor e instante. Nada se
--    borra (regla maestra 10).
--
-- 5. NO HAY NINGUN DROP CHECK EN ESTA MIGRACION, Y SE VERIFICO LEYENDO V58 Y V60, NO
--    ASUMIENDO. clase_programada.estado es un VARCHAR(16) sin CHECK de valores, asi que
--    agregarle EN_CURSO y REALIZADA no requiere reconstruir ninguna lista. Importa
--    decirlo porque DROP CHECK + ADD CONSTRAINT reescribe la lista ENTERA: V57 reconstruyo
--    un check desde el estado anterior a V56 y borro el valor que V56 acababa de agregar,
--    sin que git viera un solo conflicto porque son archivos distintos.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- clase_programada — el ciclo operativo. Ver el punto 5.
--
-- EstadoClase sube de dos valores a cuatro: PROGRAMADA, EN_CURSO, REALIZADA, CANCELADA.
-- El javadoc de V58 ya lo anticipaba —"los estados operativos llegan en 08.03"— y agregar
-- valores es aditivo: ningun cliente se rompe.
--
-- Se hace por ALTER y NO editando V58 ni V60, aunque ninguna de las dos haya corrido
-- todavia contra un motor: una migracion publicada no se toca, y la regla no admite
-- excepciones comodas.
-- -------------------------------------------------------------------------------------
ALTER TABLE clase_programada
    ADD COLUMN iniciada_en            DATETIME(6) NULL
        COMMENT 'Instante en que la clase abrio y se pudo empezar a tomar lista (RF-M13-007). No se exige que el reloj haya llegado al horario: una clase que arranca cinco minutos antes es normal.',
    ADD COLUMN iniciada_por_cuenta_id BIGINT      NULL,
    ADD COLUMN cerrada_en             DATETIME(6) NULL
        COMMENT 'Instante del cierre operativo. NO devenga nada: ver el punto 1 y la §6 del diseno.',
    ADD COLUMN cerrada_por_cuenta_id  BIGINT      NULL;


-- -------------------------------------------------------------------------------------
-- asistencia_actividad — EL HECHO OPERATIVO (§30.6 de la spec, RF-M28-007).
--
-- Una fila por participante y por clase. NO es una Sesion, NO es un Turno y NO convierte
-- a nadie en paciente: apunta a una persona del padron y no exige perfil de paciente
-- vigente (RF-M07-010). La derivacion al circuito clinico es 08.04.
-- -------------------------------------------------------------------------------------
CREATE TABLE asistencia_actividad
(
    id                        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id           BIGINT       NOT NULL,
    consultorio_id            BIGINT       NOT NULL COMMENT 'Va aunque sea derivable de la clase: derivarlo obliga a un join para filtrar por sede y habilita la consulta sin join que ningun test de una etapa detecta, porque los tests de una etapa usan un solo tenant.',
    clase_id                  BIGINT       NOT NULL,

    persona_id                BIGINT       NOT NULL COMMENT 'Persona del padron (M07). NO se exige perfil de paciente vigente.',
    inscripcion_id            BIGINT       NOT NULL COMMENT 'El recibo del que cuelga. NO HAY ASISTENCIA SIN INSCRIPCION: una asistencia huerfana es una persona adentro de la clase que el contador de cupo no ve, o sea la sobreventa por la puerta de atras. Quien llega sin inscribirse pasa por POST /ingresos, que crea la inscripcion tomando el lugar de verdad.',

    resultado                 VARCHAR(16)  NOT NULL COMMENT 'PRESENTE, PRESENTE_TARDE o AUSENTE. TARDE NO se agrega a EstadoInscripcion: RN-M28-004 enumera seis estados y un septimo para un matiz operativo convierte la maquina normativa en una lista abierta. El vocabulario rico vive aca, donde no rompe nada.',
    origen                    VARCHAR(24)  NOT NULL COMMENT 'MOSTRADOR, LOTE, CIERRE o INGRESO_SIN_INSCRIPCION. Distingue "alguien la marco" de "la puso el cierre por no-show", y esa distincion va a importar cuando el no-show cueste plata (08.07).',

    profesional_membership_id BIGINT       NULL COMMENT 'El instructor CONGELADO al momento del hecho, copiado de la clase. No se elige por participante: la clase tiene un profesional, no cuarenta, y un reemplazo es una reprogramacion de la clase, que ya existe desde 08.01 con su validacion de habilitacion. NULL cuando la oferta no exige profesional (V24).',
    observaciones             VARCHAR(500) NULL COMMENT 'OPERATIVA, NO CLINICA. "Llego a los 10 minutos", "uso la colchoneta chica". La ve cualquiera con inscripcion:read, que es precisamente por que no puede llevar informacion de salud: RNF-M28-002 lo prohibe.',

    registrada_en             DATETIME(6)  NOT NULL,
    registrada_por_cuenta_id  BIGINT       NOT NULL,

    corregida_en              DATETIME(6)  NULL,
    corregida_por_cuenta_id   BIGINT       NULL,
    correcciones              INT          NOT NULL DEFAULT 0 COMMENT 'Cuantas veces se corrigio. El detalle de cada una vive en asistencia_evento; esto es para que la lista pueda marcar la fila sin un join.',

    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    version                   BIGINT       NOT NULL DEFAULT 0,

    -- LA REFERENCIA ECONOMICA UNICA que pide el plan, y la que hace que "cerrar dos veces
    -- no duplica obligaciones" sea cierto SIN esfuerzo del lado economico. Sin deleted_key
    -- a proposito: ver el punto 4 de la cabecera.
    --
    -- Es tambien la idempotencia entera de la etapa. NO hay Idempotency-Key aca y no es un
    -- olvido: la clave natural del hecho es (clase, persona) y ya esta en este unique. Un
    -- segundo pedido con el mismo resultado devuelve la fila sin escribir; con otro
    -- resultado es una CORRECCION, que exige motivo. Misma forma que el cierre de sesion
    -- de 06.05: la idempotencia se evalua contra el hecho ocurrido, no contra una clave
    -- que el cliente tiene que acordarse de mandar — y una clave habria dejado el agujero
    -- de que dos claves distintas produzcan dos asistencias para la misma persona.
    CONSTRAINT uk_asistencia_clase_persona
        UNIQUE (organization_id, clase_id, persona_id),

    -- Un recibo, un hecho.
    CONSTRAINT uk_asistencia_inscripcion
        UNIQUE (organization_id, inscripcion_id),

    CONSTRAINT fk_asistencia_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_asistencia_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_asistencia_clase
        FOREIGN KEY (clase_id) REFERENCES clase_programada (id),

    CONSTRAINT fk_asistencia_inscripcion
        FOREIGN KEY (inscripcion_id) REFERENCES inscripcion_clase (id),

    CONSTRAINT fk_asistencia_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    -- Una correccion declara quien y cuando, o no hubo correccion. Sin esto, `correcciones`
    -- podria crecer sin que nadie quede nombrado.
    CONSTRAINT ck_asistencia_correccion_completa
        CHECK ((correcciones = 0 AND corregida_en IS NULL AND corregida_por_cuenta_id IS NULL)
            OR (correcciones > 0 AND corregida_en IS NOT NULL AND corregida_por_cuenta_id IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'El hecho de que una persona estuvo en una clase (M28, §30.6). NO es una Sesion clinica: ver el punto 1 de la cabecera de V61.';


-- El detalle operativo paginado de RF-M28-009, que es la consulta que sostiene la pantalla
-- de la clase. El id al final hace el orden total.
CREATE INDEX ix_asistencia_clase
    ON asistencia_actividad (organization_id, clase_id, id);

-- "A que clases fue esta persona". La usara la ficha 360 y la usara 08.07 para el ledger.
CREATE INDEX ix_asistencia_persona
    ON asistencia_actividad (organization_id, persona_id, id);


-- -------------------------------------------------------------------------------------
-- asistencia_evento — historial APPEND-ONLY de registros y correcciones (RN-M28-009).
--
-- Sin `version`, sin `updated_at` y sin baja. Su puerto en Java tampoco declara `update`
-- ni `delete`: la ausencia de esas operaciones es la unica garantia real de que el
-- historial sea inmutable. Misma forma que clase_evento, turno_evento, caso_evento y
-- autorizacion_movimiento.
--
-- POR QUE SE PISA EL VALOR Y SE APENDEA EL EVENTO, Y NO SE VERSIONAN FILAS COMO 04.02:
-- la entrada clinica de 04.02 es un DOCUMENTO, y sus versiones sucesivas tienen que poder
-- leerse enteras porque lo que se firmo en la version 2 importa completo. Una asistencia
-- es un HECHO CON UN SOLO VALOR VIGENTE —estuvo o no estuvo— y su historia es una
-- secuencia de correcciones. Esa es la forma de turno_evento. Versionar filas aca
-- obligaria ademas a decidir cual de las N es "la" referencia economica, que es justo lo
-- que uk_asistencia_clase_persona resuelve.
-- -------------------------------------------------------------------------------------
CREATE TABLE asistencia_evento
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id     BIGINT       NOT NULL,
    consultorio_id      BIGINT       NOT NULL,
    clase_id            BIGINT       NOT NULL,
    asistencia_id       BIGINT       NOT NULL,

    tipo                VARCHAR(16)  NOT NULL COMMENT 'REGISTRO o CORRECCION.',

    resultado_anterior  VARCHAR(16)  NULL COMMENT 'NULL en el registro inicial: no habia resultado previo.',
    resultado_nuevo     VARCHAR(16)  NOT NULL,

    motivo              VARCHAR(300) NULL COMMENT 'OBLIGATORIO para corregir, NULL al registrar. Corregir una asistencia cambia un hecho ya afirmado, y el por que es lo unico que hace auditable la correccion.',

    actor_cuenta_id     BIGINT       NULL,
    ocurrido_en         DATETIME(6)  NOT NULL,

    CONSTRAINT fk_asistencia_evento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_asistencia_evento_asistencia
        FOREIGN KEY (asistencia_id) REFERENCES asistencia_actividad (id),

    -- Una correccion sin motivo no es auditable, y una correccion que no cambia nada no es
    -- una correccion.
    CONSTRAINT ck_asistencia_evento_correccion
        CHECK (tipo <> 'CORRECCION'
            OR (motivo IS NOT NULL AND resultado_anterior IS NOT NULL
                AND resultado_anterior <> resultado_nuevo))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Historial append-only de una asistencia (RN-M28-009). No se actualiza ni se borra.';


CREATE INDEX ix_asistencia_evento
    ON asistencia_evento (organization_id, asistencia_id, ocurrido_en);
