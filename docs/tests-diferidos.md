# Tests diferidos — deuda de verificación con etapa destino

Registro explícito de escenarios **decididos y no ejecutados**. Existe para que nada se dé
por cubierto sin haber corrido: un escenario que no está en esta lista y no tiene test, es
un olvido; uno que está acá, es una decisión con fecha.

Se revisa al cerrar cada etapa. Una fila solo se borra de la tabla de pendientes cuando el
test existe y pasa.

---

## Ejecución de los diferidos de 01.01 — AKINE-01.02

Los once escenarios recibieron test en `src/test/java/com/akine/diferidos/`. Los datos de abajo
son la foto de `target/failsafe-reports` al **2026-08-23 15:30**, no una corrida hecha al escribir
este documento.

### Cerrados: test escrito y en verde

| # | Escenario | Test | Evidencia |
|---|---|---|---|
| 1 | Cross-tenant en todos los endpoints | `AislamientoDeTenantIT` — "Con contexto de A, los tres endpoints de B responden 404 y ninguno 403" | 3 tests, 0 fallos |
| 2 | Membership revocada entre requests | `AislamientoDeTenantIT` — "ventana de revocacion cero" | ídem |
| 3 | Suscripción SUSPENDIDA | `AislamientoDeTenantIT` — "la mutacion es 409 subscription-suspended y el historico sigue en 200" | ídem |
| 4 | Transición de suscripción concurrente | `TransicionConcurrenteDeSuscripcionIT` | 1 test, 0 fallos |
| 5 | Onboarding con fallo parcial | `RollbackDeOnboardingIT` | 1 test, 0 fallos |
| 6 | Onboarding con reintento concurrente | `IdempotenciaYUniquesIT` — casos 6 y 6-bis | 6 tests, 0 fallos, 1 saltado |
| 9 | Uniques con alcance tenant | `IdempotenciaYUniquesIT` — caso 9 | ídem |

### Abiertos

| # | Escenario | Estado | Motivo y etapa destino |
|---|---|---|---|
| 7 | `Idempotency-Key` repetida por HTTP | **parcial** — 7a (misma clave, mismo payload → replay) y 7c (el conflicto funciona en el alta compuesta de `organization`) pasan; **7b está `@Disabled`** en `IdempotenciaYUniquesIT:132` | La mitad "payload distinto" **no tiene camino HTTP**. `AccountRegistrationController:147` pasa `requestHash = null` por decisión documentada de 01.02, y la tabla `onboarding_registro` (migración V8) no tiene columna donde guardarlo. Como `identity` corta primero en el replay (`OnboardingService:137`), la comparación de hash que sí existe en `organization` nunca se ejecuta: la segunda alta responde `202` en vez de `409 idempotency-key-conflict`, o sea que le acusa recibo a un pedido que ignoró. El otro camino candidato, `POST /api/v1/organizations`, documenta explícitamente que su `Idempotency-Key` no registra idempotencia. Arreglarlo es una migración con `request_hash` más un cambio de API → **AKINE-01.03** |
| 8 | Límite de plan bajo concurrencia | **rojo** — `LimiteDePlanConcurrenteIT` falla | Las dos altas simultáneas del recurso número límite entran las dos: `[exactamente un alta entra. Desenlaces: [OK 900000004, OK 900000005]] expected: 1L but was: 2L` (`LimiteDePlanConcurrenteIT:99`). Es un bug real de concurrencia, no del test: el bloqueo pesimista no está conteniendo la carrera. **Se corrige dentro de 01.02**, antes de cerrar la etapa |
| 10 | E2E de cambio de contexto sin fuga | **sin test** | Crear organización, seleccionar contexto, ver solo sus datos; cambiar de organización y comprobar que no queda ningún dato residual. Requiere el frontend de 01.02 commiteado y un E2E de auth que hoy no existe (`e2e/smoke.spec.ts` no toca ningún flujo de sesión). Destino: **AKINE-01.03** |
| 11 | E2E de errores sin internals | **sin test** | Ninguna respuesta de error contiene `com.akine`, `org.springframework` ni `stacktrace` (ADR-0005). Mismo bloqueo que el 10. Destino: **AKINE-01.03** |

**Nota sobre el #8:** de los once es el más importante, y es el único que encontró el bug que
buscaba. La carrera existía en el diseño original y se detectó en el design challenge; el test es
lo único que evita que alguien "simplifique" la firma del `PlanGate` y la reintroduzca sin que
nadie se entere. Mientras esté en rojo, **01.02 no puede declarar cerrado el escenario**.

---

## Cómo se cierra esta deuda

