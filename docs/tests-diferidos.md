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
| 26 | **Cuantas veces avanza la `version` de la cabecera al enmendar.** El escenario nacio de una sospecha: la cabecera se leia con `OPTIMISTIC_FORCE_INCREMENT` **y** quedaba sucia (cambia `ultimo_numero_version`), asi que el flush emitia un UPDATE versionado y encima Hibernate registraba un `EntityIncrementVersionProcess` antes del commit. Con los dos aplicados la base queda en `leida + 2` mientras la vista devuelve `leida + 1`, y la enmienda siguiente del mismo cliente come un 409 espurio. **Lo que se hizo (19/09/2026):** se saco el force-increment de esa lectura y se borro el metodo `findWithLockByIdAndOrganizationId` del puerto y del repositorio de `EntradaClinica`; `enmendar` lee por `findByIdAndOrganizationId`. No debilita nada porque el contador vive en la fila del padre: la cabecera queda sucia igual y el `UPDATE ... WHERE version = N` serializa dos enmiendas concurrentes. La leccion de 02.07 **no** aplica —alla la escritura solo tocaba tablas hijas—. Cubierto en unitarias por `EntradaClinicaServiceTest#enmendar_ensucia_la_cabecera`, que falla si alguien saca el contador de la cabecera. **Lo que queda por confirmar contra MySQL real:** que la version quede exactamente en `leida + 1` despues del commit, y que dos enmiendas concurrentes den una ganadora y un 409 —no dos filas con el mismo `numero_version`—. Origen: correccion de los seis defectos de AKINE-04.02 | Una enmienda y `SELECT version FROM entrada_clinica` despues del commit; mas el caso concurrente en `EntradaClinicaConcurrenteIT` | **Arreglado en codigo, no verificado contra la base.** Exige MySQL real. Destino: **primera sesion con Docker disponible** |
| 27 | **Que las dos aperturas/subidas concurrentes devuelvan la fila ganadora y no un 500.** El INSERT de `historia_clinica` (`HistoriaClinicaService.abrirIdempotente`) y el de `adjunto_administrativo` (`person.AdjuntoService.subir`) salieron a `HistoriaClinicaEscrituraAparte` y `AdjuntoEscrituraAparte`, con `REQUIRES_NEW`: dentro de la transaccion de negocio, el choque contra el unique la marcaba `rollbackOnly` y el `catch` corria sobre una sesion inutilizable —`UnexpectedRollbackException` o `AssertionFailure`, o sea 500 donde el contrato promete idempotencia—. Es el defecto que `organization.application.OnboardingService` ya documentaba y que `AdjuntoClinicoService` ya tenia arreglado. Origen: residuo 2 de la correccion de AKINE-04.02 | Dos hilos abriendo la misma historia y dos subiendo el mismo contenido; se espera una creacion y una respuesta idempotente, cero 500 y una sola fila | **Arreglado en codigo, no verificado contra la base.** Un unitario no puede reproducirlo: la marca `rollbackOnly` la pone Hibernate contra un motor real. Destino: **primera sesion con Docker disponible** |

### Lo que estas clases deliberadamente NO cubren

| Escenario | Por qué no se escribió |
|---|---|
| **El borde del cursor con más de `limite` eventos de UNA fuente en el instante exacto del cursor** | El diseño §2.1 lo declara **abierto y no resuelto**, con su precio escrito. Un test que lo ejerciera fallaría por diseño y no por defecto, y un `@Disabled` con esa explicación no agrega nada sobre el documento que ya la tiene |
| **La auditoría de cada operación** (`TIMELINE_ACCESSED`, `ADJUNTO_CLINICO_DOWNLOADED` y los seis restantes) | `audit_event` es append-only por los triggers de `V14` y ya tiene su propia cobertura unitaria por servicio. Verificar el contenido de cada evento desde un IT duplicaría esa prueba sin agregar nada que dependa del motor |
| **La capa REST de las doce operaciones** | Un IT de `api` exigiría el contrato regenerado, que está en drift hasta que alguien corra `verify -Dakine.contract.update=true`. Escribirlo contra el contrato viejo sería escribir contra una forma que va a cambiar |
| **El contenido real en disco del `LocalFileSystemContenidoClinicoStorage`** | Ya tiene `LocalFileSystemContenidoClinicoStorageTest`, que es unitario y no necesita base. El IT del binario perdido apunta la fila a una clave inexistente en vez de borrar el archivo, para no depender de dónde montó su raíz la máquina que corre |

---

## AKINE-04.03 — cuatro clases de integración más, **escritas y nunca ejecutadas**

