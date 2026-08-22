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

Vive en el workspace padre (`../docs/`), no en este repo:

- `../docs/AKINE_Requerimientos_Integrados.md` — **fuente de verdad funcional** M01–M29 + §30–45.
- `../docs/AKINE_IMPLEMENTATION_PLAN.md` — **fuente de verdad de arquitectura y roadmap**, DP-01…DP-09.
- `../docs/arquitectura-java-angular21.md` — referencia de layout Spring Boot.
- Documentos históricos (`AKINE_info.txt`, `AkinePN.docx`, UML 2019): **antecedente de negocio únicamente.**

Convenciones de identificadores en la especificación:
`RF-MXX-NNN` requerimiento funcional · `RN-MXX-NNN` regla de negocio ·
`RNF-MXX-NNN` requerimiento no funcional · `CA-MXX-NNN-YY` criterio de aceptación.

Todo cambio debe trazarse a un RF/RN concreto. Si no hay RF que lo respalde, no se implementa
sin decisión explícita del usuario.

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
clinical/       # HC, timeline, Caso, Plan, Sesión, evolución         → M09–M14
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

**AKINE-00.01 completada y verificada.** 28 tests en verde (19 unitarios/arquitectura +
9 de integración). Sin commits todavía: el árbol está listo para el commit inicial.

Próxima etapa: **AKINE-00.02** — baseline ejecutable y arquitectura comprobada.
