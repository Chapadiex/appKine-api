# Diseño — AKINE-04.05 · Integración y consumo de autorizaciones

> El *qué* normativo está en `docs/AKINE_IMPLEMENTATION_PLAN.md` §"Etapa AKINE-04.05"; las reglas
> vinculantes en `AGENT.md`. El desafío adversarial está en `docs/diseno/AKINE-04.05-challenge.md`
> y **manda sobre este archivo** donde discrepen.

Trazabilidad: RF-M17-001..008; RN-M17-001..008; RF-M11-007..008.

---

## 1. Qué cierra esta etapa

03.06 construyó órdenes y autorizaciones y su propio registro lo declaró sin rodeos: *"la
elegibilidad no tiene consumidor — `consultarElegibilidadAdministrativa` responde, pero ni turno ni
sesión ni obligación la consultan"*. 04.04 declaró lo mismo de su cantidad autorizada: se registra
**declarada**, y la costura real es ésta.

**Esta etapa estrena las dos y cierra F4.**

## 2. La decisión que ordena todo: el consumo es un hecho, no un contador

Hoy `autorizacion.cantidad_consumida` es una columna `int` que **nadie mueve**. Si esta etapa se
limitara a incrementarla, tendríamos un número sin historia: nadie podría decir *qué* la consumió,
*cuándo*, ni deshacer una sola unidad sin recalcular a mano.

**`V50` agrega `autorizacion_movimiento`, append-only**, con un movimiento por hecho:

| Campo | Para qué |
|---|---|
| `tipo` | `CONSUMO` \| `REVERSION` \| `RESERVA` \| `LIBERACION_DE_RESERVA` |
| `cantidad` | Siempre **positiva**. El signo lo da el `tipo`, no el número |
| `tipo_origen` + `referencia_origen` | Qué hecho lo produjo: `SESION` + `sesion_id` hoy |
| `motivo` | Obligatorio en `REVERSION` |

**El ledger es la fuente de verdad y `cantidad_consumida` queda como saldo materializado**,
actualizado **en la misma transacción** que el movimiento.

> **Esto parece contradecir a 04.04, que se negó a guardar `cantidad_realizada`, y no la
> contradice.** Allá el dueño del hecho era **otro módulo** (`encounter` sabe cuándo se cierra una
> sesión), así que la columna habría sido de `clinical` y sólo `encounter` podría mantenerla
> correcta. Acá el ledger y la autorización son **la misma tabla y el mismo módulo**: se escriben
> juntos o no se escribe ninguno. Es el mismo criterio con el que 07.01 materializó el saldo de la
> obligación. La diferencia no es de gusto: es **quién puede garantizar la coherencia dentro de una
> transacción**.

Y hay una razón práctica que sola alcanzaría: `cantidad_consumida` **ya tiene consumidores** — la
elegibilidad de 03.06 la lee. Quitarla sería romper una etapa cerrada para ganar pureza.

## 3. El saldo nunca es negativo, y no lo garantiza un `if`

```sql
UPDATE autorizacion
   SET cantidad_consumida = cantidad_consumida + :cantidad
 WHERE id = :id AND organization_id = :org
   AND cantidad_autorizada - cantidad_consumida >= :cantidad
```

Cero filas afectadas significa "no hay saldo", y lo decide la base. **Es exactamente lo que 07.02
hizo para imputar un cobro** —*"imputar es un `UPDATE ... WHERE saldo >= :importe`, no un lock: es
una resta que no puede pasar de cero, y eso una condición lo expresa"*—, y es el caso borde que el
plan nombra: **"última unidad concurrente"**. Dos sesiones peleando por la última unidad: una gana,
la otra ve cero filas. Sin lock, sin deadlock posible.

## 4. La idempotencia es un unique, no un chequeo

`UNIQUE (organization_id, autorizacion_id, tipo, tipo_origen, referencia_origen)`.

Un reintento del mismo cierre choca y **se responde con el movimiento que ya existe**, no con 409.
Es la misma forma que la subida idempotente de adjuntos. El `tipo` entra en la clave a propósito:
una `REVERSION` del mismo origen es **otra** fila, no un duplicado — y una segunda reversión del
mismo origen sí es un duplicado y choca.