Misma situación que la sección anterior y por la misma causa: el servicio `com.docker.service` sigue
detenido y arrancarlo pide una elevación que la sesión no tiene, así que Testcontainers no levanta
MySQL y ningún `*IT` se ejecuta. **Ninguna de estas cuatro clases se ejecutó jamás**, y cada una lo
declara en su javadoc con la frase *"escrito el 19/09/2026 y NUNCA EJECUTADO: Docker no estaba
disponible"*.

Lo único verificado es que **compilan** (`./mvnw -o -q test-compile`, en verde) y que las **2.196
unitarias siguen en verde** (`./mvnw -o test -DskipITs`: `Tests run: 2196, Failures: 0, Errors: 0,
Skipped: 0`). Un test que compila y no corre no cubre nada: puede fallar por un fixture mal
sembrado, por un CHECK que no está donde se cree, o por el defecto real que fue a buscar.

**Agravante propio de esta etapa: `V47` y `V48` no se aplicaron nunca contra un motor.** No hay
evidencia de que siquiera *ejecuten*, mucho menos de que sus CHECK hagan lo que sus comentarios
dicen. El escenario 30 es lo primero que hay que correr.

**Destino de las cinco filas: la primera sesión con Docker disponible.** Es la misma que tiene que
correr `./mvnw verify -Dakine.contract.update=true` para regenerar el contrato —que ahora arrastra
el drift de `0.30.0` **y** el de `0.31.0`— desde la rama que tenga las dos tandas.

