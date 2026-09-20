# AKINE-07.04 — Design challenge

Revisión adversarial de `AKINE-07.04-presentaciones.md`, previa al código, según `CLAUDE.md` §3.
**Este documento manda sobre el diseño donde discrepen.**

Veredicto global: **aprobado con una reserva y una decisión pendiente del usuario** (pregunta 7).

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

**`billing`, las cuatro. Ningún otro módulo las toca. Aprobado.**

| Tabla | Propietario | Quién más la lee o escribe |
|---|---|---|
| `presentacion` | `billing` | nadie |
| `presentacion_item` | `billing` | nadie |
| `financiador_pago` | `billing` | nadie |
| `presentacion_numerador` | `billing` | nadie |

`billing` ya es propietario de `obligacion` (V36), `cobro` + `cobro_medio` + `cobro_imputacion`
(V37) y `jornada_caja` + `movimiento_caja` (V54). Las cuatro nuevas viven en el mismo agregado
económico y ninguna necesita ser leída desde afuera.

### Los dos `ALTER` sobre tablas existentes, que es donde hay que mirar

Las dos tablas alteradas son **del mismo módulo**, así que no hay cruce de ownership:

- `obligacion` gana `financiador_id`. Es `billing` escribiéndose a sí mismo.
- `movimiento_caja` cambia un `CHECK` para admitir `PAGO_FINANCIADOR`. También `billing`.

**Objeción considerada:** `financiador_id` es una FK a `financiador`, que es de `contracting`.
¿Eso no es "tocar la tabla de otro módulo"? **No.** Una FK es una restricción de integridad
referencial, no un acceso: `billing` no lee `financiador` por SQL en ninguna consulta. Cuando
necesita el nombre del financiador lo pide por `contracting.spi`, que es el único camino
permitido. Es exactamente el mismo patrón que `obligacion.oferta_id → oferta_servicio_consultorio`
(de `offering`) y `obligacion.persona_id → persona` (de `person`), los dos aceptados desde 07.01.

**Objeción considerada:** ¿`V56` puede alterar `movimiento_caja`, que `V54` acaba de crear en otra
etapa en vuelo? **Sí, y la forma correcta es exactamente ésta.** La regla del repositorio es que
una migración no se edita **después de aplicada**; agregar un valor a un `CHECK` desde una
migración posterior es el camino previsto. Editar `V54` para colar `PAGO_FINANCIADOR` sería el
error: dos árboles con la misma versión y checksums distintos, que es como Flyway deja de
arrancar.

---

## 2. Ciclos — ¿la dependencia entre módulos es unidireccional? ¿Pasa ArchUnit?

**Sí. VERIFICADO CON ARCHUNIT, no razonado. Aprobado.**

La etapa introduce **una** arista nueva: `billing → contracting.spi`, para
`CoberturaCatalogoDirectory.findFinanciador(...)`.

**El precedente que obliga a medir en vez de pensar:** el challenge de 04.05 dio por verificado de
memoria que `person → encounter.spi` no cerraba ningún ciclo, y era falso —`clinical → person.spi`
y `encounter → clinical.spi` ya existían—. Lo destapó **poner una clase sonda y dejar que ArchUnit
hablara**. `SlicesRuleDefinition` busca ciclos de **cualquier longitud**; la cabeza encuentra los
de dos.

**Lo que se hizo acá, antes de escribir una línea de la etapa:** se creó
`billing.application.SondaDeCiclos`, una clase que importa `contracting.spi.CoberturaCatalogoDirectory`
y `contracting.spi.FinanciadorSnapshot` y los usa, y se corrió `ModuleArchitectureTest`:

```
Running com.akine.architecture.ModuleArchitectureTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 — 13.20 s
BUILD SUCCESS
```

La sonda se borró después de medir. **El grafo admite la arista.** Lo razonado —que `contracting`
sólo alcanza `organization.spi`, `platform.spi` y `resource.spi`, y que ninguno de los tres llega a
`billing`— coincide con lo medido, pero es lo medido lo que vale.

