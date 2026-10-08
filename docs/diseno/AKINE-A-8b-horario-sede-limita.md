# AKINE-A-8b — El horario general de la sede limita la agenda

> Decisión **DP-19** (08/10/2026). Contrato **0.77.0** (aditivo). **Sin migración**: `V83`
> (A-8) ya tiene todo lo que hace falta. Rama `akine-A-8b-horario-sede-limita`.

## 1. La decisión y su relación con el RN

Hasta A-8 el horario general de la sede (`consultorio_horario`, dueño `resource`) era
informativo: la agenda no lo leía. DP-19 lo vuelve **techo**: ningún turno se ofrece ni se reserva
fuera del horario de la sede, aunque el profesional tenga disponibilidad cargada. **Sin horario
cargado no se limita nada** —las sedes anteriores a A-8 y las que se dieron de alta sin declararlo
siguen exactamente igual—.

**Discrepancia con RN-M03-004, documentada y no contradictoria.** El RN dice "el horario general
no reemplaza la disponibilidad individual de profesionales". A-8 lo había leído como "la agenda no
lo mira". DP-19 lo lee literalmente: el horario de la sede sigue sin **reemplazar** —no abre nada
que el profesional no tenga— y pasa a **recortar**. La disponibilidad efectiva queda
**intersectada**. El comentario de tabla de `V83` todavía dice "Informativo" y no se toca: es una
migración aplicada y cambiarla rompe el checksum de Flyway.

## 2. Diseño — un solo lugar

La intersección es la **quinta etapa** de `DisponibilidadEfectivaCalculator`, después de BASE,
APERTURA, FERIADO y CIERRE (`HorarioDeSede.limitar`, puro, en `resource.domain`). Va última porque
es un techo y no una regla del profesional. Que viva en el calculador —y no en cada consumidor— es
lo que hace que vean lo mismo:

| Consumidor | Cómo le llega |
|---|---|
| Disponibilidad efectiva (pantalla de M05) | `DisponibilidadEfectivaService.sinAutorizar` lee el horario vigente y lo pasa al calculador |
| Agenda (motor de slots, M12) | `DisponibilidadDirectory.efectiva` → el mismo `sinAutorizar` |
| Reserva, reprogramación y series | `RevalidadorDeSlot` → la misma costura. Fuera de horario es `SlotNoDisponibleException` → el mismo **409 `slot-no-disponible`** de siempre |
| Clases (`activity.ClaseService`) | la misma costura: una clase fuera del horario de la sede tampoco se programa |
| Consulta previa de impacto (A-11) | `SimuladorDeImpacto` corre el calculador con el horario vigente en las dos fotos |

**Ofertas sin profesional.** No pasan por la disponibilidad efectiva —no hay de quién
calcularla—. La agenda les sigue declarando `SIN_HORARIO` (no ofrece slots; eso no cambia), pero
la **reserva** por API no tenía ningún control de horario. Se agrega
`DisponibilidadDirectory.fueraDelHorarioDeSede(org, sede, inicio, fin)` y el revalidador la
consulta para esas ofertas: sin esto serían la única puerta para reservar fuera del horario. Que la
agenda use el horario de la sede para **ofrecer** slots de ofertas sin profesional sigue siendo la
decisión aparte que dejó A-8 (pendiente 13a).

**Reglas de la intersección** (`HorarioDeSedeTest`):

- Las franjas **contiguas** de la sede se unen antes de intersectar (09–13 + 13–17 = 09–17). Si
  no, el bloque 08–18 se parte en dos y el motor de slots, que corta cada franja por separado,
  pierde el turno de 12:30.
- Franja dentro del horario: intacta. Franja que lo excede: queda la parte común con
  `recortadoPor = HORARIO_SEDE`.
- Día con franjas del profesional y ninguna en común con la sede (incluye el día de la semana en
  que la sede no declaró franja): vacío con **`razonVacio = HORARIO_SEDE`**.
- Día que ya estaba vacío (feriado, cierre, vínculo, sin reglas): conserva su razón.

## 3. Motivo nuevo — aditivo

- `OrigenFranja.HORARIO_SEDE`: viaja como `razonVacio` y `recortadoPor` en la disponibilidad
  efectiva. Esos dos campos están documentados en prosa —no como enum— a propósito (OpenAPI 3.1
  con nullable), así que el valor nuevo no rompe ningún cliente generado.
