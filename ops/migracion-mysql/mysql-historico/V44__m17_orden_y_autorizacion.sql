-- =====================================================================================
-- AKINE-03.06 — Ordenes medicas, autorizaciones y documentacion administrativa (M17/M25).
--
-- Trazabilidad: RF-M17-001 (registrar autorizacion), RF-M17-002 (adjuntar orden medica),
-- RF-M17-003 (consultar saldo autorizado), RF-M17-006 (alertar vencimiento/agote),
-- RF-M17-007 (validar documentacion por Oferta financiada), RF-M25-006 (documentacion
-- administrativa de servicio); RN-M17-001 (autorizado != consumido), RN-M17-003 (los
-- faltantes generan alertas), RN-M17-004 (la documentacion administrativa NO reemplaza el
-- registro clinico), RN-M17-005 (los requisitos se aplican SOLO cuando la Oferta y el
-- convenio lo exigen), RN-M17-006 (una actividad no cubierta no pide orden ni autorizacion);
-- RN-M08-004 (cobertura del paciente != convenio del consultorio); DP-08 (los requisitos se
-- configuran por Financiador/Plan/Convenio/Prestacion, con vigencia y trazabilidad, y ningun
-- ejemplo historico es obligatorio global).
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0005 (Problem Details), ADR-0007 (expandir-migrar-contraer).
--
-- ---------------------------------------------------------------------------------------
-- POR QUE ESTAS TABLAS SON DEL MODULO `person` Y NO DE `contracting`
--
-- AGENT.md seccion 4 lista M17 bajo `contracting`. No se puede, y el motivo es estructural:
-- una autorizacion cuelga de la COBERTURA de un paciente, que es de `person`, y `person` ya
-- depende de `contracting.spi` desde 03.04 para congelar el plan. Poner M17 en `contracting`
-- obligaria a `contracting -> person.spi` y cerraria un CICLO entre modulos, que la regla 4
-- de AGENT.md prohibe y que ArchUnit hace fallar el build.
--
-- La direccion que si funciona es la que ya existe: `person -> contracting.spi`. Estas dos
-- tablas viven al lado de `cobertura_paciente` y de `adjunto_administrativo`, que es donde
-- estan los datos que referencian. `contracting` NO las lee ni las escribe.
--
-- ---------------------------------------------------------------------------------------
-- ESTA ETAPA REGISTRA Y HABILITA. NO CONSUME.
--
-- RN-M17-001: autorizado y consumido son conceptos DISTINTOS, y esta migracion los separa
-- fisicamente en dos columnas. `cantidad_autorizada` la fija el financiador; la escribe esta
-- etapa. `cantidad_consumida` la mueve la sesion clinica, que es RF-M17-004/005 y una
-- integracion POSTERIOR: la columna nace en 0 y NADIE la incrementa todavia. Existe ahora
-- porque el saldo de RF-M17-003 es una resta y sin las dos mitades no hay resta que devolver;
-- crearla despues obligaria a un ALTER sobre datos vivos.
--
-- El corolario que hay que tener presente: **el saldo que esta etapa devuelve es el saldo
-- inicial**. Que baje al atender lo trae quien cablee el consumo.
--
-- ---------------------------------------------------------------------------------------
-- EL HUECO QUE 03.05 DEJO ABIERTO, Y COMO SE CIERRA
--
-- `convenio.requiere_orden`, `requiere_autorizacion`, `requiere_credencial` y
-- `limite_sesiones_mensual` existen desde V43 y su registro de cierre lo dice con todas las
-- letras: "se declaran y nadie los interpreta. Quien los aplique es M17". Esta migracion crea
-- las filas que SATISFACEN esos requisitos, y `ElegibilidadAdministrativaService` es quien
-- los confronta —leyendo el convenio VIVO por `contracting.spi.ArancelDirectory#resolver`,
-- porque decidir se hace con la regla de hoy—.
--
-- RN-M17-005 y RN-M17-006 son la mitad que se olvida: si el convenio no exige orden, NO se
-- pide orden; si no hay convenio vigente, la lista de requisitos viene VACIA y la prestacion
-- es elegible. Una actividad no cubierta que pide autorizacion artificialmente es un bug, no
-- una precaucion.
--
-- ---------------------------------------------------------------------------------------
-- EL SNAPSHOT DEL CONVENIO: COPIA, NO PUNTERO, Y OPCIONAL
--
-- `convenio_id`, `convenio_codigo`, `convenio_nombre`, `requeria_orden`,
-- `requeria_autorizacion`, `requeria_credencial` y `referencia_capturada_el` son una COPIA
-- congelada de lo que el convenio exigia el dia en que la autorizacion se registro. Mismo
-- patron que las nueve columnas de `cobertura_paciente` (V42) y que el precio de `obligacion`
-- (V36): la copia la entrega `contracting.spi.ArancelDirectory#congelar`, que devuelve valores
-- y no un puntero al agregado. En PASADO a proposito —"requeria"—: dice lo que el convenio
-- pedia ese dia, no lo que pide hoy.
--
-- Y es OPCIONAL, que es la diferencia con V42. `congelar` devuelve vacio cuando no hay
-- convenio vigente para ese plan y esa sede, o cuando el convenio existe pero la practica no
-- tiene arancel cargado. Ninguna de las dos cosas puede impedir registrar una autorizacion
-- que el financiador YA otorgo por telefono: el mostrador no puede quedarse sin poder cargar
-- un numero de autorizacion real porque la grilla de aranceles esta incompleta. Por eso el
-- CHECK dice "entera o ninguna", y no "obligatoria".
--
-- ---------------------------------------------------------------------------------------
-- VENCIDA Y AGOTADA NO SON ESTADOS PERSISTIDOS
--
-- `estado` solo toma PENDIENTE, APROBADA, OBSERVADA y RECHAZADA: los cuatro que una PERSONA
-- decide. Vencida y agotada son funciones del reloj y de la resta, y se calculan AL LEER,
-- igual que la disponibilidad efectiva en 02.04 y la vigencia de la cobertura en 03.04.
--
-- Materializarlas exigiria un job que las mueva, y un job que no corre deja autorizaciones
-- vencidas que el sistema sigue creyendo vigentes: el estado guardado y la verdad se separan
-- en silencio. Ademas, "un documento vencido no desaparece" es requisito de la etapa; con
-- vencimiento calculado la fila no se toca nunca y el historico queda intacto por
-- construccion.
--
-- ---------------------------------------------------------------------------------------
-- NINGUN UNIQUE EXPRESA SOLAPAMIENTO. LO HACE CUMPLIR UN LOCK
--
-- MySQL 8.4 no tiene exclusion constraints. La regla temporal de esta etapa es:
--
--   Un paciente no puede tener DOS autorizaciones APROBADAS de la MISMA cobertura y la MISMA
--   practica con vigencias solapadas.
--
-- Es un duplicado de carga, y su consecuencia es concreta: el saldo autorizado se contaria
-- dos veces y el centro creeria tener veinte sesiones donde el financiador dio diez. La
-- verifica la aplicacion bajo el lock de `autorizacion_persona_lock`, en READ_COMMITTED —con
-- REPEATABLE READ InnoDB fija la foto en la primera lectura consistente, que ocurre ANTES del
-- lock, y las dos transacciones pasarian—. Es la leccion que 05.02 pago con `agenda_sede` y
-- que 03.04 volvio a aplicar con `cobertura_persona_lock`.
--
-- LO QUE NO ES REGLA: dos autorizaciones CONSECUTIVAS son el caso normal —renovar—, y dos de
-- practicas distintas conviven aunque se pisen en el tiempo. Y una PENDIENTE nunca choca con
-- nada: todavia no autoriza cantidad alguna. Por eso el lock lo toman el alta que nace
-- aprobada y la APROBACION, no todas las escrituras.
--
-- ---------------------------------------------------------------------------------------
-- LOS BINARIOS SIGUEN SIENDO DE `adjunto_administrativo` (V40)
--
-- Ni la orden ni la autorizacion guardan bytes. `adjunto_id` es una FK al adjunto que 03.02
-- ya sabe subir, validar, versionar y entregar detras de `AdjuntoStoragePort`, con el
-- `storage_key` que nunca sale del backend. RF-M17-002 —"vincular documento"— es exactamente
-- eso: un vinculo, no un segundo mecanismo de carga. Un segundo mecanismo significaria una
-- segunda validacion de tipo y tamano, una segunda ruta de descarga y una segunda superficie
-- de path traversal.
--
-- UN adjunto por fila, no una tabla de vinculos: la orden es un papel y la autorizacion es un
-- comprobante. Si hicieran falta varios, la persona ya los tiene todos listables por
-- `GET /personas/{id}/adjuntos`.
--
-- ---------------------------------------------------------------------------------------
-- RN-M17-004: ESTO NO ES UNA HISTORIA CLINICA
--
-- `orden_medica.indicacion` es la TRANSCRIPCION administrativa de lo que dice el papel, en 280
-- caracteres, para que el mostrador sepa que autorizar. No es evolucion, no es diagnostico
-- estructurado y no reemplaza nada de M09/M14: la documentacion administrativa no sustituye el
-- registro clinico. Es el mismo criterio con que V40 dejo afuera las categorias clinicas de
-- `adjunto_administrativo`.
-- =====================================================================================