**Aristas existentes que la etapa NO cambia:** `billing → organization.spi` (sede y permisos),
`billing → platform.spi` (auditoría, tenant, problemas), `billing → encounter.spi` (observador del
cierre), `billing → person.spi` (contribuidor del Paciente 360). Ninguna se invierte.

**Y una que se rechazó explícitamente:** que `contracting` expusiera una consulta de "deuda
presentada de mis convenios" habría cerrado el ciclo de dos. La cuenta corriente del financiador la
responde `billing`, que es quien tiene la deuda.

---

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

**Sí. Aprobado.**

Las cuatro llevan `organization_id NOT NULL`, **incluidas las hijas**, que llegan por
`presentacion_id` y podrían "deducir" el tenant con un JOIN. ADR-0004 dice *sin excepción* y el
motivo es operativo: una consulta que se olvide del JOIN cruza tenants **sin fallar**. Misma
convención que `cobro_medio` (V37) y `movimiento_caja` (V54).

`presentacion` y `financiador_pago` llevan además `consultorio_id`: el convenio es contextual a la
sede (RN-M16-001) y un lote que cruza sedes mezcla convenios distintos.

Uniques, uno por uno:

| Unique | ¿Lleva tenant? |
|---|---|
| `presentacion (organization_id, consultorio_id, financiador_id, numero)` | sí |
| `presentacion (organization_id, financiador_id, factura_numero)` | sí |
| `presentacion_item (organization_id, obligacion_id, ocupa_marca)` | sí |
| `presentacion_item (presentacion_id, obligacion_id)` | **no, y es correcto** |
| `financiador_pago (organization_id, idempotency_key)` | sí |
| `presentacion_numerador (organization_id, consultorio_id, financiador_id)` | sí |

**El que no lo lleva, justificado.** `(presentacion_id, obligacion_id)` es una clave sobre un
`presentacion_id` que **ya es único en todo el sistema** —es un `AUTO_INCREMENT` global—, así que
agregarle `organization_id` no acota nada: no existen dos presentaciones con el mismo id en tenants
distintos. Es el mismo criterio con el que V36 dejó `uk_obligacion_prestacion (sesion_id,
responsable, deleted_key)` sin tenant, y con el que `ObligacionRepository.findPorPrestacion` no
filtra por organización —y su javadoc explica por qué agregarlo sería peor—.

Todos los índices de lectura arrancan por `organization_id`.

---

## 4. Reglas maestras — ¿el diseño confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?

**No, y es la pregunta cara de esta etapa. Aprobado, con dos verificaciones.**

### 4.1 Una presentación no es un cobro

El sistema tiene ahora **cuatro** cosas distintas, y el diseño las mantiene separadas por
construcción, no por disciplina:

| | Tabla | ¿Mueve el saldo de la obligación? | ¿Mueve caja? |
|---|---|---|---|
| Obligación | `obligacion` | lo crea | no |
| Cobro | `cobro` | sí, al imputar | sí, en la misma transacción |
| Caja | `movimiento_caja` | no | es la caja |
| **Presentación** | `presentacion` | **no, hasta conciliar** | **no** |
| **Pago del financiador** | `financiador_pago` | no | **sí** |

**Confirmar una presentación no mueve un peso ni toca una obligación.** Es la prueba de que
"presentado" es un estado distinto de "cobrado": RN-M21-001 hecha cumplir por lo que el código
**no** hace.

**Verificación adversarial:** ¿se coló algún camino donde presentar cobre? Se revisaron los siete
comandos. `crear`, `agregarItem`, `quitarItem`, `validar`, `confirmar`, `registrarFactura` y
`anular` **no llaman a `descontarSaldo` ni a `MovimientoCajaService` en ninguna rama**. Sólo dos
lo hacen: `conciliar` (salda obligaciones, no toca caja) y `registrarPago` (toca caja, no salda
obligaciones). **Ningún comando hace las dos cosas**, y eso es lo que hace que el modelo no se
pueda colapsar por descuido.

