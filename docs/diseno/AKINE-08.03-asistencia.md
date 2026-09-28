# AKINE-08.03 — Asistencia y operación de clases (M28, M13)

Diseño de la etapa. **La revisión adversarial está en `AKINE-08.03-challenge.md` y manda sobre
este documento.**

Trazabilidad: RF-M28-007, RF-M28-009; RF-M13-007, RF-M13-008; RN-M28-006, RN-M28-007, RN-M28-009;
RNF-M28-001..005. Sale de AKINE-08.02 (`inscripcion_clase`, el cupo como reserva atómica, la lista
de espera) y de AKINE-08.01 (módulo `activity`, `clase_programada`, exclusión de agenda).

RF-M18-008 y RF-M19-009 figuran en la etapa del plan y **no se implementan**: el motivo está en
§6 y en la pregunta 4 del challenge, y es que su precondición no existe todavía en el esquema.

---

## 1. El problema que decide toda la etapa

**Tomar asistencia no es prestar una atención clínica, y una clase no es N sesiones.**

Es la regla maestra que el proyecto sostiene desde 06.01 —Sesión ≠ Turno, DP-05— trasladada al
mundo grupal, y acá tiene una forma concreta: hay **tres hechos distintos** que la tentación natural
es fundir en uno.

| Hecho | Qué afirma | Dónde vive |
|---|---|---|
| **Inscripción** | esta persona tiene reservado un lugar | `inscripcion_clase` (08.02) |
| **Asistencia** | esta persona **estuvo** en esta clase | `asistencia_actividad` (esta etapa) |
| **Atención clínica** | a esta persona se le prestó una prestación clínica documentada | `sesion` (08.04/08.05) |

La inscripción es la reserva; la asistencia es el hecho operativo; la atención clínica es un
documento. **Ninguna transición administrativa prueba por sí sola que una prestación ocurrió** y
**ninguna asistencia prueba por sí sola que hubo atención clínica** (RN-M28-007).

### 1.1 Por qué la asistencia no puede ser sólo un estado de la inscripción

08.02 dejó `ASISTIO` y `AUSENTE` en `EstadoInscripcion`, y la salida barata sería transicionar la
inscripción y terminar. **No alcanza, y por tres razones que no son de gusto:**

1. **Un estado no tiene actor, instante ni observación.** RF-M28-007 pide registrar quién marcó,
   cuándo, qué instructor estaba al frente y la observación operativa. Meter esas cuatro columnas
   en `inscripcion_clase` es hacer que la fila de la reserva cargue con los datos del hecho: es
   exactamente el atajo `Paciente → Sesión` que §9 de `AGENT.md` prohíbe, con otro nombre.
2. **Un estado no admite corrección trazable.** "La marqué ausente y había venido" es la operación
   más frecuente de un mostrador. Pisar `estado` borra que alguna vez dijo otra cosa, y eso es
   información histórica relevante (regla maestra 10).
3. **La especificación declara la entidad.** §30.6 enumera `AsistenciaActividad` con
   `claseId, personaId, fecha, asistencia, profesionalId, observaciones`, y §30.7 pone
   `ClaseProgramada + InscripcionClase + AsistenciaActividad` como la Fase 2 obligatoria. No es una
   invención de esta etapa.

**Pero el estado tampoco sobra.** La inscripción sigue siendo la que ocupa el lugar, y su estado es
lo que la grilla, el cupo y las etapas económicas leen. Así que las dos cosas se escriben **en la
misma transacción**: la fila de `asistencia_actividad` es el hecho, y `inscripcion_clase.estado`
es su proyección administrativa. La función que las une vive en un solo lugar
—`ResultadoAsistencia.estadoDeInscripcion()`— por el mismo motivo por el que `consumeCupo()` vive
en uno solo: dos copias de la misma regla divergen en la primera modificación.

### 1.2 El cupo no se mueve, y hay que decirlo en voz alta