| # | Escenario | Test escrito | Motivo y etapa destino |
|---|---|---|---|
| 28 | **El cierre de sesión toma DOS numeradores en la misma transacción.** Es la primera transacción de este sistema que lo hace: el de la Historia Clínica (`sesion_numerador`, `V35`) y el del Caso (`caso_sesion_numerador`, `V47`), pedido por `clinical.spi.CasoDirectory`. El orden tiene que ser **siempre historia primero**, o dos cierres concurrentes de sesiones de casos cruzados se bloquean mutuamente. Cubre: dos cierres del mismo caso, el **cruzado** —sesión del caso 1 y sesión del caso 2 a la vez—, la ráfaga de cinco sobre un caso **sin fila de numerador** (el deadlock del lazy-create, ya pagado cuatro veces), la ráfaga secuencial mezclada que prueba que **las dos numeraciones avanzan independientes**, la sesión **sin caso** que cierra con `numero_en_caso` en NULL, y la idempotencia que no consume correlativo en ninguna de las dos dimensiones. Origen: AKINE-04.03, challenge §8.4 —"la más fácil de olvidar y la más cara" | `encounter/CierreConDosNumeradoresIT` — 8 tests | **No corrido.** Hoy el orden fijo está **razonado y no probado**. Con datos válidos el ciclo de espera no se puede construir —un caso pertenece a una sola historia, así que dos sesiones que comparten caso ya se serializan en el primer numerador—, y **eso es la consecuencia del orden fijo, no una debilidad del test**: deja de ser cierto si alguien invierte los pasos 5 y 5b o mete un tercer numerador en el medio. Destino: **primera sesión con Docker disponible** |
| 29 | **La numeración del Caso y el duplicado razonable bajo concurrencia.** "Dos administrativos abren un caso para el mismo paciente y la misma oferta, al mismo tiempo, desde dos sedes" (challenge §8). Cubre: dos altas concurrentes que **entran las dos** con `numero_caso` 1 y 2 —RN-M10-002 admite varios casos activos y un unique acá sería un bug disfrazado de protección—, la ráfaga de cinco sobre una historia **sin fila de numerador**, la ráfaga secuencial 1..6 sin huecos, el 409 de duplicado **con la lista de candidatos** y sin consumir correlativo, el reenvío confirmado que entra, y el **control negativo**: dos altas sobre historias distintas entran las dos sin esperarse. Origen: AKINE-04.03, challenge §8 puntos 1 y 2 | `clinical/CasoClinicoConcurrenteIT` — 8 tests | **No corrido.** El control negativo es lo que a 02.07 le faltó: un lock demasiado grueso —por organización en vez de por historia— pasaría inadvertido con el resto en verde, porque todos los demás escenarios usan una sola historia. Destino: **primera sesión con Docker disponible** |
| 30 | **`V47` y `V48` contra el motor.** Las cinco tablas con `organization_id NOT NULL` y todo índice declarado empezando por él; `ck_caso_clinico_estado` y `ck_caso_clinico_cierre_coherente` en sus dos direcciones —un CERRADO incompleto y un ACTIVO que arrastra datos de cierre—; `uk_caso_numero` y su control negativo por historia y por tenant; que **no haya** baja lógica de caso ni contador de sesiones cacheado; `ck_caso_profesional_rol` y `ck_caso_profesional_vigencia`; la columna generada **`hasta_key`**, que exista, sea `STORED` y valga el centinela; que `caso_evento` **no** tenga `version`, `updated_at` ni baja; los tres CHECK del historial —tipo, motivo obligatorio sólo en CIERRE y REAPERTURA, y la APERTURA como único evento sin estado anterior—; la unicidad de los dos numeradores; y de `V48` el `ck_sesion_numero_en_caso` —**en particular que no pueda haber `numero_en_caso` sin `caso_id`**—, `uk_sesion_numero_en_caso` y que las sesiones sin caso no se estorben entre sí. Origen: AKINE-04.03, `V47`/`V48` | `clinical/infrastructure/CasoClinicoMigrationIT` — 33 tests | **No corrido, y ninguna de las dos migraciones se aplicó jamás.** MySQL ignoró en silencio toda la sintaxis `CHECK` hasta 8.0.16, y en 8.4 una expresión mal escrita sobre una columna generada falla con un **3819** que sólo aparece al ejecutar — que es lo que esta clase de test destapó en 03.06. Destino: **primera sesión con Docker disponible** |
| 31 | **El ciclo de vida del Caso contra base real.** Abrir → editar con `expectedVersion` → cerrar con motivo → reabrir → cerrar de nuevo, con `caso_evento` acumulando los cinco eventos en orden y **cada cierre conservando su propio motivo**; que reabrir **limpie** las tres columnas del cierre; que un caso cerrado no admita editar contenido ni cambiar equipo (**409, no 403**) ni **sesiones nuevas** —verificado por el camino real, `SesionService#iniciar` a través del `spi`—; que **reabrir NO reinicie `caso_sesion_numerador`** (la sesión siguiente es la 4, no la 1); que el profesional desvinculado **siga figurando** con `hasta` puesto; que el cambio de equipo **haga avanzar la `version` del caso** aunque no toque ninguna de sus columnas; y el filtro por caso del timeline, incluido que un caso de otra historia responda **404 y no página vacía**. Origen: AKINE-04.03, RF-M10-001..006 y la quinta condición del challenge | `clinical/CasoClinicoCicloIT` — 12 tests | **No corrido.** Dos afirmaciones sólo se pueden hacer acá: que el numerador del caso no vuelva atrás —vive en una fila que sólo existe en la base— y que el `OPTIMISTIC_FORCE_INCREMENT` de `cambiarEquipo` **efectivamente** suba la versión cuando la escritura sólo toca tablas hijas, que es comportamiento de Hibernate contra un motor real y es exactamente la lección de 02.07. Destino: **primera sesión con Docker disponible** |
| 32 | **Aislamiento de tenant de lo que 04.03 agrega.** Un actor del tenant B no abre, ve, edita, cierra ni cambia el equipo de un caso del tenant A; no lista ni abre casos sobre una historia de A; no cierra una sesión de A; y no puede **filtrar su propio timeline por un caso de A**. El resultado es **404, nunca 403** — un 403 confirmaría que ese caso existe y dejaría censar casos ajenos por id. Está repartido en las tres clases de servicio, una prueba por clase. Origen: `AGENT.md` §6, que lo exige en **cada** test de integración | `CasoClinicoConcurrenteIT`, `CasoClinicoCicloIT`, `CierreConDosNumeradoresIT` | **No corrido.** Mismo bloqueo. Destino: **primera sesión con Docker disponible** |

### Lo que estas clases deliberadamente NO cubren

| Escenario | Por qué no se escribió |
|---|---|
| **El ciclo de espera real entre los dos numeradores** —dos transacciones tomándolos en orden inverso— | **No se puede construir con datos válidos.** Un caso pertenece a exactamente una historia, así que dos sesiones que comparten caso comparten historia y quedan serializadas en el primer numerador, y dos de historias distintas no comparten ninguno. Forzarlo exigiría sembrar una sesión de la historia B con un caso de la historia A —dato que `SesionService#iniciar` rechaza— y el test estaría probando el comportamiento del motor ante datos que el sistema no puede producir. Lo que sí se prueba es lo observable: que las combinaciones con solapamiento máximo **terminen las dos** |
| **La capa REST de las ocho operaciones nuevas** | Mismo motivo que en 04.02: un IT de `api` exigiría el contrato regenerado, y `0.31.0` está en drift junto con `0.30.0`. Escribirlo contra el contrato viejo sería escribir contra una forma que va a cambiar |
| **La auditoría de cada operación del caso** (`CASO_CLINICO_OPENED`, `CASO_CLINICO_ACCESSED` y las cinco restantes) | `audit_event` es append-only por los triggers de `V14` y cada servicio ya tiene su cobertura unitaria. Verificar el contenido de cada evento desde un IT duplicaría esa prueba sin agregar nada que dependa del motor |
| **Que `abrir` corra efectivamente en `READ_COMMITTED`** | El nivel de aislamiento no es observable desde el resultado de la operación: lo que se puede observar es el efecto —que dos altas concurrentes no numeren igual— y eso ya lo cubre el escenario 29. Un test que leyera `@@transaction_isolation` probaría la anotación, no la regla |

