# Design challenge — AKINE-08.02

Paso obligatorio de `CLAUDE.md` §3. **Manda sobre `AKINE-08.02-inscripciones.md`.**

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

Una tabla nueva, `inscripcion_clase`, **propiedad de `activity`**. Ningún otro módulo la lee ni la
escribe.

Y un `ALTER` sobre `clase_programada`, que **ya es de `activity`**: agregarle `cupo_ocupado` y
`ultima_posicion_espera` no cruza ninguna frontera. Es la misma etapa tocando su propio módulo.

**Lo que `activity` NO posee y toca por `spi`:** `persona` sigue siendo de `person` y se consulta
por `PacienteDirectory` y por el nuevo `ContactoDirectory`; `notification_outbox` sigue siendo de
`notification` y se escribe por `NotificationOutbox`. Ninguna FK obliga a lo contrario: que exista
`fk_inscripcion_persona` hacia `persona` no habilita a leer esa tabla desde `activity` —la FK la
hace cumplir el motor, no un repositorio— y es el mismo criterio con el que `clase_programada`
apunta a `oferta_servicio_consultorio` sin que `activity` lea nada de `offering`.

**La pregunta incómoda: ¿`inscripcion_clase` debía vivir en `person`?** No. La inscripción es un
hecho de la **actividad**, no del padrón: su ciclo de vida lo manda la clase —si la clase se
cancela, se cancelan todas— y su invariante fuerte es contra el cupo de la clase, que es una
columna de una tabla de `activity`. Ponerla en `person` partiría esa transacción entre dos módulos
para que el asignador y el recibo vivan separados, que es exactamente lo que el diseño evita.

**Veredicto: pasa.**

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

Aristas **nuevas**, las dos salientes de `activity`: `activity → person.spi` y
`activity → notification.spi`. Las de 08.01 —`scheduling.spi`, `offering.spi`,
`organization.spi`, `resource.spi`, `platform.spi`— siguen igual.

`activity` es **hoja**: ningún módulo depende de ella, y tampoco ahora. `person` no importa nada de
`activity`; `notification` tampoco —de hecho `notification` no importa a nadie, es el extremo de
todas las flechas del sistema—.

