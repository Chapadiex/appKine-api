# AKINE — Mapa del proyecto

**Generado:** 2026-08-27 · **Comando:** `/project-map` (sin argumento → mapa completo)
**Método:** relevado por Bash contra filesystem y git. Donde un documento contradice lo
observado, **gana lo observado** y la contradicción queda en §8.

| Repo | Rama | Commits | HEAD | Working tree |
|---|---|---|---|---|
| `appKine-api` | `akine-01.02-identidad` | 54 | `be19bb5` | limpio (0 modificados, 0 untracked) |
| `appKine-web` | `akine-01.02-identidad` | 20 | `8f5ca3a` | limpio (0 modificados, 0 untracked) |

Comandos: `git rev-list --count HEAD`, `git log --oneline | head -1`,
`git status --short`, `git ls-files --others --exclude-standard | wc -l`.

---

## 1. Estado por etapa

| Etapa | Estado | Evidencia observada |
|---|---|---|
| AKINE-00.01 | ✅ cerrada | Registro de cierre en el plan, línea 5307 |
| AKINE-00.02 | ✅ cerrada | Registro, línea 5472 |
| AKINE-00.03 | ✅ cerrada | Registro, línea 5681 |
| AKINE-01.01 | ✅ cerrada | Registro, línea 5784 (1 escenario diferido: 7b) |
| AKINE-01.02 | ✅ cerrada | Registro, línea 5929 |
| AKINE-01.03 | ✅ cerrada | Registro, línea 6053 · diseño `AKINE-01.03-permisos.md` |
| AKINE-02.01 | ✅ cerrada | Registro, línea 6181 · diseño `AKINE-02.01-consultorios.md` |
| AKINE-02.02 | ✅ cerrada | Registro, línea 6283 — **es una reconstrucción declarada, no un acta**. Sin diseño de etapa. |
| AKINE-02.03 | ✅ cerrada | Registro, línea 6800 · diseño `AKINE-02.03-colaboradores.md` |
| AKINE-02.04 | ✅ cerrada | Registro, línea 6928 · diseños `AKINE-02.04-plan.md` + `-disponibilidad.md` |
| AKINE-02.05 | ✅ cerrada | Registro, línea 6687 · diseño `AKINE-02.05-catalogos.md` |
| **AKINE-02.06** | 🔶 **en curso — backend a mitad, frontend sin empezar** | 8 commits (`e1fce26`…`be19bb5`), módulo `offering` con dominio/aplicación/infra, `V24`, ADR-0023, diseños `AKINE-02.06-plan.md` + `-servicio-y-oferta.md`. **Sin controller REST, sin paths en OpenAPI, sin feature en el frontend, sin registro de cierre.** |
| AKINE-02.07 | ⬜ pendiente | Definida en el plan, sin código |
| AKINE-03.01 | ⬜ pendiente | Definida en el plan, sin código |

El plan define 56 etapas (`AKINE-00.01` → `AKINE-09.04`).
Comando: `grep -oE "AKINE-0[0-9]\.[0-9]+" docs/AKINE_IMPLEMENTATION_PLAN.md | sort -u | wc -l` → 56.

---

## 2. Backend — `appKine-api`

### 2.1 Módulos

| Módulo | Archivos `.java` en `src/main` |
|---|---|
| `organization` | 196 |
| `resource` | 148 |
| `identity` | 107 |
| `platform` | 44 |
| `offering` | **33 — nuevo en 02.06** |
| `notification` | 32 |

Comando: `find src/main/java/com/akine/<mod> -name '*.java' | wc -l`.

`offering` tiene `application/`, `domain/`, `domain/exception/`, `domain/port/`, `infrastructure/`
y **cero clases con `@RestController`** (`grep -rl RestController src/main/java/com/akine/offering | wc -l` → 0).

### 2.2 Endpoints — 81 operaciones en 23 controllers

