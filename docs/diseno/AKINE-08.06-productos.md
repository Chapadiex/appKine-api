# AKINE-08.06 — Productos, packs y venta inicial (M29)

> Diseño de la etapa. **El design challenge —`AKINE-08.06-challenge.md`— manda sobre este
> documento.**

Trazabilidad: RF-M29-001, RF-M29-002, RF-M18-009, RN-M29-001..004, RN-M29-008, RN-M27-006,
RF-M18-008 (la **política**, no el disparo), CA-M29-001-06, CA-M29-002-06.

---

## 1. Qué vende esta etapa, y qué no

| | |
|---|---|
| **Entra** | El **producto** vendible por Oferta (RF-M29-001), la **venta idempotente** de un Pase con saldo inicial (RF-M29-002), la **obligación** que esa venta devenga (RF-M18-009), el **movimiento `COMPRA`** que explica ese saldo (RN-M29-003), y el **vocabulario resuelto de `esquema_cobro` con su política de devengo** (RN-M27-006, RF-M18-008). |
| **No entra** | Consumo, devolución, ajuste y vencimiento de créditos → **08.07**. Abonos, renovaciones y períodos → **08.08**. El **cobro** de la venta → ya existe, es `POST /cobros` de 07.02. La **caja** → 07.03, que **no está en esta rama**. |

**Vender un pack no es cobrarlo, y cobrarlo no es que entre plata al cajón.** La venta produce
exactamente **una obligación**. Quien la paga usa el endpoint de cobro que 07.02 ya publicó, y ese
cobro genera movimiento de caja cuando 07.03 esté integrado. Esta etapa **no escribe una sola fila
de `cobro` ni de caja**, y no publica un atajo "vender y cobrar en un click": ese atajo es la regla
maestra M18/M19/M20 colapsada en una operación, y es el error del UML de 2019.

## 2. Las tres tablas nuevas, y por qué son tres

Todas propiedad de **`activity`** — AGENT.md §4 le asigna M28–M29 textualmente: *"clases,
inscripciones, participación, **pases y abonos**"*.

### 2.1 `producto_servicio` — la definición comercial

Es el **catálogo**: "Pack Pilates 8 clases, $40.000, válido 60 días, sobre la oferta Pilates de la
sede Centro". CA-M29-001-06 pide exactamente eso: *"queda disponible como producto **sin crear
todavía saldo a una persona**"*.

Cuelga de una **Oferta**, no de un Servicio: el precio y la vigencia son de la sede, y el Servicio
es catálogo global sin `organization_id` (V24). Un producto sobre un Servicio sería un precio sin
dueño.

`tipo` nace con `CHECK IN ('PACK', 'ABONO')` aunque **esta etapa sólo emite `PACK`**. El motivo es
la lección de `V57`: una migración posterior que agregue un valor a un `CHECK` existente tiene que
hacer `DROP CHECK` + `ADD CONSTRAINT`, que **reescribe la lista entera**, y basta con que alguien
reconstruya la lista desde un estado viejo para borrar en silencio un valor que otra migración
acababa de agregar. Declarar hoy el vocabulario completo que 08.08 va a necesitar cuesta una línea
y le ahorra a 08.08 una operación peligrosa.

### 2.2 `pase_servicio` — lo que una persona compró

Producto ≠ Pase comprado, y es la regla que el plan de la etapa nombra primero. El producto se
edita; el pase **no cambia cuando el producto cambia**, porque lleva su propio snapshot comercial
—`snapshot_nombre`, `snapshot_precio`, `snapshot_moneda`, `snapshot_creditos`,
`snapshot_oferta_id`—. Subirle el precio al pack mañana no puede cambiar lo que alguien pagó hoy:
es exactamente el congelamiento que `Obligacion` hace con su `snapshot_precio` (07.01).

