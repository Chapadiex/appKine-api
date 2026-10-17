# F10 — Consolidación final

> **Reverificada el 16/10/2026** · backend `main` `eef0c87` (contrato 0.118.0, `V103`) · **~25 %**.
> Auditoría original del 01/10/2026 sobre `37ff201` (0 %).
> Etapas del plan: 09.01–09.04 (§13, `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` desde la línea ~5063).

F10 arrancó el 16/10, con [F9](F9-segunda-entrega.md) en ~82 %: las cuatro etapas corren en
paralelo, cada una en su worktree. **Ninguna tiene PR mergeado** al 16/10; lo que sigue mide lo
que hay en `main` y lo que heredan de F8 y del carril G.

## Estado por etapa

| Etapa | Estado | % | Qué hay en `main` | Lo que falta |
|---|---|---|---|---|
| 09.01 Reportes para clases y productos | EN CURSO (rama `akine-09.01-reportes-activity`, sin commits) | 10 | `reporting` crece por contribuyentes (`ReporteContributor`): turnos, sesiones, casos, economía y financiadores. `ReporteCode` ya se está tocando en la rama | El contribuyente de `activity`: clases, ocupación, asistencia, pases vendidos, consumidos, vencidos y anulados, abonos renovados |
| 09.02 Hardening de seguridad, privacidad y accesibilidad | EN CURSO (rama `akine-09.02-hardening-f9`, un IT sin commitear: `ActividadAislamientoIT`) | 30 | G-5 (threat model, #78), G-7 (a11y, web #24), aislamiento de tenant por IT en los módulos del MVP, pre-QA de 146 pasos con D1–D7 corregidos (#126, #131, #133) | Aislamiento de tenant de la superficie de F9 (escenarios diferidos 41, 48 y 59); SAST/DAST (**U-06**, propuesta abierta en #119); `caso:create` y la justificación en `encounter` (U-03, U-04) |
| 09.03 Concurrencia, rendimiento y observabilidad | EN CURSO (rama `akine-09.03-concurrencia-f9`, un archivo de soporte sin commitear) | 30 | G-4 observabilidad, G-10 carga con k6 (`docs/carga.md` §6–§11), N+1 de agenda y recepción cerrados (#91, #134), concurrencia de pases, cupos, derivación y atención grupal con ITs (ver F9) | Escenarios diferidos de concurrencia de 08.01–08.03 (39, 46, 55, 57); carga de F9; **el SLO de escrituras a 500 usuarios sigue sin cumplirse** y espera decisión (`docs/carga.md` §10) |
| 09.04 Release candidate, E2E, CI/CD y documentación | EN CURSO (este documento, rama `akine-09.04-docs-rc`) | 30 | CI con cuatro checks en verde sobre `eef0c87` (4.009 unitarias + 880 de integración); imagen Docker y SBOM (G-3); backup, restore, rollback, smoke y runbooks (G-13, `docs/operaciones/`); E2E contra el backend real en el CI de `appKine-web`, con specs de clases, asistencia, pases y atención grupal; notas del RC en [`../release/RC-1.md`](../release/RC-1.md); runbook de los jobs de F9 (`docs/operaciones/runbooks.md` R7) | **QA manual** (U-01), **aprobación clínica y legal** y deploy con ventana de estabilización (U-02), ensayo de migración `V96`–`V103` contra una copia de datos, tag del RC |

El porcentaje de la fase es el promedio simple de las cuatro.

## Lo que el criterio de aceptación de 09.04 pide y dónde está

| Criterio del plan | Estado al 16/10 |
|---|---|
| Pipeline verde | **Sí**: los cuatro checks de `eef0c87` (build/test/contrato, imagen y SBOM, OWASP/OSV, Sonar) |
| Migraciones ensayadas | **A medias**: cada CI aplica `V1`–`V103` sobre una base vacía; el simulacro de restore de G-13 es anterior a `V96`. Falta aplicar `V96`–`V103` sobre una copia restaurada |
| OpenAPI coherente | **Sí**: 0.118.0, 333 operaciones, sin `operationId` repetido, `OpenApiContractIT` sin drift; cliente web en 0.118.0 |
| E2E críticos verdes | **Parcial**: el plan pide tenant/login, consultorio/personal, paciente/cobertura, caso/plan, turno/check-in/sesión, deuda/cobro/caja, clase/pase y reportes. Hay spec para cada uno entre los 60 de `appKine-web/e2e/` (`flujo-clases`, `flujo-pases`, `flujo-asistencia`, `flujo-atencion-grupal`, `flujo-reportes`…); **la derivación de un participante no tiene pantalla** (F9) |
| Runbook usable | **Sí** para lo del MVP (R1–R6) y, desde este documento, los jobs de F9 (R7). Sin ejecutar en un incidente real |
| Aprobación clínica y legal documentada | **No** (U-02) |

## Para cerrar la fase

- [ ] Contribuyente de `activity` al reporting (09.01).
- [ ] Gates de F8 repetidos con M28–M29 adentro (09.02, 09.03).
- [ ] QA manual corrido con los circuitos de F9 incluidos (los doce circuitos de `docs/qa/` son del MVP:
  no hay uno de clases, pases y abonos).
- [ ] Release completo M01–M29: tag del RC, notas finales, aprobación.
- [ ] Tabla de trazabilidad del plan (§ líneas ~5254–5312) actualizada: hoy marca las 56 etapas
  como "NO IMPLEMENTADO".
