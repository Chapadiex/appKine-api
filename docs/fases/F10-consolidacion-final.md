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
| 09.01 Reportes para clases y productos | IMPLEMENTADA en el backend (PR #145; RF-M23-008 y los ingresos de RF-M23-009 en 09.01b, contrato 0.123.0; la rentabilidad espera **U-50**) | 10 | `reporting` crece por contribuyentes (`ReporteContributor`): turnos, sesiones, casos, economía y financiadores. `ReporteCode` ya se está tocando en la rama | El contribuyente de `activity`: clases, ocupación, asistencia, pases vendidos, consumidos, vencidos y anulados, abonos renovados |
| 09.02 Hardening de seguridad, privacidad y accesibilidad | EN CURSO: **superficie de F9 hecha** (rama `akine-09.02-hardening-f9`, contrato 0.120.0; registro abajo) | 60 | G-5 (threat model, #78), G-7 (a11y, web #24), aislamiento de tenant por IT en los módulos del MVP, pre-QA de 146 pasos con D1–D7 corregidos (#126, #131, #133) | ~~Aislamiento de tenant de la superficie de F9~~ (09.02, `ActividadAislamientoIT`); U-46; SAST/DAST (**U-06**, propuesta abierta en #119); `caso:create` y la justificación en `encounter` (U-03, U-04) |
| 09.03 Concurrencia, rendimiento y observabilidad | EN CURSO: **superficie de F9 hecha** (rama `akine-09.03-concurrencia-f9`, sin cambio de contrato ni migración) | 70 | Lo anterior más: las seis carreras de F9 que faltaban con IT (`InscripcionContraCancelacionIT`, `CarrerasDePasesIT`) y **dos defectos corregidos** (inscripción en la cola de una clase cancelada; venta repetida con la misma clave → 409); métricas, traza y log de los jobs de pases y abonos (`docs/observabilidad.md` §7, alerta `AkineJobConFallos`); N+1 de los seis listados de F9 medido, ninguno crece; carga de F9 medida (`docs/carga.md` §12) | Escenarios diferidos de concurrencia de 08.01–08.03 (39, 46, 55, 57); **el SLO no se cumple con la asistencia en lote (U-49) ni con la venta de pases (U-48)**; el SLO de escrituras a 500 usuarios (`docs/carga.md` §10) |
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

- [x] ~~Contribuyente de `activity` al reporting (09.01).~~ AKINE-09.01 (`V104`): reportes `CLASES` (RF-M23-007, secciones `clases` y `participacion`) y `PASES` (RF-M23-010, seccion `pases`). RF-M23-008 y RF-M23-009 siguen sin cimiento; la atencion grupal ya la cuenta `encounter` como sesion y no se duplica.
- [x] ~~RF-M23-008 y la parte de RF-M23-009 con fuente (09.01b).~~ AKINE-09.01b (`V106`, contrato 0.123.0, [diseño](../diseno/AKINE-09.01b-continuidad-y-rentabilidad.md)): reporte `CONTINUIDAD` (alta clinica = proxima conducta `ALTA`; transito a cada naturaleza por sesion cerrada o asistencia, CA-M23-008-06) y reporte `SERVICIOS` (devengado y cobrado imputado por oferta y concepto —prestacion, clase, pack, abono—, anticipos sin imputar, creditos consumidos aparte y la ocupacion de `clases`, CA-M23-009-06). **Queda afuera, sin dato:** la **rentabilidad** (ningun egreso se imputa a un servicio; honorarios por clase en U-41), la **caja por servicio** (no hay regla para repartir un cobro entre ofertas) y la continuidad para el `PROFESIONAL` (se omite). Las tres en **U-50**.
- [ ] Gates de F8 repetidos con M28–M29 adentro (09.02, 09.03).
- [ ] QA manual corrido con los circuitos de F9 incluidos (los doce circuitos de `docs/qa/` son del MVP:
  no hay uno de clases, pases y abonos).
- [ ] Release completo M01–M29: tag del RC, notas finales, aprobación.
- [x] ~~Tabla de trazabilidad del plan actualizada~~ (17/10/2026): las 56 etapas del §23 llevan estado real y los PR que las cerraron, y el plan tiene el registro "F9 y F10 (17/10/2026)" al final.

## Registro — AKINE-09.02, hardening de la superficie de F9 (16/10/2026)

Revisión adversarial de `activity` con cuatro lentes en paralelo (aislamiento, permisos, PHI y
entradas), cada hallazgo intentado refutar antes de aceptarlo, y los de aislamiento reproducidos
por HTTP contra MySQL antes de corregirlos. Contrato **0.120.0** (sólo restricciones de entrada y
un booleano que pasa a opcional; ninguna operación nueva). Sin migración.

| # | Sev. | Hallazgo | Arreglo |
|---|---|---|---|
| 1 | Alta | **Confirmar una inscripción de otra sede de la organización pidiéndola por la propia** daba 200 y la confirmaba (reproducido por `ActividadAislamientoIT`); cancelar se salvaba por el orden del armado de la respuesta | `InscripcionService.exigirInscripcionDeLaSede` |
| 2 | Alta | La auditoría de una derivación (HC, caso, plan, persona, motivo) y de los tratamientos se leía sin `auditoria:read-clinica` | `DERIVACION_CLINICA_` y `TRATAMIENTO_` en `VocabularioClinicoDeAuditoria` |
| 3 | Media | `activity` nunca escribía `SUPPORT_ACCESS_USED`: el `PLATFORM_ADMIN` con soporte leía participantes sin rastro | `SoporteEnActividad` en los cinco servicios que evalúan permisos |
| 4 | Media | La reversión de una derivación escribía antes de comprobar que era de esa participación; el 403/409 previo delataba ids | `ReversionDeDerivacion` acotada a la participación; `clinical` responde 404 antes del acceso |
| 5 | Media | El lote de atención clínica sin `sesion:register` respondía 200 con N ítems "error no previsto" (y un `log.error` por ítem) | Exige `sesion:register` y `hc:write` antes del primer ítem: 403 |
| 6 | Media | `{"items":[null]}` en los lotes → NPE → 500 | `List<@NotNull @Valid ...>` |
| 7 | Media | `page` enorme en `GET .../pases/{p}/movimientos` → 500 | Tope de página |
| 8 | Media | Fechas fuera de rango: 500 (`DateTimeException`) o 409 "dato ya existente" (año > 9999 en MySQL) | `FechasAdmitidas` en clases, productos y ventas; `@Max(3660)` en `vigenciaDias`; `DateTimeException` → 400 global |
| 9 | Media-baja | `GET .../clases` sin tope de ventana (todo `1970..9999` sin paginar) y ventana invertida = 200 vacío | 400 con ventana invertida o > 62 días, como la agenda |
| 10 | Baja | `PUT .../productos/{p}` sin `limpiarVigenciaHasta` era 400 de cuerpo ilegible (`boolean` primitivo, Jackson 3) aunque "los ausentes se conservan" | `Boolean` |
| 11 | Baja | El ajuste de pase con `cantidad` cerca de `Integer.MAX_VALUE` desbordaba el mensaje | `@Min(-10000) @Max(10000)` |
| 12 | Baja | Cerrar una clase escribe asistencias y cancela la espera con sólo `clase:manage` (latente) | Exige también `asistencia:manage` e `inscripcion:manage` |
| 13 | Baja | `SESION_GRUPAL_CONSULTADA` no decía de qué pacientes | Lista las historias clínicas expuestas |

Tests: `ActividadAislamientoIT` (nuevo: cuatro atacantes contra todos los endpoints de `activity`,
y la lectura por soporte contra `audit_event`), `MatrizDePermisosIT` (familias F9 con los cinco
roles y cuatro grants F9 rechazados), `VocabularioClinicoDeAuditoriaTest`.

Verificado sin cambios: la lista blanca de `SanitizedPayload` cubre todas las claves de los avisos
de F9; los logs de `activity` sólo llevan ids; todas las mutaciones de F9 se auditan en la
transacción del negocio; los workers operan cada candidato con su organización y sede.

**U-46 decidida el 17/10/2026 (DP-28)**: (a), (b) y (f) quedan; (c) el `PROFESIONAL` no ve `paseId`/`saldo`; (d) el título de la clase no viaja en el correo; (e) sigue abierta junto con U-03. Decididas también U-48 (DP-29, lock solo en abonos), U-49 (DP-30: (a) hecha, (c) solo si el entorno lo exige, (b) no) y, fuera del RC, U-50 (DP-31, rentabilidad y caja por servicio). Queda para el usuario: U-03 y, con ella, U-46 (e). Fuera de F9 y anotado como R13 del
threat model: `scheduling` tampoco escribe `SUPPORT_ACCESS_USED` para `turno:read`.
