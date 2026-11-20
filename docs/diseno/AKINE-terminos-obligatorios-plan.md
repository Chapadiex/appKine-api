# Términos obligatorios y reaceptación — plan de implementación

> **Para agentes:** ejecutar con superpowers:executing-plans (o subagent-driven-development), tarea por tarea.

**Objetivo:** términos siempre exigidos, en el pedido de prueba y en toda cuenta, con reaceptación bloqueante al cambiar la versión.

**Arquitectura:** la versión vigente sale de configuración con valor por defecto; `identity` la publica por `platform.spi` para que `organization` valide el pedido de prueba. Un interceptor de `identity` corta con 403 `terminos-pendientes` a toda cuenta sin aceptación de la vigente; la web la manda a `/aceptar-terminos`.

**Stack:** Java 21, Spring Boot 4.1, PostgreSQL 18, Flyway; Angular 21 (zoneless, signals), cliente generado.

**Diseño:** `docs/diseno/AKINE-terminos-obligatorios.md`.

## Restricciones globales

- Ramas `akine-terminos` (`wt-API-TERMINOS`) y `akine-web-terminos` (`wt-WEB-TERMINOS`). Commits locales, sin push ni PR hasta que el usuario lo pida.
- Migración **V32**, contrato **0.183.0**.
- Versión por defecto `2026-11-19`; url por defecto `/terminos`. Propiedades `akine.identity.terminos.version-vigente` / `.url` (sin cambiar su nombre: `TerminosIT` las usa).
- Problem type nuevo: `terminos-pendientes` (403).
- Tests pocos: ITs de lo nuevo contra la base real, nada de unitarios de framework.

## Foco de revisión

1. Una cuenta sin aceptación puede **iniciar y cerrar sesión y refrescar**: el bloqueo no puede dejarla sin salida (IT de la tarea 3).
2. Pedidos sin sesión (landing, `/terms`, enlaces de correo) **no pasan** por el bloqueo (IT de la tarea 3, pedido público).
3. Aceptar dos veces la misma versión **no falla** (unique `cuenta_id, version`): 204 las dos (tarea 3).
4. El pedido de prueba con el campo trampa sigue respondiendo 202 aunque no traiga términos (tarea 2).
5. Con la versión vacía (tests) todo se comporta como hoy: ninguna cuenta queda pendiente (tarea 3, rama `exigeAceptacion() == false`).

---

### Tarea 1: versión por defecto y `platform.spi.TerminosVigentes`

**Archivos:**
- Modificar: `src/main/resources/application.yml` (bloque `identity.terminos`): `${AKINE_TERMINOS_VERSION:2026-11-19}`, `${AKINE_TERMINOS_URL:/terminos}` y el comentario.
- Crear: `src/test/resources/config/application.yml` con `akine.identity.terminos.version-vigente: ""` y un comentario de por qué (los ITs crean cuentas por SQL sin aceptación).
- Crear: `src/main/java/com/akine/platform/spi/terminos/TerminosVigentes.java`
- Modificar: `src/main/java/com/akine/identity/infrastructure/IdentityConfig.java`

**Interfaces:**
- Produce: `interface TerminosVigentes { Optional<String> version(); }` en `com.akine.platform.spi.terminos`.
- Produce: `@Bean TerminosVigentes terminosVigentes(TerminosSettings s)` → `s::version`.

- [ ] Paso 1: aplicar los cambios.
- [ ] Paso 2: `./mvnw -o test -Dtest="com.akine.architecture.**" -Djacoco.skip=true` → ArchUnit en verde.
- [ ] Paso 3: `./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=TerminosIT -Djacoco.skip=true` → en verde (la propiedad del test pisa la de `config/`).
- [ ] Paso 4: commit "Términos: versión vigente por defecto y TerminosVigentes en platform.spi".

### Tarea 2: términos en el pedido de prueba

