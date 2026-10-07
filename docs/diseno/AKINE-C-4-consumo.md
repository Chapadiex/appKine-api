# AKINE C-4 — Consumo de autorizaciones: DP-12, DP-13, cobertura y caso (M17)

> Paquete **C-4** de `docs/fases/01-trabajo-en-paralelo.md` (grupo G1, ruta crítica de la
> economía: C-4 → F-4). Cierra los ítems de 04.05 de `docs/fases/F4-dominio-clinico.md`.
> Migración: **`V76`**. Contrato: **0.59.0**.

## 1. Qué se cubre y de dónde sale

| Requisito | Qué dice | Cómo se cubre |
|---|---|---|
| **DP-12** (DU-4) | Una sesión consume **una unidad en cada autorización involucrada**; varias prácticas bajo la misma autorización son una unidad; idempotente por (sesión, autorización) | §2 |
| **DP-13** (DU-7) | La reversión **sigue manual** (RF-M17-005). Al anular la obligación de una sesión que consumió, **alerta "consumo a revisar"** sobre esa autorización, sin tocar el saldo | §3 |
| RN-M17-002 | El consumo debe ser idempotente | §2.3: por sesión, no sólo por autorización |
| RN-M17-003 | Faltantes generan alertas | §3 |
| RF-M17-004 / RNF-M17-002 | "Vigencia de referencias", "Estado de las entidades" | §4: la cobertura de la autorización tiene que estar vigente el día de la atención |
| RF-M17-007 (validaciones) | "No reutilizar autorización de otro Caso o persona" | §5 |
| Plan, etapa 04.05 | "reservar **si la regla lo exige**" | §6: no hay regla → `RESERVA` queda afuera |

### Lo que queda AFUERA, y por qué

- **`RESERVA` / `LIBERACION_DE_RESERVA`.** Ningún RF de M17, M12 ni M13 dice **cuándo** se reserva una
  unidad. El plan de 04.05 dice "reservar si la regla lo exige" y la regla no existe; DP-05 prohíbe
  además que una transición del turno pruebe una prestación. Sin RF no se implementa (AGENT.md §2).
  Los dos valores siguen en el CHECK de `V50` y en el enum, sin emisor.
- **Descartar una alerta** ("la anulación fue una cortesía, el consumo está bien"). Ningún RF lo pide
  y DP-13 sólo dice que la alerta existe. Hoy la alerta se resuelve **sola** cuando alguien revierte
  ese consumo (RF-M17-005). Decisión a revisar (§9).
- **Recurrir a otra autorización si la elegida perdió la carrera por el saldo.** Igual que en 04.05:
  `SIN_SALDO` es un desenlace, no un reintento.
- **Un endpoint que consuma.** El consumo sigue naciendo sólo del cierre de sesión.

## 2. DP-12 — una unidad por autorización involucrada

### 2.1 El algoritmo

`ConsumoDeAutorizacionService.consumirPorSesion` deja de elegir **una** autorización y pasa a:

1. **Candidatas:** las aprobadas y activas del paciente que **habilitan** el día local de la sede
   (vigencia y saldo), con su **cobertura vigente** ese día (§4) y que **no estén atadas a otro caso**
   (§5). Siguen ordenadas por "la que vence antes".
2. **Sin prácticas registradas** (sesiones anteriores a 06.04, ofertas sin tratamientos): se conserva
   el comportamiento de 04.05 — **una** unidad en la primera candidata. Vacío es "no se sabe", no
   "ninguna" (`ConsumoPorSesion`).
3. **Con prácticas:** cada práctica realizada se asigna a la **primera candidata que la cubre**
   (`autorizacion.practica_id`). Se agrupa por autorización y se descuenta **una unidad por grupo**.
   Dos prácticas bajo la misma autorización son un grupo: una unidad.
4. Cada grupo pasa por el mismo `UPDATE` condicional de 04.05 (el saldo lo decide el motor) y deja
   su fila `CONSUMO` en el ledger, con `referencia_origen = sesionId`.

### 2.2 El resultado

El `spi` devuelve **una lista** de `ResultadoDeConsumo`, uno por autorización involucrada. Cuando no
hay ninguna, la lista trae **un** desenlace global (`SIN_AUTORIZACION_ELEGIBLE`,
`SIN_AUTORIZACION_PARA_LA_PRACTICA` o el nuevo `SIN_COBERTURA_VIGENTE`). Nunca viene vacía y nunca
lanza por falta de saldo: el cierre clínico no falla por el consumo (decisión de 04.05, DP-06).

