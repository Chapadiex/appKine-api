# F5 — Agenda y recepción

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~65 %**
> Etapas del plan: 05.01–05.04 (§13, líneas ~2503–2822).

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 05.01 Slots explicables | CUMPLIDA | 85 | Vista pública con PII mínima; E2E real |
| 05.02 Reserva y confirmación | PARCIAL | 70 | Notificación; cobertura y caso en la reserva; autoservicio |
| 05.03 Cancelación, reprogramación, historial | PARCIAL | 60 | **Modelo de serie de turnos inexistente** |
| 05.04 Recepción y check-in | PARCIAL "reducida" | 45 | **No valida cobertura/convenio/autorización** |

Todas tienen pantalla (`buscador-de-agenda`, `reserva-de-turno`, `ciclo-de-turno`,
`recepcion-del-dia`; el registro de 05.04 dice "sin frontend" y ya no es cierto).

## Los dos hallazgos centrales

### 1. No existen las series de turnos (05.03, DP-04)
No hay `serie_id`, ni regla recurrente, ni comando "cancelar futuros", ni confirmación de alcance
en la UI. Varios criterios de aceptación ("cancelar futuros", "la primera ausencia preserva los
futuros") **no son verificables**. Lo único que sí está de DP-04: `AUSENTE` no borra el turno.
Se difirió con justificación ("no tiene escritor") **pero sin etapa destino**.

### 2. La recepción no valida nada administrativo (05.04, DP-05)
`RecepcionService` no importa nada de `contracting` ni de elegibilidad. El recorte se justificó
por DP-10: "con PARTICULAR única no hay nada que validar". Hoy 03.06 y 04.05 están en `main` y
**el recableado nunca se hizo**. `consultarElegibilidadAdministrativa` responde y sigue sin
consumidor. Además, el check-in es `EN_ESPERA` **dentro de `EstadoTurno`**: no hay máquina de
estados de recepción propia, como pedía DP-05.

## Faltantes

### Backend / API
- [x] ~~**Modelo de serie** (05.03): diseño propio, migración, comandos de alcance (este / este y
  futuros / toda la serie), ausencia que preserva futuros.~~ Hecho en **E-3** (backend):
  `docs/diseno/AKINE-E-3-series.md`, `V74` (`turno_serie`, `turno.serie_id`; reservada como `V70`), alta todo o nada,
  cancelación y reprogramación con alcance y confirmación por cantidad (`/series-de-turnos`),
  contrato 0.57.0, `SerieDeTurnosIT`. Falta la mitad web (confirmación de alcance en la UI).
- [ ] **Recepción con validación administrativa** (05.04): consumir `consultarElegibilidadAdministrativa`
  y el `spi` de cobertura vigente de [F3](F3-personas-y-cobertura.md); snapshot administrativo
  preliminar; comando "atender como Particular"; prepago como anticipo (este último depende de
  anticipos en [F7](F7-economia-del-mvp.md)).
- [ ] Decidir si la recepción tiene máquina de estados propia (DP-05) o se documenta el desvío.
- [ ] `ReservarTurnoRequest` sin cobertura elegida ni caso, aunque el plan valida "caso cuando la
  oferta lo exige". Se coordina con el gate RF-M10-007 ([F4](F4-dominio-clinico.md), [F6](F6-atencion-clinica.md)).
- [x] ~~Notificaciones de reserva, cancelación y reprogramación (RF-M26-002/003) por el outbox.~~
  Hecho en E-5: `scheduling.application.AvisosDeTurno`, tipos `TURNO_RESERVADO`,
  `TURNO_CANCELADO` y `TURNO_REPROGRAMADO`, encolados en la transacción de la operación
  (`NotificacionDeTurnoIT`).
- [ ] Política de ventana de cancelación para el paciente.
- [ ] Vista pública de slots con PII mínima.
- [ ] Autoservicio del paciente: depende del alcance `OWN` (decisión del usuario).

### Frontend
- [ ] Confirmación de alcance al cancelar/reprogramar una serie.
- [ ] Recepción mostrando el resultado de elegibilidad y el camino "Particular".

### Tests
- [ ] Los dos E2E de agenda (`agenda-buscador`, `agenda-reserva`) **sintetizan el HTTP con
  `route.fulfill`**: no prueban el backend. Reescribirlos contra el servidor real.
- [ ] E2E de ciclo de turno y de recepción → espera.
- [x] ~~`ReservaProbeSobreTurnos` (corregida el 28/09) solo tiene unitarios: IT contra MySQL.~~ → `AgendaDescuentaReservasIT`, por el buscador; verificado por mutación (con la sonda vacía falla).

## Desvíos

| Desvío | Documentado |
|---|---|
| Series diferidas sin etapa destino | Sí, registro de 05.03. **Cerrado en E-3** (backend) |
| Recepción "reducida" por DP-10 y nunca recableada | Sí el recorte; **no** la falta de recableado |
| Check-in como estado del turno, sin máquina propia | Parcial |
| Notificaciones diferidas | Sí, javadoc de `TurnoService`. **Cableadas en E-5** |
| Slots sin persistir; el espacio se elige en 05.02 | Sí |

## Para cerrar la fase

- [x] ~~Diseño + implementación del modelo de serie, con design challenge.~~ E-3 (backend).
- [ ] Recepción validando elegibilidad contra `contracting`.
- [ ] E2E reales de agenda, ciclo y recepción.
- [x] ~~Notificaciones de turno por outbox.~~ E-5.
