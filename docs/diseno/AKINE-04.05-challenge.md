# Design challenge — AKINE-04.05

Paso obligatorio de `CLAUDE.md` §3. **Manda sobre `AKINE-04.05-consumo-de-autorizaciones.md`.**

---

## 1. Ownership — ¿qué módulo es propietario de la tabla nueva? ¿Algún otro la toca?

`autorizacion_movimiento`: **propietario `person`**, el mismo que ya posee `autorizacion` y
`orden_medica` desde 03.06. Ningún otro módulo la lee ni la escribe.

**El que hay que mirar es `encounter`**, porque el consumo lo dispara el cierre de sesión. **No la
toca**: implementa `CierreDeSesionObserver`, y quien escribe es `person`. Es la misma forma que
`billing.ObligacionDevengador` usa desde 07.01 — el módulo que reacciona es el que escribe en sus
propias tablas.

**Veredicto: pasa.**

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

Arista nueva: **`person → encounter.spi`**. Hoy no existe, y hay que confirmar que no cierra nada:
`encounter` **no importa `person`** (verificado), ni `person` está en su cadena. `billing` ya
depende de los dos y eso no forma ciclo porque nadie depende de `billing`.

Grafo resultante alrededor del cierre: `billing → encounter.spi`, `billing → person.spi`,
`person → encounter.spi`. **Unidireccional.**

**Veredicto: pasa.** Con una regla vinculante: el observador vive en `person.infrastructure` y
**sólo** habla con `encounter` por su `spi`. Si necesita un dato de la sesión que el `spi` no trae,
se agranda `SesionCerrada`, no se importa `encounter.domain`.

## 3. Tenant — ¿lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí, y encabeza el unique de idempotencia
`(organization_id, autorizacion_id, tipo, tipo_origen, referencia_origen)` y el índice de listado.

`organization_id` va **aunque sea derivable** de la autorización, por el mismo motivo de 04.02,
04.03 y 04.04: derivarlo obliga a un join para filtrar por tenant y habilita la consulta sin join
que ningún test de una etapa detecta, porque los tests de una etapa usan un solo tenant.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿confunde algo?

Tres riesgos, los tres reales:

1. **Turno ≠ Sesión (regla 4, DP-05).** Consumir al reservar sería tratar una transición
   administrativa como prueba de que la prestación ocurrió. **No se consume al reservar.**
2. **Obligación ≠ Cobro (reglas 6 y 7).** Consumir una autorización **no es** facturar. Son dos
   observadores distintos del mismo cierre, escriben en tablas distintas y **ninguno depende del
   otro**. Si el consumo fallara y la obligación no, o al revés, el sistema sigue siendo coherente
   porque son hechos independientes.
3. **Información histórica no se elimina (regla 10).** La reversión **compensa**, no borra.
   `autorizacion_movimiento` es append-only: sin `version`, sin `updated_at`, sin baja, y su puerto
   no declara `delete` ni `update`. Mismo diseño que `turno_evento`, `caso_evento` y `plan_evento`.

**Veredicto: pasa.**

## 5. Baja lógica — ¿hay borrado físico?

Ninguno. Ver punto 4.3.

**Veredicto: pasa.**

## 6. Contrato — ¿aditivo o incompatible?

Cinco operaciones nuevas. **Una existente cambia de forma**:
`consultarElegibilidadAdministrativa` gana saldo y vigencia en su respuesta.

**Agregar campos a una respuesta es aditivo** —un cliente viejo los ignora— así que **`0.32.0` →
`0.33.0`**, menor. Sería incompatible si se quitara o se renombrara algo, y no se hace.

**Y por cuarta vez queda sin regenerar.** El riesgo que crece con la acumulación sigue siendo el
mismo y conviene repetirlo: **cuantas más operaciones se apilan sin regenerar, menos sabemos si
springdoc desambiguó algún `operationId`** — problema que este repositorio ya tuvo y que hoy tiene
un gate que **no se puede correr**.

**Veredicto: pasa el cambio, no pasa la verificación.**

## 7. Ruta crítica — ¿tiene sus cimientos?

Dependencias declaradas: **04.04** (escrita, en la rama de la que ésta sale) y **03.06** (cerrada).
Están. Esta etapa **cierra F4**.

**Veredicto: pasa.**

## 8. El caso que rompe el diseño

**"Dos sesiones del mismo paciente cierran a la vez y queda una sola unidad autorizada. Después se
anula una de las dos."**

1. **Las dos consumen la última unidad.** Lo impide el `UPDATE ... WHERE cantidad_autorizada -
   cantidad_consumida >= :cantidad`: cero filas es "no hay saldo", decidido por la base. **Sin
   lock**, así que tampoco hay deadlock posible. Es el patrón de 07.02.
2. **La perdedora hace fallar el cierre clínico.** **No debe.** §5.1: la atención ocurrió. El
   observador no lanza; el saldo insuficiente es un desenlace, no una excepción. **Es la decisión
   que más se aparta del precedente de `billing`, y es deliberada.**
3. **El reintento del cierre duplica el movimiento.** Lo impide el unique de idempotencia, y el
   choque se resuelve devolviendo el movimiento existente, no con 409.
4. **La anulación posterior deja el saldo mal.** La reversión es un movimiento `REVERSION` con
   motivo **más** el `UPDATE` inverso en la misma transacción. Y **revertir dos veces el mismo
   origen choca contra el unique**, que es lo que hace la reversión idempotente en vez de
   meramente segura.

> **Quinta cosa, la que va a doler y no se resuelve acá:** si el `UPDATE` de saldo y el `INSERT` del
> movimiento divergen alguna vez —por un camino que escriba uno y no el otro—, **nada lo detecta**,
> porque no hay nadie recalculando el saldo desde el ledger. Mitigación posible y **no
> implementada**: un test de integración que, después de N movimientos, compare la suma del ledger
> contra la columna. **Queda escrito como lo primero a cubrir cuando haya Docker.**

**Veredicto: pasa, con las cinco anotadas.**

---

## Veredicto global

**Pasa.** Cuatro límites declarados:

1. **Oferta y práctica siguen siendo dos granularidades.** El plan cuenta por oferta, la
   autorización es por práctica. Se compara lo comparable y se declara lo que no. Unificar es 06.04.
2. **`RESERVA` y `LIBERACION_DE_RESERVA` existen en el modelo y ningún camino las emite.**
3. **El saldo materializado y el ledger pueden divergir sin que nada avise.**
4. **El contrato queda en drift por cuarta vez.**
