-- =====================================================================================
-- AKINE-06.06 — Enmiendas y versionado de sesion cerrada (M14).
--
-- POR QUE V53
--
-- El diseño de la etapa (docs/diseno/AKINE-06.06-enmiendas.md, seccion 4) reserva V53 ANTES
-- de escribir una linea de codigo, y no es formalismo: `V26` quedo vacia para siempre porque
-- 02.07 y 03.01 nacieron las dos como V26, Flyway rechaza versiones duplicadas —"Found more
-- than one migration with version N"— y la aplicacion directamente no arranca. Hoy hay varias
-- etapas en vuelo en worktrees distintos: `V51` y `V52` son de una, `V54` de otra. El numero
-- se reserva antes y no se negocia despues. Flyway no exige versiones contiguas.
--
-- POR QUE ESTA MIGRACION EXISTE
--
-- Una sesion cerrada es historia clinica. ADR-0011 y la regla maestra 10 prohiben
-- reescribirla, y AKINE-06.05 lo dejo fail-closed: escribir sobre una sesion cerrada es 409.
-- Pero una sesion cerrada con un dato mal tambien es historia clinica mal registrada, y no
-- poder corregirla obliga a elegir entre dos cosas malas.
--
-- RN-M14-006 lo dice en cinco palabras: "una sesion cerrada no se modifica SILENCIOSAMENTE".
-- No prohibe corregir; prohibe corregir sin rastro. La salida es la ENMIENDA: una version
-- nueva que preserva el original, con motivo obligatorio y autoria propia. En terminos de
-- esquema: enmendar es un INSERT mas el avance de un contador, NUNCA un UPDATE que pise
-- contenido sin dejar el anterior.
--
-- Trazabilidad: RF-M14-010 (corregir sesion finalizada con enmienda, motivo y auditoria),
-- RF-M24-005 (mostrar enmiendas clinicas), RN-M14-006 (no se modifica silenciosamente),
-- RN-M14-002 (el correlativo es del Caso y no se renumera), `plan_sesiones.txt` seccion 18.3
-- (versionado clinico: fecha, usuario, cambio, version); ADR-0011 (requisitos
-- clinico-legales), DP-03 / ADR-0010 (la HC es de la ORGANIZACION), DP-05 (Sesion es una
-- maquina de estados propia), DP-06 (el cierre clinico no depende del cobro); reglas
-- maestras 1, 3, 5, 6, 7, 10, 11 y 12.
--
-- ADR-0003 (Flyway unica autoridad del esquema), ADR-0004 (persistencia multi-tenant),
-- ADR-0005 (contrato de errores).
--
-- NO SE INVENTA UNA TERCERA FORMA DE VERSIONAR
--
-- Este repositorio ya resolvio el mismo problema dos veces y las dos estan en esta rama:
-- `entrada_clinica` + `entrada_clinica_version` (V45, 04.02) y `plan_tratamiento` +
-- `plan_tratamiento_version` + `plan_item` (V49, 04.04). Se copia la de V45 entera: cabecera
-- con contador, una fila por version, la v1 es el original, motivo obligatorio desde la v2,
-- y las versiones SIN `active` ni `deleted_at`.
--
-- La unica diferencia estructural es que aca la cabecera YA EXISTE y YA TIENE CONTENIDO:
-- `sesion` guarda sus columnas clinicas desde V34 y V35, y hay consumidores que las leen
-- —`findPreviaEvaluada`, `contarCerradasPorOferta`, el indice `ix_sesion_comparacion`—. Eso
-- se resuelve con una duplicacion declarada (ver "LA CONCESION" abajo), no con un modelo
-- distinto.
--
-- PROPIETARIO
--
-- Modulo `encounter`, el mismo de `sesion` y `sesion_numerador`. Ningun otro modulo lee ni
-- escribe esta tabla (AGENT.md seccion 4, regla 1). El caso que habia que mirar es el
-- simetrico del que miro 04.02: seria tentador que la sesion enmendada escribiera una
-- `entrada_clinica_version` y reusar aquella implementacion. NO se hace, por lo mismo que
-- `SesionEventoContributor` no copia la sesion al timeline: la evolucion viviria duplicada
-- en `sesion.evolucion` y en `entrada_clinica_version.cuerpo`, con dos modulos dueños de la
-- misma verdad clinica. Y ademas el contenido de una sesion es ESTRUCTURADO —EVA, evolucion,
-- tolerancia, proxima conducta— y el de una entrada clinica es un texto: mudarlo alla lo
-- convertiria en prosa inconsultable, justo lo que 06.02 evito al tiparlo.
--
-- LO QUE SE PUEDE ENMENDAR, Y LO QUE NO
--
-- Esta tabla tiene exactamente las columnas enmendables. Las que faltan no son un olvido:
--
--   numero_sesion, numero_en_caso  RENUMERAR SESIONES CERRADAS ES REESCRIBIR HISTORIA
--                                  CLINICA. Es lo que 04.03 rechazo explicitamente y lo que
--                                  06.05 dejo fijado: el numero esta impreso en informes.
--   caso_id, oferta_id, turno_id,  Son `updatable = false` desde V33 y V48. No son contenido
--   profesional_membership_id      clinico: son la identidad del hecho.
--   iniciada_en, cerrada_en,       Cuando paso y quien lo asento. Corregir eso no es
--   cerrada_por_cuenta_id, estado  enmendar, es falsificar.
--   modo                           Es una decision de la PANTALLA sobre cuanto mostrar
--                                  (06.02), no contenido clinico.
--   asistencia                     Ver el bloque siguiente. Es la decision filosa.
--
-- POR QUE `asistencia` NO ESTA ACA, QUE ES LA DECISION CENTRAL DE LA ETAPA
--
-- Al cerrar corren dos observadores DENTRO de la transaccion: `billing.ObligacionDevengador`
-- —devenga la deuda con precio congelado— y `encounter.ConsumoDeAutorizacionEnCierre`
-- —consume una unidad autorizada—. La enmienda NO los vuelve a disparar, y no hace falta que
-- lo haga porque NADA DE LO ENMENDABLE LOS AFECTA: los dos leen `asistio`, `ofertaId` y el
-- precio de la oferta, y ninguno de los tres es enmendable.
--
-- El argumento NO es que re-dispararlos duplicaria la deuda: se verifico contra el codigo que
-- los dos son idempotentes por el hecho de origen —`ObligacionDevengador` consulta
-- `findPorPrestacion` antes de insertar, respaldado por el unique de V36, y el consumo lo es
-- por `uk_autorizacion_movimiento_origen` de V50—. Los motivos reales son dos y mas finos:
--
--   (a) re-disparar es ASIMETRICO: solo puede AGREGAR efectos economicos, nunca sacarlos.
--       PRESENTE -> AUSENTE dejaria la deuda devengada y la unidad consumida tal cual, porque
--       no existe ningun observador de "des-cierre";
--   (b) la idempotencia del consumo es POR AUTORIZACION, no por sesion. Si entre el cierre y
--       la enmienda cambio cual es la autorizacion elegible, el `origen` es el mismo pero el
--       `autorizacion_id` es otro, el unique no choca y SE CONSUME UNA SEGUNDA UNIDAD.
--
-- Por eso la asistencia queda congelada: es la unica bisagra entre el relato clinico y el
-- dinero. Asi el criterio de aceptacion del plan —"no hay cambios economicos implicitos"—
-- deja de ser una promesa del servicio y pasa a ser una propiedad del esquema: no existe
-- columna por la que una enmienda pueda mover plata.
--
-- LA CONTRACARA, DECLARADA: una sesion cerrada con la asistencia equivocada NO se arregla
-- enmendando. Cambiarla es un acto economico que exige compensacion explicita —anular la
-- obligacion (M18) o devengarla, y revertir el consumo (M17, que ya tiene su endpoint desde
-- 04.05)— y esos actos tienen dueño en otros modulos. Ver el challenge, seccion 8.
--
-- LA CONCESION, DECLARADA
--
-- `sesion` guarda el contenido VIGENTE y `sesion_version` guarda TODAS las versiones,
-- incluida la vigente. Eso duplica el contenido de la ultima version. Se acepta porque la
-- alternativa era peor: mover el contenido fuera de `sesion` rompe a todos los lectores que
-- ya existen y obliga a una migracion de datos que esta etapa no puede verificar contra
-- ningun motor —Docker no esta disponible—. La duplicacion es segura porque las dos
-- escrituras van en la MISMA transaccion y no hay otro camino que escriba una sin la otra; y
-- el original, que es lo que la regla maestra 10 protege, esta en la v1 y nunca se toca.
--
-- MIGRACION UNICA Y NO EXPANDIR-MIGRAR-CONTRAER (ADR-0007), mismo motivo que V45 y V49: la
-- tabla es nueva y la unica columna que se agrega a una existente nace con DEFAULT y se
-- rellena en el mismo paso, asi que no hay ventana de incompatibilidad que administrar.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. El historial de contenido de una sesion. Una fila por version; la 1 es el original.
-- -------------------------------------------------------------------------------------
CREATE TABLE sesion_version
(
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    organization_id       BIGINT        NOT NULL
        COMMENT 'Tenant propietario. Es derivable de la sesion por la FK y va IGUAL: derivarlo obliga a un join para filtrar por tenant, y el dia que alguien escriba la consulta sin el join tiene una fuga que NINGUN test de la etapa ve, porque los tests de una etapa corren con un solo tenant. Mismo criterio que entrada_clinica_version en V45',
    sesion_id             BIGINT        NOT NULL
        COMMENT 'Atencion de la que esta version es contenido',
    numero_version        INT           NOT NULL
        COMMENT 'Orden de la version dentro de la sesion. La 1 es el contenido con el que se cerro; cada enmienda agrega la siguiente. Sale del contador sesion.ultimo_numero_version, NUNCA de un MAX(numero_version)+1: dos MAX simultaneos devuelven el mismo numero y dejan dos "version 3" sin criterio de desempate',

    -- Evaluacion base (V34). Todo nullable, y es una regla de negocio: "seguimiento no exige
    -- examen completo" (06.02). Una version que solo tiene dolor y evolucion es legitima.
    motivo_clinico        VARCHAR(500)  NULL
        COMMENT 'Lo que trae al paciente, en palabras del profesional. NO es un diagnostico',
    dolor_eva             INT           NULL
        COMMENT 'Escala visual analogica 0 a 10. INT y no TINYINT por lo mismo que en V34: el mapeo Java es Integer y `ddl-auto: validate` rechaza el tipo mas chico, con el sintoma de que fallan TODOS los tests de integracion a la vez',
    dolor_zona            VARCHAR(120)  NULL,
    dolor_lateralidad     VARCHAR(16)   NULL,
    evolucion             VARCHAR(16)   NULL,
    objetivo_sesion       VARCHAR(500)  NULL,
    limitacion_funcional  VARCHAR(500)  NULL,

    -- Cierre clinico (V35). `asistencia` NO esta: ver la cabecera.
    nota_de_cierre        VARCHAR(2000) NULL
        COMMENT 'Lo unico que registra que se hizo mientras 06.04 no exista. Obligatorio si el paciente asistio, y esa regla la sigue haciendo cumplir CierreDeSesion: aca es nullable porque una sesion cerrada con AUSENTE no la tiene',
    respuesta_tratamiento VARCHAR(500)  NULL,
    tolerancia            VARCHAR(16)   NULL,
    indicaciones          VARCHAR(1000) NULL,
    proxima_conducta      VARCHAR(16)   NULL,

    motivo_enmienda       VARCHAR(280)  NULL
        COMMENT 'Por que se enmendo. NULL en la version 1 —no enmienda nada— y OBLIGATORIO en toda posterior: sin motivo, una enmienda es indistinguible de una correccion de tipeo y el historial deja de servir para lo unico que sirve (RN-M14-006)',
    registrada_en         DATETIME(6)   NOT NULL
        COMMENT 'Instante UTC en que se escribio esta version. En la v1 es el del cierre',
    registrada_por        BIGINT        NOT NULL
        COMMENT 'accountId del autor de ESTA version. Es por version y no por sesion: quien enmienda no suele ser quien cerro —un supervisor, el profesional del turno siguiente— y perder eso vaciaria el historial. En la v1 es cerrada_por_cuenta_id. Sin FK a cuenta, mismo motivo que en V32 y V45',

    created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    -- La red debajo de la numeracion, y es red y no mecanismo: lo que serializa dos enmiendas
    -- concurrentes es el UPDATE versionado de `sesion`, que queda sucia al mover el contador.
    -- Esto existe para que un camino futuro que esquive el servicio choque contra la base en
    -- vez de dejar dos "version 3". Es ademas el UNICO indice de la tabla: cubre entero el
    -- unico camino de lectura —las versiones de una sesion, ordenadas por numero— y empieza
    -- por organization_id como todo indice de este esquema (ADR-0004).
    CONSTRAINT uk_sesion_version_numero
        UNIQUE (organization_id, sesion_id, numero_version),
    CONSTRAINT fk_sesion_version_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),
    -- RESTRICT (el default): no hay borrado fisico de una sesion con versiones.
    CONSTRAINT fk_sesion_version_sesion
        FOREIGN KEY (sesion_id) REFERENCES sesion (id),

    CONSTRAINT ck_sesion_version_numero
        CHECK (numero_version >= 1),
    -- RF-M14-010: la enmienda exige motivo. El original no lo lleva porque no enmienda nada.
    CONSTRAINT ck_sesion_version_motivo_de_enmienda
        CHECK ((numero_version = 1 AND motivo_enmienda IS NULL)
            OR (numero_version > 1 AND motivo_enmienda IS NOT NULL)),

    -- Los mismos CHECK que V34 y V35 le pusieron a `sesion`. Repetirlos no es redundancia:
    -- una version que la cabecera no aceptaria tampoco deberia poder existir aca, y sin esto
    -- el historial podria guardar un EVA de 12 que la sesion vigente nunca pudo tener.
    CONSTRAINT ck_sesion_version_dolor_eva
        CHECK (dolor_eva IS NULL OR (dolor_eva >= 0 AND dolor_eva <= 10)),
    CONSTRAINT ck_sesion_version_lateralidad
        CHECK (dolor_lateralidad IS NULL
            OR dolor_lateralidad IN ('IZQUIERDA', 'DERECHA', 'BILATERAL', 'NO_APLICA')),
    -- La lateralidad sin zona no dice nada: "derecha" de que. Al reves si es legitimo —una
    -- zona central como la lumbar no tiene lado—, por eso la implicacion va en un solo sentido.
    CONSTRAINT ck_sesion_version_lateralidad_con_zona
        CHECK (dolor_lateralidad IS NULL OR dolor_zona IS NOT NULL),
    CONSTRAINT ck_sesion_version_evolucion
        CHECK (evolucion IS NULL
            OR evolucion IN ('MEJOR', 'IGUAL', 'PEOR', 'SIN_REFERENCIA')),
    CONSTRAINT ck_sesion_version_tolerancia
        CHECK (tolerancia IS NULL OR tolerancia IN ('BUENA', 'REGULAR', 'MALA')),
    CONSTRAINT ck_sesion_version_proxima_conducta
        CHECK (proxima_conducta IS NULL
            OR proxima_conducta IN ('CONTINUA', 'ALTA', 'DERIVA', 'REEVALUA'))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Contenido de una version de sesion cerrada (M14, RF-M14-010). SIN active y SIN deleted_at a proposito: una version es un hecho pasado y darla de baja seria reescribir historia clinica (ADR-0011). Propietario: modulo encounter';

