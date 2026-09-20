# Design challenge — AKINE-08.06

Paso obligatorio de `CLAUDE.md` §3. **Manda sobre `AKINE-08.06-productos.md`.**

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

**Tres tablas nuevas, las tres de `activity`:** `producto_servicio`, `pase_servicio` y
`movimiento_pase`. Ningún otro módulo las lee ni las escribe.

**Y dos `ALTER` sobre tablas ajenas**, que es lo que esta pregunta existe para destapar:

| Tabla | Dueño | Qué le agrega 08.06 | Por qué ahí y no en una tabla propia |
|---|---|---|---|
| `obligacion` | `billing` | `sesion_id` pasa a `NULL`, `origen`, `pase_id`, un unique y un check | Una obligación por compra de pack **es una obligación**. Crear una tabla `deuda_por_venta` paralela sería tener dos cuentas corrientes para la misma persona, y el cobro de 07.02 —que imputa contra `obligacion`— no vería la mitad. Es literalmente la regla "no reinventes `obligacion`". |
| `oferta_servicio_consultorio` | `offering` | `esquema_cobro` con `CHECK`, `momento_devengo`, `devenga_no_show` | RN-M27-006 dice que el esquema económico lo declara el centro **en la Oferta**, y `V24` ya reservó la columna esperando a que alguien fijara el vocabulario. Una tabla `politica_devengo_oferta` sería una fila obligatoria 1:1 con una FK y un join para guardar tres valores que ya tienen casa. |

**La escritura la hace el dueño, no el vecino.** `activity` no toca `obligacion`: se lo pide a
`billing.spi.DevengoDeVentas`, que implementa `billing.infrastructure`. Y `activity` no escribe la
política: la **lee** por `offering.spi`, y quien la edita es `OfertaService`, de `offering`. Que la
migración `V64` cree columnas en tablas de otros módulos no rompe la regla 1 de AGENT.md —la regla
habla de quién lee y escribe desde código, y Flyway es autoridad única del esquema entero por
ADR-0003—, pero **sí** obliga a que el código respete al dueño, y lo respeta.

**La pregunta incómoda: ¿`producto_servicio` no debería ser de `offering`?** Un producto se parece
muchísimo a una Oferta: cuelga de una sede, tiene precio, moneda, vigencia y baja lógica. **No**, y
la diferencia importa:

- Una **Oferta** es *qué presta el centro y cómo* —duración, capacidad, si requiere profesional,
  si genera registro clínico—. Un **Producto** es *qué se vende y a cuánto*, y su unidad no es una
  atención sino **N créditos con un vencimiento**. `offering` no tiene el concepto de crédito y no
  debería ganarlo: `OfertaSnapshot` existe para que el motor de agenda sepa dimensionar un slot, y
  meterle productos obligaría a ese módulo a saber de plata anticipada.
- El producto **sólo existe para ser comprado**, y la compra crea un Pase, que es participación
  —M28/M29, `activity`—. Partir producto y pase en dos módulos partiría en dos la transacción que
  los relaciona.
- AGENT.md §4 lo dice sin ambigüedad: `activity/ # clases, inscripciones, participación, **pases y
  abonos** → M28–M29`.

**La segunda: ¿`movimiento_pase` no es de `billing`, que ya tiene un ledger?** No, y confundirlo
sería romper RN-M29-006. **Un crédito no es plata.** El ledger de `billing` mueve `DECIMAL` con
moneda; éste mueve `INT` de créditos y **no genera movimiento de caja al consumirse**. Son dos
unidades distintas con dos invariantes distintos, y la tabla que las juntara tendría la mitad de
sus columnas siempre nulas.

**Veredicto: pasa, con los dos `ALTER` ajenos declarados y su dueño de escritura nombrado.**

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

**Una arista nueva: `activity -> billing.spi`.** Es la primera vez que `activity` toca el circuito
económico, y `billing.spi` **no existía**: este es el paquete que lo crea.

