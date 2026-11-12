-- =====================================================================================
-- AKINE-02.04 — Calendario de feriados (M05).
--
-- Trazabilidad: RF-M05-004 (registrar excepcion). ADR-0003 (Flyway unica autoridad),
-- ADR-0004 (persistencia multi-tenant), ADR-0022 (feriados globales sin organization_id).
--
-- Propietario de la tabla: modulo `resource`.
--
-- POR QUE ESTA TABLA NO LLEVA organization_id
--
-- Un feriado nacional no es de nadie. Es el mismo caso de ADR-0021 con los catalogos
-- clinicos globales, pero mas simple: aca no conviven dos poblaciones (global y contextual)
-- en la misma tabla, asi que no hace falta el mecanismo de owner_key nullable de V20. Alcanza
-- con la ausencia lisa y llana de la columna, como en ADR-0019 y ADR-0020. La decision que SI
-- es de cada centro —si cierra o no ese dia— vive en `consultorio_calendario`, que lleva
-- organization_id NOT NULL como cualquier tabla de negocio (V23).
--
-- POR QUE EL UNIQUE NO NECESITA CENTINELA
--
-- Este repositorio ya se cruzo varias veces con que en MySQL varios NULL no colisionan en un
-- indice unico (V10, V12, V18/V19, V20). Aca ese problema NO aparece: no hay ninguna columna
-- nullable involucrada en el UNIQUE. `pais` tiene DEFAULT 'AR' pero es NOT NULL, y `fecha` es
-- NOT NULL. Dos filas con el mismo pais y la misma fecha son, sin ninguna ambiguedad, el mismo
-- feriado duplicado. Un centinela aca seria una solucion a un problema que esta tabla no tiene.
--
-- POR QUE EL SEED ENVEJECE, Y POR QUE ESTA BIEN QUE ASI SEA
--
-- Los feriados trasladables y los puentes se fijan por decreto cada anio. Ningun seed puede
-- adelantarse a eso. Por ese motivo la sede SIEMPRE puede cargar una excepcion propia sin
-- depender de que esta tabla este al dia: el seed es una comodidad, no la autoridad.
-- =====================================================================================

CREATE TABLE feriado
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,

    pais       CHAR(2)      NOT NULL DEFAULT 'AR' COMMENT 'ISO 3166-1 alfa-2. Existe desde el dia uno para que sumar otro pais no sea una migracion de datos',
    fecha      DATE         NOT NULL COMMENT 'Fecha calendario del feriado. Sin hora: un feriado es un dia, no un instante',
    nombre     VARCHAR(160) NOT NULL COMMENT 'Denominacion oficial. Se muestra tal cual en la pantalla de calendario',
    tipo       VARCHAR(32)  NOT NULL COMMENT 'Clasificacion oficial. INAMOVIBLE y TRASLADABLE son las dos que cambian el comportamiento del decreto anual',

    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,

    CONSTRAINT pk_feriado PRIMARY KEY (id),

    -- Un pais no puede tener dos feriados el mismo dia. Si dos conmemoraciones caen juntas,
    -- el nombre las junta: son un solo dia no laborable.
    CONSTRAINT uk_feriado_pais_fecha UNIQUE (pais, fecha),

    CONSTRAINT ck_feriado_tipo
        CHECK (tipo IN ('INAMOVIBLE', 'TRASLADABLE', 'PUENTE', 'NO_LABORABLE', 'RELIGIOSO'))
)
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_0900_ai_ci
    COMMENT = 'Feriado del calendario nacional (M05). GLOBAL, sin organization_id: ADR-0022. Que un feriado cierre o no una sede lo decide consultorio_calendario, no esta tabla';


