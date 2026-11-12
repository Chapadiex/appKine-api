-- =====================================================================================
-- AKINE-02.04 — Disponibilidad profesional: calendario de sede, bloques y excepciones (M05).
--
-- Trazabilidad: RF-M05-003 (configurar disponibilidad semanal), RF-M05-004 (registrar
-- excepcion), RF-M05-005 (modificar disponibilidad detectando conflictos);
-- RN-M05-001 (la disponibilidad pertenece a Profesional + Consultorio), RN-M05-002 (las
-- excepciones prevalecen sobre el horario base), RN-M05-003 (desvincular no elimina autoria
-- historica), RN-M05-004 (los turnos futuros afectados quedan visibles para resolucion).
--
-- ADR-0003 (Flyway unica autoridad), ADR-0004 (persistencia multi-tenant), ADR-0022
-- (feriados globales sin organization_id, V22).
--
-- Propietario de las tres tablas: modulo `resource`. Ningun otro modulo las lee ni las
-- escribe: lo que otros necesiten sale por `com.akine.resource.spi` (AGENT.md seccion 4).
--
-- ---------------------------------------------------------------------------------------
-- POR QUE consultorio_calendario ES UNA TABLA Y NO UNA COLUMNA EN consultorio
--
-- Lo natural seria `consultorio.cierra_por_feriado`. Esa tabla es del modulo `organization`
-- y este modulo es `resource`: escribirla rompe la propiedad de tablas que ArchUnit y el
-- design challenge sostienen. La tabla propia cuesta una fila por sede y mantiene la regla.
--
-- Ademas cumple un segundo rol, y por eso se crea a demanda y nunca se borra: es la fila
-- sobre la que se toma el FOR UPDATE que serializa los writes de disponibilidad de la sede.
-- Ver la cabecera de DisponibilidadService.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE hora_hasta ADMITE '24:00:00' Y NO SE PERMITE CRUZAR MEDIANOCHE
--
-- MySQL acepta '24:00:00' en una columna TIME (el rango es -838:59:59..838:59:59). Un bloque
-- nocturno se carga como DOS bloques: lunes 22:00-24:00 y martes 00:00-02:00.
--
-- Si se permitiera 22:00-02:00 en una sola fila, `hora_desde < hora_hasta` seria falso para
-- una fila valida, y toda la aritmetica de solapamiento —que es comparacion de extremos—
-- dejaria de funcionar en silencio. El costo de prohibirlo es una fila extra; el costo de
-- permitirlo es que el calculo mienta sin fallar. La misma regla aplica a las excepciones con
-- franja horaria: comparten el mismo CHECK.
--
-- ---------------------------------------------------------------------------------------
-- POR QUE NO HAY UNIQUE QUE IMPIDA EL SOLAPAMIENTO
--
-- Haria falta una exclusion constraint, y MySQL 8.4 no las tiene: son de PostgreSQL. Los
-- indices unicos parciales tampoco existen. El solapamiento se valida en aplicacion, y por
-- eso necesita el lock de consultorio_calendario: sin el, dos altas concurrentes insertan
-- dos bloques que se pisan y ninguna de las dos ve a la otra.
--
-- ---------------------------------------------------------------------------------------
-- profesional_disponibilidad — LA DOBLE VENTANA, MISMO PATRON QUE espacio (V19)
--
-- `vigencia_desde/vigencia_hasta` es la ventana OPERATIVA (desde cuando atiende ese bloque) y
-- `active/deleted_at/deactivation_reason` es el ciclo de vida ADMINISTRATIVO (bloque cargado
-- por error). Mezclarlas pierde la diferencia entre "dejo de atender los martes en marzo" y
-- "esto nunca debio existir". `vigencia_hasta` es EXCLUSIVO y NULL significa sin fin previsto,
-- que es un estado real y no un valor faltante — igual que `valid_until` en V19.
--
-- ---------------------------------------------------------------------------------------
-- disponibilidad_excepcion — membership_id NULL, hora_* NULL, y por que APERTURA no es de adorno
--
-- `membership_id NULL` significa alcance SEDE ENTERA, no un hueco. Es el mismo significado
-- que ya tiene `consultorio_id NULL` en `membership` (V10) y en `colaborador_invitacion` (V21):
-- este repositorio no inventa una tercera forma de decir "sin acotar a una entidad puntual".
--
-- `hora_desde/hora_hasta` ambos NULL significa DIA COMPLETO. Cubre el caso "excepcion parcial"
-- sin una columna de flag adicional: si vienen las horas, la excepcion recorta una franja; si
-- no vienen, tapa el dia. El CHECK exige que las dos vengan juntas o ninguna.
--
-- `APERTURA` no es decorativa. RF-M05-004 pide "ausencia, licencia, bloqueo o AMPLIACION
-- excepcional". Es lo que permite atender un feriado puntual o sumar una banda extra un sabado,
-- sobreescribiendo el cierre que `consultorio_calendario.cierra_por_feriado` aplicaria solo.
-- =====================================================================================

