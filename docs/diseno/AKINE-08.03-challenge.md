# Design challenge — AKINE-08.03

Paso obligatorio de `CLAUDE.md` §3. **Manda sobre `AKINE-08.03-asistencia.md`.**

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

Dos tablas nuevas, **las dos propiedad de `activity`**: `asistencia_actividad` y
`asistencia_evento`. Ningún otro módulo las lee ni las escribe.

Y un `ALTER` sobre `clase_programada`, que **ya es de `activity`** desde 08.01: agregarle el ciclo
operativo no cruza ninguna frontera.

**Lo que `activity` NO posee y toca por `spi`, sin una arista nueva:** `persona` sigue siendo de
`person` y se consulta por `PacienteDirectory`; `consultorio`, memberships y permisos son de
`organization` y se resuelven por `ConsultorioDirectory` y `PermissionGuard`; la auditoría es de
`platform`, por `AuditTrail`. Que `asistencia_actividad` tenga FK hacia `persona` no habilita a
leer esa tabla desde `activity` —la FK la hace cumplir el motor, no un repositorio— y es el mismo
criterio con el que `inscripcion_clase` apunta a `persona` desde 08.02.

**La pregunta incómoda: ¿la asistencia debía vivir en `scheduling`, al lado del check-in?**
No. El check-in de 05.04 es una transición de la máquina de estados del **Turno**, y un turno es de
`scheduling`. Una asistencia a clase no tiene turno: su ciclo de vida lo manda la clase —si la clase
se cierra, se resuelven todas— y su invariante fuerte es contra `inscripcion_clase`, que es de
`activity`. Ponerla en `scheduling` partiría en dos módulos la transacción que escribe el hecho y la
proyección administrativa del mismo hecho.

**La segunda: ¿y en `encounter`, junto a la Sesión?** Menos todavía, y sería el error de fondo que
la etapa existe para evitar: **una asistencia no es una atención**. Meterla en el módulo clínico
haría que `activity` dependiera de `encounter` para registrar que alguien fue a Pilates, y que un
instructor no clínico escribiera en el módulo que guarda historias clínicas.

**Veredicto: pasa.**

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

**Cero aristas nuevas.** Las cinco que la etapa usa —`organization.spi`, `person.spi`,
`platform.spi`, `offering.spi`, `resource.spi`— ya existen desde 08.01 y 08.02, y `activity` sigue
siendo **hoja**: ningún módulo la importa.

Y lo que es más importante: **no se agrega `activity → billing.spi` ni `activity → clinical.spi` ni
`activity → encounter.spi`**, que son las tres aristas que un diseño apurado habría metido acá. La
razón está en §5 y §6 del diseño, pero vale notar que **también habrían sido las tres peligrosas**:
`encounter → clinical.spi` y `clinical → person.spi` ya existen, así que cualquier arista de
`activity` hacia esa zona hay que medirla y no razonarla.

**Comprobado, no razonado — y con control negativo.** El challenge de 04.05 dio por verificada de
memoria una arista que sí cerraba ciclo, y lo destapó una clase sonda. Acá se hizo lo mismo **antes
de escribir una línea de dominio**, y además se comprobó que la sonda medía algo.

**Corrida 1 — sonda positiva.** `activity.application.SondaDeCiclos0803` declarando las cinco
aristas de la etapa:

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.akine.architecture.ModuleArchitectureTest
[INFO] BUILD SUCCESS
```

**Corrida 2 — control negativo.** Se agregó **a propósito** una arista que cierra ciclo:
`activity.spi.SondaDeCiclos0803Spi` más `notification.infrastructure.SondaNegativa0803` que la
importa, o sea `notification → activity` contra el `activity → notification.spi` que ya existía:

```
[ERROR] com.akine.architecture.ModuleArchitectureTest.sin_ciclos_entre_modulos <<< FAILURE!
Cycle detected: Slice activity ->
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Sin la corrida 2, un 5/5 verde no prueba nada: podría significar "no hay ciclo" o "la sonda no
declaró la arista". Las dos sondas se borraron antes de escribir dominio.

`sin_ciclos_entre_modulos` usa `SlicesRuleDefinition`, que busca ciclos de **cualquier longitud** —
que es justo lo que la cabeza no encuentra.

**Veredicto: pasa, verificado con ArchUnit y con control negativo.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí, y encabezándolos.

