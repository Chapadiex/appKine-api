# CLAUDE.md — appKine-api

Workflow de trabajo y comportamiento esperado del agente en el backend de AKINE.

> **Reglas técnicas y de negocio: `AGENT.md`.** Leerlo completo antes de cualquier tarea.
> Este archivo define *cómo* trabajar, no *qué* construir.

---

## 1. Protocolo de sesión inicial

1. `/caveman` — activar modo de ahorro de tokens.
2. `mem_search` con keywords del tema (Engram) — recuperar contexto de sesiones anteriores.
3. Leer `AGENT.md` completo (o confirmarlo en memoria).
4. Verificar estado del repo — correr tests antes de tocar código.
5. Identificar el próximo paso — ver §7 "Estado actual".

## 2. Skills instaladas y sus triggers

### Plugin `superpowers` (v6.3.0)

| Skill | Cuándo se usa |
|---|---|
| `superpowers:brainstorming` | **Obligatorio** antes de cualquier trabajo creativo: feature nueva, componente, cambio de comportamiento |
| `superpowers:writing-plans` | Ya hay spec/requisitos y falta el plan, antes de tocar código |
| `superpowers:executing-plans` | Ejecutar un plan escrito con checkpoints de revisión |
| `superpowers:test-driven-development` | **Obligatorio** al implementar feature o bugfix — tests primero |
| `superpowers:systematic-debugging` | **Obligatorio** ante cualquier bug, test que falla o comportamiento inesperado, antes de proponer un fix |
| `superpowers:subagent-driven-development` | Tareas independientes dentro de la misma sesión |
| `superpowers:dispatching-parallel-agents` | 2+ tareas sin estado compartido ni dependencia secuencial |
| `superpowers:using-git-worktrees` | Trabajo que necesita aislamiento del workspace principal |
| `superpowers:requesting-code-review` | Al completar tareas o antes de mergear |
| `superpowers:receiving-code-review` | Al recibir feedback de un PR |
| `superpowers:finishing-a-development-branch` | Implementación completa, tests en verde, decidir integración |
| `superpowers:verification-before-completion` | **Obligatorio** antes de decir "listo", "funciona" o "pasa" — evidencia antes de afirmar |

### Skills globales (`~/.claude/skills/`)

| Skill | Trigger |
|---|---|
| `engram-sdd-flow` | Cambios no triviales — orquesta el ciclo explore → propose → apply → verify → archive |
| `debugging-code` | Debugger interactivo: breakpoints, step, inspección de estado en runtime |
| `playwright-skill` | Tests E2E y de API con Playwright |

### Slash commands built-in

| Comando | Uso |
|---|---|
| `/caveman` | Modo ahorro de tokens (~75 %) |
| `/simplify` | **Obligatorio** después de implementar: reuso, simplificación, eficiencia. No busca bugs |
| `/code-review` | Review de correctitud sobre el diff actual, un PR o una rama |
| `/security-review` | Review de seguridad de los cambios pendientes en la rama |
| `/init` | Regenerar este archivo desde el código (solo cuando exista código real) |

### Plugin `engram` — memoria persistente

`mem_save`, `mem_search`, `mem_context`, `mem_session_summary`, `mem_get_observation`.
Ver §5.

> **No existen** `/sdd-new`, `/sdd-apply`, `/sdd-ff`, `/sdd-archive` ni la skill `judgment-day`
> en esta instalación. El ciclo SDD se ejecuta a través de la skill `engram-sdd-flow`
> y el paso adversarial de diseño está definido explícitamente en §3.

## 3. Workflow — Spec Driven Development

Todo cambio no trivial pasa por exploración y especificación antes de tocar código.

### Ciclo canónico

```
brainstorming → explore → propose → spec → design → DESIGN CHALLENGE
              → tasks → apply (TDD) → /simplify → verify
              → verification-before-completion → archive
```