### 4.2 La cuenta corriente del financiador no es la caja

Se verificó que no se colara el error simétrico, que es más sutil: **tratar el saldo del
financiador como si fuera plata en un cajón.**

- La caja es de una **sede**, tiene **jornada**, **arqueo** y **fecha de negocio**. Se cuenta.
- La cuenta corriente de un financiador es de la **organización**, vive en **meses** y **no se
  arquea**: nadie cuenta los 400.000 que una obra social debe.

Por eso `presentacion.saldo` **no es un saldo de caja** y `financiador_pago` **no es un
movimiento de caja**: es el hecho comercial que *produce* uno. El pago tiene `movimiento_caja_id`
apuntando al movimiento que generó, no al revés, y la dirección importa: el movimiento existe
porque entró plata, y la plata entró porque el financiador pagó.

**Consecuencia que se aceptó a propósito:** un pago por transferencia asienta un movimiento con
`jornada_caja_id` NULL y **no afecta el arqueo**. Es la regla de 07.03 aplicada sin excepción, y es
correcta: esa plata fue a un banco, no al cajón. Meterla en el arqueo garantizaría que el conteo
nunca cuadre.

### 4.3 Sesión, Turno y Caso

La etapa **no toca nada clínico**. `presentacion_item` guarda `snapshot_sesion_id` como
**referencia de trazabilidad congelada**, no como puntero vivo: lo que se presenta es la
**obligación**, no la sesión. Y RN-M21-004 —"rechazar una prestación no elimina la sesión
original"— se cumple trivialmente porque **esta etapa no tiene ninguna escritura sobre
`encounter` ni sobre `clinical`**, en ningún camino. Debitar un ítem cambia el ítem y el total del
lote, y nada más.

---

## 5. Baja lógica — ¿hay borrado físico de información histórica relevante?

**Uno, y está justificado. Aprobado con la justificación escrita.**

| Tabla | Qué pasa al "borrar" |
|---|---|
| `presentacion` | `deleted_at` + `deleted_key`, igual que `obligacion` en V36. **Nunca se borra**; el descarte es `estado = 'ANULADA'` con motivo. |
| `financiador_pago` | **append-only**. Sin `deleted_at` y sin `delete` en el puerto: un pago mal registrado se compensa revirtiendo su movimiento y registrando el correcto. |
| `presentacion_numerador` | no se borra: borrarlo reiniciaría la serie de lotes de un financiador. |
| `presentacion_item` | **`DELETE` físico, sólo en `BORRADOR`.** |

### El `DELETE` físico, defendido

`DELETE FROM presentacion_item` sólo es alcanzable desde una presentación en `BORRADOR`. En ese
estado **no hay información histórica relevante que preservar**: el lote no se envió, nadie lo vio,
no tiene número y no existió para el financiador. Preservar cada ítem que alguien agregó y sacó
mientras armaba la pantalla no es historia: es ruido que después ensucia todas las consultas de
`presentacion_item` con filas fantasma que hay que filtrar en cada JOIN.

Un ítem de una presentación **confirmada no se puede quitar por ningún camino** —`quitarItem`
devuelve 409 `presentacion-no-editable`—: se debita, que deja motivo, actor e instante.

**Objeción considerada y rechazada:** usar baja lógica igual, "por consistencia". Tendría un costo
concreto: `ocupa_marca` es una columna generada sobre `estado`, y un ítem borrado lógicamente
seguiría en la tabla. Habría que agregar un cuarto estado `QUITADO` sólo para que libere la
obligación, y con él una rama más en cada consulta. **Un estado que sólo existe para tapar un
borrado que no hicimos es peor que el borrado.**