### Una observación de producción que estas clases NO pueden saldar

`SesionService#cerrar` es hoy la **única** mutación del sistema que toma un numerador y **no**
declara `Isolation.READ_COMMITTED` (`encounter/application/SesionService.java:308`). Las otras diez
que serializan sí lo hacen —`TurnoService:136`, `CicloDeTurnoService:230`, `CoberturaPacienteService`,
`AutorizacionService`, `ConvenioService`, `ArancelService`, `MembershipService`, y el propio
`CasoClinicoService#abrir:153` de esta etapa—. No está probado que sea un defecto: una transacción
lee siempre sus propias escrituras, así que el `leerUltimo` posterior al `incrementar` debería ver
el valor nuevo aun bajo `REPEATABLE READ`. Pero es una desviación no declarada de la regla que
05.02 dejó fijada, y **04.03 acaba de convertir ese método en el que toma dos numeradores**. Queda
como pregunta para la primera sesión con Docker: correr el escenario 28 y, si pasa, decidir si la
anotación se unifica igual por coherencia.

---

## AKINE-07.03 — Caja diaria (M20)

La etapa cerró con **un solo archivo de tests unitarios** —`billing/application/ReglasDeCajaTest`,
seis casos— por decisión explícita de alcance: cubrir las reglas que si se rompen rompen el
negocio y no una más. Lo que quedó afuera se anota acá en vez de simularse.

### Lo que sólo se puede probar contra MySQL real

| # | Escenario | Motivo y etapa destino |
|---|---|---|
| 33 | **El `UPDATE` condicional del saldo bajo concurrencia real.** Dos egresos simultáneos peleándose por el último peso: el primero se lo lleva y el segundo tiene que afectar **cero filas** y terminar en `caja-saldo-insuficiente`, con el `CHECK (saldo_arqueo >= 0)` de `V54` como respaldo. Hoy el caso unitario prueba lo que el servicio hace **cuando el mock devuelve cero filas**; que la base devuelva cero filas en esa carrera es precisamente lo que no está probado — y es la mitad que importa. Mismo hueco que 04.05 dejó en la última unidad de una autorización | **No escrito.** Es un IT: dos transacciones peleándose no se simulan con Mockito. Destino: **primera sesión con Docker disponible** |
| 34 | **`saldoTeoricoEsperado`: el cobro en efectivo que entra entre el conteo y el cierre.** Es el caso que rompe el diseño (challenge §8) y toda su protección vive en un `AND saldo_arqueo = :esperado` dentro del `UPDATE` que cierra. Cubre también el cierre concurrente —dos cierres simultáneos, el segundo afecta cero filas porque `estado` ya no es `ABIERTA` → `caja-cerrada`— | **No escrito.** Igual que el 33: la condición la evalúa el motor, y un mock que devuelve cero filas prueba el `if` del servicio, no la regla. Destino: **primera sesión con Docker disponible** |
| 35 | **`V54` contra el motor.** Que la migración siquiera ejecute, y que hagan lo que sus comentarios dicen: la columna generada `abierta_marca` y el `UNIQUE (organization_id, consultorio_id, abierta_marca)` —**a lo sumo una jornada abierta por sede**, con varios NULL que no colisionan—; la generada `afecta_arqueo = (medio = 'EFECTIVO')`; `CHECK (medio <> 'EFECTIVO' OR jornada_caja_id IS NOT NULL)`; el CHECK de motivo obligatorio sólo con diferencia distinta de cero **y prohibido con diferencia cero**; `CHECK (importe > 0)` y los de reversión; y el unique que impide revertir dos veces el mismo movimiento | **No escrito, y `V54` no se aplicó jamás contra un motor.** MySQL 8.4 falla con un **3819** sobre una expresión mal escrita en una columna generada sólo al ejecutar. Destino: **primera sesión con Docker disponible** |

### Lo que se decidió no cubrir, y por qué

