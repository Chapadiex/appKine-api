# Design challenge — AKINE-08.04

Paso obligatorio de `CLAUDE.md` §3. **Manda sobre `AKINE-08.04-derivacion.md`.**

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

**Una tabla nueva, `derivacion_clinica`, y es de `clinical`.** No de `activity`. Es la decisión
central de la etapa y merece el argumento entero, porque el reflejo apunta al otro lado: la etapa
se llama "derivación de participante", el comando entra por una ruta de clases, y `activity` es
quien sabe qué es una participación.

**Por qué igual no es de `activity`:**

1. **El vínculo es un hecho clínico.** "De dónde vino este paciente y por qué está en este Caso" es
   exactamente la clase de dato que DP-03 protege. Leerlo tiene que exigir permiso clínico,
   relación asistencial o justificación declarada, y **auditarse al leerse** —04.01 fijó que las
   lecturas clínicas se auditan, no sólo las mutaciones—.
2. **Esa política es privada de `clinical.application`.** `AutorizacionClinica` y `AccesoClinico`
   son package-private, y con razón. Si la fila viviera en `activity`, habría que exportarlas —o,
   mucho más probable, reescribirlas peor— y **DP-03 tendría dos implementaciones** que envejecen
   distinto. Una sola de las dos se acordaría de auditar la lectura.
3. **El riesgo concreto, no el teórico:** el detalle operativo de 08.03 (RF-M28-009) es la pantalla
   del mostrador y la mira un instructor no clínico. Si `derivacion_clinica` fuera de `activity`, un
   `caso_clinico_id` estaría a un `join` de distancia de esa respuesta, y la garantía pasaría a ser
   "alguien se acordó de no incluirlo". El plan pide, textualmente, **"sin copiar PHI al módulo
   grupal"**.

**La pregunta incómoda: ¿y entonces no debería ser de `encounter`, que es donde van las Sesiones?**
No. `encounter` documenta atenciones; una derivación no es una atención —§1 del diseño es
literalmente eso—. Y sobre todo: el vínculo apunta a **Caso y Plan**, que son de `clinical`. Las
dos FK son a tablas propias; puestas en `encounter` serían dos FK cruzadas más.

**Qué toca `activity` y qué no.** `activity` no lee ni escribe `derivacion_clinica`: la alcanza por
`clinical.spi.DerivacionClinicaRegistry` y recibe snapshots. Sigue siendo dueño de
`clase_programada`, `inscripcion_clase`, `asistencia_actividad` y sus eventos, y ninguna gana una
columna.

**`offering` gana dos campos en `OfertaSnapshot`** —`requiereCasoClinico`, `generaRegistroClinico`—
que ya existen como columnas desde `V24`. No es una tabla nueva ni un dueño nuevo: es un record de
`offering` que empieza a llevar dos columnas de `offering`.

**Veredicto: pasa.**

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

**Una arista nueva: `activity → clinical.spi`.** Es la pregunta cara de la etapa, porque `activity`
era hoja hasta acá y la zona a la que se acerca ya tiene tráfico interno: `clinical → person.spi` y
`encounter → clinical.spi` existen desde 04.01 y 06.01, y `person → encounter.spi` ya fue
rechazada por ese motivo en 04.05.

**Comprobado, no razonado — y con control negativo.** El precedente que obliga es el challenge de
04.05, que dio por verificada **de memoria** una arista que sí cerraba ciclo. Acá se hizo lo mismo
que 07.04, 07.05, 07.06, 08.01, 08.02 y 08.03: sonda + `ModuleArchitectureTest` **antes de escribir
una línea de dominio**.

**Corrida 1 — sonda positiva.** `activity.application.SondaDeCiclos0804` declarando las aristas de
la etapa: `clinical.spi.HistoriaClinicaDirectory`, `clinical.spi.CasoDirectory`,
`person.spi.PacienteDirectory` y `person.spi.AutorizacionDirectory`, las cuatro tocadas de verdad y
no sólo importadas.

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.akine.architecture.ModuleArchitectureTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Corrida 2 — control negativo.** Se agregó **a propósito** una arista que cierra ciclo, y
deliberadamente **de longitud tres**, que es la que la cabeza no encuentra: `activity.spi.
SondaDeCiclos0804Spi` más `person.infrastructure.SondaNegativa0804` que la importa, o sea
`person → activity` sobre el `activity → clinical` de la sonda y el `clinical → person` que ya
existía.