### 2.3 Idempotencia por sesión, no sólo por autorización

El unique `uk_autorizacion_movimiento_origen` ya es por (autorización, tipo, origen, sesión): es
exactamente el "(sesión, autorización)" que DP-12 pide. Pero **no alcanza solo**, y es el defecto
que este paquete encontró (§8): el re-disparo vuelve a elegir candidatas, y la autorización que el
primer disparo dejó **agotada** ya no habilita, así que el segundo disparo elige **otra** que cubre
la misma práctica y descuenta de nuevo. Por eso el servicio lee primero los `CONSUMO` que la sesión
ya dejó en el ledger: esas autorizaciones vuelven como `YA_CONSUMIDA` y **sus prácticas salen del
conjunto pendiente** antes de elegir. Sin prácticas, cualquier consumo previo de la sesión cierra el
caso.

## 3. DP-13 — alerta "consumo a revisar" al anular la deuda

### 3.1 Cómo viaja

`billing.ObligacionService.anular` llama, **dentro de su transacción**, a un `spi` nuevo de
`person`: `person.spi.AlertasDeConsumo#consumoARevisar(ConsumoARevisar)`. `billing` ya depende de
`person.spi` (`PacienteDirectory`) y **nadie depende de `billing`**, así que la arista no cierra
ningún ciclo — verificado con `ModuleArchitectureTest` (§7.2). Se descartó el evento post-commit:
la alerta tiene que existir si y sólo si la anulación commitea, y la auditoría va en la misma
transacción.

### 3.2 Qué hace `person`

1. Busca los `CONSUMO` del ledger con origen `SESION` = `obligacion.sesion_id` que **no** tengan
   `REVERSION` (si ya se revirtió, no hay nada que revisar).
2. Por cada uno inserta una fila en `autorizacion_alerta` (tipo `CONSUMO_A_REVISAR`) con
   `INSERT ... ON DUPLICATE KEY UPDATE`: el unique es por **movimiento de consumo**, así que anular
   dos obligaciones de la misma sesión (lo que F-4 va a producir: financiador + paciente) deja una
   sola alerta por consumo, y dos anulaciones concurrentes no chocan.
3. **No toca el saldo** ni el ledger. Audita `AUTORIZACION_CONSUMO_A_REVISAR` en la transacción.
4. Una obligación sin `sesion_id` o una sesión que no consumió: no hace nada.

### 3.3 Cómo se resuelve

`revertir` (RF-M17-005) marca resuelta la alerta pendiente de ese consumo (`resolucion = REVERTIDO`)
en la misma transacción. Se lee con `GET /api/v1/autorizaciones/{id}/alertas` y el saldo suma el
campo `consumosARevisar` (pendientes). Los dos cambios son aditivos → **0.59.0**.

## 4. Cobertura vigente al consumir

La autorización se otorgó bajo una cobertura del paciente (`autorizacion.cobertura_id`, B-2). Si
esa cobertura **no está vigente** el día de la atención —dada de baja, vencida o todavía no
empezada— la autorización no es candidata. Se evalúa con `CoberturaPaciente#vigenteEl`, el mismo
criterio que `CoberturasAplicablesService` (B-2). Una credencial vencida **no** excluye: es alerta,
no invalidez (mismo criterio que B-2). Si había autorizaciones habilitadas y todas caen por
cobertura, el desenlace es `SIN_COBERTURA_VIGENTE` y el cierre sigue.

## 5. Caso al consumir

RF-M17-007 pide "no reutilizar autorización de otro Caso". La autorización no tiene `caso_id`: el
vínculo con un caso existe sólo cuando un ítem de plan la ata (RF-M11-007, `plan_item.autorizacion_id`).

- `SesionCerrada` suma `casoId` (nullable): la sesión ya lo tiene.
- El observador (`encounter.infrastructure`) pregunta a `clinical.spi.AutorizacionesDelCaso` qué
  autorizaciones están atadas a planes de **otros** casos de la misma historia clínica y **no** a
  uno de este. `encounter → clinical.spi` ya existe.
- Esas autorizaciones viajan en `ConsumoPorSesion.autorizacionesDeOtroCaso` y `person` las excluye.
- **Sesión sin caso** (hoy, la mayoría: el gate RF-M10-007 es C-9): no se excluye nada. Excluir
  todo lo atado a un caso apagaría el consumo de las sesiones viejas, el mismo error que 06.04
  evitó con el vacío de prácticas.

## 6. `RESERVA` / `LIBERACION_DE_RESERVA`