Las demás ya estaban desde 08.01–08.03: `organization.spi`, `person.spi`, `platform.spi`,
`offering.spi`, `resource.spi`. De `offering.spi` se consume además la política de devengo, que es
un método nuevo en una interfaz existente, **no una arista nueva**.

**Comprobado con clase sonda y control negativo, antes de escribir una línea de dominio.** Es el
precedente que 04.05 dejó caro —dio por verificada de memoria una arista que sí cerraba ciclo— y
que 08.01, 08.02, 08.03, 07.04, 07.05 y 07.06 convirtieron en procedimiento.

**Corrida 1 — sonda positiva.** `billing.spi.SondaDeCiclos0806Spi` más
`activity.application.SondaDeCiclos0806` que la importa, o sea la arista real de la etapa:

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.736 s -- in com.akine.architecture.ModuleArchitectureTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Corrida 2 — control negativo.** Se agregó **a propósito** la arista de vuelta:
`activity.spi.SondaNegativa0806Spi` más `billing.infrastructure.SondaNegativa0806` que la
implementa, o sea `billing -> activity` contra el `activity -> billing.spi` de la corrida 1:

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 9.136 s <<< FAILURE! -- in com.akine.architecture.ModuleArchitectureTest
Cycle detected: Slice activity ->
                Slice activity
  1. Dependencies of Slice activity
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Sin la corrida 2, el 5/5 verde de la corrida 1 no probaría nada: podría significar "no hay ciclo" o
"la sonda no declaró la arista". Las cuatro clases sonda **se borraron** antes de escribir dominio
—`git status` limpio, verificado—.

`sin_ciclos_entre_modulos` usa `SlicesRuleDefinition`, que busca ciclos de **cualquier longitud**.
Importa acá más que en otras etapas, porque la zona es densa: `billing -> encounter.spi`,
`encounter -> clinical.spi`, `clinical -> person.spi` y `billing -> person.spi` ya existen, y
`activity` también apunta a `person.spi`. Un razonamiento de cabeza encuentra los ciclos de dos y
se pierde los de cuatro.

**Y la dirección es la inversa de la de 07.01, a propósito.** Allá es `billing -> encounter` para
que el módulo clínico no sepa que existe facturación; acá es `activity -> billing.spi` porque
vender **es** un acto comercial y porque CA-M29-002-04 exige pase y obligación en una sola
transacción, cosa que un observador post-commit no puede dar. Está argumentado en §5 del diseño.

**Veredicto: pasa, verificado con ArchUnit y con control negativo.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí, y encabezándolos.

- `uk_producto_sede_nombre (organization_id, consultorio_id, nombre, deleted_key)`
- `ix_producto_oferta (organization_id, consultorio_id, oferta_id, active)`
- `uk_pase_idempotency (organization_id, idempotency_key)`
- `ix_pase_persona (organization_id, persona_id, id)`
- `ix_pase_producto (organization_id, producto_id)`
- `uk_movimiento_origen (organization_id, pase_id, origen_tipo, origen_id)`
- `ix_movimiento_pase (organization_id, pase_id, ocurrido_en, id)`

`consultorio_id` va en `producto_servicio` y en `pase_servicio` aunque el segundo sea derivable del
producto, por el mismo motivo que en `clase_programada` e `inscripcion_clase`: derivarlo obliga a
un join para filtrar por sede y habilita la consulta sin join que **ningún test de una etapa
detecta**, porque los tests de una etapa usan un solo tenant.

`movimiento_pase` **no** lleva `consultorio_id`: un movimiento de crédito no es un hecho de sede
—el pase se compra en una sede y se puede consumir en otra de la misma organización— y ponerlo
obligaría a decidir cuál guardar en el consumo, que es una decisión de 08.07. Lleva
`organization_id`, que es lo que el aislamiento exige.

**El unique de idempotencia tiene alcance ORGANIZACION y no sede**, igual que el de clases y el de
turnos: una clave reusada apuntando a otra sede sigue siendo la misma clave.

**Ninguna firma de puerto resuelve por id pelado**, incluidas las de escritura. Un `UPDATE` que
sólo filtra por PK funciona perfecto y es un agujero de aislamiento.

