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

## Defectos de la QA contra MySQL real (05/11/2026)

Sin cambio de contrato (sigue **0.158.0**, `OpenApiContractIT` en verde sin regenerar): cambian conductas y
textos, no schemas.

### D-1 (media): dar de baja o achicar un CIERRE volvía a abrir la doble oferta

**Causa.** La validación entre sedes sólo corría donde se **agrega** disponibilidad que ya se sabía que
agregaba (alta y edición de una APERTURA, alta y edición de un bloque habitual). `ExcepcionService.darDeBaja`
y la edición de un CIERRE no consultaban `DisponibilidadEntreSedes`, y quitar o achicar un cierre también
devuelve horario. Repro: la apertura de Norte con sustitución crea un CIERRE en Centro; darlo de baja (o
achicarlo a 10:00–10:30) respondía 200 y las dos sedes ofrecían 10:00–11:30.

**Corrección.** `DisponibilidadEntreSedes.validarLiberacion`, con la misma lógica central que la apertura
(`superposiciones`, disponibilidad **efectiva** de la persona en sus otras sedes, habitual o apertura, ya
recortada por cierres y feriados):

- Corre en la **baja** y la **edición** de todo CIERRE, de un profesional o de la sede entera. En una de sede,
  por cada membership con horario habitual (`findMembershipsConBloquesVigentesEn`) o apertura propia
  (`findMembershipsConAperturasEn`) en esa sede y esas fechas: el cierre de sede también recorta aperturas
  (RN-M05-002).
- Se mira lo que **de la franja del cierre anterior** quedó ofrecido en esta sede después del cambio: se
  escribe, se hace `flush` y se calcula la efectiva. Un rechazo deshace todo. Sólo el futuro (desde ahora):
  un cierre pasado se ordena sin mirar nada.
- 409 **`bloque-solapado`** (`DisponibilidadSuperpuestaException`) con las extensiones de siempre
  (`conflictoConsultorioId`/`Nombre` de la otra sede, `conflictoInicio`/`Fin`, `superposiciones`) y otro
  `detail`: "… ya tiene horario de atención el 08/03/2027 de 10:00 a 12:00 en la sede Norte. No es posible dar
  de baja o achicar el cierre: …".
- Locks en el orden del alta: `consultorio_calendario` de la sede, después `agenda_profesional` de cada
  persona en orden de cuenta (`bloquearEnOrden`). Las filas se aseguran antes de la transacción; en un cierre
  de sede la lista se recalcula bajo el lock de la sede (una membership nueva sin fila da 409 y reintentar).
- El **alta y la edición de bloques habituales** ya validaban (`exigirSinSolapamiento` contra los bloques de la
  misma persona en todas las sedes y `exigirSinAperturasEnOtrasSedes`): no se tocaron.

**Decisión: la baja del cierre de una sustitución no ofrece deshacer la apertura.** El 409 alcanza: nombra la
sede y la franja de la apertura que lo originó, y el `detail` dice que primero hay que dar de baja o achicar
esa apertura. Hacerlo en la misma operación sería dar de baja en otra sede, con otro permiso, algo que quien
opera no pidió (ADR-0011). Sin extensión nueva (`aperturaOrigenId`) para no tocar el contrato: el cierre ya
lleva en sus notas "Sustituida por una apertura en la sede X" y la auditoría guarda `aperturaId`.

### D-2 (baja): el cierre de la sustitución se leía técnico en el historial

**Causa.** `DisponibilidadEntreSedes.cerrar` auditaba sin `antes`/`despues` y sin motivo; el historial caía en
`HistorialDeAgendaService.lado`, que muestra los `details` crudos (`origen`, `aperturaConsultorioId`, …).
**Corrección.** El alta auditada lleva `despues` de `ResumenDeAgendaParaAuditoria` ("Cierre por bloqueo,
09/11/2026, 10:00–12:00") y `reason` "Sustituida por una apertura en la sede Norte"; las claves de origen
siguen en `details` para trazabilidad y `lado` las trata como técnicas (para las filas ya escritas, que
quedan sin motivo).

### D-3 (baja, venía de `main`): `PUT /excepciones/{id}` devolvía la versión leída

**Causa.** "save() antes del flush devuelve la versión vieja": la respuesta se armaba antes de que Hibernate
incrementara `@Version`, y un segundo guardado encadenado daba 409 `concurrent-modification`. **Corrección.**
`DisponibilidadExcepcionRepositoryPort.flush()` después del `save` en la edición y en la baja.

### D-4 (baja): el 409 de una serie mezclaba un instante UTC con horas locales

**Causa.** `OcurrenciaSinLugarException` escribía `Instant.toString()`. **Corrección.** Recibe la zona de la sede
y el `detail` dice "La ocurrencia del 13/11/2026 14:30 no tiene lugar: " más el `detail` de la causa (el
mismo de una reserva suelta). `ocurrenciaInicio` sigue en UTC.

## Límites

- **Aperturas de sede entera** (sin profesional) no se validan entre sedes: abren para todos y no tienen una
  persona sobre la que preguntar.
- Las horas de la apertura se comparan crudas (no recortadas por el horario general de su sede).
- **El alta de un cierre de sede** no toma `agenda_profesional` de cada persona (la baja y la edición sí, por D-1): la carrera cierre de sede ↔ turno/clase
  sigue como estaba (dos filas-lock distintas, `consultorio_calendario` y `agenda_sede`).
- **Clases recurrentes y cambio de sede de una clase no existen** en `activity`; la recurrencia validada es la
  de las series de turnos (cada ocurrencia por `RevalidadorDeSlot`, y la previsualización las reporta).
- El 403 de la sustitución sin permiso no distingue cuál de varias sedes lo produjo más que por el mensaje.
- **Liberar disponibilidad** (D-1) se valida al dar de baja o editar un CIERRE. Lo que no se revalida: la
  **baja de un feriado del calendario** (`cierraPorFeriado`, sin fila) y el **cambio del horario general de la
  sede**, que también pueden devolver horario. Un CIERRE de sede con muchos profesionales calcula la efectiva
  de cada uno bajo el lock (lineal en profesionales × días).