| Fase | Qué produce | Herramienta |
|---|---|---|
| brainstorming | Intent y requisitos explorados antes de comprometerse | `superpowers:brainstorming` — **obligatorio** |
| explore | Análisis del codebase, del RF/RN aplicable y de las restricciones | `engram-sdd-flow` |
| propose | Propuesta con intent, scope y enfoque | `engram-sdd-flow` |
| spec | Requisitos y escenarios formales, trazados a `RF-MXX-NNN` | `engram-sdd-flow` |
| design | Decisiones de arquitectura: módulo propietario, tablas, contrato API, eventos | `engram-sdd-flow` + `AGENT.md` |
| **design challenge** | Revisión adversarial del diseño — ver abajo | **obligatorio**, paso manual |
| tasks | Checklist implementable y ordenada | `superpowers:writing-plans` |
| apply | Código real, tests primero | `superpowers:test-driven-development` |
| simplify | Reuso, simplificación, eficiencia, deuda | `/simplify` — **obligatorio** |
| verify | Validación de la implementación contra la spec | tests + `/code-review` |
| verification-before-completion | Evidencia de completitud antes de cerrar | `superpowers:verification-before-completion` — **obligatorio** |
| archive | Cierre del cambio y persistencia del estado | `mem_save` + `mem_session_summary` |

### Design challenge — paso obligatorio al cerrar `design`

Antes de pasar a `tasks`, el agente debe desafiar su propio diseño respondiendo por escrito:

1. **Ownership** — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro módulo la toca?
2. **Ciclos** — ¿la dependencia entre módulos que introduce este cambio es unidireccional?
   ¿Pasa ArchUnit?
3. **Tenant** — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?
4. **Reglas maestras** — ¿el diseño confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?
5. **Baja lógica** — ¿hay algún borrado físico de información histórica relevante?
6. **Contrato** — ¿el cambio de API es aditivo o incompatible? ¿Requiere versión mayor?
7. **Ruta crítica** — ¿este módulo tiene todos sus cimientos construidos, o lo estoy adelantando?
8. **El caso que rompe el diseño** — nombrar el escenario más adverso y decir cómo lo resuelve.

Si alguna respuesta es insatisfactoria, se vuelve a `design`. No se avanza a `tasks`.

### Flujos por tipo de tarea

**Feature nueva**
```
/caveman → brainstorming → engram-sdd-flow (explore/propose/spec/design)
→ DESIGN CHALLENGE → writing-plans → TDD → /simplify → tests + /code-review
→ verification-before-completion → mem_save
```

**Fix o cambio pequeño**
```
/caveman → systematic-debugging → TDD (test que reproduce primero)
→ fix → /simplify → tests → mem_save
```

**Refactor o cambio complejo**
```
/caveman → brainstorming → using-git-worktrees → writing-plans
→ DESIGN CHALLENGE → executing-plans → /simplify → tests + /code-review
→ verification-before-completion → finishing-a-development-branch → mem_save
```

**Debugging**
```
/caveman → systematic-debugging → debugging-code (si hace falta runtime)
→ test que reproduce → fix → /simplify
```

**Cambio que toca la API**
```
... diseño ... → actualizar implementación → actualizar openapi/akine-api.yaml
→ publicar contrato versionado → avisar al repo frontend para regenerar cliente
→ contract tests + E2E
```
Nunca asumir commit atómico entre repos. Ver `../CLAUDE.md`.

## 4. Protocolo de cambios

- **Nada de código sin un RF/RN que lo respalde.** Si no existe, se pregunta antes de inventar.
- **Tests primero.** TDD no es opcional en este repo.
- **ArchUnit se corre en cada cambio que toque estructura de módulos.**
- **Baja lógica siempre.** Nunca `DELETE` sobre información histórica relevante.
- **Datos sintéticos únicamente.** Jamás datos reales de pacientes en tests, seeds o fixtures.
- **Sin secretos versionados.**
- `main` siempre desplegable. Trabajo incompleto detrás de feature flag.
- Commits y PRs se escriben en prosa normal, no en caveman.

## 5. Memoria — Engram

### Guardar (`mem_save`) — proactivamente, sin que lo pidan

Inmediatamente después de: decisión de arquitectura o diseño, convención establecida,
bug fijado (con causa raíz), elección de herramienta con tradeoffs, descubrimiento no obvio,
gotcha o edge case, patrón de naming/estructura, preferencia o restricción del usuario.