**La reversión no borra nada** (RN de la etapa, y regla maestra 10): compensa con un movimiento
propio y devuelve el saldo con el `UPDATE` inverso, en la misma transacción.

## 5. Cuándo se consume, y el error que sería consumir al reservar

**No se consume al reservar un turno.** Lo dice la etapa y lo dice DP-05: ninguna transición
administrativa prueba que una prestación ocurrió. Un turno que después se cancela habría comido una
unidad que el paciente nunca usó.

**Se consume al cerrar la sesión**, que es el hecho facturable, por un
`encounter.spi.CierreDeSesionObserver` implementado en `person.infrastructure` — la misma costura
que `billing.ObligacionDevengador` usa desde 07.01.

`RESERVA` y `LIBERACION_DE_RESERVA` existen en el `tipo` **y ningún camino las emite hoy**: son la
"regla explícita" que el enunciado admite, y el modelo las contempla para no tener que migrarlo
después. Se corta alcance, no modelo.

### 5.1 Un cierre sin saldo NO hace fallar el cierre

Ésta es la decisión más importante de la etapa y va contra el precedente de `billing`.

`ObligacionDevengador` **hace fallar el cierre clínico** si no puede devengar, y está bien: una
prestación sin deuda es plata perdida. **Acá es al revés.** La atención **ocurrió**: el
kinesiólogo atendió, el paciente estuvo. Que el financiador no tenga saldo es un problema
administrativo que se resuelve después —con otra autorización, o facturándole al paciente— y
**bloquear el cierre de una historia clínica por eso es exactamente lo que DP-06 prohíbe.**

Entonces: el observador **no lanza**. Registra el intento y su desenlace, y el saldo insuficiente
sale por la elegibilidad y por el listado de movimientos, no por una excepción. **Un observador que
falla hace fallar el cierre; éste, por diseño, no falla.**

## 6. Elegibilidad, ahora con saldo

`consultarElegibilidadAdministrativa` de 03.06 responde sobre requisitos. Esta etapa le agrega
**saldo y vigencia** y la vuelve la consulta que el frontend necesita para el "selector explicable"
que pide el enunciado: qué autorizaciones sirven, cuántas unidades quedan y **por qué** una no
sirve.

> **El hueco que el challenge de 04.04 dejó anotado y que hay que mirar acá:** el avance del plan
> cuenta **por oferta**, y la autorización es **por práctica** (`practica_id`). Son dos
> granularidades. Esta etapa **no las unifica**: compara lo que puede comparar y declara lo que no.
> Unificarlas es 06.04 (tratamientos realizados).

## 7. Permisos

Sin permisos nuevos. El enunciado pide "administrativo gestiona, profesional consulta lo
necesario": hoy eso lo da la matriz existente —las autorizaciones son de `person` y se gestionan
con los permisos que 03.06 fijó—. El consumo **no lo dispara una persona**: lo dispara el cierre de
sesión, así que no tiene endpoint de escritura propio salvo la reversión, que sí es un acto
humano y exige motivo.

## 8. API — contrato `0.33.0`

```
GET    /api/v1/personas/{id}/autorizaciones/elegibles
GET    /api/v1/autorizaciones/{id}/movimientos
GET    /api/v1/autorizaciones/{id}/saldo
POST   /api/v1/autorizaciones/{id}/reversiones
POST   /api/v1/planes-tratamiento/{id}/autorizacion
```

La última es RF-M11-007: vincular la autorización al ítem del plan, que es lo que convierte la
`cantidad_autorizada` declarada de 04.04 en una referencia real.

`ProblemType` nuevos: `autorizacion-sin-saldo`, `autorizacion-vencida`,
`movimiento-ya-revertido`, `reversion-sin-motivo`.

## 9. Lo que esta etapa NO hace

- **No consume al reservar turno** (§5).
- **No unifica oferta y práctica** (§6).
- **No factura** — el consumo de autorización y la obligación económica son dos cosas y se
  devengan por dos observadores distintos.
- **No hay frontend**, por la misma causa que 04.02, 04.03 y 04.04.

## 10. Migración y bloqueo heredado

`V50`, propietario `person`: `autorizacion_movimiento`.

Docker sigue caído. El contrato queda en `0.33.0` con **cuatro** tandas sin regenerar. Condición de
salida, sin cambios: **una sola regeneración desde la rama que las tenga todas**, antes de cualquier
merge.
