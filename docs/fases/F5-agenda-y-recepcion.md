# F5 — Agenda y recepción

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · ~65 %.
> **Reverificada el 12–13/10/2026** contra `main` (backend `8979519`, frontend `2cdd3bb`) y los PR
> mergeados: **~92 % del alcance técnico; lo que falta es decisión del usuario o QA manual.** Ver el
> registro de cierre al final.
> Etapas del plan: 05.01–05.04 (§13, líneas ~2503–2822).

## Estado por etapa

| Etapa | Estado al 01/10 | % 01/10 | Estado al 13/10 | % 13/10 | Lo que falta, en una línea |
|---|---|---|---|---|---|
| 05.01 Slots explicables | CUMPLIDA | 85 | CUMPLIDA | 95 | Vista **pública** (sin login): espera el autoservicio. El payload de slots ya no lleva PII |
| 05.02 Reserva y confirmación | PARCIAL | 70 | CUMPLIDA salvo decisión | 90 | Caso en la reserva (C-9, F4/F6); autoservicio del paciente (`OWN`); selector de cobertura en la pantalla |
| 05.03 Cancelación, reprogramación, historial | PARCIAL | 60 | CUMPLIDA salvo decisión | 90 | Ventana de cancelación **del paciente**: sin autoservicio no tiene actor |
| 05.04 Recepción y check-in | PARCIAL "reducida" | 45 | CUMPLIDA salvo decisión | 92 | Salida "se retiró sin ser atendido"; Particular desde `EN_ESPERA` |

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
  contrato 0.57.0, `SerieDeTurnosIT`. La mitad web entró en web #10 y #34.
- [x] ~~**Recepción con validación administrativa** (05.04): consumir `consultarElegibilidadAdministrativa`
  y el `spi` de cobertura vigente de [F3](F3-personas-y-cobertura.md); snapshot administrativo
  preliminar; comando "atender como Particular".~~ Hecho en **E-4** (backend):
  `docs/diseno/AKINE-E-4-recepcion.md`, `V78` (`recepcion`, `recepcion_evento`, migración de los
  turnos `EN_ESPERA`), contrato 0.63.0, `RecepcionIT`, `RecepcionConcurrenteIT`,
  `MigracionRecepcionV78IT`. La elegibilidad se consume por el nuevo
  `person.spi.ElegibilidadAdministrativaDirectory`. La mitad web entró en web #8 (07/10).
- [x] ~~Prepago como anticipo en la recepción: es **E-6**, depende de anticipos en
  [F7](F7-economia-del-mvp.md) y se apoya en la Recepción de E-4.~~ Hecho en **E-6** (backend):
  `docs/diseno/AKINE-E-6-prepago.md`, `V81`, contrato 0.66.0. La política vive en la oferta
  (`exige_prepago`, `PUT …/ofertas/{id}/politica-de-prepago`); la recepción la muestra como
  alerta (`Recepcion.prepago`, motivo `PREPAGO_PENDIENTE` en el evento de espera) sin bloquear; el
  prepago es un cobro de F-3 con `turnoId` y se imputa a la deuda del paciente después del commit
  del cierre (`PrepagoDeRecepcionIT`). La mitad web entró en web #14 y #21.
- [x] ~~Decidir si la recepción tiene máquina de estados propia (DP-05) o se documenta el desvío.~~
  Decidido: **DP-16** (07/10/2026), máquina propia. Implementada en E-4.
- [x] ~~`ReservarTurnoRequest` sin cobertura elegida~~ — **hecho en el cierre de F5 (13/10/2026)**,
  `V93`, contrato **0.94.0**: `coberturaId` opcional en la reserva, validado por el nuevo
  `person.spi.CoberturasAplicablesDirectory#vigenteDeLaPersona` (financiada, activa y vigente el
  día del turno, de esa persona y ese tenant; si no, 404), guardado en `turno.cobertura_id`,
  devuelto en `TurnoResponse.coberturaId` y usado por la recepción como cobertura por defecto al
  validar. Si aplica a la práctica lo sigue decidiendo la recepción, que observa sin bloquear.
  `RecepcionIT` (dos escenarios nuevos), `TurnoServiceTest`, `CicloDeRecepcionServiceTest`.
