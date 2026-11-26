# Consentimiento del centro para usar IA — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Pasos con `- [ ]`.

**Goal:** sin consentimiento vigente del centro ninguna función de IA corre; el ORG_ADMIN lo acepta
desde Organización → Inteligencia artificial y queda registrado.

**Architecture:** tabla `organization_ia_consentimiento` del módulo `organization`; la condición se
suma en `FuncionesIaService.prendida()`, el único punto que consultan todas las funciones, y en el
dictado por un método nuevo de `FuncionesIaDelCentro`. Dos operaciones nuevas en
`FuncionesIaController`. En la web, cartel en la página de IA.

**Tech Stack:** Spring Boot 4 / PostgreSQL / Flyway; Angular 21 zoneless con cliente generado.

**Spec:** `docs/diseno/AKINE-consentimiento-ia.md`

## Global Constraints

- Migración **V49**, contrato **0.195.0** (aditivo).
- Propiedad `akine.ia.consentimiento.version`, valor `${AKINE_IA_CONSENTIMIENTO_VERSION:2026-11-26}`;
  en `src/test/resources/config/application.yml` vale `""` (no se exige en tests).
- Texto en `src/main/resources/ia/consentimiento.md`, borrador marcado "a revisar por un abogado".
- Aceptar exige `tenant:update`; leer, ser miembro (igual que `GET /ia`).
- Evento de auditoría `ORGANIZATION_IA_CONSENTIMIENTO_ACEPTADO`.
- `organization` no importa `identity`: el nombre sale de `platform.spi.identity.AccountIdentityDirectory`.
- Preferencia del proyecto: código primero, tests pocos y después; no correr suites completas en local.

## Review Focus

- Dos clics simultáneos en "Acepto": no duplica ni da 500 (unique `(organization_id, version)` +
  `ON CONFLICT DO NOTHING`).
- El texto cambió mientras el admin lo leía (versión enviada ≠ vigente): 409 `concurrent-modification`.
- Versión vacía en el despliegue: todo sigue como hoy (nada bloqueado, cartel oculto).
- Centro que aceptó la versión vieja y se sube la versión: vuelve a quedar bloqueado.
- Un no-admin entra a la página de IA sin consentimiento: ve el cartel sin botón.

---

### Task 1: Backend — dato, regla y bloqueo

**Files:**
- Create: `src/main/resources/db/migration/V49__consentimiento_ia.sql`
- Create: `src/main/resources/ia/consentimiento.md`
- Create: `organization/domain/port/ConsentimientoIaRepositoryPort.java`,
  `organization/infrastructure/ConsentimientoIaRepository.java` (JdbcTemplate o JPA, lo que use el módulo)
- Create: `organization/application/ConsentimientoIa.java` (record: `version`, `accountId`, `nombre`, `aceptadoAt`)
- Create: `organization/application/TextoDelConsentimientoIa.java` (lee versión de la propiedad y texto del recurso)
- Modify: `FuncionesIaService`, `FuncionesIaView`, `AuditEvents`, `spi/FuncionesIaDelCentro`,
  `assistant/application/DictadoService`, `application.yml`, `src/test/resources/config/application.yml`
- Test: `FuncionesIaServiceTest` (casos nuevos)

**Interfaces:**
- Produces: `FuncionesIaDelCentro.consentimientoVigente(long organizationId): boolean`;
  `FuncionesIaService.aceptarConsentimiento(long orgId, String version, long actorAccountId): FuncionesIaView`;
  `FuncionesIaView` suma `Consentimiento consentimiento` con
  `record Consentimiento(boolean exigido, String versionVigente, boolean aceptado, String aceptadoPor, Instant aceptadoAt)`;
  `TextoDelConsentimientoIa.version(): String`, `.texto(): String`.

- [ ] **Step 1:** V49: tabla con `id`, `organization_id` (FK), `version varchar(32)`, `account_id bigint`,
  `nombre varchar(200)`, `aceptado_at timestamptz`, `uk_org_ia_consentimiento (organization_id, version)`,
  `COMMENT ON TABLE` (sólo inserciones, nunca se borra).
- [ ] **Step 2:** puerto: `Optional<ConsentimientoIa> buscar(long orgId, String version)` e
  `insertarSiNoExiste(long orgId, String version, long accountId, String nombre, Instant at)`
  (`INSERT ... ON CONFLICT (organization_id, version) DO NOTHING`).
- [ ] **Step 3:** `consentimientoVigente`: versión vacía → `true`; si no, existe fila de esa versión.
  `prendida()` devuelve `false` si no hay consentimiento (antes de mirar `organization_ia`).
  `vista()` arma `Consentimiento` y `activa` también lo exige.