-- -------------------------------------------------------------------------------------
-- 2. El contador de versiones, en la propia cabecera.
--
-- LA REGLA DEL REPOSITORIO SE CUMPLE SIN TABLA DE NUMERADOR, y conviene decir por que, porque
-- el reflejo va a ser buscar un `sesion_version_numerador` al lado de `sesion_numerador`.
-- La regla es dos cosas: (a) el correlativo sale de un `UPDATE ... +1` y nunca de un MAX+1
-- —se cumple: el contador es esta columna—, y (b) la fila del numerador se crea en una
-- transaccion aparte, porque la creacion perezosa dentro de la transaccion que la bloquea
-- produce deadlock y el try/catch no salva. (b) NO APLICA: la fila ya existe, la creo el
-- inicio de la sesion, asi que no hay creacion perezosa posible y no hace falta REQUIRES_NEW.
-- Es exactamente lo que hace `entrada_clinica.ultimo_numero_version` en V45.
--
-- Y por lo mismo la aplicacion NO usa OPTIMISTIC_FORCE_INCREMENT: enmendar ENSUCIA esta fila,
-- asi que el flush ya emite un `UPDATE ... WHERE version = N` versionado y esa es toda la
-- garantia. Forzar el incremento encima dejaria la base en `leida + 2` devolviendo
-- `leida + 1`, y el cliente comeria un 409 del que no puede salir. 04.02 lo pago.
-- -------------------------------------------------------------------------------------
ALTER TABLE sesion
    ADD COLUMN ultimo_numero_version INT NOT NULL DEFAULT 0
        COMMENT 'Numero de la ultima version de contenido escrita. 0 mientras la sesion esta abierta —no hay version hasta que se cierra— y 1 desde el cierre. La enmienda numera con este contador +1, NUNCA con MAX(numero_version)'
        AFTER numero_en_caso;