CREATE TABLE consultorio_calendario
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id    BIGINT      NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo unique e indice de esta tabla empieza por el',
    consultorio_id     BIGINT      NOT NULL COMMENT 'Sede cuya politica de calendario describe esta fila (RN-M05-001). Una fila por sede, creada a demanda',

    pais               CHAR(2)     NOT NULL DEFAULT 'AR' COMMENT 'ISO 3166-1 alfa-2 del calendario de feriados que aplica a esta sede. Independiente del pais de la organizacion: existe desde el dia uno para no migrar datos el dia que un centro opere en otro pais',
    cierra_por_feriado TINYINT(1)  NOT NULL DEFAULT 1 COMMENT 'Si la sede cierra automaticamente los dias marcados como feriado en la tabla global feriado (V22). Un feriado es un hecho del calendario; esta columna es la decision operativa de la sede, y una APERTURA puntual en disponibilidad_excepcion la puede sobreescribir dia por dia',

    version            BIGINT      NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 concurrent-modification en vez de pisar el cambio ajeno en silencio',
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,

    CONSTRAINT pk_consultorio_calendario PRIMARY KEY (id),

    -- Una sola fila de politica por sede. Es ademas la fila sobre la que se toma el
    -- FOR UPDATE que serializa los writes de disponibilidad de esa sede (ver la cabecera).
    CONSTRAINT uk_consultorio_calendario_sede
        UNIQUE (organization_id, consultorio_id),

    CONSTRAINT fk_consultorio_calendario_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_consultorio_calendario_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Politica de calendario de una sede (M05). Propietario: modulo resource. No vive en consultorio (modulo organization) porque escribirla rompe la propiedad de tablas: ver la cabecera de este archivo';

