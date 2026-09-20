# AKINE-07.03 — Caja diaria y movimientos reales (M20)

Diseño de la etapa. **El design challenge (`AKINE-07.03-challenge.md`) manda sobre este documento
donde discrepen.**

Trazabilidad: RF-M20-001..007, RN-M20-001..004, RF-M24-006, DP-06.
Depende de AKINE-07.02 (cobros), cerrada.

---

## 1. El punto de la etapa, en una frase

**`Cobro ≠ Caja` (regla maestra 7) y la caja registra movimientos monetarios REALES (regla 8).**

Un cobro es un hecho **comercial**: alguien pagó una obligación. Un movimiento de caja es un hecho
**monetario**: entró o salió plata, de un lugar concreto, en una jornada concreta, con un
responsable. La relación no es uno a uno y **no todos los cobros mueven la caja**.

Todo lo que sigue se deriva de eso.

---

## 2. Qué es una "caja"

**La caja es la sede. La unidad operativa es la jornada.**

| Candidato | Por qué no |
|---|---|
| Una caja por **responsable** | Exigiría un cajón físico por persona. En un centro de kinesiología el mostrador es uno y lo atienden tres personas por turno. El arqueo sería de una caja que nadie puede contar por separado. |
| Una caja por **día**, rígida | Un centro que abre mañana y tarde con dos responsables distintos arquea dos veces. Atarlo al día calendario obliga a mentir en uno de los dos arqueos. |
| Una caja por **organización** | Una organización con dos sedes tiene dos cajones a 40 km. Un solo saldo no se puede contar. |

Entonces:

- **`jornada_caja`** es el agregado: pertenece a `(organization_id, consultorio_id)`, tiene
  `fecha_negocio`, saldo inicial declarado, responsable de apertura y, al cerrar, responsable de
  cierre, saldo teórico congelado, saldo declarado, diferencia y motivo.
- **A lo sumo una jornada `ABIERTA` por sede.** Lo hace cumplir la base, no un `if`: columna
  generada `abierta_marca = IF(estado='ABIERTA', 1, NULL)` y `UNIQUE (organization_id,
  consultorio_id, abierta_marca)`. Varios `NULL` no colisionan en MySQL — es el mismo mecanismo que
  `deleted_key` (V30) y `owner_key` (ADR-0021), usado al derecho en vez de al revés. Al ser
  **generada**, no puede desincronizarse de `estado`.
- **Varias jornadas por `fecha_negocio` son legítimas** (mañana y tarde). No hay unique sobre la
  fecha.

### La fecha de negocio

`fecha_negocio DATE`, **derivada y no pedida**: se calcula al abrir, con `Instant.now()` proyectado
a la zona IANA de la sede (`ConsultorioSnapshot.timezone`, que 02.01 ya expone y que V16 puso en
`consultorio` por exactamente esta razón — su comentario ya nombra el "corte de caja").

No se puede abrir una jornada con fecha pasada ni futura. Una caja que se abre "para ayer" es un
ajuste contable disfrazado de operación.

**Cada movimiento lleva su propia `fecha_negocio`**, no sólo la jornada. Es lo que permite listar
la operatoria de un día que incluye movimientos sin jornada (§4) y lo que hace que el caso
"movimiento tardío" tenga una respuesta.

---

## 3. Qué medio de pago mueve la caja — la decisión central

**Sólo `EFECTIVO` afecta el arqueo.** El resto de los medios se registran, se listan y se totalizan,
pero **no cambian el saldo arqueable**.

Por qué:

- Un arqueo es **contar billetes**. Una tarjeta de crédito liquida en 18 días y a una cuenta que el
  sistema no modela; una transferencia entra a un banco. Sumarlas al saldo del cajón garantiza que
  el conteo nunca cuadre, y una caja que nunca cuadra deja de ser un control.
- Pero **excluirlas del registro** haría que la pantalla de caja mintiera sobre el día: el
  administrativo necesita ver que facturó 120.000 de los cuales 60.000 por tarjeta. RF-M20-004 pide
  "listar y filtrar operatoria diaria", no "listar efectivo".

Cómo se hace cumplir: `movimiento_caja.afecta_arqueo` es una **columna generada**
`(medio = 'EFECTIVO')`. No es un booleano que alguien setea: no puede divergir del medio, y una
etapa futura que agregue un medio nuevo (billetera virtual, cheque) hereda la regla sin tocar código.

