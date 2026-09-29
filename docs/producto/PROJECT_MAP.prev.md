# AKINE — Mapa del proyecto

> Generado: 2026-08-26 · comando `/project-map` · verificado contra filesystem + git, no contra
> documentos (CLAUDE.md, AGENT.md y el plan son narrativa; `git log`, `git status`, el árbol de
> directorios, las migraciones, el YAML y los manifiestos de paquete no lo son).
> Ramas: `appKine-api` → `akine-01.02-identidad` · `appKine-web` → `akine-01.02-identidad`
> (mismo branch en ambos repos — cumple la regla de coordinación §6 del `CLAUDE.md` de la raíz).
> Copia previa (2026-08-23, obsoleta) en `docs/PROJECT_MAP.prev.md`.

## 1. Estado por etapa

| Etapa | Estado | Evidencia |
|---|---|---|
| 00.01 Baselines técnicos | ✅ cerrada | `5ea876d` (api) / `9dcde40` (web) |
| 00.02 Arquitectura comprobada + gates | ✅ cerrada | `cec1a81` (api) / `8a00f19` (web) |
| 00.03 ADRs + matriz de permisos | ✅ cerrada | `3906533` (api) — 22 ADRs en `appKine-api/docs/adr/` hoy |
| 01.01 Tenancy, organizaciones, suscripción | ✅ cerrada — verificación parcial | `fa3b76f` (api) / `9c3697c` (web) · 11 escenarios diferidos en `docs/tests-diferidos.md`, 7 cerrados, 1 parcial, 1 en rojo entonces (corregido antes de cerrar 01.02), 2 reasignados a 01.03 |
| 01.02 Identidad, autenticación, recuperación, outbox | ✅ cerrada | `9b4398a`→... (api) / `4792a6c` (web) |
| 01.03 Memberships, roles, permisos, auditoría | ✅ cerrada | `454646c` (api, commit compartido con 02.01) / `755bb66` (web, idem) |
| 02.01 Consultorios, onboarding, contexto operativo | ✅ cerrada — CA-M03-002 parcialmente cubierto | mismo commit que 01.03 · primer box → 02.02, horario general → F5 (`docs/tests-diferidos.md`) |
| 02.02 Espacios, boxes y capacidad física (M04) | ✅ cerrada — **sin registro de cierre en el plan** | `80fb846` (api) / `294006a` (web) · hueco de documentación, confirmado en `AKINE_IMPLEMENTATION_PLAN.md:6368` y `:6382` |
| 02.03 Ciclo de vida de colaboradores | ✅ cerrada | `a774508` (api) / `4813ba6` (web) |
| 02.04 Disponibilidad semanal, excepciones y feriados (M05) | ✅ cerrada — **hoy** | `f6cccbd`…`094edc6` (api, último commit del repo) / `79f07bc`…`e743496` (web, último commit del repo) · registro de cierre en `AKINE_IMPLEMENTATION_PLAN.md:6524` |
| 02.05 Especialidades, prácticas y nomencladores | ✅ cerrada | `6808ef9` (api) / `4d06c42` (web) — cerrada antes de 02.03/02.04 por diseño (depende solo de 01.03) |
| **02.06 Servicio global y oferta por consultorio** | ⬜ pendiente — próximo paso declarado | `AKINE_IMPLEMENTATION_PLAN.md:1410` · `appKine-api/CLAUDE.md` §7 lo nombra "Próximo paso" |
| 02.07 Configuración operativa de ofertas y habilitaciones | ⬜ pendiente | `AKINE_IMPLEMENTATION_PLAN.md:1490` |
| 03.01 Persona, PerfilPaciente, búsqueda y deduplicación | ⬜ pendiente | `AKINE_IMPLEMENTATION_PLAN.md:1570` |

`git -C appKine-api log --oneline | wc -l` → **43 commits**, último `094edc6`.
`git -C appKine-web log --oneline | wc -l` → **19 commits**, último `e743496`.
Plan completo: 58 etapas (00.01 → 09.04) en `docs/AKINE_IMPLEMENTATION_PLAN.md`
(`grep -c "^## Etapa AKINE-"` → ver sección 8 para el conteo de registros de cierre).

## 2. Backend — `appKine-api`

### Módulos (`find src/main/java -name "*.java" | wc -l` → 528)

| Módulo | Archivos `.java` (`find src/main/java/com/akine/<módulo> -name "*.java" \| wc -l`) |
|---|---|
| `organization` | 196 |
| `resource` | 148 |
| `identity` | 107 |
| `platform` | 44 |
| `notification` | 32 |