```
[ERROR] com.akine.architecture.ModuleArchitectureTest <<< FAILURE! Tests run: 5, Failures: 1
Cycle detected: Slice activity ->
                Slice clinical ->
                Slice person ->
                Slice activity
Cycle detected: Slice activity ->
                Slice person ->
                Slice activity
Cycle detected: Slice activity ->
                Slice scheduling ->
                Slice person ->
                Slice activity
```

Sin la corrida 2, un 5/5 verde no prueba nada: podría significar "no hay ciclo" o "la sonda no
declaró la arista". Y el resultado es más informativo de lo esperado: `SlicesRuleDefinition`
encontró **tres** ciclos, incluido uno que pasa por `scheduling`, un módulo que la sonda ni nombró.
Eso es precisamente lo que un razonamiento de escritorio no hace.

Las tres sondas se borraron antes de escribir dominio.

**Por qué la arista es segura:** `clinical` alcanza `person`, `contracting`, `organization`,
`offering` y `platform`, y **ninguno de esos cinco alcanza `activity`**. `activity` deja de ser
hoja pero sigue siendo terminal: **ningún módulo la importa**, y esa es toda la diferencia — es el
mismo argumento con el que el javadoc de `ConsumoDeAutorizaciones` explica por qué `billing` puede
depender de todo.

**La arista que NO se agrega, y hay que decirlo:** `activity → encounter.spi`. Sería la natural si
esta etapa creara Sesiones. No las crea (§9 del diseño), y **08.05 va a tener que medirla antes de
comprometerla**, porque `encounter → clinical.spi` ya existe y `activity → clinical.spi` acaba de
nacer.

**Veredicto: pasa, verificado con ArchUnit y con control negativo.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí, y encabezándolos.

- `uk_derivacion_destino (organization_id, origen, participacion_id, caso_clinico_id, revertida_key)`
- `ix_derivacion_participacion (organization_id, origen, participacion_id)`
- `ix_derivacion_caso (organization_id, caso_clinico_id, id)`

`consultorio_id` va aunque la HC sea de la organización, por dos motivos distintos: la derivación
**ocurrió en una sede** y la auditoría la necesita, y `AutorizacionClinica` evalúa el permiso contra
la sede del contexto, así que sin ella la fila no se podría explicar seis meses después.

**Ninguna firma resuelve por id pelado**, ni las de lectura ni las de escritura. Y hay un punto
específico de esta etapa: el comando trae **tres ids que vienen del cliente** —`casoId`,
`planTratamientoId`, `autorizacionId`— y cada uno se resuelve por un camino que ya lleva
`organizationId` **más** un predicado de pertenencia:

- el Caso, con `organizationId` y verificando `perteneceAHistoria(hcResuelta)`;
- el Plan, con `organizationId` y verificando `perteneceACaso(casoId)`;
- la autorización, con `AutorizacionDirectory.find(organizationId, **personaId**, ...)`, cuyo
  javadoc ya explica por qué `personaId` no es redundante: sin él, el vínculo de un paciente puede
  quedar atado a la autorización de otro del mismo tenant.

**Sin esa cadena, el comando sería un enumerador**: "derivá al caso 5000" contestando distinto según
exista o no. Los tres colapsan a **404** —cross-tenant es 404, nunca 403—.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿el diseño confunde alguna?

Esta es **la** etapa donde Persona/Paciente y Clase/Sesión se pueden confundir más fácil. Una por
una.

