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

## Límites

- Un traslado deja dos filas en el historial del profesional de origen: la cancelación y el
  traslado (son dos hechos auditados).
- Las excepciones de **sede entera** (sin profesional) no aparecen en el historial del profesional.
- `q` no busca por nombre de paciente ni de actor (viven en otros módulos).
- `TURNO_CAMBIO_DE_PROFESIONAL` no es un tipo filtrable: se filtra `TURNO_REPROGRAMACION` y se mira
  `cambioDeProfesional`.
- Las sedes de un lector se toman de sus memberships vigentes; un `PLATFORM_ADMIN` no tiene
  `agenda:excepciones` y no lee este historial.