(Suma 527; el archivo restante es `AkineApiApplication.java` en la raíz del paquete.)

### Migraciones (`ls src/main/resources/db/migration` → V1–V23, 23 archivos)

| Rango | Módulo / tema |
|---|---|
| V1 | Baseline técnico |
| V2–V5 | M01 — organización, plan, suscripción, consultorio/membership mínimos, auditoría |
| V6–V9 | M02/M26 — cuenta, tokens, onboarding, outbox de notificaciones |
| V10–V15 | M01 — memberships múltiples, estado, grants, support access, auditoría, seed de platform admin |
| V16–V18 | M03 — consultorio: expandir → backfill timezone → contraer (patrón expand-migrate-contract, ADR-0007) |
| V19 | M04 — espacio |
| V20 | M06 — catálogos clínicos |
| V21 | M05 — invitación de colaborador |
| V22 | M05 — feriado global |
| V23 | M05 — disponibilidad profesional |

### Contrato (`openapi/akine-api.yaml`)

- `version:` → **0.11.0** (línea 11 del YAML, leído del archivo, no de ninguna prosa).
- Paths (`grep -cE "^  /" openapi/akine-api.yaml`) → **62**.
- `securityScheme` → **ninguno** (`grep -n "securityScheme" openapi/akine-api.yaml` sin resultados).
  Preexistente; el frontend se autentica por interceptor, no por lo que declara el contrato.

### Endpoints vs OpenAPI — drift

21 controllers (`find src/main/java -name "*Controller.java" | wc -l`). Se verificó cada base
(`@RequestMapping` de clase) contra los `@GetMapping/@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping`
de método, incluyendo las variantes sin paréntesis (`AuditEventController`, `PlanController`,
`VersionController` usan `@GetMapping` a secas sobre la base). **Sin drift detectado** en el
muestreo — cada path reconstruido desde el código aparece en el YAML, incluidos los casos donde
dos controllers comparten el mismo path con métodos distintos (p. ej. `GET
/api/v1/organizations/{orgId}/consultorios` lo resuelve `OrganizationController`, el `POST` al
mismo path lo resuelve `ConsultorioController`).

| Controller | Base | Operaciones |
|---|---|---|
| `AccountAdminController` | `/api/v1/accounts` | POST `{id}/block`, `{id}/unblock`, `{id}/deactivate` |
| `AccountRegistrationController` | `/api/v1/auth` | POST `register`, `activate`, `activation/resend` |
| `AuthSessionController` | `/api/v1/auth` | POST `login`, `refresh`, `context`, `logout`; DELETE `sessions` |
| `ColaboradorInvitacionController` | `/api/v1/organizations/{orgId}/colaborador-invitaciones` | POST (base), `{id}/resend`, `{id}/cancel` |
| `InvitacionPublicaController` | `/api/v1/auth/invitations` | POST `preview`, `accept`, `decline` |
| `MembershipProvisioningController` | `/api/v1/memberships` | POST (base) |
| `PasswordResetController` | `/api/v1/auth` | POST `password-reset`, `password-reset/confirm` |
| `AuditEventController` | `/api/v1/organizations/{orgId}/audit-events` | GET (base) |
| `ConsultorioController` | `/api/v1/organizations/{orgId}/consultorios` | POST (base), GET/PATCH `{id}`, POST `{id}/deactivate` |
| `MeContextController` | `/api/v1/me` | GET `contexts`, `permissions` |
| `MembershipController` | `/api/v1/organizations/{orgId}/memberships` | GET (base), GET `{id}`, `{id}/grants`, `{id}/desvinculacion-impacto`; PATCH `{id}`; POST `{id}/suspend`, `{id}/reactivate`, `{id}/revoke`, `{id}/grants`; DELETE `{id}/grants/{code}` |
| `OrganizationController` | `/api/v1/organizations` | POST (base), GET/PATCH `{orgId}`, GET `{orgId}/consultorios` |
| `PlanController` | `/api/v1/plans` | GET (base) |
| `PlatformRoleController` | `/api/v1/platform/roles` | POST (base), DELETE `{id}` |
| `PlatformSupportAccessController` | `/api/v1/platform` | GET `organizations/{orgId}/support-access`, DELETE `support-access/{id}` |
| `SubscriptionController` | `/api/v1/organizations/{orgId}/subscription` | POST/GET `transitions`, POST `plan-changes` |
| `VersionController` | `/api/v1/version` | GET (base) |
| `CalendarioController` | `/api/v1/consultorios/{id}/calendario` | PUT (base) |
| `CatalogoController` | `/api/v1/catalogos` | POST/GET `{tipo}`, GET/PATCH `{tipo}/{id}`, GET `nomencladores/{id}/items` |
| `CatalogoSolicitudController` | `/api/v1/catalogo-solicitudes` | POST (base), POST `{id}/resolve` |
| `DisponibilidadController` | `/api/v1/consultorios/{cId}/profesionales/{mId}/disponibilidad` | POST (base), PUT/DELETE `{bloqueId}`, GET `efectiva` |
| `EspacioController` | `/api/v1/organizations/{orgId}/consultorios/{cId}/espacios` | POST (base), GET/PATCH `{id}`, POST `{id}/deactivate`, GET `availability` |
| `ExcepcionController` | `/api/v1/consultorios/{id}/excepciones` | POST (base), DELETE `{id}` |