> **Lo que esto NO decide:** dónde va la plata que no es efectivo. Cuentas bancarias, conciliación y
> liquidación de tarjetas son M21/M22 (07.04 y 07.05). Esta etapa dice "esto no es del cajón" y no
> pretende decir de quién es.

---

## 4. ¿El movimiento lo genera el cobro, o lo registra una persona?

**Lo genera el cobro, automáticamente, DENTRO de la misma transacción.**

La alternativa —que una persona cargue el movimiento después— tiene una falla que no se puede
cerrar: nada obliga a que lo haga, nada verifica que el importe coincida, y la caja y los cobros
divergen **sin que falle nada**. El único mecanismo que los reconciliaría es un reporte que nadie
corre. Es el mismo razonamiento con el que 07.01 devenga la deuda dentro de la transacción del
cierre clínico, con la misma contrapartida asumida y escrita: **si el registro del movimiento falla,
el cobro no se confirma.**

Acá el costo es menor que en 07.01, porque no hay borde de módulo: `Cobro` y `MovimientoCaja` son
del **mismo módulo** `billing` y de la misma transacción. No hay observador, no hay evento, no hay
arista nueva en el grafo.

### Y si no hay caja abierta cuando alguien cobra

Esta es la pregunta que arruina el diseño si se contesta de taquito. Tres salidas, y la respuesta
**depende del medio**:

| | Efectivo | No efectivo |
|---|---|---|
| **Decisión** | **Se rechaza el cobro: 409 `caja-no-abierta`** | **Se registra el movimiento con `jornada_caja_id` NULL** |

- **Efectivo sin jornada abierta se rechaza**, y es el único caso donde el mundo físico obliga.
  La plata entra al cajón igual; si el sistema no sabe a qué jornada pertenece, el arqueo de ese
  día no puede cuadrar contra nada y el dinero queda fuera de todo control. Abrir la caja es un
  acto de diez segundos; recuperar un arqueo roto no lo es.
  - **Rechazadas las otras dos salidas:** abrir la jornada automáticamente inventaría una apertura
    sin responsable y con saldo inicial cero —y "apertura auditada" dejaría de significar algo—;
    y registrar el efectivo sin jornada es exactamente el agujero que se quiere evitar.
- **No efectivo no necesita jornada**, porque esa plata nunca tocó el cajón. El movimiento se
  asienta con `jornada_caja_id` NULL, su propia `fecha_negocio` y `afecta_arqueo = 0`. Sigue
  apareciendo en la operatoria del día y no participa de ningún arqueo.

La base lo garantiza: `CHECK (medio <> 'EFECTIVO' OR jornada_caja_id IS NOT NULL)`. Se escribe sobre
`medio` y no sobre la columna generada a propósito, para no depender de la semántica de generadas
dentro de un `CHECK`.

> **Consecuencia sobre 07.02 que hay que declarar:** `POST /cobros` gana un 409 nuevo. Es aditivo
> en el contrato (un código de error más en una operación que ya devolvía 409) pero **es un cambio
> de comportamiento**: un cobro en efectivo que antes entraba, ahora puede no entrar. Está
> declarado en el challenge §6.

---

## 5. El saldo: materializado, movido por condición, nunca negativo

`jornada_caja.saldo_arqueo DECIMAL(12,2)` arranca igual al saldo inicial y se mueve en la **misma
transacción** que cada movimiento que `afecta_arqueo`.

Es el criterio de V36 (saldo de la obligación) y de V50 (`cantidad_consumida`): **el ledger es la
fuente de verdad y la columna es la respuesta rápida y el guardián.** Y acá vale igual que allá: la
columna y las filas son del mismo módulo y de la misma transacción, así que alguien puede garantizar
su coherencia.

Las dos escrituras, y las condiciones son todo el diseño:

```sql
-- Ingreso (y reversión de egreso)
UPDATE jornada_caja
   SET saldo_arqueo = saldo_arqueo + :importe
 WHERE id = :id AND organization_id = :org AND estado = 'ABIERTA';

-- Egreso (y reversión de ingreso)
UPDATE jornada_caja
   SET saldo_arqueo = saldo_arqueo - :importe
 WHERE id = :id AND organization_id = :org AND estado = 'ABIERTA'
   AND saldo_arqueo >= :importe;
```

