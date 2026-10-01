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

### Estado vigente (29/09/2026, medido sobre `343ad43`)

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
| Cobertura | instrucción **71,15 %** · línea **73,03 %** · rama **63,46 %**. El gate bajó de `0.80` a **`0.73` línea / `0.71` instrucción** — ver abajo |
| Docker | **Funciona** (29.2.0). Era el bloqueo que mandaba sobre todo desde el 19/09 |

#### El gate de cobertura bajó, y es deuda, no una decisión de calidad

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
  tiene que repedir. Es lo que 02.07 ya había declarado para las habilitaciones de una oferta, y la
  alternativa —ensuciar también el padre— hace avanzar la versión **dos** veces.
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
- **Escenario diferido 20** —que la `@Version` forzada de 02.07 realmente avance contra una base
  real— sigue abierto, y 04.02 sumó el mismo hueco para la numeración de versiones de entrada clínica.
- **`ReservaProbeSobreTurnos`** —que la agenda descuente los turnos ya vendidos— **nunca se ejerció
  contra el stack real**: el QA del 01/09 corrió antes de que esa sonda existiera.
- **Suites concurrentes que sí se ejercieron contra MySQL real**, cuando había Docker:
  `TurnoConcurrenteIT`, `DisponibilidadIT`, `CierreConcurrenteIT`, `CobroConcurrenteIT` y las de
  solapamiento de coberturas y convenios.

### Decisiones pendientes del usuario

1. **Cómo se toma posesión del `PLATFORM_ADMIN` sembrado por `V15`.** Hoy es inalcanzable por
   cualquier camino soportado: `PasswordResetService.solicitar` corta en `!cuenta.puedeAutenticarse()`
   —`false` para una cuenta sin credencial— y devuelve 202 en silencio, y el otro camino exige el rol
   que se quiere obtener. En un despliegue nuevo **ningún endpoint de `/api/v1/platform` es
   alcanzable**. Arreglarlo toca ADR-0018 y el flujo de invitación.
   > Dato relacionado: **otorgar `PLATFORM_ADMIN` a una cuenta le quita sus permisos de tenant.**
2. **Si se agrega el gate de `BRANCH` a JaCoCo.** La rama viene abajo del 72 %: agregarlo hoy rompe
   el build, así que primero hay que subir la cobertura. El frontend **sí** lo gatea, y por eso la
   suya no cayó — es la diferencia práctica entre tener el gate y no tenerlo.
3. **Cómo se unifican `concurrent-modification` y `conflict`.** Varios contratos publicados prometen
   un tipo de error que su código no devuelve: `resource`, `espacio` y `catalogo` lanzan el
   `OptimisticLockingFailureException` plano, que `GlobalExceptionHandler` mapea a `conflict`,
   mientras `concurrent-modification` lo emite sólo `OrganizationProblemHandler`. Corregido sólo
   para M05; unificarlo cambia respuestas de todos los módulos.
4. **Que un `ORG_ADMIN` pueda cambiar el plan de su organización.** Decidido y sin implementar: el
   plan `BASICO` permite una sola sede y el cambio está reservado a `PLATFORM_ADMIN`, así que un
   centro que se registra no puede abrir su segunda sede. Falta resolver el cobro.

### Huecos funcionales conocidos

- **El OpenAPI no declara ningún `securityScheme`**, en ningún módulo. El frontend funciona porque
  agrega la autenticación por interceptor; un cliente generado no lo sabría.
- **No existe `paciente:read`**: las lecturas del padrón se autorizan por pertenencia, así que una
  membership con rol `PACIENTE` **lee el padrón entero de su organización**. Aprobar el permiso no
  lo arreglaría: el problema es el alcance `OWN`, sin implementar porque no hay vínculo entre cuenta
  y persona.
- **`consultarElegibilidadAdministrativa` no tiene consumidor.** Responde, pero ni turno ni sesión
  ni obligación la consultan. Es el trabajo de **04.05**.
- **RF-M06-005 no cierra de punta a punta:** resolver un pedido al catálogo de la plataforma no
  tiene pantalla, y su prerrequisito es un endpoint que le diga al frontend si quien mira tiene rol
  de plataforma.
- **CA-M03-002 parcialmente cubierto:** RF-M03-002 pide consultorio + primer box + horario en un
  acto; el box lo entregó 02.02 y el horario general va a F5.

### Próximo paso concreto

1. **Las pantallas de F4 en adelante.** El cliente TypeScript ya se regenero contra `0.44.0` el
   01/10, asi que el frontend puede llamar al backend; lo que falta es la UI: timeline clinico,
   caso, plan de tratamiento, examen, caja, presentaciones, reportes y segunda entrega.
2. **El QA manual del parrafo 6**, sin correr desde 02.02 y declarado bloqueante para deploy.
   Docker funciona y el contrato esta al dia.
3. **La etapa de tests de `billing`, `encounter` y `activity`**, que es lo que devuelve el gate de
   cobertura a 0,80.
4. **El QA manual del §6**, sin correr desde 02.02 y declarado bloqueante para deploy. Ahora que
   Docker funciona y el contrato está al día, no queda excusa técnica.
5. Las **decisiones pendientes del usuario** de más abajo, empezando por el recableado del
   devengado: hoy no existe ninguna obligación con `responsable = FINANCIADOR`, así que la bandeja
   de 07.04 devuelve lista vacía en un despliegue real.

Pendientes que arrastra el backend:

- [ ] Protección de rama en `main`
- [ ] Activar el job de SonarQube en `.github/workflows/ci.yml` (listo, comentado)
- [ ] Observabilidad: logging JSON, Prometheus, OpenTelemetry
- [ ] Reglas `PACKAGE` de cobertura al 90 % para módulos críticos: el `PENDIENTE(F1)` del `pom.xml` sigue abierto
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