**Archivos:**
- Crear: `src/main/resources/db/migration/V32__terminos_obligatorios.sql`
- Modificar: `organization/api/dto/SolicitudDePruebaRequest.java`, `organization/api/SolicitudDePruebaController.java`, `organization/application/SolicitudDePruebaService.java`, `organization/domain/SolicitudDePrueba.java`, `organization/api/OrganizationProblemHandler.java` (o el advice que ya mapea las excepciones de `SolicitudDePrueba`)
- Crear: `organization/domain/exception/TerminosDelPedidoException.java`
- Test: `src/test/java/com/akine/organization/SolicitudDePruebaTerminosIT.java`

**V32:**
```sql
ALTER TABLE solicitud_de_prueba ADD COLUMN version_terminos varchar(40);
ALTER TABLE solicitud_de_prueba ADD COLUMN terminos_aceptados_en timestamptz(6);
ALTER TABLE aceptacion_terminos DROP CONSTRAINT ck_aceptacion_terminos_origen;
ALTER TABLE aceptacion_terminos ADD CONSTRAINT ck_aceptacion_terminos_origen
    CHECK (origen IN ('REGISTRO','INVITACION','REACEPTACION'));
```
Con `COMMENT ON COLUMN` para las dos columnas (nulas en pedidos anteriores al 19/11/2026).

**Interfaces:**
- Consume: `TerminosVigentes` (tarea 1).
- `SolicitudDePruebaRequest` suma `Boolean aceptaTerminos`, `String versionTerminos` (`@Size(max = 40)`).
- `Pedido` suma `Boolean aceptaTerminos, String versionTerminos`.
- `TerminosDelPedidoException(String campo, String mensaje)` → 400 `validation-error` con `errors.{campo}`, igual que `IdentityProblemHandler.handleTerminosNoAceptados`.
- `SolicitudDePrueba` suma `versionTerminos`, `terminosAceptadosEn` (constructor con los dos al final).

**Regla en `recibir`:** después del campo trampa (un bot sigue recibiendo 202) y antes de la idempotencia: si `terminos.version()` está presente, exigir `aceptaTerminos == TRUE` (si no, campo `aceptaTerminos`) y `versionTerminos.strip()` igual a la vigente (si no, campo `versionTerminos`, nombrando la vigente). Guarda versión e instante.

- [ ] Paso 1: IT con `@TestPropertySource(properties = "akine.identity.terminos.version-vigente=2026-11-19")`:
  - `sin_aceptar_es_400`: sin campos → 400, `errors.aceptaTerminos`; nada en `solicitud_de_prueba` con ese email.
  - `version_vieja_es_400`: `aceptaTerminos=true, versionTerminos="2025-01"` → 400, `errors.versionTerminos`.
  - `aceptando_guarda_version_y_fecha`: 202 y la fila tiene `version_terminos='2026-11-19'` y `terminos_aceptados_en` no nulo.
  - `trampa_sigue_en_202`: `sitioWeb` lleno y sin términos → 202, nada guardado.
- [ ] Paso 2: correrlo → falla.
- [ ] Paso 3: implementar.
- [ ] Paso 4: correrlo → verde; también `./mvnw -o test -Dtest="com.akine.organization.**.*SolicitudDePrueba*Test" -Djacoco.skip=true`.
- [ ] Paso 5: commit "Pedido de prueba: exige aceptar los términos vigentes (V32)".

### Tarea 3: reaceptación y bloqueo

**Archivos:**
- Modificar: `identity/domain/AceptacionTerminos.java` (`Origen.REACEPTACION`), `identity/domain/port/AceptacionTerminosRepositoryPort.java`, su adaptador JPA en `identity/infrastructure`, `identity/application/TerminosService.java`, `identity/api/IdentityProblemHandler.java`, `platform/spi/problem/ProblemType.java`
- Crear: `identity/api/MisTerminosController.java`, `identity/api/dto/MisTerminosResponse.java`, `identity/api/dto/AceptarTerminosRequest.java`, `identity/domain/exception/TerminosPendientesException.java`, `identity/infrastructure/TerminosPendientesInterceptor.java`, `identity/infrastructure/TerminosWebConfig.java`
- Test: `src/test/java/com/akine/identity/ReaceptacionDeTerminosIT.java`

