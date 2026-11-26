# Pedido de cambio de plan — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El centro pide desde Suscripción subir o bajar de plan; la plataforma lo aprueba (se aplica) o lo rechaza.

**Architecture:** Tabla `pedido_de_cambio_de_plan` en `organization`, servicio que reusa `ContratacionService.cambiar` al aprobar, dos controllers (centro bajo `/subscription`, plataforma bajo `/platform`), contador en el panel. En web: tarjeta y panel en Suscripción, bandeja nueva en la consola.

**Tech Stack:** Java 21 · Spring Boot 4.1 · PostgreSQL 18 · Flyway · springdoc · Angular 22 con cliente generado.

**Spec:** `docs/diseno/AKINE-pedido-de-cambio-de-plan.md`

## Global Constraints

- Migración `V50__pedido_de_cambio_de_plan.sql`; contrato `0.196.0` (pom `akine.contract.version` e `info.version`).
- Ramas `akine-pedido-de-plan` en los dos repos (CI cruzado por nombre de rama).
- Rutas del centro bajo `/api/v1/organizations/{orgId}/subscription/pedido-de-cambio` (pasa el filtro de suscripción suspendida).
- Permiso del centro `tenant:update`; plataforma `authorizationGuard.requirePlatformAdmin`.
- Problem types nuevos: `pedido-de-plan-pendiente` (409), `pedido-sin-cambios` (400).
- Sin suites locales completas (preferencia del usuario): sólo lo tocado; el CI corre todo.

## Review Focus

- Dos pedidos simultáneos del mismo centro → uno entra, el otro 409 `pedido-de-plan-pendiente` (no 500).
- Aprobar un pedido ya cancelado o rechazado → 409 `concurrent-modification`.
- Pedido idéntico a la contratación actual → 400 `pedido-sin-cambios`.
- Aprobar con la suscripción en PRUEBA → aplica plan y servicios, sigue en PRUEBA.
- Centro de otra organización pidiendo con el `orgId` ajeno → 404 (regla cross-tenant).

---

### Task 1: Migración y entidad

**Files:**
- Create: `src/main/resources/db/migration/V50__pedido_de_cambio_de_plan.sql`
- Create: `src/main/java/com/akine/organization/domain/PedidoDeCambioDePlan.java`
- Create: `src/main/java/com/akine/organization/domain/port/PedidoDeCambioDePlanRepositoryPort.java`
- Create: `src/main/java/com/akine/organization/infrastructure/PedidoDeCambioDePlanRepository.java`

**Interfaces — Produces:**
- `PedidoDeCambioDePlan(long organizationId, String planCode, Set<Servicio>, Set<Adicional>, String planAnterior, Set<Servicio> serviciosAnteriores, Set<Adicional> adicionalesAnteriores, String comentario, long pedidoPor)`; `aprobar(long actor, Instant)`, `rechazar(String motivo, long actor, Instant)`, `cancelar(long actor, Instant)`; enum `Estado { PENDIENTE, APROBADO, RECHAZADO, CANCELADO }`.
- Port: `saveAndFlush`, `findById`, `findUltimoDe(long orgId)`, `buscar(Estado, String q, Pageable)`, `countByEstado(Estado)`.

- [ ] Tabla como en el spec §Modelo, con `ux_pedido_plan_pendiente` único parcial y CHECKs; servicios/adicionales con un `AttributeConverter` CSV.
- [ ] Commit.

### Task 2: Servicio

**Files:**
- Create: `src/main/java/com/akine/organization/application/PedidoDeCambioDePlanService.java`, `PedidoDeCambioDePlanView.java`
- Create: excepciones `PedidoDePlanPendienteException`, `PedidoSinCambiosException`, `PedidoDeCambioDePlanNotFoundException` en `domain/exception`
- Modify: `AuditEvents.java`, `OrganizationProblemHandler.java`, `platform/spi/problem/ProblemType.java`

**Interfaces — Produces:**
- `PedidoDeCambioDePlanView pedir(long orgId, String planCode, Set<Servicio>, Set<Adicional>, String comentario, long actor)`
- `Optional<PedidoDeCambioDePlanView> ultimo(long orgId)`
- `PedidoDeCambioDePlanView cancelar(long orgId, long id, long version, long actor)`
- `Page<PedidoDeCambioDePlanView> listar(Estado, String q, Pageable)`, `long pendientes()`
- `PedidoDeCambioDePlanView aprobar(long id, long version, long actor)` → `contratacionService.cambiar(..., contratar=false, versionActual, actor)`
- `PedidoDeCambioDePlanView rechazar(long id, String motivo, long version, long actor)`

- [ ] Choque con el índice parcial → relectura en `REQUIRES_NEW`/`READ_COMMITTED` y `PedidoDePlanPendienteException`.
- [ ] Commit.

### Task 3: API, panel y contrato

**Files:**
- Create: `api/PedidoDeCambioDePlanController.java` y DTOs en `api/dto/` (`PedidoDeCambioDePlanRequest`, `...Response`, `...PageResponse`, `ResolverPedidoRequest`, `RechazarPedidoRequest`)
- Modify: `PanelDePlataforma` (+ `cambiosDePlanPendientes`), `PanelDePlataformaRepository`, `PanelDePlataformaResponse`
- Modify: `pom.xml` y `openapi/akine-api.yaml` → 0.196.0
- Test: `src/test/java/com/akine/organization/PedidoDeCambioDePlanIT.java`

- [ ] IT: pedir → listar en plataforma → aprobar cambia la contratación; rechazar exige motivo; dos pedidos → 409; idéntico → 400; cross-tenant → 404.
- [ ] `./mvnw verify -Dakine.contract.update=true` (sólo regenerar contrato + el IT nuevo).
- [ ] Commit.

### Task 4: Web — Suscripción

**Files:** `appKine-web` `features/organization/pages/subscription/*`, cliente regenerado 0.196.0.

- [ ] Tarjeta «Cambio de plan» con estado del último pedido; panel lateral con planes (`listPlans`), servicios, IA, comentario y resumen; cancelar.
- [ ] Commit.

### Task 5: Web — consola de plataforma

**Files:** `features/platform/pages/cambios-de-plan/*`, `rutas-de-plataforma.ts`, `platform.routes.ts`, `secciones.ts`, `contadores-de-plataforma.store.ts`.

- [ ] Bandeja con buscador y filtro de estado; panel con actual → pedido, Aprobar y Rechazar con motivo; contador `cambios-pendientes`.
- [ ] Commit.