- `uk_asistencia_clase_persona (organization_id, clase_id, persona_id)`
- `uk_asistencia_inscripcion (organization_id, inscripcion_id)`
- `ix_asistencia_clase (organization_id, clase_id, id)`
- `ix_asistencia_evento (organization_id, asistencia_id, ocurrido_en)`

`consultorio_id` va en las dos tablas aunque sea derivable de la clase, por el mismo motivo que en
`clase_programada` y `inscripcion_clase`: derivarlo obliga a un join para filtrar por sede y
habilita la consulta sin join que **ningún test de una etapa detecta**, porque los tests de una
etapa usan un solo tenant.

**Ninguna firma de puerto resuelve por id pelado.** Todas llevan `organizationId` en el `WHERE`,
incluidas las de escritura: un `UPDATE` que sólo filtra por PK funciona perfecto y es un agujero de
aislamiento.

**Cross-tenant → 404**, nunca 403. **Falta de contexto → 403**, nunca 401.

**El riesgo propio de esta etapa, que hay que nombrar:** el lote de §3.4 recibe una lista de ids del
cliente. Si el servicio resolviera cada `inscripcionId` por PK y confiara en que pertenece a la
clase de la ruta, **un lote sería un enumerador cross-tenant con resultados parciales explicándote
cuál existe**. Por eso cada ítem pasa por `findByIdInScope(organizationId, claseId, inscripcionId)`,
el mismo camino que la operación individual, y el ítem que no resuelve contesta `not-found` — no
"no es tuyo".

**Veredicto: pasa.**

## 4. Reglas maestras — ¿el diseño confunde alguna?

Seis riesgos, uno por uno. Los tres primeros son *la* etapa.

1. **Una asistencia no es una Sesión clínica (RN-M28-007).** No se crea Sesión, no se toca
   `encounter` ni `clinical`, no se importa ninguno de los dos, y no hay columna que insinúe lo
   contrario. 08.04 deriva y 08.05 documenta; esta etapa deja una participación cerrada y nada más.
   La entidad `AsistenciaActividad` existe en §30.6 de la spec **justamente** para que haya dónde
   registrar una participación que no es clínica.
2. **Una clase no es N turnos, ni N sesiones.** No se crea una fila de `turno` (CA-M28-001-06,
   heredado de 08.01/08.02) y tampoco N filas de sesión. El hecho grupal se registra una vez por
   participante en una tabla propia, y la clase tiene **un** estado operativo, no cuarenta.
3. **Turno/Inscripción es reserva; Asistencia es hecho (DP-05, regla maestra 4).** El diseño
   mantiene las dos filas separadas a propósito —§1.1— y la proyección administrativa
   (`inscripcion.estado`) se escribe en la misma transacción que el hecho, nunca por un listener
   post-commit que podría dejar una sin la otra.
4. **Deuda, Cobro y Caja siguen siendo tres cosas, y esta etapa no es ninguna.** No se devenga: §6
   del diseño. **Y vale decir cuál era la tentación**: el plan dice "generando la consecuencia
   económica correspondiente", así que *no* devengar es apartarse del enunciado literal. Se hace con
   el motivo escrito, que es que `esquema_cobro` es un `VARCHAR` libre declarado "no resuelto" por
   `V24` y que "la política" del RF-M18-008 no existe en ninguna tabla. **Devengar hoy sería
   inventar el vocabulario de M29 desde M28.**
5. **Una Persona no es un Paciente.** La asistencia apunta a `persona_id` y no exige perfil de
   paciente vigente. Ni el ingreso sin inscripción crea uno: si hace falta, lo hace
   `PerfilPacienteService` y sólo él (RF-M07-010).
6. **HC ≠ Caso ≠ Sesión** no se toca, porque no se toca nada clínico.

**Y el riesgo propio del diseño, que hay que decir en voz alta:** escribir dos filas —la asistencia y
el estado de la inscripción— **parece** una segunda copia de la verdad, la regla con la que 04.02 se
negó a materializar el timeline. No lo es, y la diferencia es la misma que 08.02 usó para
`cupo_ocupado`: **no son dos copias del mismo dato, son dos datos distintos.** La asistencia dice
"estuvo, a esta hora, marcada por esta persona, con este instructor"; el estado de la inscripción
dice "esta reserva quedó resuelta como asistida". Lo que sí hay es una **función de mapeo**, y por
eso vive en un solo lugar —`ResultadoAsistencia.estadoDeInscripcion()`— exactamente como
`EstadoInscripcion.consumeCupo()`.

