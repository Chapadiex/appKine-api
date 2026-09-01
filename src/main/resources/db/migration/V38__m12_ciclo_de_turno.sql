-- =====================================================================================
-- AKINE-05.03 — Cancelacion, reprogramacion e historial de Turno (M12).
--
-- V38 y no V31: DP-10 habia reservado V31 para esta etapa, pero entre medio se aplicaron
-- V32 a V37 y una version YA APLICADA no se recicla ni se intercala. Flyway no exige
-- versiones contiguas; V26, V29 y V31 quedan vacias a proposito, como ya lo estaban.
--
-- RF-M12-004 (cancelar), RF-M12-005 (reprogramar), RF-M12-007 (ausencia),
-- RF-M12-008 (historial), RN-M12-002, RN-M12-003 y DP-04.
--
-- ---------------------------------------------------------------------------------------
-- LAS CUATRO COSAS QUE HAY QUE MIRAR DE FRENTE
--
-- 1. CANCELAR NO BORRA, Y AUSENTE TAMPOCO. RN-M12-002 y ADR-0011. Un turno cancelado
--    conserva su fila: lo unico que cambia es `estado`, `deleted_at` y el motivo. V30 ya
--    habia dejado `deleted_at` y su `deleted_key` generada preparados para este momento, y
--    los tres indices de solapamiento ya los contemplan, asi que un cancelado deja de
--    ocupar lugar sin que ninguna consulta cambie.
--
-- 2. AUSENTE NO LIBERA EL LUGAR, Y ESA ES LA DIFERENCIA CON CANCELADO. El paciente no
--    vino, pero la hora se consumio: el profesional estuvo ahi. Por eso la ausencia deja
--    `deleted_at` en NULL y el turno sigue apareciendo en las consultas de solapamiento.
--    Si liberara el lugar, la agenda del pasado quedaria mintiendo sobre lo que ocurrio, y
--    M18 no tendria como cobrar un no-show.
--
-- 3. REPROGRAMAR MUEVE EL TURNO, NO CREA OTRO. DP-04 exige que cada Turno conserve
--    identidad, estado e historial propios; ademas la Sesion de M14 cuelga de `turno_id`
--    con un unique, y la obligacion de M18 cuelga de la Sesion. Un reemplazo por par
--    cancelado/nuevo cortaria esa cadena y obligaria a repuntar filas clinicas y
--    economicas. Lo que conserva la trazabilidad es `turno_evento`, que guarda el intervalo
--    anterior y el nuevo.
--
-- 4. `turno_evento` ES APPEND-ONLY. No lleva `updated_at`, ni `version`, ni `deleted_at`:
--    un historial que se puede editar no es un historial. Se inserta dentro de la misma
--    transaccion del cambio de estado —igual que la auditoria de plataforma— porque un
--    evento escrito despues del commit puede perderse y dejar la transicion sin rastro.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- turno — columnas del cierre del ciclo. El estado y la baja logica ya existian.
-- -------------------------------------------------------------------------------------
ALTER TABLE turno
    -- Obligatorio al cancelar (DP-04). Se guarda en la fila ademas de en el evento porque
    -- la grilla de agenda muestra "cancelado: <motivo>" sin tener que leer el historial de
    -- cada turno del dia.
    ADD COLUMN motivo_cancelacion      VARCHAR(300) NULL AFTER estado,
    ADD COLUMN cancelado_en            DATETIME(6)  NULL AFTER motivo_cancelacion,
    ADD COLUMN cancelado_por_cuenta_id BIGINT       NULL AFTER cancelado_en,
    -- Ausencia registrada. Ver el punto 2: NO toca deleted_at.
    ADD COLUMN ausente_en              DATETIME(6)  NULL AFTER cancelado_por_cuenta_id,
    -- Cuando se movio por ultima vez. El historial completo esta en turno_evento; esto es
    -- lo que la pantalla necesita para poder marcar "reprogramado" sin una segunda consulta.
    ADD COLUMN reprogramado_en         DATETIME(6)  NULL AFTER ausente_en;