| Escenario | Por qué no |
|---|---|
| **La capa REST y el mapeo de cada excepción a su `ProblemType`** | El contrato `0.36.0` declara la versión y **no regeneró sus `paths`**: Docker no arranca. Escribir contra la forma vieja sería escribir contra algo que va a cambiar. El mismo motivo que 04.02 y 04.03 dieron |
| **La reversión que cae en la jornada abierta HOY** y no en la del original (RN-M20-003) | Regla real y no cubierta. Se comprueba mejor de punta a punta —con una jornada cerrada y otra abierta en la base— que con mocks, y el valor unitario sería casi todo verificación de argumentos. Destino: el IT de la primera sesión con Docker |
| **Que la jornada de otra sede o de otro tenant dé 404 y nunca 403** | La regla está implementada en un único punto —`findByIdInScope` más `CajaAcceso.exigirSedeDelTenant`— y su prueba contra mocks sería la prueba de un `orElseThrow`. `AGENT.md` §6 la exige en **cada** IT, y ahí es donde se va a hacer cumplir |
| **`DECIMAL` y nunca `float`, y los `@Valid` de los DTOs** | Estructural: lo dicen los tipos declarados en la entity y las anotaciones del request. Un test que los afirme prueba que el compilador funciona |
## AKINE-07.04 — Presentaciones y cuenta corriente de financiadores (M21)

**Cinco escenarios, y ninguno se escribió.** A diferencia de 04.02 y 04.03 —que dejaron 108
escenarios escritos y nunca ejecutados— esta etapa no escribió tests de integración: escribir un IT
que no se puede correr deja un artefacto que parece verificación y no lo es, y la sesión que lo
herede tiene que revisarlo entero antes de confiar en él. Lo que sí queda es la lista de lo que hay
que escribir, con el motivo de cada uno.

Lo verificado: **2.249 unitarias en verde** (`./mvnw -o -B test -DskipITs`), ArchUnit 5/5 con la
arista nueva `billing → contracting.spi` en el grafo, y el diseño completo. Un test unitario con
mocks no prueba una columna generada, un `CHECK` ni dos transacciones peleándose por el mismo saldo.

| # | Escenario | Motivo y etapa destino |
|---|---|---|
| 36 | **`V56` contra el motor.** Que los dos `ALTER` ejecuten —en particular `DROP CHECK ck_movimiento_caja_tipo_origen` seguido del `ADD`, que es la primera vez que este repositorio reemplaza un `CHECK` desde una migración posterior—; que `ck_obligacion_financiador_presente` y su recíproca funcionen en las dos direcciones; que la columna generada **`ocupa_marca`** exista, sea `STORED` y valga NULL para `DEBITADO` y `ANULADO`; que `ck_presentacion_saldo_cuadra` rechace una fila donde los cuatro importes no cierren; que `ck_presentacion_confirmacion_completa` rechace un `BORRADOR` con número y un `PRESENTADA` sin él; y que `uk_presentacion_factura` admita varios NULL. | **No escrito.** MySQL ignoró en silencio toda la sintaxis `CHECK` hasta 8.0.16, y en 8.4 una expresión mal escrita sobre una columna generada falla con un **3819** que sólo aparece al ejecutar — es lo que esta clase de test destapó en 03.06. Destino: **primera sesión con Docker disponible** |
| 37 | **RN-M21-003 bajo concurrencia real.** Dos administrativos agregan la **misma** obligación a **dos lotes distintos** al mismo tiempo. Sólo uno entra; el otro choca contra `uk_presentacion_item_ocupa` y recibe 409 `obligacion-ya-presentada`. Y el control negativo que 02.07 dejó como lección: dos obligaciones **distintas** al mismo lote entran las dos sin esperarse. | **No escrito, y hoy la regla está probada sólo por la consulta previa** —`findVivoDeLaObligacion`—, que tiene una ventana entre leer y escribir. El mecanismo real es el unique sobre la columna generada, y eso no existe fuera del motor. Destino: **primera sesión con Docker disponible** |
| 38 | **El caso que rompe el diseño, con dos transacciones de verdad.** El aviso de débito por 15.000 y la transferencia por 100.000 sobre el mismo lote de 100.000, a la vez. Uno gana, el otro afecta **cero filas** y recibe 409 con el saldo actual; el saldo nunca queda negativo y `ck_presentacion_saldo` no se dispara. | **No escrito. Es el escenario más importante de la etapa** y el único que puede confirmar que el `UPDATE ... WHERE saldo >= :importe` sirve para lo que se lo eligió. El test unitario que existe simula cero filas con un mock: prueba la traducción a 409, **no** que el motor serialice. Destino: **primera sesión con Docker disponible** |
| 39 | **El correlativo del lote sin huecos ni repeticiones.** Ráfaga de cinco confirmaciones concurrentes del mismo financiador sobre una sede **sin fila de numerador** —el deadlock del lazy-create, ya pagado cuatro veces—; la secuencia 1..6 sin huecos; que un lote que falla la validación **no consuma** un número; y que dos financiadores distintos numeren independientes. | **No escrito.** `PresentacionNumeradorIniciador` replica un patrón probado en otras tres etapas, pero "replica un patrón" no es evidencia. Destino: **primera sesión con Docker disponible** |
| 40 | **El pago que genera caja, y el que no la toca.** Que un pago por `TRANSFERENCIA` asiente un movimiento con `jornada_caja_id` NULL, `afecta_arqueo = 0` y **sin exigir jornada abierta**; que uno en `EFECTIVO` sin jornada abierta devuelva 409 `caja-no-abierta` **y no registre el pago ni mueva el saldo del lote**; que el reintento con la misma `idempotency_key` no duplique ni el pago ni el movimiento; y el **aislamiento de tenant**: un actor del tenant B no ve, no paga y no concilia un lote de A —404, nunca 403—. | **No escrito.** La atomicidad del conjunto pago + saldo + movimiento sólo se observa cuando algo falla en el medio, y eso exige una transacción real. Destino: **primera sesión con Docker disponible** |

