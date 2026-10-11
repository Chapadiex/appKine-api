# F8 — Cierre productivo del MVP

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~20 %**
> Etapas del plan: 07.06–07.09 (§13, líneas ~3705–4020). SLO en ADR-0016.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 07.06 Reportes y tableros | DESVIADA | 30 | ~~Nadie tiene `reporte:read`~~ (G-1); sin dashboards |
| 07.07 Hardening seguridad/privacidad/a11y | PARCIAL | 40 | Hallazgos altos sin cerrar; sin SAST/SCA/DAST |
| 07.08 Rendimiento, concurrencia, observabilidad | NO IMPLEMENTADA | 5 | Todo; sin registro |
| 07.09 Gate y release del MVP | NO IMPLEMENTADA | 5 | Todo |

Esta fase es **el gate** que DP-07 exige antes de abrir F9.

## Defectos

- [x] ~~**Verificado:** `reporte:read` existe en `PermissionCode` y lo exige `ReporteService`, pero
  **no está en `RolePermissions`** → los tres endpoints de `reporting` (catálogo, reporte, CSV) son
  inalcanzables. Antes de otorgarlo, decidir el alcance del `PROFESIONAL` (¿ve solo lo suyo?).~~ →
  G-1 (DP-15): otorgado según la matriz; el `PROFESIONAL` ve solo su actividad (alcance
  `ACTIVIDAD_PROPIA`), `PLATFORM_ADMIN` con soporte. `ReporteReadIT`; matriz §14.
- [x] ~~**A verificar:** `sumarAnuladoEnElReporte` no filtra `deletedAt` → el indicador de anulados
  cuenta de más.~~ → G-2: verificado (contaba 4000 en vez de 1000) y corregido;
  `AnuladoEnElReporteIT`.

## Faltantes

### 07.06 Reportes
- [x] ~~Otorgar `reporte:read` (ver arriba).~~ → G-1.
- [ ] Dashboards en el frontend.
- [ ] Exports asíncronos.
- [ ] Reconciliación y carga (escenarios 49–53).
- [ ] La sección de financiadores da cero hasta que exista la obligación del financiador ([F7](F7-economia-del-mvp.md)).

### 07.07 Hardening — hallazgos altos abiertos
El criterio de aceptación pide **cero hallazgos altos**. Abiertos:
> **Inventario de G-5 (09/10/2026), verificado contra el código.** Ver el registro de G-5 al final
> del plan y `docs/seguridad/threat-model.md`.

- [x] ~~Alcance `OWN` / `paciente:read`: un `PACIENTE` lee el padrón entero de su organización.~~ →
  DP-22 / DU-6 (#76): `paciente:read` solo para el personal; `OWN` queda post-MVP.
- [x] ~~`PLATFORM_ADMIN` inalcanzable.~~ → DP-14 / A-4 (#51): `AKINE_BOOTSTRAP_ADMIN_EMAIL`.
- [x] ~~`concurrent-modification` vs `conflict` sin unificar.~~ → DP-21 / DU-5 (#74).
- [x] ~~`encounter` no auditaba la Sesión~~ → **reabierto y cerrado en G-5**: la integración del
  29/09 había perdido el cambio de 07.07; restituido con `SesionAuditadaIT`.
- [ ] `encounter` no exige `X-Justificacion-Acceso` y audita con `reason` nulo (DP-03 parcial).
  **Decisión del usuario** (cambio de contrato).
- [ ] `caso:create` que nadie evalúa. **Decisión del usuario**; desde G-5 el efecto coincide con la
  fila de la matriz porque `hc:write` ya no se otorga a quien la matriz le dice "No".
- [x] ~~Threat model y matriz de permisos respaldada por tests~~ → G-5:
  `docs/seguridad/threat-model.md` y `MatrizDePermisosIT`. Los **runbooks** siguen en G-13.
- [x] ~~Un grant podía otorgar una celda "No" de la matriz~~ (hallazgo nuevo de G-5, cerrado).
- [ ] SAST, SCA y DAST en CI; activar SonarQube. **Decisión del usuario** (configuración del repo).
- [ ] a11y pendiente: WCAG 1.4.11 (bordes de control y anillo de foco, 3:1), estados `:hover` y
  `:focus-visible`, foco por teclado, franja del degradé (~4,16:1).

### 07.08 Rendimiento y observabilidad (hoy solo hay actuator `health,info`)
- [x] ~~Logging JSON estructurado con correlación.~~ → G-4 (`docs/observabilidad.md`).
- [x] ~~Micrometer + Prometheus, métricas RED.~~ → G-4: histograma de `http.server.requests` con buckets de 300 ms y 800 ms.
- [x] ~~OpenTelemetry.~~ → G-4: Micrometer Tracing + OTLP, exportación opt-in.
- [ ] Dashboards y alertas.
- [ ] Harness de carga (k6 está elegido en `docs/producto/pruebas-de-carga.md` y nunca se ejecutó).
- [ ] Medir p95/p99 contra los SLO de ADR-0016; LCP del frontend.
- [ ] Baseline y runbooks.

### 07.09 Gate de release
- [x] ~~Dockerfile del backend.~~ → G-3: `Dockerfile` multi-stage (JDK 21 → JRE 21, usuario no root, layered jar, healthcheck sobre `/actuator/health/liveness`), verificado arrancando contra el MySQL de `compose.yaml`; SBOM CycloneDX y job `imagen` en el CI.
- [ ] Dockerfile del frontend (no hay ninguno). **Sigue abierto**: G-3 cubrió solo el backend.
- [ ] Job E2E del CI del frontend contra el backend (hoy comentado).
- [ ] E2E core M01–M27 (hoy 7 specs: auth, contexto, agenda ×2, contraste, errores, smoke).
- [ ] **QA manual del §6 del `CLAUDE.md`**, que no corre desde 02.02 y está declarado bloqueante.
- [ ] Gate de cobertura de vuelta a 0,80 (hoy 0,73 / 0,71 en `pom.xml`); considerar gate de `BRANCH`.
- [ ] Ensayo de backup/restore, rollback y smoke post-deploy.
- [ ] Release notes y runbooks.
- [ ] Aprobación clínica y legal (DP-08).

> **En curso al 01/10:** la rama `akine-integracion-total` agregó `TurnosEnElReporteTest`,
> `EconomiaEnElReporteTest` y `FinanciadoresEnElReporteTest`.

## Para cerrar la fase (= pasar el gate del MVP)

- [ ] Reportes accesibles y con dashboards.
- [ ] Cero hallazgos altos de 07.07.
- [ ] Observabilidad mínima y una corrida de carga medida contra los SLO.
- [ ] Imagen Docker, E2E en CI, QA manual corrido, cobertura ≥ 0,80.
- [ ] Deploy y ventana de estabilización (DP-07). **Recién después se abre F9.**
