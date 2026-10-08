# AKINE B-4 — Historial de estados de la autorización y estado de la orden médica

**08/10/2026** · rama `akine-B-4-historial-autorizacion` · migración `V85` (reservada `V68`, que
quedó por debajo de `V83`) · contrato `0.78.0` · ficha [F3](../fases/F3-personas-y-cobertura.md) ·
decisión **DP-23** (dueño del producto, 08/10/2026, resuelve DU-8).

## 1. Qué decide DP-23 y qué cambia

Hasta acá el único rastro de cómo cambió una autorización era `audit_event`
(`AUTORIZACION_RESUELTA`, `AUTORIZACION_UPDATED`…), que es de `organization`, se lee con
`auditoria:read` —el mostrador no lo tiene— y mezcla todas las entidades. La ficha F3 lo marcaba
como desvío silencioso. **DP-23**: el historial vive en una tabla propia de `person`,
`autorizacion_evento`, append-only, como `turno_evento` y `recepcion_evento`, y se escribe en la
misma transacción que cada mutación. La auditoría sigue escribiéndose igual: son dos datos con dos
lectores distintos.

## 2. La tabla

`autorizacion_evento` (dueño `person`): `organization_id`, `autorizacion_id`, `persona_id`,
`consultorio_id`, `tipo`, `estado_anterior` (NULL sólo en el `ALTA`), `estado_nuevo`, `activa`
(ciclo de vida después del hecho), `cantidad` y `movimiento_id` (sólo consumo y reversión),
`detalle` (≤500, administrativo), `motivo` (≤1000), `actor_cuenta_id`, `ocurrido_en`.

| Constraint | Qué asegura |
|---|---|
| `uk_autorizacion_evento_movimiento (organization_id, movimiento_id)` | Un evento por movimiento del ledger. Las filas sin movimiento llevan NULL y no colisionan |
| `ck_autorizacion_evento_alta_sin_anterior` | Sólo el alta va sin estado anterior |
| `ck_autorizacion_evento_movimiento_coherente` | Movimiento y cantidad juntos, y sólo en `CONSUMO`/`REVERSION_DE_CONSUMO` |
| FK a `organization`, `autorizacion`, `persona`, `autorizacion_movimiento` | Todo con la tabla adelante (los nombres son únicos por esquema) |
| `ix_autorizacion_evento_autorizacion (organization_id, autorizacion_id, ocurrido_en, id)` | La lectura paginada |

`V85` siembra un `ALTA` por cada autorización existente, con su estado de ese día y el `detalle`
declarando que es reconstruido. Lo anterior no se inventa a partir de la auditoría.

## 3. Los eventos y quién los escribe

| Evento | Mutación | Estado | Detalle / motivo |
|---|---|---|---|
| `ALTA` | `AutorizacionService.registrar` | — → PENDIENTE o APROBADA | cantidad y vigencia |
| `APROBACION` · `OBSERVACION` · `RECHAZO` | `resolver` | anterior → destino de la acción | lo que la aprobación parcial cambió; motivo |
| `MODIFICACION` | `editar` | igual | campos cambiados; **las observaciones se nombran, no se copian** (texto libre). Sin cambios, no hay evento |
| `DOCUMENTO` | `vincularDocumento` | igual | vinculado (adjunto) / desvinculado |
| `CONSUMO` | `ConsumoDeAutorizacionService.consumirUna` (cierre de sesión, 04.05/C-4) | igual | sesión; movimiento y cantidad |
| `REVERSION_DE_CONSUMO` | `revertir` (RF-M17-005, DP-13) | igual | consumo revertido; motivo |
| `ANULACION` | `darDeBaja` | igual, `activa = false` | motivo |

**El vencimiento no es un evento.** Es función del reloj: ninguna lectura escribe, no hay job,
y `EstadoAutorizacion` ya calcula VENCIDA al leer. El historial lo devuelve **calculado** en la
respuesta (`vencida`, `vencidaDesde` contra `fecha`). Si algún día se materializara con un job,
sería un evento más (`VENCIMIENTO`, aditivo en el CHECK y en el contrato).