**Comprobado, no razonado.** El challenge de 04.05 dio por verificada de memoria una arista que sí
cerraba ciclo —`clinical → person.spi` y `encounter → clinical.spi` ya existían— y lo destapó una
clase sonda. Acá se hizo lo mismo **antes de escribir una línea de dominio**: se creó
`activity.infrastructure.SondaDeCiclos0802` declarando las dos aristas nuevas y se corrió
`ModuleArchitectureTest`:

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- ModuleArchitectureTest
```

`sin_ciclos_entre_modulos` usa `SlicesRuleDefinition`, que busca ciclos de **cualquier longitud**,
no sólo de dos — que es justo lo que la cabeza no encuentra. La sonda se borra al escribir las
clases reales.

**Veredicto: pasa, verificado con ArchUnit.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí. `organization_id` en `inscripcion_clase`, **encabezando los dos uniques y los dos índices**:

- `uk_inscripcion_idempotencia (organization_id, idempotency_key)`
- `uk_inscripcion_clase_persona (organization_id, clase_id, persona_id, deleted_key)`
- `ix_inscripcion_cola (organization_id, clase_id, estado, posicion_espera, id)`
- `ix_inscripcion_persona (organization_id, persona_id, id)`

`consultorio_id` va aunque sea derivable de la clase, por el mismo motivo por el que 08.01 lo puso
en `clase_programada`: derivarlo obliga a un join para filtrar por sede y habilita la consulta sin
join que ningún test de una etapa detecta, porque los tests de una etapa usan un solo tenant.

**Ninguna firma de puerto resuelve por id pelado.** Todas llevan `organizationId` en el `WHERE`, y
el `UPDATE` condicional del cupo también —un `UPDATE` sin predicado de tenant que sólo filtra por
PK funciona perfecto y es un agujero de aislamiento—.

**Cross-tenant → 404**, nunca 403: una inscripción de otra organización se trata como inexistente.
**Falta de contexto → 403**, nunca 401.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿el diseño confunde alguna?

Cinco riesgos, uno por uno.

1. **Una inscripción no es una prestación y no devenga nada.** Ninguna línea de esta etapa toca
   `billing`, no se crea Obligación, no se congela precio. Reservar un lugar no prueba que nadie
   haya entrenado (RN-M28-007, DP-05). El devengo llega con la asistencia (08.03) y con la
   integración económica (08.09). **Y explícitamente no se adelanta el cobro de la clase.**
2. **Una clase no es N turnos.** Inscribir **no crea un turno**, ni una fila en `turno`, ni pasa
   por `scheduling`. CA-M28-001-06. La clase ya ocupó el box y el profesional una sola vez, en
   08.01, y el cupo no vuelve a tocar la agenda.
3. **Una Persona no es un Paciente.** La inscripción apunta a `persona_id` y **no exige
   `esPacienteVigente`**: anotarse en una clase de yoga no convierte a nadie en paciente ni crea un
   `perfil_paciente`. Eso lo hace `PerfilPacienteService` y sólo él (RF-M07-010). La derivación al
   circuito clínico es 08.04 y tiene su propia etapa justamente por esto.
4. **Sesión ≠ Turno ≠ Inscripción.** No se crea Sesión, no se toca `encounter`, y `CONFIRMADA` no
   afirma que nadie haya venido: para eso está `ASISTIO`, que es de 08.03.
5. **Deuda, cobro y caja siguen siendo tres cosas** y esta etapa no es ninguna.

**Y el riesgo propio del diseño, que hay que decir en voz alta:** materializar `cupo_ocupado`
parece violar "una tabla de resumen es una segunda copia de la verdad" —la regla con la que 04.02
se negó a materializar el timeline y 02.04 la disponibilidad efectiva—. **No es lo mismo, y la
diferencia es cuál de las dos filas manda.** El timeline es una proyección: la verdad está en las
entradas y el resumen sólo las repite, así que puede divergir sin que nada lo note.
`cupo_ocupado` **es el asignador**: el lugar lo otorga el `UPDATE`, y la fila de inscripción es el
recibo de un lugar que ya se otorgó. No es una copia de la verdad, es la verdad, y lo que podría
divergir son los recibos.

Es exactamente la forma de `autorizacion.cantidad_consumida` + ledger de 04.05, con el **mismo
invariante y la misma deuda**: nadie comprobó todavía contra una base que la suma de los recibos dé
el contador. Queda anotado.

**Veredicto: pasa, con la desviación de 08.01 declarada acá y en §1.3 del diseño.**

## 5. Baja lógica — ¿se conserva la información histórica?

Sí, y es la mitad del punto de la etapa: RF-M28-003 pide literalmente "liberar cupo **y conservar
historial individual**" y su validación obligatoria dice "no borrar físicamente".

Cancelar una inscripción **no borra la fila**: `estado = CANCELADA`, `deleted_at`,
`motivo_cancelacion`, actor e instante. Queda quién se había anotado, cuándo, quién lo dio de baja
y por qué. Y `posicion_espera` **se conserva después de promover**, que es la única forma de poder
demostrar después que la prioridad se respetó (CA-M28-004-06 pide que sea reproducible).

**Cuál de las dos formas de baja se usa, que es lo que el enunciado pide decidir.** Se usa la de
08.01 y `V30` —`estado` + `deleted_at` + `deleted_key` + `motivo_cancelacion`— y **no** el cuarteto
`active`/`deleted_at`/`deactivation_reason`/`deleted_key`. El motivo:

- Una inscripción **tiene una máquina de estados impuesta por RN-M28-004**, y uno de esos seis
  estados es `CANCELADA`. Una columna `active` diría lo mismo que `estado` y sería una segunda
  fuente de verdad sobre el mismo hecho: exactamente lo que este proyecto prohíbe, y el mismo
  argumento con el que 08.01 se apartó.
- `deleted_at` **sí** hace falta, y no por simetría: es lo que hace funcionar
  `uk_inscripcion_clase_persona (..., deleted_key)`, que es lo que permite que alguien que canceló
  se vuelva a anotar sin que su fila vieja estorbe ni desaparezca. Sin `deleted_key` habría que
  elegir entre prohibir la reinscripción y borrar el histórico.
- El cuarteto completo es la forma correcta para una **entidad de configuración** —un espacio, un
  servicio, una cobertura—, que no tiene estado propio y cuya baja es el único hecho que la saca de
  circulación. Una inscripción no es eso.

**Veredicto: pasa, con la forma elegida y su motivo escritos.**

## 6. Contrato — ¿el cambio de API es aditivo?

**Sí, estrictamente.** Cinco operaciones nuevas y **ninguna existente cambia de forma**:
`ClaseResponse` no gana ni pierde un campo, `GET .../clases` devuelve la misma estructura, la
agenda unificada tampoco cambia. `0.40.0` → **`0.42.0`**, minor: aditivo.

Lo que **sí** cambia es el **valor** de `ClaseResponse.ocupados`, que deja de ser siempre `0`. El
campo ya existía, ya estaba declarado y su javadoc ya decía "siempre 0 en 08.01". Un cliente en
`0.40.0` no se rompe: el tipo es el mismo y la semántica es la que el contrato prometía. **Es un
cambio de comportamiento sin cambio de contrato, y como en 08.01 conviene decirlo en voz alta en
vez de dejarlo como efecto colateral.**

Dos tipos de problema nuevos, cuatro reusados. `clase-completa` lo pide el plan explícitamente; el
duplicado reusa `conflict` porque publicar un código propio obligaría al cliente a manejar dos para
el mismo desenlace.

El YAML **no se edita a mano** y queda en drift hasta que alguien regenere con Docker.

**Veredicto: pasa.**

## 7. Ruta crítica — ¿tiene sus cimientos? ¿Qué queda para 08.03 y para 08.07/08.08?

Los cimientos son **08.01** —la clase, el módulo `activity`, `V58`, la exclusión de agenda— y
**03.01** —Persona y padrón—. **Los dos existen.** 08.01 además dejó el hueco preparado a
propósito: `contarOcupacion` en un único lugar y la regla de capacidad ya escrita leyendo cero.

**Desvío del plan que hay que declarar, el mismo de 08.01:** el plan pide AKINE-07.09 —gate y
release del MVP— antes de F9, y no está cerrada. Se avanza igual porque la dependencia es de
**release**, no técnica: 08.02 no consume ninguna capacidad que 07.09 produzca.

### Qué queda para cada etapa, explícito

**08.03 — asistencia.** `ASISTIO` y `AUSENTE` y su consecuencia. Esta etapa deja los dos valores en
el enum y decidido que **los dos consumen cupo** —el ausente reservó un lugar que nadie más pudo
usar, y liberarlo retroactivamente falsearía el histórico de ocupación—, así que 08.03 no toca el
contador: sólo transiciona estados.

**08.07 / 08.08 — créditos y abonos. NADA de cobro entra acá.** Cancelar una inscripción **no**
devuelve dinero, no genera crédito, no toca `billing`. Inscribir **no** exige abono ni pase vigente
y **no** consume ninguno. Lo único que esta etapa le deja a 08.07 es el gancho que necesita: una
operación de cancelación **idempotente**, que es la precondición para colgarle una devolución sin
devolverla dos veces. La regla se fija antes que el dinero que protege.

**08.04 — derivación clínica.** La inscripción apunta a una Persona y nada más. Convertirla en
paciente es de allá.

**08.09 — integración económica.**

### La ventana de aceptación: no se implementa, y es una decisión del usuario

RF-M28-004 paso 7 dice "confirma según **política automática o ventana de aceptación**", y el plan
menciona "expiración de oferta". Esta etapa implementa **la política automática**: al liberarse un
lugar, la cabeza de la cola pasa a `RESERVADA` en el acto y se le avisa.

La ventana de aceptación exigiría un **séptimo estado** (`OFRECIDA`) que **RN-M28-004 no lista**,
más una columna de vencimiento, más un job que expire ofertas no respondidas y vuelva a ofrecer, más
decidir qué pasa si nadie acepta. Es una máquina de estados con reloj, y es una etapa propia.

La política automática **satisface los dos criterios de aceptación que importan**: CA-M28-004-06
—la prioridad se respeta y el cupo no se sobrevende— y CA-M26-007-06 —el último cupo no termina
confirmado para dos personas—. Lo que **no** satisface es un centro que quiera que la vacante se
ofrezca y no se imponga.

> **Decisión pendiente del usuario:** si AKINE necesita la ventana de aceptación, o si la promoción
> automática alcanza. No se inventa la respuesta acá.

**Veredicto: pasa, con el desvío de 07.09 y la ventana de aceptación declarados.**

## 8. El caso que rompe el diseño

Son dos, y son la misma carrera en los dos sentidos.

### 8.1 "Dos personas se llevan la última vacante y nadie se entera"

Por qué es **el** caso: ningún unique puede expresarlo —"quedan vacantes" no es un valor de
columna, es un conteo contra un tope— así que la tentación natural es contar en el servicio y
después insertar. Entre esas dos sentencias hay una ventana, y **la ventana es el bug**: dos
transacciones cuentan 7 de 8, las dos insertan, la clase queda con 9 personas en 8 lugares y
**nada falla**. Defecto silencioso, el peor tipo. Y no se arregla releyendo: bajo `REPEATABLE READ`
la segunda lectura devuelve la misma foto.

**Cómo se resuelve, en una línea: el lugar lo otorga un `UPDATE` condicional, y cero filas
afectadas es "no hay lugar".**

```sql
UPDATE clase_programada SET cupo_ocupado = cupo_ocupado + 1
 WHERE id = ? AND organization_id = ? AND deleted_at IS NULL AND estado = 'PROGRAMADA'
   AND cupo_ocupado < LEAST(capacidad, :capacidadEfectiva)