`RESERVADA`, `CONFIRMADA`, `ASISTIO` y `AUSENTE` **consumen cupo los cuatro** (08.02,
`EstadoInscripcion.consumeCupo()`). Marcar asistencia va de un estado que consume a otro que
consume: **`cupo_ocupado` no cambia nunca en esta etapa**, y ninguna operación de acá llama a
`tomarCupo` ni a `liberarCupo`… salvo una, el ingreso sin inscripción de §3.5, que **crea una
inscripción** y por lo tanto sí pide lugar.

Consecuencia práctica: **esta etapa no introduce ninguna contención nueva por clase.** Marcar
asistencia de cuarenta participantes no serializa nada contra la fila de `clase_programada`, porque
no la escribe. El lote de §3.4 es barato por construcción, no por optimización.

Y el corolario que vale para 08.07: **ausentarse no devuelve el lugar** —lo decidió 08.02 y esta
etapa lo respeta— así que la política de no-show es una decisión **económica**, no de cupo, y vive
en la etapa que tenga el dinero.

---

## 2. Modelo

### 2.1 `asistencia_actividad` — propietario `activity`

El hecho operativo. Una fila por participante y por clase.

| Columna | Por qué |
|---|---|
| `organization_id`, `consultorio_id`, `clase_id` | tenant, sede y clase, con el mismo criterio de 08.02: `consultorio_id` va aunque sea derivable, porque derivarlo obliga a un join para filtrar por sede |
| `persona_id` | **Persona, no Paciente.** Haber ido a Pilates no crea `perfil_paciente` (RF-M07-010) |
| `inscripcion_id` | el recibo del que cuelga. Una asistencia sin inscripción no existe: ver §3.5 |
| `resultado` | `PRESENTE`, `PRESENTE_TARDE`, `AUSENTE` — ver §2.4 |
| `origen` | `MOSTRADOR`, `LOTE`, `CIERRE`, `INGRESO_SIN_INSCRIPCION`. Es lo que distingue "alguien la marcó" de "la marcó el cierre por no-show", y sin eso el cierre operativo se vuelve indistinguible de una decisión humana |
| `profesional_membership_id` | el instructor **congelado** al momento del hecho, copiado de la clase. Ver §2.5 |
| `observaciones` | RF-M28-007 paso 6. Operativa, **no clínica**: ver §5 |
| `registrada_en`, `registrada_por_cuenta_id` | actor e instante del hecho |
| `corregida_en`, `corregida_por_cuenta_id`, `correcciones` | la última corrección y cuántas hubo. El detalle está en `asistencia_evento` |
| `version` | control optimista |

**Uniques**, los dos encabezados por `organization_id`:

- **`uk_asistencia_clase_persona (organization_id, clase_id, persona_id)`** — **la referencia
  económica única** que el plan pide. Sin `deleted_key` y **a propósito**: ver §2.3.
- `uk_asistencia_inscripcion (organization_id, inscripcion_id)` — un recibo, un hecho.

Índice `ix_asistencia_clase (organization_id, clase_id, id)`, que es el detalle operativo paginado
de RF-M28-009.

### 2.2 La idempotencia sale del hecho, no de una clave

RNF-M28-004 exige que la asistencia sea segura ante reintentos, y el plan lo repite: "retries no
duplican asistencia, obligación ni cobro". **No hay `Idempotency-Key` en esta etapa, y no es un
olvido.**

La clave natural del hecho es `(clase, persona)` y ya está en un unique. Entonces:

| Segundo pedido | Qué pasa |
|---|---|
| mismo `resultado` | se devuelve la fila existente, sin escribir, sin auditar de nuevo. **200** |
| `resultado` distinto | es una **corrección**, exige motivo, pisa el valor y **apendea** el evento |

Es la misma forma que 06.05 le dio al cierre de sesión: la idempotencia se evalúa contra el hecho
ya ocurrido, no contra una clave que el cliente tiene que acordarse de mandar. Una clave habría
agregado dos columnas, un `CHECK` y una consulta más para proteger algo que el unique ya protege
mejor — y encima habría dejado el agujero de que dos claves distintas produzcan dos asistencias
para la misma persona.