CREATE TABLE profesional_disponibilidad
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo unique e indice de esta tabla empieza por el',
    consultorio_id      BIGINT       NOT NULL COMMENT 'Sede a la que pertenece el bloque (RN-M05-001: la disponibilidad es Profesional + Consultorio)',
    membership_id       BIGINT       NOT NULL COMMENT 'Colaborador cuyo bloque de atencion describe esta fila. Sobrevive a la desvinculacion (RN-M05-003): el calculo la ignora porque la membership deja de estar vigente, pero la fila no se borra',

    dia_semana          TINYINT      NOT NULL COMMENT 'ISO-8601: lunes = 1 .. domingo = 7',
    hora_desde          TIME         NOT NULL COMMENT 'Hora LOCAL de la sede (consultorio.timezone, NOT NULL desde V18). Nunca UTC: un "lunes 09:00" tiene que seguir siendo 09:00 despues de un cambio de huso',
    hora_hasta          TIME         NOT NULL COMMENT 'Hora LOCAL, EXCLUSIVA. Admite 24:00:00 y no admite cruzar medianoche: ver la cabecera de este archivo',

    vigencia_desde      DATE         NOT NULL COMMENT 'Ventana OPERATIVA: desde que fecha rige este bloque. Distinta del ciclo de vida administrativo de active/deleted_at, mismo patron que valid_from en espacio (V19)',
    vigencia_hasta      DATE         NULL COMMENT 'Ventana OPERATIVA, EXCLUSIVA. NULL significa sin fin previsto, que es un estado real y no un valor faltante',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: el bloque deja de computar en la disponibilidad efectiva y conserva intacta su historia',
    deleted_at          DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras el bloque este activo',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio al dar de baja: sin el, la auditoria no responde por que seis meses despues',

    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. La edicion lo reenvia y una version vieja produce 409 concurrent-modification en vez de pisar el cambio ajeno en silencio',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_profesional_disponibilidad PRIMARY KEY (id),

    CONSTRAINT fk_profesional_disponibilidad_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_profesional_disponibilidad_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_profesional_disponibilidad_membership
        FOREIGN KEY (membership_id) REFERENCES membership (id),

    -- ISO-8601: 1 = lunes .. 7 = domingo. Fuera de rango es un dato imposible, no un caso raro.
    CONSTRAINT ck_profesional_disponibilidad_dia_semana
        CHECK (dia_semana BETWEEN 1 AND 7),

    -- hora_hasta <= '24:00:00' resuelve el cruce de medianoche sin permitirlo: ver la cabecera.
    CONSTRAINT ck_profesional_disponibilidad_horario
        CHECK (hora_hasta > hora_desde AND hora_hasta <= '24:00:00'),

    -- Coherencia temporal: una ventana que termina antes de empezar no es una ventana. El
    -- limite superior es exclusivo, asi que vigencia_hasta = vigencia_desde tampoco es valido.
    CONSTRAINT ck_profesional_disponibilidad_vigencia
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta > vigencia_desde),

    -- Coherencia de la baja logica: los tres campos se mueven juntos o no se mueven. Mismo
    -- patron que ck_espacio_baja_coherente en V19.
    CONSTRAINT ck_profesional_disponibilidad_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- La consulta que resuelve la disponibilidad efectiva siempre filtra por sede, profesional
    -- y dia de la semana, y despues acota por vigencia. Con esa columna al final el filtro de
    -- rango sale del indice sin un sort adicional.
    INDEX ix_profesional_disponibilidad_sede_profesional_dia
        (organization_id, consultorio_id, membership_id, dia_semana, vigencia_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Bloques recurrentes de atencion de un profesional en una sede (M05, RF-M05-003). Propietario: modulo resource. No hay unique que impida el solapamiento: ver la cabecera de este archivo';

CREATE TABLE disponibilidad_excepcion
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id     BIGINT       NOT NULL COMMENT 'Tenant propietario. Toda tabla de negocio lo lleva (AGENT.md seccion 5) y todo unique e indice de esta tabla empieza por el',
    consultorio_id      BIGINT       NOT NULL COMMENT 'Sede afectada por la excepcion',
    membership_id       BIGINT       NULL COMMENT 'Profesional afectado, o NULL para una excepcion de la SEDE ENTERA. Mismo significado que consultorio_id NULL en membership (V10) y en colaborador_invitacion (V21): un alcance, no un hueco',

    tipo                VARCHAR(20)  NOT NULL COMMENT 'CIERRE o APERTURA. APERTURA no es decorativa: RF-M05-004 pide ampliacion excepcional, y es lo que permite atender un feriado puntual o sumar una banda extra',
    motivo              VARCHAR(20)  NOT NULL COMMENT 'AUSENCIA, LICENCIA, FERIADO, BLOQUEO, AMPLIACION u OTRO. Lista cerrada para que un reporte pueda agrupar sin normalizar texto libre despues',

    fecha_desde         DATE         NOT NULL COMMENT 'Primera fecha calendario cubierta por la excepcion',
    fecha_hasta         DATE         NOT NULL COMMENT 'Fecha calendario limite, EXCLUSIVA',

    hora_desde          TIME         NULL COMMENT 'Hora LOCAL desde la que rige la excepcion dentro de cada dia cubierto. NULL junto con hora_hasta significa DIA COMPLETO: ver la cabecera',
    hora_hasta          TIME         NULL COMMENT 'Hora LOCAL, EXCLUSIVA, hasta la que rige la excepcion. Admite 24:00:00, mismo motivo que en profesional_disponibilidad',

    feriado_id          BIGINT       NULL COMMENT 'Feriado global (V22) que motivo esta excepcion, cuando corresponde. NULL para una excepcion sin origen en el calendario nacional (ausencia, licencia, bloqueo administrativo)',
    notes               VARCHAR(280) NULL COMMENT 'Observacion operativa libre. Dato del tenant, nunca contenido clinico',

    active              TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida administrativo. 0 tras la baja logica: la excepcion deja de computar en la disponibilidad efectiva y conserva intacta su historia',
    deleted_at          DATETIME(6)  NULL COMMENT 'Instante UTC de la baja logica. NULL mientras la excepcion este activa',
    deactivation_reason VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio al dar de baja: sin el, la auditoria no responde por que seis meses despues',

    version             BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. La edicion lo reenvia y una version vieja produce 409 concurrent-modification en vez de pisar el cambio ajeno en silencio',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    CONSTRAINT pk_disponibilidad_excepcion PRIMARY KEY (id),

    CONSTRAINT fk_disponibilidad_excepcion_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_disponibilidad_excepcion_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_disponibilidad_excepcion_membership
        FOREIGN KEY (membership_id) REFERENCES membership (id),
    CONSTRAINT fk_disponibilidad_excepcion_feriado
        FOREIGN KEY (feriado_id) REFERENCES feriado (id),

    CONSTRAINT ck_disponibilidad_excepcion_tipo
        CHECK (tipo IN ('CIERRE', 'APERTURA')),

    CONSTRAINT ck_disponibilidad_excepcion_motivo
        CHECK (motivo IN ('AUSENCIA', 'LICENCIA', 'FERIADO', 'BLOQUEO', 'AMPLIACION', 'OTRO')),

    -- Coherencia temporal: una ventana que termina antes de empezar no es una ventana. El
    -- limite superior es exclusivo, asi que fecha_hasta = fecha_desde tampoco es valido.
    CONSTRAINT ck_disponibilidad_excepcion_fechas
        CHECK (fecha_hasta > fecha_desde),

    -- Las dos horas vienen juntas o ninguna: si no vienen, la excepcion tapa el dia completo;
    -- si vienen, recortan una franja y valen las mismas reglas de hora_hasta que en el bloque
    -- recurrente (exclusiva, admite 24:00:00, no cruza medianoche).
    CONSTRAINT ck_disponibilidad_excepcion_horario
        CHECK ((hora_desde IS NULL AND hora_hasta IS NULL)
            OR (hora_desde IS NOT NULL AND hora_hasta IS NOT NULL
                AND hora_hasta > hora_desde AND hora_hasta <= '24:00:00')),

    -- Coherencia de la baja logica: los tres campos se mueven juntos o no se mueven. Mismo
    -- patron que ck_espacio_baja_coherente en V19.
    CONSTRAINT ck_disponibilidad_excepcion_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- La pantalla de calendario de sede pregunta "que excepciones caen en esta ventana",
    -- sin acotar a un profesional: cubre tanto las de sede (membership_id NULL) como las de
    -- cualquier profesional.
    INDEX ix_disponibilidad_excepcion_sede_fechas
        (organization_id, consultorio_id, fecha_desde, fecha_hasta),

    -- El calculo de la disponibilidad efectiva de UN profesional pregunta ademas por sus
    -- propias excepciones puntuales, sin traer las de otros colegas de la misma sede.
    INDEX ix_disponibilidad_excepcion_sede_profesional_fecha
        (organization_id, consultorio_id, membership_id, fecha_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Cierres y aperturas puntuales de disponibilidad (M05, RF-M05-004). Propietario: modulo resource. membership_id NULL es alcance SEDE ENTERA, no un hueco: ver la cabecera de este archivo';
