# Design challenge — AKINE-07.03

Paso obligatorio de `CLAUDE.md` §3. Ocho preguntas, respondidas por escrito antes de `tasks`.
**Este documento manda sobre `AKINE-07.03-caja.md`** donde discrepen.

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

`jornada_caja` y `movimiento_caja`: **propietario `billing`**, sin excepción. Ningún otro módulo las
lee ni las escribe, y ninguna vive detrás de un `spi`: **esta etapa no expone nada al resto del
sistema.**

El caso que había que mirar es al revés del habitual. La tentación no es que otro módulo escriba la
caja, sino que la caja **lea** hacia afuera para saber cuánta plata entró: leer `cobro_medio` para
sumar el efectivo del día en vez de asentar el movimiento al cobrar. Eso convertiría el saldo en una
consulta derivada de un agregado ajeno, y la caja dejaría de tener movimientos propios —que es
literalmente la regla maestra 8—. **No lo hace:** el movimiento se asienta cuando el cobro ocurre, y
después la caja no vuelve a mirar el cobro nunca.

El acoplamiento real es intramodular: `CobroService` llama a `CajaDeCobro`. Los dos están en
`billing.application`, en la misma transacción. No hay borde que cruzar.

**Veredicto: pasa.**

## 2. Ciclos — ¿la dependencia entre módulos es unidireccional? ¿Pasa ArchUnit?

**Esta etapa no introduce ninguna arista nueva entre módulos.** Consume únicamente:

- `organization.spi` → `ConsultorioDirectory`, `PermissionGuard`, `PermissionQuery`. `billing` ya
  depende de los tres desde 07.01.
- `platform.spi` → `ProblemType` y **`AuditTrail`**, que es la única que `billing` todavía no usaba.
  `platform` es la base del grafo y no depende de ningún módulo de negocio, así que no puede cerrar
  nada.

No hay `billing → clinical`, ni `→ encounter`, ni `→ person`, ni `→ contracting`. El módulo se
queda donde estaba.

**Y esto NO se da por verificado razonando el grafo.** Es el error que el challenge de 04.05 cometió
—dio por buena una arista porque "de memoria" no cerraba ciclo, y `clinical → person.spi` más
`encounter → clinical.spi` ya existían—. `SlicesRuleDefinition` busca ciclos de **cualquier
longitud**; la cabeza encuentra los de dos. Acá la afirmación es más fuerte y más fácil de comprobar
—*cero aristas nuevas*—, y se comprueba igual: **`ModuleArchitectureTest` se corre después de cada
bloque, y el veredicto es el suyo, no el mío.**

**Veredicto: pasa, sujeto a que lo confirme ArchUnit.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí, las dos, y `consultorio_id` también: **una caja pertenece a una sede**, no a la organización.
Una organización con dos sedes tiene dos cajones a distancia física, y un saldo que los sume no se
puede contar.

`movimiento_caja` lleva `organization_id` **aunque se pueda llegar por `jornada_caja_id`**. ADR-0004
dice "sin excepción" y es por eso mismo: una consulta que se olvide del JOIN cruzaría tenants sin
fallar. Misma convención que `cobro_medio` y `cobro_imputacion` en V37, donde ArchUnit ya lo exigió.
Y acá hay un motivo extra: `jornada_caja_id` **es nullable** (§4 del diseño), así que para los
movimientos no-efectivo sin jornada, `organization_id` no es una redundancia — es el único camino al
tenant.

Los uniques:

- `UNIQUE (organization_id, consultorio_id, abierta_marca)` — empieza por el tenant.
- `UNIQUE (organization_id, tipo, tipo_origen, referencia_origen, medio)` — empieza por el tenant.
- `UNIQUE (organization_id, idempotency_key)` — igual que `cobro` en V37.

Los índices también. **Ningún unique sin `organization_id`.**

**Veredicto: pasa.**

## 4. Reglas maestras — ¿el diseño confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?

Es *la* pregunta de esta etapa. Las tres cosas se mantienen separadas y cada una tiene una prueba
estructural:

| | Prueba de que no se confunde |
|---|---|
| **Obligación** (M18) | `obligacion` no gana una sola columna en esta etapa. La deuda no afecta la caja: **RN-M20-001**. Cerrar una caja no toca ningún saldo de deuda, y anular una obligación no genera movimiento. |
| **Cobro** (M19) | `cobro` no gana una sola columna. El cobro **no tiene `jornada_caja_id`**: es el movimiento el que apunta al cobro, no al revés. Un cobro puede existir sin jornada (no efectivo) y una jornada existe sin cobros. |
| **Caja** (M20) | `movimiento_caja` **no tiene `obligacion_id`**. La caja no sabe qué deuda se pagó y no tiene por qué saberlo: registra que entró plata, no por qué se debía. Quien quiera unir las dos puntas pasa por el cobro. |

