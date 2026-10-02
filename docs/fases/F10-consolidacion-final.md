# F10 — Consolidación final

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **0 %**
> Etapas del plan: 09.01–09.04 (§13, líneas ~4734–5053).

Ninguna etapa empezó. Depende de [F9](F9-segunda-entrega.md) completa.

| Etapa | Estado | Punto de partida |
|---|---|---|
| 09.01 Reportes para clases y productos | NO IMPLEMENTADA | `reporting` crece por contribuyentes (`ReporteContributor`): hoy hay de turnos, sesiones, casos, economía y financiadores. Falta el de `activity` |
| 09.02 Hardening final | NO IMPLEMENTADA | Hereda lo de 07.07 ([F8](F8-cierre-del-mvp.md)) más la superficie de F9 |
| 09.03 Concurrencia, rendimiento, observabilidad | NO IMPLEMENTADA | Hereda 07.08. k6 elegido y sin ejecutar |
| 09.04 Release candidate, E2E, CI/CD, docs | NO IMPLEMENTADA | Hereda 07.09. `ci.yml` existe; Sonar comentado; sin backup/restore ni aprobación clínico-legal |

Si F8 se cierra bien, buena parte de 09.02–09.04 es repetir sus gates sobre la superficie de F9.

## Para cerrar la fase

- [ ] Contribuyente de `activity` al reporting.
- [ ] Gates de F8 repetidos con M28–M29 adentro.
- [ ] Release completo M01–M29.
- [ ] Tabla de trazabilidad del plan (§ líneas ~5254–5312) actualizada: hoy marca las 56 etapas
  como "NO IMPLEMENTADO".
