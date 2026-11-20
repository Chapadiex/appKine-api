# Términos y condiciones obligatorios y reaceptación

Fecha: 19/11/2026. Rama `akine-terminos` (api) y `akine-web-terminos` (web).
Reservas: migración **V32**, contrato **0.183.0** (V29–V31 y 0.180.0–0.182.0 están tomados por
`akine-asistente-acciones`, `akine-api-cobro` y `akine-api-planes`).

## Para qué

El usuario quiere que aceptar los términos deje de depender de la configuración: **siempre**
se aceptan, también al pedir la prueba de 30 días, y **cuando cambia la versión todos vuelven a
aceptar** antes de seguir usando la app. Hoy (DP-45) sólo se piden si alguien configura
`AKINE_TERMINOS_VERSION`, y sólo al crear la cuenta por registro o invitación.

## Objetivos

1. Versión vigente con valor por defecto: el despliegue exige términos sin configurar nada.
2. El pedido de prueba (landing) exige la casilla y guarda versión y fecha.
3. Toda cuenta sin aceptación de la versión vigente queda **bloqueada en el backend** (decisión
   del usuario: backend y pantalla) hasta aceptar, sin importar cómo nació (registro,
   invitación, administrador de plataforma, alta manual).
4. La web muestra una pantalla "Aceptá los términos" después del ingreso y ante el 403.

## No objetivos

- Un texto de términos distinto para prueba y para planes pagos: es uno solo.
- Administrar versiones desde una pantalla: la versión sigue en la configuración, porque el texto
  vive en `/terminos` de la web y cambiarlo ya es un despliegue.
- Avisar por correo el cambio de términos.

## Enfoques considerados

| Enfoque | Qué cambia | Por qué no / por qué sí |
|---|---|---|
| Sólo pantalla | Guard en la web | Descartado por el usuario: la API quedaba usable sin aceptar |
| **Backend + pantalla** (elegido) | Interceptor que responde 403 `terminos-pendientes` + pantalla | Nadie opera sin aceptar, ni por fuera de la web |
| Versiones en base con ABM de plataforma | Tabla de versiones y pantalla | Más trabajo sin beneficio: el texto igual se despliega |

## Diseño

**Versión vigente.** `akine.identity.terminos.version-vigente: ${AKINE_TERMINOS_VERSION:2026-11-19}`
y `url: ${AKINE_TERMINOS_URL:/terminos}`. La bean pasa a `platform.spi.TerminosVigentes` porque
la usan `identity` (cuentas) y `organization` (pedido de prueba), y `organization` no puede
importar `identity`. Cambiar los términos = editar `/terminos` (con su fecha) y subir la versión
en el mismo despliegue. La configuración de **tests** (`src/test/resources/config/application.yml`)
la deja vacía para que los ~1.150 ITs que crean cuentas por SQL sigan igual; los ITs de esta
etapa la prenden con `@TestPropertySource`, como ya hace `TerminosIT`.

**Pedido de prueba.** `SolicitudDePruebaRequest` suma `aceptaTerminos` y `versionTerminos`, con
la misma validación que el alta (400 `validation-error` con el campo). V32 agrega a
`solicitud_de_prueba` las columnas `version_terminos` y `terminos_aceptados_en` (nulas para los
pedidos viejos). El campo trampa y la respuesta idéntica no cambian.

**Al aprobar el pedido** no se copia la aceptación a la cuenta: la cuenta del dueño todavía no
existe y la invitación la crea con su propia aceptación (camino de siempre). La constancia del
pedido queda en el pedido.

**Reaceptación.**
- `aceptacion_terminos.origen` suma `REACEPTACION` (V32 recrea el CHECK con los tres valores).
- `GET /api/v1/me/terminos` → `{versionVigente, url, pendiente, aceptadaEn}`.
- `POST /api/v1/me/terminos` con `{versionTerminos}` → 204; idempotente (el unique
  `cuenta_id, version` ya existe; si ya estaba, no hace nada). Versión distinta de la vigente →
  400 en `versionTerminos`.
- `TerminosPendientesInterceptor` (identity, infraestructura) en todo `/api/**` autenticado:
  si la cuenta no aceptó la vigente → **403 `terminos-pendientes`**. Exceptuados: `/api/v1/auth/**`
  (login, refresh, logout), `/api/v1/terms`, `/api/v1/me/terminos` y los endpoints públicos (sin
  cuenta no hay nada que mirar). Una aceptación es append-only, así que el "ya aceptó" se guarda
  en memoria por (cuenta, versión) y la consulta se hace una vez por cuenta.
- Acceso de soporte de plataforma: el `PLATFORM_ADMIN` también acepta, como cualquier cuenta.

**Web.**
- Casilla en el formulario de prueba con `app-terminos-del-alta` (ya existe), siempre visible.
- `TerminosPendientesStore` en `core/`: se lee al iniciar sesión; `terminosGuard` en las rutas
  autenticadas manda a `/aceptar-terminos`; el interceptor de errores hace lo mismo ante un 403
  `terminos-pendientes` (por si la versión cambia con la sesión abierta).
- Pantalla `/aceptar-terminos`: enlace al texto, casilla, "Aceptar y seguir" y "Cerrar sesión".
- El registro y la invitación ya muestran la casilla cuando `/terms` dice obligatoria; con el
  valor por defecto pasa a verse siempre.
- E2E del CI web: el backend se levanta con `AKINE_TERMINOS_VERSION=""` hasta que los E2E sepan
  aceptar.

## Desafío del diseño

1. Ownership: `aceptacion_terminos` es de `identity`; las columnas nuevas de `solicitud_de_prueba`, de `organization`.
2. Ciclos: `identity → platform.spi` y `organization → platform.spi`, que ya existen. Ninguna arista nueva.
3. Tenant: ninguna tabla nueva. `aceptacion_terminos` sigue sin `organization_id` (ADR-0019).
4. Reglas maestras: no toca HC, turnos ni cobros.
5. Baja lógica: append-only, nada se borra.
6. Contrato: aditivo (dos operaciones, dos campos en el pedido de prueba, un problem type). El
   pedido de prueba sin los campos nuevos pasa a 400: es el pedido explícito.
7. Ruta crítica: la tabla y el flujo de DP-45 ya están.
8. El caso que rompe: la versión cambia con 300 sesiones abiertas. Cada una recibe 403 en su
   siguiente pedido, la web la manda a aceptar y sigue con la misma sesión; nada se pierde salvo
   un formulario a medio guardar, que el 403 informa como cualquier error.

## Cómo quedó (backend)

- Contrato **0.183.0**: `getMisTerminos`, `aceptarMisTerminos`, `aceptaTerminos` y
  `versionTerminos` en `SolicitudDePruebaRequest`, y el problem type `terminos-pendientes`.
  Migración **V32**.
- `TerminosVigentes` quedó como interfaz en `platform.spi.terminos`; `IdentityConfig` la arma
  desde `TerminosSettings`.
- El interceptor y su configuración viven en `identity.infrastructure` (la regla de ArchUnit
  pide `@Configuration` ahí).
- `/api/v1/me/terminos` se sumó a las rutas exentas de `TenantContextFilter`: con un token
  `pre_context` (recién iniciada la sesión, sin sede elegida) respondía `missing-tenant-context`
  y la cuenta pendiente no tenía cómo aceptar.
- ITs: `SolicitudDePruebaTerminosIT` y `ReaceptacionDeTerminosIT` (en `com.akine.diferidos`, para
  reusar `BaseEscenarioDiferido`). Refresh y logout no se ejercen: los cubre el prefijo
  `/api/v1/auth/`.