**Interfaces:**
- Port: `boolean existsByCuentaIdAndVersion(long cuentaId, String version)`; `Optional<Instant> aceptadaEn(long cuentaId, String version)`.
- `TerminosService`:
  - `record Estado(String versionVigente, String url, boolean pendiente, Instant aceptadaEn)`
  - `Estado estado(long cuentaId)` — sin versión vigente: `pendiente=false`.
  - `boolean pendiente(long cuentaId)` — con caché en memoria `ConcurrentHashMap<Long, String>` de cuentas que ya aceptaron la vigente (append-only: un positivo nunca se invalida; se compara contra la versión vigente, así un cambio de versión por redespliegue lo vacía de hecho).
  - `void aceptar(long cuentaId, String versionTerminos, Instant ahora)` — versión distinta → `TerminosNoAceptadosException("versionTerminos", …)`; ya aceptada → no hace nada; si no, guarda con `Origen.REACEPTACION`. Choque contra el unique en carrera → se trata como ya aceptada.
- `GET /api/v1/me/terminos` (`operationId = getMisTerminos`) → `MisTerminosResponse(versionVigente, url, pendiente, aceptadaEn)`.
- `POST /api/v1/me/terminos` (`operationId = aceptarMisTerminos`), cuerpo `AceptarTerminosRequest(@NotBlank @Size(max=40) String versionTerminos)` → 204. `produces` incluye `application/json` (regla de los 204, ver AGENT.md).
- `ProblemType.TERMINOS_PENDIENTES("terminos-pendientes")`; handler → 403, `title` "Términos pendientes", `detail` "Hay que aceptar la versión vigente de los términos y condiciones para seguir", propiedad `versionVigente`.
- `TerminosPendientesInterceptor.preHandle`: pasa si no hay `AuthenticatedPrincipal`, si `!(handler instanceof HandlerMethod)` o si la ruta empieza con `/api/v1/auth/`, `/api/v1/terms`, `/api/v1/me/terminos` o `/api/v1/public/`; si `terminosService.pendiente(accountId)` lanza `TerminosPendientesException`. Dependencias por `ObjectProvider` (los slices `@WebMvcTest`).

- [ ] Paso 1: IT con la versión prendida por `@TestPropertySource`, sobre una cuenta activa creada por los helpers del IT base, sin fila en `aceptacion_terminos`:
  - `sin_aceptar_cualquier_endpoint_es_403`: `GET /api/v1/me` → 403, `type` termina en `terminos-pendientes`.
  - `puede_entrar_y_salir`: login → 200; refresh → 200; logout → 204.
  - `mis_terminos_dice_pendiente`: `GET /api/v1/me/terminos` → 200, `pendiente=true`, `versionVigente="2026-11-19"`.
  - `aceptar_destraba`: `POST` con la vigente → 204; fila `origen='REACEPTACION'`; `GET /api/v1/me` → 200; segundo `POST` → 204, sigue una sola fila.
  - `otra_version_es_400`: `POST` con `"2025-01"` → 400, `errors.versionTerminos`.
  - `publico_no_se_bloquea`: `GET /api/v1/terms` sin token → 200.
- [ ] Paso 2: correrlo → falla.
- [ ] Paso 3: implementar.
- [ ] Paso 4: correrlo → verde; `TerminosIT` → verde; ArchUnit → verde.
- [ ] Paso 5: commit "Reaceptación de términos: GET/POST /me/terminos y bloqueo 403 terminos-pendientes".

### Tarea 4: contrato 0.183.0