Auto-check después de cada tarea:
*"¿Tomé una decisión, fijé un bug, aprendí algo no obvio o establecí una convención?
Si sí → `mem_save` ahora."*

Tipos: `bugfix`, `decision`, `architecture`, `discovery`, `pattern`, `config`, `preference`.

### Topic keys estables

```
akine/api/{modulo}/design
akine/api/{modulo}/decisions
akine/api/etapa/{AKINE-XX.YY}
akine/api/gotchas
akine/contract/openapi
```

### Buscar (`mem_search`)

Al inicio de sesión, antes de empezar algo que pudo hacerse antes, y cuando el usuario dice
"recordá", "qué hicimos", "cómo resolvimos".

### Cierre de sesión (`mem_session_summary`) — obligatorio antes de decir "listo"

Goal · Discoveries · Accomplished · Next Steps · Relevant Files.

### Después de compactación de contexto

1. `mem_session_summary` con el contenido compactado.
2. `mem_context` para recuperar sesiones anteriores.
3. Recién entonces continuar.

## 6. QA manual y gate de deploy

`.claude/qa-config.md` es **bloqueante**: sin él no corre el QA manual post-verify y no se
puede deployar ni pushear a `main`.

Es personal de cada máquina (credenciales de prueba, rutas locales) y está **gitignored**.
Template: `~/.claude/templates/qa-config-template.md`.

Reglas innegociables del QA:
- Validación de persistencia **contra la DB directamente** después de cada write/update/delete.
  Un `200` no prueba que el dato quedó bien guardado.
- Verificar aislamiento de tenant: un token del tenant B no debe ver datos del tenant A.
- **No maquillar la DB para forzar estados.** Si un flujo no llega al estado esperado, se
  registra como hallazgo — no se ajusta la base a mano.
- Si el frontend tiene Local API Switch, cambiarlo a localhost antes de probar y **revertirlo
  siempre** al terminar, haya pasado o fallado el QA.

## 7. Estado actual

**AKINE-00.01 completada y verificada.** Rutas y comandos reales en `AGENT.md` §12.

Remote: `https://github.com/Chapadiex/appKine-api.git`

Stack fijado: Java 21.0.10 · Spring Boot **4.1.1** · Maven 3.9 · MySQL 8.4 · Flyway ·
springdoc **3.1.0** · ArchUnit 1.4.1 · Testcontainers.

Evidencia: 28 tests en verde — 12 convenciones de código, 5 arquitectura de módulos,
2 slice web, 7 integración, 2 contrato.

> **Ojo con Spring Boot 4.** Rompe cosas respecto de 3.x y ya nos costó tiempo:
> starters partidos (`spring-boot-starter-webmvc`, no `-web`; un `-test` por starter),
> `@WebMvcTest` en `org.springframework.boot.webmvc.test.autoconfigure`,
> `TestRestTemplate` movido a `spring-boot-resttestclient` y **sin autoconfigurar**
> (usar `RestTestClient` + `@AutoConfigureRestTestClient`), `MySQLContainer` no genérico
> en Testcontainers 2.x, y **Jackson 3**: `WRITE_DATES_AS_TIMESTAMPS` pasó de
> `SerializationFeature` a `DateTimeFeature`, o sea `spring.jackson.datatype.datetime.*`.
> Ante una duda de API, inspeccionar el jar antes de asumir la forma de 3.x.

### AKINE-00.02 — completada

Baseline ejecutable y arquitectura comprobada.

- [x] Commit inicial del baseline en `main`
- [x] **ADRs** en `docs/adr/` — 7 decisiones con alternativas y consecuencias
- [x] **Umbral de cobertura activo** en JaCoCo (80 %). Medido: **100 %**
- [x] **`maven-enforcer`**: Java 21 exacto, Maven ≥3.9, convergencia de dependencias
- [x] `GlobalExceptionHandler` con tests — cubre el contrato de errores y el no-filtrado
- [x] Reporte reproducible de baseline: `../docs/baseline-report.md`
- [x] Harness de carga identificado (k6), sin ejecutar: `../docs/pruebas-de-carga.md`

