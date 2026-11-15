# Analizador de evolución, fase 1 — registro de implementación

> Problemas, desvíos del plan y cómo se resolvió cada uno, en el orden en que aparecieron.
> Plan: `AKINE-analizador-de-evolucion-plan.md`. Diseño: `AKINE-analizador-de-evolucion.md`.

| # | Tarea | Qué pasó | Cómo se resolvió |
|---|---|---|---|
| 1 | 2 | — | La sonda con `encounter → knowledge.spi`, `clinical.spi` y `scheduling.spi` pasó `ModuleArchitectureTest` (5/5). Sondas borradas sin commit |
| 2 | 3 | Las ediciones por script no aplicaban: los archivos del checkout tienen fin de línea CRLF (`core.autocrlf=true`) y en la máquina no hay Python | Se restauraron, se normalizaron a LF (git los vuelve a CRLF al hacer checkout) y se editaron de nuevo. Sin efecto en el contenido |
| 3 | 4 | El plan pedía sumar campos a `CasoSnapshot`, que tiene 12 llamadas al constructor en tests y código | Se agregó `CasoDirectory.detalle(...)` con un record propio, `CasoDetalleSnapshot`. `CasoSnapshot` no cambia |
| 4 | 4 | La versión del plan vigente en una fecha sí se puede saber: `plan_tratamiento_version.registrada_en` | `PlanVigenteSnapshot` lleva `versionVigenteDesde`, y `CONDUCTA_SIN_SEGUIR` se implementa (el plan preveía no hacerlo si no había forma de saberlo) |
| 5 | 5 | `turno` no tiene índice por `caso_id` (sí lo tiene `sesion`, `ix_sesion_caso`) | `V17` suma `ix_turno_caso (organization_id, caso_id, inicio)`. La migración toca dos tablas de dos módulos; es una sola versión reservada para M31 |
| 6 | 5 | El plan pedía un `TurnosDelCasoIT` aparte. El IT de la Tarea 8 necesita el mismo armado (caso, sesiones, turnos cancelados por el paciente y por la institución, otra organización) | Los escenarios de turnos del caso se cubren dentro de `EvolucionDelCasoIT`, contra PostgreSQL, en vez de duplicar el armado (preferencia del proyecto: menos tests, los que corren contra el motor no se recortan) |
| 7 | 6 | Se verificó que la enmienda reescribe la fila de `sesion` (`Sesion.enmendar`): la lectura no necesita `sesion_version` | Confirmado en el código. No se crea el record intermedio `SesionParaEvolucion`: el servicio arma directamente la entrada del dominio, que es la única forma en que se usa |
| 8 | 7 | `CONDUCTA_SIN_SEGUIR` con `REEVALUA` disparaba en la sesión siguiente, que suele ser justamente la reevaluación | `REEVALUA` exige dos sesiones presentes posteriores; `ALTA` y `DERIVA`, una. Además, la regla sólo mira la última sesión con una de esas conductas. Criterio del diseño, a confirmar con los umbrales (U-02) |
| 9 | 7 | Un turno `CANCELADO` sin origen (datos anteriores a `origen_cancelacion`) no dice quién canceló | No cuenta como inasistencia: sólo se cuenta en contra del paciente lo que el dato afirma (RN-M31-008). Un turno vigente ya pasado y sin sesión tampoco se cuenta: nadie registró qué pasó |
| 10 | 7 | `DOLOR_EN_AUMENTO` y `DOLOR_SIN_MEJORA` pueden cumplirse a la vez (si sube, tampoco bajó) | Si dispara el aumento, no se agrega "sin mejora". Lo mismo `MEDICION_EMPEORA` sobre `MEDICION_SIN_CAMBIO`, y la racha de `PEOR` sobre la de `IGUAL` |
| 11 | 7 | La frecuencia de un plan activado hace una semana se subestimaba contra una ventana de cuatro | La ventana empieza en la última de: activación del plan o primera sesión, con un tope de `frecuencia.ventana-semanas` |
| 12 | 7 | Tres tests fallaron en la primera corrida: las sesiones de prueba quedaban antes de la versión del plan y fuera de la ventana de frecuencia | Era el armado, no la regla. Se corrigieron las fechas del fixture; 25/25 en verde |
