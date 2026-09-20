# Design challenge — AKINE-07.05

Paso obligatorio de `CLAUDE.md` §3. Ocho preguntas, respondidas por escrito **antes** de escribir
código. **Este documento manda sobre `AKINE-07.05-egresos.md`** donde discrepen.

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

`egreso` y `pago_egreso`: **propietario `billing`**, sin excepción. Ningún otro módulo las lee ni
las escribe, y **ninguna se expone por un `spi`**: esta etapa no le ofrece nada al resto del
sistema. El día que M23 (reportes) necesite "cuánto se le pagó a cada profesional", eso será un
puerto nuevo, decidido entonces, y no una tabla que alguien empezó a leer por su cuenta.

Hay un tercer objeto en juego, y es el que había que mirar de frente: **`movimiento_caja`, que esta
etapa escribe y no es suya… salvo que sí lo es.** Es de `billing`, la escribe 07.03, y esta etapa la
escribe **por el mismo método de `billing.application` que la escribe todo lo demás**
(`MovimientoCajaService.asentar` y `.revertir`). No hay un segundo camino para que salga plata: no
hay `UPDATE` directo a `jornada_caja`, no hay tabla `egreso_movimiento`, no hay ledger paralelo.

Ese era el error disponible y tiene nombre: si el egreso se hubiera asentado por su cuenta, el
`saldo_arqueo` de la jornada tendría **dos dueños** y el arqueo dejaría de ser la suma de un solo
ledger. La caja seguiría cuadrando en los tests y dejaría de cuadrar en producción el primer día
que alguien pagara un alquiler.

El `ALTER` de `V57` sobre `ck_movimiento_caja_tipo_origen` es una modificación de esquema sobre una
tabla ajena a la etapa pero **propia del módulo**, aditiva (ningún valor existente deja de ser
válido). No cruza ningún borde.

**Veredicto: pasa.**

---

## 2. Ciclos — ¿la dependencia entre módulos es unidireccional? ¿Pasa ArchUnit?

Es la pregunta con el precedente más caro del proyecto —el challenge de 04.05 dio por verificada de
memoria una arista que sí cerraba ciclo— y **acá hay un riesgo concreto y nombrable**: un egreso a
un profesional toca `billing` y la **identidad del colaborador**, que vive en `organization`.

Lo que la etapa consume:

| Desde | Hacia | ¿Arista nueva? |
|---|---|---|
| `billing.application` | `organization.spi.ConsultorioDirectory`, `PermissionGuard`, `PermissionQuery` | **No.** `billing` depende de los tres desde 07.01 |
| `billing.application` | `organization.spi.ConsultorioMembershipDirectory`, `ConsultorioMembershipSnapshot` | **No a nivel de módulo**: `billing → organization.spi` ya existe. Son tipos nuevos de un paquete ya alcanzado |
| `billing.application` | `platform.spi.identity.AccountIdentityDirectory` | **No a nivel de módulo**: `billing → platform.spi` ya existe (`AuditTrail`, `ProblemType`) |

**Cero aristas nuevas entre módulos.** Y la que parecía peligrosa —resolver el nombre del
colaborador— resulta ser la que *no* se toma: **`billing` no importa `identity`**, ni directa ni
indirectamente, porque `AccountIdentityDirectory` es un **puerto invertido declarado en
`platform.spi`** que `identity` implementa. Es el mismo mecanismo por el que `organization` puede
mostrar el nombre de un colaborador sin cerrar el ciclo `organization → identity`. Las dos flechas
apuntan a `platform`, que no depende de nadie.

La arista que **no** se toma, y que habría sido el error: `billing → organization.domain` para leer
`Membership`, o `billing → identity.spi` para el nombre. Las dos están prohibidas y las dos son la
forma obvia de resolver el problema si uno no mira el grafo.

> **Y esto no se da por verificado razonando.** La afirmación es fuerte —*cero aristas nuevas*— y
> es la clase de afirmación que 04.05 hizo y que era falsa. `SlicesRuleDefinition` busca ciclos de
> **cualquier longitud**; la cabeza encuentra los de dos. **`ModuleArchitectureTest` se corre
> después de cada bloque y el veredicto es el suyo, no el mío.**

**Veredicto: pasa, sujeto a que lo confirme ArchUnit en cada bloque.**

---

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí, las dos, y `consultorio_id` también: **un egreso se paga desde la caja de una sede**, igual que
la jornada de 07.03 y que la obligación de 07.01.

`pago_egreso` lleva `organization_id` **aunque se pueda llegar por `egreso_id`**. ADR-0004 dice "sin
excepción" y es precisamente por esto: una consulta que se olvide del JOIN cruzaría tenants sin
fallar. Misma convención que `cobro_medio` (V37) y `movimiento_caja` (V54).

