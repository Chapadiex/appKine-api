-- =====================================================================================
-- AKINE-07.06 — M23 Reportes y tableros del MVP
--
-- ESTA MIGRACION NO CREA NINGUNA TABLA, Y ESO ES EL PUNTO.
--
-- Un reporte es una lectura, y una lectura no puede convertirse en una segunda copia de
-- la verdad. El repositorio viene sosteniendo esa regla en cinco lugares —la
-- disponibilidad efectiva de 02.04, el Paciente 360 de 03.02, el timeline clinico de
-- 04.02, el motor de slots de 05.01 y la comparacion de mediciones de 06.03— y esta
-- etapa no la rompe: no hay tabla de KPI, no hay snapshot diario, no hay proyeccion
-- refrescada por un job. El modulo `reporting` no tiene una sola tabla propia ni capa
-- `infrastructure`.
--
-- El argumento no es de pureza, es de falla. Una tabla de resumen se desincroniza el dia
-- que alguien escribe por otro camino, y aca los caminos que escriben llegan de a poco:
-- el devengado de financiador se va a recablear, 06.04 va a cambiar como avanza el plan,
-- una anulacion retroactiva toca un periodo ya "cerrado". Cada uno de esos cambios seria
-- un backfill que alguien tiene que acordarse de correr. Cuando no se acuerda, el tablero
-- no se rompe: miente, y nadie lo detecta.
--
-- Lo unico que se agrega son DOS INDICES. Un indice no es una copia de la verdad: es la
-- misma verdad, ordenada para poder leerla. No expone un camino de lectura nuevo, no
-- agrega una fila y no puede divergir.
--
-- CONDICION DE SALIDA de la decision de no materializar, para que sea revisable y no un
-- dogma: se materializa el dia que exista una medicion, contra un dataset representativo
-- y contra MySQL real, que muestre que un reporte del MVP no entra en el tiempo
-- interactivo con estos indices. Hoy esa medicion no existe y no se puede tomar. Un cache
-- que nadie midio es solo una copia mas.
--
-- Los DOS empiezan por organization_id, como exige ADR-0004. Un indice de reporte que no
-- empiece por el tenant es una invitacion a un plan de ejecucion que escanea el SaaS
-- entero, y una agregacion que se olvida del filtro no falla: devuelve un numero mas
-- grande, y nadie lo nota.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- `sesion` tiene indices por historia (V33), por profesional (V33) y por caso (V48).
-- NINGUNO por sede, y el reporte clinico y el operativo recortan exactamente por sede y
-- por rango de cierre: "cuantas sesiones se cerraron en esta sede este mes".
--
-- `cerrada_en` y no `iniciada_en`: una sesion en borrador todavia no es una atencion
-- realizada. Sesion != Turno, y ninguna transicion administrativa prueba por si sola que
-- una prestacion ocurrio (DP-05).
-- -------------------------------------------------------------------------------------
CREATE INDEX ix_sesion_sede_cierre
    ON sesion (organization_id, consultorio_id, cerrada_en);

-- -------------------------------------------------------------------------------------
-- `caso_clinico` (V47) NO TIENE NINGUN INDICE no-unico. El reporte clinico cuenta los
-- casos abiertos y cerrados de una sede en un periodo.
--
-- La sede de un caso es `oferta_consultorio_id` y no una columna `consultorio_id`: el
-- caso cuelga de la historia clinica, que es de la ORGANIZACION (DP-03), y su sede es la
-- de la oferta que lo origino. Indexar la columna equivocada aca daria un indice que el
-- optimizador nunca usa.
-- -------------------------------------------------------------------------------------
CREATE INDEX ix_caso_clinico_sede_apertura
    ON caso_clinico (organization_id, oferta_consultorio_id, abierto_en);