| Controller | Base path | Ops |
|---|---|---|
| AccountAdminController | `/api/v1/accounts` | 3 |
| AccountRegistrationController | `/api/v1/auth` | 3 |
| AuthSessionController | `/api/v1/auth` | 5 |
| PasswordResetController | `/api/v1/auth` | 2 |
| InvitacionPublicaController | `/api/v1/auth/invitations` | 3 |
| MeContextController | `/api/v1/me` | 2 |
| MembershipProvisioningController | `/api/v1/memberships` | 1 |
| OrganizationController | `/api/v1/organizations` | 4 |
| SubscriptionController | `/api/v1/organizations/{orgId}/subscription` | 4 |
| MembershipController | `/api/v1/organizations/{orgId}/memberships` | 10 |
| AuditEventController | `/api/v1/organizations/{orgId}/audit-events` | 1 |
| ColaboradorInvitacionController | `/api/v1/organizations/{orgId}/colaborador-invitaciones` | 4 |
| ConsultorioController | `/api/v1/organizations/{orgId}/consultorios` | 4 |
| EspacioController | `/api/v1/organizations/{orgId}/consultorios/{consultorioId}/espacios` | 6 |
| CalendarioController | `/api/v1/consultorios/{consultorioId}/calendario` | 2 |
| ExcepcionController | `/api/v1/consultorios/{consultorioId}/excepciones` | 3 |
| DisponibilidadController | `/api/v1/consultorios/{consultorioId}/profesionales/{membershipId}/disponibilidad` | 5 |
| CatalogoController | `/api/v1/catalogos` | 8 |
| CatalogoSolicitudController | `/api/v1/catalogo-solicitudes` | 3 |
| PlanController | `/api/v1/plans` | 1 |
| PlatformSupportAccessController | `/api/v1/platform` | 3 |
| PlatformRoleController | `/api/v1/platform/roles` | 3 |
| VersionController | `/api/v1/version` | 1 |

Comando: por controller, `grep -cE "@(Get|Post|Put|Patch|Delete)Mapping"`. Suma → **81**.

### 2.3 Migraciones — V1…V24 (24 archivos)

| Migración | Tablas creadas |
|---|---|
| V1 | `platform_schema_info` |
| V2 | `organization`, `plan`, `plan_limit`, `plan_feature`, `subscription`, `subscription_transition` |
| V3 | `consultorio`, `membership`, `account_active_context`, `organization_onboarding` |
| V4 | — (seed de planes) |
| V5 | `audit_event` |
| V6 | `cuenta` |
| V7 | `token_verificacion`, `refresh_token` |
| V8 | `onboarding_registro` |
| V9 | `notification_outbox` |
| V10, V11 | — (alter de `membership`) |
| V12 | `membership_grant`, `platform_role` |
| V13 | `support_access` |
| V14 | — (índices e inmutabilidad de auditoría) |
| V15 | — (seed `PLATFORM_ADMIN`) |
| V16 | `consultorio_alta` |
| V17, V18 | — (backfill/contracción de timezone) |
| V19 | `espacio` |
| V20 | `especialidad`, `practica`, `nomenclador`, `nomenclador_item`, `catalogo_solicitud` |
| V21 | `colaborador_invitacion` |
| V22 | `feriado` |
| V23 | `consultorio_calendario`, `profesional_disponibilidad`, `disponibilidad_excepcion` |
| **V24** | **`servicio`, `oferta_servicio_consultorio`** — 02.06 |

**27 tablas de negocio + 1 técnica.**

### 2.4 Tests

| Métrica | Valor | Comando |
|---|---|---|
| Clases de test | 153 | `find src/test -name "*.java" \| wc -l` |
| Métodos `@Test`/`@ParameterizedTest` | 1386 | `grep -rhoE "@(Test\|ParameterizedTest)\b" src/test \| wc -l` |
| Clases de test de `offering` | 4 | `find src/test -path "*offering*" -name "*.java" \| wc -l` |
| Sin commitear | 0 | working tree limpio |

