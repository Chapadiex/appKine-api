# AKINE-06.06 — Design challenge

Revisión adversarial del diseño de `AKINE-06.06-enmiendas.md`, según `CLAUDE.md` §3.
**El challenge manda sobre el diseño:** lo que se corrige acá se corrige allá.

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Otro la toca?

**`sesion_version` es de `encounter`**, el mismo módulo que es dueño de `sesion` y de
`sesion_numerador`. Ningún otro módulo la lee ni la escribe.

La tentación que había que mirar es la simétrica de la que miró 04.02, y conviene dejarla escrita
porque el próximo que lea esto la va a tener:

> *"`clinical` ya tiene `entrada_clinica_version`. Que la sesión enmendada escriba una entrada
> clínica versionada y listo, una sola implementación."*

**No.** Sería el mismo error que `SesionEventoContributor` ya rechazó en 04.02: la evolución
viviría duplicada en `sesion.evolucion` y en `entrada_clinica_version.cuerpo`, con dos módulos
dueños de la misma verdad clínica y la garantía de que en algún momento discrepan. Además el
contenido de una sesión es **estructurado** —EVA, evolución, tolerancia, próxima conducta— y el de
una entrada clínica es **un texto**: meterlo ahí lo convertiría en prosa inconsultable, y 06.02
tipó esos campos justamente para que se puedan comparar entre sesiones.

La tabla nueva **toca `sesion`** con una columna (`ultimo_numero_version`), y `sesion` es del mismo
módulo. Correcto.

**`clinical` sigue sin enterarse de que esto existe.** No hay un `spi` nuevo, ni un evento, ni un
contribuyente al timeline por enmienda: una enmienda no es un hecho clínico nuevo, es el mismo
hecho mejor contado, y agregarla al timeline mostraría dos filas donde pasó una cosa.

---

## 2. Ciclos — ¿la dependencia que introduce es unidireccional? ¿Pasa ArchUnit?

**No introduce ninguna arista entre módulos que no exista ya.**

| Arista | ¿Nueva? |
|---|---|
| `encounter.application → platform.spi.audit` | **No**: `encounter.api → platform.spi.problem` y `encounter.api → platform.spi.tenant` ya existen. `platform` es la base del grafo y no alcanza a ningún módulo de negocio |
| `encounter → clinical.spi`, `organization.spi`, `offering.spi`, `person.spi`, `scheduling.spi` | Todas preexistentes. La enmienda no agrega ninguna |
| Cualquier arista **hacia** `encounter` | Ninguna nueva. `billing` sigue siendo el único que lo escucha, por `CierreDeSesionObserver` |

> **No se razonó el grafo de memoria, y es a propósito.** El challenge de 04.05 dio por verificado
> que una arista no cerraba ningún ciclo y era falso: `SlicesRuleDefinition` busca ciclos de
> **cualquier longitud** y la cabeza encuentra los de dos. Acá **no hace falta sonda porque no hay
> arista nueva que sondear**: la tabla de arriba no lista ninguna. La verificación es
> `ModuleArchitectureTest` sobre el código ya escrito, y **no se declara verde hasta haberlo
> corrido** — el resultado va al registro de cierre, no acá.

Las capas también: `domain` no importa nada de `application` ni de `api`, el servicio nuevo vive en
`application`, el repositorio en `infrastructure` y su puerto en `domain`, como manda la regla que
01.01 fijó.

---

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques lo incluyen?

Sí, y **es derivable de `sesion_id` y va igual**, por el mismo argumento que `entrada_clinica_version`
escribió en 04.02: derivarlo obliga a un join para filtrar por tenant, y el día que alguien escriba
la consulta sin el join tiene una fuga que **ningún test de la etapa ve, porque los tests de una
etapa corren con un solo tenant**.

- `uk_sesion_version_numero (organization_id, sesion_id, numero_version)` — empieza por el tenant.
- No hay ningún otro índice: ese unique **es** el índice del único camino de lectura (las versiones
  de una sesión, ordenadas por número).
- La FK `fk_sesion_version_organization` existe además de la de `sesion`, igual que en `V45`.

La lectura del historial resuelve la sesión primero por `findByIdInScope(organizationId,
consultorioId, sesionId)` —que es la consulta que ya acota por tenant **y** por sede— y recién
después busca las versiones. Una sesión de otro tenant es **404**, indistinguible de "no existe",
que es la regla desde 01.01.

---

## 4. Reglas maestras — ¿el diseño confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?

