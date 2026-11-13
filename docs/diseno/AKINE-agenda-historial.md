# Agenda — historial por profesional e indicador "reprogramado" (contrato 0.155.0)

Rama `akine-agenda-historial`. Migración **`V134`** (`V135` reservada y sin usar). Sigue a
[`AKINE-agenda-roles.md`](AKINE-agenda-roles.md) y [`AKINE-agenda-operativa.md`](AKINE-agenda-operativa.md).

## Qué existía y se reusó

- **La auditoría transversal ya tenía todo.** Bloques, excepciones y todos los eventos de turno se
  auditaban en `audit_event` con actor, `rol`, motivo, sede y antes/después parcial; los de turno con
  `profesionalMembershipId` (y `profesionalAnteriorId` en la reprogramación), los de horario y
  excepciones con `membershipId`. **No hay tabla nueva**: el historial es una lectura de esa tabla.
- **`listAuditEvents` no se extendió**: exige `auditoria:read` (ni la secretaría ni el profesional lo
  tienen), redacta detalles sin `auditoria:read-clinica`, muestra el JSON crudo y obliga a elegir un
  único filtro. La pantalla de auditoría del frontend (`features/organization/pages/audit`) sigue
  igual; el historial de agenda es otra pregunta con otro permiso.
- **`historialTurno` y `turno_evento`** ya traían antes/después de cada reprogramación (inicio,
  profesional, box), actor y motivo. El indicador "reprogramado" se deriva de ahí.

## Historial por profesional

`GET /api/v1/organizations/{orgId}/agenda/historial` (`listarHistorialDeAgenda`), módulo `scheduling`.

| Parámetro | |
|---|---|
| `profesionalId` (obligatorio) | membership en cualquier sede; se leen **todas** las memberships de la persona en la organización, vigentes o no (`ConsultorioMembershipDirectory.findTodasDeLaCuenta`, nuevo) |
| `consultorioId` | acota a una sede |
| `tipo` (repetible) | `HORARIO_ALTA/EDICION/BAJA`, `EXCEPCION_ALTA/EDICION/BAJA` (ausencias, licencias y vacaciones son excepciones; desbloqueo = baja), `TURNO_RESERVA`, `TURNO_SOBRETURNO`, `TURNO_CONFIRMACION`, `TURNO_CANCELACION`, `TURNO_AUSENCIA`, `TURNO_REPROGRAMACION` (el cambio de profesional viene marcado con `cambioDeProfesional`), `TURNO_TRASLADO`, `TURNO_OBSERVACIONES` |
| `desde` / `hasta` | instantes UTC sobre **cuándo se hizo el cambio**; `desde` inclusivo, `hasta` exclusivo |
| `q` | contiene, en el motivo y en el tipo de evento |
| `page`, `size`, `sort` | tamaño por defecto **10** (tope 100); `sort` = `ocurridoEn,asc|desc` (por defecto desc, desempate por id) |