`creditos_disponibles` se mapea **`insertable = true, updatable = false`**. Nace con la compra
—esta etapa lo escribe una vez— y a partir de ahí sólo se mueve por `UPDATE` condicional, que es lo
que 08.07 va a escribir. Si JPA pudiera actualizarlo, cualquier `save()` del pase lo pisaría con un
valor leído antes y **el saldo negativo volvería por la puerta de atrás**. Es la misma decisión que
`cupo_ocupado` en 08.02, con la única diferencia de que acá el valor inicial sí lo pone el `INSERT`.

`estado` nace con los cuatro valores de su ciclo completo —`ACTIVO`, `AGOTADO`, `VENCIDO`,
`ANULADO`— por el mismo motivo que `tipo`: 08.07 los va a usar y no debería tener que tocar el
`CHECK`.

**No lleva `deleted_at` ni `deleted_key`**, misma desviación consciente que `asistencia_actividad`
en 08.03 y por la misma razón: un pase no se da de baja, **se anula**, y eso es un estado con
motivo y rastro. Si tuviera baja lógica, el unique de idempotencia tendría que incluir
`deleted_key` y dos ventas vivas de la misma clave podrían coexistir apenas alguien diera una de
baja.

### 2.3 `movimiento_pase` — el ledger, abierto acá y llenado por 08.07

**Esta es la única decisión de esta etapa que se acerca al borde de 08.07, y se toma a propósito.**

RN-M29-003 dice que **todo** cambio de créditos genera un `MovimientoPase` inmutable, y crear un
pase con 8 créditos **es** un cambio de créditos. RF-M29-002 paso 7 lo pide con esas palabras:
*"Genera MovimientoPase COMPRA y obligación de pago"*. No escribirlo dejaría el saldo sin explicar
desde la fila uno y obligaría a 08.07 a **backfillear una `COMPRA` por cada pase existente** antes
de poder afirmar su invariante.

Así que la tabla se crea acá, con el `CHECK` de `tipo` conteniendo **los cinco valores de
RN-M29-004** —`COMPRA`, `CONSUMO`, `AJUSTE`, `DEVOLUCION`, `VENCIMIENTO`—, y **esta etapa emite
exclusivamente `COMPRA`**. Lo que queda para 08.07 está en §7 del challenge, nombrado.

El puerto **no tiene `update` ni `delete`**, que es la única garantía real de inmutabilidad —misma
forma que `ClaseEventoRepositoryPort` y `AsistenciaEventoRepositoryPort`—.

`uk_movimiento_origen (organization_id, pase_id, origen_tipo, origen_id)` es la idempotencia del
ledger. Para la `COMPRA` el origen es la venta misma (`origen_tipo = 'VENTA'`,
`origen_id = pase_id`): exactamente un movimiento de compra por pase, garantizado por el motor.
Cuando 08.07 consuma por asistencia, `('ASISTENCIA', asistenciaId)` le da gratis que un reintento
no descuente dos veces.

## 3. Lo que se le agrega a `obligacion`, y por qué no alcanzaba

**`obligacion.sesion_id` es `NOT NULL` con FK a `sesion`.** Una obligación por compra de pack
**no tiene sesión**, y ése es el impedimento real que RF-M18-009 encuentra en el esquema de 07.01.

La expansión (ADR-0007, paso *expandir*; ninguna fila existe todavía en esta rama):

```sql
ALTER TABLE obligacion MODIFY sesion_id BIGINT NULL;
ALTER TABLE obligacion ADD origen VARCHAR(16) NOT NULL DEFAULT 'SESION';
ALTER TABLE obligacion ADD pase_id BIGINT NULL;
ALTER TABLE obligacion ADD CONSTRAINT uk_obligacion_venta UNIQUE (pase_id, responsable, deleted_key);
ALTER TABLE obligacion ADD CONSTRAINT ck_obligacion_origen CHECK (...exactamente uno...);
```

**Ningún `CHECK` existente se toca.** No hay un solo `DROP CHECK` en `V64`: los cuatro constraints
de `V36` quedan exactamente como estaban y los dos nuevos se suman. Es la precaución directa que la
lección de `V57` impone.