1. **Una Persona no es un Paciente.** La respuesta completa está en §4 del diseño y se resume en
   tres cerrojos: la conversión **nunca es implícita** (bandera explícita, y sin ella 409), **nunca
   la escribe una clase nueva** (el puerto `PerfilPacienteProvisioning` tiene un solo implementador
   y delega en `PerfilPacienteService.activar` — el adaptador no contiene un `INSERT`, contiene una
   llamada), y **nunca sin `paciente:manage`**, que ese servicio exige y que no se saltea por venir
   de otro módulo.
   > **Y si algún día alguien saltea el primer paso, el segundo lo frena:**
   > `HistoriaClinicaDirectory.asegurar` exige perfil vigente desde 04.01. Son dos cerrojos y los
   > dos quedan.
2. **Una clase no es N sesiones, y derivar no es atender.** No se crea ninguna Sesión, no se numera
   nada dentro del Caso —numerar es `CasoDirectory.siguienteNumeroDeSesion` y esta etapa no lo
   llama—, y no se importa `encounter` ni por asomo. 08.03 se cuidó explícitamente de no crear
   Sesiones y esta etapa **no decide distinto**: decide lo mismo por un tramo más.
3. **HC ≠ Caso ≠ Sesión.** La derivación apunta a HC **y** Caso **y** (opcional) Plan, con tres
   columnas distintas y tres validaciones distintas, y no funde ninguna: el Caso se verifica contra
   la HC resuelta y el Plan contra el Caso. La HC se **asegura** —idempotente por unique— y el Caso
   **no se abre**: tiene que existir.
4. **Turno/Inscripción es reserva; Asistencia es hecho.** La derivación se ancla en la
   **asistencia**, no en la inscripción, y exige `resultado.estuvo()`. No se deriva una reserva.
5. **Deuda, Cobro y Caja siguen siendo tres cosas y ninguna es ésta.** No se devenga y no se
   consume autorización. §6 y §9 del diseño.
6. **Información histórica relevante no se elimina.** Pregunta 5.
7. **Actividad no clínica nunca toca HC.** RF-M09-007 y CA-M09-007-06: `generaRegistroClinico =
   false` es 409 **antes** de resolver persona, de asegurar historia y de escribir nada. El orden de
   las validaciones importa y está fijado: **primero el gate de la oferta.**

**El apartamiento que hay que declarar en voz alta, porque es el segundo de la fase:** el objetivo
del plan dice *"únicamente cuando la Oferta sea clínica **y exista autorización válida**"*, y esta
etapa **no exige autorización**. El motivo está en §6 del diseño y no es comodidad: el alcance
vigente es el **Circuito Particular**, un paciente particular no tiene ninguna autorización, y
`ResultadoDeConsumo.SIN_AUTORIZACION_ELEGIBLE` declara en su javadoc que ése es *"el caso más
frecuente, no una anomalía"*. Exigirla literalmente haría la etapa inaplicable al alcance que el
producto tiene hoy. Lo que sí se implementa: **si se declara una, tiene que habilitar**, con el
`motivoNoElegible` que el snapshot ya calcula. Queda elevado al usuario.

**Veredicto: pasa, con el apartamiento del enunciado de autorización declarado acá y en §6 del
diseño.**

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

**Ninguno.** Una derivación se **revierte**: `estado = REVERTIDA`, motivo obligatorio, actor,
instante, y la fila queda para siempre. RF-M28-008 lo pide como postcondición.

**Por qué reversión y no el cuarteto `active`/`deleted_at`/`deactivation_reason`/`deleted_key` de
siempre:** porque una derivación no se "da de baja administrativamente", se **deshace**. La
distinción no es de vocabulario: una baja lógica dice "esta fila ya no cuenta para nadie", y una
reversión dice "este paciente **no** pertenece a este Caso, y acá está quién lo decidió y por qué".
Lo segundo es el dato que alguien va a querer leer. `revertida_key` cumple exactamente el papel que
`deleted_key` cumple en el resto del repositorio —el centinela `'1970-01-01'`, porque en MySQL
varios `NULL` no colisionan— así que el unique de destino protege lo vigente sin estorbar al
histórico, y volver a derivar después de revertir funciona.