- [ ] **Caso en la reserva**: es el gate RF-M10-007, etapa **C-9**, que depende de las pantallas
  clínicas ([F4](F4-dominio-clinico.md), [F6](F6-atencion-clinica.md)). Fuera de F5 a propósito.
- [x] ~~Notificaciones de reserva, cancelación y reprogramación (RF-M26-002/003) por el outbox.~~
  Hecho en E-5: `scheduling.application.AvisosDeTurno`, tipos `TURNO_RESERVADO`,
  `TURNO_CANCELADO` y `TURNO_REPROGRAMADO`, encolados en la transacción de la operación
  (`NotificacionDeTurnoIT`).
- [ ] Política de ventana de cancelación para el paciente — **pendiente del usuario**: el plan
  (05.03, "Ownership/política de ventana para paciente") la ata al paciente, y hoy el paciente no
  cancela nada (sin `OWN`). Ningún RF de M12 fija la ventana ni dice si alcanza al staff.
- [ ] Vista pública de slots con PII mínima — **pendiente del usuario**, por el autoservicio. Lo
  verificable ya se cumple: `AgendaResponse` lleva oferta, sede, zona, días, motivos y slots con
  `profesionalId`, `espacioId` y cupo; **ningún dato de paciente**. Lo que no existe es un endpoint
  sin login.
- [ ] Autoservicio del paciente: depende del alcance `OWN` (decisión del usuario; el `CLAUDE.md`
  del backend lo deja para después del MVP).

### Frontend
- [x] ~~Confirmación de alcance al cancelar/reprogramar una serie.~~ web #10 (07/10, alta con
  previsualización y cancelación con alcance), web #34 (08/10, reprogramar con alcance), web #20
  (08/10, bandeja de series).
- [x] ~~Recepción mostrando el resultado de elegibilidad y el camino "Particular".~~ web #8 (07/10):
  `recepcion-del-dia` sobre la máquina de DP-16, observación visible y "Atender como Particular"
  con motivo obligatorio.
- [x] ~~Recepción mostrando el prepago pendiente y el cobro del anticipo con `turnoId` (E-6).~~
  web #14 (07/10) y web #21 (08/10, prepago visible antes del check-in).
- [ ] Selector de cobertura en la pantalla de reserva: espera que este PR entre y que el cliente se
  regenere contra **0.94.0** (regla 4 de coordinación). Sin él la reserva por pantalla sigue sin
  elegir y la recepción se comporta como antes.