-- -------------------------------------------------------------------------------------
-- 3. Backfill: la v1 de todas las sesiones ya cerradas.
--
-- Sin esto, el historial de una sesion cerrada antes de esta migracion arrancaria en la v2 y
-- el ORIGINAL no existiria en ningun lado —exactamente lo que la etapa viene a evitar—. Es el
-- mismo criterio con el que V38 backfilleo `turno_evento`.
--
-- `cerrada_en` y `cerrada_por_cuenta_id` son NOT NULL para toda sesion cerrada: lo garantiza
-- `ck_sesion_cierre_completo` de V35, que ata numero, instante y actor a ir los tres o ninguno.
-- Por eso el filtro es `numero_sesion IS NOT NULL`, que es como el dominio decide "esta
-- cerrada" (Sesion#estaCerrada).
--
-- Las sesiones dadas de baja logica tambien entran: `deleted_at` saca la sesion de las
-- consultas vigentes, no la borra, y su contenido sigue siendo historia clinica consultable.
-- -------------------------------------------------------------------------------------
INSERT INTO sesion_version (organization_id, sesion_id, numero_version,
                            motivo_clinico, dolor_eva, dolor_zona, dolor_lateralidad,
                            evolucion, objetivo_sesion, limitacion_funcional,
                            nota_de_cierre, respuesta_tratamiento, tolerancia,
                            indicaciones, proxima_conducta,
                            motivo_enmienda, registrada_en, registrada_por)