**Lo que esta pregunta destapa y no se tapa:** hoy **cualquier derivación vigente se puede
revertir**, y eso va a estar mal en cuanto exista 08.05. Revertir una derivación de la que cuelga
una Sesión cerrada dejaría una atención clínica documentada apuntando a un vínculo deshecho. Hoy no
cuelga ninguna Sesión de ninguna derivación, porque esa tabla es de 08.05.

**Cómo se maneja:** se deja escrito en tres lugares —§5.3 del diseño, el javadoc de `revertir` y
acá— que **08.05 tiene que cerrar ese cerrojo** cuando cree la referencia que lo hace posible.
Construir hoy una sonda contra una tabla que no existe sería anticipar en el esquema la decisión de
otra etapa, que es exactamente lo que 08.03 se negó a hacer cuando no le dejó a esta etapa una
columna `derivable`.

**Veredicto: pasa, con el cerrojo pendiente nombrado y asignado.**

## 6. Contrato — ¿el cambio de API es aditivo? ¿Requiere versión mayor?

**Aditivo, minor.** `0.43.0` → **`0.45.0`**. El salto de dos no es un error de cuenta: **`0.44.0`
la reservó 08.06**, que está en vuelo en otro worktree — misma situación que 08.02 declaró con el
salto de `0.41`.

Cuatro operaciones nuevas y **ninguna existente cambia de forma**: ningún DTO gana ni pierde un
campo, ninguna ruta cambia de método ni de cuerpo.

**Lo que sí cambia y no se ve en el YAML:** `offering.spi.OfertaSnapshot` gana dos componentes. Es
un record **interno**, no un DTO: no viaja por HTTP y no aparece en el contrato. Rompe la
compilación de sus seis sitios de construcción —uno de producción y cinco de test—, que es
exactamente lo que se quiere de un cambio así: el compilador obliga a decidir el valor en cada
lugar en vez de dejar que un default silencioso decida por nadie.

**Tipos de problema nuevos**, y son cinco. Cada uno existe porque el cliente tiene que poder
**explicar el bloqueo** en la pantalla, que es lo que RF-M28-008 pide:
`oferta-no-clinica`, `participacion-sin-asistencia`, `persona-sin-perfil-paciente`,
`autorizacion-no-elegible`, `derivacion-duplicada`. Reusar `conflict` para los cinco haría que la
pantalla tuviera que parsear el `detail` en castellano para saber qué ofrecerle al operador.

Los que **no** se inventan: `not-found` para las tres referencias cross-tenant, `forbidden` para el
permiso, y los de `clinical` que ya existen para caso cerrado y acceso no justificado.

Ninguna operación responde **204**; igual el `produces` de clase declara `application/json` además
de `application/problem+json`, que es la regla que rompió activación de cuenta.

El YAML **no se edita a mano** y queda en drift hasta que alguien regenere con Docker.

**Veredicto: pasa.**

## 7. Ruta crítica — ¿tiene sus cimientos? ¿Qué queda para 08.05?

Los cimientos son **08.03** (asistencia, el ancla), **03.01** (Persona y PerfilPaciente), **04.01**
(HC organizacional), **04.03** (Caso) y **04.04** (Plan). **Los cinco existen** en esta rama.

**Desvío del plan, y es el que importa:** el plan lista **AKINE-04.05** como dependencia. 04.05
existe, pero **esta etapa no la usa**: no consume autorizaciones, sólo las mira, y mirarlas es
04.04 (`AutorizacionDirectory`). La dependencia se listó porque el enunciado suponía que derivar
consumiría. No falta un cimiento: el edificio que lo necesitaba **es 08.05**.

**Desvío heredado de 08.01/08.02/08.03:** el plan pide AKINE-07.09 —gate y release del MVP— antes
de F9, y no está cerrada. Se avanza igual porque la dependencia es de **release**, no técnica.

### Dónde termina derivar y dónde empieza atender — la línea, nombrada

Es lo que esta pregunta existe para fijar, y se dice en una frase: **08.04 establece el vínculo;
08.05 documenta la atención.** En concreto, **08.05 y no esta etapa**:

