# F9 — Segunda entrega (clases, participación, pases y abonos)

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~23 %**
> Etapas del plan: 08.01–08.09 (§13, líneas ~4021–4733). Leer DP-07 y DP-10 (§7).

## ⚠ Fase congelada

**DP-07** dice que la segunda entrega empieza cuando el MVP cumple su Definition of Done, está
desplegado y pasó una ventana de estabilización. 07.08 y 07.09 no existen. El registro de 08.01
salteó 07.09 por considerarla "de release, no técnica" y lo dejó como decisión pendiente 8, pero
cambiar la secuencia de DP-07 exige **una decisión formal de producto que no existe**.

**Recomendación:** no avanzar F9 hasta pasar el gate de [F8](F8-cierre-del-mvp.md), **o** escribir
la DP que lo habilite. Lo que ya está en `main` se queda (no se revierte), pero no se le suma.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 08.01 Clases y agenda unificada | PARCIAL | 60 | ITs de conflicto y capacidad; la agenda del front no muestra clases |
| 08.02 Inscripciones, cupos, espera | PARCIAL | 55 | ITs de concurrencia (criterio central); frontend |
| 08.03 Asistencia | PARCIAL | 45 | **No devenga obligación**; ITs; frontend |
| 08.04 Derivación al circuito clínico | PARCIAL | 30 | **Sin API**: solo el lado clínico, sin consumidor |
| 08.05 Atención clínica grupal | NO IMPLEMENTADA | 0 | Todo |
| 08.06 Productos, packs, venta | PARCIAL | 15 | **Solo esquema `V64`**, cero código Java |
| 08.07 Ledger de créditos | NO IMPLEMENTADA | 0 | Solo existe la tabla `movimiento_pase` |
| 08.08 Abonos | NO IMPLEMENTADA | 0 | Todo |
| 08.09 Integración económica | NO IMPLEMENTADA | 0 | Todo |

**Cero frontend de F9.** El `CLAUDE.md` dice que F9 cubre "derivación y productos": **no es así**.

## Lo que hay en `main`

- `V58` (clase), `V60` (inscripción), `V61` (asistencia), `V63` (derivación clínica), `V64`
  (producto, pase, movimiento, `esquema_cobro`/`momento_devengo` en la oferta).
- Módulo `activity` con `ClaseService`, `InscripcionService`, `AsistenciaService`; agenda
  unificada `GET /consultorios/{id}/agenda` con discriminador TURNO/CLASE; permisos `clase:read`/`clase:manage`.
- Lado clínico de la derivación: `DerivacionClinicaService` (427 líneas, **sin un test**),
  `clinical.spi.DerivacionClinicaRegistry`, `person.spi.PerfilPacienteProvisioning`.

## Ramas estacionadas

| Rama | Contenido | Qué hacer |
|---|---|---|
| `akine-08.06-productos` (`3e7d8d1`, pusheada) | WIP "SIN TERMINAR": `EsquemaCobro`, `MomentoDevengo`, `PoliticaDeDevengo` en `offering` + `spi.PoliticaDeDevengoDeOferta`. 19 archivos, +662/−78. Base vieja: 67 commits detrás de `main` | **No integrar.** Al retomar 08.06, rebasear sobre `main` y revisar contra el diseño `docs/diseno/AKINE-08.06-*` |

## Faltantes, en orden de retome

1. [ ] **08.04:** `activity.api.DerivacionController` y `activity.application.DerivacionService`
   que promete el diseño (§3) y no existen; tests de `DerivacionClinicaService`; pruebas de
   privacidad negativa; registro de avance (no tiene).
2. [ ] **08.01–08.03:** ITs de los escenarios diferidos 39–59 (cupo nunca superado, unique de
   asistencia contra el motor, cierre concurrente, lote con transacciones independientes, tenant).
3. [ ] **08.03:** devengo de la asistencia (RF-M18-008, RF-M19-009). `activity` no importa nada de
   `billing`. Depende de interpretar `esquema_cobro` y `momento_devengo`.
4. [ ] **08.06:** código sobre `V64` (entidades, compra idempotente, devengo); registro de avance (no tiene).
5. [ ] 08.05, 08.07, 08.08, 08.09 en ese orden.
6. [ ] Frontend por etapa: clases en la agenda, inscripciones y espera, asistencia, venta de pases.
7. [ ] Autoservicio del paciente: depende del alcance `OWN`.

> **En curso al 01/10:** la rama `akine-integracion-total` agregó unitarios de `activity`
> (`ClaseProgramadaTest`, `ClaseServiceTest`, `AsistenciaServiceTest`,
> `ActivityProblemHandlerTest`, `AgendaUnificadaServiceTest`). No reemplazan los ITs 39–59.

## Desvíos

| Desvío | Documentado |
|---|---|
| F9 abierta sin el gate del MVP | En registro de 08.01 y decisión pendiente 8, **sin DP formal** |
| 08.04 a medias, sin API | **No — silencioso**, sin registro |
| 08.06 solo esquema, mergeado a `main` | **No — silencioso**, sin registro |
| La vacante se impone al promover desde lista de espera (sin ventana de aceptación) | Sí |
| Devengo de asistencia diferido | Sí, registro y javadoc de `AsistenciaActividad` |
| `clase:*` propios en vez de `turno:*` | Sí, decisión pendiente 9 |

## Para cerrar la fase

- [ ] DP formal (o gate F8 pasado).
- [ ] 08.01–08.09 completas con ITs, frontend y E2E.
- [ ] Corregir el `CLAUDE.md` para que no dé por cubiertas la derivación y los productos.