### Tests (`find src/test -name "*.java" | wc -l` → 148 clases)

- `grep -rhoE "@(Test|ParameterizedTest|RepeatedTest)\b" src/test | wc -l` → **1343**
  (1279 `@Test` + 56 `@ParameterizedTest` + 8 `@RepeatedTest`, conteo de **anotaciones**, no de
  casos ejecutados).
- Integración (`*IT.java`, `find src/test -name "*IT.java" | wc -l`) → **21 clases**, 95
  anotaciones de test.
- `appKine-api/CLAUDE.md` declara **1527 unitarias + 118 de integración** desde los reportes de
  `target/` (2026-08-26). La diferencia frente al conteo estático (1343) es consistente con
  `@ParameterizedTest`/`@RepeatedTest` expandiéndose en más de un caso por anotación al
  ejecutarse — **no se pudo verificar el número exacto sin correr la suite**, lo que el comando
  `/project-map` prohíbe. Tratarlo como **no medido de forma estática**; el número del CLAUDE.md
  es de ejecución real, no de conteo de archivo.
- Diferido confirmado: `IdempotenciaYUniquesIT.java:132` tiene `@Disabled` (escenario 7b,
  pendiente de `request_hash` en `onboarding_registro`).
- Todo commiteado — `git status --short` vacío, sin archivos sin trackear.

### Checklist pendiente de `CLAUDE.md` (§7, "Próximo paso — AKINE-02.06")

- [ ] Protección de rama en `main`
- [ ] Job de SonarQube en CI (listo, comentado)
- [ ] Observabilidad: logging JSON, Prometheus, OpenTelemetry
- [ ] Reglas `PACKAGE` de cobertura al 90 % (`PENDIENTE(F1)` en `pom.xml:286`, confirmado en el archivo)
- [ ] Gate de `BRANCH` en JaCoCo
- [ ] `PENDIENTE(F1)` de `.claude/qa-config.md`
- [ ] Escenario diferido 7b (`request_hash`)
- [ ] Registro de cierre de AKINE-02.02

`AGENT.md` no tiene items `- [ ]` pendientes (`grep` sin resultados).

## 3. Frontend — `appKine-web`

### Estructura (`src/app`)

```
app/
├── api/            (cliente generado — no se edita)
├── core/           guards, interceptors, models, services, testing
├── shared/         components, directives, pages, utils
└── features/       auth, catalog, organization, platform, resource
```

Archivos `.ts` no-spec fuera del cliente generado (`find src/app -name "*.ts" -not -name
"*.spec.ts" -not -path "*/api/generated/*" | wc -l`) → **85**.

### Rutas (`src/app/app.routes.ts`, por `path:`)

`''` (layout) · `auth` (sin guards) · `seleccionar-contexto` · `organizacion` · `espacios` ·
`SEGMENTO_HORARIOS` (constante, no literal) · `catalogo` · `sin-permiso` · `**` (comodín 404).

### Contrato consumido

- `src/environments/environment.ts` → `contractVersion: '0.11.0'`
- `src/environments/environment.prod.ts` → `contractVersion: '0.11.0'`
- Coincide con el `version:` del YAML del backend (0.11.0) → **sin drift**.
- `scripts/check-contract-version.mjs` es quien lo garantiza en CI: compara `info.version` del
  YAML del repo hermano contra `contractVersion` de `environment.ts` y falla si no coinciden.