1. **Crea la Sesión**, una por participante, independiente, en `encounter`. Esta etapa no crea
   ninguna y no importa `encounter`.
2. **La numera dentro del Caso**, con `CasoDirectory.siguienteNumeroDeSesion`. Esta etapa no lo
   llama.
3. **Consume la autorización**, por `ConsumoDeAutorizaciones.consumirPorSesion`, que se dispara en
   el cierre. Esta etapa mira el saldo y no lo toca.
4. **Devenga**, si 08.06 resolvió para entonces la política que 08.03 elevó.
5. **Publica en el timeline clínico.** Lo que va al timeline es la Sesión, no la derivación.
6. **Cierra el cerrojo de la reversión** (pregunta 5).
7. **Mide `activity → encounter.spi` con una sonda antes de comprometerla** (pregunta 2).

Y lo que 08.05 **recibe** de acá: una fila `derivacion_clinica` vigente que ya resolvió persona,
perfil, historia, caso, plan y oferta, con `requiere_caso_clinico` congelado, y un unique por
destino del que colgar su "una Sesión por participante/caso" sin tener que revalidar nada.

**Veredicto: pasa, con los dos desvíos declarados y la línea 08.04/08.05 escrita.**

## 8. El caso que rompe el diseño

Son tres. El primero es el que la consigna nombra y no es el peor.

### 8.1 "Derivo a alguien que ya es paciente y ya tiene un Caso abierto"

Es el caso **más frecuente**, no el más adverso, y conviene empezar por él porque la respuesta
equivocada es tentadora: abrir un Caso nuevo "para esta clase".

**Cómo se resuelve: el Caso lo elige el profesional y esta etapa no abre ninguno.** El comando trae
`casoId` y tiene que existir, estar activo y colgar de la historia de esa persona. Si el paciente
tiene dos Casos abiertos —rodilla y hombro—, la pantalla se los muestra y él decide; el sistema no
adivina. Y si el Caso que eligió está **cerrado**, es 409 con el tipo de problema que `clinical` ya
publica desde 04.03: reabrirlo es una operación de M10 con su propio permiso y su propio motivo, y
hacerla de costado desde una ruta de clases sería abrir historia clínica sin que nadie lo decidiera.

Ése es el caso borde "caso cerrado" del plan, y el diseño **no le inventa un atajo.**

### 8.2 "Derivo dos veces a la misma persona"

Dos formas, y sólo una es un problema.

**Doble submit al mismo Caso.** El segundo intento choca contra
`uk_derivacion_destino`. La respuesta **no es un 500 ni un 409 seco**: es la derivación que ya
existe, devuelta como si fuera la de este intento. Es la forma de 06.05 y de
`PerfilPacienteService`: **la idempotencia sale del estado del mundo, no de un flag que alguien
tiene que acordarse de leer**, y con dos capas —el pre-chequeo resuelve el caso común, el unique
cierra la ventana de carrera que ningún `SELECT` previo cierra—.

**Derivación a dos Casos distintos.** *No es un error y se permite*, porque el plan dice "una sola
vez **por destino**" y porque el escenario es real: un paciente con dos Casos abiertos que en la
misma clase trabaja los dos. Lo que el diseño garantiza es que sean **dos filas explícitas**, cada
una con su motivo y su actor, y no una fila que cambia de Caso en silencio.

**Y la variante que sí rompía algo:** derivar, revertir, y volver a derivar al mismo Caso. Sin
`revertida_key` el unique lo impediría —una reversión que no deja volver a derivar es una reversión
que no revierte—. Con el centinela `'1970-01-01'`, la fila revertida sale del unique vigente y la
nueva entra. Es el mismo mecanismo que `uk_inscripcion_clase_persona`.

### 8.3 El que de verdad rompe el diseño: "la clase es mixta"

Seis personas en una clase de la misma oferta clínica. Tres son pacientes con Caso abierto, dos son
personas del padrón que nunca fueron pacientes, y una es paciente pero su única cobertura tiene la
autorización agotada. El instructor cierra la clase y el administrativo aprieta "derivar a todos".