### 2.3 Se corrige, y la corrección no borra: `asistencia_evento`

La tabla es **append-only**, misma forma que `clase_evento`, `turno_evento`, `caso_evento` y
`autorizacion_movimiento`: sin `version`, sin `updated_at`, sin baja, y **su puerto en Java no
declara `update` ni `delete`** — la ausencia de esas operaciones es la única garantía real de que
el historial sea inmutable.

| Columna | |
|---|---|
| `organization_id`, `consultorio_id`, `clase_id`, `asistencia_id` | alcance y a qué hecho pertenece |
| `tipo` | `REGISTRO` o `CORRECCION` |
| `resultado_anterior`, `resultado_nuevo` | `NULL` el primero en el registro inicial |
| `motivo` | **obligatorio para corregir**, `NULL` al registrar |
| `actor_cuenta_id`, `ocurrido_en` | |

**Por qué se pisa el valor y se apendea el evento, y no se versionan filas como 04.02.** La entrada
clínica de 04.02 es un **documento**: sus versiones sucesivas tienen que poder leerse como
documentos completos, porque lo que se firmó en la versión 2 importa entero. Una asistencia es un
**hecho con un solo valor vigente** —estuvo o no estuvo— y su historia es una secuencia de
correcciones, no una pila de documentos. Esa es la forma de `turno_evento`, y es la que se usa.
Versionar filas acá obligaría además a decidir cuál de las N es "la" referencia económica, que es
justamente lo que el unique de §2.1 resuelve.

**Y por eso `asistencia_actividad` no tiene `deleted_at` ni `deleted_key`.** Una asistencia no se da
de baja: se corrige. Si tuviera baja lógica, el unique tendría que incluir `deleted_key` para que el
histórico no estorbara, y entonces **dos filas vivas podrían existir para la misma persona en la
misma clase** en cuanto alguien diera de baja una — que es exactamente la puerta que la referencia
económica única existe para cerrar. Es una desviación consciente de la forma de `V30`/`V58`/`V60`
y está argumentada en la pregunta 5 del challenge.

Lo que sí se conserva y no se toca: **cancelar una inscripción con asistencia ya registrada es
409**, y eso ya lo hacía `InscripcionClase.cancelar` desde 08.02 —remitiendo literalmente a esta
etapa—. No se modifica esa regla: el camino para revertir es la corrección, no la baja.

### 2.4 `ResultadoAsistencia`: tres valores, y la inscripción sigue con seis estados

El plan pide "presente/ausente/tarde/cancelado por participante". **`TARDE` no se agrega a
`EstadoInscripcion`**, y el motivo es el mismo con el que 08.02 se negó a agregar `OFRECIDA`:
RN-M28-004 enumera exactamente seis estados de inscripción, y agregar uno séptimo para un matiz
operativo convierte la máquina de estados normativa en una lista abierta.

```
PRESENTE        -> EstadoInscripcion.ASISTIO
PRESENTE_TARDE  -> EstadoInscripcion.ASISTIO
AUSENTE         -> EstadoInscripcion.AUSENTE
```

El vocabulario rico vive en el hecho, donde no rompe nada; la proyección administrativa se queda con
los dos valores que la norma declara. **Y `cancelado` no es un resultado de asistencia**: es
`CANCELADA` en la inscripción, que ya existe y que significa otra cosa —no vino porque se dio de
baja antes, y liberó el lugar—.

Que `PRESENTE_TARDE` mapee a `ASISTIO` tiene una consecuencia que conviene decir: **para el cupo y
para la economía futura, llegar tarde es haber asistido.** Si algún centro quiere que la tardanza
tenga consecuencia económica distinta, eso es una política de 08.07 y tiene el dato para leerlo.

### 2.5 El instructor se congela, y no se elige por participante

`profesional_membership_id` se copia de `clase_programada` en el momento de registrar, y **no se
puede pasar por parámetro**. Dos razones:

- **La clase tiene un profesional, no cuarenta.** Permitir uno por participante haría que la misma
  clase tuviera N instructores sin que nada lo justifique, y volvería a insinuar que una clase son N
  turnos — la confusión que 08.01 y 08.02 gastaron su diseño en evitar.
- **Un reemplazo de instructor es una reprogramación de la clase**, y esa operación ya existe desde
  08.01 con su validación de habilitación, su exclusión contra la agenda y su evento en
  `clase_evento`. Duplicar esa validación acá sería una segunda fuente de verdad sobre quién puede
  dar qué oferta.

Se congela y no se deriva al leer por el mismo criterio que `inicio`/`fin` en `V58` y que el importe
de M18: reprogramar la clase mañana no puede cambiar quién estuvo al frente ayer.

Es `NULL` cuando la oferta no exige profesional (`requiere_profesional = 0`, `V24`).

### 2.6 `clase_programada` gana el ciclo operativo (ALTER en `V61`)

`EstadoClase` sube de dos valores a cuatro: `PROGRAMADA`, **`EN_CURSO`**, **`REALIZADA`**,
`CANCELADA`. El javadoc de 08.01 ya lo anticipaba —"los estados operativos llegan en 08.03"— y
agregar valores es aditivo.

| Columna nueva | |
|---|---|
| `iniciada_en`, `iniciada_por_cuenta_id` | RF-M13-007: la clase abrió y se puede tomar lista |
| `cerrada_en`, `cerrada_por_cuenta_id` | el cierre operativo |

> **Nota de migración, que esta semana costó un defecto real en otra rama.** `V58` y `V60` **no
> definen ningún `CHECK` sobre `clase_programada.estado`** —se verificó leyendo las dos, no
> asumiendo—, así que no hay lista de valores que reconstruir y `V61` no ejecuta ningún
> `DROP CHECK`. Si lo hiciera, reescribiría la lista entera y borraría lo que la migración anterior
> agregó, que es exactamente lo que le pasó a `V57` con `V56`.

`TipoEventoClase` gana `INICIO` y `CIERRE`, que se apendean a `clase_evento` — la tabla ya existe,
ya es append-only y su columna `tipo` es `VARCHAR(20)` sin `CHECK`.

---

## 3. Operaciones

Siete, todas bajo `/api/v1/consultorios/{consultorioId}/clases/{claseId}`.

### 3.1 Iniciar la clase (RF-M13-007)

`POST /inicio`. Permiso `clase:manage`. `PROGRAMADA` → `EN_CURSO`.

**Idempotente**: iniciar una `EN_CURSO` devuelve la misma clase sin auditar de nuevo. Una
`CANCELADA` o una `REALIZADA` es 409.

**No exige que el reloj haya llegado al horario**, y es deliberado: una clase que arranca cinco
minutos antes es normal y el sistema no tiene por qué discutirlo. Lo que sí exige es que la clase
esté viva.

### 3.2 Cerrar la clase (cierre operativo idempotente)

`POST /cierre`. Permiso `clase:manage`. `EN_CURSO` o `PROGRAMADA` → `REALIZADA`.

El cierre **resuelve a todos los que quedaron sin resultado**, que es lo que hace que se cumpla
"cada participante tiene un resultado único y trazable":

1. cada inscripción `RESERVADA` o `CONFIRMADA` **sin fila de asistencia** recibe una con
   `resultado = AUSENTE` y `origen = CIERRE`, y su estado pasa a `AUSENTE`;
2. cada inscripción en `LISTA_ESPERA` se **cancela** con motivo fijo —la clase pasó y nunca se le
   liberó un lugar—, porque dejarla esperando una clase que ya ocurrió es un estado que no se
   resuelve nunca;
3. la clase queda `REALIZADA`, con `cerrada_en` y actor, y se apendea `CIERRE` a `clase_evento`.

**Idempotente, y sin depender de una bandera.** Un segundo cierre no encuentra pendientes —porque el
unique de §2.1 impide una segunda fila para la misma persona— y no cambia nada. Es la misma forma
que 06.05: la idempotencia sale del estado del mundo, no de un flag que alguien tiene que acordarse
de leer.