**Cross-tenant → 404**, nunca 403. **Falta de contexto → 403**, nunca 401.

**El riesgo propio de esta etapa, que hay que nombrar:** `GET /personas/{personaId}/pases` no lleva
sede en la ruta. Si resolviera la persona por PK y devolviera sus pases, **sería un enumerador
cross-tenant con datos económicos adentro**. Por eso resuelve la persona por
`PacienteDirectory.find(organizationId, personaId)` —el mismo camino que la inscripción— y consulta
los pases por `(organizationId, personaId)`; una persona de otro tenant contesta **404**, no
"no es tuya".

**Veredicto: pasa.**

## 4. Reglas maestras — ¿el diseño confunde alguna?

Cinco riesgos. Los dos primeros son *la* etapa.

1. **Deuda, Cobro y Caja siguen siendo tres cosas.** La venta produce **una obligación y nada
   más**. No hay endpoint "vender y cobrar", no se escribe una fila de `cobro`, y **no se toca
   caja** —que además no existe en esta rama: 07.03 está en vuelo en otro worktree—. Quien paga usa
   `POST /cobros` de 07.02, que imputa contra la obligación que esta venta creó; el movimiento de
   caja lo decide el medio de pago, en 07.03. **La tentación era el atajo comercial** —"el
   mostrador vende y cobra en un click"— y es exactamente el colapso del UML de 2019.
2. **Un crédito no es plata (RN-M29-006).** `movimiento_pase` mueve `INT`, no `DECIMAL`, y su
   consumo **no genera movimiento de caja**. Están en tablas distintas, en módulos distintos, con
   unidades distintas. La única conexión entre las dos es la venta, que crea una obligación de una
   sola vez.
3. **Producto ≠ Pase comprado.** Dos tablas, y el pase lleva snapshot congelado. Editar el producto
   no altera una compra: es el mismo congelamiento que `Obligacion.snapshotPrecio`. La
   alternativa —leer el precio del producto al mostrar el pase— reescribe la historia cada vez que
   alguien actualiza una lista de precios.
4. **Una Persona no es un Paciente.** El pase apunta a `persona_id` y no exige perfil de paciente
   vigente: comprar un pack de Pilates no es entrar al circuito clínico. Ni lo crea: eso es
   `PerfilPacienteService` y sólo él (RF-M07-010).
5. **Los importes son `DECIMAL(12,2)` y `BigDecimal`**, en la tabla, en el dominio y en la API. Ni
   un `double` en ningún borde. Los créditos son `INT`, que es lo correcto: medio crédito no
   existe.

**Y el riesgo propio del diseño, que hay que decir en voz alta:** crear `movimiento_pase` en esta
etapa **parece** adelantar 08.07, que es el ledger. La defensa está en §2.3 del diseño y se resume
así: **RN-M29-003 dice "todo cambio de créditos", y crear un pase con 8 créditos es un cambio de
créditos.** No escribir la `COMPRA` dejaría el invariante `saldo == suma(ledger)` roto desde la
primera fila y obligaría a 08.07 a un backfill. Lo que **sí** queda entero para 08.07 está en la
pregunta 7, nombrado uno por uno.

**Veredicto: pasa, con el borde de 08.07 declarado y acotado a un solo tipo de movimiento.**

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

**Ninguno.**

- **`producto_servicio` lleva el cuarteto completo** —`active`, `deleted_at`,
  `deactivation_reason`, `deleted_key` con el centinela `'1970-01-01'`— igual que `servicio` y
  `oferta`. El motivo del centinela es el de siempre: varios `NULL` no colisionan en un unique de
  MySQL, así que sin él dos productos activos homónimos de la misma sede no chocarían.
- **`pase_servicio` NO la lleva**, y es una desviación consciente —la misma que
  `asistencia_actividad` en 08.03—. Un pase no se da de baja: **se anula**, y eso es un estado del
  ciclo con motivo, actor y rastro. Si tuviera baja lógica, `uk_pase_idempotency` tendría que
  incluir `deleted_key` para no estorbar al histórico, y entonces **dos ventas vivas con la misma
  clave de idempotencia podrían coexistir** apenas alguien anulara una: la idempotencia dejaría de
  serlo justo en el caso que existe para cubrir.