Al cerrar AKINE-01.02, el registro de cierre debe dar cuenta de las cuatro filas abiertas: la 8
en verde, y la 7b, la 10 y la 11 explícitamente reasignadas a 01.03 con su motivo.

Mientras existan filas en la tabla de abiertos, el registro de cierre de la etapa correspondiente
**no puede afirmar** que los criterios de aceptación asociados están cubiertos: están decididos y
pendientes de verificación, que no es lo mismo.

Los orígenes de cada escenario (RF/RN/CA y número de test del diseño de 01.01) están en el
`@DisplayName` de cada test y en el historial de este archivo: `git log -p docs/tests-diferidos.md`.

---

## AKINE-02.01 — cobertura declarada como PARCIAL

### CA-M03-002 — **parcialmente cubierto**, con etapa destino

RF-M03-002 pide, literalmente: *"Crear consultorio, primer box, horario general e intervalo
inicial"*. Lo que 02.01 entrega de esos cuatro:

| Pieza del RF | Estado en 02.01 | Etapa destino |
|---|---|---|
| Crear consultorio | **cubierto** — `POST /api/v1/organizations/{orgId}/consultorios` | — |
| Intervalo inicial | **cubierto** — `slot_minutes`, columna de `consultorio` | — |
| **Primer box** | **NO cubierto** | **AKINE-02.02** |
| **Horario general** | **NO cubierto** | **F5 / agenda** |

**Por qué el box no entra, y por qué no es negociable.** `Box`/`Espacio` es del módulo
`resource` (M04) y el plan lo asigna a AKINE-02.02. Que `organization` cree una fila en una
tabla de `resource` viola la regla 1 de `AGENT.md` §4 —cada tabla tiene un módulo propietario— y
`ModuleArchitectureTest` lo rechaza. No hay forma de "cubrirlo igual": la única alternativa era
adelantar el módulo entero.

**Por qué el horario general tampoco.** RN-M03-004 dice que el horario general **no sustituye**
la disponibilidad profesional individual. Modelarlo como tabla hija en F1 —antes de que exista
esa disponibilidad (M05/M12) y antes de los slots que la consumen (F5)— es la forma más rápida
de que la agenda futura lo tome como fuente de verdad, que es exactamente lo que esa regla
prohíbe. Es una decisión del implementador, revisable, y está anotada como tal en
`V16__m03_consultorio_expandir.sql` y en `Consultorio.slotMinutes`.

**Consecuencia:** ni el registro de cierre de 02.01 ni ningún reporte pueden afirmar que
CA-M03-002 está cubierto. Está cubierto **en dos de sus cuatro piezas**, y las otras dos tienen
etapa destino escrita acá.

### Escenarios de 02.01 decididos y no ejecutados

| # | Escenario | Motivo y etapa destino |
|---|---|---|
| 12 | **Baja bloqueada por turnos futuros** | El puerto `organization.spi.ConsultorioDeactivationProbe` está declarado y el código `409 consultorio-has-active-references` está publicado en el contrato, pero **la lista de implementaciones es vacía**: `scheduling` no existe. No hay nada que probar hasta que exista. Destino: **F5 (M12)**, junto con la decisión abierta sobre qué pasa con los turnos ya reservados |
| 13 | **Alta de sede concurrente contra una mutación de membership del mismo tenant** | Es el test que vigila el orden de bloqueo único `subscription → organization`. No está escrito: exige montar dos sesiones con roles distintos en el mismo tenant, y el alta de membership por API entró en 01.03 como alta directa reservada a `identity`. Mientras tanto, el orden lo sostienen dos comentarios que se citan mutuamente —`ConsultorioService.bloquearTenant` y `MembershipService.bloquearTenant`— y **ninguna herramienta los compara**. Destino: **AKINE-02.02** |
| 14 | **Un `PROFESIONAL` acotado a una sede no ve la sede nueva y recibe 403 al crear por API** | La membership acotada a una sede que hace falta para montarlo **no la crea ningún endpoint** que un test pueda usar sin sembrar por SQL, y un E2E que siembra por SQL deja de ser de punta a punta justo en el paso que importa. Destino: **AKINE-02.02** |
| 15 | **E2E del recorrido completo de sedes** (registro → login → contexto → crear sede → selector → cambiar contexto → baja con motivo → detalle legible → auditoría) | Es de `appKine-web` y de esta etapa solo depende el backend. Destino: el cierre de 02.01 del lado del frontend |