### Lo que esta etapa NO puede validar, y no es un test que falte

**RF-M21-003 no comprueba orden, autorización ni credencial.** Los tres requisitos documentales del
convenio viven en `contracting.spi.ArancelCongelado` —`requeriaOrden`, `requeriaAutorizacion`,
`requeriaCredencial`— y el consumidor que tenía que copiarlos a `obligacion` es
`ObligacionDevengador`, que **nunca se recableó contra convenios**. Es la misma causa raíz por la
que no existe ninguna obligación con `responsable = FINANCIADOR` y por la que la bandeja de
elegibles devuelve lista vacía.

**No es deuda de verificación: es un cimiento que falta**, y su destino no es "la primera sesión con
Docker" sino una etapa propia —recablear el devengado, con migración y cambio de contrato—. Está en
§2 y §7 del design challenge de AKINE-07.04, y es una **decisión pendiente del usuario**.
## AKINE-07.05 — Egresos y pagos a profesionales (M22)

**No se escribió ni un `*IT.java`.** Docker no arranca, y la etapa entregó **un solo archivo de
test unitario con cinco casos**, por decisión explícita del usuario: cubrir lo que la etapa exige y
avanzar. Lo que sigue no es una lista de tests escritos y no corridos —como la de 04.02 y 04.03—
sino de **escenarios que sólo se pueden probar contra MySQL real y que todavía no tienen clase**.

Es una deuda más grande que la de aquellas etapas, y conviene que se lea como lo que es: las tres
primeras filas son las que deciden si el dinero cuadra.