La alerta "consumo a revisar" de DP-13 **no** es un evento: no mueve estado ni saldo y tiene su
recurso (`GET /autorizaciones/{id}/alertas`).

## 4. Lectura

`GET /api/v1/personas/{personaId}/autorizaciones/{autorizacionId}/historial?page&size&fecha`
(`getHistorialDeAutorizacion`), del hecho más viejo al más nuevo, paginado por offset (tope 100,
default 50: una autorización tiene decenas de hechos, no miles). Mismo permiso que
`getAutorizacion`: pertenencia al tenant; 404 cruzado.

## 5. Estado de la orden médica

Ningún RF de M17 define una máquina de estados de la orden; el plan de 03.06 pide "estado,
vigencia y consumo". La orden no tiene actos propios que cambien su estado: lo mueven el reloj,
el financiador y las sesiones. Por eso es **derivado y no se guarda**
(`person.domain.SituacionOrdenMedica`), como VENCIDA y AGOTADA de la autorización. Viaja en
`Orden.situacion` y `Orden.sesionesConsumidas` (aditivo, el `estado` ACTIVA/INACTIVA no cambia).
Precedencia: `ANULADA` > `CUMPLIDA` > `VENCIDA` > `EN_CURSO` > `AUTORIZADA` > `EN_TRAMITE` >
`RECHAZADA` > `SIN_AUTORIZACION`. Sólo cuentan las autorizaciones **activas** que apuntan a la
orden; lo consumido sale de las APROBADAS. La lectura del listado hace una consulta de
autorizaciones por paciente, no una por orden.

## 6. Design challenge

1. **Ownership.** `autorizacion_evento` es de `person`, igual que `autorizacion`,
   `autorizacion_movimiento` y `autorizacion_alerta`. Ningún otro módulo la toca: el consumo llega
   por `person.spi.ConsumoDeAutorizaciones` y lo escribe `person`.
2. **Ciclos.** No hay aristas nuevas entre módulos. ArchUnit no cambia.
3. **Tenant.** `organization_id` NOT NULL con FK, encabeza el unique y el índice. Toda consulta del
   puerto lo lleva; la lectura carga la autorización por `(id, organización, persona)` antes de
   leer eventos.
4. **Reglas maestras.** El consumo sigue naciendo sólo del cierre de sesión (DP-05): el historial
   lo registra, no lo provoca. Anular la deuda no escribe evento (DP-13).
5. **Baja lógica.** Append-only: sin UPDATE ni DELETE en ningún camino. La baja de la autorización
   es un evento más.
6. **Contrato.** Aditivo: un endpoint, dos schemas nuevos (`HistorialDeAutorizacionResponse`,
   `EventoDeAutorizacionResponse`) y dos campos nuevos en `Orden`. Minor `0.78.0`.
7. **Ruta crítica.** Los cimientos existen: autorización (03.06), ledger (04.05), reversión
   manual (DP-13). No adelanta nada.
8. **El caso que rompe el diseño.** *Dos consumos concurrentes de la última unidad.* El evento se
   escribe **después** del `UPDATE` condicional y del movimiento, en la misma transacción: el que
   ve cero filas no escribe ni movimiento ni evento, y el ganador escribe uno de cada uno. Si dos
   transacciones llegaran con el mismo movimiento —el unique del ledger ya lo impide—,
   `uk_autorizacion_evento_movimiento` haría fallar a la segunda. *Una mutación que hace
   rollback después de escribir el evento:* el evento cae con ella, porque es la misma
   transacción (`HistorialDeAutorizacionIT#rollback_no_deja_evento`).
   *Una edición concurrente:* el evento se escribe después del `saveAndFlush`; si el flush muere
   por `@Version`, no llega a escribirse.

## 7. Lo que queda afuera

- La pantalla del historial y de la situación de la orden (otra tanda, `appKine-web`).
- `VENCIMIENTO` como evento: sólo si algún día se materializa el vencimiento.
- La historia previa a `V85` de las autorizaciones existentes: queda el `ALTA` reconstruido.