- **`movimiento_pase` no tiene `update` ni `delete` en su puerto.** Es la única garantía real de
  inmutabilidad; un `deleted_at` sobre un ledger es una contradicción.

### La pregunta que esta sección tiene que contestar: ¿qué pasa con lo ya vendido?

**02.06 fijó que la baja de un Servicio no cascadea** —las ofertas que ya lo prestaban siguen
operando— y **acá vale lo mismo, con un argumento más fuerte: un pase vendido es plata que alguien
pagó.**

Dar de baja un producto significa exactamente **"no se vende más"**:

| | qué pasa |
|---|---|
| Ventas nuevas | **409 `producto-no-vendible`**. Es también el caso borde "producto inactivo durante el checkout": el producto se revalida dentro de la transacción de la compra, no al armar la pantalla. |
| Pases ya vendidos | **Intactos.** Siguen `ACTIVO`, con su saldo, su vigencia y su snapshot. 08.07 los consume sin enterarse. |
| Obligaciones ya devengadas | **Intactas.** Una deuda no se borra porque se deje de vender el producto que la originó; eso sería reescribir una cuenta corriente. |
| Borrado físico | **Imposible desde el motor**: `pase_servicio.producto_id` es FK `RESTRICT`. |

Lo contrario —cascadear— significaría que dar de baja un pack le apaga los créditos a quien los
pagó. Es un robo con forma de operación administrativa.

**Veredicto: pasa, con la desviación de `pase_servicio` y su motivo escritos.**

## 6. Contrato — ¿el cambio de API es aditivo? ¿Requiere versión mayor?

**Minor: `0.43.0` → `0.46.0`.** `0.44.0` y `0.45.0` quedan reservadas para 07.07 y 08.04, que están
en vuelo en otros worktrees.

Ocho operaciones nuevas. Ninguna ruta existente cambia de método ni de cuerpo. Ninguna responde
**204**, así que la trampa del `Accept: application/problem+json` no aplica.

**Pero hay un cambio que NO es puramente aditivo, y esconderlo sería el error:**

1. **`ObligacionResponse.sesionId` pasa a ser anulable**, y gana `origen` y `paseId`. Un campo
   obligatorio que pasa a opcional **es un cambio de forma**: un cliente generado en `0.43.0` que
   deserialice un `long` primitivo se rompe al recibir `null`. Hoy no hay ningún cliente
   —el frontend de F5 en adelante no existe— y ninguna obligación de venta existe todavía, así que
   la ventana de compatibilidad es la que la regla de coordinación pide y **está vacía**. Queda
   declarado, no disimulado.
2. **`esquema_cobro` se cierra a un enum.** Era `VARCHAR(32)` libre y ahora acepta cuatro valores.
   Es un **estrechamiento**, no un ensanchamiento: un cliente que mandaba texto arbitrario ahora
   come un 400. Se hace igual porque `V24` declaró esa columna *"DATO DECLARADO, NO RESUELTO… el
   vocabulario lo fija M16/M18"* y **esta etapa es el módulo que lo fija**; porque ninguna fila
   existe —la migración normaliza a `NULL` cualquier valor fuera de la lista antes de agregar el
   `CHECK`, y en esta rama esa sentencia no va a tocar nada—; y porque dejarlo libre haría que la
   política de devengo dependiera de un campo de texto que cualquiera puede escribir mal.
3. `CreateOfertaRequest`, `UpdateOfertaRequest` y `OfertaResponse` ganan `momentoDevengo` y
   `devengaNoShow`. Eso sí es puramente aditivo: campos opcionales nuevos.

**Cuatro tipos de problema nuevos** —`producto-no-vendible`, `producto-nombre-en-uso`,
`pase-not-found`, `producto-not-found`— y el resto reusados: `not-found`, `conflict`,
`validation-error`, `idempotency-key-conflict`, `forbidden`.