CREATE TABLE orden_medica
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id      BIGINT       NOT NULL COMMENT 'Tenant propietario. La persona pertenece a la ORGANIZACION (RF-M07-010), asi que su orden tampoco lleva sede como propiedad',
    persona_id           BIGINT       NOT NULL COMMENT 'Paciente al que se le prescribio. La aplicacion exige perfil de paciente vigente antes de insertar: una Persona no es un Paciente',
    consultorio_id       BIGINT       NULL COMMENT 'Sede desde la que se cargo. Dato de AUDITORIA del hecho, no de propiedad. Mismo criterio que adjunto_administrativo (V40)',

    cobertura_id         BIGINT       NULL COMMENT 'Cobertura para la que se presenta la orden. NULL = la orden vale para cualquiera: una prescripcion la firma un medico, no un financiador, y atarla a una cobertura obligaria a recargarla cuando el paciente cambia de obra social',

    numero               VARCHAR(64)  NULL COMMENT 'Numero impreso en la orden, cuando lo tiene. Muchas ordenes en papel no lo traen y exigirlo dejaria al mostrador sin poder cargarlas',
    profesional_emisor   VARCHAR(160) NOT NULL COMMENT 'Profesional que firma la orden, tal como figura en el papel. Texto libre: el emisor es externo al sistema y no esta en ningun catalogo del tenant',
    matricula_emisor     VARCHAR(64)  NULL COMMENT 'Matricula del emisor, cuando esta legible. El caso borde "documento ilegible" se resuelve dejandolo en NULL y cargando lo demas, no rechazando la orden',
    fecha_emision        DATE         NOT NULL COMMENT 'Fecha que la orden declara. Es lo que el financiador mira para aceptar o rechazar la presentacion',
    indicacion           VARCHAR(280) NULL COMMENT 'Transcripcion administrativa de lo indicado. NO es registro clinico (RN-M17-004): ver la cabecera',
    sesiones_prescriptas INT          NULL COMMENT 'Cantidad de sesiones que la orden indica. Es INFORMATIVA: quien limita lo que se puede atender es la autorizacion del financiador, no la prescripcion',

    vigencia_desde       DATE         NOT NULL COMMENT 'Primer dia en que la orden se puede presentar',
    vigencia_hasta       DATE         NULL COMMENT 'ULTIMO dia, INCLUSIVO. NULL = sin vencimiento declarado. Mismo criterio que V41, V42 y V43',

    adjunto_id           BIGINT       NULL COMMENT 'Documento escaneado, si se cargo. Es un VINCULO al adjunto de V40: los bytes no entran a esta tabla ni a ninguna otra',

    observaciones        VARCHAR(500) NULL COMMENT 'Notas administrativas. NUNCA contenido clinico',

    active               TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. La baja es LOGICA: una orden vencida o dada de baja sigue explicando con que papel se atendio al paciente (regla maestra 10)',
    deleted_at           DATETIME(6)  NULL,
    deactivation_reason  VARCHAR(280) NULL COMMENT 'Motivo declarado de la baja. Obligatorio',

    deleted_key          DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique del numero. Mismo centinela y mismo motivo que en V40, V41 y V42',

    version              BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista. Una version vieja produce 409 en vez de pisar el cambio ajeno',
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,

    CONSTRAINT pk_orden_medica PRIMARY KEY (id),

    -- El mismo numero de orden no se carga dos veces vigente para el mismo paciente. Los NULL
    -- no colisionan en MySQL, que es exactamente lo que hace falta: las ordenes sin numero
    -- impreso conviven sin estorbarse.
    CONSTRAINT uk_orden_numero_vigente
        UNIQUE (organization_id, persona_id, numero, deleted_key),

    CONSTRAINT fk_orden_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_orden_persona      FOREIGN KEY (persona_id) REFERENCES persona (id),
    CONSTRAINT fk_orden_cobertura    FOREIGN KEY (cobertura_id) REFERENCES cobertura_paciente (id),
    CONSTRAINT fk_orden_adjunto      FOREIGN KEY (adjunto_id) REFERENCES adjunto_administrativo (id),

    -- INCLUSIVA: una orden que vale un solo dia es un estado real.
    CONSTRAINT ck_orden_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    -- Una orden no puede valer antes de haberse escrito. Es la coherencia temporal que
    -- RF-M17-001 exige validar, y aca la hace cumplir la base y no solo un servicio.
    CONSTRAINT ck_orden_emision_previa
        CHECK (vigencia_desde >= fecha_emision),

    -- Cero sesiones prescriptas no es "sin tope": es un dato mal cargado. Sin tope se expresa
    -- omitiendolo, mismo criterio que limite_sesiones_mensual en V43.
    CONSTRAINT ck_orden_sesiones_positivas
        CHECK (sesiones_prescriptas IS NULL OR sesiones_prescriptas > 0),

    CONSTRAINT ck_orden_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El unico camino de lectura: las ordenes de un paciente, mas nuevas primero, filtrando
    -- por ciclo de vida. Empieza por organization_id como todo indice del esquema (ADR-0004).
    INDEX ix_orden_persona (organization_id, persona_id, active, vigencia_desde)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Orden medica presentada por un paciente (M17). Documentacion ADMINISTRATIVA: no reemplaza el registro clinico (RN-M17-004). El escaneo vive en adjunto_administrativo. Propietario: modulo person';