**La relación cobro↔movimiento no es uno a uno, y el diseño lo hace explícito por construcción:**
un cobro con dos medios produce **dos** movimientos; un cobro íntegramente con tarjeta produce un
movimiento que **no afecta el arqueo**; un ingreso manual produce un movimiento **sin cobro**; una
reversión produce un movimiento **sin cobro nuevo**. Si la relación fuera uno a uno, el modelo
estaría confesando que caja y cobro son la misma cosa.

El otro riesgo era colapsar la caja en un `SUM` sobre `cobro_medio` —"la caja es el total cobrado en
efectivo del día"—. Eso hace desaparecer el egreso, el saldo inicial, el arqueo y la diferencia, y
convierte M20 en una vista de M19. **Rechazado en §1.**

**Veredicto: pasa.**

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

No, y más fuerte: **no hay borrado ni edición de ninguna clase.**

- `movimiento_caja` es **append-only**. Sin `version`, sin `updated_at`, sin `deleted_at`, y el
  puerto de persistencia **no declara `update` ni `delete`**. Un historial que se puede editar no es
  un historial. Mismo diseño y mismo argumento que `turno_evento`, `caso_evento`, `plan_evento` y
  `autorizacion_movimiento`.
- `jornada_caja` **no se borra ni se desactiva**: se cierra, y una cerrada no se reabre. El cuarteto
  `active`/`deleted_at`/… no aplica porque el ciclo de vida no es alta/baja, es abierta/cerrada.
- **La corrección es compensación.** Un movimiento erróneo se anula con otro movimiento de tipo
  `REVERSION_DE_*`, con motivo obligatorio y puntero al original. El original queda donde estaba,
  diciendo que ocurrió — que es lo que RF-M24-006 pide poder mostrar.

El punto donde esto se podía arruinar: **reescribir la jornada ya cerrada** cuando se revierte un
movimiento suyo. El diseño lo prohíbe (§7) y la compensación cae en la jornada abierta hoy. Sin esa
regla, un cierre histórico cambiaría de saldo declarado después de haber sido contado, y la
`diferencia` —la única evidencia de que hubo un desvío— se borraría sola.

**Veredicto: pasa.**

## 6. Contrato — ¿el cambio de API es aditivo o incompatible? ¿Requiere versión mayor?

**Aditivo. `0.36.0`, minor.** Siete operaciones nuevas, ningún path existente cambia de forma, ningún
campo se quita ni se renombra.

**Pero hay un cambio de comportamiento que hay que declarar y no esconder detrás de "aditivo":**
`POST /cobros` puede ahora devolver **409 `caja-no-abierta`** cuando el cobro incluye un medio
`EFECTIVO` y la sede no tiene jornada abierta. Estructuralmente es un código de error más en una
operación que ya devolvía 409 —un cliente generado no se rompe—, pero **funcionalmente un cobro que
antes entraba ahora puede no entrar**.

Se acepta con los ojos abiertos, por lo que dice §4 del diseño: efectivo que entra sin jornada es
plata en el cajón que ningún arqueo puede encontrar, y ese es el agujero exacto que M20 existe para
tapar. La alternativa —aceptarlo y registrarlo sin jornada— haría que el arqueo de esa sede no
cuadrara nunca y por una razón invisible.

**Consecuencia operativa que el frontend va a tener que resolver y que queda escrita acá:** la
pantalla de cobro tiene que ofrecer "abrir caja" ante ese 409, o el administrativo queda trabado sin
entender por qué. No es alcance de esta etapa (no hay frontend) pero sí es deuda declarada.

> **El contrato NO se regenera en esta etapa.** Docker no arranca y `OpenApiContractIT` es un test
> de integración. Se bumpea `pom.xml`, `application.yml` y el `info.version` del YAML; **los `paths`
> quedan en los de `0.29.0`**. El drift ya existía antes de esta etapa (el repo declara `0.33.0` con
> paths de `0.29.0`) y esta etapa lo hereda y lo agranda. Es lo que ordena el enunciado y está
> declarado como no verificado.

## 7. Ruta crítica — ¿este módulo tiene sus cimientos, o lo estoy adelantando?