**No se ejecutaron.** Verde/rojo: no medido — el mapa hace conteo estático, no corre builds.
Cobertura: **no medida** por la misma razón; el último número declarado es 89,69 % línea /
78,03 % rama, y no hay gate de `BRANCH` en `pom.xml` que lo sostenga.

### 2.5 Documentación del repo

24 entradas en `docs/adr/` (incluye README) — últimas: ADR-0021, 0022, **0023** (tablas globales
sin `organization_id`, consolidado en 02.06). 8 archivos en `docs/diseno/`.

---

## 3. Frontend — `appKine-web`

### 3.1 Rutas (`src/app/app.routes.ts`)

`''` · `auth` · `seleccionar-contexto` · `organizacion` · `espacios` · `SEGMENTO_HORARIOS` ·
`catalogo` · `sin-permiso` · `**`.

**No hay ruta de servicios ni de ofertas.**

### 3.2 Features

| Feature | `.ts` (sin spec) | `.spec.ts` |
|---|---|---|
| `resource` | 25 | 21 |
| `organization` | 17 | 14 |
| `auth` | 10 | 10 |
| `catalog` | 6 | 5 |
| `platform` | 1 | 1 |

**No existe `features/offering`.**

### 3.3 Core

- `guards/`: `auth.guard`, `context.guard`, `permission.guard`
- `interceptors/`: `auth.interceptor`, `error.interceptor`
- `services/`: `auth-token.store`, `permissions.store`, `session.service`, `tenant-context.store`
- `models/`, `testing/`
- `shared/`: `components`, `directives`, `pages`, `utils`

### 3.4 Tests

| Métrica | Valor | Comando |
|---|---|---|
| Archivos `.spec.ts` | 66 | `find src -name "*.spec.ts" \| wc -l` |
| Bloques `it(` | 548 | `grep -rhoE "\bit\(" src --include=*.spec.ts \| wc -l` |
| Specs E2E | 4 archivos | `ls e2e` → `auth-flujo`, `contexto-sin-fuga`, `errores-sin-internals`, `smoke` |
| Bloques `test(` en E2E | 24 | `grep -rhoE "\btest\(" e2e \| wc -l` |

**Los E2E cubren 01.02–01.03. No hay E2E de espacios, catálogo, horarios ni servicios.**
Tampoco se ejecutaron acá.

5 ADRs de frontend en `docs/adr/` (0001–0005).

---

## 4. Contrato OpenAPI

| | Valor |
|---|---|
| Publicado por el backend | `openapi/akine-api.yaml` **0.11.0** |
| Consumido por el frontend | `environment.ts` y `environment.prod.ts` → `contractVersion: '0.11.0'` |
| Paths documentados | 62 |
| Operaciones documentadas | 81 |
| Operaciones implementadas | 81 |

**Drift de operaciones: 0.** Los 81 endpoints con anotación de mapping coinciden en número con
las 81 operaciones del YAML (`grep -cE "^    (get|post|put|patch|delete):"`).

**Drift de alcance, sí:** `V24` creó `servicio` y `oferta_servicio_consultorio` y el módulo
`offering` tiene servicios de aplicación funcionando, pero **el contrato no expone ni un path de
servicio ni de oferta** (`grep -E "^  /" | grep -iE "servicio|oferta"` → vacío). Las 11
coincidencias de "servicio/oferta" en el YAML son prosa de descripciones de `espacio`, no rutas.
Consecuencia: **02.06 no es consumible por ningún cliente todavía**, y por eso la versión sigue
en 0.11.0 sin estar mal.

**El OpenAPI sigue sin declarar `securityScheme`** en ningún módulo.

---

## 5. Trabajo sin commitear

Ninguno. Ambos repos tienen el working tree limpio y 0 archivos untracked.

---

## 6. Runtime (al momento de generar)