**32 pruebas** (23 unitarias/arquitectura + 9 integración). Cobertura 100 %.

### AKINE-00.03 — completada

- [x] `DP-01`–`DP-09` como ADRs aceptadas: `docs/adr/0008`–`0016`
- [x] Matriz mínima de permisos aprobada: `docs/seguridad/matriz-permisos-minima.md`
- [x] Baseline verificado contra las decisiones — sin contradicciones
- [x] Plan rebaselinado: huecos de la spec con etapa destino (registro de cierre en el plan)

### AKINE-01.01 — completada con verificación parcial

Primer módulo de negocio: `organization` (tenant, planes, suscripción, consultorio y membership
mínimos) + tenancy técnico y auditoría en `platform`. Migraciones `V2`–`V5`. Contrato **0.2.0**
con 10 endpoints.

**357 pruebas backend** (348 unitarias/arquitectura + 9 integración). Cobertura **81,89 %** —
bajó de 98,35 % al agregar la capa API sin tests por decisión explícita: **quedan 1,89 puntos
de margen** sobre el piso.

> **La etapa NO declara cubiertos sus criterios de aceptación funcionales.** Sin login (01.02)
> ningún endpoint de negocio es ejercitable por un usuario. Lo verificado es la lógica; el
> comportamiento de punta a punta queda pendiente. **11 escenarios diferidos** en
> `docs/tests-diferidos.md`, cada uno con su origen y etapa destino.

Reglas que esta etapa dejó fijadas y que las siguientes heredan:

| Regla | Por qué |
|---|---|
| `application` consume **puertos en `domain`**, nunca repositorios de `infrastructure` | `AGENT.md` §4: `infrastructure → application`. ArchUnit lo verifica |
| Las excepciones de un módulo se mapean en **su propio advice**, no en `GlobalExceptionHandler` | `platform.api → organization.domain` cierra un ciclo |
| Cross-tenant → **404**, nunca 403 | Un 403 confirma que existe: bastaría probar ids consecutivos |
| Falta de contexto → **403**, nunca 401 | El interceptor del frontend borra el token ante cualquier 401 → bucle de login |
| La auditoría se escribe **en la transacción del negocio** | Un listener post-commit que falla deja la mutación sin rastro |
| `organization` **jamás** importa `identity` | Evita el ciclo; la orquestación va siempre desde `identity` |

### AKINE-01.02 — completada

Identidad, autenticación, recuperación de contraseña y outbox de notificaciones.

Módulo `identity` completo (dominio, aplicación y `api` con 4 controllers, 12 DTOs y advice
propio), módulo `notification` con outbox transaccional, y `platform/infrastructure/security`
con el filtro JWT, el rate limit de ventana fija y los handlers de error como Problem Details.
El filtro CSRF por `Origin` vive en `SecurityConfig` (`OriginCsrfFilter`) y cubre los dos
endpoints que se autentican con la cookie de refresh. Migraciones `V6`–`V9`: `cuenta`,
`token_verificacion`, `refresh_token`, `onboarding_registro`, `notification_outbox`.

Contrato **0.3.0**: 13 endpoints nuevos de `identity` sobre los 11 que ya había — 24
operaciones en 22 paths, sin drift contra los mappings del código.

Tres ADRs nuevas: `docs/adr/0017` (custodia y ciclo de vida de los tokens), `0018`
(anti-enumeración uniforme), `0019` (identidad global sin `organization_id`).

**785 anotaciones de test en 86 clases.** Últimos reportes en `target/` (2026-08-23 15:32):
905 unitarios en verde y 21 de integración con **1 fallo abierto** — el escenario diferido 8.
Cobertura **97,70 % instrucción · 87,42 % rama**, sobre un gate de 80 %; venía en 81,89 % al
cierre de 01.01.

> **Los números de arriba son los del cierre de 01.02 y quedaron congelados ahí.** El estado
> vigente del repositorio está en "Estado vigente" al final de esta sección.

