# ADR-0012 — Máquinas de estado separadas para Turno, Check-in y Sesión

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

El `DTE.pdf` histórico define **una sola máquina de estados** que arranca en la
disponibilidad del slot y termina en la atención finalizada: reserva, llegada, espera,
atención y cierre son estaciones del mismo riel. La especificación moderna (M12, M13, M14)
exige tres máquinas separadas: Turno, Check-in/Recepción y Sesión.

La máquina única tiene un defecto estructural que la deuda documental ya señala: **absorbe
la recepción y la ejecución clínica dentro del turno**. Consecuencias concretas:

- Una transición administrativa termina "probando" un hecho clínico: si el estado
  `ATENDIDO` del turno es lo único que existe, un click de recepción certifica que hubo
  prestación — y de la prestación cuelgan obligaciones y facturación (M18).
- Los tres procesos tienen actores, permisos y tiempos distintos: la reserva la maneja
  agenda, la llegada la maneja recepción, la atención la registra el profesional.
- Cada variante operativa (paciente sin turno, sesión que no pasó por recepción) exige
  estados artificiales en un riel que ya mezcla tres vocabularios.

El impacto declarado es la migración de estados y la UI de agenda; el riesgo real es
mayor: si la primera versión de la agenda nace con la máquina única, separar después
exige migrar estados en producción. Había que decidirlo antes de AKINE-05.03.

## Decisión

**Turno, Check-in/Recepción y Sesión tienen máquinas de estado independientes y
controladas por el backend.**

- **Turno** representa exclusivamente la reserva de agenda: reservar, reprogramar,
  cancelar, registrar ausencia. Un turno no demuestra atención.
- **Check-in/Recepción** representa el circuito administrativo de la visita: llegada,
  validación administrativa, espera y llamado.
- **Sesión** representa la atención clínica real: lo que efectivamente ocurrió, registrado
  por el profesional.

Se coordinan mediante **comandos y eventos explícitos** — el llamado de recepción puede
disparar la creación de la sesión; el cierre de la sesión puede reflejarse en el turno —
pero **ninguna transición administrativa prueba por sí sola que una prestación ocurrió**:
la prestación la acredita la Sesión, únicamente.

**Cada transición registra actor, fecha, estado anterior, estado nuevo y motivo cuando
corresponda**, siguiendo las convenciones de persistencia y auditoría de
[ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md).

## Alternativas consideradas

**Máquina única estilo DTE (modelo histórico).** Una sola entidad, una sola tabla, una UI.
Descartada porque acopla tres procesos con dueños distintos: recepción queda habilitada a
"finalizar atenciones", el módulo de agenda necesita conocer estados clínicos, y cada
variante nueva (sesión grupal de M28, por ejemplo) agrega estados a un autómata ya
sobrecargado. Es además la fuente de la deuda señalada en el UML de 2019: Turno, Atención
y Cobro fundidos.

**Dos máquinas: Turno (con el check-in adentro) y Sesión.** Reduce la separación al punto
"administrativo vs clínico". Descartada porque la recepción tiene ciclo propio (M13):
validación de cobertura, sala de espera, orden de llamado. Metida en el turno, cada estado
de espera contamina la agenda; separada, la agenda muestra reservas y recepción muestra
la sala — cada pantalla con su máquina.

**Una máquina por entidad, con transiciones automáticas en cascada.** Descartada en su
forma implícita: si cerrar el check-in cierra "solo" el turno y abre "solo" la sesión, la
cascada silenciosa reintroduce el acople por la puerta de atrás. Las coordinaciones
existen, pero como comandos y eventos explícitos, visibles en el código y en la auditoría.

## Consecuencias

### Positivas

- La frontera administrativa/clínica queda en el tipo, no en la disciplina: recepción no
  puede certificar prestaciones porque su máquina no tiene ese estado.
- Cada módulo evoluciona su máquina sin tocar las otras: agregar "confirmación por
  mensaje" al turno no toca la sesión.
- La facturación (M18) cuelga de la Sesión — del hecho clínico — y no de un estado de
  agenda; el circuito económico hereda una base honesta.

### Negativas

- Tres máquinas y sus coordinaciones son más superficie que un autómata único: más
  transiciones que testear, y la posibilidad nueva de **estados incoherentes entre
  máquinas** (turno finalizado sin sesión, sesión abierta con turno cancelado), que exigen
  invariantes de coordinación explícitas.
- La UI de agenda compone información de dos o tres orígenes para mostrar "cómo viene el
  día"; el frontend paga parte del costo.
- El vocabulario histórico ("sesión/consulta/atención" intercambiables) ya no alcanza:
  hay que unificar la nomenclatura en pantallas y reportes.

### Qué obliga a hacer

- `scheduling` es propietario de las máquinas de Turno y de Check-in; `clinical`, de la
  máquina de Sesión. La coordinación cruza módulos solo vía `spi` o eventos
  ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)).
- Toda tabla de transiciones persiste actor, timestamps UTC, estado origen/destino y
  motivo ([ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md)).
- Las invariantes de coordinación (qué combinaciones son legales) se especifican y testean
  en la etapa que las introduce: AKINE-05.03 (estados de turno), 05.04 (check-in),
  06.01–06.06 (sesión).
- Ningún endpoint administrativo escribe estados de Sesión, y ningún endpoint clínico
  escribe estados de Turno o Check-in.