-- =====================================================================================
-- La autorizacion del financiador. Es lo que HABILITA, y por eso tiene estado y saldo.
-- =====================================================================================

CREATE TABLE autorizacion
(
    id                      BIGINT       NOT NULL AUTO_INCREMENT,

    organization_id         BIGINT       NOT NULL COMMENT 'Tenant propietario',
    persona_id              BIGINT       NOT NULL COMMENT 'Paciente autorizado. Redundante con cobertura_paciente.persona_id y guardado igual: es la columna por la que se lee el panel administrativo, y resolverla por join en cada listado seria un join para nada',
    consultorio_id          BIGINT       NOT NULL COMMENT 'Sede que gestiono la autorizacion. NOT NULL y no auditoria, a diferencia de la orden: el convenio que la respalda es de la SEDE (RN-M16-001), asi que sin sede el snapshot congelado no se puede explicar',

    cobertura_id            BIGINT       NOT NULL COMMENT 'Cobertura contra la que el financiador autorizo. NOT NULL: una autorizacion sin cobertura no significa nada, porque no hay financiador que la haya dado',
    orden_medica_id         BIGINT       NULL COMMENT 'Orden que respalda el pedido, si el convenio la exigia. NULL es legitimo: hay convenios que autorizan sin orden',
    practica_id             BIGINT       NOT NULL COMMENT 'Practica del catalogo clinico (M06) autorizada. Es el eje sobre el que se pacta con un financiador, el mismo que usa convenio_arancel (V43)',

    numero                  VARCHAR(64)  NOT NULL COMMENT 'Numero de autorizacion que devolvio el financiador. Obligatorio: es el dato con el que se presenta la liquidacion, y una autorizacion sin numero no se puede cobrar',

    estado                  VARCHAR(16)  NOT NULL DEFAULT 'PENDIENTE' COMMENT 'PENDIENTE, APROBADA, OBSERVADA o RECHAZADA. Los cuatro que decide una PERSONA. VENCIDA y AGOTADA NO estan aca: se calculan al leer, ver la cabecera',
    motivo                  VARCHAR(280) NULL COMMENT 'Por que se observo o se rechazo. Obligatorio en esos dos estados: una autorizacion rechazada sin motivo deja al mostrador sin saber que corregir',

    cantidad_autorizada     INT          NULL COMMENT 'Sesiones que el financiador otorgo. NULL = sin tope declarado, que es distinto de cero. Puede ser MENOR que la pedida: eso es la autorizacion parcial',
    cantidad_consumida      INT          NOT NULL DEFAULT 0 COMMENT 'Sesiones ya consumidas. RN-M17-001: autorizado y consumido son distintos. ESTA ETAPA NO LA MUEVE: la escribe quien cablee RF-M17-004, y hasta entonces vale 0',

    vigencia_desde          DATE         NOT NULL COMMENT 'Primer dia en que la autorizacion habilita',
    vigencia_hasta          DATE         NULL COMMENT 'ULTIMO dia, INCLUSIVO. NULL = sin vencimiento declarado',

    -- --- Snapshot CONGELADO del convenio. Copia, no puntero, y opcional. Ver la cabecera. ---
    convenio_id             BIGINT       NULL COMMENT 'Identidad estable del convenio congelado. Para trazabilidad, NUNCA para releer sus exigencias: para eso estan las tres columnas requeria_*',
    convenio_codigo         VARCHAR(64)  NULL COMMENT 'COPIA del codigo del convenio al registrar',
    convenio_nombre         VARCHAR(160) NULL COMMENT 'COPIA del nombre al registrar. SI puede diferir de la fila viva, y ese es el punto',
    requeria_orden          TINYINT(1)   NULL COMMENT 'COPIA de convenio.requiere_orden al registrar. En PASADO: dice lo que el convenio pedia ese dia. Es la mitad del hueco que 03.05 dejo abierto',
    requeria_autorizacion   TINYINT(1)   NULL COMMENT 'COPIA de convenio.requiere_autorizacion al registrar',
    requeria_credencial     TINYINT(1)   NULL COMMENT 'COPIA de convenio.requiere_credencial al registrar',
    referencia_capturada_el DATETIME(6)  NULL COMMENT 'Instante UTC del congelamiento. Es lo que permite explicar, seis meses despues, por que esta copia dice algo distinto del convenio de hoy',

    adjunto_id              BIGINT       NULL COMMENT 'Comprobante de la autorizacion, si se cargo. VINCULO al adjunto de V40',

    observaciones           VARCHAR(500) NULL COMMENT 'Notas administrativas. NUNCA contenido clinico',

    active                  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Ciclo de vida. La baja es LOGICA: una autorizacion vencida o rechazada sigue siendo consultable',
    deleted_at              DATETIME(6)  NULL,
    deactivation_reason     VARCHAR(280) NULL,

    deleted_key             DATETIME(6) AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL
        COMMENT 'Discriminador del unique del numero. Un numero de una autorizacion dada de baja se puede volver a cargar',

    version                 BIGINT       NOT NULL DEFAULT 0 COMMENT 'Bloqueo optimista',
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,

    CONSTRAINT pk_autorizacion PRIMARY KEY (id),

    -- El mismo numero no se carga dos veces vigente bajo la misma cobertura. Por COBERTURA y
    -- no por persona: el numero lo emite el financiador, y dos financiadores distintos pueden
    -- devolver el mismo numero al mismo paciente sin que eso sea un duplicado de nada.
    CONSTRAINT uk_autorizacion_numero_vigente
        UNIQUE (organization_id, cobertura_id, numero, deleted_key),

    CONSTRAINT fk_autorizacion_organization FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_autorizacion_persona      FOREIGN KEY (persona_id) REFERENCES persona (id),
    CONSTRAINT fk_autorizacion_consultorio  FOREIGN KEY (consultorio_id) REFERENCES consultorio (id),
    CONSTRAINT fk_autorizacion_cobertura    FOREIGN KEY (cobertura_id) REFERENCES cobertura_paciente (id),
    CONSTRAINT fk_autorizacion_orden        FOREIGN KEY (orden_medica_id) REFERENCES orden_medica (id),
    CONSTRAINT fk_autorizacion_practica     FOREIGN KEY (practica_id) REFERENCES practica (id),
    CONSTRAINT fk_autorizacion_convenio     FOREIGN KEY (convenio_id) REFERENCES convenio (id),
    CONSTRAINT fk_autorizacion_adjunto      FOREIGN KEY (adjunto_id) REFERENCES adjunto_administrativo (id),

    CONSTRAINT ck_autorizacion_estado
        CHECK (estado IN ('PENDIENTE', 'APROBADA', 'OBSERVADA', 'RECHAZADA')),

    -- Observar o rechazar sin decir por que deja al mostrador sin nada que corregir.
    CONSTRAINT ck_autorizacion_motivo_exigido
        CHECK (estado NOT IN ('OBSERVADA', 'RECHAZADA') OR motivo IS NOT NULL),

    -- Cero sesiones autorizadas no es "sin tope": es un dato mal cargado.
    CONSTRAINT ck_autorizacion_cantidad_positiva
        CHECK (cantidad_autorizada IS NULL OR cantidad_autorizada > 0),

    -- El saldo no puede ser negativo, y eso lo dice la base y no solo la aplicacion: el dia que
    -- exista el consumo, un decremento mal escrito choca aca en vez de dejar deuda fantasma.
    CONSTRAINT ck_autorizacion_consumo_coherente
        CHECK (cantidad_consumida >= 0
            AND (cantidad_autorizada IS NULL OR cantidad_consumida <= cantidad_autorizada)),

    CONSTRAINT ck_autorizacion_vigencia_coherente
        CHECK (vigencia_hasta IS NULL OR vigencia_hasta >= vigencia_desde),

    -- El snapshot viaja ENTERO o no viaja. Media referencia —un convenio_id sin lo que exigia
    -- aquel dia— es exactamente el puntero que estas columnas existen para no ser.
    CONSTRAINT ck_autorizacion_snapshot_coherente
        CHECK ((convenio_id IS NULL AND convenio_codigo IS NULL AND convenio_nombre IS NULL
            AND requeria_orden IS NULL AND requeria_autorizacion IS NULL
            AND requeria_credencial IS NULL AND referencia_capturada_el IS NULL)
            OR (convenio_id IS NOT NULL AND convenio_codigo IS NOT NULL
            AND convenio_nombre IS NOT NULL AND requeria_orden IS NOT NULL
            AND requeria_autorizacion IS NOT NULL AND requeria_credencial IS NOT NULL
            AND referencia_capturada_el IS NOT NULL)),

    CONSTRAINT ck_autorizacion_baja_coherente
        CHECK ((active = 1 AND deleted_at IS NULL AND deactivation_reason IS NULL)
            OR (active = 0 AND deleted_at IS NOT NULL AND deactivation_reason IS NOT NULL)),

    -- El panel administrativo del paciente: sus autorizaciones, mas nuevas primero.
    INDEX ix_autorizacion_persona (organization_id, persona_id, active, vigencia_desde),

    -- La consulta de elegibilidad: las aprobadas de ESA cobertura y ESA practica, ese dia.
    INDEX ix_autorizacion_cobertura
        (organization_id, cobertura_id, practica_id, active, estado)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Autorizacion de un financiador para un paciente (M17). Guarda una COPIA CONGELADA de lo que el convenio exigia al registrarla. autorizado != consumido (RN-M17-001): esta etapa escribe la primera y no toca la segunda. Propietario: modulo person';


-- =====================================================================================
-- La fila que existe unicamente para ser bloqueada.
--
-- Bloquear las filas de `autorizacion` que YA existen no impide que otra transaccion INSERTE
-- una nueva en el hueco, que es exactamente el caso a evitar, y MySQL 8.4 no tiene exclusion
-- constraints. Hace falta una fila que siempre exista y que todas las aprobaciones de ese
-- paciente se disputen.
--
-- POR QUE NO SE REUSA `cobertura_persona_lock` (V42), que tiene la MISMA granularidad: porque
-- protege otra invariante. Compartirlo haria que cargar una cobertura y aprobar una
-- autorizacion se serialicen entre si sin que ninguna de las dos pueda romper a la otra, y
-- ataria dos reglas independientes al mismo punto de contencion. Una fila por persona cuesta
-- 24 bytes; el acoplamiento cuesta cada vez que una de las dos reglas cambie.
--
-- Granularidad por PERSONA y no por organizacion ni por cobertura: la invariante es de UN
-- paciente —dos autorizaciones suyas que se pisan—, y un lock por organizacion serializaria el
-- padron entero para proteger algo que nunca cruza de paciente.
--
-- La fila se crea en una transaccion APARTE, con INSERT ... ON DUPLICATE KEY UPDATE. Crearla
-- perezosamente dentro de la transaccion que la bloquea produce deadlock entre las primeras N
-- escrituras concurrentes, y el try/catch no salva: atrapar una excepcion de persistencia no
-- des-marca la transaccion y Spring lanza UnexpectedRollbackException al commitear. Ya se pago
-- cuatro veces en este repositorio —agenda_sede, consultorio_calendario, sesion_numerador y
-- cobertura_persona_lock—.
-- =====================================================================================

CREATE TABLE autorizacion_persona_lock
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,

    organization_id BIGINT      NOT NULL COMMENT 'Tenant propietario. El lock nunca cruza de organizacion',
    persona_id      BIGINT      NOT NULL COMMENT 'Persona cuyas autorizaciones serializa esta fila',

    created_at      DATETIME(6) NOT NULL,

    CONSTRAINT pk_autorizacion_persona_lock PRIMARY KEY (id),

    -- Es el unique que hace idempotente al INSERT ... ON DUPLICATE KEY UPDATE, y por lo tanto
    -- lo que evita el deadlock entre las primeras escrituras concurrentes.
    CONSTRAINT uk_autorizacion_persona_lock UNIQUE (organization_id, persona_id),

    CONSTRAINT fk_autorizacion_lock_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_autorizacion_lock_persona
        FOREIGN KEY (persona_id) REFERENCES persona (id)
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Una fila por persona cuyo unico proposito es ser bloqueada con FOR UPDATE. No guarda estado. Serializa las aprobaciones de autorizaciones de ese paciente: ningun unique puede expresar solapamiento de intervalos en MySQL 8.4. Propietario: modulo person';