### Tests

- Specs (`find src -name "*.spec.ts" | wc -l`) → **66**.
- `it(` (`grep -rhoE "\bit\(" src --include=*.spec.ts | wc -l`) → **548**.
- E2E (`find e2e -name "*.spec.ts"`) → **4 archivos**: `auth-flujo`, `contexto-sin-fuga`,
  `errores-sin-internals`, `smoke`. `test(` (`grep -rhoE "\btest\(" e2e --include=*.spec.ts | wc
  -l`) → **24**.
- Todo commiteado — `git status --short` vacío.

> `appKine-api/CLAUDE.md` (estado vigente del workspace) declaraba "382 unitarias + 39 E2E".
> Ambos números están desactualizados contra el árbol real: **548** `it()` y **24** `test()`
> E2E medidos hoy.

### ADRs (`ls docs/adr | grep -vi readme | wc -l` → 5)

`0001` token en memoria/refresh en cookie · `0002` cliente API generado · `0003` proxy de dev ·
`0004` estructura core/shared/features · `0005` estados obligatorios y accesibilidad.

## 4. Trabajo sin commitear

**Ninguno.** `git status --short` y `git ls-files --others --exclude-standard` vuelven vacíos en
ambos repos. Los dos árboles de trabajo están limpios.

## 5. Runtime (no bloqueante, medido al momento de generar este mapa)

- `docker ps` → `akine-mysql` Up 4 días (healthy), `akine-mailpit` Up 35 horas (healthy).
- `curl localhost:8080/actuator/health` → sin respuesta (código `000`): el backend Spring Boot
  **no está corriendo** en este momento.
- `curl -o /dev/null -w "%{http_code}" localhost:4200` → **200**: el dev server de Angular sí
  está levantado.

## 6. Discrepancias — documentos vs. repositorio observado