**Cero filas es la respuesta, no un error técnico.** Sin lock, sin lectura previa, sin ventana entre
leer y escribir, y **sin deadlock posible**. Es literalmente lo que 07.02 hizo para imputar y 04.05
para consumir.

Cero filas tiene dos causas —la jornada cerró, o no alcanza el saldo— y hay que distinguirlas para
responder bien. **Se distinguen releyendo la jornada después del UPDATE.** Es seguro: un `UPDATE` de
cero filas no marca la transacción para rollback, a diferencia de un `flush` fallido por constraint
(ver `AdjuntoClinicoEscrituraAparte`, y la regla que este repo ya pagó cuatro veces).

`CHECK (saldo_arqueo >= 0)` respalda la condición: **un egreso no puede dejar la caja en negativo**
porque un cajón no puede tener menos de cero pesos. Si un camino futuro se olvida del `WHERE`, la
base lo frena.

### No hay `@Version` en `jornada_caja`, y es deliberado

Las escrituras que mueven el saldo son **SQL nativo** y no pasan por JPA: no incrementarían la
`@Version`. Un token optimista que no avanza cuando el dato cambia es peor que no tenerlo, porque
promete una protección que no da. Y hacer que la avancen exigiría cargar la entidad antes —que es
exactamente la lectura previa que la condición evita— o un force-increment sobre un padre que ya
queda sucio, que es la trampa que 04.02 pagó: la versión avanzaría dos veces y el cliente comería un
409 del que no puede salir.

**La protección la da la condición del `WHERE`, y para el cierre, `saldoTeoricoEsperado` (§6).**
Es una desviación explícita de la línea "cierre con optimistic/pessimistic protection" del plan:
se cumple el propósito con un mecanismo distinto y más fuerte.

---

## 6. El cierre

Un solo `UPDATE` condicional:

```sql
UPDATE jornada_caja
   SET estado = 'CERRADA',
       saldo_teorico_cierre = saldo_arqueo,
       saldo_declarado      = :declarado,
       diferencia           = :declarado - saldo_arqueo,
       motivo_diferencia    = :motivo,
       cerrada_en = :ahora, cerrada_por_cuenta_id = :actor
 WHERE id = :id AND organization_id = :org
   AND estado = 'ABIERTA'
   AND saldo_arqueo = :saldoTeoricoEsperado;
```

- **`saldo_teorico_cierre` se congela.** Es lo mismo que `saldo_arqueo` en ese instante, pero
  guardado aparte: un cierre histórico tiene que poder explicarse dentro de seis meses sin recalcular
  nada, igual que el snapshot de precio de V36.
- **`diferencia` la calcula el servidor**, nunca el cliente. Es `declarado − teórico`: positiva =
  sobra plata, negativa = falta.
- **El cierre es concurrente-seguro sin lock.** Dos cierres simultáneos: el segundo afecta cero
  filas porque `estado` ya no es `'ABIERTA'` → 409 `caja-cerrada`.
- **`saldoTeoricoEsperado` es la protección optimista**, y resuelve el caso que rompe el diseño
  (challenge §8): el cobro en efectivo que entra **entre que el operador contó y confirmó el
  cierre**. Sin esa condición, el sistema registraría una diferencia que nunca existió y obligaría
  a justificar por escrito un faltante inventado. Con ella: cero filas, 409 `caja-saldo-cambio`
  con el saldo teórico actual, y el operador recuenta o confirma contra el número nuevo.

### La diferencia se registra. No se rechaza y no se ajusta.

| Salida | Por qué no |
|---|---|
| **Rechazar el cierre** si no cuadra | Dejaría al centro sin poder cerrar el día en que realmente falta plata, que es exactamente el día en que el registro importa. |
| **Ajustar** con un movimiento que iguale | Borra el hecho: el ledger pasaría a sumar el conteo declarado y la diferencia desaparecería de los movimientos. Un número que se corrige solo no es un control. |