**Y lo que el `DELETE` físico NO puede hacer:** una presentación anulada **no borra** sus ítems.
Los pasa a `ANULADO`, que libera la obligación por `ocupa_marca` y conserva la evidencia de qué se
había pensado presentar.

---

## 6. Contrato — ¿el cambio de API es aditivo?

**Aditivo. `0.36.0 → 0.38.0`, minor. Aprobado.**

> El salto de dos minors no es un descuido: `0.37.0` está reservada por otra etapa en vuelo
> (06.04), igual que `V55`. Reservar el número antes de escribir código es regla del repositorio.

Trece operaciones nuevas, todas bajo rutas nuevas
(`/api/v1/consultorios/{id}/presentaciones..` y `/prestaciones-elegibles`). **Ninguna operación
existente cambia de firma, de códigos de respuesta ni de esquema.**

Diez tipos de problema nuevos en `ProblemType`. Agregar valores a ese enum es aditivo por
construcción: el gate `scripts/check-problem-types.mjs` del frontend falla cuando el cliente usa
un tipo que el contrato **no** declara, nunca al revés.

### Lo que sí hay que declarar, y no es gratis

- **`obligacion` gana una columna**, y la exponen `ObligacionResponse` y el resumen de persona.
  Agregar un campo opcional a un response es aditivo para un cliente generado. **No se agrega a
  ningún request**: `financiador_id` no se elige, se deriva del devengado.
- **`OrigenMovimiento` gana `PAGO_FINANCIADOR`**, y ese enum **sí viaja** en
  `MovimientoCajaResponse`. Un valor nuevo en un enum de respuesta es el borde de lo aditivo: un
  cliente TypeScript generado desde una versión anterior que haga `switch` exhaustivo sobre `tipoOrigen` no lo
  conoce. **Hoy no hay cliente**: el frontend está en `0.29.0` y no tiene pantalla de caja, así que
  no hay consumidor que romper. Queda declarado igual, porque el día que exista una pantalla de
  caja esto es exactamente lo que hay que revisar antes de agregar el quinto valor.
- **El contrato NO se puede regenerar**: `OpenApiContractIT` necesita Docker. El YAML queda con el
  `info.version` en `0.38.0` y los `paths` de `0.29.0`. **Es drift declarado, no un descuido, y el
  YAML no se edita a mano para taparlo.** Se destraba con una sola corrida de
  `./mvnw verify -Dakine.contract.update=true` desde la rama que tenga todas las etapas en vuelo.

**La trampa del 406:** `DELETE .../items/{itemId}` responde 204 sin cuerpo y por lo tanto declara
`application/json` **además** de `application/problem+json` en sus `produces`. Sin eso el cliente
generado manda `Accept: application/problem+json` y el `produces` de clase lo corta con 406
**antes de entrar al método** — y ningún test lo agarra, porque los unitarios del frontend usan
`HttpTestingController`, que no negocia contenido, y los de integración mandan el `Accept` de
MockMvc. Es la única operación de la etapa que responde 204.

---

## 7. Ruta crítica — ¿este módulo tiene sus cimientos, o lo estoy adelantando?

**RESERVA. Le falta un cimiento, el cimiento no es de esta etapa, y hay que decírselo al usuario.**

La ruta crítica del `AGENT.md` §9 es
`... → Obligación → Cobro → Caja → Presentación/Reportes`, y las tres anteriores existen. Pero:

**No hay ninguna obligación con `responsable = FINANCIADOR`, y nada la produce.**

`ObligacionDevengador` devenga una sola obligación, a nombre del paciente, por el `precio_base` de
la Oferta. Es lo que DP-10 recortó y lo que V36 dejó escrito: `Responsable.FINANCIADOR` existe en
el enum y en el `CHECK`, `snapshot_convenio_id` y `snapshot_arancel_id` están reservadas en NULL, y
el comentario dice *"es donde 03.05 se enchufa"*. **03.05 se implementó como el módulo
`contracting` y nunca volvió a tocar el devengado.** El enchufe sigue vacío.