- [ ] Paso 1: subir `akine.contract.version` en `pom.xml` y `info.version` donde se declare a `0.183.0`.
- [ ] Paso 2: `./mvnw verify -Dakine.contract.update=true -Dit.test=OpenApiContractIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true` → `openapi/akine-api.yaml` con `getMisTerminos`, `aceptarMisTerminos`, los dos campos del pedido y `terminos-pendientes`.
- [ ] Paso 3: sumar §"Cómo quedó" al diseño. Commit "Contrato 0.183.0: términos obligatorios".

### Tarea 5: web

**Archivos (`wt-WEB-TERMINOS`):**
- Regenerar: `npm run api:generate` contra `../wt-API-TERMINOS/openapi/akine-api.yaml` (ajustar la ruta si el script la fija); `contractVersion` `0.183.0` en los dos `environment`.
- Modificar: `features/landing/components/pedido-de-prueba/pedido-de-prueba.{ts,html}` — `leerTerminosVigentes()` y `<app-terminos-del-alta prefijo="pedido">` antes del botón; `...terminos.campos()` en el cuerpo; envío bloqueado con `terminos.faltaAceptar()` / `impidenElAlta()`; rechazo por términos con `esRechazoDeTerminos` como en `register-page.ts`. `TerminosVigentes` vive en `features/auth/models`: moverlo a `shared/` o a `core/models` si el lint de imports entre features lo exige (mismo para `TerminosDelAlta`).
- Crear: `core/services/terminos-pendientes.store.ts` — `pendiente = signal<boolean | null>(null)`; `cargar(): Observable<boolean>` (`getMisTerminos`); `marcarPendiente()`; `aceptar(version): Observable<void>`; se resetea al cerrar sesión.
- Modificar: `core/guards/auth.guard.ts` — con sesión, si el store dice pendiente (cargándolo una vez) → `createUrlTree(['/aceptar-terminos'], { queryParams: { volverA } })`.
- Modificar: `core/interceptors/error.interceptor.ts` — ante 403 `terminos-pendientes`: `store.marcarPendiente()` y navegar a `/aceptar-terminos` (salvo que ya esté ahí).
- Crear: `features/auth/pages/aceptar-terminos/aceptar-terminos-page.{ts,html}` y la ruta `aceptar-terminos` en `app.routes.ts` (sólo `authGuard` no: usa una variante sin el chequeo de términos para no hacer bucle). Contenido: título "Actualizamos los términos y condiciones", enlace al texto (`url` de `/me/terminos`), casilla "Leí y acepto los términos y condiciones (versión X)", botón primario "Aceptar y seguir" (deshabilitado sin casilla), botón secundario "Cerrar sesión". Al aceptar vuelve a `volverA` o a la entrada habitual.
- Modificar: `.github/workflows/ci.yml` — `AKINE_TERMINOS_VERSION: ""` en el backend del job E2E.

- [ ] Paso 1: implementar.
- [ ] Paso 2: `npx ng test --watch=false --no-coverage --include "src/app/features/landing/components/pedido-de-prueba/**/*.spec.ts"` y los specs de `core/guards` y `core/interceptors` tocados → verde (ajustar los que esperaban el formulario sin casilla).
- [ ] Paso 3: `npm run lint` y `npx prettier@3.10.1 --check` sobre lo tocado.
- [ ] Paso 4: commits "Pedido de prueba con términos", "Reaceptación de términos: guard, interceptor y pantalla", "Cliente 0.183.0".

### Tarea 6: prueba en la app real

- [ ] Levantar backend del worktree (base copia de `akine_main`, puerto propio) y web en `127.0.0.1`.
- [ ] Landing: pedir prueba sin casilla (error en la casilla) y con casilla (202, fila con versión).
- [ ] Ingresar con una cuenta existente: aparece `/aceptar-terminos`; aceptar → entra; volver a ingresar → no aparece.
- [ ] Cambiar `AKINE_TERMINOS_VERSION`, reiniciar el backend con la sesión abierta: el siguiente clic lleva a la pantalla.
- [ ] Capturas en claro y oscuro, 1440 y 375 px.