El YAML **no se edita a mano**: sólo `info.version`. Los `paths` quedan en drift hasta que alguien
regenere con Docker, que es la deuda de todas las etapas desde 04.02.

**Veredicto: pasa, con el cambio no aditivo declarado en voz alta y su ventana de compatibilidad
razonada.**

## 7. Ruta crítica — ¿tiene sus cimientos? ¿Qué queda para 08.07 y 08.08?

**Cimientos, todos presentes en esta rama:** 08.01 (módulo `activity`, clase), 08.02 (inscripción,
cupo atómico), 08.03 (asistencia), 02.06 (Oferta y su precio), 03.01 (Persona), **07.01
(obligación)** y **07.02 (cobro)**.

**Un cimiento que el plan lista y que NO está: AKINE-07.03 (Caja).** El plan declara la dependencia
y RF-M20-008 pide registrar en caja los cobros de estos productos. **07.03 está en vuelo en otro
worktree** —su `V54` no existe en esta rama—. Se avanza igual porque la etapa **no necesita caja
para ser correcta**: produce una obligación, y la cadena obligación → cobro → caja ya está
diseñada y es de 07.02/07.03. Lo que **no** se hace es fingirla: RF-M20-008 queda sin implementar y
**declarado**, no simulado. Media funcionalidad miente más que la falta — es textual de 07.02.

**Desvío heredado, igual que 08.01, 08.02 y 08.03:** el plan pide AKINE-07.09 —gate y release del
MVP— antes de F9, y no está cerrada. La dependencia es de **release, no técnica**.

### Qué queda para cada etapa, nombrándolas

**08.07 — ledger y ciclo de créditos.** Recibe la tabla `movimiento_pase` creada, con el `CHECK` de
`tipo` **ya conteniendo los cinco valores de RN-M29-004**, y un pase cuyo saldo ya está explicado
por una fila. Lo que tiene que escribir, entero:

- Los **cuatro tipos restantes**: `CONSUMO`, `DEVOLUCION`, `AJUSTE`, `VENCIMIENTO`.
- El **`UPDATE` condicional** `SET creditos_disponibles = creditos_disponibles - 1 WHERE
  creditos_disponibles >= 1`, que es lo que hace cumplir RN-M29-008. Esta etapa lo **no** escribe:
  `creditos_disponibles` se mapea `updatable = false` justamente para que no haya otro camino.
- La **elegibilidad**: qué pase se consume cuando la persona tiene varios (RN-M29-005), y cómo se
  decide que uno es compatible con la Oferta de la clase.
- La **política de devolución** al cancelar (RN-M29-009) y el **job de vencimiento** (RF-M29-006).
- El **invariante `creditos_disponibles == creditos_iniciales + suma(ledger)`**, que es el gemelo
  exacto de la deuda que 04.05 dejó con `cantidad_consumida` y que 08.02 dejó con `cupo_ocupado`.
  **Si divergen, hoy no lo detecta nada.**
- Y la decisión que 08.03 le dejó y que esta etapa **no** le saca: **si el no-show consume
  crédito.** El `devenga_no_show` de la política es su gemelo del lado de la deuda; el del lado del
  crédito es de 08.07.

**08.08 — abonos, renovaciones y vencimientos.** Recibe `producto_servicio.tipo` con `'ABONO'` ya
en el `CHECK` y `obligacion.origen` con `'VENTA_ABONO'` ya en el suyo: **no tiene que hacer un
`DROP CHECK`**, que es la operación que `V57` demostró que borra valores ajenos en silencio. Lo que
tiene que escribir: el período de cobertura, la obligación recurrente, la renovación y el aviso de
vencimiento. Y `esquema_cobro = POR_ABONO` con `momento_devengo = VENTA` ya está en el vocabulario,
así que RN-M29-007 —el abono no genera deuda por asistencia— es configuración y no código nuevo.