**El cierre no devenga nada.** Ver §6.

> **Una clase se cierra aunque nadie haya venido.** El caso "cierre parcial" del plan no es un error:
> una clase con cero asistencias cierra con todos `AUSENTE`, y eso es un dato, no una falla.

### 3.3 Registrar asistencia de un participante (RF-M28-007)

`POST /asistencias`. Permiso `asistencia:manage`.

Cuerpo: `inscripcionId`, `resultado`, `observaciones`, `motivo` (sólo para corregir).

Orden: contexto (403) → sede del tenant (404) → permiso (403) → clase viva y del tenant (404) →
**clase en estado que admite asistencia** (409) → inscripción de esa clase (404) → **la inscripción
tiene lugar** (409) → registrar o corregir.

| Situación | Desenlace |
|---|---|
| no hay fila previa | se crea, evento `REGISTRO`, inscripción transiciona. **201** |
| hay fila y el resultado es el mismo | se devuelve tal cual, sin escribir. **200** |
| hay fila y el resultado cambia | corrección: exige `motivo`, pisa el valor, evento `CORRECCION`. **200** |
| la inscripción está `CANCELADA` | **409** — se dio de baja y no ocupó lugar |
| la inscripción está en `LISTA_ESPERA` | **409** — nunca tuvo lugar. El camino es promoverla (una baja la promueve) o §3.5 |
| la clase está `CANCELADA` o `PROGRAMADA` sin iniciar | **409** — primero se inicia |

Que la clase tenga que estar `EN_CURSO` o `REALIZADA` es lo que hace que el estado operativo sirva
para algo: sin esa condición, `EN_CURSO` sería decorativo. Se admite también sobre una `REALIZADA`
**porque las correcciones llegan después de que la clase terminó**, que es cuando se descubren.

### 3.4 Lote (RF-M13-008, "resultados parciales explícitos")

`POST /asistencias/lote`. Permiso `asistencia:manage`. Hasta 200 ítems.

**Cada ítem va en su propia transacción.** RF-M13-008 paso 6 lo pide literalmente —"actualización
transaccional independiente"— y el motivo es el que el plan nombra: un fallo individual no puede
ocultar los resultados de los demás ni hacer que una tabla de cuarenta filas se pierda entera porque
una persona estaba cancelada.

La respuesta trae, por ítem, `inscripcionId` + (`asistencia` | `error`), con el `problemType` y el
detalle del fallo. **El HTTP es 200 aunque haya fallos parciales**: no es un error del pedido, es el
resultado del pedido. Publicar 207 obligaría a cada cliente generado a manejar un status que el
repositorio no usa en ninguna otra parte.

El método de lote **no es transaccional** y delega en el de §3.3, que sí lo es. Es la única forma de
que "independiente" signifique algo: si el lote abriera transacción, los hijos se unirían a ella y
el primer fallo revertiría todo.

### 3.5 El que se presenta sin estar inscripto (RF-M13-007, caso borde del plan)

`POST /ingresos`. Permisos **`inscripcion:manage` y `asistencia:manage`**, los dos.

Es el caso que más se parece al check-in de 05.04, y la respuesta es: **no hay asistencia sin
inscripción.** La inscripción es lo que sostiene el cupo, y una asistencia huérfana dejaría a una
persona adentro de una clase llena sin que el contador se entere — la sobreventa por la puerta de
atrás, después de que 08.02 cerró la de adelante.

Entonces la operación hace las tres cosas en **una transacción**, en este orden:

1. **toma el lugar** con el mismo `UPDATE` condicional de 08.02 —`tomarCupo`—, así que el techo
   `LEAST(capacidad, :capacidadEfectiva)` sigue siendo infranqueable. Cero filas → **409
   `clase-completa`**, con capacidad efectiva y ocupados, y **sin lista de espera**: una clase en
   curso no tiene cola;
2. crea la `InscripcionClase` en `RESERVADA`;
3. registra la asistencia con `origen = INGRESO_SIN_INSCRIPCION`.