**Veredicto: pasa, con el apartamiento del enunciado económico declarado acá y en §6 del diseño.**

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

**Ninguno, y la corrección es el punto entero.**

Corregir una asistencia **no borra** el valor anterior: se pisa `resultado` en la fila vigente y se
**apendea** un `CORRECCION` en `asistencia_evento` con `resultado_anterior`, `resultado_nuevo`,
motivo obligatorio, actor e instante. `asistencia_evento` no tiene `update` ni `delete` en su
puerto, que es la única garantía real de inmutabilidad.

**Y acá hay una desviación consciente de la forma del repositorio, que es lo que esta pregunta
existe para destapar:** `asistencia_actividad` **no lleva `deleted_at` ni `deleted_key`**, a
diferencia de `clase_programada` (`V58`) y `inscripcion_clase` (`V60`). El motivo:

- Una asistencia **no se da de baja: se corrige.** No hay un estado "esta asistencia ya no cuenta";
  hay "estuvo" y "no estuvo", y pasar de uno a otro es una corrección con motivo.
- Si tuviera baja lógica, el unique tendría que incluir `deleted_key` para no estorbar al histórico
  —es lo que hace `uk_inscripcion_clase_persona`— y entonces **dos filas vivas podrían coexistir
  para la misma persona en la misma clase** apenas alguien diera de baja una. Eso rompe la
  **referencia económica única** que el plan pide y que es lo que hace que "cerrar dos veces no
  duplique obligaciones" sea cierto sin esfuerzo del lado económico.
- La tercera opción —baja lógica **y** unique sin `deleted_key`— sería lo peor de las dos: una fila
  dada de baja seguiría bloqueando la creación de otra, o sea una baja que no da de baja.

**Lo que no se toca:** cancelar una inscripción que ya tiene asistencia sigue siendo **409**, tal
como `InscripcionClase.cancelar` lo dejó escrito en 08.02 remitiendo literalmente a esta etapa. El
camino para revertir es la corrección, no la baja, y eso mantiene una sola puerta.

**Veredicto: pasa, con la desviación y su motivo escritos.**

## 6. Contrato — ¿el cambio de API es aditivo? ¿Requiere versión mayor?

**Aditivo, minor.** `0.42.0` → **`0.43.0`**. Siete operaciones nuevas y **ninguna existente cambia
de forma**: ningún DTO gana ni pierde un campo, ninguna ruta cambia de método ni de cuerpo.

Lo que cambia de **valor** y hay que decir en voz alta —tercera etapa seguida que lo hace—:
`ClaseResponse.estado` ahora puede devolver `EN_CURSO` y `REALIZADA`. Es **ensanchamiento de enum**:
el tipo no cambia, el campo ya existía y el javadoc de `EstadoClase` ya anunciaba que los estados
operativos llegaban en esta etapa. Un cliente en `0.42.0` compila igual; lo que se le queda corto es
un `switch` exhaustivo, y eso es responsabilidad del consumidor cuando regenere.

**Cero tipos de problema nuevos**, cinco reusados. Es deliberado: `clase-transicion-no-permitida`
con su `motivo` ya cubre "no se puede marcar asistencia sobre esta clase", y publicar un código
propio obligaría al cliente a manejar dos para el mismo desenlace.

Ninguna operación responde **204**, así que la trampa del `Accept: application/problem+json` que
rompió activación de cuenta no aplica; igual el `produces` de clase declara los dos tipos.

El YAML **no se edita a mano** y queda en drift hasta que alguien regenere con Docker.

**Veredicto: pasa.**

## 7. Ruta crítica — ¿tiene sus cimientos? ¿Qué queda para 08.04, 08.05, 08.07 y 08.08?

Los cimientos son **08.01** —módulo `activity`, clase, exclusión de agenda—, **08.02**
—inscripción, cupo atómico, lista de espera— y **03.01** —Persona—. **Los tres existen** en esta
rama, que sale de `akine-08.02-inscripciones`.

**Desvío del plan que hay que declarar, heredado de 08.01 y 08.02:** el plan pide AKINE-07.09 —gate
y release del MVP— antes de F9, y no está cerrada. Se avanza igual porque la dependencia es de
**release**, no técnica.