| # | Escenario | Por qué sólo se puede probar contra el motor | Etapa destino |
|---|---|---|---|
| 41 | **Dos pagos concurrentes en efectivo contra un cajón que no alcanza para los dos.** Es *el caso que rompe el diseño* (challenge §8): 80.000 en la caja, dos administrativos pagan 50.000 cada uno. Tiene que entrar **uno solo**, y el otro recibir 409 `caja-saldo-insuficiente`. Hoy las dos condiciones —`saldo_pendiente >= :importe` en `egreso` y `saldo_arqueo >= :importe` en `jornada_caja`— están **razonadas y no probadas bajo concurrencia** | Una condición del `WHERE` sólo se puede observar con dos transacciones peleándose. Un mock que devuelve cero filas prueba la rama del `if`, no que el motor serialice — es exactamente lo que 04.05 dejó anotado sobre la última unidad de una autorización | **Primera sesión con Docker** |
| 42 | **Dos pagos concurrentes contra el mismo egreso.** La segunda condición, la del compromiso: pagar 100.000 dos veces contra una liquidación de 100.000 tiene que dejar `saldo_pendiente` en cero y no en −100.000, y el segundo recibir `egreso-saldo-insuficiente` | Igual que 33 | **Primera sesión con Docker** |
| 43 | **La suma de los pagos vigentes contra `saldo_pendiente`.** `PagoEgresoRepositoryPort.totalPagadoVigente` existe para esto y **hoy no lo usa nadie**: si la columna y las filas divergen, nada lo detecta. Es el mismo hueco que 04.05 dejó entre el ledger de autorizaciones y `cantidad_consumida` | La divergencia aparece después de una secuencia de pagos y anulaciones reales, con los `UPDATE` nativos y el `clearAutomatically` de por medio | **Primera sesión con Docker** |
| 44 | **`V57` contra el motor.** Los diez `CHECK` de `egreso` —en particular `ck_egreso_borrador_sin_pagos`, `ck_egreso_beneficiario_coherente` y `ck_egreso_periodo_coherente`, cada uno en sus dos direcciones—, la columna generada **`anulado_key`** (que exista, sea `STORED` y valga el centinela `'1970-01-01'`), el unique del comprobante **con y sin** anulación de por medio, los cuatro `CHECK` de `pago_egreso`, y el `ALTER` que agrega `PAGO_EGRESO` al `CHECK` de `movimiento_caja` —**incluido que los tres valores viejos sigan siendo válidos**— | MySQL ignoró en silencio toda la sintaxis `CHECK` hasta 8.0.16, y en 8.4 una expresión mal escrita sobre una columna generada falla con un **3819** que sólo aparece al ejecutar. Es lo que esta clase de test ya destapó en 03.06. **Ninguna migración de esta etapa se aplicó jamás contra un motor** | **Primera sesión con Docker** |
| 45 | **El ciclo completo: borrador → confirmar → pagar parcial → anular pago → pagar total → anular egreso rechazado.** Con `movimiento_caja` acumulando `EGRESO` y `REVERSION_DE_EGRESO` en orden, el saldo de la jornada volviendo a su valor, y el 409 `egreso-con-pagos` cuando queda un pago vigente | Tres agregados y dos `UPDATE` nativos con `clearAutomatically`: es exactamente donde la copia en memoria de JPA y la fila real se separan, y es la trampa que este repositorio ya pagó | **Primera sesión con Docker** |
| 46 | **La compensación cae en la jornada abierta HOY.** Pagar en la jornada A, cerrarla, abrir la jornada B y anular el pago: la reversión tiene que asentarse en **B**, y el `saldo_teorico_cierre` de A tiene que quedar **intacto** | Exige dos jornadas reales, un cierre real y releer una fila que la transacción anterior ya escribió | **Primera sesión con Docker** |
| 47 | **El arreglo de `revertir` sin caja abierta.** Anular un pago hecho **por transferencia** cuando la sede no tiene jornada abierta tiene que funcionar —es el defecto heredado de 07.03 que esta etapa corrige— y anular uno **en efectivo** en la misma situación tiene que seguir rechazándose con `caja-no-abierta` | El movimiento sin jornada sólo existe contra la base: el `CHECK (medio <> 'EFECTIVO' OR jornada_caja_id IS NOT NULL)` es quien lo sostiene | **Primera sesión con Docker** |
| 48 | **Aislamiento de tenant de lo que 07.05 agrega.** Un actor del tenant B no lista, ve, edita, confirma, anula ni paga un egreso del tenant A, y no anula un pago de A. Resultado **404, nunca 403** | `AGENT.md` §6 lo exige en **cada** test de integración | **Primera sesión con Docker** |

### Lo que esta etapa deliberadamente NO cubre, ni ahora ni después

| Escenario | Por qué |
|---|---|
| **La capa REST de las ocho operaciones nuevas** | Mismo motivo que en 04.02 y 04.03: un IT de `api` exigiría el contrato regenerado, y `0.39.0` está en drift. Escribir contra el contrato viejo sería escribir contra una forma que va a cambiar |
| **El adjunto binario del comprobante (RF-M22-003)** | No está implementado. La etapa entrega la **referencia** documental —tipo, número y fecha— y no el archivo, porque sería el **tercer consumidor** del storage duplicado y la condición de salida escrita desde 04.02 es extraerlo a `platform.spi`. Eso refactoriza dos módulos cerrados y es una etapa propia. **Decisión pendiente del usuario** |
| **El cálculo de la liquidación desde las sesiones** | Fuera de alcance por el plan: *"sin inventar regla remunerativa"*. RF-M22-006 y RF-M22-007 son de la segunda entrega |

## AKINE-07.06 — Reportes y tableros del MVP (M23)

**Un solo archivo de test unitario con cinco casos**, por decisión explícita del usuario, y **ni un
`*IT.java`**: Docker no arranca.

Los cinco casos que sí se escribieron protegen el **gate, el recorte por permiso, el aislamiento de
tenant, la validación del rango y la auditoría clínica** — todo lo que decide si el reporte es
seguro y honesto. **Ninguno prueba una fórmula**, y es deliberado: un test que mockea el
repositorio y después comprueba que la suma da lo que el mock devolvió no prueba nada. Es el mismo
error que este repositorio ya documentó cuando despachaba el evento que el template escuchaba.

Así que lo que falta acá **es todo lo que hace que los números sean los números**, y conviene
leerlo como lo que es: sin estos escenarios, el reporte está verificado como mecanismo y **no como
contador**.