- [ ] **Step 4:** `aceptarConsentimiento`: `exigirVigente`; versión vacía o distinta de la enviada →
  `OptimisticLockingFailureException`; nombre por `AccountIdentityDirectory.identidadesDe`; inserta;
  audita sólo si insertó (detalle `version`).
- [ ] **Step 5:** `DictadoService`: sin `consentimientoVigente` responde como dictado apagado (mismo
  error que hoy con `akine.dictado.enabled=false`).
- [ ] **Step 6:** tests en `FuncionesIaServiceTest`: `sinConsentimientoNadaPrendida`,
  `versionVaciaNoExigeConsentimiento`, `aceptarDosVecesNoAuditaDosVeces`,
  `aceptarVersionViejaEsConflicto`, `consentimientoDeVersionAnteriorNoVale`.
  Run: `./mvnw -o test -Dtest="FuncionesIaServiceTest" -Djacoco.skip=true` → PASS.
- [ ] **Step 7:** compilar todo (`./mvnw -o test-compile -q`), commit.

### Task 2: Backend — API, contrato y manual

**Files:**
- Modify: `organization/api/FuncionesIaController.java`, `FuncionesIaResponse` (+ `ConsentimientoIaResponse`)
- Create: `organization/api/dto/AceptarConsentimientoIaRequest.java` (`@NotBlank String version`),
  `TextoConsentimientoIaResponse` (`version`, `texto`)
- Modify: `openapi/akine-api.yaml` (regenerado), `pom.xml`/`application.yml` versión del contrato 0.195.0
- Modify: ficha del manual de la pantalla de IA en `src/main/resources/manual/`
- Test: `ConsentimientoIaIT` (contra PostgreSQL)

**Interfaces:**
- Consumes: Task 1.
- Produces: `GET /api/v1/organizations/{orgId}/ia/consentimiento` (`getConsentimientoIa`),
  `POST .../ia/consentimiento` (`aceptarConsentimientoIa`, 200 con `FuncionesIaResponse`),
  `FuncionesIaResponse.consentimiento`.

- [ ] **Step 1:** endpoints con `@Operation` y respuestas 200/400/403/404/409 como el `PUT`.
- [ ] **Step 2:** `ConsentimientoIaIT` con `@TestPropertySource(properties="akine.ia.consentimiento.version=2026-11-26")`:
  sin aceptar `GET /ia` da todas inactivas; `POST` con la versión → activas y `aceptadoPor` con nombre;
  dos `POST` en paralelo → una fila; `POST` con otra versión → 409.
  Run: `./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ConsentimientoIaIT -Djacoco.skip=true` → PASS.
- [ ] **Step 3:** regenerar contrato: `-Dit.test=OpenApiContractIT -Dakine.contract.update=true`, subir a 0.195.0.
- [ ] **Step 4:** ficha del manual: párrafo del consentimiento; `CargadorDelManualTest` → PASS. Commit.

### Task 3: Web — cartel y estado

**Files:** (en `wt-WEB-CONSENT`)
- Modify: `src/app/api/generated/` (regenerado), `environment*.ts` (`contractVersion` 0.195.0)
- Modify: `features/organization/pages/inteligencia-artificial/inteligencia-artificial-page.{ts,html,css,spec.ts}`

**Interfaces:**
- Consumes: `getConsentimientoIa`, `aceptarConsentimientoIa`, `FuncionesIaResponse.consentimiento`.

- [ ] **Step 1:** `npm run api:generate` y `api:check` alineado.
- [ ] **Step 2:** página: si `consentimiento.exigido && !aceptado`, pide el texto y muestra una sección
  "Consentimiento para usar inteligencia artificial" con el texto (párrafos, sin `innerHTML`) y, si el
  usuario puede editar, el botón "Acepto en nombre del centro"; sin permiso, "Lo tiene que aceptar el
  administrador del centro". Interruptores y acciones del motor deshabilitados. Con consentimiento: línea
  "Consentimiento aceptado por {nombre} el {fecha} (versión {v})". Al aceptar, refresca
  `FuncionesIaStore` para que el menú muestre las funciones. 409 → relee el texto con aviso.
- [ ] **Step 3:** spec: `muestraElCartelSinConsentimiento`, `aceptarLlamaConLaVersionYRefresca`,
  `sinPermisoNoHayBoton`. Run:
  `npx ng test --watch=false --no-coverage --include "src/app/features/organization/pages/inteligencia-artificial/**/*.spec.ts"` → PASS.
- [ ] **Step 4:** lint de los archivos tocados; commit.

### Task 4: Verificar y abrir PRs

- [ ] Levantar backend del worktree (base nueva `akine_consent`, versión prendida) y web; probar como
  admin: cartel → aceptar → funciones activas y fila en la tabla (consulta SQL). Captura en claro y oscuro, 375 px.
- [ ] Push de las dos ramas `akine-consentimiento-ia`, PR en cada repo, Auto-fix.
