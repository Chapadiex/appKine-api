# F0–F1 — Fundación y plataforma segura

> **Cerrada técnicamente el 12/10/2026** · backend `7f835cf` (contrato 0.85.0, `V85`) · frontend
> `a954680` · **~98 %**. Lo que falta es del usuario: secretos de Sonar y el QA manual del §6.
> Auditoría original del 01/10/2026 sobre backend `37ff201` / frontend `4c6ae8b` (~88 %).
> Etapas del plan: 00.01–00.03 y 01.01–01.03 (§13, líneas ~562–1069). Arquitectura en §2–§3.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| §2–§3 Arquitectura y stack | CUMPLIDA (pendiente del usuario) | 95 | Cargar `SONAR_TOKEN`/`SONAR_HOST_URL`: el job existe y se saltea limpio |
| 00.01 Repos y baseline | CUMPLIDA | 100 | — (Dependabot/OWASP en G-6, protección de `main` verificada) |
| 00.02 Baseline ejecutable | CUMPLIDA | 100 | — (observabilidad en G-4, api #62) |
| 00.03 Decisiones y rebaseline | CUMPLIDA | 100 | — |
| 01.01 Tenancy y suscripción | CUMPLIDA | 100 | — (E2E de alta de organización en web #36) |
| 01.02 Identidad y recuperación | CUMPLIDA | 100 | — (cambio de email pendiente: fuera de alcance del MVP, ver registro) |
| 01.03 Memberships, permisos, auditoría | CUMPLIDA con deuda declarada | 95 | Escenario diferido 7b (`request_hash` en `onboarding_registro`) |

## Lo que está bien y no hay que tocar

- Monolito modular `com.akine.<modulo>/{api,application,domain,infrastructure,spi}`. Todo cruce
  entre módulos pasa por `spi`; ArchUnit lo hace cumplir (`ModuleArchitectureTest`,
  `CodingConventionsTest`).
- `EsquemaMultiTenantIT` exige `organization_id` en cada tabla, con las excepciones de ADR-0023.
- Contrato code-first con gate de drift (`OpenApiContractIT`). El YAML **sí declara
  `securitySchemes`** (bearer + cookie): los `CLAUDE.md` que dicen lo contrario están viejos.

## Faltantes

### Backend
- [x] ~~**API de reintento manual de notificaciones** (01.02). `OutboxDispatchService.reintentarManualmente`
  existe y no tiene llamador; `notification` no tiene paquete `api`. Propuesta:
  `POST /api/v1/notifications/{id}/retry` con permiso administrativo y filtro por tenant.~~
  → A-3 (api #15, 06/10/2026): `POST /api/v1/organizations/{orgId}/notifications/{notificationId}/retry` con
  `colaborador:manage` (contrato 0.47.0). Pantalla en web #32 (08/10/2026).
- [x] ~~**Bootstrap del `PLATFORM_ADMIN`** sembrado por `V15`. Hoy es inalcanzable:
  `PasswordResetService.solicitar` corta en `!cuenta.puedeAutenticarse()` y responde 202 en
  silencio. Consecuencia: en un despliegue nuevo ningún endpoint de `/api/v1/platform/**` es
  usable. **Requiere decisión del usuario** (toca ADR-0018 y el flujo de invitación).~~
  → DP-14 + A-4 (api #51, 07/10/2026): `AKINE_BOOTSTRAP_ADMIN_EMAIL` re-apunta la cuenta sembrada y le encola un enlace
  de activación al arrancar (`PlatformAdminBootstrapRunner`, `docs/diseno/AKINE-A-4-bootstrap.md`).
  Sin migración, sin cambio de contrato y sin tocar ADR-0018.
- [x] ~~Soporte para "cambio de email pendiente" (caso borde de 01.02): no hay esquema.~~
  → **Fuera de alcance del MVP** (12/10/2026). Ningún RF ni CA lo exige: M02 tiene cinco RF
  —RF-M02-001 *Registrar cuenta*, 002 *Autenticar usuario*, 003 *Restablecer contraseña*, 004
  *Asignar o revocar rol*, 005 *Bloquear o desactivar cuenta*— y ninguno cambia el email de una
  cuenta; la palabra no aparece en todo el §M02 de `AKINE_Requerimientos_Integrados.md`
  (líneas 496–880). La única mención está en la lista de casos borde de la etapa 01.02 del plan
  (`AKINE_IMPLEMENTATION_PLAN.md`, línea 1300: *"…token usado/expirado; enumeración; cambio de
  email pendiente; proveedor caído…"*), que enumera riesgos a considerar y no un requisito. El
  único cambio de email que existe es el re-apuntado del bootstrap de A-4
  (`docs/diseno/AKINE-A-4-bootstrap.md` §"Cambio de email"), que no es un flujo de usuario.
  Si se quiere, es una etapa propia: tabla de cambio pendiente con token de confirmación al
  correo nuevo, aviso al viejo, y un RF nuevo en M02.

### Frontend / E2E
- [x] ~~E2E de crear organización.~~ → A-6, web #36 (08/10/2026): `cuenta-ciclo-real.spec.ts`,
  *"la administracion de plataforma da de alta una organizacion, y el slug repetido es un 409
  tipado"* — contra el backend real, verifica el 201, el `Location`, el primer consultorio y el
  `409 organization-slug-taken`. El alta self-service la cubre el registro del ítem siguiente.
- [x] ~~E2E de registro → activación → login → **reset con canje real del token**.~~ → A-6, web #36:
  *"registro, activacion por el enlace del correo, login y restablecimiento con canje real"*. Los
  dos enlaces salen por SMTP a Mailpit y se abren tal cual llegaron; se verifica el un-solo-uso.
- [x] ~~E2E de acceso denegado y de consulta de auditoría.~~ → A-6 (web #36) + web #37 (08/10/2026,
  saca el `test.fail` después de api #70): *"acceso denegado: la pantalla redirige, el backend
  responde 403 y la auditoria del centro lo registra"* y *"la auditoria se lee solo desde el
  contexto de esa organizacion"*. Sin `test.fail`/`skip`/`fixme` en el archivo.

### Tests backend
- [x] ~~Slice tests de `MembershipController`, `AuditEventController` y los dos controllers de plataforma.~~ → A-5 (api #25, 06/10/2026): 51 tests `@WebMvcTest` en `organization/api` (22 + 11 + 9 + 9). Destaparon un defecto real: el PATCH de membership sin `changeScope` —el request que arma la pantalla para cambiar sólo el rol— respondía 400 por `boolean` primitivo bajo Jackson 3; corregido a `Boolean`.

### CI y operación
- [x] ~~Job que construya imagen Docker del backend con SBOM.~~ → G-3 (api #29, 06/10/2026; web #18, 07/10/2026): `Dockerfile` multi-stage, `cyclonedx-maven-plugin` (SBOM CycloneDX embebido en el jar) y job `imagen` en `ci.yml` que hace `docker build` sin push y publica el SBOM como artifact.
- [x] ~~Activar el job E2E del `ci.yml` del frontend.~~ → G-9, web #30 (08/10/2026): job *"E2E Playwright contra el backend real"*, que levanta el backend del `main` de `appKine-api` y es check requerido de `main`. El proyecto `chromium` de `playwright.config.ts` corre `cuenta-ciclo-real.spec.ts`.
- [x] ~~Dependabot u OWASP dependency-check en los dos repos.~~ → G-6 (api #83, web #41, 11/10/2026): los dos, más `npm audit` en el frontend (`docs/calidad.md`).
- [x] ~~Protección de rama en `main`.~~ → L-0, verificada por la API de GitHub el 12/10/2026 en los dos repos: `enforce_admins` activo, sin force-push. Checks requeridos — api: *Build, test y contrato*, *Imagen Docker y SBOM*; web: *Lint, build y tests*, *Cliente API alineado con el contrato*, *Contraste de color*, *Imagen Docker y SBOM*, *E2E Playwright contra el backend real*.
- [x] ~~Observabilidad (diferida de 00.02).~~ → G-4, api #62 (08/10/2026): log JSON correlacionado, Prometheus y trazas OTLP.
- [ ] **Pendiente del usuario** — Activar SonarQube: el job `sonar` corre en los dos `ci.yml` y se saltea limpio (*success* sobre `main` el 12/10); falta cargar los secretos `SONAR_TOKEN` y `SONAR_HOST_URL` en los dos repos. La API de secretos no lista ninguno al 12/10/2026.
- [x] ~~Reglas de cobertura por módulo crítico.~~ → G-6 (api #83) puso el trinquete y api #94 (12/10/2026) llevó la capa REST de `billing`, `person`, `scheduling` y `clinical` por encima del 0,90.

## Desvíos

| Desvío | Documentado |
|---|---|
| El módulo `encounter` es dueño de la Sesión; §2.4 la pone en `clinical` | Sí — [ADR-0024](../adr/0024-encounter-es-duenio-de-la-sesion.md); `AGENT.md` §4 lista `encounter` |
| Estado de la organización derivado de la suscripción | Sí, registro de 01.01 |
| `ORG_ADMIN` con `is_founder` en vez de rol `OWNER` | Sí, registro de 01.01 |
| Sin lockout automático por intentos | Sí, `V6` y rebaseline de 00.03 |
| Rate limit y `SecureLinkVault` en memoria (no multi-instancia) | Sí |
| Alta directa de colaborador antes que invitación | Sí; la invitación llegó en 02.03 |
| Gate de cobertura 0,73 / 0,71 contra ≥80 % de §3 | Sí, como deuda (ver F8) |
| Cambio de email de una cuenta no implementado | Sí, fuera de alcance: ningún RF de M02 lo pide (ver arriba) |

## Para cerrar la fase

- [x] ~~ADR que ratifique `encounter` como módulo propietario de la Sesión; actualizar `AGENT.md` §4.~~ → ADR-0024 y `AGENT.md` §4 (A-2, api #4, 04/10/2026).
- [x] ~~Decisión + implementación del bootstrap de `PLATFORM_ADMIN`.~~ → DP-14 (07/10/2026) y A-4
  (api #51), con `PlatformAdminBootstrapIT` contra MySQL.
- [x] ~~Endpoint de retry de notificaciones con su test.~~ → A-3 (api #15), `ReintentoDeNotificacionServiceTest`.
- [x] ~~Los tres E2E de arriba, contra backend real.~~ → web #36 y #37 (08/10/2026), corriendo en CI (web #30).
- [x] ~~Registro de cierre de 01.02 y 01.03 actualizado.~~ → sección siguiente (12/10/2026). Se
  consolida en `AKINE_IMPLEMENTATION_PLAN.md` por el coordinador.
- [ ] **Pendiente del usuario:** secretos de Sonar y QA manual del §6 de `CLAUDE.md`. No bloquean
  el cierre técnico.

---

# Registro de cierre — 12/10/2026

> Escrito en la ficha y no en el plan porque el 12/10 había siete agentes cerrando F2–F8 en
> paralelo sobre los mismos documentos. El coordinador lo consolida al final de
> `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` y en `docs/fases/README.md`.

## Actualización del registro de cierre de AKINE-01.02

Su §9 *Deuda diferida*, ítem por ítem, contra `main` `7f835cf`:

| Deuda (registro original) | Estado al 12/10/2026 | Evidencia |
|---|---|---|
| Escenario 7b: `request_hash` en `onboarding_registro` | **Abierta**, reasignada a 01.03 | `IdempotenciaYUniquesIT:132` sigue `@Disabled`; ninguna migración agrega `request_hash` a `onboarding_registro` |
| `SecureLinkVault` en memoria | **Abierta, declarada** como desvío (no multi-instancia) | `identity/infrastructure/SecureLinkVault.java`; F8 |
| Rate limit en memoria; `X-Forwarded-For` sin proxy de confianza | **Abierta, declarada** como desvío | F8 |
| `SmtpEmailSender` no existe | **Saldada** | `notification/infrastructure/SmtpEmailSender.java`; el E2E de A-6 recibe los dos enlaces por SMTP en Mailpit |
| `PLATFORM_ADMIN` sin origen de dato productivo | **Saldada** | 01.03 (`V15`) + DP-14/A-4 (api #51), `PlatformAdminBootstrapIT` |
| Ventana de gracia de 10 s del refresh | Sin cambio: necesita ADR que la supersede | — |
| Purga de `refresh_token` vencidos | Sin cambio: declarada en ADR-0017 | — |
| API de reintento manual de notificaciones (auditoría 01/10) | **Saldada** | A-3, api #15 + web #32 |
| E2E de reset con canje real (auditoría 01/10) | **Saldada** | A-6, web #36, en CI por web #30 |
| Cambio de email pendiente (caso borde, plan línea 1300) | **Fuera de alcance del MVP** | Ningún RF/CA de M02 (RF-M02-001…005) lo exige; ver *Faltantes* |

## Actualización del registro de cierre de AKINE-01.03

| Deuda (registro original) | Estado al 12/10/2026 | Evidencia |
|---|---|---|
| Escenario 7b: `request_hash` en `onboarding_registro` | **Abierta.** Es la única deuda técnica de F0–F1 con código pendiente | `docs/tests-diferidos.md` fila 7; arreglarla es migración + cambio de API de registro |
| `consultorio:manage` y `colaborador:manage` GLOBAL para plataforma | Decisión tomada, sin cambio | — |
| `ProblemDetail.type` no referencia a `ProblemType` | **Saldada** | `GlobalExceptionHandler` toma los URI de `platform.spi.problem.ProblemType`; el frontend tiene el gate `check-problem-types.mjs` |
| `PlatformRoleController` da de alta por `accountId` y no por email | Sin cambio, documentado en el propio controller | — |
| `AccountAdminController` promete una distinción de permisos que no entregó | Documentado; G-5 (api #78) hizo ejecutable la matriz de permisos | — |
| Contradicción §4 contra §5 y §6 de la matriz sobre editar la propia organización | Abierta, es decisión del usuario | — |
| `PLATFORM_ADMIN` inalcanzable (auditoría 01/10) | **Saldada** | DP-14 + A-4 (api #51) |
| Slice tests de membership, auditoría y plataforma (auditoría 01/10) | **Saldada** | A-5 (api #25): 51 `@WebMvcTest` y un defecto real corregido (`changeScope`) |
| E2E de acceso denegado y de auditoría (auditoría 01/10) | **Saldada** | A-6 (web #36) + web #37, después de api #70 (la organización de la ruta tiene que ser la del contexto) |

## Qué quedó

- Las seis etapas de F0–F1 cumplidas. Todos los ítems de *Faltantes* y de *Para cerrar la fase*
  que son código están en `main` de los dos repos, con PR y fecha arriba.
- **Ningún código nuevo en este cierre:** lo único abierto que era código —cambio de email— no lo
  pide ningún RF, y la deuda 7b exige un cambio de API del registro que no es de esta auditoría.

## Con qué evidencia

- PR mergeados, verificados por la API de GitHub el 12/10/2026 — api: #4, #15, #25, #29, #51,
  #62, #70, #78, #83, #94; web: #18, #30, #32, #36, #37, #41.
- Protección de `main` leída por la API en los dos repos (checks requeridos listados arriba).
- CI sobre los `main` actuales, todo *success*: api `7f835cf` (12/10, 18:57 UTC) — *Build, test y
  contrato*, *Imagen Docker y SBOM*, *Vulnerabilidades de dependencias*, *Analisis SonarQube*
  (salteado sin secretos); web `a954680` (12/10, 20:10 UTC) — *Lint, build y tests*, *Cliente API
  alineado con el contrato*, *Contraste*, *Imagen Docker y SBOM*, **E2E Playwright contra el
  backend real**, que incluye los cuatro E2E de cuenta de A-6.
- No se corrió `./mvnw verify` localmente: el cierre sólo toca esta ficha.

## Qué queda para el usuario

1. **Cargar `SONAR_TOKEN` y `SONAR_HOST_URL`** como secretos de Actions en `appKine-api` y
   `appKine-web`. El job ya existe y se enciende solo.
2. **QA manual del §6 de `appKine-api/CLAUDE.md`**, sin correr desde 02.02; declarado bloqueante
   para deploy, no para el cierre técnico.
3. Decidir si el **cambio de email de una cuenta** entra al alcance (sería un RF nuevo en M02 y una
   etapa propia) y la **contradicción de la matriz** sobre editar la propia organización (01.03).
4. Priorizar el **escenario 7b** (`request_hash` en el registro): migración + cambio de API.
