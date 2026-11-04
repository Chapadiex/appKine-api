# Agenda de profesionales — roles y permisos

Rama `akine-agenda-permisos`, contrato **0.153.0**, migración **`V129`** (`V130` reservada y vacía).
Integrada con la agenda operativa (`akine-agenda-turnos`, `V131`, `V132`): contrato **0.154.0**. Las
operaciones de esa rama —traslado de sede, observaciones, cancelación en lote, edición de
excepciones— y cómo quedaron sus permisos: [`AKINE-agenda-operativa.md`](AKINE-agenda-operativa.md).
Checklist de origen: `AGENDA-checklist.md` §1, §3, §9, §12.4 y §14 (raíz del workspace). Matriz:
`docs/seguridad/matriz-permisos-minima.md` §21.

Historial de cambios por profesional e indicador "reprogramado" (contrato **0.155.0**, `V134`):
[`AKINE-agenda-historial.md`](AKINE-agenda-historial.md).

## Decisiones del usuario (03/11/2026)

1. El `PROFESIONAL` **ve** todas las agendas de su sede y **opera solo sus turnos**.
2. La secretaría (`ADMINISTRATIVO`) carga bloqueos y excepciones de cualquier profesional, y **no**
   cambia el horario habitual salvo que un administrador se lo otorgue.
3. Se extiende el RBAC existente (`PermissionCode`, `RolePermissions`, `PermissionScope`, grants);
   no se crea un sistema paralelo. Toda regla se valida en el servicio.

## Qué puede hacer cada rol

| | Ver agenda | Turnos (reservar, mover, cancelar, confirmar, recepción, espera) | Bloqueos y excepciones | Horario habitual | Sobreturno |
|---|---|---|---|---|---|
| `ORG_ADMIN` | toda la org | toda la org | toda la org | toda la org | sí |
| `CONSULTORIO_ADMIN` | su sede | su sede | su sede | su sede | sí |
| `ADMINISTRATIVO` | su sede | su sede | su sede (también los de sede entera) | por grant | por grant |
| `PROFESIONAL` | su sede | **solo los suyos** (grant: su sede) | **solo los suyos** (grant: su sede) | **solo el suyo** | por grant |

"Suyo" = el profesional del turno, del bloque o de la excepción es una membership **de la misma
cuenta** que el actor. Lo ajeno responde **403 `forbidden`**.

## Diseño

- **Códigos.** `agenda:horario` y `agenda:excepciones` reemplazan a `consultorio:manage` en
  `DisponibilidadService` y `ExcepcionService` (mutaciones e impacto previo). Calendario de la sede,
  horario general, espacios y configuración de ofertas —incluida la duración propia por profesional
  (V128), que es de la oferta— siguen en `consultorio:manage`.
- **Alcance propio = `ACTIVIDAD_PROPIA`.** Ya significaba "lo que el actor atiende, resuelto por sus
  memberships" (G-1, `reporte:read`). No se usó `OWN`, que es el paciente sobre sus datos y necesita
  un vínculo cuenta↔persona que no existe. El evaluador concede la sede y devuelve el alcance; el
  recorte lo hacen `scheduling.application.AgendaDelProfesional` y
  `resource.application.AutorizacionDeSede.exigirPermisoSobreProfesional`. La pertenencia se resuelve
  en el servicio, con una consulta a `organization.spi.ConsultorioMembershipDirectory`, y **solo
  cuando la decisión vino recortada**: no se agregó una lectura por pedido al `ApiActor`.
- **Gana lo más amplio.** Si la base es `ACTIVIDAD_PROPIA` y un grant del mismo código cubre, el
  evaluador devuelve el alcance del grant. Para ampliar a un profesional se otorga `turno:manage` o
  `agenda:excepciones`.
- **Excepción de sede.** Sin profesional, `agenda:excepciones` exige un alcance no propio.
- **Otorgables nuevos** (`RolePermissions.GRANT_POR_ROL`): `agenda:horario` y `turno:sobreturno` al
  `ADMINISTRATIVO` y al `PROFESIONAL`; `turno:manage` y `agenda:excepciones` al `PROFESIONAL`.
- **Frontend.** `GET /me` suma `context.membershipId` (la membership que gobierna el contexto, la de
  sede si la hay; si no hay membership de organización, el rol sale de esa). `GET /me/permissions`
  suma `ownScopedPermissions`.
- **Auditoría.** Los eventos de turno (`TURNO_RESERVADO`, `TURNO_CONFIRMADO`, `TURNO_CANCELADO`,
  `TURNO_AUSENTE`, `TURNO_REPROGRAMADO`, `TURNO_SOBRETURNO`, `TURNO_TRASLADADO`, `TURNO_OBSERVACIONES`)
  y de disponibilidad y excepciones llevan en `details` el `rol` del actor (del contexto ya revalidado
  por `TenantContextFilter`) y `profesionalMembershipId` / `membershipId`. Los de turno salen todos de
  `DetallesDeAuditoriaDeTurno.de(actor, turno)`, que suma persona, oferta, inicio, box y turno de
  origen (agenda operativa).

## Doble turno entre sedes (defecto §9.2) y horario entre sedes (§9.3)

Con memberships por sede, la misma persona tiene un id en cada sede, el lock era `agenda_sede` y el
cruce de turnos miraba una membership: dos reservas a la misma hora en dos sedes pasaban.

- **`agenda_profesional` (V129, dueño `resource`)**: fila por (`organization_id`, `account_id`). Se crea
  con `FilaLockPrevia` **antes** de la transacción de negocio (`AgendaSedeIniciador.asegurarProfesionales`,
  `DisponibilidadService`) y se toma `FOR UPDATE` **después** del lock de la sede, nunca antes: con ese
  orden fijo dos sedes esperan la misma fila sin deadlock. Varias personas en una transacción (series)
  se toman de una vez en orden de cuenta (`bloquearEnOrden`).
- Vive en `resource` y se ofrece por `resource.spi.BloqueoDeAgendaDeProfesional` porque `scheduling` ya
  depende de `resource.spi` y no al revés.
- `RevalidadorDeSlot` cruza contra `findVivosDeProfesionalesQueCruzan` con **todas** las memberships de
  la cuenta. `DisponibilidadService` rechaza con `bloque-solapado` un bloque que se pisa con uno de la
  misma persona en otra sede.
- Si la fila de la persona no existe al bloquear (el turno cambió de profesional entre la lectura sin
  lock y la transacción), la operación responde 409 `concurrent-modification`: recargar y reintentar.

## Operaciones de la agenda operativa

Al integrar `akine-agenda-turnos` se aplicó el mismo recorte a sus operaciones (detalle en
[`AKINE-agenda-operativa.md`](AKINE-agenda-operativa.md), "Permisos después de integrar"):

- **Traslado de sede:** `turno:manage` en las dos sedes; con alcance propio, el turno de origen y el
  profesional de destino tienen que ser de su cuenta. Toma el lock de la persona de destino después de
  los de las sedes y cruza contra todas sus memberships.
- **Observaciones:** `turno:manage`, solo en sus turnos.
- **Cancelación en lote:** el 403 propio se reporta por ítem (`RECHAZADO`, `forbidden`).
- **Edición de excepciones:** `agenda:excepciones` con la `membershipId` de la excepción, igual que
  el alta y la baja.

## Lo que no se hizo

- Las **clases** (`activity`) siguen cruzando por membership: una clase en otra sede no bloquea un
  turno de la misma persona.
- El 403 del recorte propio no deja fila `PERMISSION_DENIED` (el evaluador concedió; el rechazo es del
  servicio).
- Duración propia por profesional: sigue en `consultorio:manage`.