| Documento | Afirma | Se observó | Impacto |
|---|---|---|---|
| `appKine-web/CLAUDE.md` §7 | "Rama `akine-01.02-identidad`, 3 commits, **64 rutas sin commitear**"; 01.02 "construida, sin commitear" | 19 commits, último `e743496`, **árbol limpio**, 01.02–02.05 cerradas y commiteadas | **Es, casi con certeza, el origen del error de la versión anterior del mapa** (fechada 23/08), que reportaba exactamente "64 archivos sin commitear" como si fuera el estado actual. El documento nunca se actualizó después del cierre de 01.02 y describe un instante congelado de hace cuatro etapas |
| `appKine-api/CLAUDE.md` §7 "Estado vigente (26/08/2026)" | "**41 commits**, último `be39da4`" | **43 commits**, último `094edc6` | El documento quedó desactualizado por 2 commits — probablemente escrito antes de `0a5301f` (registro de cierre de 02.04) y `094edc6` (fix posterior), ambos ya en el repo |
| Workspace `CLAUDE.md` (raíz) | "appKine-api: ... **16 — último `a774508`**" / "appKine-web: ... **11 — último `4813ba6`**"; migraciones V1–V21; contrato 0.10.0; 21 ADRs; "1379 unitarias + 94 de integración"; Próximo paso AKINE-02.04 | api: 43 commits / `094edc6`; web: 19 commits / `e743496`; migraciones V1–V23; contrato 0.11.0; 22 ADRs backend, 5 frontend; próximo paso ya es **AKINE-02.06** (02.04 cerrada hoy) | Esperado y señalado por la consigna: el workspace `CLAUDE.md` quedó congelado en el cierre de 02.03/antes de 02.04, cuatro etapas atrás de lo real. No se usó como fuente — solo confirma su propia obsolescencia |
| Workspace `CLAUDE.md` (raíz) | "seis ADRs de frontend" | **5 ADRs** en `appKine-web/docs/adr/` (`0001`–`0005`, más `README.md`) | Discrepancia numérica menor, de la misma familia que las anteriores |
| `appKine-api/CLAUDE.md` / workspace `CLAUDE.md` | "1527 unitarias + 118 de integración" (de los reportes de `target/`) | Conteo estático de anotaciones: **1343** totales (1279 `@Test` + 56 `@ParameterizedTest` + 8 `@RepeatedTest`), 95 en `*IT.java` | No es necesariamente un error: un conteo estático de anotaciones no captura los casos que expande un `@ParameterizedTest`/`@RepeatedTest` al ejecutarse. El número de "ejecución real" es mayor por diseño. Se registra la diferencia porque el comando exige no mezclar fuentes sin decirlo |
| `appKine-api/CLAUDE.md` §7 | Gate de cobertura: "gatea `LINE` (89,69 %) e `INSTRUCTION` (88,94 %)... no gatea `BRANCH`" | Confirmado en `pom.xml:271-282`: la única `<rule>` de JaCoCo tiene dos `<limit>`, `LINE` y `INSTRUCTION`, ambos con `minimum 0.80`. Sin `<counter>BRANCH</counter>` en ningún lado | **Vigente, no corregido.** Cobertura de rama real no verificable sin correr `mvnw verify` (prohibido en esta tarea) |
| `appKine-api/CLAUDE.md` §7 | "Los contratos publicados de 02.02 y 02.05 prometen `concurrent-modification` y su código devuelve `conflict`... quedan 13 ocurrencias en el YAML" | Confirmado: `grep -c "concurrent-modification" openapi/akine-api.yaml` → **13**. `resource.application.{CatalogoService,CatalogoSolicitudService,DisponibilidadService,EspacioService}` lanzan `OptimisticLockingFailureException` plano → `GlobalExceptionHandler` lo mapea a `conflict`; solo `OrganizationProblemHandler` (vía `ObjectOptimisticLockingFailureException`, la subclase de JPA con `@Version`) emite `concurrent-modification`. `DisponibilidadProblemHandler` lo documenta explícitamente en su Javadoc (líneas 51–62) | **Vigente, no corregido** en M04/M05/M06 |
| `appKine-api/CLAUDE.md` §7 | "El QA manual... no se corrió para 02.02, 02.03, 02.04 ni 02.05", tampoco sus E2E | `docs/tests-diferidos.md` (#17–#19) confirma que el E2E del recorrido de horarios (02.04) y el QA manual contra la base **no corrieron**; no hay forma de verificar desde filesystem si corrieron para 02.02/02.03/02.05 más allá de que ese mismo documento y el registro de cierre de 02.04 lo afirman sin contradicción en el código | Sin corregir — coincide con lo declarado |
| `appKine-api/docs/tests-diferidos.md` | Escenario 7b: "`@Disabled` en `IdempotenciaYUniquesIT:132`" | Confirmado línea por línea | Sin discrepancia |
| `appKine-web/CLAUDE.md` §8 "Checklist de cierre de tarea" y §7 "Próximo paso — cerrar 01.02" | Checklist fechado al cierre de 01.02 (commitear tras contrato 0.3.0, E2E de auth, remote de GitHub) | 01.02 está cerrada hace cuatro etapas; el checklist nunca se actualizó ni se reemplazó por uno vigente | El archivo no describe el estado de la etapa actual en ningún punto después de 01.02 — es la misma causa raíz que la primera fila de esta tabla |

## 7. Qué no se pudo determinar

- **Conteo exacto de tests ejecutados** (vs. anotados): requiere correr `mvnw verify` /
  `npm run test:ci`, prohibido por esta tarea y por la regla del comando de no ejecutar builds.
- **Si el QA manual y los E2E de 02.02, 02.03 y 02.05 corrieron en algún momento no reflejado en
  el código**: solo hay constancia documental (tests-diferidos.md, registros de cierre), nada
  verificable contra el filesystem.
- **Cobertura de rama real al 26/08**: el `CLAUDE.md` del backend cita 78,03 % de un reporte de
  `target/`, que no se puede regenerar sin build.
- **Remote de GitHub para `appKine-web`**: `appKine-web/CLAUDE.md` §7 ya declaraba "sin remote
  configurado" en el estado congelado de 01.02; no se verificó `git remote -v` en esta corrida.

## 8. Próximo paso recomendado

1. **Escribir el registro de cierre de AKINE-02.02** (`docs/AKINE_IMPLEMENTATION_PLAN.md`,
   siguiendo el formato de los registros ya escritos) — es deuda documental reconocida desde
   02.03 y el único hueco entre 00.01 y 02.05.
2. **Actualizar `appKine-web/CLAUDE.md` §7** con el estado real (19 commits, árbol limpio,
   02.01–02.05 cerradas): hoy describe un instante de hace cuatro etapas y es la causa más
   probable del error que esta regeneración corrige.
3. **Empezar AKINE-02.06** (servicio global y oferta por consultorio) — es el próximo paso que
   declara `appKine-api/CLAUDE.md` y el primer `⬜` de la tabla de la sección 1.
