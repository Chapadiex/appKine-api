# AGENT.md — appKine-api

Lineamientos técnicos y de negocio del backend de AKINE. **Leer completo antes de cualquier tarea.**

---

## 1. Propósito

Backend de AKINE: SaaS multi-tenant para centros de kinesiología, fisioterapia, rehabilitación
y actividades de salud/bienestar. Cubre el circuito clínico, administrativo y económico:
turnos, check-in, sesiones, historia clínica, coberturas, convenios, obligaciones económicas,
cobros, caja, presentaciones a financiadores y reportes.

Es la **autoridad** del sistema: permisos, tenant, estados y reglas de negocio se resuelven
acá. El frontend nunca puede elevar privilegios ni saltear una validación.

## 2. Documentación canónica

Vive en este repo, en `docs/producto/` (hasta el 29/09/2026 estaba fuera de todo git, en el workspace padre):

- `docs/producto/AKINE_Requerimientos_Integrados.md` — **fuente de verdad funcional** M01–M29 + §30–45.
- `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` — **fuente de verdad de arquitectura y roadmap**, DP-01…DP-09.
- `docs/producto/arquitectura-java-angular21.md` — referencia de layout Spring Boot.
- Documentos históricos (`AKINE_info.txt`, `AkinePN.docx`, UML 2019): **antecedente de negocio únicamente.**

Convenciones de identificadores en la especificación:
`RF-MXX-NNN` requerimiento funcional · `RN-MXX-NNN` regla de negocio ·
`RNF-MXX-NNN` requerimiento no funcional · `CA-MXX-NNN-YY` criterio de aceptación.

Todo cambio debe trazarse a un RF/RN concreto. Si no hay RF que lo respalde, no se implementa
sin decisión explícita del usuario.

**Decisiones ya tomadas — leer antes de rediscutir cualquiera:**

| Dónde | Qué |
|---|---|
| `docs/adr/0001`–`0007` | Decisiones técnicas del baseline (módulos y `spi`, contrato OpenAPI, Flyway, persistencia multi-tenant, errores, tests de arquitectura, expandir–migrar–contraer) |
| `docs/adr/0008`–`0016` | Decisiones de producto `DP-01`–`DP-09` formalizadas (onboarding compuesto, identidad única con contexto, alcance de la HC, series de turnos, máquinas de estado separadas, prepago configurable, alcance MVP, requisitos clínico-legales, versiones y SLO) |
| `docs/seguridad/matriz-permisos-minima.md` | Roles, matriz de §32, semántica ejecutable de cada valor, catálogo de permisos e invariantes. **Vinculante** para toda evaluación de permiso |

Un ADR aceptado no se edita: se supersede con uno nuevo. La spec es deliberadamente abstracta
en varios puntos (estados de suscripción y membership, TTLs, lockout, política de contraseñas,
outbox): lo que no está en la spec se resuelve como **decisión documentada** (§44 del
documento de requerimientos), nunca se inventa en silencio.

### Retomar el trabajo: `docs/fases/`

**Antes de tocar una etapa, leé la ficha de su fase en `docs/fases/`.** Esa carpeta sale de la
auditoría plan-vs-código del 01/10/2026 y dice, por fase, qué se cumplió, qué falta, qué se desvió
del plan (documentado o en silencio), qué defectos siguen vivos y qué decisiones del usuario la
bloquean. Está pensada para alinear a un agente en un chat nuevo sin depender de sesiones previas.

1. `docs/fases/README.md` — cómo se usa y un prompt para abrir el chat.
2. `docs/fases/00-orden-recomendado.md` — orden de las fases, arreglos rápidos y handoff del trabajo en curso.
3. `docs/fases/01-trabajo-en-paralelo.md` — **si trabajás en paralelo con otras personas**: carriles,
   paquetes de trabajo con dependencias, migraciones reservadas, decisiones bloqueantes y reglas de
   convivencia (worktrees, contrato, puntos calientes). Diagrama en `01-trabajo-en-paralelo.excalidraw`.
4. `docs/fases/FX-*.md` — la ficha de la fase que vas a trabajar.

Las fichas son una foto: **verificá cada faltante contra el código antes de actuar**, y tachá en
la ficha lo que cerrás, en el mismo commit.

