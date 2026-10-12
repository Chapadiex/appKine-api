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

> Reescrito el **19/09/2026**. La versión anterior se había congelado el 01/09 y daba mal el
> contrato, los commits, los módulos, las migraciones y la fase: mandaba a reconstruir cosas que
> ya existen. Los detalles de cierre de cada etapa **no se repiten acá** — viven íntegros en los
> registros de cierre al final de `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`, que son **21** más un
> "Registro de avance — AKINE-04.02".

Remote: `https://github.com/Chapadiex/appKine-api.git`

Stack fijado: Java 21 · Spring Boot **4.1.1** · Maven 3.9 · MySQL 8.4 · Flyway · springdoc ·
ArchUnit · Testcontainers.

> **Ojo con Spring Boot 4.** Rompe cosas respecto de 3.x y ya nos costó tiempo:
> starters partidos (`spring-boot-starter-webmvc`, no `-web`; un `-test` por starter),
> `@WebMvcTest` en `org.springframework.boot.webmvc.test.autoconfigure`,
> `TestRestTemplate` movido a `spring-boot-resttestclient` y **sin autoconfigurar**
> (usar `RestTestClient` + `@AutoConfigureRestTestClient`), `MySQLContainer` no genérico
> en Testcontainers 2.x, y **Jackson 3**: `WRITE_DATES_AS_TIMESTAMPS` pasó de
> `SerializationFeature` a `DateTimeFeature`, o sea `spring.jackson.datatype.datetime.*`.
> Ante una duda de API, inspeccionar el jar antes de asumir la forma de 3.x.

### Estado al 11/10/2026, sobre `81344c5`

> Esta subsección **supersede** a la del 07/10 que sigue abajo en contrato, migraciones, tests,
> cobertura y paquetes; la del 07/10 queda como historia. Las cifras de cobertura del bloque del
> 29/09 más abajo también quedan superadas por las de esta tabla.