La ruta de `AGENT.md` §9 es `… → Sesión → Obligación → Cobro → **Caja** → Presentación/Reportes`.
La Caja es el eslabón siguiente y **todo lo anterior está construido**: 06.05 cierra sesiones, 07.01
devenga obligaciones, 07.02 registra cobros con medios. `organization` da sede, permisos y zona
horaria; `platform` da auditoría y Problem Details.

No se adelanta nada: 07.04 (presentaciones) y 07.05 (egresos M22) dependen de esta, no al revés, y
esta etapa **no** implementa pedazos de ellas (§11 del diseño).

El único cimiento con el que hay que tener cuidado es la **zona horaria de la sede**, porque la
`fecha_negocio` depende de ella. Existe desde 02.01 (`ConsultorioSnapshot.timezone`, poblada por
`V16`/`V17`) y V16 ya nombra el "corte de caja" como su motivo. **Está.**

**Veredicto: pasa.**

## 8. El caso que rompe el diseño

### El cobro en efectivo que entra entre el conteo y el cierre

El administrativo abre el arqueo: la pantalla dice saldo teórico **$142.500**. Cuenta los billetes
—tarda cuatro minutos— y le dan $142.500. Escribe 142.500 y confirma. En esos cuatro minutos su
compañera cobró **$3.000 en efectivo** en la otra computadora de la misma sede.

Con un diseño ingenuo —`diferencia = declarado − saldo_arqueo_actual`— el cierre registra un faltante
de **$3.000** que **nunca existió**, RN-M20-004 exige un motivo por escrito para justificarlo, y el
operador tiene que inventar una explicación de un desvío que no ocurrió. Peor: los $3.000 están
físicamente en el cajón, así que la jornada siguiente también arranca mal. **El control se convierte
en generador de ruido, y un control que produce falsos positivos deja de leerse.**

Un `@Version` sobre `jornada_caja` **no lo atrapa**: las escrituras que mueven el saldo son SQL
nativo y no la incrementarían (§5 del diseño). Un lock pesimista tomado al empezar a contar tampoco:
serían cuatro minutos de lock bloqueando los cobros de toda la sede.

**Cómo lo resuelve el diseño.** El cierre lleva `saldoTeoricoEsperado`: el número que la pantalla
mostraba cuando el operador empezó a contar. La condición entra en el mismo `UPDATE` que cierra:

```sql
... WHERE id = :id AND estado = 'ABIERTA' AND saldo_arqueo = :saldoTeoricoEsperado
```

Cero filas → **409 `caja-saldo-cambio`**, con el saldo teórico actual en el cuerpo. El operador ve
"entraron $3.000 mientras contabas: ahora el teórico es $145.500", suma los tres billetes que están
ahí y confirma. **Sin diferencia, sin motivo inventado, sin lock y sin ventana.** Es control
optimista de concurrencia aplicado a la **cantidad que significa algo** en vez de a un número de
versión.

Y resuelve de paso el cierre concurrente que el plan pide en "Casos borde": dos cierres simultáneos,
el segundo afecta cero filas porque `estado` ya no es `'ABIERTA'` → 409 `caja-cerrada`.

### El caso que el diseño acepta que NO resuelve

**El operador que confirma el segundo intento sin volver a contar.** Nada impide que, ante el 409,
apriete de nuevo con el nuevo teórico y cierre en falso. Es un problema de proceso, no de esquema, y
el sistema no puede distinguir "recontó" de "aceptó el número". Lo que sí queda es el rastro: la
`diferencia` de ese cierre vale cero y los movimientos del período están todos en el ledger, así que
una auditoría posterior puede reconstruir qué entró en esos cuatro minutos. **Se declara, no se
finge resuelto.**

### Los otros dos que el plan nombra, y su respuesta corta

- **Movimiento tardío** — un cobro no efectivo que llega cuando la jornada del día ya cerró. No
  necesita jornada (§4), lleva su propia `fecha_negocio` y aparece en la operatoria del día sin
  alterar ningún arqueo ya cerrado.
- **Reversión posterior** — se compensa en la jornada abierta hoy, nunca reescribiendo la cerrada
  (§7). `movimiento_origen_id` cruza jornadas y es lo que RF-M24-006 pide mostrar.

---

## Veredicto global

Las ocho pasan. Dos quedan **condicionadas a evidencia**, no a razonamiento:

1. **§2 (ciclos)** — la afirmación "cero aristas nuevas" la confirma `ModuleArchitectureTest`
   corriendo, no este documento.
2. **§6 (contrato)** — el `0.36.0` no se puede regenerar sin Docker. El drift queda declarado y
   crece; regenerarlo es trabajo de quien pueda levantar el motor.