> **Los criterios de aceptación no están todos cubiertos.** De los 11 escenarios diferidos de
> 01.01, siete corren en verde (1, 2, 3, 4, 5, 6 y 9); el 7 pasa a medias y su mitad faltante se
> difiere a 01.03, el 8 falla y el 10 y el 11 son E2E sin escribir. Ver `docs/tests-diferidos.md`.

Reglas que esta etapa dejó fijadas y que las siguientes heredan:

| Regla | Por qué |
|---|---|
| Las tablas de `identity` **no llevan `organization_id`** | Una cuenta es global y precede a toda organización: ADR-0019, excepción explícita al ADR-0004 |
| Login, registro y solicitud de recuperación responden **igual exista o no la cuenta** | Si la respuesta cambia, el endpoint es un oráculo de existencia de cuentas: ADR-0018 |
| Access token en memoria del cliente, refresh en **cookie `httpOnly` con rotación estricta** | Cada canje invalida el refresh presentado. Un token robado sirve una sola vez: ADR-0017 |
| Los dos endpoints que se autentican por cookie validan **`Origin` contra la lista blanca**, y un request sin `Origin` ni `Referer` **se rechaza** | `SameSite` depende del navegador; la validación de `Origin` la aplica el servidor |
| Los correos salen por el **outbox transaccional**, nunca desde el servicio | Un envío fuera de la transacción manda el correo de una operación que después hace rollback |
| `identity` orquesta hacia `organization` por el `spi`, nunca al revés | Cierra el ciclo que la regla de 01.01 ya prohibía en la otra dirección |
| Los tokens sensibles **nunca en texto plano ni en logs** | RN-M02-003, restricción dura de la especificación |

### AKINE-01.03 a AKINE-07.01 — completadas

Los registros de cierre completos de cada una viven en `../docs/AKINE_IMPLEMENTATION_PLAN.md`,
sección final. Resumen de qué entregó cada una y qué regla dejó fijada:

| Etapa | Qué entregó | Regla que hereda el resto |
|---|---|---|
| **01.03** | Memberships, roles, permisos y auditoría base | Los permisos se resuelven por membership vigente; nada de roles hardcodeados en los servicios |
| **02.01** | Consultorios, onboarding y contexto operativo | Orden de bloqueo único `subscription → organization`; invertirlo reintroduce un deadlock documentado |
| **02.02** | Espacios y boxes (M04) | Se commiteó sin registro de cierre; el suyo se escribió un día después y **se declara reconstrucción, no acta**. Es la única etapa cerrada sin diseño propio en `docs/diseno/` |
| **02.05** | Catálogo clínico: especialidades, prácticas y nomencladores | Filtrar por `owner_key = IFNULL(organization_id, 0)`, nunca por `organization_id`: varios `NULL` no colisionan en MySQL (ADR-0021) |
| **02.03** | Ciclo de vida de colaboradores: invitación por email | `MAX_MIEMBROS_ACTIVOS` ahora **sí** se aplica: cualquier alta de membership puede recibir 409 por tope de plan |
| **02.04** | Disponibilidad semanal, excepciones y feriados (M05) | Disponibilidad ≠ turno; la efectiva se calcula al leer y no se materializa; el lock de escritura es la fila de `consultorio_calendario` de la sede y se toma **antes** de leer nada |
| **02.06** | Servicio global y oferta por sede (M27) | La baja de un servicio **no cascadea**: las ofertas que ya lo prestaban siguen operando y sólo se impide crear ofertas nuevas. Un catálogo global va exceptuado de `TenantContextFilter`; las ofertas no pueden estarlo. Cada módulo necesita su `ApiActor` **con nombre propio** o Spring no arranca |
| **02.07** | Habilitación de profesionales y espacios por oferta (M27) | Un `@Version` sobre el padre **no protege** escrituras que sólo tocan tablas hijas: hace falta `OPTIMISTIC_FORCE_INCREMENT` (`b8bbc67`). La pantalla tiene que repedir la oferta después de guardar |
| **03.01** | Persona, PerfilPaciente, búsqueda y deduplicación (M07) | Una `Persona` **no es** un `Paciente`: dos tablas, ninguna columna `es_paciente`, y una sola clase inserta en `perfil_paciente` (RF-M07-010). La persona es de la **organización**, no de la sede |
| **04.01** | Historia Clínica organizacional reducida (M09) | La HC es de la organización (DP-03). Todo acceso clínico exige **justificación declarada** hasta que `RelacionAsistencialProbe` tenga implementación real, y toda lectura se audita |
| **05.01** | Motor de slots explicables (M12) | El slot se calcula al leer y **no se persiste**; un día sin slots nunca viaja sin su `MotivoSinSlots` |
| **05.02** | Reserva atómica de Turno (M04/M12) | Ningún unique expresa solapamiento de intervalos: la regla la hace cumplir el lock de `agenda_sede`, y **con `REPEATABLE READ` el lock no alcanza** — las mutaciones que serializan van en `READ_COMMITTED` |
| **06.01** | Agregado Sesión, inicio y autosave (M14) | Sesión ≠ Turno (DP-05). La **propiedad no es un permiso**: escribir en la atención ajena es 409, no 403. El control optimista **es** el autosave |
| **06.02** | Evaluación base y modo Sesión rápida (M14) | Todo lo clínico es nullable —"seguimiento no exige examen completo"—; se valida lo que sería FALSO, no lo que falta, y con 400 |
| **06.05** | Resultado y cierre idempotente (M14) | El correlativo se asigna con `UPDATE ... ultimo_numero + 1`, no con `MAX+1`, y la idempotencia se evalúa **antes** de pedir número. Cerrar no cobra (DP-06) |
| **07.01** | Obligaciones económicas (M18) | Deuda, cobro y caja son tres cosas. La deuda se devenga **dentro** de la transacción del cierre (`billing → encounter`), con snapshot congelado y saldo materializado. `DECIMAL`, nunca `float` |