| Regla | Cómo la respeta |
|---|---|
| **1.** HC ≠ Caso ≠ Sesión | `sesion_version` cuelga de la **sesión**, no de la historia ni del caso. Una versión es el contenido de **una atención** |
| **3.** Las sesiones se numeran dentro del Caso | **No se toca ninguno de los dos correlativos.** Es lo que 04.03 rechazó explícitamente y lo que 06.05 dejó fijado |
| **4.** Turno es reserva, Sesión es atención | La enmienda no mira el turno ni lo modifica. Una sesión sin turno se enmienda igual |
| **5/6/7.** Sesión → obligación ≠ cobro ≠ caja | **Ver §5 del diseño: la enmienda no roza lo económico.** No llama observadores y no puede cambiar `asistencia` ni `ofertaId` |
| **10.** Nada histórico se borra | Ninguna versión se borra ni se da de baja. La tabla **no tiene** `active` ni `deleted_at`, y no es una omisión: no existe ese estado |
| **11.** Multi-tenancy | §3 |
| **12.** El backend es la autoridad | El motivo lo exige la **entidad**, no el DTO: un camino futuro que no pase por el controller falla igual |

El punto que más se podía romper era el 5/6/7, y el diseño no lo resuelve con disciplina del
servicio sino con **el tipo del request**: no hay campo para la asistencia, así que no hay código
que pueda moverla.

---

## 5. Baja lógica — ¿hay borrado físico de información histórica relevante?

**No hay un solo `DELETE` en esta etapa**, ni físico ni lógico.

- Las versiones no se dan de baja. Una versión es un hecho pasado; desactivarla sería reescribir
  historia clínica (ADR-0011).
- El original nunca se pisa: la enmienda es un `INSERT`.
- La única columna que cambia en `sesion` es el contador `ultimo_numero_version` (más el
  `@Version` de JPA). **Las columnas clínicas de `sesion` sí se sobrescriben con el contenido
  vigente** — y ahí hay que ser honesto, porque es la única concesión del diseño:

> **La concesión, declarada.** `sesion` guarda el contenido **vigente** y `sesion_version` guarda
> **todas** las versiones incluida la vigente. Eso es una duplicación: el contenido de la última
> versión existe en dos lugares. Se acepta porque la alternativa era peor: mover el contenido fuera
> de `sesion` rompe a todos los lectores que ya existen —`findPreviaEvaluada`,
> `contarCerradasPorOferta`, `SesionResponse`, el índice `ix_sesion_comparacion`— y obliga a una
> migración de datos que esta etapa no puede verificar contra ningún motor. La duplicación es
> **segura porque las dos escrituras van en la misma transacción y no hay ningún otro camino que
> escriba una sin la otra**; y el original, que es lo que la regla maestra 10 protege, está en la
> v1 y nunca se toca. Si algún día el contenido se muda a la versión, la v1 ya está escrita para
> todas las sesiones y la migración es un `UPDATE` de lectura, no una reconstrucción.

---

## 6. Contrato — ¿aditivo o incompatible? ¿versión mayor?

**Aditivo.** `0.33.0 → 0.35.0`, minor. (`0.34.0` la reserva otra etapa en vuelo.)

- Dos operaciones **nuevas**. Ninguna existente cambia de forma, de verbo ni de ruta.
- `SesionResponse` gana dos campos opcionales (`ultimoNumeroVersion`, `fueEnmendada`). Un cliente
  viejo los ignora.
- Un `ProblemType` **nuevo** (`sesion-no-cerrada`). Agregar un valor al enum publicado es aditivo;
  sacarlo sería incompatible. Ningún type existente cambia de significado.
- Las dos operaciones devuelven cuerpo, así que **no aplica** la trampa de las 204 que rompió
  activación de cuenta: no hay ninguna respuesta sin cuerpo en esta etapa.

**Lo que no se puede declarar verde:** el YAML no se regeneró. `OpenApiContractIT` va a reportar
drift —igual que ya lo hace por las cuatro etapas de F4— hasta que alguien corra
`./mvnw verify -Dakine.contract.update=true` desde la rama que tenga todo.

---

## 7. Ruta crítica — ¿están los cimientos, o lo estoy adelantando?

Están, y es la última pieza de M14.

`Persona → HC → Caso → Sesión → cierre → obligación` corre entero en el backend. 06.06 no agrega un
eslabón nuevo a esa cadena: **le agrega una segunda dimensión —el tiempo— al eslabón que ya
existe**. Depende de 06.05, que está cerrada, y de nada más.

Lo que **sí** se está adelantando y se declara: RF-M24-005 pide "mostrar enmiendas clínicas" y lo
que esta etapa entrega es el endpoint, no la pantalla. M24 es una etapa de frontend que no existe.
El backend queda listo y el consumidor no.

Y una advertencia de orden, porque esta rama no es la única en vuelo: `V51`, `V52` y `V54` son de
otras etapas. **`V53` se reservó antes de escribir código**, que es la lección que `V26` dejó cuando
02.07 y 03.01 nacieron las dos con el mismo número y Flyway no arrancó.