**08.09 — integración económica y auditoría.** RF-M19-008 (cobrar la compra) **ya funciona** con
`POST /cobros` de 07.02 contra la obligación de venta; lo que le queda es RF-M20-008, la caja, que
depende de 07.03.

**La etapa que encienda `POR_CLASE`** —08.07 u 08.09, según dónde caiga— no agrega esquema: agrega
un observador que lee `PoliticaDeDevengoDeOferta` y llama a `DevengoDeVentas` con un origen
`CLASE`. Ver §4.3 del diseño.

### La decisión que sigue siendo del usuario, y que ahora es una configuración

> **¿El cargo de una clase se devenga con la asistencia, con la inscripción, o con la venta de un
> pack? ¿Y el no-show cobra?**

**Esta etapa no la responde: la hace respondible.** Las tres respuestas son tres valores de
`(esquema_cobro, momento_devengo)` y el no-show es un `boolean`. **Ninguna de las tres exige una
tabla, una FK ni una firma que las otras no necesiten**, y si el diseño obligara a una de ellas
estaría mal. Las consecuencias de cada opción están en el reporte de la etapa.

**Veredicto: pasa, con el cimiento faltante (07.03) declarado, el desvío heredado repetido y la
decisión elevada convertida en configuración.**

## 8. El caso que rompe el diseño

Son tres, en orden de cuánto duelen.

### 8.1 "El mostrador aprieta Vender dos veces y la persona queda debiendo dos packs"

Es **el** caso, porque es el que CA-M29-002-06 nombra literalmente —*"reintentar la compra no
duplica pase, créditos ni obligación"*— y porque tiene tres formas de salir mal a la vez: dos
pases, dos filas de crédito y **dos deudas**. La tercera es la peor: un pase de más se ve; una
obligación de más la descubre el paciente tres meses después mirando su cuenta corriente.

**Cómo se resuelve, en tres capas y no en una:**

1. **`Idempotency-Key` evaluada ANTES de todo.** Antes de validar el producto, antes de crear el
   pase y sobre todo **antes de devengar**. Es la regla del repositorio —la idempotencia se evalúa
   antes de pedir número— y acá el número es la obligación. Un reintento con la misma huella
   devuelve la venta existente **sin escribir nada**; con huella distinta es **409
   `idempotency-key-conflict`**, no una segunda venta silenciosa.
2. **`uk_pase_idempotency (organization_id, idempotency_key)`**, que cubre la carrera entre dos
   requests simultáneos que la consulta del punto 1 no puede cubrir: los dos leen vacío, los dos
   insertan, **el motor mata al segundo**. Hacen falta las dos —la consulta da el 409 explicable,
   el unique da la garantía— exactamente como 08.02 con la inscripción duplicada.
3. **`uk_obligacion_venta (pase_id, responsable, deleted_key)`**, que es el cinturón del lado de
   `billing`. Aunque alguien escribiera mañana un segundo camino de devengo por venta sin pasar por
   acá, **no puede crear dos deudas por el mismo pase**. Es la misma forma que
   `uk_obligacion_prestacion` le da al cierre de sesión.

Y todo ocurre en **una transacción**: pase, movimiento y obligación commitean juntos o no commitea
ninguno. Es lo que CA-M29-002-04 pide, y es el motivo técnico de que la arista sea
`activity -> billing.spi` y no un evento post-commit.

### 8.2 "Le dan de baja al producto mientras alguien está pagando"

El caso borde que el plan nombra como *"producto inactivo durante checkout"*, y su gemelo
*"precio futuro"*: la pantalla cotizó $40.000 y entre eso y el Vender alguien subió el precio o
apagó el producto.

**Cómo se resuelve: el producto se revalida DENTRO de la transacción de la compra**, no al armar la
pantalla, y **el importe sale de la fila leída ahí adentro**, no del cuerpo del request. Un
producto inactivo o fuera de su ventana comercial es **409 `producto-no-vendible`** con el motivo
adentro, para que el mostrador pueda explicarlo.