## 3. Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 4.1.x — fijar la última revisión estable en AKINE-00.01 |
| Build | Maven Wrapper (`mvnw`, `mvnw.cmd`, `pom.xml`) |
| Base de datos | MySQL 8.4 LTS |
| Migraciones | Flyway — `src/main/resources/db/migration/` |
| API | REST, OpenAPI 3 |
| Auth | JWT con refresh tokens |
| Tests | JUnit, Spring Boot Test, Testcontainers/MySQL, ArchUnit |
| Runtime local | Docker — `compose.yaml` para MySQL y dependencias |
| CI/CD | GitHub Actions + SonarQube |
| Observabilidad | Logging JSON, Prometheus, OpenTelemetry |

Cobertura exigida: **≥80 % en código nuevo, ≥90 % en módulos críticos**
(identity, organization, clinical, billing).

## 4. Arquitectura — monolito modular

Un único desplegable Spring Boot, dividido en **módulos de negocio** con APIs internas
explícitas, ownership de datos y dependencias controladas. **No hay microservicios en el
alcance inicial.**

Package raíz: `com.akine`. Cada módulo contiene **cinco** paquetes posibles:

```
com.akine.<modulo>.spi              ← ÚNICO punto de entrada desde otros módulos
com.akine.<modulo>.api              ← HTTP: controllers y DTO
com.akine.<modulo>.application      ← reglas de negocio, transacciones
com.akine.<modulo>.domain           ← modelo y entities
com.akine.<modulo>.infrastructure   ← repositorios, config, adaptadores
```

> **`spi` resuelve una ambigüedad del plan.** El plan exige que los módulos se comuniquen
> por "servicio/puerto interno explícito" sin decir dónde vive ese puerto. AKINE-00.01 lo
> fija: `spi` (Service Provider Interface) es lo único público de un módulo. Todo lo demás
> es privado, y **ArchUnit lo verifica** en `ModuleArchitectureTest`.

Módulos previstos (solo `platform` existe hoy — el resto se crea en su etapa):

```
platform/       # config, errores, tenancy técnico, auditoría base, observabilidad
identity/       # cuentas, autenticación, tokens, sesiones            → M02
organization/   # organización, suscripción, consultorio, memberships → M01, M03, M05
resource/       # espacios, disponibilidad, catálogos operativos      → M04–M06
person/         # Persona, Paciente, coberturas, adjuntos admin       → M07–M08, M15, M25
contracting/    # financiadores, planes, convenios, aranceles, autorizaciones → M15–M17
clinical/       # HC, timeline, Caso, Plan, derivaciones, adjuntos    → M09–M13
encounter/      # Sesión, evolución, mediciones, tratamientos (ADR-0024) → M14
scheduling/     # slots, Turno, series, check-in, agenda              → M12–M13
billing/        # obligaciones, anticipos, cobros, caja, claims, egresos → M18–M22
offering/       # Servicio, Oferta, habilitaciones                    → M27
activity/       # clases, inscripciones, participación, pases, abonos → M28–M29
notification/   # outbox, entrega, resultados, reintentos, templates  → M26
reporting/      # consultas, proyecciones, exports                    → M23
```

### Reglas de módulo — innegociables

1. **Cada tabla tiene un módulo propietario.** Ningún otro módulo la lee ni la escribe directamente.
2. El acceso entre módulos es **solo por el paquete `spi`** del módulo destino, o por
   **evento posterior al commit**. Prohibido importar `api`, `application`, `domain` o
   `infrastructure` de otro módulo.
3. **Prohibida una capa global** (`repository/`, `entity/` compartidos) que habilite acceso irrestricto.
4. Dependencias entre módulos **unidireccionales**. Los ciclos están prohibidos.
5. Transacciones locales para invariantes fuertes. Eventos internos post-commit para efectos derivados.
6. Los DTO son lo único que sale del backend. **Las entities nunca cruzan el borde del service.**

Las reglas 1–4 y 6 **se verifican automáticamente** en
`src/test/java/com/akine/architecture/`. No son documentación: son tests que fallan el build.

### Reglas de capa dentro del módulo

