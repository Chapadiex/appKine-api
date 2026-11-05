# Agenda — disponibilidad central por persona

Rama `akine-agenda-disponibilidad`, contrato **0.158.0**. Migraciones `V141` y `V142` reservadas y **sin
usar** (ver `docs/fases/01-trabajo-en-paralelo.md` §6). Sigue a
[`AKINE-agenda-clases-y-avisos.md`](AKINE-agenda-clases-y-avisos.md) y
[`AKINE-agenda-roles.md`](AKINE-agenda-roles.md).

**Regla:** un profesional no puede tener obligaciones simultáneas incompatibles aunque sean de distintas
sedes o tipos, ni ofrecer disponibilidad simultánea en dos sedes.

## Causa: la validación estaba fragmentada

Relevado sobre `main` (`df19c35`):

| Camino | Turnos de la persona | Clases | Cierres (bloqueo, ausencia, cierre de sede) | Franja en la sede | Disponibilidad en otra sede |
|---|---|---|---|---|---|
| Reservar, reprogramar, trasladar, series, lista de espera (`RevalidadorDeSlot`) | sí, todas las sedes | sí | implícito en la franja, **sin nombrarlo** | sí, **leída antes del lock de la persona** | — |
| Sobreturno | no (a propósito) | sí | — | — | — |
| Programar / reprogramar clase, principal | sí | sí | implícito, sin nombrarlo | sí | — |
| Instructores adicionales (al programar, reprogramar, sumar) | sí | sí | **no** | **no** | — |
| Horario habitual (`DisponibilidadService`) | — | — | — | — | bloques de otra sede sí, **aperturas no**; el 409 decía "en esta sede" |
| Apertura (`ExcepcionService`) | **no** | **no** | — | — | **no**; sin lock de la persona |
| Cierre de un instructor con clases | turnos sí (`confirmaTurnosAfectados`) | **no** | — | — | — |

Causas concretas de los cuatro problemas:

3. **Mensajes.** `BloqueSolapadoException` no sabía la sede del bloque con el que chocaba y el handler tenía
   el texto fijo "en esta sede"; `recurso-ocupado` decía "en otra sede" sin nombrarla ni dar la franja.
   Cada módulo armaba su propia frase.
4. **Aperturas.** `ExcepcionService` solo validaba idempotencia: no preguntaba por la persona en otras sedes,
   ni por lo reservado ni por su horario, y no tomaba `agenda_profesional`.
5. **Instructores adicionales.** `ClaseService.exigirPersonaLibre` miraba turnos y clases y nada más; la
   franja (que es la que recorta bloqueos y ausencias) solo se exigía al principal. El cierre de un
   instructor no miraba sus clases.
6. **Históricos.** Nada impedía antes la doble oferta ni la clase con instructor bloqueado, y nada los marcaba.

## Diseño

### Una pregunta, un tipo de respuesta

- **`scheduling.spi.OcupacionDePersona`** (impl. `scheduling.application.OcupacionDePersonaService`): la
  lógica central. `actividadReservada(consulta)` = turnos y clases (principal o adicional) de **todas** las
  memberships de la persona en la organización; `cierres(consulta)` = cierres de la sede que alcanzan a esa
  membership (`BLOQUEO`/`AUSENCIA`) o a la sede entera (`CIERRE_DE_SEDE`). Sin tablas: lee turnos, clases
  por `OcupacionExternaProbe` (activity) y cierres por `resource.spi.DisponibilidadDirectory`. La
  `Consulta` lleva las exclusiones (turno que se mueve, clase que se mueve, grupo de una oferta grupal).
- **`platform.spi.problem.ConflictoDeAgenda`**: la respuesta, igual en los tres módulos. Causa (`TURNO`,
  `CLASE`, `BLOQUEO`, `AUSENCIA`, `CIERRE_DE_SEDE`, `DISPONIBILIDAD`), id del evento, sede (id, nombre, misma
  o no), franja, zona e instructor. `detalle(...)` arma el `detail` en castellano y `propiedades()` las
  extensiones del Problem Detail. **Nunca datos del paciente.**