### Tests
- [x] ~~Los dos E2E de agenda **sintetizan el HTTP con `route.fulfill`**.~~ Reescritos contra el
  backend real en **E-2** (api #66 y web #26, 08/10): `agenda-buscador` y `agenda-reserva` importan
  `support/agenda-real`. El arreglo de api #66 —la agenda ofrece lo que la reserva acepta— salió de
  esa reescritura. Feriado del día abierto en el sembrado: web #67 (12/10).
- [x] ~~E2E de ciclo de turno y de recepción → espera.~~ `agenda-ciclo` y `agenda-recepcion` (web
  #26); series y prepago en `flujo-series` y `flujo-prepago` (web #35, 08/10). Corren en el CI
  contra el backend real desde G-9 (web #30).
- [x] ~~`ReservaProbeSobreTurnos` (corregida el 28/09) solo tiene unitarios: IT contra MySQL.~~ → `AgendaDescuentaReservasIT`, por el buscador; verificado por mutación (con la sonda vacía falla).

## Desvíos

| Desvío | Documentado |
|---|---|
| Series diferidas sin etapa destino | Sí, registro de 05.03. **Cerrado en E-3** (api #44, web #10 y #34) |
| Recepción "reducida" por DP-10 y nunca recableada | Sí el recorte; **no** la falta de recableado |
| Check-in como estado del turno, sin máquina propia | Parcial. **Cerrado en E-4** (DP-16): `Recepcion` con máquina propia, `EN_ESPERA` fuera de `EstadoTurno` |
| Notificaciones diferidas | Sí, javadoc de `TurnoService`. **Cableadas en E-5** |
| Slots sin persistir; el espacio se elige en 05.02 | Sí |

## Para cerrar la fase

- [x] ~~Diseño + implementación del modelo de serie, con design challenge.~~ E-3 (backend).
- [x] ~~Recepción validando elegibilidad contra `contracting`.~~ E-4 (backend), por `person.spi`.
- [x] ~~E2E reales de agenda, ciclo y recepción.~~ E-2 (api #66, web #26, 08/10).
- [x] ~~Notificaciones de turno por outbox.~~ E-5 (api #37, 06/10).
- [x] ~~Cobertura elegida en la reserva.~~ Cierre de F5 (13/10, `V93`, 0.94.0).
- [ ] QA manual del §6 sobre `docs/qa/05-agenda-turnos-recepcion.md` (AGE-01 a AGE-14). Es del
  usuario.

---

## Registro de cierre — 12/10/2026

> Escrito el 12–13/10/2026 por el agente de cierre de F5, rama `akine-cierre-F5` sobre `main`
> `8979519`. **Cierre técnico, no de fase**: lo que queda abierto es decisión del usuario o QA
> manual, y está listado abajo con su pregunta.

### Evidencia por ítem

| Ítem | Dónde | Fecha |
|---|---|---|
| Modelo de serie con alcance (DP-04) | api #44 (`V74`, 0.57.0, `SerieDeTurnosIT`); web #10, #34; bandeja api #59 / web #20 | 06–08/10 |
| Serie `CANCELADA` como tercer estado (DP-20) | api #71 (E-8b) | 08/10 |
| Recepción con máquina propia y elegibilidad (DP-05, DP-16) | api #52 (`V78`, 0.63.0, `RecepcionIT`, `RecepcionConcurrenteIT`); web #8 | 07/10 |
| "Atender como Particular" hasta la obligación | api #56 (E-7), api #72 (E-7b, 409 `oferta-sin-precio`, DP-17) | 07–08/10 |
| Prepago como anticipo y visible antes del check-in | api #54, #59; web #14, #21 | 07–08/10 |
| Avisos de reservar, cancelar y reprogramar por el outbox (RF-M26-002/003) | api #37 (E-5, `NotificacionDeTurnoIT`) | 06/10 |
| La agenda descuenta los turnos vendidos | api #22 (`AgendaDescuentaReservasIT`, verificado por mutación) | 06/10 |
| El horario general de la sede limita la agenda (DP-19) | api #73 (A-8b, `HorarioDeSedeLimitaLaAgendaIT`); web #39 | 08/10 |
| "Hoy" en la zona de la sede | web #43, #60 | 11/10 |
| E2E reales de agenda, ciclo y recepción | api #66 + web #26 (E-2); web #35 (series y prepago); web #67 (feriado del día) | 08–12/10 |
| Baja de sede contra reserva: revalida la sede bajo el lock de agenda | api #96 (otro agente; fuera de esta rama) | 13/10 |
| **Cobertura elegida en la reserva** | **este PR**: `V93`, contrato **0.94.0**, `RecepcionIT` (2 escenarios nuevos), `TurnoServiceTest` (3), `CicloDeRecepcionServiceTest` (1); QA `AGE-14` | 13/10 |

**Medido en esta rama**: unitarias de `scheduling`, `person` y `architecture` (594 tests, 0 fallos,
ArchUnit incluido); `RecepcionIT` (16) y `OpenApiContractIT` (5) contra MySQL de Testcontainers,
0 fallos, con `V93` aplicada. La suite completa **no** se corrió: la máquina estaba cargada y la
corre el CI del PR.

**Una decisión de diseño que el usuario puede revertir sin costo**: la reserva valida solo que la
cobertura elegida sea de la persona y esté vigente ese día; **no** que aplique a la práctica por
convenio. Eso lo decide la recepción, que observa sin bloquear (RN-M13-003). Rechazarlo en la
reserva haría que un arancel cargado tarde impida reservar. La huella de idempotencia de un pedido
**sin** cobertura es idéntica a la anterior, así que un reintento con una clave vieja no se vuelve
409 por el despliegue. Las series (`/series-de-turnos`) **no** aceptan cobertura todavía.

### Pendiente del usuario

> **Decididas el 19/10/2026** (el usuario aceptó las recomendaciones; ver `decisiones-pendientes.md`). U-21 `RETIRADO` desde `LLEGO`/`EN_ESPERA` y Particular desde `EN_ESPERA`: se implementa, PR en curso (DP-43). U-22 el feriado omite la ocurrencia de la serie: se implementa, PR en curso (DP-43). U-23 eventos de turno que notifican: se deja como está (DP-42). U-29 ventana de cancelación de 24 h por sede y U-30 vista pública de slots: después del RC (DP-44).

1. **Autoservicio del paciente y alcance `OWN`.** ¿El paciente reserva, cancela y ve sus turnos
   desde el portal en el MVP? *Recomendado: después del MVP*, como ya dice el `CLAUDE.md` del
   backend. Destraba los dos siguientes.
2. **Ventana de cancelación.** ¿Cuántas horas antes puede cancelar el paciente, se configura por
   oferta o por sede, y alcanza al staff? *Recomendado: por sede, solo para el paciente, 24 h por
   defecto; el staff cancela siempre con motivo.* Ningún RF de M12 la fija.
3. **Vista pública de slots.** ¿Hay reserva online sin login? *Recomendado: no en el MVP.* El
   payload de slots ya no lleva PII, así que abrirla después es un endpoint y una regla de rate
   limit, no un rediseño.
4. **Qué eventos de turno notifican** (decisión 7 del `CLAUDE.md` del backend). Hoy avisan
   reservar, cancelar y reprogramar; confirmar y ausente no. *Recomendado: dejarlo así.*
5. ~~**Salida "se retiró sin ser atendido"** y **Particular desde `EN_ESPERA`** (decisión 18).~~
   **Decidido el 19/10/2026 (DP-43, U-21) e implementado** en `akine-dp43-recepcion-y-series`
   (`V116`, contrato 0.136.0): `POST .../recepcion/retiro` deja la recepción `RETIRADO` desde
   `LLEGO` o `EN_ESPERA`, con motivo obligatorio, sin tocar el turno; la llegada consta, así que
   después no hay nuevo check-in, ni ausencia, ni reprogramación. "Atender como Particular" se
   admite desde `EN_ESPERA` y la persona sigue en la sala.
6. ~~**Alta de serie todo o nada** (decisión 19, E-3): un feriado la hace fallar entera.~~
   **Decidido el 19/10/2026 (DP-43, U-22) e implementado** en la misma rama: la ocurrencia que cae
   en un día que la sede cierra por feriado se omite, queda en `turno_serie_omision` y vuelve en
   `SerieDeTurnos.ocurrenciasOmitidas`; si todas caen en feriado no se crea nada. Las demás causas
   siguen siendo todo o nada. La previsualización de la pantalla ya ve el feriado en la agenda
   (`motivoSinSlots = FERIADO`).
7. **QA manual del §6** sobre `docs/qa/05-agenda-turnos-recepcion.md`, AGE-01 a **AGE-14** (este
   PR sumó AGE-14, cobertura elegida por la API). Bloqueante para deploy y sin correr desde 02.02;
   la ejecución es del usuario.

### Pendiente técnico, sin decisión

- **Caso en la reserva**: gate RF-M10-007, etapa **C-9**, depende de las pantallas clínicas. No se
  tocó a propósito.
- **Selector de cobertura en `reserva-de-turno`** (`appKine-web`): después de mergear este PR y
  regenerar el cliente contra 0.94.0.
- **Cobertura en las series**: `CrearSerieDeTurnosRequest` no la acepta; es el mismo campo y la
  misma validación por ocurrencia.