**Dos uniques y no uno genérico.** `uk_obligacion_prestacion (sesion_id, responsable, deleted_key)`
sigue protegiendo el devengo por sesión —y sigue funcionando, porque en MySQL varios `NULL` no
colisionan, así que las obligaciones de venta no se estorban entre sí ahí— y
`uk_obligacion_venta (pase_id, responsable, deleted_key)` protege el devengo por venta. Un unique
genérico sobre `(origen, origen_id)` habría perdido las dos FK reales.

`origen` lleva `CHECK IN ('SESION', 'VENTA_PASE', 'VENTA_ABONO')`. `VENTA_ABONO` se declara hoy y
**no se emite**: misma precaución de §2.1, para que 08.08 sume una columna y no reescriba un check.

## 4. La política de devengo — la pregunta que 08.03 delegó

> **¿El cargo de una clase se devenga con la asistencia, con la inscripción, o con la venta de un
> pack? ¿Y el no-show cobra?**

`V24` dejó `esquema_cobro` como `VARCHAR(32)` sin lista cerrada, declarándolo *"DATO DECLARADO, NO
RESUELTO… el vocabulario lo fija M16/M18"*. **M18 existe desde 07.01 y M29 es esta etapa: el
vocabulario se fija acá.**

### 4.1 El modelo: tres columnas en la Oferta, no una tabla

```
esquema_cobro     POR_SESION | POR_CLASE | POR_PACK | POR_ABONO     (NULL = sin declarar)
momento_devengo   ASISTENCIA | INSCRIPCION | VENTA                  (NULL sii esquema NULL)
devenga_no_show   0 | 1
```

Van **en `oferta_servicio_consultorio`** y no en una tabla aparte porque la relación es 1:1
estricta con la oferta y RN-M27-006 dice literalmente que el esquema lo declara el centro **en la
Oferta**. Una tabla `politica_devengo_oferta` sería una fila obligatoria por oferta con una FK y un
join, para guardar tres valores que ya tienen dónde vivir.

La coherencia entre las tres la hace cumplir `ck_oferta_politica_devengo`:

| `esquema_cobro` | `momento_devengo` admitido | qué significa |
|---|---|---|
| `POR_SESION` | `ASISTENCIA` | lo que 07.01 ya hace: la sesión cerrada con asistencia devenga |
| `POR_CLASE` | `ASISTENCIA` **o** `INSCRIPCION` | **la decisión del usuario** |
| `POR_PACK` | `VENTA` | la deuda nace al comprar el pack; la clase **consume crédito y no devenga** |
| `POR_ABONO` | `VENTA` | RN-M29-007: el abono cubre un período y no genera deuda por asistencia |

### 4.2 Por qué esto hace que la respuesta sea configuración y no rediseño

Las tres respuestas posibles **no cambian una tabla, una FK ni una firma**:

- *"con la asistencia"* → `POR_CLASE` + `ASISTENCIA`. El disparador es
  `AsistenciaService.registrar`, que ya existe y ya sabe si la persona estuvo.
- *"con la inscripción"* → `POR_CLASE` + `INSCRIPCION`. El disparador es
  `InscripcionService.inscribir`, que ya existe y ya es idempotente por `Idempotency-Key`.
- *"con la venta del pack"* → `POR_PACK` + `VENTA`. **Ya está implementado: es esta etapa.**

Y el no-show es un `boolean` que sólo la rama `ASISTENCIA` lee. Ninguna de las tres exige una tabla
que la otra no necesite, porque en los tres casos el resultado es **una obligación**, que ya sabe
nacer de una sesión (07.01) y ahora también de una venta (esta etapa). Lo único que cambia es
**quién llama y con qué origen**.

### 4.3 Lo que esta etapa NO hace con la política

**No cablea el disparo en `POR_CLASE`.** La lee, la valida, la persiste y la expone por
`offering.spi.PoliticaDeDevengoDeOferta`, y ahí se detiene. Conectar `AsistenciaService` o
`InscripcionService` al devengo implica elegir una de las dos ramas —o escribir las dos y dejar una
muerta—, y **esa elección es del usuario**. Encender la rama equivocada por defecto es exactamente
lo que 08.03 se negó a hacer y tenía razón.