**Y una nota que no es un test diferido sino un hallazgo de producto.** El plan por defecto del
alta self-service es `BASICO`, y `BASICO` tiene `MAX_CONSULTORIOS = 1` (seed de `V4`). O sea que
**un tenant recién registrado no puede crear ni una sola sede adicional** hasta cambiar de plan,
y el cambio de plan está reservado a `PLATFORM_ADMIN`. El límite funciona como corresponde —lo
prueba `ConsultoriosIT`, que tiene que contratar `PROFESIONAL` por el endpoint real para poder
crear la segunda sede— pero es lo primero que va a encontrar cualquiera que pruebe la pantalla
nueva con una cuenta nueva. No se cambió nada: el catálogo de planes es una decisión de negocio.

---

## AKINE-02.04 — la costura hacia `scheduling`

### Escenarios de 02.04 decididos y no ejecutados

| # | Escenario | Motivo y etapa destino |
|---|---|---|
| 16 | **RN-M05-004 — los turnos futuros afectados quedan visibles para resolución.** Origen: AKINE-02.04. Destino: **F5** (`scheduling`, M12). No se puede ejecutar: no existe ningún turno que pueda estar afectado. La costura está declarada (`resource.spi.DisponibilidadImpactProbe`) y hoy devuelve cero por construcción. Al implementarla, este escenario se ejecuta sin cambiar el contrato |

**Lo que sí corre en esta etapa.** `ColaboradorDesvinculacionProbe` (definido por `organization`,
implementado acá por `ResourceDesvinculacionProbe`) no está en esta lista porque no depende de
`scheduling`: los bloques y las excepciones de disponibilidad ya existen (`V22`/`V23`), y contar
cuántos quedan colgando de una membership es verificable hoy. `ResourceDesvinculacionProbeTest`
lo cubre.

### Escenarios de 02.04 decididos y no ejecutados — verificación de interfaz

Estos no dependen de que exista otro módulo: dependen de correrlos. Se anotan acá porque una
etapa cerrada sin ellos no está verificada en uso, y decirlo por escrito es lo único que impide
que se lea como si lo estuviera.

| # | Escenario | Motivo y etapa destino |
|---|---|---|
| 17 | **E2E del recorrido de horarios**: cargar disponibilidad, meter un cierre que la recorta, ver el preview explicando la regla, intentar un bloque solapado y recibir el `409`. Origen: AKINE-02.04, paso 1 de la tarea 18 | **No corrido.** Exige el stack real levantado —`docker compose up -d`, backend en `local`, frontend en `4200`— y un navegador. Destino: sesión de verificación de interfaz, junto con los E2E pendientes de 02.02, 02.03 y 02.05 |
| 18 | **QA manual contra la base** de las cuatro pantallas de `/horarios`: validar la persistencia consultando la DB después de cada write, y verificar que un token del tenant B no ve la disponibilidad del tenant A. Origen: AKINE-02.04, paso 2 de la tarea 18 | **No corrido.** `CLAUDE.md` §6 lo declara **bloqueante para deploy**, así que esta etapa no se puede desplegar hasta que corra. Mismo estado que 02.02, 02.03 y 02.05 |
| 19 | **Contraste de color de todas las pantallas del repositorio** | **No verificable con las herramientas actuales.** La regla `color-contrast` de axe **nunca reporta bajo jsdom**: siempre vuelve `incomplete`, porque jsdom no calcula layout. Ninguna auditoría de accesibilidad del repositorio lo cubre, no solo las de esta etapa. Se suma que los estados nuevos de modo lectura no pasaron por axe y que no hay regla CSS para `input:disabled` en `resource.css`. Destino: sin herramienta asignada — exige un runner con layout real |

**Y una deuda de esta etapa que no es un test sino una decisión que espera al usuario.** La
cobertura de **rama** del backend está en **78,03 %** (2135 de 2736) y el `pom.xml` **no gatea
`BRANCH`**: gatea `LINE` e `INSTRUCTION` sobre el BUNDLE. Por eso `verify` pasa legítimamente
mientras la rama cayó nueve puntos desde el 87,42 % que declaraba el cierre de 01.02. Agregar el
gate hoy rompe el build, así que exige primero un tramo de cobertura. Está anotado en el registro
de cierre de 02.04 y en los dos `CLAUDE.md`.

### Escenario de 02.07 — el incremento forzado de la versión de la oferta

Este no se difiere por depender de otro módulo ni por pereza: **se difiere porque los mocks no
pueden observarlo.** Que Hibernate suba la `version` al cerrar la transacción es comportamiento
del proveedor de persistencia contra una base real, y un test con Mockito solo puede fijar la
costura que lo habilita.