Respuesta `CambioDeAgenda`: tipo, entidad e id, sede, actor (cuenta, nombre, `actorRol`), motivo,
`antes`/`despues` ya armados ("Mar 09:00–13:00, desde …" → "Mar 10:00–13:00, …"; "Lun 08/03 09:00 ·
Sede Centro · Gómez, Laura · Box 2" → …, en la zona de la sede), profesional, turno e inicio, paciente
(con la condición de abajo) y `cambioDeProfesional`. `actorRol` es el `roleCode` del contexto de quien
operó (`ORG_ADMIN`, `CONSULTORIO_ADMIN`, `ADMINISTRATIVO`, `PROFESIONAL`); ausente en eventos
anteriores a la agenda de profesionales.

**V134 — columnas generadas.** `audit_event.agenda_membership_ref` (`profesionalMembershipId` o
`membershipId` del JSON) y `agenda_membership_anterior_ref` (`profesionalAnteriorId`), `VIRTUAL`, con
índices `(organization_id, ref, occurred_at)`. No hay backfill: `audit_event` es append-only por
trigger (V14) y una columna virtual alcanza las filas viejas sin escribirlas. Los emisores no
cambian. `platform.spi.audit.AuditQuery#deAgenda` es la única lectura.

**Antes/después de horario y excepciones.** Desde esta rama, `DisponibilidadService` y
`ExcepcionService` agregan `antes`/`despues` legibles a los `details` (la edición antes solo anotaba
los campos cambiados). Las filas viejas caen a esos campos "a -> b" partidos por lado.

### Permiso (decisión)

Se reusa **`agenda:excepciones`** y no se crea un código nuevo: tiene exactamente el reparto pedido —
`ORG_ADMIN` organización, `CONSULTORIO_ADMIN` y `ADMINISTRATIVO` su sede, `PROFESIONAL`
`ACTIVIDAD_PROPIA`— y el criterio es "quien administra la agenda de alguien ve sus cambios". Un grant
de `agenda:excepciones` al profesional le amplía la lectura a su sede, igual que la operación.

- El servicio evalúa a nivel organización; si no concede, sede por sede entre las del lector. La
  consulta se recorta a las sedes concedidas (`consultorio_id IN`).
- **Ajeno con alcance propio: 403 `forbidden` con la extensión `alcance: "PROPIO"`**
  (`AgendaAjenaException`). Sin permiso en ninguna sede pedida: 403 `forbidden` común.
- Otra organización, profesional o sede que no son del tenant: **404**.
- **PHI mínima:** `personaId`/`personaNombre` solo si el lector tiene `turno:read` en la sede del evento.

## Indicador "reprogramado" (no es un estado)

`OperativoDeTurno` (agenda operativa, turnos del día, `GET …/turnos/{id}`, turnos de la persona) suma
`reprogramaciones` (int, 0 si nunca) y `reprogramacion` (`cantidad`, `inicioOriginal`, `ultimoTipo`
`REPROGRAMACION|TRASLADO`, `ultimaEn`, `ultimoMotivo`, `ultimoActorCuentaId`, `ultimoActorNombre`).
`estadoOperativo` no cambia.

- Se sigue la cadena `turno_origen_id`: cuentan las reprogramaciones de cada eslabón y **cada traslado
  es una más**. `inicioOriginal` = `inicioAnterior` de la primera reprogramación del original, o su
  inicio si nunca se reprogramó.
- Sin N+1 (`ReprogramacionesDeTurnos`): una consulta por nivel de cadena (casi siempre ninguna) y una
  a `turno_evento` por lote (`ix_turno_evento_turno` alcanza: `V135` no hizo falta).
- **`historialTurno` sigue la cadena** (elección: inline, no exponer el id para que la UI lo pida):
  incluye los eventos de los turnos de origen, cortando en la primera sede donde el lector no tiene
  `turno:read`. Cada evento suma `turnoId` y `consultorioId` para distinguirlos.

## Cierres de sede y traslado en una fila (contrato 0.157.0)

Rama `akine-agenda-historial-2`. Migración **`V139`** (`V140` reservada y sin usar).

### 1. Los cierres de la sede entera no aparecían en el historial del profesional

**Causa.** El historial filtra `audit_event` por las columnas generadas de V134, que salen de
`profesionalMembershipId` o `membershipId` en `details`. Una excepción de sede entera
(`disponibilidad_excepcion.membership_id IS NULL`, p. ej. el feriado de toda la sede Centro del
16/11) se audita **sin** `membershipId`: su `agenda_membership_ref` es `NULL` y ninguna consulta por
profesional podía alcanzarla. No era un filtro mal puesto: el evento no nombra a nadie, y "a quién
afectó" no estaba escrito en ningún lado.

**Solución: se resuelve al leer, no se copia.** La auditoría queda como está —un evento por alta,
edición o baja del cierre— y el historial decide, en cada lectura, qué cierres tocaron a la persona:

- `resource.spi.CierresDeSedeDirectory` (nuevo, `ResourceCierresDeSedeDirectory`) devuelve **todos**
  los cierres de sede entera de las sedes donde la persona tuvo membership —activos o dados de
  baja, de cualquier fecha— y, por cada uno, sus memberships con **horario habitual** en el
  período: un bloque de esa sede cuya vigencia cruza el cierre (las dos `hasta` son exclusivas), en
  un día de la semana que el bloque atiende (y en horas que se cruzan si el cierre es parcial), y
  que no estaba dado de baja antes de que el cierre empezara. **La asignación histórica manda:** se
  leen todas las memberships de la persona (vigentes o no) y todos sus bloques, también los dados
  de baja después; un cambio de sede posterior ni borra ni agrega filas.
- Si no tenía horario, `scheduling` mira si tenía **turnos** (cualquier estado, también cancelados:
  `TurnoRepositoryPort#findDeProfesionalesEnSedeEnVentana`) o **clases**
  (`OcupacionExternaProbe`) de esa sede en el período.
- Los ids de los cierres afectados viajan a `AuditQuery#deAgenda` como `EventosDeSede`
  (`entity_type = 'DisponibilidadExcepcion'`, ids, tipos de evento) y la consulta los suma con un
  `OR` sobre `ix_audit_event_entity`. **El paginado, el total, el orden, `desde`/`hasta`, `q` y la
  sede siguen siendo SQL** sobre una sola consulta: no hay mezcla en memoria.

Tipos nuevos: `CIERRE_DE_SEDE_ALTA`, `CIERRE_DE_SEDE_EDICION`, `CIERRE_DE_SEDE_BAJA` (entidad
`CIERRE_DE_SEDE`). Comparten `event_type` con `EXCEPCION_*` y se distinguen por la ausencia de
`membershipId`: pedir sólo `EXCEPCION_*` no trae cierres de sede y viceversa. La fila lleva la sede,
el actor, el motivo declarado, `antes`/`despues` y, en `profesionalId`, la membership de la persona
**en esa sede**. `cierreDeSede` trae `excepcionId`, `motivo` (`FERIADO`, …), `fechaDesde`,
`ultimoDia` (**inclusive**: la tabla guarda el fin exclusivo), horas si es parcial, `vigente`,
`conHorarioHabitual` y los turnos de la persona en el período contados al leer
(`turnosEnElPeriodo`, `turnosPendientes` —reservados o confirmados, los que la agenda operativa
marca `fueraDeDisponibilidad`— y `turnosCancelados`). Para llegar a ellos, el frontend abre
`agendaOperativa` de `consultorioId` desde `fechaDesde` hasta `ultimoDia + 1`.

### 2. Un traslado aparecía como dos (o tres) filas

**Causa.** `TrasladoDeTurnoService` audita tres hechos en una transacción: `TURNO_CANCELADO` (vía
`aplicarCancelacion`, sin aviso) y `TURNO_TRASLADADO` sobre el turno original, y `TURNO_RESERVADO`
sobre el nuevo. Las tres llevan profesional, así que el historial del profesional de origen
mostraba cancelación + traslado, y si el destino era la misma persona (otra membership) además la
reserva; el profesional de destino sólo veía una "reserva" suelta. **Reprogramación y cambio de
profesional no sufren esto:** auditan un único `TURNO_REPROGRAMADO` (con `profesionalAnteriorId`
cuando cambia la persona, que V134 ya indexa para las dos).

**Solución.**

- **Vínculo para los eventos viejos y nuevos: `turnoOrigenId`.** La reserva del turno nuevo ya lo
  llevaba en `details` (la imagen de `turno.turno_origen_id`, desde V131). **V139** lo expone como
  columna generada `agenda_turno_origen_ref`, sin backfill (append-only, igual que V134).
- **Vínculo explícito para los nuevos: `operacionId`** (UUID) en `details` de las tres filas
  (`CicloDeTurnoService.aplicarCancelacion` gana una sobrecarga con detalles extra). No se usa para
  filtrar —el vínculo estructural alcanza a todo— sino para trazabilidad; viaja en la fila.
- **La consulta absorbe en SQL** la `TURNO_CANCELADO` de un turno que tiene `TURNO_TRASLADADO` y la
  `TURNO_RESERVADO` con `agenda_turno_origen_ref`, y hace aparecer el `TURNO_TRASLADADO` también
  para el profesional de **destino** (la membership de esa reserva) y para un lector que sólo
  alcanza la sede de destino. Un traslado = una fila para cada persona involucrada; el total y el
  paginado siguen exactos.
- La fila `TURNO_TRASLADO` trae antes (sede, fecha/hora, profesional y box de origen) y después
  (los del turno nuevo), `turnoNuevoId`, `operacionId`, `cambioDeProfesional` (comparado contra
  las memberships de la persona consultada: dos memberships de la misma persona no son un cambio) y
  `eventosRelacionados` (la cancelación y la reserva absorbidas, con id de auditoría, tipo, turno y
  sede). **No se borra ni se reescribe ninguna fila de auditoría.**
- Una cancelación independiente sigue siendo `TURNO_CANCELACION`.
- **El aviso único al paciente no cambió** (`turno-trasladado:<nuevo>:desde:<origen>`):
  `NotificacionDeTurnoIT` lo sigue verificando.

### 3. El cierre creado por una sustitución se leía técnico (QA del 05/11/2026, D-2)

**Causa.** La sustitución de disponibilidad (contrato 0.158.0) auditaba el CIERRE que crea en la otra sede
sin `antes`/`despues` y sin `reason`, así que la fila caía en `lado(...)` y mostraba los `details` crudos
("origen SUSTITUCION_POR_APERTURA · aperturaConsultorioId 575 · …"), con motivo vacío.

**Solución.** `DisponibilidadEntreSedes.cerrar` escribe `despues` con `ResumenDeAgendaParaAuditoria`
("Cierre por bloqueo, 09/11/2026, 10:00–12:00") y `reason` "Sustituida por una apertura en la sede Norte".
`origen`, `aperturaConsultorioId`, `aperturaId` (y `cierresPorSustitucion`, `clasesAfectadas` de la apertura
y la edición) quedan en `details` para trazabilidad y `lado` los trata como claves técnicas, lo que también
limpia las filas escritas antes de la corrección (que siguen sin motivo: no se reescribe la auditoría).

## Límites

- Los cierres **automáticos por feriado** del calendario de la sede (`cierraPorFeriado`, sin fila en
  `disponibilidad_excepcion`) no son eventos auditados y no aparecen; sí los feriados cargados como
  excepción de sede.
- El período y los conteos de un cierre de sede son los de la fila **actual** de la excepción (si
  se editó, el `antes`/`despues` de cada edición muestra el período de ese momento). Los turnos que
  se reprogramaron fuera del período ya no cuentan en `turnosEnElPeriodo`.
- La baja de un bloque se compara contra el inicio del cierre en la zona de la sede; un bloque
  cargado **después** del cierre con vigencia retroactiva también cuenta como horario.
- Afectación por turnos/clases: una consulta por cierre sin horario habitual, en cada lectura. Con
  muchos años de cierres en una sede donde la persona no tiene horario, el costo crece lineal.
- `q` no busca por nombre de paciente ni de actor (viven en otros módulos).
- `TURNO_CAMBIO_DE_PROFESIONAL` no es un tipo filtrable: se filtra `TURNO_REPROGRAMACION` y se mira
  `cambioDeProfesional`.
- Las sedes de un lector se toman de sus memberships vigentes; un `PLATFORM_ADMIN` no tiene
  `agenda:excepciones` y no lee este historial.