La etapa que la encienda **no agrega esquema**: agrega un observador que lee la política y llama al
mismo `DevengoDeVentas` extendido con un origen `CLASE`. Está nombrado en §7 del challenge.

## 5. La costura con `billing`

```
activity.application.VentaDePaseService  ──>  billing.spi.DevengoDeVentas
                                                     ▲
                                        billing.infrastructure.DevengoDeVentasAdapter
```

**La dirección es `activity -> billing.spi`, y es la inversa de la que 07.01 usó con `encounter`.
La asimetría es deliberada y hay que decirla en voz alta.**

En 07.01 la dirección es `billing -> encounter` porque **el módulo clínico no puede saber que
existe facturación**: cerrar una atención es un acto clínico y que dependa de la caja es el
acoplamiento que el UML de 2019 tenía. Acá es al revés porque **vender un pack es un acto
comercial**: el módulo que vende *tiene* que saber que genera deuda — es la mitad de lo que
significa vender.

Y hay un motivo técnico encima del conceptual: CA-M29-002-04 exige que una validación fallida **no
deje datos parciales**, o sea pase y obligación en la misma transacción. La inversión
—que `billing` observe un evento "venta ocurrida"— sólo funciona post-commit, y un post-commit que
falla deja un pase con créditos y **sin deuda**: créditos regalados que ningún arqueo encuentra.
La única forma de observar dentro de la transacción es que el observador se llame sincrónicamente,
que es lo que esta arista hace sin disfrazarlo.

**`billing` no gana ninguna dependencia hacia `activity` en código.** La FK
`obligacion.pase_id -> pase_servicio` sí va en esa dirección en el esquema, y es el mismo criterio
que 08.03 aplicó con `asistencia_actividad.persona_id`: *la FK la hace cumplir el motor, no un
repositorio*. Ninguna clase de `billing` importa nada de `activity`.

## 6. El flujo de la compra (RF-M29-002)

`READ_COMMITTED`, porque serializa —el mismo motivo de siempre: con `REPEATABLE READ` la foto se
fija en la primera lectura consistente—.

1. Contexto activo (falta → **403**, nunca 401) y sede del tenant (otra → **404**, nunca 403).
2. Permiso `pase:manage`.
3. **Idempotencia ANTES de todo lo demás.** `findByIdempotencyKey` sobre el alcance organización;
   misma clave con huella distinta → **409 `idempotency-key-conflict`**; misma huella → se devuelve
   la venta existente **sin escribir**. Es regla del repositorio y acá tiene consecuencia directa:
   un reintento que pasara primero por el devengo crearía una segunda obligación.
4. Producto **activo y dentro de su ventana comercial** (inactivo o fuera → **409
   `producto-no-vendible`**). Es el caso borde *"producto inactivo durante el checkout"*.
5. Persona del padrón del tenant (ajena → **404**; ficha de baja → **409**). **Persona, no
   paciente**: comprar un pack no es entrar al circuito clínico.
6. Se crea el `PaseServicio` con el snapshot congelado, `creditos_disponibles = creditos_iniciales`
   y vigencia `[hoy, hoy + vigencia_dias)`.
7. `saveAndFlush` — **antes del flush el `@Version` devuelve el valor viejo** y el cliente comería
   un 409 en la operación siguiente.
8. `movimientos.registrar(MovimientoPase.compra(...))`.
9. `devengoDeVentas.devengarPorVentaDePase(...)` → la obligación, **en la misma transacción**.
10. Auditoría **dentro** de la transacción.

## 7. API — ocho operaciones, todas aditivas