Se buscó en M17, M12 y M13 del documento de requerimientos un RF que diga cuándo se aparta una
unidad: no hay ninguno. Quedan **afuera**, declarado en la ficha de F4.

## 7. Design challenge

1. **Ownership.** `autorizacion_alerta` es de `person`, igual que `autorizacion` y el ledger. Sólo
   `person` la escribe y la lee; `billing` la alcanza por `person.spi`, nunca por repositorio.
2. **Ciclos.** Aristas nuevas: `billing → person.spi` (ya existía por `PacienteDirectory`) y
   `encounter → clinical.spi` (ya existía por `CasoDirectory`). Ninguna nueva entre módulos.
   `clinical` no gana dependencia nueva. Verificado con `ModuleArchitectureTest` después de
   escribir el código, no razonando el grafo (la lección de 04.05).
3. **Tenant.** La tabla lleva `organization_id` primero en el unique y en los índices. Toda lectura
   filtra por la organización del contexto; una autorización de otro tenant responde **404** también
   en `/alertas`. La consulta de `clinical` filtra por organización.
4. **Reglas maestras.** No se confunde obligación con consumo: anular la deuda **no** devuelve la
   unidad (DP-13) — la prestación ocurrió. Turno ≠ Sesión: nada se reserva al agendar.
5. **Baja lógica.** Nada se borra. La alerta se resuelve con columnas (`resuelta_en`, `resolucion`),
   el consumo original sigue en el ledger.
6. **Contrato.** Aditivo: un `GET` nuevo y un campo nuevo en la respuesta del saldo. **0.59.0**.
7. **Ruta crítica.** Los cimientos existen: ledger (04.05), prácticas realizadas (06.04), cobertura
   aplicable (B-2), anulación de obligación (07.01). A-9 (puente Oferta↔Práctica) **no** hace falta:
   DP-11 dice que al consumir manda la práctica realizada; la principal de la oferta sólo entra para
   la sesión sin tratamientos, y para ese caso se conserva el comportamiento de 04.05.
8. **El caso que rompe el diseño.** *Dos sesiones del mismo paciente cierran a la vez, cada una con
   prácticas de dos autorizaciones, y a una de ellas le queda una sola unidad.* Cada grupo es un
   `UPDATE` condicional independiente: una sesión gana la última unidad, la otra recibe `SIN_SALDO`
   **para ese grupo** y consume igual la otra autorización; ninguna falla y `cantidad_consumida`
   nunca pasa lo autorizado. El orden de los `UPDATE` es por id de autorización en las dos
   transacciones, así que dos cierres que tocan las mismas dos filas no se bloquean en cruz
   (deadlock). Segundo caso: *el re-disparo del mismo cierre después de agotar la autorización* —
   §2.3, es el defecto que este paquete corrige.

## 8. Defecto encontrado

**El re-disparo de un cierre podía descontar dos veces la misma práctica.** Ver §2.3. Lo reproduce
`ConsumoPorAutorizacionIT#el_redisparo_despues_de_agotar_no_consume_otra`; el arreglo es leer los
consumos previos de la sesión antes de elegir.

**Alcance real:** el `spi` prometía idempotencia por sesión y no la cumplía. Por el camino normal
el defecto queda tapado más arriba —`SesionService.cerrar` sale temprano si la sesión ya está
cerrada, y dos cierres concurrentes los separa el `@Version` de `Sesion`—, así que hoy hace falta un
re-disparo del observador para verlo. Con DP-12 la ventana se agranda (más autorizaciones por
sesión, más chances de que una quede agotada por el propio disparo) y F-4 va a construir sobre este
`spi`: por eso se corrige ahora.

**Verificación por mutación:** apagar la lectura del ledger de la sesión, el filtro de cobertura y
el de caso hace fallar exactamente los tres escenarios que los cubren
(`el_redisparo_despues_de_agotar_no_consume_otra`, `cobertura_vencida_no_consume`,
`autorizacion_de_otro_caso_no_se_consume`) y nada más.

## 9. Decisiones a revisar por el usuario

1. **Descartar una alerta sin revertir** (cortesía, error de precio). Hoy no hay forma: la alerta
   queda pendiente hasta que alguien revierte. Si se quiere, es un `POST` con motivo y permiso
   `paciente:manage`.
2. **Sesión sin caso y autorización atada a un caso:** hoy se consume. Cuando C-9 haga obligatorio el
   caso, conviene revisar si se excluye.
3. **Autorización atada a planes de dos casos** (nada lo impide al vincular): hoy se admite en las
   sesiones de los dos.
