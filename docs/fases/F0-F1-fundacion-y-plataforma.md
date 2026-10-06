# F0–F1 — Fundación y plataforma segura

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~88 %**
> Etapas del plan: 00.01–00.03 y 01.01–01.03 (§13, líneas ~562–1069). Arquitectura en §2–§3.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| §2–§3 Arquitectura y stack | PARCIAL | 80 | Observabilidad, Sonar, SBOM, imagen Docker, protección de `main`, E2E en CI |
| 00.01 Repos y baseline | CUMPLIDA con deuda | 85 | Análisis de dependencias, protección de rama |
| 00.02 Baseline ejecutable | CUMPLIDA | 90 | Observabilidad (diferida a 07.08) |
| 00.03 Decisiones y rebaseline | CUMPLIDA | 95 | — |
| 01.01 Tenancy y suscripción | CUMPLIDA | 90 | E2E de crear organización |
| 01.02 Identidad y recuperación | PARCIAL | 85 | API de reintento manual de notificaciones; E2E de reset completo |
| 01.03 Memberships, permisos, auditoría | PARCIAL | 85 | `PLATFORM_ADMIN` inalcanzable; E2E de acceso denegado y auditoría |

## Lo que está bien y no hay que tocar

- Monolito modular `com.akine.<modulo>/{api,application,domain,infrastructure,spi}`. Todo cruce
  entre módulos pasa por `spi`; ArchUnit lo hace cumplir (`ModuleArchitectureTest`,
  `CodingConventionsTest`).
- `EsquemaMultiTenantIT` exige `organization_id` en cada tabla, con las excepciones de ADR-0023.
- Contrato code-first con gate de drift (`OpenApiContractIT`). El YAML 0.44.0 **sí declara
  `securitySchemes`** (bearer + cookie): los `CLAUDE.md` que dicen lo contrario están viejos.

## Faltantes

### Backend
- [x] ~~**API de reintento manual de notificaciones** (01.02). `OutboxDispatchService.reintentarManualmente`
  existe y no tiene llamador; `notification` no tiene paquete `api`. Propuesta:
  `POST /api/v1/notifications/{id}/retry` con permiso administrativo y filtro por tenant.~~
  → A-3: `POST /api/v1/organizations/{orgId}/notifications/{notificationId}/retry` con
  `colaborador:manage` (contrato 0.47.0).
- [ ] **Bootstrap del `PLATFORM_ADMIN`** sembrado por `V15`. Hoy es inalcanzable:
  `PasswordResetService.solicitar` corta en `!cuenta.puedeAutenticarse()` y responde 202 en
  silencio. Consecuencia: en un despliegue nuevo ningún endpoint de `/api/v1/platform/**` es
  usable. **Requiere decisión del usuario** (toca ADR-0018 y el flujo de invitación).
- [ ] Soporte para "cambio de email pendiente" (caso borde de 01.02): no hay esquema.

### Frontend / E2E
- [ ] E2E de crear organización.
- [ ] E2E de registro → activación → login → **reset con canje real del token** (hoy la activación
  está sembrada y el reset solo prueba la respuesta uniforme).
- [ ] E2E de acceso denegado y de consulta de auditoría.

### Tests backend
- [x] ~~Slice tests de `MembershipController`, `AuditEventController` y los dos controllers de plataforma.~~ → A-5: 51 tests `@WebMvcTest` en `organization/api` (22 + 11 + 9 + 9). Destaparon un defecto real: el PATCH de membership sin `changeScope` —el request que arma la pantalla para cambiar sólo el rol— respondía 400 por `boolean` primitivo bajo Jackson 3; corregido a `Boolean`.

### CI y operación (se pueden diferir a F8, pero se anotan acá porque nacieron en F0)
- [ ] Job que construya imagen Docker del backend con SBOM.
- [ ] Activar el job E2E del `ci.yml` del frontend (está comentado: **hoy ningún E2E corre en CI**).
- [ ] Dependabot u OWASP dependency-check en los dos repos.
- [ ] Protección de rama en `main`.
- [ ] Activar SonarQube (el job está escrito y comentado en los dos `ci.yml`).
- [ ] Reglas JaCoCo `PACKAGE` al 90 % para módulos críticos (`PENDIENTE(F1)` en `pom.xml`).

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

## Para cerrar la fase

- [x] ~~ADR que ratifique `encounter` como módulo propietario de la Sesión; actualizar `AGENT.md` §4.~~ → ADR-0024 y `AGENT.md` §4 (A-2).
- [ ] Decisión + implementación del bootstrap de `PLATFORM_ADMIN`.
- [x] ~~Endpoint de retry de notificaciones con su test.~~ → A-3, `ReintentoDeNotificacionServiceTest`.
- [ ] Los tres E2E de arriba, contra backend real.
- [ ] Registro de cierre de 01.02 y 01.03 actualizado.