SELECT s.organization_id,
       s.id,
       1,
       s.motivo_clinico,
       s.dolor_eva,
       s.dolor_zona,
       s.dolor_lateralidad,
       s.evolucion,
       s.objetivo_sesion,
       s.limitacion_funcional,
       s.nota_de_cierre,
       s.respuesta_tratamiento,
       s.tolerancia,
       s.indicaciones,
       s.proxima_conducta,
       NULL,
       s.cerrada_en,
       s.cerrada_por_cuenta_id
  FROM sesion s
 WHERE s.numero_sesion IS NOT NULL;

UPDATE sesion
   SET ultimo_numero_version = 1
 WHERE numero_sesion IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- 4. El CHECK va AL FINAL, y el orden no es estetico.
--
-- MySQL valida un CHECK agregado por ALTER contra las filas que ya estan. Ponerlo antes del
-- UPDATE del paso 3 rechazaria toda sesion cerrada existente —tendria numero_sesion y
-- ultimo_numero_version = 0— y la migracion fallaria en una base con datos mientras pasa
-- limpia en una vacia, que es la peor forma de fallar: verde en CI, roja en produccion.
--
-- Lo que el CHECK impide: una sesion abierta con versiones (no existe contenido versionado
-- hasta el cierre) y una sesion cerrada sin ninguna (el original tiene que estar).
-- -------------------------------------------------------------------------------------
ALTER TABLE sesion
    ADD CONSTRAINT ck_sesion_ultimo_numero_version
        CHECK ((numero_sesion IS NULL AND ultimo_numero_version = 0)
            OR (numero_sesion IS NOT NULL AND ultimo_numero_version >= 1));
