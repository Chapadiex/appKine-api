# F8 — Cierre productivo del MVP

> **Reverificada el 13/10/2026** · backend `8979519` (contrato 0.90.0, `V85`) · frontend
> `2cdd3bb` · **~80 %**. Lo técnico que no espera una decisión está hecho o en curso; **el gate
> del MVP no pasa** y lo que lo frena es del usuario (QA manual, aprobación, deploy, dos
> decisiones de seguridad y una CVE con excepción). Ver el registro de cierre al final.
> Auditoría original del 01/10/2026 sobre backend `37ff201` / frontend `4c6ae8b` (~20 %).
> Etapas del plan: 07.06–07.09 (§13, líneas ~3705–4020). SLO en ADR-0016.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 07.06 Reportes y tableros | CUMPLIDA con desvío declarado | 90 | Export asíncrono diferido por diseño (síncrono y acotado); escenario 52 sin correr |
| 07.07 Hardening seguridad/privacidad/a11y | PARCIAL, pendiente del usuario | 85 | `caso:create` (U-04). Justificación en `encounter`, SAST/DAST y Connector/J **decididos el 17/10/2026** (DP-33, DP-35, DP-34) |
| 07.08 Rendimiento, concurrencia, observabilidad | PARCIAL, en curso | 75 | p95 con 500 usuarios y escrituras 3,3–3,6 s contra 300 ms: rendimiento por pedido en curso |
| 07.09 Gate y release del MVP | PARCIAL, pendiente del usuario | 70 | QA manual, aprobación clínica y legal, deploy y estabilización (G-14); E2E clínicos (web #4) |

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
- [x] ~~El indicador "Cancelados" del reporte de turnos daba siempre 0.~~ → api #86 (11/10),
  encontrado validando la guía de G-12; `ReporteReadIT.el_cancelado_cuenta`.
- [x] ~~Los schemas de reportes colisionaban con los del Paciente 360 en el contrato.~~ → api #58
  (07/10), `SchemaNameCollisionTest`.

## Faltantes

### 07.06 Reportes
- [x] ~~Otorgar `reporte:read` (ver arriba).~~ → G-1.
- [x] ~~Dashboards en el frontend.~~ → G-8, web #12 (07/10): tablero de reportes de la sede, con
  los cinco reportes y el CSV. Recorrido a 375 px en `e2e/movil.spec.ts` (web #66, 13/10) y con
  datos reales en `e2e/flujo-dia-de-la-sede.spec.ts`.
- [ ] Exports asíncronos. **Desvío declarado, no implementado:** el export es síncrono, con una
  ventana máxima de 366 días y 500 filas por sección (`ReporteService.MAXIMO_DIAS`,
  `LIMITE_FILAS`; descripción de la operación en el contrato). La carga de G-10 midió el reporte
  en p95 38 ms con 500 usuarios en lecturas (`docs/carga.md` §5, corrida 2b): no hay hoy un dato
  que pida el asíncrono. **Confirmar el desvío es del usuario**; no bloquea el gate.
- [x] ~~Reconciliación y carga (escenarios 49–53).~~ → **cierre de F8 (13/10):** 49, 50, 51 y 53
  en verde en `reporting/ReportesContraElMotorIT` (5 casos contra MySQL). La conciliación da
  **cero** con efectivo, mixto y tarjeta; dos tenants no se mezclan en turnos ni en economía; el
  turno de las 21:30 cae en el día de la sede; los bordes `DATETIME(6)` entran y salen donde
  deben. **52 (EXPLAIN) sigue sin correr**: sobre las filas de un IT el optimizador elige `ALL`
  aunque el índice exista. Detalle en `docs/tests-diferidos.md` §07.06. La "carga" del ítem la
  cubre G-10.
- [x] ~~La sección de financiadores da cero hasta que exista la obligación del financiador.~~ →
  F-4, api #49 (`V77`, `ObligacionDelFinanciadorIT`).

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
- [x] ~~`encounter` no exige `X-Justificacion-Acceso` y audita con `reason` nulo (DP-03 parcial).~~
  **Cerrado por decisión del usuario el 17/10/2026 (U-03 + U-46 e, DP-33):** la propiedad de la
  Sesión —y del panel de atención grupal— es la relación asistencial de DP-03; `reason` nulo
  documentado, auditoría como está, sin cambio de contrato. Hallazgo alto R2 del threat model cerrado.
- [ ] `caso:create` que nadie evalúa. **Pendiente del usuario**; desde G-5 el efecto coincide con
  la fila de la matriz porque `hc:write` ya no se otorga a quien la matriz le dice "No".
- [x] ~~Threat model y matriz de permisos respaldada por tests~~ → G-5:
  `docs/seguridad/threat-model.md` y `MatrizDePermisosIT`. Los **runbooks** están desde G-13 (#77).
- [x] ~~Un grant podía otorgar una celda "No" de la matriz~~ (hallazgo nuevo de G-5, cerrado).
- [x] SAST, SCA y DAST en CI. **Decidido el 17/10/2026 (U-06, DP-35):** SAST CodeQL y DAST ZAP
  baseline, no bloqueantes (api #119, web #77); Sonar sigue salteándose sin secretos.
  Antes: **SCA hecho** en G-6 (api #83, web #41):
  Dependabot y dependency-check bloqueante con CVSS ≥ 7, respaldo OSV.dev. **Pendiente del
  usuario:** elegir el SAST (CodeQL o Sonar), decidir el DAST y cargar `SONAR_TOKEN`,
  `SONAR_HOST_URL` y `NVD_API_KEY`.
- [x] **Resuelto el 17/10/2026 (U-05, DP-34):** Connector/J baja a **9.6.0** —no hay 9.x corregida
  y el NVD no registra CVE para 9.6.0—, se retira la excepción, y el frontend aplica el
  `overrides` `basic-ftp@^6`. Antes: `mysql-connector-j` 9.7.0 con cuatro CVE sin versión corregida (la más alta 7,7). Excepción en
  `dependency-check-suppressions.xml` **hasta el 09/11/2026**. **Pendiente del usuario:** esperar
  la corrección o bajar a 9.6.0. En el frontend, la cadena de `basic-ftp` (dependencia de
  desarrollo) espera decidir un `overrides` a `basic-ftp@^6` (`appKine-web/docs/calidad.md`).
- [x] ~~a11y pendiente: WCAG 1.4.11 (bordes de control y anillo de foco, 3:1), estados `:hover` y
  `:focus-visible`, foco por teclado~~ → G-7, web #24 (08/10): `contraste-no-textual.spec.ts`
  mide contorno 3:1, anillo de foco con Tab real, hover y orden de tabulación en dos temas.
- [x] ~~Franja del degradé (~4,16:1)~~ → web #66 (13/10): el indicador de desborde pasa al borde
  lateral, axe mide las tablas sin excepción, y `e2e/movil.spec.ts` recorre once pantallas a
  375 px sin scroll horizontal y con objetivos de toque ≥ 24 px.

### 07.08 Rendimiento y observabilidad
- [x] ~~Logging JSON estructurado con correlación.~~ → G-4 (`docs/observabilidad.md`).
- [x] ~~Micrometer + Prometheus, métricas RED.~~ → G-4: histograma de `http.server.requests` con buckets de 300 ms y 800 ms.
- [x] ~~OpenTelemetry.~~ → G-4: Micrometer Tracing + OTLP, exportación opt-in.
- [x] ~~Dashboards y alertas.~~ → **cierre de F8 (13/10):** `ops/observabilidad/alertas-akine-api.yml`
  (siete alertas: p95 y p99 fuera de SLO, 5xx, pool con cola y agotado, heap, `up`) y
  `ops/observabilidad/tablero-akine-api.json` (Grafana). `ObservabilidadIT` falla si usan una
  métrica que el `/actuator/prometheus` real no publica. `docs/observabilidad.md` §6. Montar
  Prometheus y Grafana es del despliegue (G-14).
- [x] ~~Harness de carga (k6 está elegido y nunca se ejecutó).~~ → G-10, api #80 (11/10):
  `perf/k6`, `perf/stack.sh`, `docs/carga.md`.
- [ ] Medir p95/p99 contra los SLO de ADR-0016; LCP del frontend. **Medido, no cumple con
  escrituras.** Lecturas con 500 usuarios: p95 43 ms (cumple). Mezcla completa con 500 usuarios y
  pool 10, después de #91 (11/10): el traba del pool desapareció (errores 30,34 % → 0,00 %, 401
  120 → 0), pero el p95 de lo interactivo es **3.345–3.566 ms** contra 300 ms (`docs/carga.md` §8).
  #97 (13/10) sacó los `REQUIRES_NEW` anidados que quedaban.
  **En curso, en otro agente:** rendimiento por pedido (6.5: dos lecturas de `membership` y tres
  transacciones por pedido), sin PR al 13/10.
  - [x] ~~LCP del frontend~~ → web #47 (11/10) y #69 (12/10): con el perfil móvil 4G de
    laboratorio el Paciente 360 baja de 4,0 s a **1,90 s** y las siete pantallas medidas quedan
    bajo 2,5 s (fuente: descripción de web #69; ADR-0016 habla de p75 de usuarios reales, esto es
    laboratorio).
- [x] ~~Baseline y runbooks.~~ → baseline en `docs/carga.md` §5 y §8; runbooks R1–R6 en
  `docs/operaciones/runbooks.md` (G-13, api #77).

### 07.09 Gate de release
- [x] ~~Dockerfile del backend.~~ → G-3: `Dockerfile` multi-stage (JDK 21 → JRE 21, usuario no root, layered jar, healthcheck sobre `/actuator/health/liveness`), verificado arrancando contra el MySQL de `compose.yaml`; SBOM CycloneDX y job `imagen` en el CI.
- [x] ~~Dockerfile del frontend.~~ → G-3 web #18 (07/10): nginx no root, SBOM y job `imagen`;
  Brotli/gzip estáticos desde web #69.
- [x] ~~Job E2E del CI del frontend contra el backend (hoy comentado).~~ → G-9, web #30 (08/10):
  job `e2e`, check obligatorio de `main` en `appKine-web`.
- [ ] E2E core M01–M27. **Parcial:** 30 specs en `appKine-web/e2e/` (frente a 7 el 01/10), contra
  el backend real: cuenta y contexto, catálogo, servicios, espacios, horarios, colaboradores,
  padrón, prácticas y aranceles, autorizaciones, agenda (buscador, reserva, ciclo, recepción),
  series, prepago, caja, cobros posteriores, presentaciones, notificaciones, día de la sede y
  reportes (web #26, #35, #36, #64, #67, #68). **Falta la vertical clínica** (historia, caso,
  plan, sesión): sus pantallas están en el PR web #4, abierto desde el 06/10 y fuera del alcance
  de este cierre.
- [ ] **QA manual del §6 del `CLAUDE.md`**, que no corre desde 02.02 y está declarado bloqueante.
  La guía está (G-12, api #84 y #95: doce circuitos en `docs/qa/`). **Ejecutarla es del usuario.**
- [x] ~~Gate de cobertura de vuelta a 0,80.~~ → G-11 (api #45, #65), G-6 (#83) y #94: gate global
  0,80 de línea e instrucción más una regla por módulo crítico; BUNDLE medido el 11/10 en **línea
  93,22 % · instrucción 92,77 % · rama 77,91 %** (`docs/calidad.md` §4). Frontend st 91,03 % ·
  rama 82,49 % · fn 87,44 % · ln 92,06 % con piso de 80 % en las cuatro.
  - [ ] Gate de `BRANCH` en JaCoCo. **Pendiente del usuario** (77,91 %; ponerlo por encima rompe
    el build).
- [x] ~~Ensayo de backup/restore, rollback y smoke post-deploy.~~ → G-13, api #77:
  `ops/backup.sh`, `ops/restore.sh`, `ops/simulacro-restore.sh`, `ops/smoke.mjs`,
  `docs/operaciones/{backup-y-restore,rollback,smoke-post-deploy}.md`. El simulacro encontró dos
  defectos, corregidos en #82.
- [x] ~~Release notes y runbooks.~~ → G-13: `docs/operaciones/release-notes.md`, plantilla y
  `ops/release-notes.mjs`; runbooks R1–R6.
- [ ] Aprobación clínica y legal (DP-08). **Pendiente del usuario** (G-14).
- [ ] Deploy y ventana de estabilización (DP-07). **Pendiente del usuario** (G-14).

> **Dependabot abiertos que tocan versiones mayores** (no se mergean en este cierre): api #87
> (Temurin 25, salto de Java) y web #53, #55–#57 (Angular 22), #70 (Vitest coverage 5). Son
> decisión del usuario.

## Para cerrar la fase (= pasar el gate del MVP)

- [x] ~~Reportes accesibles y con dashboards.~~ Ver 07.06.
- [ ] Cero hallazgos altos de 07.07. **No cumple:** queda `caso:create` (U-04). La justificación
  en `encounter` (DP-33), SAST/DAST (DP-35) y Connector/J (DP-34) se cerraron el 17/10/2026.
- [ ] Observabilidad mínima y una corrida de carga medida contra los SLO. **Observabilidad y
  medición, sí; el SLO con escrituras a 500 usuarios, no** (p95 3,3–3,6 s). En curso.
- [ ] Imagen Docker, E2E en CI, QA manual corrido, cobertura ≥ 0,80. **Todo sí salvo el QA
  manual**, del usuario.
- [ ] Deploy y ventana de estabilización (DP-07). **Recién después se abre F9.**

---

# Registro de cierre — 12/10/2026

> Verificado el 12 y el 13/10/2026 contra `main` de los dos repos (backend `8979519`, frontend
> `2cdd3bb`) y contra los PR mergeados, leídos por la API de GitHub. Lo que este cierre agregó va
> en la rama `akine-cierre-F8`.

## Lo que hizo este cierre

| Qué | Dónde | Verificación |
|---|---|---|
| Escenarios diferidos 49, 50, 51 y 53 de 07.06 | `src/test/java/com/akine/reporting/ReportesContraElMotorIT.java` | `./mvnw -o test-compile failsafe:integration-test failsafe:verify -Dit.test=ReportesContraElMotorIT`: **5 tests, 0 fallos** (13/10) |
| Tablero y alertas de Prometheus/Grafana | `ops/observabilidad/alertas-akine-api.yml`, `ops/observabilidad/tablero-akine-api.json`, `docs/observabilidad.md` §6 | `ObservabilidadIT`, caso nuevo `las_alertas_y_el_tablero_leen_metricas_que_existen`: **4 tests, 0 fallos** (13/10) |
| Ficha reverificada ítem por ítem | este archivo | PR de api #45–#100 y de web #4–#72 |

Ni migración ni contrato: el cierre no cambia la API.

## El gate del MVP, ítem por ítem

| Criterio | ¿Cumple? | Número o fuente |
|---|---|---|
| Reportes con `reporte:read` según la matriz | **Sí** | G-1, api #50; `ReporteReadIT` |
| Reportes con dashboard en pantalla | **Sí** | G-8, web #12; E2E `flujo-dia-de-la-sede` y `movil` |
| Reportes que cuentan bien contra el motor | **Sí, salvo 52** | `ReportesContraElMotorIT` 5/5; conciliación = 0 |
| Cero hallazgos altos de 07.07 | **No** | 2 decisiones abiertas (justificación en `encounter`, `caso:create`); Connector/J CVE 7,7 bajo excepción hasta el 09/11 |
| SCA | **Sí** | G-6, api #83 / web #41 |
| SAST y DAST | **No** | sin elegir; Sonar listo sin secretos |
| a11y: contraste AA, 1.4.11, foco, teclado, móvil | **Sí** | web #24 y #66, más las 16/16 auditorías de contraste AA del 20/09 |
| Log estructurado, métricas, trazas | **Sí** | G-4, api #62 |
| Tablero y alertas | **Sí (artefactos)** | este cierre; montarlos es del despliegue |
| Carga medida contra los SLO | **Sí, medida** | G-10, api #80; `docs/carga.md` |
| SLO de latencia con lecturas, 500 usuarios | **Sí** | p95 43 ms · p99 62 ms (`docs/carga.md` §5) |
| SLO de latencia con escrituras, 500 usuarios | **No** | p95 3.345–3.566 ms, 0,00 % de errores (`docs/carga.md` §8, después de #91); en curso |
| Pool que no se traba | **Sí** | #91 y #97; `PoolDeConexionesIT` con pool 2 |
| LCP ≤ 2,5 s | **Sí (laboratorio)** | 360 en 1,90 s, siete pantallas bajo 2,5 s con móvil 4G (web #69) |
| Imagen Docker de los dos repos | **Sí** | G-3, api #29 / web #18; check obligatorio de `main` |
| E2E en el CI contra el backend real | **Sí** | G-9, web #30; 30 specs |
| E2E de la vertical clínica | **No** | pantallas en web #4, abierto |
| Cobertura ≥ 0,80 | **Sí** | línea 93,22 % · instr. 92,77 % (gate 0,80 + por módulo); frontend ≥ 82 % en las cuatro |
| Backup/restore, rollback, smoke, runbooks, release notes | **Sí** | G-13, api #77 |
| QA manual del §6 | **No** | guía lista (api #84, #95); no corrida |
| Aprobación clínica y legal | **No** | DP-08 |
| Deploy y estabilización | **No** | DP-07, G-14 |

**El gate no pasa.** Lo técnico que no depende de nadie está cerrado salvo el SLO de latencia con
escrituras, que está en curso.

## Lo que bloquea el gate y depende del usuario

1. **Ejecutar el QA manual del §6** con la guía de `docs/qa/` (G-12). Bloqueante para deploy desde
   02.02.
2. **Aprobación clínica y legal** (DP-08) y **deploy con ventana de estabilización** (DP-07, G-14).
3. **La justificación de acceso en `encounter`** (header obligatorio, opcional auditado, o declarar
   que la propiedad de la sesión es relación asistencial).
4. **`caso:create`**: cablearlo o absorberlo en `hc:write` en la matriz.
5. **Connector/J 9.7.0**: esperar la corrección o bajar a 9.6.0 **antes del 09/11/2026**, cuando
   vence la excepción y el job `vulnerabilidades` se pone rojo. En el frontend, decidir el
   `overrides` de `basic-ftp` (dependencia de desarrollo, cruza una mayor).
6. **SAST y DAST**: elegir CodeQL o Sonar y decidir el ZAP baseline; cargar `SONAR_TOKEN`,
   `SONAR_HOST_URL` y `NVD_API_KEY` en los secretos de los repos.

## Lo que no bloquea pero también es del usuario

- Gate de `BRANCH` en JaCoCo (77,91 % hoy).
- Confirmar el desvío del export síncrono y acotado de 07.06.
- Angular 22 (web #53, #55–#57) y Java 25 en la imagen (api #87).

## Lo que sigue en curso, fuera de este cierre

- **Rendimiento por pedido** (hallazgo 6.5 de `docs/carga.md`): el p95 con 500 usuarios y
  escrituras. Otro agente, sin PR al 13/10.
- **Pantallas clínicas y sus E2E**: web #4 (D-a, D-b, D-c) y lo que sigue de D-*.
