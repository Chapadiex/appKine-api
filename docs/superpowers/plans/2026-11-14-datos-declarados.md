# Datos declarados de la HC — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que la secretaria (y el profesional) carguen en la historia clínica antecedentes y archivos que declara el paciente, marcados "declarado · sin revisar", y que el profesional los revise.

**Architecture:** columnas de origen y revisión en `historia_clinica_antecedente` y `adjunto_clinico` (V16); permiso nuevo `hc:declarar`; un servicio `DatosDeclaradosService` en `clinical.application` que reusa los servicios de antecedentes y adjuntos con el origen como parámetro; un controlador por persona. En el frontend, una pantalla del feature `clinical` montada en `/pacientes/:personaId/datos-declarados`, marcas y botón "Revisado" en la HC, y "Cargar datos" en el padrón.

**Tech Stack:** Java 21 · Spring Boot 4.1 · PostgreSQL 18 · Flyway · springdoc / Angular 22 zoneless + signals · cliente generado openapi-generator.

**Spec:** `docs/diseno/AKINE-datos-declarados.md` (aprobado el 14/11/2026). Leerlo entero antes de la tarea 1.

## Global Constraints

- Backend en `appKine-api`, rama **`akine-servicio-personalizado`** (ya trae V15 y contrato 0.171.0 sin commitear: no tocarlos). Frontend en `appKine-web`, rama **`akine-web-estetica-3`**. Sin worktrees nuevos.
- **Sin commits, push ni PR** hasta que el usuario lo pida (preferencia del usuario). Donde la plantilla diría "Commit", acá no hay paso.
- **Pocos tests, sin TDD** (`CLAUDE.md` §4 de cada repo): sólo los que fija cada tarea. Correr únicamente esos, con los comandos de la tarea. La suite completa es del CI.
- Migración **`V16__datos_declarados.sql`**. Contrato **0.172.0** (minor aditiva) en `pom.xml` (`akine.contract.version`) y `openapi/akine-api.yaml`; `contractVersion` igual en los dos `environment*.ts` del web.
- Permiso **`hc:declarar`**. Decisión registrada como **DP-47** en `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7 (confirmar que 47 sigue libre: `grep -o "DP-[0-9]*"`).
- Valores: `origen` ∈ `PROFESIONAL`, `DECLARADO` (default `PROFESIONAL`). Categorías declarables: `ESTUDIO`, `INFORME`, `IMAGEN`, `OTRO`. Problem types nuevos: `dato-declarado-revisado`, `dato-no-declarado`, `adjunto-ya-en-historia`. Eventos: `ANTECEDENTE_DECLARADO`, `ADJUNTO_DECLARADO`, `DATO_DECLARADO_DADO_DE_BAJA`, `DATO_DECLARADO_REVISADO`.
- Nada de contenido clínico (descripción, título, nombre de archivo) en los detalles de `audit_event`: el mismo criterio que `AntecedenteClinicoService.registrar` y `AdjuntoClinicoService.contenido`.
- Texto sugerido del motivo: `Carga de datos que declara el paciente`.
- UI: norma global de `appKine-web/DESIGN.md` (tokens, estado = color + texto + ícono, claro/oscuro, 375 px sin desborde).

## Review Focus

1. **La secretaria pide por id un adjunto o antecedente `PROFESIONAL`** (por `verContenidoDeAdjuntoDeclarado` o `darDeBajaDatoDeclarado`) → 404, nunca 200 ni 409 que confirme que existe. Test en la tarea 5.
2. **Persona de otra organización o sin perfil de paciente vigente** → 404 / 409 `persona-sin-perfil-paciente`, sin abrir historia. Test en la tarea 5.
3. **La secretaria sube un archivo que el profesional ya cargó** → 409 `adjunto-ya-en-historia` sin datos del existente; si ya estaba como declarado, 200 con ese mismo. Test en la tarea 3.
4. **Doble click en "Revisado"** o dos profesionales a la vez → idempotente, la versión avanza una sola vez. Test en la tarea 4.
5. **La secretaria con `hc:declarar` intenta leer la HC** (`GET /historias-clinicas/por-persona/{id}`, antecedentes, adjuntos, timeline) → 403, igual que hoy. Test en la tarea 5.

---

## Archivos

**appKine-api** (`src/main/java/com/akine/…`)

| Archivo | Qué |
|---|---|
| `organization/domain/PermissionCode.java`, `RolePermissions.java` | `HC_DECLARAR` y su base |
| `clinical/domain/PermissionCodes.java` | constante `HC_DECLARAR` |
| `resources/db/migration/V16__datos_declarados.sql` | columnas y CHECK |
| `clinical/domain/OrigenDato.java` (nuevo) | enum |
| `clinical/domain/AntecedenteClinico.java`, `AdjuntoClinico.java` | campos y `estaDeclaradoSinRevisar()` |
| `clinical/infrastructure/AntecedenteClinicoRepository.java`, `AdjuntoClinicoRepository.java` | UPDATE condicionado de revisión, listados por origen, conteo |
| `clinical/domain/exception/{DatoDeclaradoRevisado,DatoNoDeclarado,AdjuntoYaEnHistoria}Exception.java` (nuevos) | 409 |
| `platform/spi/problem/ProblemType.java`, `clinical/api/ClinicalProblemHandler.java` | mapeo |
| `clinical/application/AuditEvents.java` | cuatro eventos |
| `clinical/application/AntecedenteClinicoService.java`, `AdjuntoClinicoService.java` | origen como parámetro, `revisar` |
| `clinical/application/DatosDeclaradosService.java`, `DatosDeclaradosView.java` (nuevos) | casos de uso de declarados |
| `clinical/application/HistoriaClinicaService.java`, `HistoriaClinicaView.java` | `declaradosSinRevisar` |
| `clinical/api/DatosDeclaradosController.java` (nuevo), `HistoriaClinicaController.java`, `AdjuntoClinicoController.java` | rutas |
| `clinical/api/dto/*` | campos nuevos y `DatosDeclaradosResponse` |
| tests: `organization/domain/RolePermissionsTest.java`, `clinical/DatosDeclaradosIT.java` (nuevo) | |

**appKine-web** (`src/app/…`)

| Archivo | Qué |
|---|---|
| `api/generated/**` | regenerado |
| `core/models/permisos.ts` | `PERMISO_HC_DECLARAR` |
| `core/models/cabecera-de-persona.ts` | pantalla `'declarados'` y su pestaña |
| `app.routes.ts` | `pacientes/:personaId/datos-declarados` antes de `pacientes` |
| `features/clinical/datos-declarados.routes.ts`, `features/clinical/pages/datos-declarados/*` (nuevos) | pantalla |
| `features/clinical/pages/historia-clinica/components/visor-de-adjunto.ts`, `nuevo-adjunto.ts` | fuente de descarga/subida inyectable |
| `features/clinical/pages/historia-clinica/*` | aviso, marca y "Revisado" |
| `features/person/pages/padron-de-personas/*` | "Cargar datos" |
| traductores de problem types en `core/` | los tres nuevos |

---

### Task 1: Permiso `hc:declarar` y DP-47

**Files:**
- Modify: `organization/domain/PermissionCode.java` (junto a `HC_WRITE`, ~l.193), `organization/domain/RolePermissions.java` (bases de `CONSULTORIO_ADMIN` ~l.345, `PROFESIONAL` ~l.423, `ADMINISTRATIVO` ~l.474), `clinical/domain/PermissionCodes.java`
- Modify: `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7, `docs/seguridad/matriz-permisos-minima.md`
- Test: `src/test/java/com/akine/organization/domain/RolePermissionsTest.java`

**Interfaces:**
- Produces: `PermissionCode.HC_DECLARAR("hc:declarar")`; `clinical.domain.PermissionCodes.HC_DECLARAR = "hc:declarar"`.

- [ ] **Step 1:** Agregar `HC_DECLARAR` al enum y a las bases con `PermissionScope.CONSULTORIO` para `ADMINISTRATIVO`, `PROFESIONAL` y `CONSULTORIO_ADMIN`. **No** va en `GRANT_POR_ROL` (no otorgable: `otorgableComoGrant(ORG_ADMIN|PACIENTE, HC_DECLARAR)` da `false` por la regla existente). Comentario corto citando DP-47.
- [ ] **Step 2:** Si el catálogo de permisos tiene otra fuente (grep `"hc:write"` en `src/main` y `resources`; p.ej. etiquetas para la pantalla de permisos o el contrato), sumar `hc:declarar` en cada una.
- [ ] **Step 3:** Escribir DP-47 en §7 del plan (texto del §3 del diseño: el `ADMINISTRATIVO` puede declarar antecedentes y adjuntos del paciente en la HC, sin leerla; lo declarado lo revisa quien tiene `hc:write`) y la fila en la matriz mínima.
- [ ] **Step 4: Test** en `RolePermissionsTest`:

```java
@Test void hcDeclarar_esBaseDeAdministrativoProfesionalYConsultorioAdmin() {
  for (RoleCode r : List.of(ADMINISTRATIVO, PROFESIONAL, CONSULTORIO_ADMIN))
    assertThat(RolePermissions.baseOf(r)).containsEntry(PermissionCode.HC_DECLARAR, PermissionScope.CONSULTORIO);
}
@Test void hcDeclarar_noOtorgableAOrgAdminNiPaciente() {
  assertThat(RolePermissions.otorgableComoGrant(ORG_ADMIN, PermissionCode.HC_DECLARAR)).isFalse();
  assertThat(RolePermissions.otorgableComoGrant(PACIENTE, PermissionCode.HC_DECLARAR)).isFalse();
}
@Test void hcDeclarar_noDaHcReadAlAdministrativo() {
  assertThat(RolePermissions.baseOf(ADMINISTRATIVO)).doesNotContainKey(PermissionCode.HC_READ);
}
```

- [ ] **Step 5:** `./mvnw -o test -Dtest="com.akine.organization.domain.RolePermissionsTest" -Djacoco.skip=true` → PASS. Si algún test de la matriz que enumera permisos por rol falla, actualizar su expectativa (es el cambio buscado).

---

### Task 2: V16, dominio y repositorios

**Files:**
- Create: `src/main/resources/db/migration/V16__datos_declarados.sql`, `clinical/domain/OrigenDato.java`
- Modify: `clinical/domain/AntecedenteClinico.java`, `clinical/domain/AdjuntoClinico.java`, `clinical/infrastructure/AntecedenteClinicoRepository.java`, `clinical/infrastructure/AdjuntoClinicoRepository.java` (y el puerto de dominio si el repo lo implementa)

**Interfaces:**
- Produces:
  - `enum OrigenDato { PROFESIONAL, DECLARADO }`
  - En ambas entidades: `OrigenDato getOrigen()`, `Long getRevisadoPor()`, `Instant getRevisadoEn()`, `boolean estaDeclaradoSinRevisar()`; constructor/fábrica que acepte `OrigenDato` (el constructor actual queda y usa `PROFESIONAL`).
  - Repos: `int marcarRevisado(long organizationId, long id, long revisadoPor, Instant revisadoEn)` (UPDATE nativo `SET revisado_por=…, revisado_en=…, version = version + 1 WHERE organization_id=… AND id=… AND origen='DECLARADO' AND revisado_en IS NULL`; sin `version` si la tabla no la tiene); `List<…> buscarDeclaradosVigentesPorHistoria(long organizationId, long historiaId)`; `long contarDeclaradosSinRevisar(long organizationId, long historiaId)`.

- [ ] **Step 1:** Migración según §5 del diseño, para las dos tablas. Nombres de constraint con la tabla adelante: `ck_hc_antecedente_origen`, `ck_hc_antecedente_revision_solo_declarado`, `ck_hc_antecedente_revision_coherente`, y `ck_adjunto_clinico_origen`, `…_revision_solo_declarado`, `…_revision_coherente`. Índice parcial por historia para el conteo: `… (organization_id, historia_clinica_id) WHERE origen = 'DECLARADO' AND revisado_en IS NULL AND active`.
- [ ] **Step 2:** Campos en las entidades: `origen` con `@Enumerated(STRING)`, `updatable = false`; `revisado_por` / `revisado_en` sólo los escribe el UPDATE nativo, así que **`@DynamicUpdate`** en las dos entidades (regla de los UPDATE nativos, `CLAUDE.md` del api §7) o `insertable/updatable = false` en esas dos columnas.
- [ ] **Step 3:** Métodos de repositorio de la sección Interfaces. El UPDATE nativo con `@Modifying(clearAutomatically = false)` y sin desacoplar otras entidades cargadas.
- [ ] **Step 4:** `./mvnw -o test-compile -Djacoco.skip=true` → BUILD SUCCESS. La migración se prueba en el IT de la tarea 5.

---

### Task 3: Declarar, ver, abrir y dar de baja (`DatosDeclaradosService`)

**Files:**
- Create: `clinical/application/DatosDeclaradosService.java`, `clinical/application/DatosDeclaradosView.java`, `clinical/domain/exception/DatoDeclaradoRevisadoException.java`, `AdjuntoYaEnHistoriaException.java`, `DatoNoDeclaradoException.java`
- Modify: `clinical/application/AntecedenteClinicoService.java`, `AdjuntoClinicoService.java`, `AuditEvents.java`, `AntecedenteView.java`, `AdjuntoClinicoView.java`, `platform/spi/problem/ProblemType.java`, `clinical/api/ClinicalProblemHandler.java`

**Interfaces:**
- Consumes: tarea 2; `HistoriaClinicaService.asegurar(long organizationId, long personaId, long actorAccountId)`; `AutorizacionClinica.exigirSobrePersona(…, PermissionCodes.HC_DECLARAR, personaId, justificacion, accion)`; `AccountIdentityDirectory.identidadesDe(Collection<Long>)`.
- Produces (`DatosDeclaradosService`, todos con `OperatingActor actor, long personaId, …, String justificacion`):
  - `DatosDeclaradosView ver(actor, personaId, justificacion)` — `record DatosDeclaradosView(Long historiaClinicaId, List<AntecedenteView> antecedentes, List<AdjuntoClinicoView> adjuntos)`
  - `AntecedenteView declararAntecedente(actor, personaId, TipoAntecedente tipo, String descripcion, justificacion)`
  - `AdjuntoClinicoAlta declararAdjunto(actor, personaId, AdjuntoClinicoAltaCommand command, justificacion)` — **sin** `@Transactional`
  - `ContenidoDeAdjuntoClinico contenidoDeclarado(actor, personaId, long adjuntoId, justificacion)`
  - `void darDeBaja(actor, personaId, TipoDatoDeclarado tipo, long id, String motivo, justificacion)` con `enum TipoDatoDeclarado { ANTECEDENTES, ADJUNTOS }`
  - `AntecedenteView` y `AdjuntoClinicoView` suman `OrigenDato origen, String cargadoPorNombre, String revisadoPorNombre, Instant revisadoEn` (nombres resueltos en lote por quien arma la lista, como `SesionService` con `SesionVersionView`; nombre ausente = `null`).
  - `ProblemType.DATO_DECLARADO_REVISADO("dato-declarado-revisado")`, `DATO_NO_DECLARADO("dato-no-declarado")`, `ADJUNTO_YA_EN_HISTORIA("adjunto-ya-en-historia")`, los tres 409.

- [ ] **Step 1:** En `AntecedenteClinicoService`, extraer el cuerpo de `registrar` a un método de paquete `AntecedenteView registrar(OperatingActor, long personaId, TipoAntecedente, String, String justificacion, OrigenDato origen)`: con `DECLARADO` exige `HC_DECLARAR`, usa `historiaClinicaService.asegurar(...)` en vez de exigir historia existente, guarda con origen `DECLARADO` y audita `ANTECEDENTE_DECLARADO`. El público de hoy delega con `PROFESIONAL`, sin cambiar conducta.
- [ ] **Step 2:** En `AdjuntoClinicoService`, sobrecarga de paquete `subir(actor, historiaClinicaId, command, justificacion, OrigenDato origen)`. En `prepararSubida`, con `DECLARADO`: permiso `HC_DECLARAR`; categoría fuera de `ESTUDIO|INFORME|IMAGEN|OTRO` → la `IllegalArgumentException`/400 de validación que ya usa el servicio; si `buscarVigentePorChecksum` encuentra un existente `PROFESIONAL` → `AdjuntoYaEnHistoriaException` (mensaje sin id ni título del existente); si es `DECLARADO` → idempotente como hoy. Audita `ADJUNTO_DECLARADO`. El público delega con `PROFESIONAL`.
- [ ] **Step 3:** En `AdjuntoClinicoService`, método de paquete `contenido(actor, historiaClinicaId, adjuntoId, justificacion, OrigenDato soloOrigen)`: con `DECLARADO` exige `HC_DECLARAR` y trata un adjunto `PROFESIONAL` como inexistente (`AdjuntoClinicoNotAccessibleException`, 404). Audita el mismo `ADJUNTO_CLINICO_DOWNLOADED`.
- [ ] **Step 4:** `DatosDeclaradosService`:
  - `ver`: autoriza con `HC_DECLARAR`; sin historia vigente devuelve `historiaClinicaId = null` y listas vacías; con historia, sólo `DECLARADO` vigentes, más recientes primero. Audita lectura como acceso clínico.
  - `declararAdjunto`: `asegurar` en su transacción (bean aparte o `TransactionTemplate`), después `adjuntos.subir(..., DECLARADO)` (orden del §7 "Transacciones" del diseño).
  - `contenidoDeclarado`: resuelve la historia vigente de la persona (sin historia → 404) y delega en el paso 3.
  - `darDeBaja`: sólo `DECLARADO` vigente de esa persona; `PROFESIONAL` o inexistente → 404 (`AntecedenteNotAccessibleException` / `AdjuntoClinicoNotAccessibleException`); revisado → `DatoDeclaradoRevisadoException`. Reusa la baja lógica existente de cada servicio (motivo obligatorio) y audita `DATO_DECLARADO_DADO_DE_BAJA`.
- [ ] **Step 5:** Mapear las tres excepciones a 409 en `ClinicalProblemHandler` (no en `GlobalExceptionHandler`). Sumar los cuatro eventos en `AuditEvents`.
- [ ] **Step 6:** `./mvnw -o test -Dtest="com.akine.clinical.**.*Test" -Djacoco.skip=true` → PASS (lo existente de antecedentes y adjuntos sigue verde: la conducta `PROFESIONAL` no cambia). Los unitarios que construyan `AntecedenteView`/`AdjuntoClinicoView` se ajustan a la firma nueva.

---

### Task 4: Revisión y conteo en la HC

**Files:**
- Modify: `AntecedenteClinicoService.java`, `AdjuntoClinicoService.java`, `HistoriaClinicaService.java`, `HistoriaClinicaView.java`, `clinical/api/dto/HistoriaClinicaResponse.java`

**Interfaces:**
- Produces:
  - `AntecedenteView AntecedenteClinicoService.revisar(OperatingActor actor, long personaId, long antecedenteId, String justificacion)`
  - `AdjuntoClinicoView AdjuntoClinicoService.revisar(OperatingActor actor, long historiaClinicaId, long adjuntoId, String justificacion)`
  - `HistoriaClinicaView.declaradosSinRevisar()` (`long`), suma de los dos conteos de la tarea 2.

- [ ] **Step 1:** `revisar` (los dos): `@Transactional`, exige `HC_WRITE`; carga el dato vigente de esa historia (si no, 404 de siempre); `PROFESIONAL` → `DatoNoDeclaradoException`; llama `marcarRevisado`; 0 filas = ya revisado → devuelve el estado actual sin error ni evento; 1 fila → audita `DATO_DECLARADO_REVISADO` (detalles: `tipoDato`, `historiaClinicaId`). Relee después del UPDATE para devolver la versión nueva.
- [ ] **Step 2:** `HistoriaClinicaService.ver` y `abrirOObtener` llenan `declaradosSinRevisar`; `HistoriaClinicaResponse` lo publica.
- [ ] **Step 3:** `./mvnw -o test-compile -Djacoco.skip=true` → BUILD SUCCESS. La conducta se prueba en el IT de la tarea 5.

---

### Task 5: API, contrato 0.172.0 e IT

**Files:**
- Create: `clinical/api/DatosDeclaradosController.java`, `clinical/api/dto/DatosDeclaradosResponse.java`, `clinical/api/dto/DeclararAntecedenteRequest.java` (o reusar `RegistrarAntecedenteRequest`), `src/test/java/com/akine/clinical/DatosDeclaradosIT.java`
- Modify: `HistoriaClinicaController.java`, `AdjuntoClinicoController.java`, `AntecedenteResponse.java`, `AdjuntoClinicoResponse.java`, `pom.xml`, `openapi/akine-api.yaml`

**Interfaces:**
- Consumes: tareas 3 y 4.
- Produces (rutas bajo `/api/v1`, header `X-Justificacion-Acceso` como en `HistoriaClinicaController`):

| operationId | Método y ruta | Respuesta |
|---|---|---|
| `verDatosDeclarados` | `GET /personas/{personaId}/datos-declarados` | 200 `DatosDeclaradosResponse { historiaClinicaId?, antecedentes[], adjuntos[] }` |
| `declararAntecedente` | `POST /personas/{personaId}/datos-declarados/antecedentes` | 201 `AntecedenteResponse` |
| `declararAdjunto` | `POST /personas/{personaId}/datos-declarados/adjuntos` multipart (`archivo`, `categoria`, `titulo`) | 201 creado / 200 existente, `AdjuntoClinicoResponse` |
| `verContenidoDeAdjuntoDeclarado` | `GET /personas/{personaId}/datos-declarados/adjuntos/{adjuntoId}/contenido` | 200 binario, mismas cabeceras que `AdjuntoClinicoController.descargar` |
| `darDeBajaDatoDeclarado` | `POST /personas/{personaId}/datos-declarados/{tipoDato}/{id}/baja` (`{motivo}`) | 204 |
| `revisarAntecedente` | `PUT /historias-clinicas/por-persona/{personaId}/antecedentes/{antecedenteId}/revision` | 200 `AntecedenteResponse` |
| `revisarAdjunto` | `PUT /historias-clinicas/{historiaClinicaId}/adjuntos/{adjuntoId}/revision` | 200 `AdjuntoClinicoResponse` |

  - `AntecedenteResponse` y `AdjuntoClinicoResponse` suman `origen` (enum `PROFESIONAL|DECLARADO`), `cargadoPorNombre`, `revisadoPorNombre`, `revisadoEn` (opcionales).

- [ ] **Step 1:** Controlador nuevo con `produces` que incluya `application/json` además de `application/problem+json` (regla 15 del workspace: el 204 de la baja muere en 406 si no). Copiar de `AdjuntoClinicoController` la armadura del multipart y de la descarga segura (tipo por bytes, `Content-Disposition`, `nosniff`); extraer a un helper compartido si es más de unas líneas.
- [ ] **Step 2:** Rutas de revisión en los dos controladores existentes. Declarar los 403/404/409 nuevos en las anotaciones springdoc de cada operación.
- [ ] **Step 3:** `pom.xml` → `akine.contract.version` 0.172.0. Regenerar: `./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=OpenApiContractIT -Dakine.contract.update=true -Djacoco.skip=true` (Docker arriba). Verificar con `grep -cE '^\s+operationId:' openapi/akine-api.yaml` que suben 7 y que ningún `operationId` tiene sufijo numérico.
- [ ] **Step 4: IT** `DatosDeclaradosIT` contra PostgreSQL, por HTTP, con un `ADMINISTRATIVO` (secretaria) y un `PROFESIONAL` de la misma sede (seguir el armado de `HistoriaClinicaApiIT` / `AdjuntoClinicoIT`):

```java
// migración y CHECK
@Test void checkImpideRevisionEnDatoProfesional()          // INSERT directo origen=PROFESIONAL con revisado_en → viola ck_*_revision_solo_declarado
// flujo
@Test void secretariaDeclaraSinHistoria_laAbreYVeSoloLoDeclarado()   // GET → historiaClinicaId null; POST antecedente → 201 origen=DECLARADO; GET → 1 antecedente, 0 del profesional
@Test void secretariaNoLeeLaHistoria()                      // GET /historias-clinicas/por-persona/{id} y /antecedentes → 403
@Test void personaSinPerfilPaciente_409()                   // persona_sin_perfil → 409 persona-sin-perfil-paciente, ninguna fila en historia_clinica
@Test void otraOrganizacion_404()
@Test void adjuntoDelProfesional_invisibleParaSecretaria()   // contenido y baja por id del adjunto PROFESIONAL → 404
@Test void mismoArchivo_quecargoElProfesional_409SinDatos()  // 409 adjunto-ya-en-historia; cuerpo sin id ni titulo del existente
@Test void mismoArchivoDeclaradoDosVeces_idempotente()       // segundo POST → 200, mismo id
@Test void categoriaNoDeclarable_400()                       // CONSENTIMIENTO_CLINICO → 400
@Test void secretariaAbreSuArchivoDeclarado_revisadoONo()    // 200 con los bytes antes y después de revisar
@Test void bajaDeRevisado_409()                              // revisar como profesional; baja como secretaria → 409 dato-declarado-revisado
@Test void revisarDosVeces_idempotente()                     // dos PUT → 200 las dos; revisado_en igual; version avanzó una vez; un solo DATO_DECLARADO_REVISADO
@Test void revisarDatoProfesional_409()                      // 409 dato-no-declarado
@Test void historiaInformaDeclaradosSinRevisar()             // 2 declarados → declaradosSinRevisar=2; revisar uno → 1
```

- [ ] **Step 5:** `./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=DatosDeclaradosIT -Djacoco.skip=true` → PASS. Correr también `-Dtest="com.akine.architecture.**"` (ArchUnit) → PASS.

---

### Task 6: Frontend — cliente, permiso y pestaña

**Files:**
- Modify: `src/app/api/generated/**` (regenerado), `src/environments/environment*.ts`, `core/models/permisos.ts`, `core/models/cabecera-de-persona.ts`, el traductor de problem types de `core/` (grep `adjunto-clinico-inactivo`)

**Interfaces:**
- Produces: `PERMISO_HC_DECLARAR = 'hc:declarar'`; `PantallaDePersona` suma `'declarados'`; la pestaña `{ etiqueta: 'Datos declarados', ruta: '/pacientes/:id/datos-declarados' }`, después de "Historia clínica", visible si `paciente && (actual === 'declarados' || tiene(PERMISO_HC_DECLARAR))`.

- [ ] **Step 1:** `npm run api:generate`; `contractVersion` → `0.172.0`; `npm run api:check` → alineado.
- [ ] **Step 2:** Permiso, pestaña y textos de los tres problem types: `dato-declarado-revisado` → "Ya lo revisó un profesional: sólo él puede darlo de baja desde la historia."; `dato-no-declarado` → "Ese dato no es declarado: no lleva revisión."; `adjunto-ya-en-historia` → "Ese archivo ya está en la historia clínica."
- [ ] **Step 3:** `npx tsc -p tsconfig.app.json --noEmit` → sin errores.

---

### Task 7: Frontend — pantalla "Datos declarados"

**Files:**
- Create: `features/clinical/datos-declarados.routes.ts`, `features/clinical/pages/datos-declarados/datos-declarados-page.{ts,html,css}`
- Modify: `app.routes.ts` (nueva entrada **antes** de `pacientes`, igual que `pacientes/:personaId/cuenta-corriente`), `features/clinical/pages/historia-clinica/components/visor-de-adjunto.ts`, `nuevo-adjunto.ts`

**Interfaces:**
- Consumes: tarea 6; servicio generado de declarados.
- Produces:
  - `visor-de-adjunto`: input opcional `descarga = input<(() => Observable<Blob>) | null>(null)`; si viene, la usa en vez de `api.descargar(historiaClinicaId, adjuntoId, …)`.
  - `nuevo-adjunto`: input opcional `subir = input<((archivo: File, categoria: string, titulo: string) => Observable<AdjuntoClinicoResponse>) | null>(null)` y `categorias = input<readonly string[] | null>(null)` (limita el select).

- [ ] **Step 1:** Ruta `pacientes/:personaId/datos-declarados` con `canActivate: [authGuard]` y `children` que montan la página con `contextGuard` y `permissionGuard(PERMISO_HC_DECLARAR)`.
- [ ] **Step 2:** Página según §8.1 del diseño: `app-cabecera-de-pagina` con la cabecera de persona (`actual: 'declarados'`); pedido de motivo con el texto sugerido (reusar `components/pedido-de-motivo.ts`); sección Antecedentes con alta en línea (select de `TipoAntecedente` + descripción) y tabla; sección "Estudios y documentos" con `app-nuevo-adjunto` (categorías `ESTUDIO, INFORME, IMAGEN, OTRO`) y tabla cuyo título abre `app-visor-de-adjunto` con `descarga` del endpoint de declarados; estado por fila con pastilla color + texto + ícono ("Sin revisar" / "Revisado · nombre, fecha"); menú "⋯" sólo en sin revisar con "Dar de baja" (`confirmacion-con-motivo`); 409 `persona-sin-perfil-paciente` → estado vacío con enlace a la ficha. Estado en signals.
- [ ] **Step 3:** Verificar en `http://localhost:4200` (ya levantado; si no, `preview_start` con la configuración de 4200) como `qa.administrativo` de `appKine-api/.claude/qa-config.md`: alta de antecedente, subida, apertura del archivo, baja; claro y oscuro, 1440 y 375 px sin desborde; consola sin errores.

---

### Task 8: Frontend — HC del profesional y padrón

**Files:**
- Modify: `features/clinical/pages/historia-clinica/historia-clinica-page.{ts,html}` y el componente que lista adjuntos/antecedentes; `features/person/pages/padron-de-personas/padron-de-personas-page.{ts,html}`

**Interfaces:**
- Consumes: `HistoriaClinicaResponse.declaradosSinRevisar`, `origen`/`revisadoPorNombre`/`revisadoEn` en las respuestas, `revisarAntecedente`, `revisarAdjunto`.

- [ ] **Step 1:** HC: aviso arriba si `declaradosSinRevisar > 0` ("N datos declarados por el paciente sin revisar") con enlace a la sección; marca "Declarado · sin revisar" / "Declarado · revisado por X" en cada `DECLARADO`; botón **Revisado** (sólo con `hc:write`) que llama a la revisión y reemplaza la fila con la respuesta; el contador baja sin recargar.
- [ ] **Step 2:** Padrón: en la columna Historia clínica, rama nueva antes de "Sin historia": `persona.esPaciente && tiene(PERMISO_HC_DECLARAR) && !puedeVerHistoria()` → enlace "Cargar datos" a `/pacientes/{id}/datos-declarados` (mismo estilo que "Abrir HC", ícono `agregar`, texto oculto con el nombre).
- [ ] **Step 3:** Verificar en 4200: como `qa.profesional`, aviso y revisión de lo que cargó la secretaria en la tarea 7; como `qa.administrativo`, "Cargar datos" en el padrón y ningún enlace a la HC. Captura de pantalla de cada uno para el usuario.

---

## Lo que queda fuera

- Arreglar los specs y E2E del web que rompió el padrón: va antes del PR final, no en este plan.
- Commits y PR: cuando el usuario lo pida, uno por repo.