| Capa | Hace | No hace |
|---|---|---|
| `spi` | Contratos que el módulo ofrece a otros módulos | Exponer entities o detalles internos |
| `api` | Recibe request, valida formato (`@Valid`), devuelve response | Lógica de negocio, acceso a BD |
| `application` | Reglas de negocio, orquestación, transacciones | Conocer HTTP (nada de `HttpServletRequest`) |
| `infrastructure` | Consultas a la base, configuración, adaptadores | Reglas de negocio |
| `domain` | Modelo y mapeo | Depender de web, de otras capas, o salir hacia el cliente |

## 5. Persistencia — convenciones

- **Toda tabla de negocio lleva `organization_id`.** Sin excepción.
- Lleva `consultorio_id` cuando el hecho pertenece a una sede específica.
- **Índices y uniques deben incorporar el alcance tenant.** Un unique sin `organization_id`
  es un bug de aislamiento.
- Importes: `DECIMAL` en la base, `BigDecimal` en Java. **Nunca `float`/`double`.**
- Fechas: se persisten como **instantes UTC**. Las reglas locales usan una zona IANA explícita.
- **Baja lógica siempre.** Información histórica relevante no se elimina físicamente
  (regla maestra 10). Vigencias, snapshots históricos y auditoría.
- Migraciones Flyway versionadas, nunca editadas después de aplicadas.

## 6. Multi-tenancy

- Organización/Empresa = tenant. Una Organización tiene uno o más Consultorios.
- Separación estricta: **identidad global** ≠ **memberships contextuales** ≠ **habilitación profesional**.
- Login autentica una identidad única, **sin selección previa de rol** (DP-02). Después del login
  el usuario selecciona Organización + Consultorio entre sus contextos autorizados.
- El backend calcula permisos efectivos desde memberships y roles vigentes en ese contexto.
- Cambio de contexto autorizado: no requiere nuevo login, pero emite/renueva un access token
  acotado al contexto y **queda auditado**.
- **Un token del tenant B nunca puede ver datos del tenant A.** Verificar en cada test de integración.

## 7. Contrato API

- Este repo es **propietario** del OpenAPI 3 canónico: `openapi/akine-api.yaml`.
- El pipeline lo valida contra la implementación y lo publica como artefacto versionado (SemVer).
- El frontend genera su cliente desde una versión fijada. Cambio incompatible → versión mayor
  + ventana de compatibilidad. Ver `../CLAUDE.md` § "Regla de coordinación entre repos".

## 8. Reglas funcionales maestras

De `AKINE_Requerimientos_Integrados.md` §3. Se violan con facilidad si no se tienen presentes:

1. Historia Clínica ≠ Caso Clínico ≠ Sesión.
2. Plan de Tratamiento ≠ Turno ≠ Sesión.
3. Las sesiones se numeran **dentro del Caso Clínico**.
4. **Turno es reserva; Sesión es atención realizada.** Ninguna transición administrativa
   prueba por sí sola que una prestación ocurrió (DP-05).
5. Sesión finalizada **puede** generar obligación económica.
6. Obligación económica ≠ Cobro.
7. Cobro ≠ Caja.
8. Caja registra movimientos monetarios reales.
9. Cobertura del paciente ≠ Convenio del consultorio.
10. Información histórica relevante no se elimina físicamente.
11. Todos los módulos respetan multi-tenancy.
12. Backend es autoridad para permisos, estados y reglas de negocio.

**Turno, Check-in/Recepción y Sesión tienen máquinas de estado independientes** (DP-05).
Se coordinan por comandos y eventos explícitos. Cada transición registra actor, fecha,
estado anterior, estado nuevo y motivo cuando corresponda.

**Historia Clínica pertenece a la Organización** (DP-03), no al consultorio ni global al paciente.
Consultable desde otros Consultorios de la misma Organización solo con membership vigente,
permiso clínico y relación asistencial o justificación autorizada — todo auditado.
**Nunca se comparte automáticamente entre Organizaciones.**

**Turnos seriados** (DP-04): serie/regla explícita, pero cada Turno conserva identidad, estado
e historial propios. Una ausencia al primer turno se registra como `AUSENTE` y **nunca elimina
la serie**. Cancelar turnos futuros exige confirmación explícita, motivo obligatorio y auditoría;
los turnos pasados quedan inalterables.