```

Una sola sentencia: no hay dos, así que no hay ventana. El `UPDATE` toma el lock X de la fila y lee
la versión commiteada más reciente —current read—, así que la segunda transacción evalúa su
predicado contra lo que la primera ya escribió. La perdedora recibe `0` y va a la lista de espera o
se lleva un `409 clase-completa`. **Es el patrón que 07.02, 04.05, 07.03 y 07.04 ya usaron** para
"una resta que no puede pasar de cero".

`capacidad` está en el `WHERE` como columna y no sólo como parámetro para que **ningún llamador
pueda saltearse el techo**, ni siquiera uno con un bug.

### 8.2 "Dos bajas simultáneas promueven dos veces a la misma persona"

O peor, su gemelo: promueven a dos personas para un solo lugar liberado, o liberan dos lugares y no
promueven a nadie.

**Cómo se resuelve: la baja libera el lugar ANTES de leer la cola.** El `UPDATE` que decrementa
`cupo_ocupado` toma el lock X de la fila de la clase y lo **retiene hasta el commit**, así que la
segunda baja se queda esperando ahí y, cuando entra, lee una cola de la que ya salió el promovido
por la primera. Leer la cola antes de liberar sería leer una foto vieja: es exactamente el mismo
error que leer antes de bloquear, que ya se pagó en 05.02 y en 02.04.

Y encima, la promoción transiciona con un `UPDATE` condicional —
`WHERE id = ? AND estado = 'LISTA_ESPERA'`— cuyo cero filas significa "ya la promovió otro": si
alguna vez aparece un camino que promueva sin pasar por la liberación, **sigue sin poder promover
dos veces**. Cuando eso pasa se devuelve el lugar tomado y no se promueve a nadie, que es lo
correcto: el lugar vuelve a estar libre para la próxima inscripción.

### 8.3 El tercero, que es el que no se ve

**Bajar la capacidad de una clase mientras alguien se está inscribiendo.** 08.01 rechaza bajarla
por debajo de la ocupación, pero lo hace leyendo con un `SELECT` plano y escribiendo después: una
inscripción que se mete en el medio deja `cupo_ocupado > capacidad`. Se cierra leyendo la clase
**`FOR UPDATE`** en `reprogramar` y en `cancelar`, con lo cual la inscripción espera y después
evalúa `LEAST` contra la capacidad nueva.

### Lo que esto cuesta, y no se esconde

1. **Contención por clase.** Todas las inscripciones de una misma clase se serializan. Es por
   clase y no por sede —mucho menos que el lock de `agenda_sede`— y es el precio correcto: sin
   serialización no hay cupo.
2. **Orden de locks, que hay que respetar para siempre.**
   `agenda_sede → clase_programada → inscripcion_clase`. **La inscripción nunca toma
   `agenda_sede`**, y no por ahorrar: tomarlo después de la fila de la clase sería una inversión de
   orden y un deadlock en producción que ningún test unitario reproduce.
3. **Nada de esto se ejerció contra MySQL real**, porque Docker no arranca, y es **exactamente** lo
   que un mock no puede contestar: un repositorio falso que devuelve `0` filas no reproduce ni el
   gestor de locks de InnoDB ni la semántica de current read. **No se simula.** Los cuatro
   escenarios —última vacante, promoción doble, capacidad bajada en carrera y el invariante contador
   vs recibos— quedan en `docs/tests-diferidos.md` con etapa destino.

**Veredicto: pasa en diseño, con la verificación concurrente explícitamente diferida y sin
sustituto falso.**

---

## Resultado

**Ocho de ocho pasan.** Cuatro cosas quedan escritas y no resueltas, y ninguna bloquea el diseño:

1. **La reversión de una decisión de 08.01** —materializar la ocupación— con su motivo en la
   pregunta 4.
2. **El desvío de la dependencia 07.09**, heredado de 08.01.
3. **La ventana de aceptación**, que es una **decisión del usuario** y no se inventa.
4. **La verificación concurrente diferida** por Docker, sin sustituto.

Se avanza a `tasks`.