Los uniques, todos empezando por el tenant:

- `UNIQUE (organization_id, beneficiario_clave, comprobante_tipo, comprobante_numero, anulado_key)`
- `UNIQUE (organization_id, idempotency_key)` en las dos tablas

Los índices también: los tres de `egreso` y el de `pago_egreso` empiezan por `organization_id`.
**Ningún unique ni índice sin `organization_id`.**

Y del lado del comportamiento: cross-tenant → **404**, nunca 403 —un 403 confirma que el egreso
existe y bastaría probar ids consecutivos—; falta de contexto → **403**, nunca 401 —un 401 deja al
frontend en bucle de login—. Las dos las resuelve `CajaAcceso`, que ya existe y ya las hace bien.

**Veredicto: pasa.**

---

## 4. Reglas maestras — ¿el diseño confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?

**Es *la* pregunta de esta etapa**, y la trampa es simétrica a la de 07.03: allá era colapsar la
caja en un `SUM` sobre los cobros; acá es colapsar el egreso en un movimiento de caja.

### Las tres cosas, con su prueba estructural

| | Qué es | Prueba de que no se confunde |
|---|---|---|
| **`egreso`** (el compromiso) | El centro **debe** plata a alguien | No tiene `movimiento_caja_id`, no tiene `jornada_caja_id`, y **existe sin que se haya movido un peso**: un egreso `CONFIRMADO` con `saldo_pendiente = importe_total` es una deuda viva |
| **`pago_egreso`** (el acto) | El centro **pagó**, total o parcialmente | Tiene su propio estado y su propia anulación. **Varios pagos por egreso**: si fueran lo mismo, el pago parcial sería irrepresentable |
| **`movimiento_caja`** (el hecho) | Salió plata de un cajón, en una jornada, con un responsable | Es el que apunta al pago, no al revés. Un pago por transferencia produce un movimiento que **no afecta el arqueo**: la plata nunca estuvo en el cajón |

**La relación no es uno a uno y el diseño lo hace explícito por construcción:** un egreso puede
tener cero pagos (deuda viva), uno, o varios (parcial); un pago produce exactamente un movimiento;
y una anulación de pago produce **otro** movimiento —la reversión— sin que el pago desaparezca ni
el egreso cambie de importe. Si alguna de esas tres fuera uno a uno con otra, el modelo estaría
confesando que son la misma cosa.

### La otra confusión disponible: Sesión

**`egreso` no tiene `sesion_id`, ni `obligacion_id`, ni `persona_id`, ni `cobro_id`.** RN-M22-003
dice que pagar a un profesional no modifica sesiones históricas, y la forma fuerte de cumplir una
regla así no es escribir un `if`: es que el modelo **no tenga cómo** nombrar la sesión. Como
contrapartida, esta etapa no puede calcular una liquidación desde las sesiones — y no debe: el plan
dice *"sin inventar regla remunerativa"*, y RF-M22-006/007 son de la segunda entrega.

### Dónde el diseño se aparta de la letra, y por qué

RN-M22-001 dice *"egreso confirmado afecta caja"*, y acá lo que mueve la caja es el **pago**. Se
adopta la lectura *"sólo un egreso confirmado puede mover la caja; un borrador no afecta nada"*,
por dos razones que la letra estricta vuelve contradictorias con el resto del documento:

1. Si confirmar sacara la plata, **un egreso debido y no pagado no podría existir**, que es la
   mitad del objetivo de M22.
2. El **pago parcial** está declarado como caso borde de la propia etapa, y no se puede sacar media
   vez la plata de un cajón.

Una regla que vuelve inexpresable un caso borde que el mismo documento declara se está leyendo mal.
Queda escrito acá y en el diseño §3 para que nadie lo descubra después como un olvido.

**Veredicto: pasa.**

---

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

No, y no hay `DELETE` por ningún camino: los puertos no declaran `delete`.

- **`egreso` anulado conserva la fila entera**, con `anulado_en`, `anulado_por_cuenta_id` y
  `motivo_anulacion`. RN-M22-002 es literal: *anular no significa borrar*.
- **`pago_egreso` anulado también.** No se borra la fila: cambia de estado y gana el motivo. El
  historial de "se pagó el 10 y se anuló el 12" es exactamente lo que una auditoría busca.
- **El movimiento de caja no se toca nunca.** La corrección es compensación: otra fila de tipo
  `REVERSION_DE_EGRESO`, con motivo y puntero al original, en la jornada abierta hoy. Es el diseño
  de 07.03 y se reusa tal cual.