-- Un turno cancelado tiene motivo y fecha, y uno que no lo esta no tiene ninguno de los
-- dos. Sin este CHECK una fila podria quedar dada de baja sin explicacion, que es
-- exactamente lo que DP-04 prohibe.
ALTER TABLE turno
    ADD CONSTRAINT ck_turno_cancelacion_completa
        CHECK ((cancelado_en IS NULL AND motivo_cancelacion IS NULL AND cancelado_por_cuenta_id IS NULL)
            OR (cancelado_en IS NOT NULL AND motivo_cancelacion IS NOT NULL
                AND cancelado_por_cuenta_id IS NOT NULL));


-- -------------------------------------------------------------------------------------
-- turno_evento — historial inmutable de transiciones (RF-M12-008). Ver el punto 4.
-- -------------------------------------------------------------------------------------
CREATE TABLE turno_evento
(
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,

    organization_id BIGINT      NOT NULL,
    consultorio_id  BIGINT      NOT NULL,
    turno_id        BIGINT      NOT NULL,

    tipo            VARCHAR(20) NOT NULL COMMENT 'RESERVA, CONFIRMACION, CANCELACION, REPROGRAMACION o AUSENCIA.',

    estado_anterior VARCHAR(16) NULL COMMENT 'NULL solo en el evento de RESERVA: antes no habia estado.',
    estado_nuevo    VARCHAR(16) NOT NULL,

    motivo          VARCHAR(300) NULL COMMENT 'Obligatorio en CANCELACION y REPROGRAMACION; opcional en AUSENCIA.',

    -- Solo en REPROGRAMACION. Es lo que hace cumplir RN-M12-003 —"reprogramar conserva
    -- trazabilidad"— sin duplicar el turno: la fila se movio y aca queda de donde vino.
    inicio_anterior DATETIME(6) NULL,
    fin_anterior    DATETIME(6) NULL,
    inicio_nuevo    DATETIME(6) NULL,
    fin_nuevo       DATETIME(6) NULL,

    actor_cuenta_id BIGINT      NULL COMMENT 'NULL = el sistema. Es informacion, no un descuido.',
    ocurrido_en     DATETIME(6) NOT NULL,

    CONSTRAINT fk_turno_evento_turno
        FOREIGN KEY (turno_id) REFERENCES turno (id),

    CONSTRAINT fk_turno_evento_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id),

    CONSTRAINT fk_turno_evento_consultorio
        FOREIGN KEY (consultorio_id) REFERENCES consultorio (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Historial append-only de transiciones de un turno. No se edita ni se borra.';

-- La unica lectura del historial: los eventos de un turno, en orden. `id` desempata dos
-- eventos con el mismo instante, que ocurre cuando una operacion registra mas de uno.
CREATE INDEX ix_turno_evento_turno
    ON turno_evento (organization_id, turno_id, ocurrido_en, id);


-- -------------------------------------------------------------------------------------
-- Backfill del historial de los turnos que ya existen.
--
-- Sin esto, RF-M12-008 mentiria sobre todo turno anterior a esta migracion: mostraria un
-- historial vacio para un turno que si fue reservado y quiza confirmado. Los datos estan en
-- la propia fila —`reservado_en`, `reservado_por_cuenta_id`, `confirmado_en`— asi que el
-- evento se reconstruye sin inventar nada. Lo unico que no se puede reconstruir es QUIEN
-- confirmo: V30 no lo guarda, y queda NULL, que es honesto.
-- -------------------------------------------------------------------------------------
INSERT INTO turno_evento (organization_id, consultorio_id, turno_id, tipo, estado_anterior,
                          estado_nuevo, actor_cuenta_id, ocurrido_en)
SELECT t.organization_id, t.consultorio_id, t.id, 'RESERVA', NULL, 'RESERVADO',
       t.reservado_por_cuenta_id, t.reservado_en
FROM turno t;

INSERT INTO turno_evento (organization_id, consultorio_id, turno_id, tipo, estado_anterior,
                          estado_nuevo, actor_cuenta_id, ocurrido_en)
SELECT t.organization_id, t.consultorio_id, t.id, 'CONFIRMACION', 'RESERVADO', 'CONFIRMADO',
       NULL, t.confirmado_en
FROM turno t
WHERE t.confirmado_en IS NOT NULL;
