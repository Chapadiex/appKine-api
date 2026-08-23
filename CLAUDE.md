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

### Próximo paso — etapa AKINE-01.02

Identidad, autenticación, recuperación y outbox de notificaciones. Diseño y challenge en el
scratchpad de sesión; decisiones transversales en `decisiones-transversales-f1.md`.

Lo primero que habilita: los 11 tests diferidos y las tres pantallas del frontend, que hoy no
se pueden ejercitar.

Pendientes que arrastra el backend:

- [ ] Protección de rama en `main`
- [ ] Activar el job de SonarQube en `.github/workflows/ci.yml` (listo, comentado)
- [ ] Observabilidad: logging JSON, Prometheus, OpenTelemetry
- [ ] Reglas `PACKAGE` de cobertura al 90 % para módulos críticos, cuando existan
- [ ] Completar los `PENDIENTE(F1)` de `.claude/qa-config.md`

## 8. Checklist de cierre de tarea

- [ ] Tests en verde (unit + integración + ArchUnit)
- [ ] `/simplify` corrido
- [ ] `/code-review` sin hallazgos abiertos
- [ ] Contrato OpenAPI actualizado si cambió la API
- [ ] QA manual contra la DB si el cambio toca persistencia
- [ ] `superpowers:verification-before-completion` con evidencia real
- [ ] `mem_save` de decisiones y descubrimientos
- [ ] `mem_session_summary` antes de cerrar