- Quién la usa:
  - `RevalidadorDeSlot` (todos los caminos de turno y el sobreturno, que filtra solo `CLASE`).
  - `ClaseService.exigirPersonaLibre` (programar, reprogramar) e `InstructoresDeClaseService.reemplazar`.
  - `resource` por **`resource.spi.ActividadDePersonaProbe`**, que contesta
    `scheduling.infrastructure.ActividadDePersonaSobreAgenda` delegando en la misma lógica (inversión, como
    `DisponibilidadImpactProbe`: `scheduling → resource.spi` ya existía, no se agrega arista).
- La disponibilidad (bloques, excepciones, efectiva) sigue siendo de `resource`;
  `resource.application.DisponibilidadEntreSedes` concentra lo que una excepción tiene que respetar en las
  otras sedes de la persona. Un tipo de actividad nuevo se suma en `OcupacionDePersonaService` (o como otra
  implementación de `OcupacionExternaProbe`) y lo ven turnos, clases y aperturas.

ArchUnit (`ModuleArchitectureTest`, `CodingConventionsTest`) pasa sin tocar reglas.

### Turnos (punto 3 y concurrencia)

- `recurso-ocupado` lleva el conflicto completo y el `detail` "El profesional ya tiene una clase el
  15/03/2027 de 10:00 a 11:00 en la sede Norte. No es posible asignarle otro turno en ese horario.".
- `slot-no-disponible` por un cierre nombra el cierre (`BLOQUEO`/`AUSENCIA`/`CIERRE_DE_SEDE`) con su franja.
- **La franja se revalida bajo el lock de la persona.** Antes se leía en `prevalidar`, después del lock de
  la sede y antes del de la persona: una sustitución en otra sede podía cerrar la franja mientras el turno
  esperaba. Ahora se vuelve a mirar después de `agenda_profesional`.
- La previsualización de una serie reporta cada ocurrencia que choca con el `detail` (fecha, hora, sede).

### Aperturas (punto 4)

En el alta y en la edición de una **APERTURA de profesional**, bajo `consultorio_calendario` de cada sede
que se escribe (id ascendente) y después `agenda_profesional`:

1. **Actividad reservada** de la persona en **otra** sede en la franja → **siempre 409 `recurso-ocupado`**
   (`ActividadReservadaEnConflictoException`).
2. **Disponibilidad efectiva** de la persona en otra sede (habitual + aperturas, ya recortada por cierres,
   feriados y horario de la sede: una franja cerrada allá no cuenta) → **409 `bloque-solapado`**
   (`DisponibilidadSuperpuestaException`, con `superposiciones`), salvo
   `sustituirDisponibilidadSuperpuesta: true`.