> **02.05 se ejecutó antes que 02.03 y 02.04.** No es un salto arbitrario: §14 del plan declara
> que depende únicamente de 01.03.

### Estado vigente (01/09/2026, medido — no copiado)

Rama `akine-01.02-identidad`, **78 commits**, último `c9a3b68`, **ninguno pusheado**.
Migraciones **V1–V25, V27, V28, V30, V32, V33, V34, V35 y V36**; **`V26`, `V29` y `V31` quedaron
vacías** —V26 por la colisión de 02.07/03.01, V29 y V31 reservadas por DP-10 y no usadas—.
Contrato **0.19.0 con 109 operaciones**, propietario del `openapi/akine-api.yaml`, sin drift; el
**cliente del frontend está en `0.17.0`**, dos versiones menores atrás, porque 06.05 y 07.01 no
tienen pantalla.

Módulos, **once**: `platform`, `organization`, `identity`, `notification`, `resource`, `offering`,
`person`, `scheduling`, `clinical`, `encounter`, `billing`.
**23 ADRs** en `docs/adr/`, matriz de permisos en `docs/seguridad/`, diseños de etapa en
`docs/diseno/`.

`./mvnw -o verify` **VERDE, medido el 01/09/2026 sobre `c9a3b68`** (14:34 min): **1701 unitarias
+ 174 de integración** contra MySQL 8.4 real, **0 fallos, 0 errores, 1 diferida**
—`IdempotenciaYUniquesIT`, escenario 7b—. `jacoco:check`: *All coverage checks have been met*.

> **La cobertura sigue cayendo y ahora está a 2,35 puntos de romper el build.** Medido sobre
> `target/site/jacoco/jacoco.csv`: **instrucción 82,35 % · línea 83,48 % · rama 72,56 % ·
> método 72,79 % · clase 83,12 %**. `pom.xml` gatea `LINE` e `INSTRUCTION` al **0,80** sobre el
> BUNDLE y **no gatea `BRANCH`**.
>
> Este archivo declaraba el 27/08 **87,73 % de línea y 86,94 % de instrucción**: las dos cayeron
> más de **cuatro puntos** en las nueve etapas siguientes, y **ningún gate avisó** porque el piso
> es 80 y todavía no lo tocan. La rama viene de 87,42 % en 01.02 y 76,36 % el 27/08: **hoy 72,56 %,
> quince puntos abajo del arranque.** Agregar el gate de `BRANCH` hoy rompe el build.
>
> El frontend sí gatea `BRANCH`, y por eso **02.06 le rompió el build ahí y hubo que escribir
> tests antes de poder cerrar**. Es la diferencia práctica entre tener el gate y no tenerlo.

