-- =====================================================================================
-- AKINE-08.04 — Derivacion de participante al circuito clinico (M28 / M09 / M10 / M11).
--
-- V63 y no V62: V62 la reservo 07.07 y V64 la reservo 08.06, las dos en vuelo ahora
-- mismo en otros worktrees. El numero se reserva ANTES de escribir codigo porque V26
-- quedo vacia para siempre cuando 02.07 y 03.01 nacieron las dos como V26: Flyway no
-- arranca con versiones duplicadas.
--
-- RF-M28-008; RF-M09-007, RF-M09-008; RF-M10-007, RF-M10-008; RF-M11-007, RF-M11-008.
--
-- ---------------------------------------------------------------------------------------
-- LAS CINCO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. DERIVAR NO ES ATENDER, Y ESTA TABLA NO ES UNA SESION.
--    Hay CUATRO hechos distintos y esta migracion agrega el tercero:
--
--        inscripcion_clase      la persona tiene RESERVADO un lugar         (08.02)
--        asistencia_actividad   la persona ESTUVO en esta clase             (08.03)
--        derivacion_clinica     esta participacion PERTENECE a este Caso    (esta etapa)
--        sesion                 se le PRESTO una atencion documentada       (08.05)
--
--    Ninguna transicion administrativa prueba que hubo prestacion (DP-05). Esta fila no
--    numera nada dentro del Caso, no descuenta una sola unidad de autorizacion y no
--    aparece en el timeline clinico. Las tres cosas son de 08.05.
--
-- 2. LA TABLA ES DE `clinical`, NO DE `activity`, Y ESA ES LA DECISION CENTRAL.
--    El reflejo apunta al otro lado —la etapa se llama "derivacion de participante" y el
--    comando entra por una ruta de clases—. Igual es de clinical, por tres motivos:
--
--      a) El vinculo ES un hecho clinico: de donde vino este paciente y por que esta en
--         este Caso. DP-03 exige permiso, relacion asistencial o justificacion declarada
--         para tocarlo, y 04.01 fijo que las LECTURAS clinicas se auditan, no solo las
--         mutaciones.
--      b) Esa politica es privada de clinical.application (AutorizacionClinica y
--         AccesoClinico son package-private). Con la fila en activity habria que
--         exportarla o —mucho mas probable— reescribirla peor, y DP-03 tendria DOS
--         implementaciones. Solo una se acordaria de auditar la lectura.
--      c) El riesgo concreto y no el teorico: el detalle operativo de 08.03
--         (RF-M28-009) lo mira un instructor NO clinico. Con la fila en activity, un
--         caso_clinico_id quedaria a un JOIN de esa respuesta. El plan pide, textual,
--         "sin copiar PHI al modulo grupal".
--
-- 3. EL ANCLA ES LA ASISTENCIA, NO LA INSCRIPCION, Y NO SE DERIVA A QUIEN NO VINO.
--    08.03 lo dejo escrito: la fila de asistencia_actividad con su unique es el ancla de
--    la que cuelga el vinculo. Una inscripcion es una reserva; derivarla seria abrir un
--    contexto clinico para alguien que quizas nunca aparecio. La aplicacion exige
--    resultado.estuvo() —PRESENTE o PRESENTE_TARDE—. Si el mostrador marco ausente por
--    error, el camino es CORREGIR la asistencia (08.03 dejo la correccion con motivo),
--    no forzar la derivacion.
--
-- 4. NO HAY FK HACIA asistencia_actividad, Y ES DELIBERADO.
--    asistencia_actividad es de `activity`. Una FK desde aca seria clinical -> activity
--    EN EL ESQUEMA, justo al reves de la dependencia de codigo que esta etapa introduce
--    (activity -> clinical.spi, medida con ArchUnit y control negativo antes de escribir
--    dominio). No es lo mismo que la FK asistencia_actividad -> persona de 08.03: esa
--    acompana a activity -> person.spi, va en la MISMA direccion. Esta iria en contra y
--    dejaria al motor impidiendo que activity borre filas que clinical referencia.
--
--    Lo que se paga: participacion_id puede quedar colgado si alguien borra fisicamente
--    una asistencia. Nadie puede — regla maestra 10, y su puerto no expone delete.
--
-- 5. UNA DERIVACION SE REVIERTE, NO SE DA DE BAJA, Y LA DISTINCION NO ES DE VOCABULARIO.
--    Una baja logica dice "esta fila ya no cuenta para nadie". Una reversion dice "este
--    paciente NO pertenece a este Caso, y aca esta quien lo decidio y por que". Lo
--    segundo es el dato que alguien va a querer leer. Por eso no esta el cuarteto
--    active/deleted_at/deactivation_reason/deleted_key de siempre: estan estado,
--    revertida_en, revertida_por_cuenta_id y motivo_reversion.
--
--    revertida_key cumple el papel que deleted_key cumple en el resto del repositorio:
--    en MySQL varios NULL no colisionan, asi que sin el centinela '1970-01-01' el unique
--    de destino protegeria justo lo que no importa. Con el, volver a derivar despues de
--    revertir funciona — una reversion que no deja volver a derivar no revierte nada.
--
-- ---------------------------------------------------------------------------------------
-- LO QUE ESTA MIGRACION NO HACE
--
--   * No agrega una columna a asistencia_actividad. Un caso_clinico_id ahi viajaria en
--     cada respuesta del mostrador, y no se puede auditar una columna al leerse.
--   * No crea ninguna tabla de Sesion ni toca `sesion`. Es 08.05.
--   * No toca oferta_servicio_consultorio: requiere_caso_clinico y genera_registro_clinico
--     existen desde V24. Lo que faltaba era que VIAJARAN, y eso es un record de offering,
--     no una columna nueva.
--   * No reescribe ningun CHECK existente. Vale decirlo porque esta semana costo un
--     defecto real: DROP CHECK mas ADD CONSTRAINT reescribe la lista ENTERA, y V57
--     borro asi el valor que V56 acababa de agregar sin que git viera conflicto.
-- =====================================================================================