Dos consecuencias concretas y medibles:

1. `GET /prestaciones-elegibles` devuelve **lista vacía** en un despliegue real.
2. RF-M21-003 **no puede validar** orden, autorización ni credencial: esos tres booleanos viven en
   `ArancelCongelado` y el consumidor que tenía que copiarlos a sus columnas es el devengado.

### El tercer error estructural del plan, y por qué esto no lo comete

`AGENT.md` §9 prohíbe "construir módulos consumidores antes que sus cimientos". ¿Esto es eso?
**No, y la distinción importa.** M21 está *completo como modelo*: agrupar, validar, confirmar,
facturar, debitar, cobrar y conciliar son reglas que no dependen de cómo se produjo la deuda, sólo
de que exista y tenga financiador. Lo que falta es **un productor**, no un cimiento conceptual. La
prueba es que el día que el devengado se recablee, esta etapa **no cambia**: se llena.

Lo que sí sería cometer el error es **inventar el productor acá**. Recablear el devengado necesita
(a) la cobertura vigente del paciente, que `person` no expone por `spi`, (b) la `practicaId` de la
oferta, que `SesionCerrada` no lleva, y (c) copiar el `ArancelCongelado` entero a `obligacion`. Son
tres módulos, una migración y un cambio de contrato: **es una etapa propia**, y hacerla de
contrabando adentro de ésta sería adelantar sin diseño lo que en su momento se recortó con uno.

### Lo que la etapa sí agrega, y por qué es el mínimo

**Una sola columna: `obligacion.financiador_id`.** No es opcional: RF-M21-001 pide listar
obligaciones de financiador **por período**, y sin financiador en la fila no hay por dónde agrupar.
Se descartaron las dos alternativas:

- **Deducirlo por `snapshot_convenio_id → convenio → financiador`:** obligaría a `billing` a
  navegar el agregado de `contracting`, la columna está en NULL igual, y una lectura viva sobre un
  convenio dado de baja dejaría deudas históricas sin financiador. Es exactamente lo que
  `ArancelCongelado` existe para impedir.
- **Poner el financiador sólo en el ítem:** rompe RF-M21-001, que necesita filtrar **antes** de
  armar el lote, cuando todavía no hay ítems.

> **DECISIÓN DEL USUARIO, no la toma el agente:** si el recableado del devengado se abre como
> etapa propia (con su migración, su cambio de contrato y su ventana de datos) o si M21 queda
> entregado y vacío hasta que F3 cierre ese enchufe. **La etapa se implementa igual**, porque su
> modelo es correcto y su entrega no depende de esa respuesta.

---

## 8. El caso que rompe el diseño

### El caso: **el débito y el pago que llegan juntos sobre el mismo lote**

Un lote de 100.000 está `FACTURADA`. Dos administrativos trabajan a la vez:

- **A** registra el aviso de débito que llegó por mail: el financiador rechaza 15.000 por falta de
  autorización en tres sesiones.
- **B** registra la transferencia que el financiador acaba de hacer: 100.000, el total del lote,
  porque el aviso de débito y la transferencia se cruzaron en el correo.

Un diseño ingenuo lee el saldo, resta y guarda. Las dos transacciones leen 100.000, las dos
consideran que alcanza, y el lote queda con `total_debitado = 15.000`, `total_cobrado = 100.000` y
**un saldo de −15.000**: la obra social figura debiéndole plata al centro por un lote que
sobrepagó. Peor: la presentación **concilia sola**, porque el residual da negativo y el control de
"saldo = 0" nunca se cumple ni se puede explicar.

Y el caso tiene una segunda mitad que agrava: si el saldo quedara negativo, `conciliar` saldaría
las obligaciones de los ítems `INCLUIDO` por importes que el financiador nunca aceptó, y esas
deudas quedarían `PAGADA` **en la cuenta corriente del paciente**.