**`motivo_diferencia` es obligatorio cuando `diferencia <> 0` y prohibido cuando es cero**, por
`CHECK`. Es RN-M20-004 hecho cumplir por el esquema. Que sea prohibido con diferencia cero no es
purismo: un motivo que a veces adorna un cierre correcto deja de leerse en los cierres que sí
importan.

**La diferencia NO es un movimiento de caja.** Si lo fuera, el saldo teórico de la jornada siguiente
partiría de un ledger que ya "explicó" el faltante y el hecho se autodestruiría. La jornada
siguiente declara su propio saldo inicial, contado por quien la abre.

---

## 7. El cierre no se revierte: se compensa

**Una jornada cerrada no se reabre, no se edita y sus movimientos no se tocan.** RN-M20-003 y regla
maestra 10.

Un movimiento asentado por error se compensa con una **fila propia de tipo `REVERSION_DE_INGRESO` o
`REVERSION_DE_EGRESO`**, con motivo obligatorio y `movimiento_origen_id` apuntando al original. Es
exactamente el diseño del ledger de 04.05 (`V50`), en dos direcciones en vez de una.

**Y la compensación se asienta en la jornada ABIERTA AHORA, no en la del original.** Es la decisión
menos obvia del documento y la más correcta:

- La jornada original ya fue arqueada. Si el error afectó el conteo, la `diferencia` de ese cierre
  **ya lo registró**. Reescribir esa jornada haría que su saldo declarado dejara de coincidir con lo
  que efectivamente se contó, destruyendo la única evidencia de que hubo un desvío.
- Físicamente, la plata sale (o entra) al cajón **hoy**. El movimiento compensatorio describe un
  hecho monetario de hoy, y eso es lo que la caja registra.

`movimiento_origen_id` cruza jornadas y eso es lo que RF-M24-006 pide: relacionar operación original
y reversión. Un movimiento se revierte **una sola vez** —lo hace cumplir el unique de §8— y **una
reversión no se revierte**: para deshacer una reversión se asienta un movimiento nuevo, que es
honesto sobre lo que pasó.

> Si no hay jornada abierta y el movimiento a revertir era en efectivo, la reversión se rechaza con
> el mismo 409 `caja-no-abierta`. Coherente con §4: sacar plata del cajón exige un cajón abierto.

---

## 8. Esquema — `V54`

Dos tablas. Ninguna otra migración, ningún `ALTER` a tablas de 07.01/07.02.

### `jornada_caja`

`organization_id`, `consultorio_id`, `fecha_negocio DATE`, `moneda CHAR(3)`,
`estado VARCHAR(16)` (`ABIERTA`|`CERRADA`), `saldo_inicial`, `saldo_arqueo`,
`abierta_en`, `abierta_por_cuenta_id`, `cerrada_en`, `cerrada_por_cuenta_id`,
`saldo_teorico_cierre`, `saldo_declarado`, `diferencia`, `motivo_diferencia`,
`abierta_marca TINYINT AS (IF(estado='ABIERTA',1,NULL)) STORED`, `created_at`, `updated_at`.

Todos los importes `DECIMAL(12,2)`.

- `UNIQUE (organization_id, consultorio_id, abierta_marca)` — una sola abierta por sede.
- `CHECK (saldo_arqueo >= 0)`, `CHECK (saldo_inicial >= 0)`.
- `CHECK` de cierre completo: los seis campos de cierre son todos NULL o todos no-NULL
  (salvo `motivo_diferencia`, que sigue su propia regla). Mismo criterio que
  `ck_obligacion_anulacion_completa` de V36.
- `CHECK ((diferencia IS NULL) OR (diferencia = 0) = (motivo_diferencia IS NULL))` — motivo
  obligatorio sólo con diferencia.
- Índice `(organization_id, consultorio_id, fecha_negocio)` — los cierres históricos, RF-M20-007.
- **Sin `version`** (§5). **Sin baja lógica**: una jornada no se borra ni se desactiva, se cierra.

### `movimiento_caja` — append-only

`organization_id`, `consultorio_id`, `jornada_caja_id` (NULLABLE, §4), `fecha_negocio DATE`,
`tipo VARCHAR(24)`, `medio VARCHAR(24)`, `importe DECIMAL(12,2)`, `moneda CHAR(3)`,
`concepto VARCHAR(160)`, `motivo VARCHAR(280)`,
`tipo_origen VARCHAR(16)`, `referencia_origen BIGINT`, `movimiento_origen_id BIGINT`,
`registrado_en`, `registrado_por_cuenta_id`, `idempotency_key`, `request_hash`,
`afecta_arqueo TINYINT AS (medio = 'EFECTIVO') STORED`, `created_at`.