-- =====================================================================================
-- SEED — Argentina, 2026 y 2027.
--
-- QUE SE CARGA Y POR QUE
--
-- Los NUEVE feriados INAMOVIBLES (Ley 27.399): su fecha calendario es la fecha observada,
-- siempre, caiga el dia de la semana que caiga. No hay decreto que los mueva, asi que no hay
-- nada que verificar anio a anio mas alla de la fecha en si.
--
-- Se agregan TRES feriados TRASLADABLES de 2026 —17 de agosto (San Martin), 12 de octubre
-- (Diversidad Cultural) y 20 de noviembre (Soberania Nacional)— y SOLO de 2026, porque para ese
-- anio la fecha observada YA ES PUBLICA Y VERIFICABLE. No es una inferencia de la regla de
-- traslado: es el resultado ya publicado.
--
-- LA COLUMNA `fecha` GUARDA LA FECHA OBSERVADA, NO LA CONMEMORATIVA
--
-- Es lo que hace que la tabla sirva para algo: el dia que el centro cierra es el dia observado.
-- Para San Martin y Diversidad Cultural las dos fechas coinciden —ambos caen lunes en 2026, asi
-- que el decreto no los mueve—. Para el Dia de la Soberania Nacional NO coinciden: la fecha
-- conmemorativa es el 20 de noviembre de 2026, que cae VIERNES, y la fecha observada es el
-- LUNES 23 de noviembre de 2026. La fila lleva el 23. Sembrar el 20 cerraria el centro el dia
-- equivocado y dejaria abierto el que la gente no trabaja, que es el peor de los dos errores
-- porque nadie lo denuncia hasta que llega el lunes. El nombre conserva la denominacion oficial:
-- lo que se traslada es la observancia, no la conmemoracion.
--
-- QUE NO SE CARGA, Y POR QUE (RULING R4 — no inventar fechas)
--
-- * Los MISMOS TRES trasladables para 2027 (San Martin 17/08, Diversidad Cultural 12/10,
--   Soberania Nacional 20/11): quedan AFUERA. El decreto que fija la fecha observada de 2027 se
--   firma habitualmente sobre el cierre de 2026, y a la fecha de este seed no hay decreto
--   publicado que confirmar. Cargar la fecha calendario a secas seria asumir que no se
--   trasladan, que es precisamente lo que un decreto puede cambiar.
-- * Paso a la Inmortalidad del General Guemes (17 de junio), en NINGUNO de los dos anios: es
--   trasladable y no forma parte del conjunto que esta migracion pudo verificar con certeza
--   para su fecha observada.
-- * Carnaval y Viernes Santo, en NINGUNO de los dos anios: son moviles por el calendario
--   liturgico, no por decreto, y no son responsabilidad de esta migracion inventarlos.
-- * Cualquier PUENTE (dia no laborable declarado ad-hoc): no existen por adelantado, se
--   declaran anio a anio y no tienen fecha fija que sembrar.
--
-- Un seed con fechas inventadas es peor que no tener seed: nadie las revisa despues y el
-- sistema cerraria centros el dia equivocado. Lo que falta ARRIBA lo carga la sede como
-- excepcion propia en `consultorio_calendario`/excepciones (V23), que no depende de que este
-- seed este al dia.
-- =====================================================================================

INSERT INTO feriado (pais, fecha, nombre, tipo, created_at, updated_at) VALUES
 ('AR', '2026-01-01', 'Año Nuevo', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-03-24', 'Día Nacional de la Memoria por la Verdad y la Justicia', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-04-02', 'Día del Veterano y de los Caídos en la Guerra de Malvinas', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-05-01', 'Día del Trabajador', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-05-25', 'Día de la Revolución de Mayo', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-06-20', 'Paso a la Inmortalidad del General Manuel Belgrano', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-07-09', 'Día de la Independencia', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-08-17', 'Paso a la Inmortalidad del General José de San Martín', 'TRASLADABLE', NOW(6), NOW(6)),
 ('AR', '2026-10-12', 'Día del Respeto a la Diversidad Cultural', 'TRASLADABLE', NOW(6), NOW(6)),
 -- Conmemoracion el viernes 20/11/2026; observancia trasladada al LUNES 23/11/2026, que es la
 -- fecha que va en la columna. Ver "LA COLUMNA `fecha` GUARDA LA FECHA OBSERVADA" mas arriba.
 ('AR', '2026-11-23', 'Día de la Soberanía Nacional', 'TRASLADABLE', NOW(6), NOW(6)),
 ('AR', '2026-12-08', 'Inmaculada Concepción de María', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2026-12-25', 'Navidad', 'INAMOVIBLE', NOW(6), NOW(6)),

 ('AR', '2027-01-01', 'Año Nuevo', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-03-24', 'Día Nacional de la Memoria por la Verdad y la Justicia', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-04-02', 'Día del Veterano y de los Caídos en la Guerra de Malvinas', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-05-01', 'Día del Trabajador', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-05-25', 'Día de la Revolución de Mayo', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-06-20', 'Paso a la Inmortalidad del General Manuel Belgrano', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-07-09', 'Día de la Independencia', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-12-08', 'Inmaculada Concepción de María', 'INAMOVIBLE', NOW(6), NOW(6)),
 ('AR', '2027-12-25', 'Navidad', 'INAMOVIBLE', NOW(6), NOW(6));