| # | Escenario | Motivo y etapa destino |
|---|---|---|
| 20 | **Dos administradores configurando la misma oferta: el segundo en guardar recibe 409.** A y B leen la oferta en la misma versión; A reemplaza los profesionales; B guarda con la versión que leyó y **debe** chocar. Origen: AKINE-02.07, defecto encontrado el 30/08/2026 | **No corrido.** Exige MySQL real por Testcontainers y en esta máquina el motor de Docker no arranca sin elevación. Lo que sí está fijado en unitarias es que el camino de escritura carga la oferta por `findWithLockByIdAndOrganizationIdAndConsultorioId` —el método anotado con `OPTIMISTIC_FORCE_INCREMENT`— y que la lectura **no** lo usa. Destino: primera sesión con Docker disponible |

**Por qué importa que este escenario existiera sin cubrir.** El test que había,
`el_reemplazo_respeta_el_control_optimista`, pasa una versión desactualizada a mano y verifica que
lance. Eso prueba que la **comparación** funciona; no prueba el escenario que su propio comentario
describe, porque nunca simula dos guardados seguidos. Y en el escenario real la versión del segundo
administrador **no estaba desactualizada**: un reemplazo de habilitaciones no toca ninguna columna
de `oferta`, así que JPA no movía su `@Version` y el control no serializaba nada. Es la misma
familia que las trampas de concurrencia ya documentadas: el test miraba el código de respuesta, no
el escenario.

---

## AKINE-04.02 — cuatro clases de integración **escritas y nunca ejecutadas**

Esta sección es distinta de todas las anteriores. Los escenarios de abajo **tienen test escrito y
commiteado**; lo que falta no es decidirlos ni redactarlos, es **correrlos**. En esta máquina el
servicio `com.docker.service` está detenido y arrancarlo pide una elevación que la sesión no
tiene, así que Testcontainers no levanta MySQL y ningún `*IT` se ejecuta.

**Ninguna de estas cuatro clases se ejecutó jamás.** Cada una lo declara en su javadoc con la
frase *"escrito el 19/09/2026 y NUNCA EJECUTADO: Docker no estaba disponible"*. Lo único
verificado es que **compilan** (`./mvnw -o -q test-compile`, en verde) y que las **2.165
unitarias siguen en verde** (`./mvnw -o test -DskipITs`, 0 fallos). Un test que compila y no
corre no cubre nada: puede fallar por un fixture mal sembrado, por un CHECK que no está donde se
cree, o por el defecto real que fue a buscar.

**Destino de las cinco filas: la primera sesión con Docker disponible.** Es la misma que tiene
que correr `./mvnw verify -Dakine.contract.update=true` para regenerar el contrato `0.30.0`, que
hoy está en drift.