- **Sin `version`, sin `updated_at`, sin `deleted_at`, y el puerto no declara `update` ni `delete`.**
  Un historial que se puede editar no es un historial. Mismo diseño que `turno_evento` (V38),
  `caso_evento` (V47), `plan_evento` (V49) y `autorizacion_movimiento` (V50).
- **`importe` es SIEMPRE POSITIVO: el signo lo da el `tipo`.** Un ledger con negativos obliga a todo
  lector a conocer el signo de cada tipo para no sumar al revés. V50 lo fijó y se respeta.
  `tipo IN ('INGRESO','EGRESO','REVERSION_DE_INGRESO','REVERSION_DE_EGRESO')`; suman los dos
  primeros del par ingreso, restan los dos del par egreso.
- `UNIQUE (organization_id, tipo, tipo_origen, referencia_origen, medio)`:
  - `tipo_origen='COBRO'` → `referencia_origen = cobro.id`, una fila por `(cobro, medio)`. El
    reintento del cobro no duplica el movimiento.
  - `tipo_origen='REVERSION'` → `referencia_origen = movimiento_origen_id`. **`tipo` entra en la
    clave a propósito** (V50): un movimiento se revierte una sola vez.
  - `tipo_origen='MANUAL'` → `referencia_origen` NULL, que en MySQL nunca colisiona; la idempotencia
    la da `UNIQUE (organization_id, idempotency_key)` con `request_hash`, igual que `cobro` en V37.
- `CHECK (medio <> 'EFECTIVO' OR jornada_caja_id IS NOT NULL)` — §4.
- `CHECK (tipo NOT IN ('REVERSION_DE_INGRESO','REVERSION_DE_EGRESO') OR (motivo IS NOT NULL AND movimiento_origen_id IS NOT NULL))`.
- `CHECK (movimiento_origen_id IS NULL OR tipo IN ('REVERSION_DE_INGRESO','REVERSION_DE_EGRESO'))`.
- `CHECK (importe > 0)`.
- `organization_id` en la tabla hija **no es negociable** aunque se llegue por `jornada_caja_id`:
  ADR-0004 dice "sin excepción", y es precisamente por eso —una consulta que se olvide del JOIN
  cruzaría tenants sin fallar. Misma convención que `cobro_medio` (V37).
- Índices: `(organization_id, jornada_caja_id, registrado_en)` para la vista operativa,
  `(organization_id, consultorio_id, fecha_negocio, tipo)` para el filtro por día,
  `(movimiento_origen_id)` para la trazabilidad de RF-M24-006.

---

## 9. API — contrato `0.36.0`

Siete operaciones, todas bajo `/api/v1/consultorios/{consultorioId}/caja`. Aditivas.

| Verbo | Ruta | RF |
|---|---|---|
| `POST` | `/jornadas` | RF-M20-001 abrir |
| `GET` | `/jornadas` | RF-M20-007 cierres históricos (filtros `estado`, `desde`, `hasta`; paginado) |
| `GET` | `/jornadas/{jornadaId}` | RF-M20-005 saldo teórico + totales por medio |
| `POST` | `/jornadas/{jornadaId}/cierre` | RF-M20-006 cerrar |
| `POST` | `/movimientos` | RF-M20-002 ingreso manual / RF-M20-003 egreso |
| `POST` | `/movimientos/{movimientoId}/reversion` | RN-M20-003, RF-M24-006 |
| `GET` | `/movimientos` | RF-M20-004 consultar y filtrar (paginado) |

Ninguna responde 204, así que la trampa del `produces` con `application/json` (la que rompió
activación de cuenta) no aplica; se declaran los dos tipos igual, por consistencia con
`CobroController`.

**Tipos de problema nuevos** (`platform.spi.problem.ProblemType`), todos 409 salvo el último:
`caja-no-abierta`, `caja-ya-abierta`, `caja-cerrada`, `caja-saldo-cambio`,
`caja-saldo-insuficiente`, `caja-moneda-distinta`, `movimiento-no-reversible`, y
`caja-diferencia-sin-motivo` (400). Se mapean en **`BillingProblemHandler`**, el advice del propio
módulo — nunca en `GlobalExceptionHandler`.