- **El cuarteto `active`/`deleted_at`/`deactivation_reason`/`deleted_key` no aplica** a ninguna de
  las dos tablas, y no es un olvido: el ciclo de vida no es alta/baja sino
  borrador/confirmado/anulado, igual que `jornada_caja` es abierta/cerrada. Forzar el cuarteto
  agregaría dos formas de que una fila esté "no vigente" y la primera consulta que se olvide de una
  daría un total equivocado.

El punto donde esto se podía arruinar: **editar el egreso después de confirmarlo**. Un importe que
cambia debajo de pagos ya asentados haría que `saldo_pendiente` dejara de reconciliar con el ledger,
sin que nada fallara. El diseño lo prohíbe con estado y con 409, y `egreso` congela beneficiario,
importe y comprobante al confirmar.

El otro: el unique del comprobante lleva `anulado_key` con el centinela `'1970-01-01'`
precisamente **para no convertir la baja lógica en una trampa**. Sin él, anular un egreso cargado
por error dejaría ese número de factura quemado para siempre y el operador no tendría forma de
rehacerlo.

**Veredicto: pasa.**

---

## 6. Contrato — ¿el cambio de API es aditivo o incompatible? ¿Requiere versión mayor?

**Aditivo. `0.39.0`, minor.** Ocho operaciones nuevas bajo un prefijo nuevo
(`/api/v1/consultorios/{consultorioId}/egresos`), ningún path existente cambia de forma, ningún
campo se quita ni se renombra, ningún tipo de problema existente cambia de significado.

**Pero hay un cambio de comportamiento sobre una operación de 07.03 que no se esconde detrás de
"aditivo":**

`POST /caja/movimientos/{movimientoId}/reversion` **deja de exigir caja abierta cuando el
movimiento original no era en efectivo**. Hoy la exige siempre, y eso contradice el §7 del propio
diseño de 07.03, que dice *"si no hay jornada abierta **y el movimiento a revertir era en
efectivo**, la reversión se rechaza"*. Es un defecto heredado que esta etapa destapa porque es la
primera que revierte movimientos no-efectivo con frecuencia.

Es un cambio que **amplía** lo aceptado y nunca lo rechazado: ningún caso que hoy funciona deja de
funcionar, y ningún cliente puede depender de recibir un 409 donde ahora recibe un 201. No requiere
versión mayor.

> **Deuda declarada, igual que en 07.03:** el contrato **no se regenera** en esta etapa porque
> `OpenApiContractIT` es un test de integración y Docker no arranca. `pom.xml`, `application.yml` y
> el `info.version` dicen `0.39.0`; los `paths` siguen siendo los de la última regeneración. **El
> YAML no se edita a mano para tapar el drift** — eso convertiría un gate que avisa en un gate que
> miente.

**Veredicto: pasa, con la deuda de regeneración declarada.**

---

## 7. Ruta crítica — ¿este módulo tiene todos sus cimientos, o lo estoy adelantando?

La ruta de `AGENT.md` §9 termina en `… → Obligación → Cobro → Caja → Presentación/Reportes`. El
egreso cuelga de **Caja**, y la caja está construida.

| Cimiento | Estado |
|---|---|
| **AKINE-07.03** — caja, jornada, movimiento, reversión | Cerrada en el backend; **es la rama de la que esta sale**. `MovimientoCajaService.asentar` y `.revertir` existen y se reusan |
| **AKINE-02.03** — ciclo de vida de colaboradores | Cerrada. `ConsultorioMembershipDirectory` resuelve el beneficiario |
| **AKINE-01.03** — permisos por membership | Cerrada. `caja:operate` ya tiene asignación base desde 07.03 |
| **AKINE-01.02 / platform** — auditoría e identidad | Cerradas. `AuditTrail` y `AccountIdentityDirectory` |

**Lo que sí se estaría adelantando si se hiciera, y por eso no se hace:** calcular la liquidación
desde las sesiones. Eso necesita M28/M29 (clases y participación) para RF-M22-005/006 de la segunda
entrega, y necesita una política de pago por contrato que **no existe en ninguna parte del
sistema**. El plan lo dice: *"las políticas de liquidación deberán configurarse por
contrato/profesional"* — configurarse, no inventarse acá.

El otro adelanto disponible: el **adjunto binario**. No se hace, y el motivo no es pereza sino que
esta etapa sería el tercer consumidor del storage duplicado, cuya condición de salida está escrita
desde 04.02 (*extraer a `platform.spi`*). Meter esa extracción de contrabando dentro de M22
refactorizaría dos módulos cerrados —`person` y `clinical`— en la misma etapa que introduce un
modelo nuevo. Es exactamente el error estructural 1 de `AGENT.md` §9 en versión inversa.