## 9. Tres errores estructurales a evitar

Explicitados en el plan de implementación §1:

1. Construir módulos consumidores antes que sus cimientos.
2. Modelar `Paciente → Sesión` como atajo suficiente.
3. Reducir la economía a `Sesión → Pago`.

La ruta crítica de dominio es:
`Tenant → Consultorio → Membership/Permisos → Espacios/Disponibilidad → Persona/Paciente →
Cobertura/Convenio → Historia Clínica → Caso → Plan → Turno → Check-in → Sesión →
Obligación → Cobro → Caja → Presentación/Reportes`.

## 10. Seguridad

- JWT + refresh tokens. Access token acotado al contexto (Organización + Consultorio).
- Backend valida **siempre**: nunca confiar en un claim de rol enviado por el cliente.
- Sin secretos versionados. Los perfiles (`application-*.yml`) no llevan credenciales reales.
- Auditoría de todo acceso clínico sensible y de toda excepción de permiso.
- Datos de prueba: **exclusivamente sintéticos.** Nunca datos reales de pacientes.

## 11. Testing

| Tipo | Herramienta | Qué cubre |
|---|---|---|
| Unit | JUnit | Reglas de negocio en `application`/`domain` |
| Integración | Spring Boot Test + Testcontainers/MySQL | Repositorios, migraciones, transacciones |
| Arquitectura | ArchUnit | Ciclos entre módulos, acceso a repos ajenos, capas |
| Contrato | validación OpenAPI en CI | Implementación vs `akine-api.yaml` |

Los tests de arquitectura **no son opcionales**: son el único mecanismo que impide que el
monolito modular degenere en un monolito acoplado.

## 12. Rutas y comandos reales

Creados por **AKINE-00.01** y verificados en esta máquina.

### Estructura

```
appKine-api/
├── pom.xml · mvnw · mvnw.cmd · .mvn/wrapper/
├── compose.yaml                          # MySQL 8.4 local
├── openapi/akine-api.yaml                # contrato canónico (generado + commiteado)
├── .github/workflows/ci.yml
├── src/main/java/com/akine/
│   ├── AkineApiApplication.java
│   └── platform/
│       ├── api/          VersionController · GlobalExceptionHandler · dto/VersionResponse
│       ├── application/  VersionService
│       ├── domain/       BuildVersion
│       └── infrastructure/config/  SecurityConfig · OpenApiConfig
├── src/main/resources/
│   ├── application.yml · application-local.yml
│   └── db/migration/V1__baseline_tecnico.sql
└── src/test/java/com/akine/
    ├── architecture/  ModuleArchitectureTest · CodingConventionsTest
    ├── platform/api/  VersionControllerTest
    ├── AkineApiApplicationIT · OpenApiContractIT · TestcontainersConfiguration
```

### Comandos canónicos

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```bash
./mvnw test
```
Unitarios + arquitectura. No requiere Docker.

```bash
./mvnw verify
```
Todo lo anterior + integración (Testcontainers) + **gate de drift del contrato**. Requiere Docker.

```bash
./mvnw verify -Dakine.contract.update=true
```
Regenera `openapi/akine-api.yaml`. Correr después de cualquier cambio de API.

```bash
docker compose exec mysql mysql -u akine -pakine akine_local -e "SELECT 1;"
```
Consultas de validación durante el QA. No hace falta cliente MySQL en la máquina.

**Bandeja de correo de desarrollo: <http://localhost:8025>**
`docker compose up -d` levanta también un Mailpit (SMTP en `localhost:1025`, sin credenciales
y sin TLS). El perfil `local` envía **de verdad** contra él: las activaciones, recuperaciones e
invitaciones aparecen ahí con su enlace de un solo uso, y nada sale de la máquina. Para volver
al adaptador que solo registra en el log: `AKINE_EMAIL_MODE=log`.

### Imagen Docker (G-3)