Se reusan `NOT_FOUND` y `IDEMPOTENCY_KEY_CONFLICT`.

---

## 10. Permisos

**`caja:operate`**, que ya existe en el catálogo (matriz §5, F7) y en `PermissionCode.CAJA_OPERATE`
sin asignación base. Esta etapa le da asignación base según la matriz §2, fila "Operar Caja"
(`No | Sí | Sí | No | Sí | No`):

| Rol | Alcance |
|---|---|
| `ORG_ADMIN` | `ORGANIZACION` |
| `CONSULTORIO_ADMIN` | `CONSULTORIO` |
| `ADMINISTRATIVO` | `CONSULTORIO` |
| `PROFESIONAL` | — (la matriz dice "No") |
| `PLATFORM_ADMIN` | — ("No": soporte mira, no opera; igual que `cobro:register`) |
| `PACIENTE` | — |

> **El cobro NO exige `caja:operate`.** El movimiento que genera es una consecuencia del cobro, no
> una operación de caja. Exigirlo haría que **poder cobrar dependiera del medio de pago elegido**,
> que es absurdo desde el mostrador y desde la matriz.

Apertura, cierre y reversión se auditan con `AuditTrail`, **dentro de la transacción de negocio**
(eventos `CAJA_ABIERTA`, `CAJA_CERRADA`, `CAJA_MOVIMIENTO_MANUAL`, `CAJA_MOVIMIENTO_REVERTIDO`).

---

## 11. Lo que esta etapa NO hace

Declarado para que nadie lo busque ni lo dé por hecho.

- **No implementa M22.** RF-M20-003 es el egreso *de caja* —una salida de dinero con concepto—. El
  egreso de M22 tiene categoría, beneficiario, pago a profesional y su propio circuito de anulación:
  es 07.05, y esta etapa le deja el primitivo de movimiento sobre el que apoyarse.
- **No implementa anticipos ni anulación de cobro con reintegro.** 07.02 los difirió *porque no
  había caja*; ahora la hay, pero los dos exigen tocar el agregado `Cobro` —un cobro sin obligación
  imputada, y una anulación que devuelva saldo a la deuda—, y eso es alcance de M19, no de M20. Lo
  que esta etapa entrega es la mitad que faltaba: el movimiento real y su reversión.
- **No modela cuentas bancarias ni concilia tarjetas.** 07.04 / 07.05.
- **No hay arqueo por denominación de billetes.** El saldo declarado es un número.
- **No hay reapertura de jornada.** Nunca.
- **No hay múltiples cajas por sede ni caja por responsable** (§2).
- **No hay cierre Z, reportes ni tableros.** 07.06.
- **No hay frontend.** El cliente TypeScript se genera desde el contrato y el contrato no se puede
  regenerar sin Docker.
- **No hay tests de integración.** Docker no arranca. Lo que queda sin verificar está listado en el
  registro de cierre.

---

## 12. Bloques de implementación

1. **`V54`** — las dos tablas, con la cabecera que explica el porqué al nivel de `V36`/`V37`/`V49`.
2. **Dominio** — `JornadaCaja`, `MovimientoCaja`, `EstadoJornada`, `TipoMovimiento`,
   `OrigenMovimiento`, excepciones, puertos.
3. **Persistencia** — repositorios con los `UPDATE` condicionales y el `INSERT ... ON DUPLICATE KEY`
   que no hace falta acá (no hay fila-lock perezosa: la jornada la crea una persona, explícitamente).
4. **Aplicación** — `CajaService` (abrir, cerrar, listar), `MovimientoCajaService` (manual, reversión,
   consulta) y `CajaDeCobro`, el registrador interno que `CobroService` invoca.
5. **API** — `CajaController`, DTOs, tipos de problema y handlers.
6. **Permisos** — `RolePermissions` y la matriz.
7. **Contrato** — `0.36.0` en `pom.xml`, `application.yml` y el `info.version` del YAML. **Los
   `paths` no se tocan: sin Docker no se regeneran.**
8. **Tests unitarios** — sólo los que la etapa exige.