| # | Escenario | Test escrito | Motivo y etapa destino |
|---|---|---|---|
| 21 | **Dos enmiendas concurrentes sobre la misma entrada clínica.** Las dos leen la misma versión de la cabecera, las dos pasan el control explícito de `expectedVersion`, y solo una puede commitear: que el `@Version` de la cabecera **efectivamente avance** cuando la escritura solo agrega una fila hija lo decide Hibernate contra una base real. Incluye la ráfaga secuencial (numeración 1..7 sin huecos) y el **control negativo** —dos enmiendas sobre entradas distintas entran las dos— que es justamente lo que al escenario 20 de 02.07 le faltaba. Origen: AKINE-04.02, challenge §8 punto 3 | `clinical/EntradaClinicaConcurrenteIT` — 5 tests | **No corrido.** Exige MySQL real por Testcontainers. Destino: **primera sesión con Docker disponible** |
| 22 | **Idempotencia de la subida de adjunto clínico.** Subir dos veces el mismo contenido a la misma historia devuelve el adjunto existente y deja **una** fila; dos subidas simultáneas —donde el pre-chequeo por checksum de las dos da vacío— también. Más: la baja lógica no borra el binario, un adjunto dado de baja se puede volver a subir (el centinela de `deleted_key`), y un binario que el almacenamiento perdió da **409 y no 404** con la fila marcada `NO_DISPONIBLE`. Origen: AKINE-04.02, diseño §4 | `clinical/AdjuntoClinicoIT` — 12 tests | **No corrido.** El invariante lo garantiza el unique de `V46`, no el servicio: con dobles el repositorio devuelve lo que se le dijo. Destino: **primera sesión con Docker disponible** |
| 23 | **Las cinco consultas nativas del timeline.** Son `SELECT *` con `LIMIT :limite`: ni el mapeo de columnas, ni el binding de `Instant` a `DATETIME(6)`, ni el `LIMIT` parametrizado los valida Hibernate al arrancar, así que **hoy un error ahí no lo agarra nada**. Se verifica que cada contribuyente aporte lo suyo, que el orden total `(ocurrioEn DESC, origen ASC, referencia DESC)` se respete al mezclar, que la paginación por cursor no repita ni saltee entre páginas, que una entrada o un adjunto dados de baja **salgan** del timeline, y que solo se indexen **sesiones cerradas**. Origen: AKINE-04.02, diseño §2 | `clinical/TimelineIT` — 9 tests | **No corrido.** Una fuente que devuelve vacío por un error de mapeo nativo es invisible: el endpoint responde 200 con menos hechos. Destino: **primera sesión con Docker disponible** |
| 24 | **Los CHECK y los uniques de `V45` y `V46` contra el motor.** El par `origen`↔`referencia_origen`, el motivo obligatorio desde la versión 2, la coherencia del cuarteto de baja lógica en las dos tablas, las listas cerradas de tipo y categoría —ninguna categoría administrativa entra—, `uk_entrada_version_numero`, `uk_adjunto_clinico_contenido_vigente` y que las columnas generadas `deleted_key` existan, sean `STORED` y valgan el centinela. Origen: AKINE-04.02, `V45`/`V46` | `clinical/infrastructure/EntradaYAdjuntoClinicoMigrationIT` — 21 tests | **No corrido.** Es la clase de test que en 03.06 destapó un MySQL 3819, que solo aparece al ejecutar. Destino: **primera sesión con Docker disponible** |
| 25 | **Aislamiento de tenant de los tres servicios nuevos.** Un actor del tenant B no ve ni toca entrada, adjunto ni timeline del tenant A, y el resultado es **404, nunca 403** — un 403 confirmaría que esa fila existe. Está repartido en las tres clases de servicio, una prueba por clase. Origen: `AGENT.md` §6, que lo exige en **cada** test de integración | `EntradaClinicaConcurrenteIT`, `AdjuntoClinicoIT`, `TimelineIT` | **No corrido.** Mismo bloqueo. Destino: **primera sesión con Docker disponible** |
| 26 | **Cuantas veces avanza la `version` de la cabecera al enmendar.** La cabecera se lee con `OPTIMISTIC_FORCE_INCREMENT` **y** queda sucia (cambia `ultimo_numero_version`), asi que el flush emite un UPDATE versionado y encima Hibernate registra un `EntityIncrementVersionProcess` que corre antes del commit. Si los dos se aplican, la base queda en `leida + 2` mientras la vista que `enmendar` devuelve trae `leida + 1`, y la enmienda siguiente del mismo cliente vuelve a comer un 409 espurio. Es lo unico del defecto 1 que un unitario **no** puede decidir: depende de Hibernate contra una base real. Si se confirma, el arreglo es **sacar el force-increment de esa lectura** âel contador que se serializa vive en la fila del padre, asi que el control optimista comun ya alcanzaâ y no perseguir la version desde el servicio. Origen: correccion de los seis defectos de AKINE-04.02 | Hay que escribirlo: una enmienda, y `SELECT version FROM entrada_clinica` despues del commit | **No corrido.** Exige MySQL real. Destino: **primera sesion con Docker disponible** |

### Lo que estas clases deliberadamente NO cubren

| Escenario | Por qué no se escribió |
|---|---|
| **El borde del cursor con más de `limite` eventos de UNA fuente en el instante exacto del cursor** | El diseño §2.1 lo declara **abierto y no resuelto**, con su precio escrito. Un test que lo ejerciera fallaría por diseño y no por defecto, y un `@Disabled` con esa explicación no agrega nada sobre el documento que ya la tiene |
| **La auditoría de cada operación** (`TIMELINE_ACCESSED`, `ADJUNTO_CLINICO_DOWNLOADED` y los seis restantes) | `audit_event` es append-only por los triggers de `V14` y ya tiene su propia cobertura unitaria por servicio. Verificar el contenido de cada evento desde un IT duplicaría esa prueba sin agregar nada que dependa del motor |
| **La capa REST de las doce operaciones** | Un IT de `api` exigiría el contrato regenerado, que está en drift hasta que alguien corra `verify -Dakine.contract.update=true`. Escribirlo contra el contrato viejo sería escribir contra una forma que va a cambiar |
| **El contenido real en disco del `LocalFileSystemContenidoClinicoStorage`** | Ya tiene `LocalFileSystemContenidoClinicoStorageTest`, que es unitario y no necesita base. El IT del binario perdido apunta la fila a una clave inexistente en vez de borrar el archivo, para no depender de dónde montó su raíz la máquina que corre |