**Desvío propio, y es el importante:** el plan lista **AKINE-07.01 y AKINE-07.02** como dependencias
de esta etapa, y las dos están cerradas — pero se listan *porque* la etapa devengaría. Al no
devengar (§6 del diseño), **esta etapa no consume nada de `billing`** y esa dependencia queda sin
usar. No es que falte un cimiento: es que el edificio que lo necesitaba se mudó a 08.06.

### Qué queda para cada etapa, nombrándolas

**08.04 — derivación al circuito clínico.** Resolver paciente/HC, elegir Caso y Plan, y vincular la
participación. Esta etapa le deja **la fila de `asistencia_actividad` con su unique**, que es el
ancla de la que va a colgar el vínculo. **No le deja una columna `derivable` ni una copia de
`genera_registro_clinico`**: 08.04 lo lee de la oferta por su cuenta, porque anticipar la decisión
de otra etapa en el esquema es peor que no tenerla.

**08.05 — atención clínica grupal por participante.** Una Sesión independiente cada uno, numerada
dentro del Caso. Esta etapa **no crea ninguna**, y la regla que le deja fijada es que **una clase
`REALIZADA` no sustituye el cierre clínico individual** — el plan de 08.05 lo dice y el diseño de
acá no lo contradice en ninguna línea.

**08.06 — productos, packs y venta inicial.** Es quien define el vocabulario de `esquema_cobro` y la
política de devengo. **Depende de esta etapa** según el plan, y lo que recibe es el hecho de
asistencia con su referencia única y su `origen`.

**08.07 — ledger y ciclo de créditos.** Consumir crédito **por asistencia** es literalmente su
enunciado. Lo que recibe: un hecho idempotente del que colgar el consumo, y `PRESENTE_TARDE` por si
la política distingue. Lo que **no** recibe y va a tener que decidir: si el no-show consume.

**08.08 — abonos.** No toca nada de acá directamente.

**08.09 — integración económica y auditoría.** RF-M19-009 (cobrar la clase adeudada) cae acá o en
08.07, según dónde quede el devengo.

### La decisión que queda para el usuario, y no se inventa

> **¿El cargo de una clase se devenga con la asistencia, con la inscripción, o con la venta de un
> pack? ¿Y el no-show cobra?** RF-M18-008 asume lo primero "cuando la política lo define", y la
> política no existe en ninguna tabla. Las tres respuestas cambian el diseño de 08.06 y 08.07.

**Veredicto: pasa, con los dos desvíos declarados y la decisión económica elevada al usuario.**

## 8. El caso que rompe el diseño

Son tres, en orden de cuánto duelen.

### 8.1 "Se presenta alguien que no estaba inscripto y la clase está llena"

Es **el** caso, porque la respuesta intuitiva es la que rompe todo: dejarlo pasar y registrar la
asistencia. Una asistencia sin inscripción es una persona adentro de la clase **que el contador de
cupo no ve**, y eso es la sobreventa por la puerta de atrás — justo después de que 08.02 gastó su
etapa entera cerrando la de adelante. Y es peor que la sobreventa de 08.02, porque ahí al menos las
dos personas tenían un recibo: acá una de ellas no existe para el sistema, así que **ni un backfill
podría reconstruir el número**.

**Cómo se resuelve: no hay asistencia sin inscripción.** El ingreso sin inscripción (§3.5 del
diseño) crea la inscripción de verdad, y para crearla **pide el lugar con el mismo `UPDATE`
condicional de 08.02**:

```sql
UPDATE clase_programada SET cupo_ocupado = cupo_ocupado + 1
 WHERE id = ? AND organization_id = ? AND deleted_at IS NULL
   AND cupo_ocupado < LEAST(capacidad, :capacidadEfectiva)
```

Cero filas es **409 `clase-completa`**, y la respuesta le dice al mostrador la capacidad efectiva y
los ocupados para que pueda explicarle a la persona por qué no. **Sin lista de espera**: una clase
en curso no tiene cola.

Lo único que esta operación levanta es la regla "la clase ya empezó" —que `inscribir` impone con
razón—, porque es exactamente la condición de entrada de un walk-in. **El orden de locks no cambia**:
`clase_programada` → `inscripcion_clase`, y nunca `agenda_sede`. `READ_COMMITTED`, como toda
mutación que serializa.

> **Y el techo sigue siendo infranqueable aunque el llamador tenga un bug**, porque `capacidad` está
> en el `WHERE` como columna y no sólo como parámetro. Esa decisión de 08.02 es la que hace que una
> operación nueva escrita en otra etapa no pueda sobrevender.