### Cómo lo resuelve

**Las dos escrituras son el mismo `UPDATE` condicional, sin lock y sin lectura previa:**

```sql
UPDATE presentacion
   SET total_cobrado = total_cobrado + :importe, saldo = saldo - :importe
 WHERE id = :id AND organization_id = :org
   AND estado IN ('PRESENTADA','FACTURADA')
   AND saldo >= :importe;
```

No hay ventana entre leer y escribir porque **no se lee**. El motor serializa las dos sentencias
sobre la misma fila, la primera gana y la segunda afecta **cero filas**. Cero filas no es un error
técnico: es la respuesta, y el servicio la distingue releyendo la presentación —seguro, porque un
`UPDATE` de cero filas no marca la transacción para rollback, a diferencia de un `flush` fallido
por constraint, que sí la marca y hace estallar cualquier consulta posterior con
`UnexpectedRollbackException`. Este repositorio ya pagó esa cuatro veces.

El que pierde recibe **409 `presentacion-saldo-insuficiente` con el saldo actual**, y ese número es
lo que arregla el caso desde el mostrador: B ve 85.000 y entiende que hubo un débito, en vez de
mirar un "no se puede" y volver a intentar.

`CHECK (saldo >= 0)` y `CHECK (saldo = total_presentado - total_debitado - total_cobrado)`
respaldan la condición. **Si un camino futuro se olvida del `WHERE`, la base lo frena** en vez de
dejar pasar el estado imposible.

### El sub-caso que la base resuelve sola

La misma prestación agregada a dos lotes a la vez —dos administrativos armando agosto y
septiembre—. No lo decide un `if`: lo decide
`UNIQUE (organization_id, obligacion_id, ocupa_marca)` sobre una **columna generada** a partir del
estado del ítem. El segundo `INSERT` choca contra el unique y se traduce a 409
`obligacion-ya-presentada` **con el id del lote que la tiene**, que es lo que permite que el
administrativo vaya a mirarlo.

Que la marca sea **generada** y no un booleano que alguien setea es el punto: no puede divergir del
estado, y un camino futuro que debite o anule un ítem libera la obligación **sin acordarse de
hacerlo**.

### Lo que este diseño NO resuelve, y queda declarado

**Un pago que cubre varios lotes a la vez.** `financiador_pago` cuelga de **una** presentación. Una
obra social que transfiere 500.000 por cuatro lotes obliga hoy a cargar cuatro pagos con la misma
referencia. Es feo y es correcto: repartir un importe global entre lotes exige una regla de
imputación que el financiador no informó, y sería el mismo error que §8 del diseño rechaza para
repartir un pago entre ítems. Anotado como límite conocido, no como defecto.

---

## Veredicto

| Pregunta | Veredicto |
|---|---|
| 1. Ownership | **Aprobado.** Cuatro tablas de `billing`; los dos `ALTER` son sobre tablas propias. |
| 2. Ciclos | **Aprobado y MEDIDO.** Sonda + `ModuleArchitectureTest` 5/5 en verde. |
| 3. Tenant | **Aprobado.** El único unique sin tenant está justificado con precedente. |
| 4. Reglas maestras | **Aprobado.** Ningún comando saldó deuda y movió caja a la vez. |
| 5. Baja lógica | **Aprobado.** Un `DELETE` físico, sólo en borrador, defendido. |
| 6. Contrato | **Aprobado, aditivo.** El valor nuevo de enum en response queda declarado. |
| 7. Ruta crítica | **RESERVA.** Falta el productor de obligaciones de financiador. **Decisión del usuario.** |
| 8. El caso que rompe | **Resuelto** por `UPDATE` condicional y columna generada, con `CHECK` de respaldo. |

**Se avanza a código.** La reserva de la 7 no bloquea la implementación —el modelo es correcto y
no cambia cuando el productor llegue— pero **tiene que viajar en el reporte de cierre, no
enterrada acá**.