---

## 8. El caso que rompe el diseño

> **La sesión se cerró con `AUSENTE` y el paciente sí había venido.**

Es el caso que rompe el diseño, y lo rompe de la peor manera: **no se puede arreglar enmendando.**
`asistencia` está congelada por decisión (§5 del diseño), así que la historia clínica queda diciendo
que el paciente no vino, y no hay ninguna operación de esta etapa que lo corrija. Peor: es
exactamente el error que un profesional apurado comete —cerrar con el valor por defecto— y el que
más caro sale, porque además no devengó la deuda ni consumió la autorización.

No se disimula con una excepción al diseño. **Se resuelve declarando que no es una enmienda.**

Cambiar la asistencia es un acto **económico**, no clínico: obliga a devengar una obligación que no
existe (M18) o a anular una que sí, y a consumir o revertir una unidad de autorización (M17, que ya
tiene su endpoint de reversión desde 04.05). Son compensaciones **explícitas**, que es literalmente
lo que el plan pide para esta etapa —"efectos derivados requieren compensación explícita"— y tienen
dueño en dos módulos que no son este. Implementarlas acá sería una tercera copia de la misma regla,
escrita por quien no es dueño de ninguna de las dos tablas, y con la garantía de divergir de las
otras dos.

**Lo que sí puede hacer el profesional hoy, y no es nada:** enmendar la nota de cierre dejando
escrito que el paciente asistió y que la asistencia quedó mal registrada, con ese motivo. El relato
clínico queda correcto y trazado, con autor y fecha. Lo que queda pendiente es el hecho
administrativo, que necesita a alguien con permiso económico.

**Alternativas que se descartaron, y por qué:**

| Alternativa | Por qué no |
|---|---|
| Permitir enmendar `asistencia` y re-disparar los observadores | **Es la alternativa que casi convence, y por eso se verificó en vez de suponerse.** Los dos observadores **sí** son idempotentes: `ObligacionDevengador` consulta `findPorPrestacion(sesionId, PACIENTE)` antes de insertar, respaldado por el unique de `V36`, y `ConsumoDeAutorizacionEnCierre` lo es por `uk_autorizacion_movimiento_origen (organization_id, autorizacion_id, tipo, tipo_origen, referencia_origen)` de `V50`. Así que re-disparar **no** duplicaría la deuda de una enmienda que sólo corrige una nota. Se descarta igual, por dos motivos más finos: **(a) es asimétrico** —re-disparar sólo puede AGREGAR efectos económicos, nunca sacarlos: `PRESENTE → AUSENTE` deja la deuda y la unidad consumida exactamente como estaban, porque no existe ningún observador de "des-cierre"—, y **(b) la idempotencia del consumo es por autorización, no por sesión**: si entre el cierre y la enmienda cambió cuál es la autorización elegible —una nueva aprobada, la anterior vencida— el `origen` es el mismo pero el `autorizacion_id` es otro, el unique no choca y **se consume una segunda unidad**. Una operación que arregla la mitad de los casos y rompe otra es peor que una que declara no hacerlo |
| Permitir enmendarla y **no** disparar nada | Es peor que no permitirlo: la sesión diría `PRESENTE` y no habría deuda. Un agujero silencioso, que es exactamente lo que `CierreDeSesionObserver` fue diseñado para evitar |
| Anular la sesión y cerrar una nueva | Rompe el correlativo —o deja un hueco que parece una sesión borrada, o renumera— y las dos cosas están prohibidas desde 06.05 |

**El otro caso adverso, que sí resuelve:** dos profesionales enmendando la misma sesión a la vez.
Las dos leen `version = N` y `ultimo_numero_version = 1`, las dos ensucian la cabecera, una
commitea y la otra recibe `OptimisticLockException` → **409 `concurrent-modification`**. El perdedor
relee y vuelve a enmendar sobre la v2, que es lo correcto: apilar su texto sobre uno que nunca vio
sería peor que rechazarlo. El unique `(organization_id, sesion_id, numero_version)` está debajo como
red por si algún camino futuro esquiva el servicio — **red, no mecanismo**.

---

## 9. Veredicto

El diseño pasa, con **dos correcciones que el challenge impone sobre el borrador**:

1. **La duplicación de §5 se declara en el diseño y en el Javadoc de la entidad**, en vez de
   dejarla implícita. Un lector que encuentre el mismo dato en dos tablas tiene que encontrar
   también por qué, o lo va a "arreglar".
2. **El caso de la asistencia se documenta en el Javadoc del servicio y en la descripción OpenAPI
   de la operación**, no sólo acá. Quien use el endpoint tiene que enterarse de lo que **no** puede
   corregir antes de intentarlo, no con un 400.