3. **Sustitución**: en la misma transacción, un CIERRE (motivo `BLOQUEO`, notas "Sustituida por una apertura
   en la sede X") en la otra sede por cada franja superpuesta, auditado como
   `DISPONIBILIDAD_EXCEPCION_CREATED` con `origen = SUSTITUCION_POR_APERTURA` y `aperturaId`. Exige
   `agenda:excepciones` sobre ese profesional en esa sede (**403** explicativo si no). Por el punto 1 nunca
   cierra una franja con algo reservado.

Además, el **horario habitual** rechaza (`bloque-solapado`, `conflictoOrigen = APERTURA`) una apertura
futura de la misma persona en otra sede ese día de la semana.

### Instructores (punto 5)

Todos los instructores de una clase pasan por la misma regla al **programar, reprogramar (horario o
profesional) y sumar o reemplazar adicionales**: turnos y clases en cualquier sede, cierres en la sede de la
clase y —decisión— **la clase entera dentro de su disponibilidad efectiva en esa sede**, que es lo que hoy se
le exige al principal. Locks de todas las personas en orden de `account_id`
(`BloqueoDeAgendaDeProfesional.bloquearEnOrden`). El 409 lleva `instructorMembershipId` e
`instructorNombre`. Una clase `REALIZADA` no revalida (cargar quién la dio es honorarios), y a un adicional
que se suma a una clase ya `EN_CURSO` no se le exige la franja (sí turnos, clases y cierres): es registrar
quién la está dando, no planificar.

El **cierre** de un profesional (o de la sede) informa las clases afectadas en el mismo 409
`bloqueo-con-turnos-afectados` (`clasesAfectadas`, `clases`), con la misma confirmación
(`confirmaTurnosAfectados`). La edición informa solo las que suma.

### Conflictos históricos (punto 6)

`AgendaOperativa.conflictos[]` suma, calculados al leer y sin tocar datos
(`scheduling.application.ConflictosDeDisponibilidad`):

- `CLASE_EN_CIERRE`: clase de esta sede con un instructor (principal o adicional) alcanzado por un cierre;
  `excepcionId`, `causa`.
- `DISPONIBILIDAD_DOBLE`: franja de la columna que se pisa con franja de la misma persona en otra sede;
  `otraSedeConsultorioId`.
- Los existentes (`TURNO_CLASE`) siguen igual.

## Propuesta de regularización (no aplicada)

1. Listar por organización: `GET .../agenda-operativa` por sede en ventanas de 14 días, o una consulta de
   QA que cruce `profesional_disponibilidad`/`disponibilidad_excepcion` por `account_id` entre sedes.
2. **Doble oferta** sin reservas en la franja: quien gestiona elige la sede que atiende y carga un CIERRE en
   la otra (o edita la apertura con `sustituirDisponibilidadSuperpuesta: true`, que hace exactamente eso y
   queda auditado).
3. **Doble oferta con reservas** o **clase con instructor en cierre**: mover o cancelar el turno o la clase,
   o quitar al adicional (`PUT .../instructores`), o dar de baja el cierre. Nada automático: la decisión es
   de quien opera (ADR-0011).
4. Después, la agenda operativa deja de marcarlos. No hace falta migración.

## Contrato 0.158.0 (aditivo)

- `CreateExcepcionRequest` y `UpdateExcepcionRequest`: `sustituirDisponibilidadSuperpuesta` (boolean,
  opcional).
- `ProblemDetail` declara las extensiones: `conflictoTipo` (enum `TURNO, CLASE, BLOQUEO, AUSENCIA,
  CIERRE_DE_SEDE, DISPONIBILIDAD`), `conflictoConsultorioId`, `conflictoConsultorioNombre`,
  `conflictoMismaSede`, `conflictoInicio`, `conflictoFin`, `turnoEnConflictoId`, `claseEnConflictoId`,
  `excepcionEnConflictoId`, `instructorMembershipId`, `instructorNombre`, `conflictoOrigen` (`BLOQUE`,
  `APERTURA`), `conflictoHoraDesde`, `conflictoHoraHasta`, `conflictoFecha`, `superposiciones`,
  `clasesAfectadas`, `clases`.
- `ConflictoDeAgenda` (agenda operativa): `tipo`, `excepcionId`, `causa`, `otraSedeConsultorioId`;
  `turnoConsultorioId` y `claseConsultorioId` pueden faltar (fuera de `TURNO_CLASE`).
- Sin `problemType` nuevos. Cambios de conducta: el `detail` de `recurso-ocupado`, `bloque-solapado` y el de
  `slot-no-disponible` por un cierre cambia de texto (el frontend no debe ramificar por él); un adicional sin
  horario en la sede ya no se puede sumar a una clase; una apertura que pisa otra sede ahora es 409.

## Límites

- **Aperturas de sede entera** (sin profesional) no se validan entre sedes: abren para todos y no tienen una
  persona sobre la que preguntar.
- Las horas de la apertura se comparan crudas (no recortadas por el horario general de su sede).
- **Cierres de sede** no toman `agenda_profesional` de cada persona: la carrera cierre de sede ↔ turno/clase
  sigue como estaba (dos filas-lock distintas, `consultorio_calendario` y `agenda_sede`).
- **Clases recurrentes y cambio de sede de una clase no existen** en `activity`; la recurrencia validada es la
  de las series de turnos (cada ocurrencia por `RevalidadorDeSlot`, y la previsualización las reporta).
- El 403 de la sustitución sin permiso no distingue cuál de varias sedes lo produjo más que por el mensaje.
