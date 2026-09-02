-- =====================================================================================
-- AKINE-05.04 (reducida) — Recepcion y check-in (M13)
--
-- Registra la LLEGADA REAL del paciente al centro. Es la pata administrativa que DP-10
-- habia dejado afuera y declarado como riesgo aceptado: sin ella el turno pasa de
-- "reservado" a "atendido" sin que nadie haya constatado que la persona vino.
--
-- Cuatro decisiones que esta migracion fija:
--
-- 1. EL CHECK-IN ES UN ESTADO DEL TURNO, NO DE LA SESION. DP-05 separa Turno, Recepcion y
--    Sesion, y la separacion se sostiene aca: la recepcionista marca la llegada sin abrir
--    ninguna atencion, y el profesional abre la atencion sin depender de que alguien haya
--    marcado la llegada. Son dos actos de dos personas distintas y ninguno bloquea al otro.
--
-- 2. `llegada_en` LA PONE EL SERVIDOR. El plan lo pide explicito ("hora real server-side") y
--    la razon es que la hora de llegada es evidencia administrativa: si la mandara el
--    cliente, el reloj de la recepcion —o cualquiera con la consola abierta— decidiria a que
--    hora llego un paciente. Es el mismo criterio que `cobrado_en` y `cerrada_en`.
--
-- 3. `estado_antes_de_espera` EXISTE PARA PODER DESHACER. Un check-in sobre la persona
--    equivocada es un error de un click, y sin vuelta atras la unica salida seria cancelar
--    un turno que nadie cancelo. Se guarda el estado del que se vino —RESERVADO o
--    CONFIRMADO— porque volver siempre a CONFIRMADO inventaria una confirmacion que quiza
--    nunca ocurrio.
--
-- 4. NO HAY VALIDACION DE COBERTURA NI DE AUTORIZACIONES, y es recableo de DP-10, no olvido.
--    El plan hace depender esta etapa de 03.06 y 04.05, que quedaron fuera de alcance; con
--    cobertura PARTICULAR unica no hay condicion administrativa que validar. El dia que
--    existan, el snapshot administrativo preliminar es una tabla nueva que cuelga de esta
--    misma fila: cortar alcance no es cortar modelo.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- turno — columnas de la recepcion
-- -------------------------------------------------------------------------------------
ALTER TABLE turno
    ADD COLUMN llegada_en              DATETIME(6) NULL
        COMMENT 'Hora real de llegada, puesta por el servidor. NULL mientras el paciente no llego.'
        AFTER reprogramado_en,
    ADD COLUMN llegada_por_cuenta_id   BIGINT      NULL
        COMMENT 'Quien registro la llegada. Es evidencia administrativa: siempre hay un responsable.'
        AFTER llegada_en,
    ADD COLUMN estado_antes_de_espera  VARCHAR(16) NULL
        COMMENT 'De donde vino el check-in, para poder deshacerlo sin inventar una confirmacion.'
        AFTER llegada_por_cuenta_id;

-- Un turno EN_ESPERA tiene hora de llegada. Sin esto una fila podria decir que el paciente
-- esta esperando sin que conste cuando llego, que es justamente el dato por el que existe
-- la etapa.
--
-- La implicacion va en UN SOLO SENTIDO, y esto es lo que hay que entender antes de
-- endurecerlo: la vuelta —"solo un turno EN_ESPERA tiene llegada"— es FALSA. Un paciente
-- que llego y al que el centro despues no pudo atender queda CANCELADO **conservando su
-- hora de llegada**, porque consta que vino; borrarla al cancelar destruiria exactamente
-- la evidencia que la recepcion existe para registrar. Lo mismo vale para el dia en que
-- exista un estado de atendido.
--
-- El caso que si borra la hora es deshacer el check-in, y ahi el estado tambien vuelve
-- atras: un check-in deshecho no dejo una llegada, dejo un error corregido, y el rastro de
-- que ocurrio vive en turno_evento, que es append-only.
ALTER TABLE turno
    ADD CONSTRAINT ck_turno_espera_tiene_llegada
        CHECK (estado <> 'EN_ESPERA' OR llegada_en IS NOT NULL);

-- Una hora de llegada sin responsable no es evidencia administrativa de nada: siempre
-- consta quien la registro. Vale en las dos direcciones porque las dos columnas se
-- escriben y se limpian juntas.
ALTER TABLE turno
    ADD CONSTRAINT ck_turno_llegada_con_responsable
        CHECK ((llegada_en IS NULL AND llegada_por_cuenta_id IS NULL)
            OR (llegada_en IS NOT NULL AND llegada_por_cuenta_id IS NOT NULL));

-- -------------------------------------------------------------------------------------
-- El indice de la pantalla de recepcion
-- -------------------------------------------------------------------------------------
-- "Los turnos de esta sede en este dia", que es la primera pregunta que hace un centro
-- cuando abre. Los indices de V30 no sirven: todos empiezan por profesional, espacio,
-- oferta o persona, y la recepcion no filtra por ninguno de esos —quiere el dia entero—.
--
-- Lleva `consultorio_id` y no solo `organization_id` porque la recepcion es de UNA sede:
-- quien atiende el mostrador de Belgrano no tiene por que ver la cola de Nueva Cordoba.
--
-- `deleted_key` queda fuera a proposito: la recepcion SI quiere ver los cancelados del dia
-- —para poder decirle a alguien que su turno se cancelo— y filtrarlos en el indice
-- obligaria a una segunda consulta para mostrarlos.
CREATE INDEX ix_turno_sede_dia
    ON turno (organization_id, consultorio_id, inicio);
