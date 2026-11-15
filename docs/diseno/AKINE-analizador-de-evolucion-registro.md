# Analizador de evolución, fase 1 — registro de implementación

> Problemas, desvíos del plan y cómo se resolvió cada uno, en el orden en que aparecieron.
> Plan: `AKINE-analizador-de-evolucion-plan.md`. Diseño: `AKINE-analizador-de-evolucion.md`.

| # | Tarea | Qué pasó | Cómo se resolvió |
|---|---|---|---|
| 1 | 2 | — | La sonda con `encounter → knowledge.spi`, `clinical.spi` y `scheduling.spi` pasó `ModuleArchitectureTest` (5/5). Sondas borradas sin commit |
| 2 | 3 | Las ediciones por script no aplicaban: los archivos del checkout tienen fin de línea CRLF (`core.autocrlf=true`) y en la máquina no hay Python | Se restauraron, se normalizaron a LF (git los vuelve a CRLF al hacer checkout) y se editaron de nuevo. Sin efecto en el contenido |
| 3 | 4 | El plan pedía sumar campos a `CasoSnapshot`, que tiene 12 llamadas al constructor en tests y código | Se agregó `CasoDirectory.detalle(...)` con un record propio, `CasoDetalleSnapshot`. `CasoSnapshot` no cambia |
| 4 | 4 | La versión del plan vigente en una fecha sí se puede saber: `plan_tratamiento_version.registrada_en` | `PlanVigenteSnapshot` lleva `versionVigenteDesde`, y `CONDUCTA_SIN_SEGUIR` se implementa (el plan preveía no hacerlo si no había forma de saberlo) |