| Servicio | Estado | Comando |
|---|---|---|
| `akine-mysql` | Up 4 días (healthy) | `docker ps` |
| `akine-mailpit` | Up 2 días (healthy) | `docker ps` |
| API `localhost:8080` | **caída** — `curl` devuelve `000` (sin conexión) | `curl -m5 /actuator/health` |
| Web `localhost:4200` | **200** — dev server levantado | `curl -m5 localhost:4200` |

El frontend está corriendo contra un backend que no responde.

---

## 7. Checklist pendiente declarada en `appKine-api/CLAUDE.md`

- [ ] Protección de rama en `main`
- [ ] Job de SonarQube en `.github/workflows/ci.yml` (escrito, comentado)
- [ ] Observabilidad: logging JSON, Prometheus, OpenTelemetry
- [ ] Reglas `PACKAGE` de cobertura al 90 % para módulos críticos (`PENDIENTE(F1)` del `pom.xml`)
- [ ] Gate de `BRANCH` en JaCoCo, después de subir la cobertura de rama
- [ ] `PENDIENTE(F1)` de `.claude/qa-config.md`
- [ ] Escenario diferido 7b: `request_hash` en `onboarding_registro`
- [ ] Registro de cierre de AKINE-02.02 → **este ítem ya no aplica, ver §8**

---

## 8. Discrepancias entre la documentación y lo observado

1. **`AKINE/CLAUDE.md` está desactualizado en una etapa entera.** Declara "Próximo paso:
   AKINE-02.06" y "41 commits, último `be39da4`". Observado: **54 commits, último `be19bb5`**,
   con ocho commits de 02.06 ya hechos. 02.06 **no es el próximo paso: está a mitad de camino.**
2. **`AKINE/CLAUDE.md` dice que "falta el registro de cierre de 02.02".** Observado: el registro
   **existe** (plan, línea 6283). Su propia cabecera aclara que es una **reconstrucción** hecha
   el 26/08 por alguien que no estuvo en la sesión, con la evidencia enumerada. La afirmación
   correcta hoy no es "falta", sino "existe y está declarado como reconstruido".
   El ítem homónimo del checklist de `appKine-api/CLAUDE.md` (línea 380) tampoco aplica ya.
3. **Conteo de tests.** `CLAUDE.md` declara "1527 unitarias + 118 de integración" = 1645.
   El conteo de anotaciones da **1386**. La diferencia es de método: los tests parametrizados se
   cuentan una vez por anotación y no por caso ejecutado. No es necesariamente un error — el
   número declarado viene de una corrida real de Surefire/Failsafe y **este mapa no corrió nada**.
   Miden cosas distintas y ninguno reemplaza al otro.
4. **Frontend con 20 commits, no 17.** Tres commits posteriores a `9c886da`: pineo de rutas y
   dos fixes de 02.04.
5. **Cobertura, tests en verde y QA manual: no medidos por este mapa.** Todo lo que
   `CLAUDE.md` dice sobre porcentajes sigue siendo la última medición conocida, no una
   verificación de hoy.

---

## 9. Próximo paso recomendado

1. **Terminar 02.06 backend.** Falta la capa REST del módulo `offering`: controllers,
   handler de problemas, y **publicar los paths en el OpenAPI subiendo la versión a 0.12.0**.
   Sin eso, `V24` y 33 clases de dominio no le sirven a nadie.
2. **Regenerar el cliente y levantar el feature del frontend** una vez publicado el contrato.
   Hoy `contractVersion` está pineado en 0.11.0 en los dos entornos, así que el gate de
   `check-contract-version.mjs` va a fallar apenas suba el backend — que es exactamente lo que
   tiene que pasar.
3. **Actualizar `AKINE/CLAUDE.md`**: próximo paso, conteo de commits, y borrar la afirmación
   de que falta el cierre de 02.02.

```bash
cd appKine-api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
