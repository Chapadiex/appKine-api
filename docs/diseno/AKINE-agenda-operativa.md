# Agenda operativa — turnos, bloqueos y trazabilidad (contrato 0.154.0)

Relevamiento: `AGENDA-checklist.md` del 03/11/2026 (§1.5, §2.5, §2.9, §5.4, §6, §7, §12, §13.2, §16, §18).
Migraciones `V131` y `V132` (`V133` reservada y sin usar). Los permisos nuevos (`agenda:horario`,
`agenda:excepciones`, alcance propio del profesional) van en otra rama: acá todo usa los códigos
existentes (`turno:manage`, `turno:read`, `consultorio:manage`, `colaborador:read`).

## Decisiones

- **D1 — Reprogramar sigue moviendo el mismo turno** (decisión 05.03, PLAN l.8527): la Sesión y la
  obligación cuelgan de `turno_id`. Lo que cambia es la trazabilidad: `turno_evento` guarda ahora
  profesional y box **antes y después** (`profesional_anterior_id`, `profesional_nuevo_id`,
  `espacio_anterior_id`, `espacio_nuevo_id`), y `TURNO_REPROGRAMADO` en `audit_event` lleva
  `inicioAnterior`, `profesionalAnteriorId` y `espacioAnteriorId`.
- **D1 bis — Cambio de sede = cancelar + reservar.** `consultorio_id` no es actualizable. El
  traslado cancela el original (`origen_cancelacion = INSTITUCION`, motivo "Cambio de sede a …") y
  reserva uno nuevo con `origen = TURNO_ANTERIOR` y `turno_origen_id` → original, **en una sola
  transacción `READ_COMMITTED`**. Toma el lock de `agenda_sede` de las dos sedes **por id
  ascendente** (dos traslados cruzados no se bloquean mutuamente); la de origen sin exigir que esté
  activa, la de destino sí. Rechaza un original con Sesión o con cualquier recepción registrada.
  Viajan la cobertura elegida (si sigue vigente ese día), caso, plan y observaciones.
- **D3 — Sin estados nuevos.** "Pendiente de reubicación" es `fueraDeDisponibilidad`, **derivado al
  leer** en la agenda operativa contra las mismas franjas que dibujan las columnas (regla 11: la
  disponibilidad efectiva no se materializa). Sólo turnos `RESERVADO`/`CONFIRMADO` con profesional.
- **Un bloqueo nunca pierde turnos.** Alta y edición de excepción, edición y baja de bloque del
  horario habitual: si dejan turnos pendientes afuera y no viene `confirmaTurnosAfectados=true`,
  409 `bloqueo-con-turnos-afectados` con `turnosAfectados` **y la lista `turnos`** (misma forma que
  `TurnoAfectadoPorDisponibilidadResponse`: paciente, profesional, estado). Confirmado, los turnos
  no se tocan.
- **Cancelación en lote:** cada ítem pasa por `CicloDeTurnoService.cancelar` a través del proxy, en
  su propia transacción: mismos controles, evento, auditoría y aviso que la cancelación suelta. Un
  rechazo no deshace los demás y vuelve con el `problemType` que habría recibido solo. Un turno que
  ya estaba cancelado vuelve `YA_CANCELADO` (reintento seguro). Un id repetido se procesa una vez.
  Sede ajena = 404 del lote entero; sin permiso = rechazo por ítem (prepara el alcance propio).
- **Reprogramación masiva:** no hay endpoint; la pantalla reprograma fila por fila con
  `reprogramarTurno`, que ahora acepta `espacioId`.
- **Auditoría transversal:** `TURNO_RESERVADO` y `TURNO_CONFIRMADO` se suman a lo que ya se
  auditaba. Todos los eventos de turno llevan en `details` `personaId`, `ofertaId`, `inicio`,
  `profesionalMembershipId`, `espacioId` y, si aplica, `turnoOrigenId`.
- **Observaciones del turno:** `turno.observaciones` (500), administrativas, **nunca clínicas**. Se
  cargan al reservar o con su propio endpoint (versión obligatoria, evento `OBSERVACIONES`, auditoría
  `TURNO_OBSERVACIONES` con antes y después). No entran en la huella de idempotencia.
- **Excepciones:** se pueden editar (motivo, fechas, horario, notas; ni tipo ni alcance). Motivo
  `VACACIONES` nuevo; el CHECK de `V132` repite **todos** los valores vigentes, incluido
  `CAPACITACION` de `V127`. Con motivo `OTRO` las notas son obligatorias (400 `validation-error`,
  `errors.notes`) al cargar y al editar; las filas viejas no se tocan hasta que alguien las edite.
  `created_by_cuenta_id` en `disponibilidad_excepcion` y `profesional_disponibilidad` (nulo en las
  anteriores a V132).

## Endpoints

| Operación | Ruta | Qué |
|---|---|---|
| `actualizarObservacionesDeTurno` (nuevo) | `PUT /consultorios/{c}/turnos/{t}/observaciones` | `{observaciones, expectedVersion}`, `turno:manage` |
| `trasladarTurno` (nuevo) | `POST /consultorios/{c}/turnos/{t}/traslado` | `{consultorioDestinoId, ofertaId, inicio, profesionalId?, espacioId?, motivo, expectedVersion}` → `{original, nuevo}`; `turno:manage` en las dos sedes |
| `cancelarTurnosEnLote` (nuevo) | `POST /consultorios/{c}/turnos/cancelacion-en-lote` | `{items:[{turnoId, expectedVersion}], motivo, origen?}` → resultado por ítem |
| `updateExcepcion` (nuevo) | `PUT /consultorios/{c}/excepciones/{id}` | `{motivo, fechaDesde, fechaHasta, horaDesde?, horaHasta?, notes?, version, confirmaTurnosAfectados?}` |
| `reservar` | `POST …/turnos/ofertas/{o}` | + `observaciones` |
| `reprogramarTurno` | `POST …/turnos/{t}/reprogramacion` | + `espacioId` |
| `historialTurno` | `GET …/turnos/{t}/historial` | + `profesionalAnteriorId/NuevoId`, `espacioAnteriorId/NuevoId`; tipo `OBSERVACIONES` |
| `listarTurnosDeSede`, `agendaOperativa` | | + `estado` (repetible, estado operativo) y `personaId`; la agenda trae `fueraDeDisponibilidad` por turno |
| `updateBloqueDisponibilidad`, `deactivateBloqueDisponibilidad` | | + `confirmaTurnosAfectados` (409 con la lista sin él) |
| Respuestas | `Turno`, `OperativoDeTurno` | + `observaciones`, `turnoOrigenId` |
| Respuestas | `ExcepcionResponse`, `BloqueResponse` | + `createdByCuentaId` (+ `createdByNombre` en excepciones) |
| Respuestas | `TurnoAfectadoPorDisponibilidadResponse` | + `personaId`, `personaNombre`, `profesionalNombre`, `estado` |

Todo aditivo salvo una conducta: **editar o dar de baja un bloque con turnos afectados ahora
responde 409** si no se confirma (antes guardaba e informaba).

## Límites conocidos

- El traslado manda dos avisos al paciente (cancelación del original y reserva del nuevo).
- `fueraDeDisponibilidad` no se calcula en el calendario (`listarTurnosDeSede`), sólo en la agenda
  operativa, que es la que ya trae las franjas.
- Los nombres de los afectados se resuelven sólo para los ≤50 que viajan en la lista.
