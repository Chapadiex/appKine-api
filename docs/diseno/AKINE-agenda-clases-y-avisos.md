# Agenda — clases contra turnos entre sedes

Rama `akine-agenda-clases-avisos`, contrato **0.156.0**. Migraciones `V136` y `V137` reservadas y
**sin usar** (ver `docs/fases/01-trabajo-en-paralelo.md` §6). Sigue a
[`AKINE-agenda-roles.md`](AKINE-agenda-roles.md), que dejó escrito en "Lo que no se hizo": *las clases
siguen cruzando por membership*. El aviso único del traslado, que nació en esta rama, se movió a otra
rama y **no está acá**.

## El defecto

La misma persona tiene una membership por sede. El turno ya se cruzaba contra los turnos de todas sus
memberships (V129, `agenda_profesional`), pero:

- la reserva preguntaba a `activity` por las clases de **una** membership: una clase de 09:30 a 10:30
  en la Sede A dejaba reservar a las 10:00 en la Sede B;
- la agenda (slots, `buscarDisponibilidadDeTurnos`, `buscarAgenda`) leía las clases **de la sede** y los
  turnos **de la membership pedida**: ofrecía libre a quien estaba ocupado en otra sede;
- programar o reprogramar una clase miraba turnos y clases de **una** membership, sin lock de persona;
- los **instructores adicionales** no ocupaban a nadie: ni se validaban al sumarlos ni bloqueaban turnos.

## Qué cambió

| Dónde | Qué |
|---|---|
| `scheduling.spi.OcupacionExternaProbe` | `profesionalOcupado(membership)` → `ocupacionDeProfesionales(memberships)`: las clases vivas de cualquier sede que ocupan a esas memberships, como principal **o adicional vigente**, con sede e intervalo |
| `scheduling.spi.AgendaDeSede` | `turnosDeProfesionalesQueCruzan(memberships)`; `OcupacionDeAgenda` suma `consultorioId` |
| `RevalidadorDeSlot` | Bajo los locks (sede → persona) cruza las clases de **todas** las memberships de la persona. Reservar, reprogramar, trasladar, series y asignar desde lista de espera pasan por acá |
| Sobreturno | Salva la ocupación de **turnos**, no la de una clase: con una clase de la misma persona (cualquier sede) el sobreturno también es `recurso-ocupado`. Toma igual el lock de la persona |
| `ReservaProbeSobreTurnos` | La ocupación que usa el motor de slots incluye turnos de las otras memberships de la persona y todas sus clases, a nombre de la membership pedida. Los boxes siguen saliendo de las clases de la sede |
| `ClaseService` (programar, reprogramar) | `asegurar` sede y personas antes de la transacción; adentro: lock de `agenda_sede` → (reprogramar: fila de la clase) → `agenda_profesional` de principal y adicionales, en orden de cuenta. Cruza turnos y clases de cualquier sede de cada persona |
| `InstructoresDeClaseService.reemplazar` | Mismo orden de locks (antes tomaba sólo la fila de la clase). Valida libres **sólo los que se suman** y sólo si la clase no está `REALIZADA` (cargar quién dio una clase pasada es honorarios, no agenda) |
| `validarHorarioDeTurno` | `conflictoTipo` (`TURNO`/`CLASE`), `claseEnConflictoId`, `conflictoConsultorioId`; el motivo de `PROFESIONAL_OCUPADO` dice "clase" o "clase en otra sede" cuando corresponde |
| Agenda operativa | `ocupacionesEnOtrasSedes` (columna, tipo, sede, inicio, fin; **sin paciente ni id**) y `conflictos` (pares turno–clase de la misma persona que ya se pisan; el id sólo del lado de esta sede). Ver `OcupacionEntreSedes` |

## Orden de locks

```
agenda_sede (una sede; en el traslado, las dos por id ascendente)
  → clase_programada FOR UPDATE   (sólo reprogramar clase / reemplazar instructores)
    → agenda_profesional          (cada persona, en orden de account_id)
```

Ningún camino de turno toma la fila de la clase, y ninguno toma una sede después de una persona: una
clase en A y un turno en B de la misma persona se encuentran en la fila de la persona, el segundo
espera y, en `READ_COMMITTED`, ve al primero. `ClaseYTurnoEntreSedesIT` lo corre en paralelo.

Una persona cuya fila no existe al bloquear (el profesional o un adicional cambió entre la lectura sin
lock y la transacción) responde 409 `concurrent-modification`, igual que en la agenda de profesionales.

## Problem type

Se reusa `recurso-ocupado` (scheduling y activity). Propiedades nuevas, aditivas: `conflictoTipo`,
`claseEnConflictoId` / `turnoEnConflictoId`, `conflictoConsultorioId`, `conflictoInicio`,
`conflictoFin`. `turnoEnConflictoId` ya no viaja en `null` cuando el conflicto es una clase. El
frontend ya traduce `recurso-ocupado` por `recurso` (`agenda-errors.ts`, `actividad-errors.ts`): no
aparece ningún tipo nuevo.

## Datos que ya se pisan

No se modifica ninguna actividad. Los pares se calculan al leer en la agenda operativa (`conflictos`),
como `fueraDeDisponibilidad`. Resolverlos (mover o cancelar uno) es una decisión de quien opera.

## Lo que no se hizo

- **Clases recurrentes:** `activity` no tiene series de clases (cada clase es una fila). Si aparecen,
  cada ocurrencia tiene que pasar por `exigirRecursosLibres`.
- **Disponibilidad de los adicionales:** al principal se le exige estar dentro de su franja efectiva
  (bloqueos y ausencias incluidas, por `DisponibilidadDirectory.efectiva`); a los adicionales no, y
  ningún RF de M28 lo pide. Se valida sólo que estén libres de turnos y otras clases.
- La agenda unificada y el calendario (`listarTurnosDeSede`) no traen `ocupacionesEnOtrasSedes`.