**Veredicto: pasa. Y la parte que no tiene cimientos queda afuera, nombrada.**

---

## 8. El caso que rompe el diseño

### El caso

**El pago en efectivo que no entra en el cajón, con dos operadores.**

Fin de mes. La caja de la sede tiene 80.000 en efectivo. Dos administrativos, en dos computadoras,
registran al mismo tiempo el pago en efectivo de dos liquidaciones de 50.000 cada una. Los dos ven
80.000 disponibles y los dos aprietan confirmar.

Si los dos entran, la caja queda en **−20.000**, que es un estado que el mundo físico no admite: no
se pueden sacar 100.000 pesos de un cajón que tiene 80.000. Y lo peor no es el número: es que el
arqueo de esa noche registraría una diferencia de 20.000 **sobre un faltante que nunca existió**,
y alguien tendría que escribir un motivo justificando un desvío inventado.

Y hay una segunda mitad: aunque el saldo alcanzara, si el egreso se descontara dos veces —dos pagos
de 50.000 contra una liquidación de 50.000— el profesional cobraría el doble y
`saldo_pendiente` quedaría en −50.000.

### Cómo lo resuelve el diseño

**Con dos condiciones de `WHERE`, sin un solo lock y sin ninguna lectura previa.** Son dos porque
son dos invariantes distintos y cada uno tiene su dueño:

```sql
-- 1. La deuda no se paga dos veces. Dueño: egreso.
UPDATE egreso SET saldo_pendiente = saldo_pendiente - :importe
 WHERE id = :egresoId AND organization_id = :org
   AND estado = 'CONFIRMADO' AND saldo_pendiente >= :importe;

-- 2. El cajón no queda en rojo. Dueño: jornada_caja (07.03, reusado tal cual).
UPDATE jornada_caja SET saldo_arqueo = saldo_arqueo - :importe
 WHERE id = :id AND organization_id = :org
   AND estado = 'ABIERTA' AND saldo_arqueo >= :importe;
```

En el escenario de arriba: el primer pago descuenta y el segundo afecta **cero filas** en la
segunda condición → **409 `caja-saldo-insuficiente`**, con `saldoDisponible` en el Problem Detail
para que la pantalla pueda decir *"en el cajón quedan 30.000"* en vez de *"no se puede"*. La
transacción entera revierte: no queda un `pago_egreso` huérfano describiendo un pago que no
ocurrió, ni un `saldo_pendiente` descontado contra plata que no salió.

**Cero filas es la respuesta, no un error técnico.** Y se desambigua releyendo —¿cerró la jornada o
no alcanzaba?—, que es seguro porque un `UPDATE` de cero filas **no marca la transacción para
rollback**, a diferencia de un `flush` fallido por constraint. Es la distinción que este
repositorio ya pagó cuatro veces.

Los dos `CHECK` de la base —`ck_egreso_saldo_no_negativo` y `ck_jornada_caja_saldo_no_negativo`—
respaldan las dos condiciones: si un camino futuro se olvida del `WHERE`, el motor lo frena en vez
de dejar el dato roto.

### Los otros tres casos borde declarados, y su respuesta

| Caso | Respuesta |
|---|---|
| **Profesional desvinculado** | La membership se valida **sólo al crear el borrador**. Confirmar y pagar un egreso viejo funciona: si se fue el 30 de septiembre, el centro le sigue debiendo septiembre, y lo contrario convertiría la desvinculación en una forma de no pagar. El nombre está congelado en el snapshot |
| **Caja cerrada** | Efectivo → 409 `caja-no-abierta`, igual que el cobro. No efectivo → el pago entra, con movimiento sin jornada, porque esa plata nunca tocó el cajón |
| **Período superpuesto** | **No se impide**, a propósito: dos liquidaciones del mismo mes son legítimas. El filtro por beneficiario y período las pone una al lado de la otra, que es la única respuesta honesta |
| **Reversión** | Anular el pago asienta `REVERSION_DE_EGRESO` **en la jornada abierta hoy**, nunca reescribiendo una jornada ya arqueada, y devuelve el saldo al egreso. Un movimiento se revierte una sola vez: lo garantiza el unique de V54 |

**Veredicto: pasa.**

---

## Veredicto global

Las ocho pasan. Dos de ellas con una condición escrita y no con un "ya está":

- **La 2** queda sujeta a `ModuleArchitectureTest`, que se corre después de cada bloque. La
  afirmación *cero aristas nuevas* es exactamente la clase de afirmación que 04.05 hizo de memoria
  y que era falsa.
- **La 6** arrastra la deuda de regeneración del contrato, que es del repositorio y no de la etapa,
  y **prohíbe** editar los `paths` del YAML a mano.

Se avanza a `tasks`.