Entre el 08/10 y el 11/10 entraron a `main` **26 PR, del #61 al #86**: dieciséis el 08/10
(#61–#76) y diez el 11/10 (#77–#86). `main` tiene **565 commits** y el último merge es `81344c5`
(PR #83, G-6). En `appKine-web` entraron **24 PR, del #24 al #48** (queda abierto el #47), y el
cliente quedó regenerado contra **0.84.0**. Quedan abiertos acá los cuatro PR que Dependabot abrió
el 11/10 (#87–#90), sin revisar.

| | Valor |
|---|---|
| Contrato | `openapi/akine-api.yaml` y `akine.contract.version` **0.84.0**, **309 operaciones** (`grep -cE '^\s+operationId:'`), ningún `operationId` repetido. El cliente de `appKine-web` `main` está en **0.84.0**. Medido sobre `81344c5` |
| Migraciones | Última **`V85`** (B-4), 72 archivos. Desde el 07/10 sólo entró `V85`. No existen `V26 V29 V31 V62 V66 V67 V68 V70 V72 V73 V79 V82 V84 V86`: `V84` quedó por debajo de `V85` y `V86` la liberó DU-6, así que la próxima libre es **`V87`**. Medido sobre `81344c5` |
| Tests unitarios | **3.466, 0 fallos ni errores** (`./mvnw -o test -DskipITs -Djacoco.skip=true` sobre `81344c5`, 11/10; contadas como `<testcase>` en los 274 reportes de surefire). Coincide con lo que corrió el CI de #83 |
| Tests de integración y cobertura | **No se corrieron para este documento.** Los números son los de G-6 (`docs/calidad.md` §4): `./mvnw verify` completo el 11/10 sobre `955855f` + la rama de G-6, **3.439 unitarias + 785 de integración, 0 fallos**, 1 salteada (7b). BUNDLE **línea 89,80 % · instrucción 88,56 % · rama 77,31 %**. El CI de #83, ya con #84 y #85 adentro, corrió 3.466 + 792 y pasaron las ocho reglas de JaCoCo (la global en 0,80 y una por módulo crítico) |
| GitHub | Rama por defecto **`main`** en los dos repos (antes `claude/goofy-goodall-8e6772` acá). `main` **protegida**: checks obligatorios "Build, test y contrato" e "Imagen Docker y SBOM". Consultado por la API el 11/10 |

#### Qué agregó cada minor del contrato desde 0.69.0

Las versiones **de `main`** no coinciden con las que declaran varios PR: cada uno subió la minor en
su rama y, al rebasear sobre lo que ya había entrado, la volvió a subir (regla 5 del plan en
paralelo). Esta tabla sale de `git log --first-parent` sobre el YAML, no de las descripciones.

| Versión | Paquete · PR | Qué agrega |
|---|---|---|
| 0.70.0 | A-11 · #64 | Cuatro consultas previas de impacto sin efectos (`impacto-de-edicion`, `impacto-de-baja` de bloques y excepciones, `impacto-de-alta` de excepciones) y `cobertura` estructurada en los hitos de coberturas del 360. 305 operaciones |
| 0.71.0 | B-7 · #63 | `importArancelesDeConvenio` con `modo = PREVIEW \| CONFIRMAR`; problem type `importacion-aranceles-rechazada` |
| 0.72.0 | E-2 · #66 | Motivo `PASADO` en `DiaDeAgenda.motivoSinSlots`; `ReprogramarTurno.profesionalId` opcional de verdad |
| 0.75.0 | E-8b · #71 | `CANCELADA` en el estado derivado de la serie (DP-20) |
| 0.76.0 | E-7b · #72 | Problem type `oferta-sin-precio` en `cerrarSesion` (DP-17) |
| 0.77.0 | A-8b · #73 | `FUERA_DE_HORARIO_SEDE` en `motivoSinSlots` e `impactoDelHorario` en el calendario de la sede (DP-19) |
| 0.78.0 | DP-21 · #74 | Las descripciones de 409 por versión vieja pasan a `concurrent-modification` en todos los módulos |
| 0.79.0 | B-4 · #75 | `getHistorialDeAutorizacion`; `Orden.situacion` y `Orden.sesionesConsumidas` (DP-23). 307 operaciones |
| 0.80.0 | DU-6 · #76 | Sólo descripciones de los 403 de `paciente:read` (DP-22) |
| 0.82.0 | #81 | `listNotifications`; 404 en invitaciones con organización distinta del contexto; descripción corregida de prestaciones elegibles. 308 operaciones |
| 0.84.0 | #85 | `listPermisosOtorgablesDelRol`, `MembershipGrantResponse.vigenteConElRolActual` y problem type `permiso-no-otorgable-al-rol`. 309 operaciones |

`0.73.0`, `0.74.0`, `0.81.0` y `0.83.0` no existen en `main`. Casi todo es aditivo, pero hay
**cambios de respuesta visibles** que sus PR declaran: desde 0.76.0 un cierre sin precio
particular responde 409 donde antes cerraba sin deuda; desde 0.77.0 una sede con horario cargado
recorta la agenda; desde 0.78.0 la versión vieja es `concurrent-modification` y no `conflict`;
desde 0.80.0 el rol `PACIENTE` recibe 403 en el padrón; y desde 0.84.0 el 400 de un grant no
admitido cambia de `type` (`validation-error` → `permiso-no-otorgable-al-rol`).

#### Paquetes que entraron del 08/10 al 11/10

- **Decisiones del usuario del 08/10, implementadas:** DP-17 (E-7b, #72), DP-18 (registrada en
  #71, confirma A-7), DP-19 (A-8b, #73), DP-20 (E-8b, #71), DP-21 (#74, resuelve DU-5), DP-22
  (#76, resuelve DU-6) y DP-23 (B-4, #75, resuelve DU-8). Las siete están en
  `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`.
- **Backend con pantalla:** A-11 (#64 · web #29), B-7 (#63 · web #28), B-4 y A-8b (#75, #73 · web
  #39), DP-17/21/22 (#72, #74, #76 · web #40), listado de notificaciones (#81 · web #46), permisos
  otorgables por rol (#85 · web #44 y #48).
- **Calidad y operación (carril G):** G-4 observabilidad (#62), G-11 cobertura (#65 y #69), G-13
  backup/restore, rollback, smoke y runbooks (#77), G-5 hallazgos altos de 07.07 y threat model
  (#78), G-10 carga con k6 (#80, **no cumple**), G-6 Dependabot, dependency-check, Sonar listo y
  cobertura por módulo (#83 · web #41) y la **guía** del QA manual de G-12 (#84, ejecución
  pendiente del usuario). En `appKine-web`: G-7 (#24) y G-9, el job E2E contra el backend real
  (#30).
- **Defectos que reportaron otros paquetes:** E-2 (#66, #67), B-6 (#68), #69, #70, #79, #81, #82
  y #86. Detalle abajo.

#### Los defectos reales corregidos desde el 07/10

| # | Qué pasaba | Dónde | Lo encontró | PR |
|---|---|---|---|---|
| 1 | La agenda **ofrecía los slots ya pasados de hoy** y reservarlos daba `slot-no-disponible` | `AgendaService` | E2E de E-2 | #66 |
| 2 | **`COMPLETO` nunca se emitía**: el día lleno viajaba con slots en cupo 0 y sin motivo | `AgendaService` | E2E de E-2 | #66 |
| 3 | **Una habilitación cargada hoy no valía para hoy** (vigencia evaluada al mediodía), y el último día de vigencia borraba el día entero u ofrecía horas fuera de ella | motor de slots | E2E de E-2 | #66 |
| 4 | **Reprogramar sin `profesionalId` daba `slot-no-disponible`** aunque el contrato lo declara opcional | `aplicarReprogramacion` | E2E de E-2 | #66 |
| 5 | **Dos logins simultáneos de la misma cuenta**: de cinco, uno entraba, uno recibía 409 y tres **500 por deadlock** (escalada S→X sobre `cuenta`: la FK del `refresh_token` toma el S y el UPDATE con `@Version` pide el X) | `CuentaRepositoryPort` | E2E de E-2 | #67 |
| 6 | **El rechazo del login revertía la transacción entera**: el contador de fallos nunca pasó de 0, `ACTIVIDAD_SOSPECHOSA` no podía dispararse y ningún `LOGIN_FALLIDO` llegó a la auditoría | `autenticar`, `abrirSesion` | `LoginConcurrenteIT` | #67 |
| 7 | **La subida concurrente de un adjunto administrativo no era idempotente**: el perdedor recibía 409 `conflict`. El mismo `REPEATABLE READ` que ya se había corregido en el adjunto clínico | `AdjuntoService.subir` | `AdjuntoConcurrenteIT` (B-6) | #68 |
| 8 | **`Retry-After: 0`** en el 429: truncaba los segundos y el reintento inmediato volvía a caer en la ventana | `FixedWindowRateLimiter` | E2E de G-9 | #69 |
| 9 | **Una cuenta ORG_ADMIN en dos organizaciones leía la auditoría, las memberships y las sedes de B con el contexto de A**, y podía suspender un colaborador de B | `AuditEventController`, `MembershipController`, `ConsultorioController` | E2E de A-6 | #70 |
| 10 | **La Sesión no se auditaba desde el 29/09**: el merge `3656980` perdió `d6ea5d0` y `SESION_INICIADA/ACCEDIDA/CERRADA` quedaron declaradas y sin uso | `encounter` | inventario de G-5 | #78 |
| 11 | **Un grant podía otorgar una celda "No" de la matriz** (`hc:write` al ORG_ADMIN, `hc:read` al ADMINISTRATIVO o al PACIENTE, `auditoria:read-clinica` al PROFESIONAL): 201 por HTTP | `RolePermissions`, evaluador | G-5 | #78 |
| 12 | **Las invitaciones ignoraban el `orgId` de la URL**: con contexto A y URL B, emitir, reenviar y cancelar **escribían en A** | `ColaboradorInvitacionController` | reportado en #70 | #81 |
| 13 | Una ruta inexistente respondía 404 con `type` `validation-error` | `GlobalExceptionHandler` | — | #81 |
| 14 | **Descargar un adjunto administrativo daba 500 siempre** contra MySQL: lectura `readOnly` que audita en la misma transacción. El barrido encontró el mismo patrón en `TratamientoService.listar` y, latente, en `DerivacionClinicaService.consultar` | `person`, `encounter`, `clinical` | simulacro de restore de G-13 | #82 |
| 15 | **El rate limit era global detrás del nginx del frontend**: contaba por `getRemoteAddr()`, que es la IP del proxy. Ahora sólo lee `X-Forwarded-For` desde `AKINE_TRUSTED_PROXIES` | `RateLimitFilter` | G-13 (runbook R4) | #82 |
| 16 | **El indicador "Cancelados" del reporte de turnos daba siempre 0** (y los reprogramados después cancelados no contaban): cancelar sella `deleted_at` y la consulta lo filtraba | `TurnoRepository` | validación de la guía de G-12 | #86 |
| 17 | **Registrar un cobro, devengar una deuda y anular una deuda no escribían `audit_event`** | `CobroService`, `ObligacionDevengador`, `ObligacionService` | validación de la guía de G-12 | #86 |

Además, #79 corrigió un IT que fallaba sólo entre las 21 y las 24 de Argentina (`CURDATE()` en UTC
contra la fecha de la sede); el mismo patrón en la pantalla de agenda lo corrigió `appKine-web`
#43.

> **El patrón de estos cuatro días: casi nada lo encontró un test unitario.** Los encontraron los
> E2E contra el backend real (1–5, 8, 9), los ITs de concurrencia (6, 7), un simulacro de restore
> (14, 15), un inventario de seguridad contra el código (10, 11) y validar las consultas de una
> guía de QA contra una base sembrada (16, 17). Es la misma lección del 29/09: **lo que destapa es
> ejecutar**, y cada forma nueva de ejecutar encontró defectos que las anteriores no veían.

#### Lo que se midió y quedó abierto

- **G-10: el backend no cumple los SLO de ADR-0016 con escrituras** (`docs/carga.md` §6). Las
  lecturas cumplen con 500 usuarios (p95 43 ms). Con reservas y cobros, **el pool de Hikari se
  traba**: `TurnoService.reservar` y `CobroService.registrar` llaman a un iniciador `REQUIRES_NEW`
  dentro de su transacción, retienen una conexión y piden otra; con el pool de 10, diez a la vez
  traban todo (89,8 % de errores). Además: el cobro serializa en `comprobante_numerador` (~330 ms
  de espera de lock), un fallo de base en la autenticación responde 401, N+1 en la agenda del día y
  un primer arranque más largo que el `start-period` del healthcheck. **Ninguno corregido en
  `main` al 11/10.**
- **G-6: `mysql-connector-j` 9.7.0 tiene cuatro CVE sin versión corregida** en Maven Central
  (CVE-2026-60586, 7,7, la más alta). Excepción temporal **hasta el 09/11/2026** en
  `dependency-check-suppressions.xml`: esperar la corrección o bajar a 9.6.0. Los overrides de
  Tomcat 11.0.26 y Jackson 3.1.7/2.21.7 hay que borrarlos cuando Spring Boot traiga iguales o
  mayores.
- **G-6: cuatro módulos críticos debajo de 0,90 de línea** —`billing` 87,56 %, `person` 86,54 %,
  `scheduling` 86,47 %, `clinical` 82,56 %—, todos por la capa REST, que los ITs no ejercitan.
- **#82:** `AuthSessionController` sigue guardando `getRemoteAddr()` como IP de la sesión: detrás
  del nginx registra la del proxy.
- **G-12:** la guía está; **el QA manual del §6 sigue sin correr desde 02.02**.

### Estado al 07/10/2026, sobre `8fe1fae` (histórico)

> Esta subsección **supersede** a la del 06/10 que sigue abajo en contrato, migraciones y
> paquetes; la del 06/10 queda como historia. Las cifras de cobertura **no** se actualizan acá:
> viven en su propio bloque más abajo.

Entre la noche del 06/10 y el 07/10 entraron a `main` **20 PR, del #41 al #60** (#40 fue el
documento de estado del 06/10). `main` tiene **451 commits** y el último merge es `8fe1fae`
(PR #60). En `appKine-web` entraron en el mismo día los PR **#6 al #23**, y el cliente quedó
regenerado contra **0.69.0**: por primera vez desde el 29/09 el frontend no está atrasado respecto
del contrato.

| | Valor medido sobre `8fe1fae` |
|---|---|
| Contrato | `openapi/akine-api.yaml` y `akine.contract.version` **0.69.0**, **301 operaciones** (`grep -cE '^\s+operationId:'`), ningún `operationId` repetido. El cliente de `appKine-web` `main` está en **0.69.0** |
| Migraciones | Última **`V83`** (A-8). Desde `V69` se usaron `V71`, `V74`, `V75`, `V76`, `V77`, `V78`, `V80`, `V81` y `V83`. No existen `V66`, `V67`, `V68`, `V70`, `V72`, `V73`, `V79` y `V82` (además de las viejas `V26`, `V29`, `V31`, `V62`). `V68` (B-4) y `V79` siguen sin usar, pero quedaron **por debajo de `V83`**: Flyway corre sin `outOfOrder`, así que B-4 tiene que tomar un número mayor. Detalle en §6 del plan de trabajo en paralelo |
| Tests | **3.276 unitarias, 0 fallos ni errores** (`./mvnw -o test -DskipITs -Djacoco.skip=true` sobre `8fe1fae`, 07/10; contadas como `<testcase>` en los reportes de surefire, porque el atributo `tests` descuenta los `@Nested` con nombres repetidos y da 3.254). Los ITs se corrieron por PR, cada uno sobre los suyos; **la suite completa con todos los ITs no se corrió sobre `8fe1fae`** |

#### Qué agregó cada minor del contrato desde 0.54.0

| Versión | Paquete · PR | Qué agrega |
|---|---|---|
| 0.55.0 | E-1 · #41 | Sólo descripciones: las sondas de impacto ya corren contra turnos reales y el contrato decía "siempre 0" |
| 0.56.0 | C-6 · #43 | `tratamientos` y `mediciones` opcionales en la enmienda y en cada versión de `GET .../versiones`; problem type `enmienda-cambia-practicas` |
| 0.57.0 | E-3 · #44 | Series de turnos: `crearSerieDeTurnos`, `verSerieDeTurnos`, `previsualizarAlcanceDeSerie`, `cancelarSerieDeTurnos`, `reprogramarSerieDeTurnos`; `serieId` en `Turno` y `TurnoDelDia` |
| 0.58.0 | A-9 · #46 | `GET`/`PUT .../ofertas/{ofertaId}/practicas` (`getPracticasDeOferta`, `reemplazarPracticasDeOferta`) |
| 0.59.0 | C-4 · #47 | `GET /autorizaciones/{id}/alertas` y `consumosARevisar` en el saldo |
| 0.60.0 | F-4 · #49 | `Obligacion` gana `concepto`, `practicaId`, `alertaPracticaNoHabilitada` y `convenio` (schema `ConvenioAplicado`) |
| 0.63.0 | E-4 · #52 | Recurso `/turnos/{t}/recepcion` (8 operaciones), `Recepcion`, `EventoDeRecepcion`, `TurnoDelDia.recepcion`, problem type `recepcion-transicion-no-permitida`. `EN_ESPERA` del turno y la llegada vieja quedan **deprecados** |
| 0.64.0 | A-7 · #53 | `GET /me/platform-role`; aprobar una solicitud de catálogo publica el concepto global. Aprobar sin ningún código posible pasó de 200 a 400 |
| 0.65.0 | B-3 · #55 | `GET /personas/{id}/cobertura-aplicable`, arancel por oferta (`ofertaId` en aranceles y en `aranceles/efectivo`) y `precios-particulares` de la oferta |
| 0.66.0 | E-6 · #54 | `PUT .../ofertas/{o}/politica-de-prepago`, `Recepcion.prepago`, `turnoId` en el registro de cobro; problem types `prepago-no-admitido`, `prepago-ya-registrado` |
| 0.67.0 | #58 | Los schemas de reportes pasan a `IndicadorDeReporteResponse`, `SeccionDeReporteResponse` y `SeccionOmitidaDeReporteResponse` |
| 0.68.0 | E-8 · #59 | `GET /consultorios/{c}/series-de-turnos` (`listarSeriesDeTurnos`) y `TurnoDelDia.prepago` |
| 0.69.0 | A-8 · #60 | `primerBox` y `horarioGeneral` en el alta de sede; `horarioGeneral` en el calendario de la sede |

`0.61.0` y `0.62.0` no existen en `main`. Todo es aditivo salvo tres puntos que los PR declaran:
el 400 nuevo de A-7, la deprecación de `EN_ESPERA` en E-4 —sigue declarado una versión más para no
romper la compilación del cliente— y el cambio de nombre de los tres schemas de 0.67.0, que corrige
un contrato que describía mal la respuesta.

#### Paquetes que entraron entre el 06/10 a la noche y el 07/10

- **Decisiones:** DP-11, DP-12 y DP-13 (#42) resuelven DU-1, DU-4 y DU-7; DP-14, DP-15 y DP-16
  (#48) resuelven DU-2, DU-3 y DU-9.
- **Cerrados:** E-1 (#41, descripciones), G-11 (#45, gate de vuelta a 0,80), A-4 (#51), G-1 (#50),
  F-8 (#57, los dieciséis escenarios 33–48 corridos), E-7 (#56).
- **Backend hecho y pantalla en `appKine-web` el mismo día:** E-3 (#44 · web #10), E-4 (#52 · web
  #8), A-7 (#53 · web #13), B-3 (#55 · web #15), E-6 (#54 · web #14), E-8 (#59 · web #20 y #21),
  A-8 (#60 · web #22).
- **Backend hecho, sin pantalla propia:** C-6 (#43), A-9 (#46: la pantalla de B-3 lee las prácticas
  de la oferta pero nadie las edita), C-4 (#47: las alertas son D-f), F-4 (#49: el convenio congelado
  se ve en la bandeja de elegibles de presentaciones, web #9; la cuenta corriente no muestra el
  `concepto`).
- **Sólo frontend, sobre backend que ya existía:** F-6 caja (web #7), F-7 presentaciones (web #9),
  G-8 reportes (web #12), B-5 coberturas del 360 (web #16), A-10 impacto (web #17), G-3 imagen
  Docker del frontend (web #18).
- **La ruta crítica del MVP llegó al final del backend:** A-9 → C-4 → F-4 están en `main`. La
  bandeja de prestaciones elegibles de 07.04 y la sección de financiadores del reporte tienen datos
  reales por primera vez (`ObligacionDelFinanciadorIT`).

#### Los defectos reales que aparecieron el 07/10

| # | Qué pasaba | Dónde | PR |
|---|---|---|---|
| 1 | **Se podía reservar un turno en el pasado.** El motor dibuja también los slots de hoy que ya pasaron y nada lo controlaba; el turno nacía inalterable y sólo se cerraba marcando `AUSENTE` a alguien que nunca faltó | `TurnoService.reservar` | #44 |
| 2 | Dos reemplazos simultáneos de la configuración de una oferta terminaban en **deadlock → 500**: cada INSERT hijo deja un lock compartido sobre la oferta y el force-increment pide el exclusivo al commitear | `AccesoALaConfiguracionDeOferta` (prácticas y habilitaciones) | #46 |
| 3 | El re-disparo de un cierre podía **descontar dos veces** la misma práctica: la autorización agotada ya no habilitaba y el reintento elegía otra. El `spi` prometía idempotencia y no la cumplía | `ConsumoDeAutorizacionService` | #47 |
| 4 | La cuenta corriente del paciente y el cobro **no distinguían quién debe**: con la fila del financiador, el paciente "debía" el arancel entero y un cobro de mostrador podía saldar la parte de la obra social. Inobservable hasta F-4 | `findDeLaPersona`, `CobroService.exigirCobrable` | #49 |
| 5 | Sacar `EN_ESPERA` del enum rompía la lectura del historial de cualquier turno que pasó por la espera: `TurnoEvento` mapeaba los estados con `EstadoTurno`. Evitado antes de mergear | `TurnoEvento` | #52 |
| 6 | El indicador `turnos-en-espera` del reporte quedaba siempre en 0 al mover la espera a la recepción. Corregido dentro del mismo PR | `TurnosEnElReporte` | #52 |
| 7 | **El débito de una presentación no persistía la marca del ítem**: la respuesta decía `DEBITADO` y la base seguía `INCLUIDO`. `registrarDebito` es un UPDATE nativo con `clearAutomatically = true` y desacoplaba el ítem cargado antes | `PresentacionService.debitar` | #57 |
| 8 | **El contrato publicaba para los reportes los schemas del Paciente 360**: springdoc nombra por el nombre simple y `IndicadorResponse`/`SeccionResponse` existían en `person` y en `reporting`. El cliente generado no podía mostrar ningún número. Lo destapó la pantalla de G-8 (web #12) | `reporting.api.dto` | #58 |

> **El patrón del día: un contrato o un UPDATE nativo que mienten sin fallar.** La fila 7 es otra
> vez un UPDATE nativo —esta vez por `clearAutomatically`, no por `version`— y la fila 8 es un
> contrato sin drift que describía otra respuesta: `OpenApiContractIT` compara el contrato contra sí
> mismo y no podía verlo. #58 sumó `SchemaNameCollisionTest`, que falla si dos records o enums de
> la capa `api` publican el mismo nombre de schema.

### Estado al 06/10/2026, sobre `d110aaf` (histórico)

> Esta subsección **supersede** a la del 29/09 que sigue abajo en contrato, migraciones y
> paquetes. Lo de abajo queda como historia: explica cómo se llegó acá. Las cifras de cobertura
> **no** se actualizan en esta subsección; viven en su propio bloque más abajo.

El 06/10 entraron a `main` **27 PR, del #13 al #39**, todos de paquetes de
[`docs/fases/01-trabajo-en-paralelo.md`](docs/fases/01-trabajo-en-paralelo.md) trabajados en
worktrees separados. `main` tiene **368 commits** y el último merge es `d110aaf` (PR #39).

| | Valor |
|---|---|
| Contrato | `openapi/akine-api.yaml` **0.54.0**, **277 operaciones** (`grep -c operationId`). El cliente del frontend en `appKine-web` `main` está en **0.46.0** |
| Migraciones | Última **`V69`** (F-3). `V65` la usó C-1. `V66`–`V68` no existen: `V67` quedó vacía (C-5 resolvió que RN-M10-002 no lleva unique) y `V66`/`V68` siguen reservadas. Ver §6 del plan de trabajo en paralelo |
| Tests | **3.044 unitarias, 0 fallos**, medidas sobre la combinación de `main` con F-3 antes de mergear #39 (`./mvnw -o test -DskipITs`). Sobre la misma combinación, **46 ITs** de `billing` + `OpenApiContractIT` + `EsquemaMultiTenantIT` en verde. La suite completa con todos los ITs no se corrió ese día |

#### Qué agregó cada minor del contrato desde 0.44.0

| Versión | Paquete · PR | Qué agrega |
|---|---|---|
| 0.45.0 | C-2 (`a5bdce2`, antes del 06/10) | API REST de la historia clínica por persona |
| 0.46.0 | B-1 (`4c8be24`, antes del 06/10) | Búsqueda de personas por número de afiliado |
| 0.47.0 | A-3 · #15 | `POST /organizations/{orgId}/notifications/{notificationId}/retry` (`retryNotification`), con `colaborador:manage` |
| 0.48.0 | F-2 · #18 | Valor `DEUDA_DEL_PACIENTE` en `HallazgoDeValidacion` / `ReparoDePresentacion` |
| 0.49.0 | C · #35 | `Sesion.estado` publica `CERRADA`, que el servidor ya emitía |
| 0.50.0 | A · #36 | `ofertaVersion` en `HabilitacionesResponse` (lectura y los dos reemplazos) |
| 0.54.0 | F-3 · #39 | Anticipo en el registro de cobro, `imputarSaldoAFavor`, `anularCobro`, `reintegrarSaldoAFavor` y los problem types `cobro-anulado`, `cobro-con-reintegros`, `saldo-a-favor-insuficiente` |

`0.51.0`–`0.53.0` no existen en `main`: F-3 se reservó `0.54.0` en su diseño
(`docs/diseno/AKINE-F-3-anticipos.md`). Todos los cambios son aditivos.

#### Paquetes que entraron el 06/10

- **Cerrados:** G-2 (#13), F-1 (#14), A-3 (#15), F-2 (#16, #17, #18), A-5 (#25 slices de
  membership, auditoría y plataforma; #26 unitarios de los cuatro servicios de F2), E-1 (#38),
  E-5 (#37).
- **Cerrados en el backend, sin pantalla:** B-5 (#34), F-3 (#39), G-3 (#29: Dockerfile, SBOM
  CycloneDX y job `imagen` en el CI; el Dockerfile del frontend sigue abierto).
- **Avance parcial:** F-8 (#19, #24, #28, #30: escenarios 33, 34, 37, 39–43, 45, 46 y 48 de
  07.03–07.05 corridos contra MySQL; faltan 35, 36, 38 y 44), G-11 (#31 `activity`, #32
  `encounter`), E-2 (#22: `AgendaDescuentaReservasIT`, la parte de backend), escenario diferido
  20 (#23) y escenarios 43–45 de 08.02 (#33).
- **Sueltos:** moneda ISO 4217 en `contracting` (#21), rol de plataforma a cuenta inexistente
  (#27), test flaky de activación concurrente de planes (#20, sólo el test).

#### Los defectos reales que aparecieron el 06/10

Veinte, uno por fila. Casi todos los destapó **un IT contra MySQL o un unitario escrito para
cubrir otra cosa**, no una revisión del código.

| # | Qué pasaba | Dónde | PR |
|---|---|---|---|
| 1 | El indicador de anulados del reporte sumaba obligaciones dadas de baja: 4000 en vez de 1000 | `ObligacionRepository.sumarAnuladoEnElReporte` | #13 |
| 2 | La cuenta corriente del financiador filtraba por la sede de la ruta y cortaba en 200 lotes | `FinanciadorPagoService.cuentaCorriente` | #14 |
| 3 | El débito movía el saldo del lote antes de validar el ítem, y podía responder 409 donde correspondía 400; el rechazo salía por `GlobalExceptionHandler` | `PresentacionService.debitar` | #16 |
| 4 | El detalle de financiadores del reporte no sumaba al indicador `prestado` (hoy no observable: no hay obligaciones del financiador) | `FinanciadoresEnElReporte` | #17 |
| 5 | Una obligación del paciente se reportaba como `FINANCIADOR_DISTINTO`, que tiene el remedio opuesto | `PresentacionService.revisar` | #18 |
| 6 | Anular un pago por transferencia sin caja abierta daba **500** por un NPE en el log; el caso que 07.05 quiso habilitar nunca funcionó | `MovimientoCajaService.revertir` | #19 |
| 7 | La moneda no se validaba contra ISO 4217: `XYZ` entraba y `PESOS` terminaba en error de base | `Convenio`, `PlanCobertura` | #21 |
| 8 | El PATCH de membership sin `changeScope` respondía 400: `boolean` primitivo y Jackson 3 rechaza el `null` | `ChangeMembershipRequest` | #25 |
| 9 | Otorgar el rol de plataforma a una cuenta inexistente respondía **201** y dejaba una fila huérfana | `PlatformRoleService.grant` | #27 |
| 10 | Un cobro que perdía la carrera contra el cierre de caja recibía `caja-saldo-insuficiente` "para egresar" en vez de caja cerrada (`REPEATABLE READ`) | `MovimientoCajaService.moverSaldo` | #28 |
| 11 | El perdedor de la carrera por una obligación recibía el `conflict` genérico en vez de `obligacion-ya-presentada` | `PresentacionService.incluir` | #30 |
| 12 | Dos obligaciones distintas agregadas a la vez al mismo borrador: la segunda moría por `@Version` | `PresentacionService.agregarItem` / `quitarItem` | #30 |
| 13 | El lote de asistencia corría cada ítem **sin transacción**: la autoinvocación no pasa por el proxy | `AsistenciaService.registrarLote` | #31 |
| 14 | La agenda unificada mostraba vacía toda clase y calculaba la capacidad sin el box | `ClasesEnLaAgendaDeTurnos.proyectar` | #31 |
| 15 | Un `valorTexto` en blanco junto a un valor NUMERICO o BOOLEANO chocaba contra el CHECK de `V55` y se perdía el tratamiento | `TratamientoParametro` | #32 |
| 16 | `promover` no movía `version`: la baja concurrente de quien esperaba pisaba la promoción y el cupo quedaba en 3 con 2 recibos | `InscripcionClaseRepository#promover` | #33 |
| 17 | El contrato publicaba `Sesion.estado` sólo con `BORRADOR` | `SesionResponse` | #35 |
| 18 | Un nombre de sede o de clase que contuviera "secret" hacía fallar cancelar o reprogramar la clase entera | `AvisosDeClase` / `SanitizedPayload` | #37 |
| 19 | Una oferta grupal que exige espacio no admitía a una segunda persona: 409 `recurso-ocupado` | `RevalidadorDeSlot.elegirEspacio` | #38 |
| 20 | `descontarSaldo` del cobro no movía `version`: una anulación vieja dejaba la deuda `ANULADA` con un cobro imputado | `CobroRepository.descontarSaldo` | #39 |

> **El patrón que más se repitió: un UPDATE nativo que no mueve `version`** (filas 16 y 20),
> el mismo mecanismo que el defecto 5 de la integración del 29/09. Toda columna que mueve un UPDATE
> nativo necesita que ese UPDATE también haga `version = version + 1`, o `@DynamicUpdate` si la
> edición nunca escribe esa columna.

### Estado al 29/09/2026, medido sobre `343ad43` (histórico)

**Todo integrado en `main`.** Las diez ramas que quedaban abiertas —04.05, 06.03, 06.04, 06.06,
07.07 y 08.01 a 08.06— entraron sobre `akine-f7-integracion`, y `main` **contiene las 25 ramas
locales**: el barrido de contención no dejó ninguna afuera. F0 a F7 completas y F9 abierta
(clases, inscripciones, asistencia, derivación y productos).

| | Valor medido |
|---|---|
| Rama | `main` = `akine-integracion-total`, **222 commits**, último `47912db`, **pusheado a GitHub el 01/10/2026** |
| Contrato | `openapi/akine-api.yaml` **0.44.0**, **267 operaciones**, sin drift y **sin un solo `operationId` desambiguado**. El cliente del frontend sigue en **0.29.0**: no hay pantallas de F4 en adelante |
| Módulos | **14**: los doce anteriores más **`reporting`** (M23, 07.06) y **`activity`** (M28/M29, F9) |
| Migraciones | **V1–V64**, menos `V26`, `V29`, `V31` y `V62`, que quedaron vacías. `V45`–`V64` se aplicaron **contra un motor por primera vez** el 29/09 |
| Tests | **2.349 unitarias + 437 de integración**, 0 fallos, 1 diferida (7b). `./mvnw verify` en **13:03 min** |
| Cobertura | **07/10/2026 (G-11, sobre `8fe1fae`):** instrucción **87,36 %** · línea **88,70 %** · rama **76,30 %**, sobre 3.297 unitarias + 737 de integración. Gate en **`0.80`** línea e instrucción, sin cambios — ver abajo. (06/10: 86,11 % · 87,36 % · 74,48 %; 29/09: 71,15 % · 73,03 % · 63,46 %, gate en 0,73/0,71) |
| Docker | **Funciona** (29.2.0). Era el bloqueo que mandaba sobre todo desde el 19/09 |

#### El gate de cobertura bajó, y es deuda, no una decisión de calidad

> **Saldado el 06/10/2026 (G-11).** `./mvnw -o verify` sobre `main` (`d110aaf`) midió el BUNDLE en
> **línea 87,36 % · instrucción 86,11 % · rama 74,48 %**, con 3.044 unitarias y 602 de integración
> en verde (1 diferida, la 7b). Los tres módulos que lo habían hundido están arriba: `billing`
> 85,75 % de línea, `encounter` 97,05 %, `activity` 96,39 %. El gate volvió a **`0.80` en línea e
> instrucción** —el tope que fija el plan, aunque lo medido da más—. Los módulos que hoy quedan más
> abajo son **`reporting` 54,24 %**, `clinical` 74,40 % y `offering` 77,51 % de línea. `BRANCH`
> sigue sin gate: 74,48 % es decisión pendiente. Lo que sigue es la historia del 29/09.
>
> **Remedido el 07/10/2026 (G-11), sobre `main` `8fe1fae`** (contrato 0.69.0): `./mvnw -o verify`
> con **3.276 unitarias y 737 de integración, 0 fallos**, 1 salteada (la 7b), y `jacoco:check` en
> verde. BUNDLE antes de los tests nuevos: **línea 87,58 % · instrucción 86,12 % · rama 75,55 %**.
> Ningún IT falló ni se colgó. Los tres módulos más bajos eran `reporting` 64,04 %, `clinical`
> 75,29 % y `offering` 79,13 % de línea, y en los tres el hueco tenía nombre: `ReporteReadIT` entra
> por el servicio, así que **nada ejercitaba el controller, los DTOs, el handler ni el CSV de
> reportes**; `DerivacionClinicaService` (derivar un participante de clase al circuito clínico)
> tenía 13 de 173 líneas cubiertas; y el cambio de fin y la baja del precio particular no tenían
> test. Con 21 unitarios nuevos —`ReporteControllerTest`, `DerivacionClinicaServiceTest` y tres
> casos en `OfertaPrecioParticularServiceTest`— el BUNDLE queda en **línea 88,70 % · instrucción
> 87,36 % · rama 76,30 %**: `reporting` 93,86 %, `clinical` 82,09 %, `offering` **79,86 %** (sigue
> apenas abajo del 80: lo que falta es la capa REST de servicios, habilitaciones y precios, sin
> slice de `@WebMvcTest`). El "después" se midió agregando los tests nuevos al `jacoco.exec` de la
> corrida completa, con `src/main` sin cambios. `BRANCH` sigue sin gate: 76,30 %.

No bajó por la integración: bajó porque entraron diez etapas cuya cobertura **nunca se había
medido**, porque `jacoco:check` corre en `verify` y Docker estuvo caído todo ese tramo. El faltante
está repartido —**`billing` 35,1 %, `encounter` 40,8 %, `activity` 45,2 %**— y ningún subconjunto
publicable llegaba al 0,80: sin F9 da 72,95 % y sin F9 ni `billing`, 77,77 %.

El número quedó **justo debajo de lo medido a propósito**: así el gate sigue siendo un trinquete
—cualquier etapa que baje la cobertura rompe el build— y no una puerta abierta. Devolverlo a 0,80
es escribir los tests de 07.03–07.06 y 08.01–08.06, y es una etapa propia. `BRANCH` sigue sin gate.

#### Lo que la primera corrida real dejó al descubierto

Siete defectos, ninguno buscado. **Tres impedían arrancar o corrompían datos**, y los encontró
ejecutar: no leer el código.

| # | Defecto | Dónde nació |
|---|---|---|
| 1 | El `saveAll` de tres puertos dejaba la aplicación **sin arrancar**: el puerto declara `List<X> saveAll(List<X>)` y `JpaRepository` declara `saveAll(Iterable<S>)`, así que Spring Data lo tomaba por consulta derivada. Error de **creación de bean**, no de compilación | 04.03, 04.04, 08.03 |
| 2 | `V64` no ejecutaba: repetía nombre de **foreign key** con `V50`, y después lo mismo con los **CHECK**. En MySQL esos nombres son únicos **por esquema**, no por tabla | 08.06 |
| 3 | La subida concurrente de un adjunto clínico terminaba en **500**: la relectura idempotente no ve la fila del ganador bajo `REPEATABLE READ` | 04.02 |
| 4 | Un plan **suspendido no ocupaba el lugar del activo**: el caso quedaba con dos planes vivos y reanudar el frenado era imposible | 04.04 |
| 5 | Una edición concurrente **pisaba el consumo** de una autorización: `descontarSaldo` es un UPDATE nativo que no toca `@Version` y sin `@DynamicUpdate` el flush reescribía `cantidad_consumida` | 04.05 |
| 6 | La **paginación del timeline perdía eventos**: seis hechos de dos en dos devolvían cuatro, con 200 y sin señal | 04.02 |
| 7 | **31 `operationId` duplicados**, desambiguados por springdoc con sufijo numérico | varias |

> **Un test que no se puede correr no es verificación.** Las etapas que cerraron sin Docker
> cerraron sin saber qué no funcionaba. Los ~108 ITs que 04.02–04.05 dejaron escritos encontraron
> cinco de estos siete **la primera vez que se ejecutaron**.

#### Dos conductas que se documentan en vez de cambiarse

- **`cambiarEquipo` devuelve la versión LEÍDA, no la nueva.** El force-increment la incrementa al
  commitear, después de armar la respuesta, así que encadenar dos escrituras da 409 y la pantalla
  tiene que repedir. La alternativa —ensuciar también el padre— hace avanzar la versión **dos**
  veces. Las habilitaciones de una oferta tenían la misma conducta y **ya no**: desde el contrato
  0.50.0 su respuesta publica `ofertaVersion = leída + 1`, que es exacta porque el reemplazo no
  ensucia la oferta y el único avance es el forzado (`HabilitacionesVersionForzadaIT`).
- **La especificación funcional vive ahora en `docs/producto/`**, dentro de este repo. Hasta el
  29/09 estaba en `AKINE/docs/`, fuera de todo git: sin historial y sin copia.

### Reglas que cada etapa dejó fijadas y el resto hereda

El detalle vive en cada registro de cierre. Acá está lo que cuesta caro olvidar.

| Etapa | Regla que hereda el resto |
|---|---|
| **01.01** | `application` consume **puertos en `domain`**, nunca repositorios de `infrastructure`. Cross-tenant → **404**, nunca 403 (un 403 confirma que existe). Falta de contexto → **403**, nunca 401 (el interceptor del frontend borra el token ante cualquier 401 → bucle de login). La auditoría se escribe **en la transacción del negocio**. `organization` jamás importa `identity` |
| **01.02** | Las tablas de `identity` **no llevan `organization_id`** (ADR-0019). Login, registro y solicitud de recuperación responden **igual exista o no la cuenta** (ADR-0018). Access token en memoria, refresh en cookie `httpOnly` con **rotación estricta** (ADR-0017). Los dos endpoints que se autentican por cookie validan `Origin` contra la lista blanca. Los correos salen por el **outbox transaccional**, nunca desde el servicio |
| **01.03** | Los permisos se resuelven por **membership vigente**; nada de roles hardcodeados en los servicios |
| **02.01** | Orden de bloqueo único `subscription → organization`; invertirlo reintroduce un deadlock documentado |
| **02.02** | Se commiteó sin registro de cierre; el suyo **se declara reconstrucción, no acta**. Única etapa cerrada sin diseño propio en `docs/diseno/` |
| **02.03** | `MAX_MIEMBROS_ACTIVOS` ahora **sí** se aplica: cualquier alta de membership puede recibir 409 por tope de plan |
| **02.04** | Disponibilidad ≠ turno; la efectiva se calcula al leer y **no se materializa**; el lock de escritura es la fila de `consultorio_calendario` de la sede y se toma **antes** de leer nada |
| **02.05** | Filtrar por `owner_key = IFNULL(organization_id, 0)`, nunca por `organization_id`: varios `NULL` no colisionan en MySQL (ADR-0021) |
| **02.06** | La baja de un servicio **no cascadea**: las ofertas que ya lo prestaban siguen operando. Un catálogo global va exceptuado de `TenantContextFilter`; las ofertas no pueden estarlo. Cada módulo necesita su `ApiActor` **con nombre propio** o Spring no arranca |
| **02.07** | Un `@Version` sobre el padre **no protege** escrituras que sólo tocan tablas hijas: hace falta `OPTIMISTIC_FORCE_INCREMENT` (`b8bbc67`) |
| **03.01** | Una `Persona` **no es** un `Paciente`: dos tablas, ninguna columna `es_paciente`, y una sola clase inserta en `perfil_paciente`. La persona es de la **organización**, no de la sede |
| **03.02** | El 360 **crece por contribuyentes, no por campos**, y cada contribuyente declara **su** permiso: si `person` preguntara a `scheduling`/`clinical` cerraría un ciclo. "Según permisos" **recorta, no rechaza** — la sección viaja en `seccionesOmitidas`. La baja de una persona **da de baja su perfil**. En adjuntos: **fila antes que blob**, y el tipo de un archivo **lo deciden sus bytes**, no la extensión ni el `Content-Type` |
| **03.03** | `contracting` nace como módulo propio. Su `spi` separa a propósito **lectura viva** (`find*`, `planesSeleccionables`, para decidir) de **copia** (`congelar`, para guardar): confundirlas rompe la estabilidad de las referencias históricas. **No hay regla de no-solapamiento entre planes** y no es un olvido: varios planes vigentes a la vez son el caso normal |
| **03.04** | Primer consumidor de `contracting.spi`, y usa **sólo `congelar`**, sólo en el alta: después ninguna lectura vuelve a tocar `contracting`. Esa ausencia de llamadas **es** la garantía, y un `verifyNoMoreInteractions` la hace ejecutable |
| **03.05** | RN-M16-002 —convenios y aranceles que no se solapan— **ningún índice la expresa**, y hay un test que comprueba que MySQL **acepta** los dos solapados para que nadie intente "arreglarlo" con un índice. La hace cumplir el lock de `convenio_lock` con las tres condiciones de siempre. Los requisitos del convenio quedaron **declarados, no resueltos** |
| **03.06** | Cierra ese hueco: la orden o autorización registrada es lo que satisface el requisito, y `consultarElegibilidadAdministrativa` lo responde. **El consumo clínico sigue afuera a propósito** — hoy ningún flujo la invoca (es 04.05) |
| **04.01** | La HC es de la **organización** (DP-03). Todo acceso clínico exige **justificación declarada** hasta que `RelacionAsistencialProbe` tenga implementación real, y **toda lectura se audita**, no sólo las mutaciones |
| **04.02** | **EN CURSO, no cerrada.** El timeline **no tiene tabla**: se calcula al leer agregando `clinical.spi.EventoClinicoContributor`, y pagina por **keyset descendente con cursor opaco** —un offset sobre fuentes heterogéneas se desordena en cuanto una inserta—. **El Turno no contribuye**, y es decisión: ninguna transición administrativa prueba que una prestación ocurrió (RN-M09-005, DP-05) |
| **05.01** | El slot se calcula al leer y **no se persiste**; un día sin slots nunca viaja sin su `MotivoSinSlots` |
| **05.02** | Ningún unique expresa solapamiento de intervalos: la regla la hace cumplir el lock de `agenda_sede`, y **con `REPEATABLE READ` el lock no alcanza** — las mutaciones que serializan van en `READ_COMMITTED` |
| **05.03** | **Cancelar libera el lugar; una ausencia no** (`AUSENTE` deja `deleted_at` en NULL: la hora se consumió igual). Un turno con Sesión registrada no se cancela ni se mueve: **409 `turno-con-atencion`**. **Reprogramar MUEVE el turno**, no cancela y crea otro — la Sesión cuelga del `turno_id`. `turno_evento` es **append-only**. La revalidación vive en `RevalidadorDeSlot` y lleva `turnoExcluidoId`, o el turno choca **contra sí mismo** |
| **05.04** | El check-in es un estado de la **reserva**, no de la atención (DP-05): dos actos de dos personas, ninguno bloquea al otro. **La hora la pone el servidor.** Check-in idempotente; deshacerlo **no**. PHI mínima: `TurnoDelDiaView` es un tipo aparte y no campos opcionales. Hallazgo de plataforma: **MySQL redondea `DATETIME(6)`, no trunca** — comparar siempre lo almacenado |
| **06.01** | Sesión ≠ Turno (DP-05). La **propiedad no es un permiso**: escribir en la atención ajena es **409, no 403**. El control optimista **es** el autosave |
| **06.02** | Todo lo clínico es nullable —"seguimiento no exige examen completo"—: se valida lo que sería FALSO, no lo que falta, y con 400 |
| **06.05** | El correlativo se asigna con `UPDATE ... ultimo_numero + 1`, no con `MAX+1`, y la idempotencia se evalúa **antes** de pedir número. Cerrar no cobra (DP-06) |
| **07.01** | Deuda, cobro y caja son tres cosas. La deuda se devenga **dentro** de la transacción del cierre (`billing → encounter`), con snapshot congelado. `DECIMAL`, nunca `float` |
| **07.02** | Reconstrucción, no acta. El saldo se descuenta con un **UPDATE condicional** —`SET saldo = saldo - :importe WHERE saldo >= :importe`—, no con un lock: cero filas es **409**. Tres tablas y no una (cobro, medios, imputaciones). Las dos sumas son invariantes **de la aplicación**: MySQL no admite subconsultas en un `CHECK`, y la comparación usa `compareTo`, no `equals` (`8500` ≠ `8500.00`). Las tablas hijas llevan `organization_id` igual |

**Dos reglas transversales que este proyecto ya pagó varias veces**, y que no son de una etapa sola:

- **Toda fila-lock se crea en una transacción aparte**, con `INSERT ... ON DUPLICATE KEY UPDATE`.
  La creación perezosa dentro de la transacción que la bloquea produce deadlock, y el `try/catch`
  no salva: atrapar una excepción de persistencia no des-marca la transacción y Spring lanza
  `UnexpectedRollbackException` al commitear. Ya se pagó cuatro veces.
- **Las versiones de migración se reservan ANTES de escribir código**, y **dos sesiones en el mismo
  árbol de trabajo rompen Flyway y `target/`.** `V26` quedó vacía porque 02.07 y 03.01 nacieron las
  dos como `V26`. F3 lo hizo bien con worktrees: `V39`–`V44` consecutivas, sin una colisión.

> **02.05 se ejecutó antes que 02.03 y 02.04.** No es un salto arbitrario: §14 del plan declara
> que depende únicamente de 01.03.

#### Las cuatro que dejó la integración del 29/09

Ninguna sale de un diseño: las cuatro se pagaron corriendo el sistema entero por primera vez.

- **En MySQL los nombres de `FOREIGN KEY` y de `CHECK` son únicos POR ESQUEMA, no por tabla.** Una
  tabla `movimiento_pase` con un `fk_movimiento_organization` choca contra el de
  `autorizacion_movimiento`, que se creó catorce migraciones antes. El error aparece **sólo al
  ejecutar**, y se lleva puesto el arranque entero del contexto. Nombrá las constraints con la
  tabla adelante.
- **Un método del puerto con una firma que `JpaRepository` no implementa literalmente deja la
  aplicación sin arrancar.** `List<X> saveAll(List<X>)` **no** es `saveAll(Iterable<S>)`: Spring
  Data lo toma por consulta derivada y falla con `No property 'saveAll' found`. Es error de
  creación de bean, no de compilación, así que **ningún test unitario lo ve**. Si el puerto declara
  una firma propia, el repositorio necesita un `default` que delegue.
- **Toda relectura que resuelve un choque contra un unique va en `READ_COMMITTED`.** El perdedor de
  la carrera relee la fila del ganador, y bajo `REPEATABLE READ` no la ve: la foto se fijó antes de
  que el otro commiteara. La idempotencia prometida termina en 500. Es la regla de 05.02 extendida:
  no alcanza con que el INSERT viva en su propia transacción.
- **Una columna que mueve un UPDATE nativo necesita `@DynamicUpdate` en la entidad.** Si no, el
  flush de cualquier edición la reescribe con el valor que leyó, el `WHERE version = N` pasa igual
  —el UPDATE nativo no tocó la versión— y el contador vuelve atrás **sin que nada falle**.

### Deuda de verificación, declarada y sin saldar

- **El QA manual del §6 no corre desde 02.02** — veinte etapas. §6 lo declara **bloqueante para
  deploy**: están cerradas con esa deuda escrita, no saldada.
- **Los E2E cubren la vertical de turnos y nada más.** Seis specs en `appKine-web/e2e/`; los dos de
  agenda sintetizan la respuesta HTTP con `route.fulfill`, así que **no prueban que el backend emita
  esos `problemType`**: si el backend renombra uno, la suite sigue verde y la pantalla queda rota.
  **No hay E2E de la vertical clínica, ni de F3, ni de cobros.**
- **Escenario diferido 7b** —conflicto de `Idempotency-Key` por payload distinto—: necesita
  `request_hash` en `onboarding_registro`, o sea migración más cambio de API. `docs/tests-diferidos.md`.
- ~~**Escenario diferido 20**~~ —que la `@Version` forzada de 02.07 realmente avance contra una
  base real— **corrido el 06/10/2026**: `HabilitacionesVersionForzadaIT` (`7dcae17`, PR #23) ve
  avanzar la versión una sola vez por reemplazo y el segundo guardado con la versión vieja choca.
  Sigue abierto el hueco que 04.02 sumó para la numeración de versiones de entrada clínica.
- ~~**`ReservaProbeSobreTurnos`** nunca se ejerció contra el stack real~~ — **cubierto el
  06/10/2026** por `AgendaDescuentaReservasIT` (PR #22), verificado por mutación. Sigue sin E2E.
- **Suites concurrentes que sí se ejercieron contra MySQL real**, cuando había Docker:
  `TurnoConcurrenteIT`, `DisponibilidadIT`, `CierreConcurrenteIT`, `CobroConcurrenteIT` y las de
  solapamiento de coberturas y convenios.

### Decisiones pendientes del usuario

1. ~~**Cómo se toma posesión del `PLATFORM_ADMIN` sembrado por `V15`.**~~ **Resuelta por DP-14
   (07/10/2026) e implementada en A-4**: al arrancar, si no hay ningún administrador de plataforma
   con credencial y está definida `AKINE_BOOTSTRAP_ADMIN_EMAIL`, la cuenta sembrada pasa a esa
   casilla y se le encola un enlace de activación por el outbox (`AGENT.md` §12, imagen Docker;
   `docs/diseno/AKINE-A-4-bootstrap.md`). ADR-0018 no se tocó. Si la casilla ya es de otra cuenta
   no se hace nada y **no** se le otorga el rol: queda a confirmar.
   > Dato relacionado: **otorgar `PLATFORM_ADMIN` a una cuenta le quita sus permisos de tenant.**
2. **Si se agrega el gate de `BRANCH` a JaCoCo.** La rama viene abajo del 72 %: agregarlo hoy rompe
   el build, así que primero hay que subir la cobertura. El frontend **sí** lo gatea, y por eso la
   suya no cayó — es la diferencia práctica entre tener el gate y no tenerlo.
3. ~~**Cómo se unifican `concurrent-modification` y `conflict`.**~~ **Resuelta por DP-21
   (08/10/2026), contrato 0.78.0**: toda versión vieja responde `concurrent-modification` desde
   `GlobalExceptionHandler`, en todos los módulos; `conflict` queda para el negocio. Lo que sigue
   es la historia. Varios contratos publicados prometen
   un tipo de error que su código no devuelve: `resource`, `espacio` y `catalogo` lanzan el
   `OptimisticLockingFailureException` plano, que `GlobalExceptionHandler` mapea a `conflict`,
   mientras `concurrent-modification` lo emite sólo `OrganizationProblemHandler`. Corregido sólo
   para M05; unificarlo cambia respuestas de todos los módulos.
4. **Que un `ORG_ADMIN` pueda cambiar el plan de su organización.** Decidido y sin implementar: el
   plan `BASICO` permite una sola sede y el cambio está reservado a `PLATFORM_ADMIN`, así que un
   centro que se registra no puede abrir su segunda sede. Falta resolver el cobro.

> **Las de abajo las abrieron los PR del 06/10/2026.** Cada una quedó implementada con un
> criterio provisorio que el PR declara; lo que falta es confirmarlo o cambiarlo.

5. ~~**Qué permiso pide la sección `coberturas` del Paciente 360** (#34).~~ **Resuelta por DP-22
   (#76, 08/10):** la sección declara `paciente:read`. Lo que sigue es la historia. `permisoRequerido()` era
   `null`: alcanzaba con pertenecer al tenant, el mismo criterio con el que `CoberturaPacienteService`
   lista las coberturas, porque la matriz no tiene `cobertura:read` ni `paciente:read`. Por eso la
   sección nunca cae en `seccionesOmitidas`. El número de afiliado viaja enmascarado.
6. **La contradicción del documento de force-increment** (#36). `docs/fases/deuda-force-increment.md`
   daba a `offering` como sitio donde la versión no avanza; los ITs contra MySQL muestran que sí.
   El documento ya está corregido para `offering` (06/10). Queda decidir si `clinical/cambiarEquipo`
   se mide con un IT o se pasa al `UPDATE` condicionado de `Sesion` sin medir.
7. **Qué eventos de turno notifican** (#37). Hoy avisan reservar, cancelar y reprogramar.
   **Confirmar no avisa** (RF-M26-002 habla de la creación) y **marcar ausente tampoco**. El aviso
   de cancelación sale aunque la oferta ya no resuelva, sin nombre de servicio.
8. **Dos límites de las sondas de impacto** (#38). **(a) ~~Cota superior~~ — corregida por A-11
   (#64, 08/10): la cuenta es exacta.** Lo que sigue de (a) es la historia. `DisponibilidadImpactProbe` informa una
   **cota superior**: también cuenta turnos de otro bloque vigente del mismo profesional, porque el
   `spi` recibe la ventana y no el bloque; corregirlo cambia el `spi` de `resource`. **(b)** Hay una
   **carrera residual en la baja de sede**: la baja toma el lock del tenant y la reserva el de
   `agenda_sede`, y la reserva valida la sede activa antes de su lock, así que puede colarse un turno
   entre la sonda y el commit de la baja. Cerrarlo exige revalidar la sede bajo el lock de agenda.
   Además el impacto de desvinculación muestra una sola fuente, y mostrar las dos es un cambio de
   contrato.
9. **Permisos de anular y reintegrar un cobro, y anticipo explícito** (#39). Anular y reintegrar
   exigen `cobro:register` **y** `caja:operate`, no un `cobro:annul` propio: la matriz §32 no tiene
   fila y hoy los dos permisos los tienen los mismos roles. El anticipo **se declara**, no se infiere
   del sobrante. También queda por confirmar que la imputación posterior exija la misma sede y que
   un cobro con reintegros no se pueda anular.
10. **El permiso del reintento de notificaciones** (#15). Reusa `colaborador:manage` porque hoy las
    únicas notificaciones con `organization_id` son invitaciones. Con los avisos de turno de E-5 en
    el outbox, conviene revisarlo.

> **Las de abajo las abrieron los PR del 06/10 a la noche y del 07/10.** Igual que las anteriores,
> cada una quedó implementada con el criterio que su PR o su diseño declara.

11. **DU-13: catálogo global de financiadores** (A-7, #53). El centro **referencia** la fila global
    (`owner_key` en `financiador`/`plan_cobertura`, que reabre todas las lecturas de `contracting`)
    o la **adopta** como copia (tabla global aparte). Recomendada la copia. Sin decidirla, esa parte
    de A-7 no se implementa (`docs/diseno/AKINE-A-7-plataforma.md` §3).
12. **"Atender como Particular"** (E-7, #56). ~~**(a)** Particular manda sobre el convenio~~ y
    ~~**(b)** oferta sin precio + Particular → no se devenga nada~~: **resueltas por DP-17 (#72,
    08/10)**, que confirma (a) y reemplaza (b) por un 409 `oferta-sin-precio` en el cierre. Sigue
    abierta **(c)**: es una foto del cierre; si la recepción cambia después, la deuda no se
    recalcula. Y una nueva de E-7b: **con arancel total cero no hay deuda ni se exige precio**.
13. **El horario general de la sede** (A-8, #60). ~~**(a)** Es informativo~~ — **resuelta por
    DP-19 (A-8b, #73, 08/10): limita la agenda para todas las ofertas.** A-8b dejó declarados sus
    límites: una **apertura especial** de la sede fuera del horario general también queda
    recortada (regla estricta, ¿se quiere así?), el turno de una oferta sin profesional que queda
    fuera no aparece en `impactoDelHorario`, y no hay consulta previa del cambio de horario. **(b)** El
    onboarding compuesto de la primera sede sigue sin box ni horario (ADR-0008 no se tocó); se
    cargan después por espacios y por el `PUT` del calendario, que A-8 agregó por eso. **(c)** Un
    alta con complemento sin ninguna extensión registrada falla con `IllegalStateException` en vez
    de descartar el box y el horario.
14. **El estado de una serie y el prepago antes del check-in** (E-8, #59). ~~El estado derivado tiene
    dos valores: `FINALIZADA` no distingue cancelada de terminada~~ — **resuelta por DP-20 (E-8b,
    #71, 08/10): `CANCELADA` es un tercer valor.** Sigue abierto: antes del check-in el prepago se muestra `PENDIENTE` aunque el paciente después resulte con
    cobertura.
15. **Cobertura por oferta** (B-3, #55). ~~**(a)** La importación masiva de convenio (RF-M16-007)
    quedó diseñada y fuera~~ — **hecha en B-7 (#63 · web #28, 08/10)**, sin modo parcial ni cierre
    automático de vigencias previas. **(b)** El arancel por oferta es una columna de
    `convenio_arancel` con la práctica obligatoria, no una tabla `convenio_oferta`. **(c)** Una
    cobertura aplica con la primera práctica que resuelve, la principal primero, y no hay desempate
    entre aranceles del mismo nivel. **(d)** El precio particular no edita su importe: sólo se
    cierra o reabre la vigencia. **(e)** La elegibilidad administrativa y la autorización siguen
    resolviendo por práctica, sin oferta.
16. **Prepago de recepción** (E-6, #54). La política vive sólo en la oferta, sin valor por defecto
    por consultorio. El prepago **avisa y no bloquea**, porque ningún RF pide bloquear. Con
    cobertura no hay aviso: la recepción no calcula el coseguro. Se imputa sólo a la deuda de esa
    sesión —lo que sobra no va a deudas viejas—, y si la imputación posterior falla no hay
    reintento ni bandeja de pendientes, sólo el log.
17. **La obligación del financiador** (F-4, #49). Una obligación por responsable y por sesión, no
    por práctica (cambiarlo cambia el unique de `V36`). No se cobra la diferencia particular −
    arancel (RF-M18-011 pide una configuración que no existe). `admite_obra_social` de la oferta
    decide si se busca cobertura. El copago del plan no interviene: el coseguro sale del arancel.
    **RF-M21-003 todavía no bloquea** por orden, autorización ni credencial: el dato está congelado
    y falta decidir si cada faltante es reparo o aviso.
18. **Recepción** (E-4, #52). No hay salida "se retiró sin ser atendido" y Particular desde
    `EN_ESPERA` no se admite. Una instancia vieja que escriba `EN_ESPERA` choca contra
    `ck_turno_estado`: hay ventana de despliegue. Contraer las columnas de llegada de `turno` queda
    para una migración posterior.
19. **Lo que dejaron abierto C-4, E-3, C-6, A-9 y G-1** (#47, #44, #43, #46, #50). C-4: no se puede
    descartar una alerta de consumo sin revertirlo, una sesión sin caso consume una autorización
    atada a un caso, y una autorización puede quedar atada a planes de dos casos. E-3: alta de serie
    todo o nada (un feriado la hace fallar entera), tope de 52, un aviso por ocurrencia y
    confirmación por cantidad y no por lista. C-6: el permiso reforzado y la ventana temporal para
    enmendar siguen siendo `sesion:register` más propiedad. A-9: lista vacía significa que la oferta
    no declara prácticas. G-1: el alcance limitado del profesional viaja como advertencia y no como
    campo, y `PLATFORM_ADMIN` lee en `SOPORTE` aunque la matriz diga "Global".

> **Las de abajo las abrieron los PR del 08/10 al 11/10.** Las del plan en paralelo que siguen
> abiertas son **DU-10** (`egreso:manage` y adjunto del egreso), **DU-11** (parámetros por práctica
> y si un observador que falla hace fallar el cierre), **DU-12** (F9 y `clase:*`) y **DU-13** (la
> 11 de arriba). DU-5, DU-6 y DU-8 se resolvieron como DP-21, DP-22 y DP-23.

20. **SAST, SCA y DAST** (G-5, #78). **SCA ya está** desde G-6 (#83): Dependabot y
    dependency-check bloqueante con CVSS ≥ 7, con respaldo OSV.dev. Falta decidir el SAST
    —CodeQL, o SonarQube con el job que ya existe y se saltea sin secretos— y si va un DAST (ZAP
    baseline no bloqueante, recomendado).
21. **La justificación de acceso en `encounter`** (G-5). Header obligatorio en el contrato,
    opcional y auditado, o declarar que la propiedad de la sesión ya es relación asistencial.
22. **`caso:create`** (G-5). Cablearlo, o enmendar la matriz para absorberlo en `hc:write`, que es
    el efecto observable desde #78.
23. **`mysql-connector-j` 9.7.0 con CVE sin corrección** (G-6, #83). Esperar la versión corregida
    o bajar a 9.6.0. La excepción vence el **09/11/2026** y desde ese día el job
    `vulnerabilidades` falla.
24. **El débito parcial de una presentación** (G-12, #84). Deja la obligación del financiador
    `PENDIENTE` por el total después de conciliar, aunque el resto se cobró (07.04 §8).
25. **La situación de la orden médica** (B-4, #75). Ningún RF de M17 define una máquina de la
    orden; los ocho valores de `SituacionOrdenMedica` y su precedencia (`ANULADA` > `CUMPLIDA` >
    `VENCIDA` > `EN_CURSO` > `AUTORIZADA` > `EN_TRAMITE` > `RECHAZADA` > `SIN_AUTORIZACION`) son
    del diseño y conviene confirmar los nombres.

### Huecos funcionales conocidos

- ~~**El OpenAPI no declara ningún `securityScheme`**~~ — **lo declara**: `bearerAuth` en
  `components.securitySchemes` y como seguridad global del contrato (G-5 lo da como resuelto en
  07.07; verificado en el YAML de `81344c5`).
- ~~**No existe `paciente:read`**~~ — **resuelto por DP-22 (#76, 08/10)**: el padrón exige
  `paciente:read`, que tiene sólo el personal; el rol `PACIENTE` recibe 403. El alcance `OWN`
  (autoservicio del paciente, vínculo cuenta↔persona) queda para después del MVP.
- ~~**`consultarElegibilidadAdministrativa` no tiene consumidor.**~~ **La consume la recepción
  desde E-4** (#52), por `person.spi.ElegibilidadAdministrativaDirectory`: lo que no cumple queda
  `OBSERVADA`, nunca un 4xx. La sesión y la obligación siguen sin consultarla.
- ~~**RF-M06-005 no cierra de punta a punta**~~ **Cierra desde el 07/10:** backend en A-7 (contrato
  0.64.0, `GET /me/platform-role` y aprobar publica el concepto global) y la consola de plataforma
  en `appKine-web` #13. Sin E2E ni prueba contra el backend real.
- **Catálogo global de financiadores (F3):** diseñado en A-7 y sin implementar, espera **DU-13**
  —referencia con `owner_key` o copia adoptada— (`docs/diseno/AKINE-A-7-plataforma.md` §3).
- ~~**CA-M03-002 parcialmente cubierto**~~ **Cubierto en el backend por A-8** (`V83`, contrato
  0.69.0): el alta de sede acepta `primerBox` y `horarioGeneral` y los crea en la misma
  transacción. Desde DP-19 (A-8b, #73) el horario general **limita la agenda**; antes era
  informativo. La pantalla
  entró en `appKine-web` #22. El onboarding compuesto de la primera sede sigue sin box ni horario:
  se cargan después por espacios y por el `PUT` del calendario.

### Próximo paso concreto

> Reescrito el 11/10/2026. El del 07/10 pedía el job E2E del CI, las pantallas de F-3, A-9,
> reprogramación de serie y reintento de notificaciones, y la migración de B-4: todo entró entre
> el 08/10 y el 11/10. Siguen en pie el QA manual y las pantallas clínicas.

1. **Ejecutar el QA manual del §6 con la guía de G-12** (`docs/qa/`), sin correr desde 02.02 y
   bloqueante para deploy. La guía —once circuitos, 120 pasos, 127 consultas validadas— está
   lista; la ejecución es del usuario. Las pantallas de B-7, A-11, A-9, F-3, E-3, B-4, A-8b y los
   permisos otorgables sólo se probaron con `HttpTestingController`.
2. **El traba del pool de Hikari que midió G-10**: con escrituras concurrentes el backend deja de
   responder. Es el hallazgo crítico de `docs/carga.md` §6 y bloquea cualquier despliegue con más
   de un puñado de usuarios. Después, el lock de `comprobante_numerador` y el 401 ante un fallo de
   base.
3. **Decidir Connector/J antes del 09/11** (decisión 23): ese día vence la excepción y el job
   `vulnerabilidades` se pone rojo.
4. **Las pantallas clínicas**: la historia clínica está en el PR #4 de `appKine-web`, abierto y sin
   tocar desde el 06/10 (D-a, D-b, D-c); faltan D-d, D-e, D-f, D-g y los egresos y pagos a
   profesionales (F-5, espera DU-10).
5. Las **decisiones pendientes del usuario** que siguen vivas: DU-10, DU-11, DU-12, DU-13 y las
   numeradas arriba que no están tachadas (de la 20 a la 25 son nuevas). La más cara de postergar sigue siendo la 17: **RF-M21-003 no
   bloquea** una presentación sin orden, autorización ni credencial.

Pendientes que arrastra el backend:

- [x] ~~Protección de rama en `main`~~ — hecha: checks obligatorios "Build, test y contrato" e "Imagen Docker y SBOM", y `main` es la rama por defecto (API de GitHub, 11/10)
- [ ] Activar SonarQube: el job `sonar` del CI ya corre y se saltea limpio; falta cargar `SONAR_TOKEN` y `SONAR_HOST_URL` (G-6, `docs/calidad.md` §3)
- [ ] Corregir los hallazgos de G-10 (`docs/carga.md` §6): traba del pool por `REQUIRES_NEW` anidado, lock de `comprobante_numerador`, 401 ante fallo de base, N+1 de la agenda del día y `start-period` del healthcheck
- [ ] Tests de controller para `billing`, `person`, `scheduling` y `clinical`, los cuatro módulos críticos debajo de 0,90
- [ ] Revisar los cuatro PR de Dependabot abiertos el 11/10 (#87–#90; uno propone Temurin 25 en la imagen, que es un salto de Java)
- [x] ~~Observabilidad: logging JSON, Prometheus, OpenTelemetry~~ — G-4, `docs/observabilidad.md`
- [x] ~~Reglas de cobertura por módulo crítico~~ — G-6: trinquete por módulo en lo medido; desde `akine-cobertura-capa-rest` los siete críticos pasan el 0,90 (`docs/calidad.md` §4)
- [ ] Cargar `NVD_API_KEY` en los secretos del repo: sin ella el job `vulnerabilidades` analiza contra OSV.dev en lugar de OWASP (el NVD da 429 a los runners sin clave)
- [ ] Gate de `BRANCH` en JaCoCo, después de subir la cobertura de rama
- [ ] Completar los `PENDIENTE(F1)` de `.claude/qa-config.md`
- [ ] Escenario diferido 7b: `request_hash` en `onboarding_registro`

## 8. Checklist de cierre de tarea

- [ ] Tests en verde (unit + integración + ArchUnit)
- [ ] `/simplify` corrido
- [ ] `/code-review` sin hallazgos abiertos
- [ ] Contrato OpenAPI actualizado si cambió la API
- [ ] QA manual contra la DB si el cambio toca persistencia
- [ ] `superpowers:verification-before-completion` con evidencia real
- [ ] `mem_save` de decisiones y descubrimientos
- [ ] `mem_session_summary` antes de cerrar
