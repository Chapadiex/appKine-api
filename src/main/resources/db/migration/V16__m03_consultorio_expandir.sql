-- =====================================================================================
-- AKINE-02.01 — Expandir: el consultorio deja de ser una sede minima.
--
-- Trazabilidad: RF-M03-001 (alta de sedes), RF-M03-003 (datos, horarios y parametros),
-- RF-M03-004 (baja logica con motivo), RN-M03-001..004, ADR-0004 (persistencia
-- multi-tenant), ADR-0007 (expandir-migrar-contraer), ADR-0008 (onboarding compuesto).
--
-- Propietario de las dos tablas: modulo organization. Ningun otro modulo las toca.
--
-- V3 dejo escrito en el COMMENT de `consultorio` que 02.01 la EXPANDE y no la reemplaza.
-- Esto es esa expansion: no hay DROP TABLE, no hay tabla nueva de sede, y el onboarding
-- compuesto sigue creando el primer consultorio por el mismo camino de siempre.
--
-- ---------------------------------------------------------------------------------------
-- LAS TRES MIGRACIONES DE ESTA ETAPA, Y POR QUE SON TRES (ADR-0007)
--
--   V16 (esta)  EXPANDIR  columnas nuevas, todas nulables o con default. Ningun NOT NULL
--                         sin default, ningun unique nuevo, ningun RENAME, ningun DROP.
--   V17         MIGRAR    backfill de `timezone` desde la organizacion, mas la verificacion
--                         de que no quedo ninguna fila sin zona.
--   V18         CONTRAER  timezone NOT NULL, columna generada `deleted_key`, y el cambio del
--                         unique de nombre.
--
-- ADR-0007 pide que la contraccion viaje en la release SIGUIENTE a la expansion. Aca las
-- tres van juntas y hay que decirlo en voz alta: el sistema no tiene todavia ningun
-- despliegue en produccion, asi que no existe codigo viejo corriendo contra el esquema nuevo
-- al que haya que proteger. El dia que exista, V18 se separa a la release siguiente. Queda
-- anotado como desviacion declarada, no como precedente.
--
-- ---------------------------------------------------------------------------------------
-- ZONA HORARIA: VIVE EN LA SEDE, NO EN LA ORGANIZACION
--
-- `organization.timezone` no se toca y no desaparece: pasa a tener un rol explicito y
-- distinto, que es ser el valor PROPUESTO al crear una sede nueva. La zona efectiva de toda
-- regla local —dia operativo, agenda, corte de caja— es la del lugar fisico donde se
-- atiende, y ese lugar es el consultorio (AGENT.md seccion 5). Una organizacion con sedes en
-- dos husos comparte tenant y no comparte reloj de pared.
--
-- ---------------------------------------------------------------------------------------
-- slot_minutes — DECISION REVISABLE, TOMADA POR EL IMPLEMENTADOR, PENDIENTE DE CONFIRMACION
--
-- RF-M03-002 nombra "horario general e intervalo inicial". De esos dos, 02.01 persiste
-- UNICAMENTE el intervalo, y como columna de la sede. NO se crea ninguna tabla hija de
-- horarios.
--
-- El motivo es RN-M03-004: el horario general NO sustituye la disponibilidad profesional.
-- Una tabla `consultorio_horario` en F1 —antes de que exista disponibilidad profesional
-- (M05/M12) y antes de que existan los slots que la consumen (F5)— es una invitacion a que
-- la agenda la tome como fuente de verdad, que es exactamente lo que esa regla prohibe. El
-- intervalo, en cambio, es el unico parametro que la agenda va a necesitar si o si y no
-- expresa ninguna disponibilidad por si mismo.
--
-- Es la opcion C de la D-2 del diseno (docs/diseno/AKINE-02.01-consultorios.md seccion 12).
-- Consecuencia asumida: RF-M03-002 queda mas incompleto de lo que ya quedaba por el box.
-- Si el usuario decide que el horario general entra, la tabla hija se agrega en F5 sin tocar
-- esta columna.
--
-- ---------------------------------------------------------------------------------------
-- CAMPOS INSTITUCIONALES — D-5, SIN TRAZA A NINGUN RF
--
-- M03 seccion 1 dice "informacion institucional" y no enumera un solo campo. Los cinco de
-- abajo son una PROPUESTA del diseno, no una traza, y van declarados como decision en el
-- registro de cierre. Todos NULL: ninguno se puede exigir sin un RF que lo respalde, y
-- exigir un CUIT para dar de alta una sede seria inventar una regla de negocio.
-- =====================================================================================