**La única regla que esta operación levanta es la de "la clase ya empezó".** `inscribir` la rechaza
—08.02, y con razón: llenar por API una clase que ya arrancó es otra cosa— y acá es justamente la
condición de entrada. Todo lo demás se revalida igual: persona del padrón y ficha activa, no
duplicada, tenant, permisos.

`READ_COMMITTED`, como toda mutación que toma el lock de la clase. **Orden de locks intacto**:
`clase_programada` y después `inscripcion_clase`; no se toca `agenda_sede`.

### 3.6 Detalle operativo paginado (RF-M28-009)

`GET /detalle-operativo?page&size`. Permiso **`inscripcion:read`**, el mismo de la lista de
participantes: lleva nombre y documento.

Cabecera —oferta, horario, estado operativo, capacidad, ocupados, presentes, ausentes, sin
resolver— más una página de participantes con su inscripción, su asistencia si la hay, y nada más.

**Paginado y no completo** por RNF-M28-003 y CA-M28-009-06, y **sin una sola consulta por
participante**: los nombres se resuelven en un batch contra `person.spi`, igual que la lista de
08.02. Resolverlos de a uno convierte la pantalla en tantas consultas como participantes, que crece
justo con lo exitosa que sea la clase.

**Lo que NO lleva, y es la mitad del punto** (RNF-M28-002, RF-M28-009 "separa indicadores clínicos
de datos económicos"): ni un dato clínico, ni cobertura, ni estado de deuda, ni pase, ni abono. Lo
económico no está porque **no existe** —§6— y lo clínico no está porque un instructor de Pilates con
`inscripcion:read` no puede ver a qué se atiende nadie.

### 3.7 Historial de una asistencia

`GET /asistencias/{asistenciaId}/historial`. Permiso `inscripcion:read`. Los eventos append-only,
del más viejo al más nuevo. Es lo que hace auditable la corrección de §2.3 y lo que sostiene
CA-M13-008-05.

---

## 4. Permisos

Un código nuevo en `organization.domain.PermissionCode` y su espejo textual en
`activity.domain.PermissionCodes`:

| Código | Quién lo tiene |
|---|---|
| `asistencia:manage` | mismo reparto que `inscripcion:manage` — administrativo, profesional/instructor, administrador de consultorio y de organización. **`PLATFORM_ADMIN` no lo recibe**: tomar lista es operación del centro |

**Por qué uno nuevo y no `inscripcion:manage`.** Anotar a alguien y decir que vino son decisiones
distintas: en un centro chico las toma la misma persona, pero en uno con instructores el que da la
clase marca asistencia y **no** debería poder inscribir ni cancelar inscripciones ajenas. Un permiso
que no se puede separar convierte esa política en imposible, y separarlo después es un cambio
incompatible.

**No se crea `asistencia:read`.** El detalle operativo y el historial usan `inscripcion:read`, que
ya protege exactamente el mismo dato —quién está en esta clase— y partirlo en dos obligaría a dar
los dos juntos en todos los casos, que es la definición de un permiso decorativo.

`activity` no importa `organization.domain`: declara los códigos como texto y los resuelve por
`organization.spi.PermissionGuard`.

---

## 5. Lo clínico: qué se hace y qué explícitamente no

**No se crea ninguna Sesión, ningún Caso, ninguna entrada clínica, ninguna Historia Clínica.**
Marcar presente no toca `clinical` ni `encounter`, y esta etapa no importa ninguno de los dos.

- **08.04** es la derivación al circuito clínico —resolver paciente/HC, elegir Caso y Plan— y la
  condiciona a que la Oferta sea clínica y exista autorización.
- **08.05** es la atención clínica grupal por participante, con una Sesión independiente cada uno.

Lo que esta etapa les deja es **una participación cerrada e identificable**: la fila de
`asistencia_actividad` con su unique. 08.04 va a colgar de ella el vínculo con el contexto clínico,
y lo va a hacer **leyendo `oferta.genera_registro_clinico` por su cuenta** — esta etapa no copia ese
flag ni agrega una columna `derivable`, porque anticipar una decisión de otra etapa en el esquema es
peor que no tenerla.

**`observaciones` es operativa y no clínica, y hay que sostenerlo.** "Llegó a los 10 minutos", "usó
la colchoneta chica". No es evolución, no es diagnóstico, y **la ve cualquiera con
`inscripcion:read`** — que es precisamente por qué no puede ser clínica: si alguien escribe ahí
información de salud, queda expuesta a un rol no clínico, y RNF-M28-002 lo prohíbe. El campo se
documenta así en el contrato y en la UI de 08.03-front.

---

## 6. La consecuencia económica: no entra, y por qué

La etapa del plan dice "generando la consecuencia económica correspondiente" y lista RF-M18-008 y
RF-M19-009. **No se implementan**, y la razón no es de alcance sino de precondición.

**RF-M18-008 dice literalmente**: crear deuda "cuando una Oferta usa esquema **POR_CLASE** y **la
política** define que el cargo se devenga con la asistencia". Las dos condiciones son inevaluables
hoy:

1. **`oferta.esquema_cobro` es un `VARCHAR(32)` sin lista cerrada.** `V24` lo declara textualmente:
   *"DATO DECLARADO, NO RESUELTO: en esta etapa se guarda y se muestra, nadie lo interpreta. Sin
   lista cerrada a propósito, porque el vocabulario lo fija M16/M18 y esos módulos no existen"*. Hoy
   ese campo puede decir `POR_CLASE`, `por clase`, `PorClase` o cualquier cosa. Interpretarlo acá
   sería **inventar el vocabulario de M29 desde M28**, y hacerlo en el módulo equivocado.
2. **No existe "la política".** No hay tabla, ni columna, ni default que diga si el cargo se devenga
   con la asistencia, con la inscripción o con la venta de un pack. Es lo que **08.06** define
   —"Productos, packs y venta inicial", que depende de esta etapa— y lo que **08.07** consume con su
   ledger de créditos.
3. **`OfertaSnapshot` no expone `esquemaCobro` y eso también es deliberado.** Su javadoc lo dice:
   *"Deliberadamente no incluye precio, esquema de cobro ni nada económico… cuando 07.01 tenga que
   armar la obligación va a pedir su propia proyección, y ese acoplamiento tiene que ser explícito y
   no llegar de arrastre"*. Para devengar acá habría que ensanchar ese record, que es exactamente el
   arrastre que se quería evitar.

Además, 08.02 dejó escrito que **ni un peso de cobro entra en su etapa** y que el de la clase es de
08.07/08.09. Devengar acá sería adelantarlo sobre una configuración que todavía no existe, y el
resultado no sería "casi correcto": sería deuda inventada con un arancel que nadie eligió.

**Lo que esta etapa sí entrega, que es lo que las económicas necesitan:**

- una **referencia económica única** por participante y clase —`uk_asistencia_clase_persona`—, que
  es la precondición para que "cerrar dos veces no duplique obligaciones" sea cierto sin ningún
  esfuerzo adicional del lado económico;
- un **cierre idempotente** del que colgar el devengo;
- el dato de **si llegó tarde**, por si la política lo usa;
- `origen`, que distingue una ausencia declarada por una persona de una puesta por el cierre — y
  esa distinción va a importar cuando el no-show cueste plata.

> **Queda para el usuario, escrito y no inventado:** si el cargo por clase se devenga con la
> **asistencia** o con la **inscripción**, y qué pasa con el **no-show**. Las tres respuestas
> cambian el diseño de 08.06/08.07 y ninguna se decide acá.

---

## 7. Contrato

**Aditivo.** `0.42.0` → **`0.43.0`**. Siete operaciones nuevas; **ninguna existente cambia de
forma**.

Lo que sí cambia de **valor**, y conviene decirlo en voz alta como hicieron 08.01 y 08.02:
`ClaseResponse.estado` puede devolver ahora `EN_CURSO` y `REALIZADA`. Un cliente en `0.42.0` no
cambia de tipo, pero un `switch` exhaustivo sobre el enum generado se queda corto. Es ensanchamiento
de enum, no cambio de forma: **minor, no major**.

Todas declaran `application/json` **y** `application/problem+json` en `produces` —el `produces` es
de clase— y **ninguna responde 204**: la regla que rompió activación de cuenta se cumple igual.

**Cero tipos de problema nuevos.** Se reusan los cinco que ya existen y ya significan lo que hace
falta:

| Situación | Tipo |
|---|---|
| clase / inscripción / asistencia de otro tenant o inexistente | `not-found` |
| la clase no admite iniciar, cerrar o recibir asistencia | `clase-transicion-no-permitida` |
| la inscripción está cancelada o en espera | `inscripcion-transicion-no-permitida` |
| ingreso sin inscripción y sin lugar | `clase-completa` |
| esa persona ya está inscripta (ingreso duplicado) | `conflict` |

Publicar un código propio para "no se puede marcar asistencia" obligaría al cliente a manejar dos
para el desenlace que `clase-transicion-no-permitida` ya cubre, con su `motivo` como discriminante.
Los mapeos van al `ActivityProblemHandler` que ya existe, **nunca a `GlobalExceptionHandler`**.

---

## 8. Migración

**`V61`.** `V51`–`V60` están tomadas. El número se reservó **antes** de escribir código: `V26` quedó
vacía para siempre porque 02.07 y 03.01 nacieron las dos como `V26` y Flyway no arranca con
versiones duplicadas.

Dos tablas nuevas y un `ALTER` sobre `clase_programada`. **Ningún backfill y ningún `DROP CHECK`**:
`V58` y `V60` nunca se aplicaron contra un motor, así que no hay una sola clase ni una sola
inscripción existentes, y `estado` no tiene `CHECK` que reconstruir (§2.6).

**No se editan `V58` ni `V60`.** Una migración publicada no se toca, aunque no haya corrido.

---

## 9. Alcance — qué entra y qué no

| Entra en 08.03 | Queda para |
|---|---|
| `AsistenciaActividad`, sus tres resultados y su historial append-only | — |
| Ciclo operativo de la clase: `EN_CURSO`, `REALIZADA`, cierre idempotente | — |
| Asistencia individual, corrección con motivo y lote con resultados parciales | — |
| El que se presenta sin inscripción, tomando cupo de verdad | — |
| Detalle operativo paginado, sin PHI y sin economía | — |
| Obligación por clase asistida (RF-M18-008) y su cobro (RF-M19-009) | **08.06 / 08.07 / 08.09** — §6 |
| Derivación al circuito clínico | **08.04** |
| Sesión clínica por participante | **08.05** |
| Consumo de créditos por asistencia y devolución por no-show | **08.07** |
| Abonos y cobertura periódica | **08.08** |
| Frontend | tras regenerar el contrato |

---

## 10. Lo que esta etapa NO verifica

Docker no arranca en esta máquina, así que:

- **`V61` nunca se aplicó contra un motor**, y `V58` y `V60` tampoco. **Ninguna migración de F9 se
  ejecutó jamás.**
- **El ingreso sin inscripción sobre la última vacante no se ejerció contra MySQL real.** Es el
  gemelo exacto de la deuda de 08.02 y un mock no puede contestarlo: un repositorio falso que
  devuelve `0` no reproduce el gestor de locks de InnoDB ni la semántica de current read. **No se
  simula.**
- **El unique `uk_asistencia_clase_persona` no se probó contra una base**, y es el que sostiene la
  idempotencia entera de §2.2 y el "cerrar dos veces no duplica".
- **El lote con transacciones independientes no se verificó** de la única forma que importa: que un
  ítem que falla no revierta los anteriores. Con mocks se prueba el ruteo, no la propagación.
- **El contrato no se regeneró**: `OpenApiContractIT` queda en drift, declarado y esperado. El YAML
  **no se editó a mano** para taparlo.

Todo esto queda en `docs/tests-diferidos.md` con etapa destino, empezando en el escenario 54.