| Método | Ruta | Permiso |
|---|---|---|
| `POST` | `/api/v1/consultorios/{consultorioId}/productos` | `producto:manage` |
| `GET` | `/api/v1/consultorios/{consultorioId}/productos` | `producto:read` |
| `GET` | `/api/v1/consultorios/{consultorioId}/productos/{productoId}` | `producto:read` |
| `PUT` | `/api/v1/consultorios/{consultorioId}/productos/{productoId}` | `producto:manage` |
| `POST` | `/api/v1/consultorios/{consultorioId}/productos/{productoId}/baja` | `producto:manage` |
| `POST` | `/api/v1/consultorios/{consultorioId}/pases` | `pase:manage` |
| `GET` | `/api/v1/consultorios/{consultorioId}/pases/{paseId}` | `pase:read` |
| `GET` | `/api/v1/personas/{personaId}/pases` | `pase:read` |

Ninguna responde **204**, así que la trampa del `Accept: application/problem+json` no aplica; el
`produces` de clase declara igual los dos tipos.

Contrato **`0.43.0` → `0.46.0`** (0.44 y 0.45 quedan para 07.07 y 08.04, en vuelo). Ver la
pregunta 6 del challenge para el único cambio que **no** es puramente aditivo.

## 8. Permisos — cuatro códigos, y la separación que el plan pide

El plan dice *"Administración de producto y operador económico separados"*. Eso son dos ejes, y
cada uno se parte en lectura y escritura como `clase:*` e `inscripcion:*`:

| Código | ORG_ADMIN | CONSULTORIO_ADMIN | ADMINISTRATIVO | PROFESIONAL | PLATFORM_ADMIN |
|---|---|---|---|---|---|
| `producto:read` | ORGANIZACION | CONSULTORIO | CONSULTORIO | CONSULTORIO | SOPORTE |
| `producto:manage` | ORGANIZACION | CONSULTORIO | — | — | — |
| `pase:read` | ORGANIZACION | CONSULTORIO | CONSULTORIO | — | SOPORTE |
| `pase:manage` | ORGANIZACION | CONSULTORIO | CONSULTORIO | — | — |

- **`producto:manage` no lo tiene el ADMINISTRATIVO**: fijar qué se vende y a cuánto es una
  decisión comercial del centro, no del mostrador. Mismo criterio con el que la matriz §13 le niega
  `convenio:manage`.
- **`pase:manage` sí lo tiene**, con el mismo reparto que `cobro:register`: vender un pack es
  exactamente el trabajo del mostrador.
- **`pase:read` no lo tiene el PROFESIONAL.** El saldo y el precio que pagó una persona son dato
  económico, y la matriz §2 le dice "No por defecto" al profesional en las filas económicas.
- **`PLATFORM_ADMIN` mira y no opera**: `producto:read` y `pase:read` con alcance SOPORTE, ninguno
  de los dos `manage`. Es la fila §32, que le dice "No" a todo lo operativo.

## 9. Baja lógica

**El producto sí la tiene** —`active` / `deleted_at` / `deactivation_reason` / `deleted_key` con el
centinela `'1970-01-01'`—, igual que `servicio` y `oferta`.

**Y no cascadea.** 02.06 fijó que dar de baja un Servicio no apaga las ofertas que ya lo prestaban,
y acá vale igual y por una razón más fuerte: **un pase ya vendido es plata que alguien pagó**. Dar
de baja el producto significa exactamente *"no se vende más"*; los pases vividos siguen activos,
conservan su saldo y su vigencia, y 08.07 los sigue consumiendo. La FK
`pase_servicio.producto_id` es `RESTRICT`, así que el borrado físico de un producto vendido es
imposible desde el motor — que es lo que RF-M27-002 pide para el Servicio y vale igual acá.

El **pase** no la tiene, por §2.2.

## 10. Lo que NO se verificó

Docker no arranca. Cero tests de integración, cobertura sin medir, contrato en drift —ahora también
el de `0.46.0`—, y **`V64` nunca se aplicó contra un motor**: ni el `ck_obligacion_origen`, ni el
`deleted_key` generado de `producto_servicio`, ni la normalización de `esquema_cobro`.

Los escenarios que **sólo** una base real puede contestar quedan en `docs/tests-diferidos.md`,
**desde el 70**, sin sustituto simulado.