### 8.2 "Cierro la clase dos veces y todos quedan ausentes dos veces"

O su gemelo económico, que es el que el plan nombra: **cerrar dos veces duplica obligaciones.**

Es el caso clásico de doble submit o de retry tras timeout, y la trampa es que el cierre **hace algo
por cada participante**: si el segundo cierre volviera a recorrer la lista, escribiría una segunda
fila de asistencia por persona, y cuando 08.06 cuelgue el devengo de esa fila, serían dos deudas.

**Cómo se resuelve, y sin una bandera:** el cierre sólo resuelve **inscripciones sin fila de
asistencia**, y `uk_asistencia_clase_persona` hace imposible que exista una segunda. El segundo
cierre no encuentra pendientes, no escribe nada y devuelve el mismo resumen. Es la forma de 06.05:
**la idempotencia sale del estado del mundo, no de un flag que alguien tiene que acordarse de
leer**. Un flag `ya_cerrada` habría sido una segunda fuente de verdad sobre lo mismo que dice
`estado = REALIZADA`, y habría dejado el hueco de un cierre que puso el flag y falló a la mitad.

La transición de la clase es condicional por el mismo motivo, y una `REALIZADA` que se cierra de
nuevo devuelve sin auditar de nuevo.

### 8.3 "El lote falla en el ítem 7 y se pierden los 6 anteriores"

RF-M13-008 pide actualización transaccional independiente y el plan pide "resultados parciales
explícitos", y hay una forma de cumplirlos en apariencia que no cumple ninguno: poner
`@Transactional` en el método de lote y atrapar las excepciones adentro. **Atrapar una excepción de
persistencia no des-marca la transacción**, y Spring lanza `UnexpectedRollbackException` al
commitear — es exactamente la trampa que este repositorio ya pagó cuatro veces con las filas-lock.
El resultado sería un lote que reporta "6 ok, 1 error" y después revierte los 6.

**Cómo se resuelve: el método de lote NO es transaccional.** Itera y llama al método individual, que
sí lo es, así que cada ítem abre y cierra su propia transacción. Un fallo del ítem 7 deja los 6
anteriores commiteados y se reporta como error de ese ítem, con su `problemType`.

El precio, que no se esconde: **un lote no es atómico, y eso es lo que se pidió.** Si alguien
necesitara "todos o ninguno", esta operación no sirve y habría que escribir otra — pero nadie lo
pidió, y CA-M13-008-06 dice literalmente que cada participante conserva su estado propio.

### Lo que esto cuesta, y no se esconde

1. **La asistencia y el estado de la inscripción se escriben en la misma transacción**, así que un
   fallo al escribir el evento del historial hace fallar el registro entero. Es la contrapartida
   asumida de que la auditoría vaya adentro —misma decisión que 07.01 con el devengo—: preferimos un
   registro que falla a un registro sin rastro.
2. **La corrección pisa el valor vigente.** Para responder "qué decía el 12 de marzo" hay que
   reconstruirlo desde `asistencia_evento`, no leerlo de la fila. Es el precio de no versionar filas,
   y es el correcto para un hecho con un solo valor vigente.
3. **Nada de esto se ejerció contra MySQL real**, porque Docker no arranca. La última vacante
   disputada por un walk-in, el unique que sostiene la idempotencia y la independencia transaccional
   del lote son **exactamente** lo que un mock no puede contestar. **No se simulan**: quedan en
   `docs/tests-diferidos.md` con etapa destino, desde el escenario 54.

**Veredicto: pasa en diseño, con la verificación concurrente y la de migración explícitamente
diferidas y sin sustituto falso.**

---

## Resultado

**Ocho de ocho pasan.** Cuatro cosas quedan escritas y no resueltas, y sólo una es del usuario:

1. **El apartamiento del enunciado económico de la etapa** —RF-M18-008 y RF-M19-009 no se
   implementan— con su motivo en la pregunta 4 y en §6 del diseño: la precondición no existe en el
   esquema.
2. **La política de devengo y de no-show**, que es una **decisión del usuario** y no se inventa.
3. **La desviación de la forma de baja lógica** —`asistencia_actividad` sin `deleted_at`— con su
   motivo en la pregunta 5.
4. **La verificación contra base real diferida** por Docker, sin sustituto.

Se avanza a `tasks`.
