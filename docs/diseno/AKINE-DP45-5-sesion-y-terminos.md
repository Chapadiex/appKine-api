# AKINE-DP45-5 — "Mantener la sesión iniciada" y aceptación de términos

- **Decisión:** DP-45 punto 5 (`docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7).
- **Contrato:** 0.148.0 (minor, aditivo). **Migración:** `V124`.
- **Módulo:** `identity`. Enmienda a ADR-0017 (vida del refresh) al final de ese ADR.

## A. Mantener la sesión iniciada

| | Sin `recordarme` (o `false`) — hoy | Con `recordarme: true` |
|---|---|---|
| Vida ABSOLUTA del refresh | `akine.identity.session.refresh-ttl` (12 h) | `akine.identity.session.refresh-ttl-recordada` (30 d) |
| Cookie `akine_rt` | la de hoy: `Max-Age` hasta el vencimiento absoluto | igual, con el `Max-Age` más largo |
| Rotación | hereda `expira_en` de la familia | hereda `expira_en` **y** `recordar_sesion` |

- `POST /api/v1/auth/login` (`login`) acepta `recordarme` opcional (boolean, default `false`). Un
  cliente que no lo manda recibe exactamente la respuesta y la cookie de hoy.
- La cookie de hoy **ya es persistente** (lleva `Max-Age` contra el vencimiento absoluto). Pasar la
  sesión corta a cookie de sesión de navegador cambiaría la conducta actual, que el encargo pide no
  tocar: queda como decisión del usuario (ver "Qué no se hace").
- `refresh_token.recordar_sesion` (V124, `NOT NULL DEFAULT 0`): la rotación lo copia. La vida no
  depende de la columna —el sucesor hereda `expira_en`, que nunca se corre— pero dejarlo en la fila
  hace auditable qué sesiones son largas y permite revocarlas o purgarlas por separado.
- Logout, cierre masivo, reuso y revocación por bloqueo/reset: sin cambios, operan por familia o
  por cuenta y no miran la vida.
- `refresh-ttl-recordada` tiene que ser ≥ `refresh-ttl`; si no, no arranca.

## B. Aceptación de términos

- Configuración: `akine.identity.terminos.version-vigente` (default vacío) y
  `akine.identity.terminos.url` (default vacío). Sin versión, nada cambia.
- `POST /api/v1/auth/register` (`registerAccount`) acepta `aceptaTerminos` (boolean) y
  `versionTerminos` (string ≤ 40). Con versión configurada exige `aceptaTerminos=true` **y** la
  versión exacta; si no, `400 validation-error` con `errors.aceptaTerminos` o
  `errors.versionTerminos`.
- **ADR-0018:** la exigencia se evalúa antes de mirar si el email tiene cuenta, como el plan y el
  slug. El 400 es el mismo con email libre o tomado; el 202 también. En el camino del duplicado
  no se registra nada (no hubo hecho sobre ninguna cuenta).
- Registro: tabla `aceptacion_terminos` (`cuenta_id`, `version`, `aceptado_en`, `origen`
  `REGISTRO|INVITACION`), unique `(cuenta_id, version)`. Tabla y no columnas en `cuenta` porque al
  cambiar la versión habrá re-aceptaciones y la historia no se pisa. Sin `organization_id`: es dato
  de la identidad global (ADR-0019).
- `POST /api/v1/auth/invitations/accept` (`acceptInvitacion`): mismos dos campos; **sólo** se exigen
  cuando la aceptación crea la cuenta (`requiereRegistro` del preview). Quien ya tiene cuenta acepta
  como hoy; su aceptación de términos vigentes es un flujo aparte que no se hace acá.
- Nueva: `GET /api/v1/terms` (`getCurrentTerms`), pública, devuelve
  `{ versionVigente, url, aceptacionObligatoria }`. Sin texto legal: lo provee el usuario.
- Las dos con la sesión del refresh no cambian de permisos: todo es público como hoy.

## Qué no se hace

- No se convierte la cookie de la sesión corta en cookie de sesión del navegador (cambiaría la
  conducta de hoy). Si el usuario lo quiere, es una línea en `RefreshCookies` más la columna.
- No se pide re-aceptación a cuentas existentes cuando cambia la versión.
- No se agrega `recordarme` a la activación ni al reset (no abren sesión).
- Ningún permiso nuevo.

## Design challenge

1. **Ownership.** `aceptacion_terminos` y la columna nueva de `refresh_token` son de `identity`.
   Nadie más las toca.
2. **Ciclos.** Ninguna arista nueva entre módulos: todo vive dentro de `identity`; `platform`
   sólo suma una ruta pública en la lista de `SecurityConfig`, como las demás de `/auth`.
3. **Tenant.** No llevan `organization_id`, por ADR-0019: la cuenta y su sesión son globales. El
   unique `(cuenta_id, version)` es por identidad, que es el alcance correcto.
4. **Reglas maestras.** No toca HC/Caso/Sesión clínica ni economía. "Sesión" acá es la de login.
5. **Baja lógica.** No se borra nada; la aceptación es append-only.
6. **Contrato.** Aditivo: dos campos opcionales en dos requests, uno en login, una operación GET
   nueva. Minor 0.148.0.
7. **Ruta crítica.** Cimientos completos (M02 cerrado desde 01.02).
8. **El caso que rompe el diseño.** Una cookie robada de una sesión recordada vale 30 días en vez
   de 12 h. Lo acota: la rotación estricta con detección de reuso (el primer canje del legítimo
   o del atacante revoca la familia), el vencimiento absoluto que la rotación no corre, y que es
   opt-in por login. Segundo caso: que la exigencia de términos abra un oráculo de cuentas; se
   cierra validando antes de mirar el email, y lo prueba un IT con email libre y tomado.