`Dockerfile` multi-stage en la raíz: compila con el `mvnw` del repo sobre `eclipse-temurin:21-jdk`
(sin tests: corren en el CI) y corre el jar en capas sobre `eclipse-temurin:21-jre`, como el
usuario sin privilegios `akine` (UID 10001), con `-XX:MaxRAMPercentage=75.0`. La imagen **no
lleva configuración de entorno ni secretos**: todo llega por variable de entorno al arrancar.

```bash
docker build -t akine-api:local .
```

Correrla contra el MySQL y el Mailpit de `compose.yaml` (`docker compose up -d` antes). Desde
el contenedor, la máquina se alcanza como `host.docker.internal` (Docker Desktop; en Linux
agregar `--add-host=host.docker.internal:host-gateway`):

```bash
docker run --rm --name akine-api -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e AKINE_DB_URL='jdbc:mysql://host.docker.internal:3306/akine_local?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC' \
  -e AKINE_DB_USER=akine -e AKINE_DB_PASSWORD=akine \
  -e AKINE_MAIL_HOST=host.docker.internal \
  akine-api:local
```

- **Perfil `local` solo para desarrollo.** Fuera de un perfil de desarrollo la aplicación **no
  arranca** sin `AKINE_JWT_SECRET` (≥ 32 bytes), con `AKINE_EMAIL_MODE=smtp` y su relay
  (`AKINE_MAIL_HOST`, `AKINE_MAIL_USER`, `AKINE_MAIL_PASSWORD`, `AKINE_MAIL_FROM`), además de
  `AKINE_DB_*`, `AKINE_CORS_ORIGINS` y `AKINE_PUBLIC_BASE_URL`. Ver `application.yml`.
- **Adjuntos:** la aplicación escribe en `/app/var` (`adjuntos/` y `adjuntos-clinicos/`). Sin
  volumen montado ahí, los binarios se pierden con el contenedor.
- **Healthcheck:** `GET /actuator/health/liveness`. El primer arranque contra una base vacía
  aplica todas las migraciones y tarda (≈2,5 min medido el 06/10/2026), por eso el
  `start-period` es de 120 s.
- **JVM:** `-e JAVA_OPTS=...` reemplaza los flags por defecto.
- **SBOM:** CycloneDX JSON, lo genera `./mvnw package` en
  `target/classes/META-INF/sbom/application.cdx.json` y viaja embebido en el jar y en la imagen
  (`/app/META-INF/sbom/application.cdx.json`). El job `imagen` del CI lo publica como artifact
  `akine-api-sbom`. La imagen **no se publica** en ningún registry. **Ojo:** el goal de CycloneDX
  exige modo online; con `./mvnw -o` se saltea con un WARNING y no falla, así que un jar
  construido offline puede salir sin SBOM o con uno viejo. La imagen y el CI construyen online.

### Endpoints existentes

| Ruta | Qué es |
|---|---|
| `GET /api/v1/version` | Contrato técnico de versionado |
| `GET /actuator/health` | Health con estado de la DB |
| `GET /v3/api-docs.yaml` | Contrato OpenAPI en runtime |
| `GET /swagger-ui.html` | Swagger UI |

No hay endpoints de negocio: se crean en las etapas M01–M29.

### Contrato OpenAPI — estrategia

**Code-first con gate de drift.** springdoc genera el contrato desde los controllers; el YAML
se commitea; `OpenApiContractIT` falla si el generado difiere del commiteado. Todo cambio de
API aparece como diff revisable en el PR.

`akine.contract.version` (en `pom.xml` y `application.yml`) es SemVer y **no sigue** la versión
de la aplicación: minor para cambios aditivos, major para incompatibles. Versión actual: **0.1.0**.

El contrato declara `servers: [{url: "/"}]` deliberadamente: si springdoc infiriera la URL del
servidor, el puerto aleatorio de los tests haría fallar el gate en cada corrida.

## 13. Estado actual

> Esta sección decía "AKINE-00.01 completada, próxima etapa 00.02" desde agosto. El estado vivo
> ya no se mantiene acá.

**El estado por fase vive en `docs/fases/`** (auditoría del 01/10/2026: ~54 % del plan, ~65 % del
MVP F0–F8). El orden para retomar está en `docs/fases/00-orden-recomendado.md`. Las métricas de
build, contrato y cobertura están en el §7 de `CLAUDE.md`.