> **El QA manual del §6 no se corrió para ninguna etapa desde 02.02** —02.02 a 02.05, 02.07, y
> las siete del Paquete B (05.01, 05.02, 04.01, 06.01, 06.02, 06.05, 07.01)—, y tampoco hay E2E
> de la vertical clínica ni de la económica. §6 lo declara bloqueante para deploy: están cerradas
> con esa deuda **escrita, no saldada**.
>
> **Docker ya no es la excusa.** Este archivo y el del workspace decían que el motor "no arranca
> sin elevación"; al 01/09/2026 `akine-mysql` (MySQL 8.4) y `akine-mailpit` llevan 27 horas arriba
> y sanos, y los 174 tests de integración corren contra esa base. Lo que falta es correr el QA.
>
> **Lo único que se ejerció contra la base concurrentemente son tres suites**, y vale la pena
> saber cuáles: `TurnoConcurrenteIT` (una sola reserva gana), `DisponibilidadIT` (las dos primeras
> altas de la sede no pasan las dos) y `CierreConcurrenteIT` (correlativo sin repetir ni huecos,
> y el devengo de la deuda con precio congelado).

> **Los contratos publicados de 02.02 y 02.05 prometen `concurrent-modification` y su código
> devuelve `conflict`.** `resource`, `espacio` y `catalogo` lanzan el
> `OptimisticLockingFailureException` plano, que `GlobalExceptionHandler` mapea a `conflict`;
> `concurrent-modification` lo emite solo `OrganizationProblemHandler`, para la subclase de JPA.
> Quedan 13 ocurrencias en el YAML. Corregido solo para M05 — unificarlo cambia respuestas de
> todos los módulos y es **una decisión de contrato transversal pendiente del usuario**.

> **El OpenAPI no declara ningún `securityScheme`**, en ningún módulo. Preexistente. El frontend
> funciona porque agrega la autenticación por interceptor; un cliente generado no lo sabría.

### Próximo paso — lo que queda del Paquete B: **05.03 y 07.02**

DP-10 declara nueve etapas y hay **siete cerradas**. Faltan **05.03** (ciclo e historial de Turno,
`V31` reservada y sin usar — hoy **un turno reservado no se puede cancelar ni reprogramar desde
ninguna pantalla**) y **07.02** (cobros e imputaciones, la que cierra la vertical: la obligación
de 07.01 ya expone saldo materializado con su `CHECK`, así que imputar es un `UPDATE` condicional
bajo lock).

Pendientes que arrastra el backend:

- [ ] Protección de rama en `main`
- [ ] Activar el job de SonarQube en `.github/workflows/ci.yml` (listo, comentado)
- [ ] Observabilidad: logging JSON, Prometheus, OpenTelemetry
- [ ] Reglas `PACKAGE` de cobertura al 90 % para módulos críticos: el `PENDIENTE(F1)` del `pom.xml` sigue abierto y `identity`, `organization`, `notification` y `resource` ya existen
- [ ] Gate de `BRANCH` en JaCoCo, después de subir la cobertura de rama
- [ ] Completar los `PENDIENTE(F1)` de `.claude/qa-config.md`
- [ ] Escenario diferido 7b: `request_hash` en `onboarding_registro` (migración más cambio de API)
- [x] Registro de cierre de AKINE-02.02 — escrito el 26/08, declarado como reconstrucción

## 8. Checklist de cierre de tarea

- [ ] Tests en verde (unit + integración + ArchUnit)
- [ ] `/simplify` corrido
- [ ] `/code-review` sin hallazgos abiertos
- [ ] Contrato OpenAPI actualizado si cambió la API
- [ ] QA manual contra la DB si el cambio toca persistencia
- [ ] `superpowers:verification-before-completion` con evidencia real
- [ ] `mem_save` de decisiones y descubrimientos
- [ ] `mem_session_summary` antes de cerrar