**Es el caso que rompe el diseño porque la respuesta intuitiva —un lote— reintroduce todo lo que
esta etapa existe para impedir.** Un lote tendría que decidir *por su cuenta* qué Caso le toca a
cada uno, y para los dos que no son pacientes tendría que crear perfil sin que nadie lo diga. Eso
es RF-M07-010 violada seis veces en una sola llamada, y encima en silencio.

**Cómo se resuelve: esta etapa no tiene operación de lote.** Se deriva **de a uno**, con el Caso
elegido explícitamente. El caso borde "clase mixta" del plan se resuelve **no resolviéndolo
automáticamente**, y el precio se dice sin adornos: **derivar seis participantes son seis
llamadas**, y el administrativo elige seis veces.

Y hay que reconocer la asimetría, porque es real: **08.03 sí tiene lote** —`registrarLote`— y esta
etapa no. La diferencia no es de gusto. Marcar asistencia es un hecho **uniforme**: el dato es el
mismo para todos y el operador no elige nada por persona. Derivar es un hecho **discrecional por
persona**: Caso, Plan y autorización son distintos para cada uno, y un lote que los uniformara
estaría inventando la parte que importa. El lote de 08.05 —que el plan sí pide, "operación de lote
con resultados individualizados"— tiene sentido justamente **porque esta etapa ya dejó decidido lo
discrecional**: atender en lote es recorrer derivaciones que ya eligieron su Caso.

**La derivación concurrente**, que el plan lista aparte: dos administrativos derivando al mismo
participante al mismo tiempo. Lo resuelve 8.2 —el unique— y no hace falta ningún lock: no hay
contador que mover, no hay cupo que tomar y no hay correlativo que pedir. **Es la primera operación
de M28 que no necesita `READ_COMMITTED`**, y vale decir por qué: no serializa nada, sólo inserta
una fila que un unique protege.

### Lo que esto cuesta, y no se esconde

1. **Derivar son dos pasos cuando la persona no es paciente**, o tres si además hay que abrir el
   Caso. Es el precio de que ni el perfil ni el Caso se creen de costado, y se paga a propósito.
2. **`participacion_id` no tiene FK** (§5.2 del diseño): el motor no garantiza que apunte a una
   asistencia que existe. Lo garantiza que nada borra asistencias.
3. **La reversión no está protegida contra Sesiones que todavía no existen** (pregunta 5).
4. **Nada de esto se ejerció contra MySQL real**, porque Docker no arranca. Que `V63` ejecute, que
   el unique sostenga la idempotencia bajo concurrencia y que la delegación de
   `PerfilPacienteProvisioning` escriba de verdad en `perfil_paciente` son **exactamente** lo que un
   mock no puede contestar. **No se simulan**: quedan en `docs/tests-diferidos.md` desde el
   escenario **65**.
5. **El contrato queda en drift declarado.** `0.45.0` en `pom.xml`, `application.yml` e
   `info.version`; los `paths` siguen siendo los de `0.29.0`. No se edita a mano.

**Veredicto: pasa en diseño, con la verificación contra base real explícitamente diferida y sin
sustituto falso.**

---

## Resultado

**Ocho de ocho pasan.** Cinco cosas quedan escritas y no resueltas, y una sola es del usuario:

1. **El apartamiento del enunciado de autorización** —la etapa no la exige para derivar— con su
   motivo en la pregunta 4 y en §6 del diseño: el alcance vigente es el Circuito Particular.
   **Decisión del usuario:** si además hay que exigirla cuando la cobertura del paciente lo pide,
   hace falta una regla que hoy no existe en ninguna tabla.
2. **El cerrojo de la reversión contra Sesiones**, asignado a 08.05 (pregunta 5).
3. **La medición de `activity → encounter.spi`**, asignada a 08.05 (pregunta 2).
4. **La ausencia de operación de lote**, con su motivo en 8.3.
5. **La verificación contra base real diferida** por Docker, sin sustituto.

Se avanza a `tasks`.
