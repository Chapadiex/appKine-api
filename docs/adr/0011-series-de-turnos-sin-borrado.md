# ADR-0011 — Series de turnos sin borrado: la primera ausencia no elimina la serie

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

`AKINE_info.txt` describe el flujo histórico de recurrencia: al planificar un tratamiento
se generan los turnos/sesiones futuros y, **si el paciente falta al primero, se elimina la
serie completa**. La especificación moderna (M11/M12) separa el Plan de Tratamiento del
Turno y prohíbe el borrado físico de información histórica.

El choque afecta cuatro cosas a la vez —recurrencia, auditoría, cupos y notificaciones—:

- Borrar la serie destruye la evidencia de que esos turnos existieron: no queda rastro
  para auditoría, ni para métricas de ausentismo, ni para políticas de no-show.
- Los cupos se liberan "en silencio": la agenda cambia sin que nadie haya decidido nada.
- Las notificaciones ya enviadas ("tenés turno el martes") referencian turnos que dejaron
  de existir.
- El modelo histórico además confunde turno con sesión: genera "sesiones futuras", cuando
  una sesión es atención ocurrida, no prometida.

Había que resolverlo antes de AKINE-05.01–05.03, porque define el modelo de datos de la
agenda: si la serie es dueña de sus turnos o si cada turno es una entidad plena.

## Decisión

Los turnos recurrentes se vinculan mediante un **modelo explícito de serie/regla**, pero
**cada Turno conserva identidad, estado e historial propios**. La serie describe la
recurrencia; no es dueña del ciclo de vida de sus miembros.

- Una ausencia al primer turno se registra como **`AUSENTE`** en ese turno. **Nunca
  elimina la serie** ni los turnos futuros.
- Un actor autorizado puede **cancelar los turnos futuros pendientes** de la serie, con
  tres condiciones inexcusables: confirmación explícita, motivo obligatorio y auditoría.
- Los turnos pasados o ya ejecutados **permanecen inalterables**: ninguna operación sobre
  la serie los modifica.

La cancelación masiva es una transición de estado sobre cada turno pendiente — no un
`DELETE`, conforme a la convención de baja lógica de
[ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md).

## Alternativas consideradas

**Eliminar la serie ante la primera ausencia (flujo histórico).** Automática y "ordenada".
Descartada porque toma una decisión clínico-administrativa —abandonar el tratamiento— a
partir de un evento ambiguo —una ausencia, que puede ser un colectivo que no pasó—, y la
ejecuta destruyendo datos: sin registro de `AUSENTE` no hay política de no-show posible,
no hay métricas, no hay defensa ante un reclamo. El comportamiento deseado (liberar la
agenda) se conserva, pero como acción humana confirmada.

**La serie como entidad contenedora, con los turnos como filas sin estado propio.**
Simplifica la generación. Descartada porque la realidad opera turno por turno: uno se
reprograma, otro se cancela, otro se atiende. Si el estado vive en la serie, cada
excepción individual requiere "romper" la serie; si vive en el turno, la serie es solo la
regla que los generó — que es exactamente lo que M12 describe.

**No modelar series: turnos sueltos generados por el plan.** Descartada porque pierde el
vínculo operativo: "cancelar los futuros de esta serie" o "la serie del plan X" son
operaciones reales de recepción que, sin el vínculo explícito, se vuelven heurística
(mismo paciente, mismo horario, mismo profesional...).

## Consecuencias

### Positivas

- El historial de ausencias queda íntegro: las políticas de no-show, la facturación de
  inasistencias y las métricas de ausentismo tienen sobre qué operar.
- Ninguna agenda cambia sin actor, motivo y timestamp: la auditoría de M24 cubre la
  recurrencia sin casos especiales.
- Reprogramar o cancelar un turno individual no afecta a sus hermanos de serie.

### Negativas

- Los turnos `AUSENTE` y `CANCELADO` se acumulan: toda consulta de agenda y de
  disponibilidad debe filtrar por estado, siempre. Un filtro olvidado muestra basura.
- La cancelación masiva con confirmación es más fricción que el borrado automático
  histórico; recepción hace un paso extra que antes "era gratis".
- Serie + turnos + estados es un modelo más grande que "lista de turnos": más tablas, más
  transiciones, más tests.

### Qué obliga a hacer

- `scheduling` es propietario de Serie y Turno; el Plan de Tratamiento (`clinical`, M11)
  referencia la serie vía `spi`, sin tocar sus tablas
  ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)).
- Ninguna operación de serie ejecuta `DELETE` sobre turnos: solo transiciones de estado
  auditadas ([ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md)).
- La cancelación masiva expone en el contrato API la confirmación explícita y el motivo
  obligatorio; el backend rechaza la operación sin ellos.
- La liberación de cupos y las notificaciones reaccionan a transiciones de estado, no a
  la desaparición de filas. Etapas: AKINE-05.01–05.03.