**Lo que el diseño NO hace, y es la parte que importa: no acepta un precio del cliente.** Un
`precio` en el request sería un campo que el frontend puede mentir, y el backend es autoridad
—AGENT.md §1—. El cliente manda `productoId` y `personaId`; el precio lo resuelve el servidor y lo
congela en el snapshot. La consecuencia asumida: si el precio cambió entre la cotización y el
Vender, **la venta sale al precio nuevo y la respuesta lo dice**. Es preferible a vender a un
precio que ya no existe porque una pantalla lo tenía cacheado.

> El caso simétrico —**dar de baja el producto después** de vendido— está en la pregunta 5: no
> cascadea, y cascadear sería apagarle los créditos a quien los pagó.

### 8.3 "El pase se creó y la obligación no, o al revés"

El modo de falla que ningún test unitario ve y que sólo aparece en producción: créditos regalados
—un pase sin deuda— o una deuda sin pase, que es un cargo injustificable. 07.01 ya declaró esta
tensión desde el otro lado: *"un observador que falla hace fallar el cierre clínico"*.

**Cómo se resuelve: una sola transacción, y la contrapartida se asume por escrito.** Si el devengo
falla, **la venta entera falla** y no queda ni pase ni movimiento. Es el **inverso** de la decisión
de 07.01 con el precio faltante —allá se loguea y se sigue, para no dejar al profesional sin poder
cerrar su atención por un dato administrativo— y la asimetría es correcta: allá el acto principal
es **clínico** y la deuda es un efecto; acá el acto principal **es** la venta, y una venta sin
deuda no es una venta a medias, **es un regalo**.

Por eso el devengo de esta etapa **no tiene la ruta de escape** que tiene el de sesión: un producto
sin precio no existe —`precio` es `NOT NULL` con `CHECK > 0` en `producto_servicio`, a diferencia
del `precio_base` anulable de la Oferta—, así que el caso "vendí algo sin saber cuánto vale" no es
posible desde el esquema y no hace falta decidir qué hacer con él.

### Lo que esto cuesta, y no se esconde

1. **La venta es más frágil que el cierre de sesión**, a propósito: cualquier fallo de `billing`
   —la base caída, un constraint violado— hace fallar la venta entera. Preferimos una venta que
   falla y se reintenta a un pase con créditos que nadie debe.
2. **`movimiento_pase` nace con un solo tipo emitido.** Hasta 08.07, el ledger es un registro de
   aperturas. Un lector futuro podría creer que la tabla está incompleta: está anotado en el
   javadoc del enum y en §2.3 del diseño.
3. **Nada de esto se ejerció contra MySQL real**, porque Docker no arranca. La carrera de dos
   compras simultáneas con la misma clave, el `ck_obligacion_origen`, y **que `V64` siquiera
   ejecute** —ninguna migración de F5 en adelante se aplicó jamás contra un motor— son exactamente
   lo que un mock no puede contestar. **No se simulan**: quedan en `docs/tests-diferidos.md` desde
   el escenario **70**, con su verificación por mutación escrita.

**Veredicto: pasa en diseño, con la verificación concurrente y la de migración explícitamente
diferidas y sin sustituto falso.**

---

## Resultado

**Ocho de ocho pasan.** Cinco cosas quedan escritas y no resueltas, y una sola es del usuario:

1. **La política de devengo de la clase y el no-show** — **decisión del usuario**, ahora reducida a
   dos valores de configuración. Es la pregunta que 08.03 delegó y que este diseño hace respondible
   sin rediseñar nada.
2. **RF-M20-008 (caja) no se implementa**, porque 07.03 no está en esta rama. Declarado, no
   simulado.
3. **El cambio no puramente aditivo del contrato** —`ObligacionResponse.sesionId` anulable y
   `esquema_cobro` estrechado a enum— con su ventana de compatibilidad razonada en la pregunta 6.
4. **La desviación de la forma de baja lógica** —`pase_servicio` sin `deleted_at`— con su motivo en
   la pregunta 5.
5. **La verificación contra base real diferida** por Docker, sin sustituto.

Se avanza a `tasks`.