- `MotivoSinSlots.FUERA_DE_HORARIO_SEDE` en la agenda (`motivoSinSlots`, que **sí** es enum). Se
  prefirió a reusar `CIERRE` o `SIN_HORARIO` porque los dos mandan al operador a la pantalla
  equivocada: `CIERRE` lo manda a buscar una excepción que no existe y `SIN_HORARIO` a cargar
  horario al profesional, que ya lo tiene. Lo que hay que revisar es el horario de la sede.

## 4. Turnos ya reservados — se informan, no se cancelan

Cambiar el horario de la sede puede dejar turnos pendientes fuera de horario. Mismo criterio que
RN-M05-004 para un cambio de disponibilidad: **se informan y no se cancelan**.

- El `PUT /consultorios/{id}/calendario` responde `impactoDelHorario`
  (`ImpactoDisponibilidadResponse`, el schema de A-11): cuántos, el primero, hasta 50 (sin datos
  del paciente) y `evaluadoHasta`. Se calcula **antes** de reemplazar, bajo el mismo lock de la
  política, con `SimuladorDeImpacto.deCambioDeHorarioDeSede`: todos los profesionales de la sede,
  desde hoy hasta 90 días, antes = disponibilidad limitada por el horario anterior, después =
  limitada por el nuevo.
- En el `GET` y en un `PUT` que no cambia el horario viaja en cero con `evaluadoHasta` nulo.
- **Límites declarados:** (a) un turno que ya estaba fuera del horario anterior no lo deja afuera
  este cambio y no cuenta; (b) la sonda de A-11 sólo devuelve turnos con profesional, así que un
  turno de una oferta sin profesional que queda fuera de horario **no se informa**; (c) cambiar
  `cierraPorFeriado` tampoco informa impacto (preexistente); (d) no hay consulta previa
  (`impacto-de-…`) del cambio de horario: sólo la respuesta del `PUT`. Es un endpoint aditivo si la
  pantalla lo pide.

## 5. Design challenge

1. **Ownership.** Ninguna tabla nueva. `consultorio_horario` sigue siendo de `resource`, y sólo
   `resource` la lee (servicio de disponibilidad efectiva, simulador y calendario).
2. **Ciclos.** Ninguna arista nueva entre módulos: `scheduling` y `activity` ya consumían
   `resource.spi.DisponibilidadDirectory`; el método nuevo vive en esa misma interfaz.
   `CalendarioService` → `SimuladorDeImpacto` es dentro de `resource`. ArchUnit en verde.
3. **Tenant.** Toda lectura del horario va por `findVigentes(organizationId, consultorioId)`.
4. **Reglas maestras.** Disponibilidad ≠ turno se mantiene: el horario recorta la oferta y la
   reserva revalida contra ella; ningún turno existente se toca.
5. **Baja lógica.** Nada se borra. Los turnos fuera de horario siguen vivos.
6. **Contrato.** Aditivo: valor nuevo en el enum `motivoSinSlots`, campo nuevo
   `impactoDelHorario` en `CalendarioSedeResponse`, valor nuevo documentado en prosa para
   `razonVacio`/`recortadoPor`. **Cambio de comportamiento, no de forma:** una sede con horario
   cargado deja de ofrecer y aceptar turnos fuera de él. El cliente TypeScript con un
   `Record<MotivoSinSlots, string>` exhaustivo va a pedir el texto del valor nuevo al regenerarse.
7. **Ruta crítica.** Todos los cimientos existen (A-8, A-11, E-2).
8. **El caso que rompe el diseño.** Una sede que declara su horario después de tener la agenda
   llena para los próximos meses, con un profesional que atiende hasta las 20 y la sede hasta las
   18. Resultado: desde el `PUT` la agenda deja de ofrecer 18–20, la reserva de ese tramo da 409
   `slot-no-disponible`, y los turnos ya dados de 18 a 20 **siguen vivos** y vienen contados y
   listados (hasta 50) en `impactoDelHorario` para que la sede decida reprogramarlos. Otro caso:
   una **APERTURA** de sede (por ejemplo, "este sábado abrimos") fuera del horario general queda
   recortada por la regla estricta de DP-19 —la sede tiene que ampliar su horario general—. Si se
   prefiere que una apertura de alcance sede amplíe el techo para ese día, es una decisión aparte y
   un cambio local en `HorarioDeSede`.

## 6. E2E del frontend

El sembrado de `appKine-web/e2e/support/sembrado.ts` no carga `horarioGeneral` (la sede sale del
registro, sin horario): **los E2E no cambian de comportamiento**. Sólo cambiaría un E2E que creara
la sede por la pantalla de alta con horario cargado y después reservara fuera de él; hoy no hay
ninguno.