ALTER TABLE consultorio
    ADD COLUMN timezone VARCHAR(64) NULL
        COMMENT 'Zona IANA de la sede. Nulable solo durante la ventana V16-V18: V17 la rellena y V18 la fija NOT NULL',
    ADD COLUMN slot_minutes INT NOT NULL DEFAULT 30
        COMMENT 'Intervalo por defecto de la agenda, en minutos. DECISION REVISABLE: es lo unico que 02.01 persiste de "horario general e intervalo inicial" (RF-M03-002); el horario general no entra porque RN-M03-004 dice que no sustituye la disponibilidad profesional',
    ADD COLUMN deactivation_reason VARCHAR(280) NULL
        COMMENT 'Motivo declarado de la baja logica (RF-M03-004). NULL mientras la sede este activa',
    ADD COLUMN legal_name VARCHAR(200) NULL COMMENT 'D-5: campo institucional sin traza a ningun RF de M03',
    ADD COLUMN tax_id VARCHAR(32) NULL COMMENT 'D-5: idem',
    ADD COLUMN address_line VARCHAR(240) NULL COMMENT 'D-5: idem. Texto libre; estructurarla es una decision abierta',
    ADD COLUMN phone VARCHAR(40) NULL COMMENT 'D-5: idem',
    ADD COLUMN contact_email VARCHAR(160) NULL COMMENT 'D-5: idem';

-- Listado paginado ordenado por nombre (RNF-M03-004). Cubre a ix_consultorio_org_active
-- como prefijo; retirar el viejo es opcional y hay que medirlo antes, asi que no se retira.
ALTER TABLE consultorio
    ADD INDEX ix_consultorio_org_active_name (organization_id, active, name);

-- ---------------------------------------------------------------------------------------
-- consultorio_alta — registro de idempotencia del alta de sedes adicionales (D-9, opcion A)
--
-- Por que una tabla propia y no generalizar `organization_onboarding`: esa tabla tiene
-- account_id y membership_id NOT NULL, que aca no aplican —el alta de una sede no crea
-- cuenta ni membership— y tocarla obligaria a relajar dos NOT NULL de un camino ya cerrado
-- y probado.
--
-- La clave lleva alcance TENANT y no es global, al reves que en organization_onboarding. La
-- diferencia es real: el onboarding es PRE-tenant —al reintentar, la organizacion todavia
-- puede no existir— y esto es POST-tenant, asi que ADR-0004 aplica sin excepciones.
--
-- request_hash detecta la misma clave con otro contenido, que es un error del cliente y no
-- un reintento: devolverle el resultado viejo lo dejaria creyendo que se creo lo que pidio
-- ahora. Mismo mecanismo que ya usa el registro de onboarding.
-- ---------------------------------------------------------------------------------------
CREATE TABLE consultorio_alta (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id BIGINT      NOT NULL COMMENT 'ADR-0004: toda tabla de negocio lleva el tenant, y todo unique lo incluye',
    idempotency_key VARCHAR(64) NOT NULL COMMENT 'Identificador del intento, generado por el cliente',
    request_hash    VARCHAR(64) NULL COMMENT 'SHA-256 del payload canonico. NULL cuando el alta no vino por HTTP',
    consultorio_id  BIGINT      NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_consultorio_alta PRIMARY KEY (id),
    CONSTRAINT uk_consultorio_alta_key UNIQUE (organization_id, idempotency_key),
    CONSTRAINT fk_consultorio_alta_org FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_consultorio_alta_consultorio FOREIGN KEY (consultorio_id) REFERENCES consultorio (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Registro de idempotencia del alta de sedes adicionales (CA-M03-001-05). Append-only: sin UPDATE y sin baja logica, porque una fila de idempotencia no tiene estado, solo existe o no.';