| # | Escenario | Por qué sólo se puede probar contra el motor | Etapa destino |
|---|---|---|---|
| 49 | **Aislamiento de tenant de las quince agregaciones.** Dos organizaciones con datos en el mismo rango de fechas: los totales del tenant A no pueden incluir una sola fila de B, en ninguno de los cinco reportes. Y la sede de otro tenant tiene que dar **404, nunca 403** | Es **la peor falla posible de un producto multi-tenant y la más silenciosa**: una agregación que se olvida del filtro no falla, devuelve un número más grande, y no hay forma de notarlo sin datos de dos tenants en la misma base. Un mock nunca lo va a mostrar. `AGENT.md` §6 lo exige en cada IT | **Primera sesión con Docker** |
| 50 | **La reconciliación cierra.** Un cobro en efectivo produce su movimiento de caja, y `conciliacion-diferencia` tiene que dar **exactamente cero**. Después: un cobro con dos medios (efectivo + tarjeta) tiene que sumar sólo la parte en efectivo, y uno íntegramente con tarjeta tiene que dejar la diferencia en cero igual | Es **el indicador que detecta plata cobrada que no entró a ninguna caja**, y hoy no lo probó nadie. La relación cobro↔movimiento no es uno a uno y sólo se observa con los dos agregados escribiendo de verdad | **Primera sesión con Docker** |
| 51 | **`CONVERT_TZ` y el turno de las 21:30.** Un turno a las 21:30 hora de Ushuaia tiene que caer en la fila **de ese día** y no en la del siguiente, y el mismo dato con la sede en Córdoba tiene que agrupar distinto. Incluye el caso degradado: **qué pasa si la base no tiene cargadas las tablas de zonas horarias** y `CONVERT_TZ` devuelve `NULL` — el `IFNULL` tiene que degradar al instante UTC y **no perder la fila** | `CONVERT_TZ` depende de `mysql.time_zone_name`, que puede estar vacía. Es una condición del motor, no del código, y el modo de falla es el peor posible: la fila desaparece del conteo en vez de dar error | **Primera sesión con Docker** |
| 52 | **`V59` contra el motor**, y que los dos índices **se usen**: `EXPLAIN` de las cinco agregaciones principales no puede mostrar un `ALL` sobre `sesion` ni sobre `caso_clinico` | Un índice que el optimizador ignora es un índice que no existe, y sólo `EXPLAIN` contra datos reales lo dice. **Ninguna migración de F7 se aplicó jamás contra un motor** | **Primera sesión con Docker** |
| 53 | **Los bordes del rango, inclusive de los dos lados.** Un hecho exactamente a las 00:00:00.000000 del día `desde` entra; uno a las 23:59:59.999999 del día `hasta` **también**; uno a las 00:00:00 del día siguiente **no**. Para las dos clases de columna: instantes (`cobrado_en`) y fechas de negocio (`fecha_negocio`) | La precisión `DATETIME(6)` y el límite exclusivo `< hastaInstante` sólo se verifican contra el motor. Un test unitario comprobaría la aritmética de `java.time`, que no es lo que está en duda | **Primera sesión con Docker** |

### Lo que esta etapa deliberadamente NO cubre, ni ahora ni después

| Escenario | Por qué |
|---|---|
| **La capa REST de las tres operaciones nuevas** | Mismo motivo que en 04.02, 04.03 y 07.05: un IT de `api` exigiría el contrato regenerado, y `0.41.0` está en drift. Escribir contra el contrato viejo sería escribir contra una forma que va a cambiar |
| **El export asíncrono con progreso** | No está implementado, y no por olvido. El plan lo pide *"cuando excedan el tiempo interactivo"*, y **sin una medición contra MySQL real no hay forma de saber cuándo eso pasa**. Lo que la etapa entrega en su lugar es un tope duro —366 días de ventana, 500 filas por sección— que es lo único honesto mientras tanto. Su etapa destino es la que tome esa medición |
| **Que `prestado` y `presentado` den un número distinto de cero** | **No se puede.** No existe ninguna obligación con `responsable = FINANCIADOR` y nada la produce. No es deuda de verificación: es un cimiento que falta, el mismo que 07.04 declaró, y su destino es una etapa propia que recablee el devengado. **Decisión pendiente del usuario** |
| **El recorte "Limitado" por rol** —que un `PROFESIONAL` vea sólo su propia actividad— | Es el alcance **`OWN`**, que **no está implementado en ninguna parte del repositorio** y cuya causa raíz es que no hay vínculo entre cuenta y persona. La matriz de permisos lo declara como hueco abierto con etapa destino **F8**. Inventar acá una versión del recorte sería fijar la respuesta equivocada |