CREATE TABLE derivacion_clinica
(
    id                      BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id         BIGINT       NOT NULL COMMENT 'Tenant propietario. AGENT.md seccion 5: toda tabla de negocio lo lleva y todo unique e indice de esta tabla empieza por el',
    consultorio_id          BIGINT       NOT NULL COMMENT 'Sede donde OCURRIO la derivacion, congelada. La HC es de la organizacion (DP-03) y la derivacion no: paso en una sede concreta, y AutorizacionClinica evalua el permiso contra la sede del contexto. Sin esta columna la fila no se puede explicar seis meses despues',

    origen                  VARCHAR(24)  NOT NULL COMMENT 'De que clase de participacion viene. Hoy CLASE_PROGRAMADA y nada mas. Existe para que 08.05 y lo que venga despues no tengan que adivinar de que tabla es participacion_id',
    participacion_id        BIGINT       NOT NULL COMMENT 'asistencia_actividad.id. SIN FK a proposito: ver el punto 4 de la cabecera',

    persona_id              BIGINT       NOT NULL COMMENT 'Redundante con la historia y NO sobra: sin el, una derivacion podria quedar atada a la historia de otra persona del mismo tenant y el error seria invisible. Mismo criterio con el que AutorizacionDirectory exige personaId',

    historia_clinica_id     BIGINT       NOT NULL COMMENT 'La HC de esa persona en esa organizacion. Se ASEGURA al derivar —idempotente por unique desde 04.01— y por eso nunca es NULL',
    caso_clinico_id         BIGINT       NOT NULL COMMENT 'El Caso al que se deriva (RF-M10-008). TIENE que existir: esta etapa no abre Casos. Abrir uno pide diagnostico presuntivo, objetivo terapeutico y equipo, o sea contenido clinico en el cuerpo de un comando de activity, que es copiar PHI al modulo grupal',
    plan_tratamiento_id     BIGINT       NULL COMMENT 'Plan del Caso (RF-M11-007). NULL legitimo: un Caso sin plan activo se deriva igual. Se valida que pertenezca al caso_clinico_id de esta misma fila',

    oferta_id               BIGINT       NOT NULL COMMENT 'La oferta clinica que JUSTIFICO la derivacion, congelada. Si manana le apagan genera_registro_clinico, esta fila sigue explicando por que se derivo entonces',
    requiere_caso_clinico   TINYINT(1)   NOT NULL COMMENT 'Copia congelada de oferta.requiere_caso_clinico (RF-M10-007). No decide si se puede derivar —derivar ES elegir el Caso, y siempre lo exige—: decide si 08.05 va a exigir Caso al atender. Viaja aca para que 08.05 no tenga que volver a la oferta',

    autorizacion_id         BIGINT       NULL COMMENT 'Autorizacion declarada (RF-M11-008). OPCIONAL: el alcance vigente es el Circuito Particular y un paciente particular no tiene ninguna. Si viene, tiene que habilitar contra el dia LOCAL de la sede. NO se consume: consumir es de person y lo dispara el cierre de una Sesion, que es 08.05',

    estado                  VARCHAR(16)  NOT NULL COMMENT 'VIGENTE o REVERTIDA. No hay tercer valor: derivar es un hecho binario',

    motivo                  VARCHAR(300) NULL COMMENT 'Por que se derivo. Opcional: el motivo clinico de fondo vive en el Caso, no aca',
    derivada_en             DATETIME(6)  NOT NULL COMMENT 'Instante UTC',
    derivada_por_cuenta_id  BIGINT       NOT NULL COMMENT 'Quien derivo. NOT NULL: no hay derivacion automatica en esta etapa',

    revertida_en            DATETIME(6)  NULL,
    revertida_por_cuenta_id BIGINT       NULL,
    motivo_reversion        VARCHAR(300) NULL COMMENT 'OBLIGATORIO al revertir. Deshacer un vinculo clinico sin decir por que no es auditable',

    revertida_key           DATETIME(6) AS (IFNULL(revertida_en, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique de destino: el instante de la reversion, o el centinela 1970-01-01 mientras la derivacion este vigente. Existe SOLO para que el unique funcione. Ver el punto 5 de la cabecera',

    version                 BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno en silencio',
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    CONSTRAINT pk_derivacion_clinica PRIMARY KEY (id),

    CONSTRAINT fk_derivacion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_derivacion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),

    CONSTRAINT fk_derivacion_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id),

    CONSTRAINT fk_derivacion_historia
        FOREIGN KEY (historia_clinica_id) REFERENCES historia_clinica (id),

    CONSTRAINT fk_derivacion_caso
        FOREIGN KEY (caso_clinico_id) REFERENCES caso_clinico (id),

    CONSTRAINT fk_derivacion_plan
        FOREIGN KEY (plan_tratamiento_id) REFERENCES plan_tratamiento (id),

    CONSTRAINT fk_derivacion_oferta
        FOREIGN KEY (oferta_id) REFERENCES oferta_servicio_consultorio (id),

    -- UNA PARTICIPACION SE DERIVA UNA SOLA VEZ POR DESTINO.
    --
    -- Es la regla del plan, literal, y "por destino" es la parte que importa: dos
    -- derivaciones VIGENTES de la misma participacion al MISMO Caso son imposibles; a
    -- Casos DISTINTOS son posibles y es deliberado. Un paciente en rehabilitacion de
    -- rodilla y de hombro tiene dos Casos abiertos, y una misma clase puede tocar los
    -- dos. Lo que se garantiza es que sean dos filas explicitas, cada una con su actor y
    -- su motivo, y no una fila que cambia de Caso en silencio.
    --
    -- El doble submit choca aca y NO produce un 500: la aplicacion devuelve la derivacion
    -- que ya existe. Dos capas, como PerfilPacienteService y como 06.05 — el pre-chequeo
    -- resuelve el caso comun y el unique cierra la ventana de carrera que ningun SELECT
    -- previo cierra.
    CONSTRAINT uk_derivacion_destino
        UNIQUE (organization_id, origen, participacion_id, caso_clinico_id, revertida_key),

    -- Lista cerrada. Mismo criterio que espacio.tipo (V19) y clase.estado (V58): el
    -- conjunto es del producto y no del tenant.
    CONSTRAINT ck_derivacion_origen
        CHECK (origen IN ('CLASE_PROGRAMADA')),

    CONSTRAINT ck_derivacion_estado
        CHECK (estado IN ('VIGENTE', 'REVERTIDA')),

    -- Coherencia de la reversion: los tres campos se mueven juntos o no se mueven. Sin
    -- esto es posible estado = REVERTIDA con revertida_en NULL, que ademas rompe el
    -- centinela del unique porque la fila caeria en 1970 junto con las vigentes.
    CONSTRAINT ck_derivacion_reversion_coherente
        CHECK ((estado = 'VIGENTE'
                AND revertida_en IS NULL
                AND revertida_por_cuenta_id IS NULL
                AND motivo_reversion IS NULL)
            OR (estado = 'REVERTIDA'
                AND revertida_en IS NOT NULL
                AND revertida_por_cuenta_id IS NOT NULL
                AND motivo_reversion IS NOT NULL))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Vinculo auditable entre una participacion grupal y su contexto clinico (RF-M28-008). Propietario: modulo clinical. NO es una atencion: no numera dentro del Caso, no consume autorizacion y no llega al timeline. Eso es 08.05';


-- La consulta de estado de la pantalla: "esta participacion, ya esta derivada?".
CREATE INDEX ix_derivacion_participacion
    ON derivacion_clinica (organization_id, origen, participacion_id);

-- Lo que 08.05 va a recorrer para atender en lote las participaciones ya derivadas a un
-- Caso. Con id adentro el ORDER BY sale del indice.
CREATE INDEX ix_derivacion_caso
    ON derivacion_clinica (organization_id, caso_clinico_id, id);
