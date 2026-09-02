
# Registro de cierre — AKINE-05.04 reducida (Recepción y check-in)

**02/09/2026** · rama `akine-01.02-identidad`. `V39`, cuatro endpoints y contrato `0.23.0`.
Primera etapa fuera del Paquete B: DP-10 la había diferido nombrándola "riesgo aceptado".

- **Recableado DP-10, tercera vez.** El plan la hace depender de 03.06 (órdenes) y 04.05 (consumo
  de autorizaciones), las dos fuera de alcance. Con cobertura PARTICULAR única **no hay condición
  administrativa que validar**, así que la etapa se reduce a lo que sí tiene sentido hoy: buscar
  los turnos del día, registrar la llegada real y poder deshacerla. El snapshot administrativo
  preliminar que pide el plan es una tabla que colgará de esta misma fila cuando exista.
- **Tapa un hueco que el QA del 01/09 dejó a la vista: no existía ninguna lectura de un turno.**
  El contrato sólo publicaba slots libres de una oferta y el historial de transiciones, así que la
  pantalla de ciclo de vida de 05.03 tuvo que **deducir el estado desde los eventos**. Ahora hay
  `GET /turnos/{id}` y `GET /turnos?fecha=`.
- **El check-in es un estado de la RESERVA, no de la atención** (DP-05). La recepcionista marca la
  llegada sin abrir ninguna atención y el profesional abre la atención sin depender de que alguien
  la haya marcado. Son dos actos de dos personas y ninguno bloquea al otro.
- **La hora la pone el servidor y el cuerpo va vacío.** Es evidencia administrativa: aceptar una
  hora del cliente significaría que el reloj del mostrador decide a qué hora llegó un paciente.
- **El listado incluye los cancelados, con su motivo.** Es la única consulta de `TurnoRepository`
  sin filtro de baja lógica, y es deliberado: alguien se presenta con un turno que se canceló y
  una lista que los esconda deja a la recepción sin nada que decirle.
- **Un turno EN_ESPERA se puede cancelar sin deshacer antes la llegada.** Es el caso "el paciente
  vino y el profesional no lo pudo atender". Obligar a deshacer primero borraría la evidencia de
  que vino, que es lo que la recepción existe para registrar. Reprogramar y marcar ausencia sí
  quedan fuera desde EN_ESPERA: mover un turno cuya hora ya llegó no tiene sentido, y afirmar que
  no vino alguien que está en la sala es falso.
- **El `CHECK` de `V39` nació mal y lo mostró el test, no la revisión.** La primera versión exigía
  que *sólo* un turno EN_ESPERA tuviera hora de llegada, lo que hace imposible el punto anterior.
  La invariante correcta va en un solo sentido: **en espera ⟹ hay llegada**, nunca la vuelta.
- **Check-in idempotente; deshacerlo no.** El doble click en el mostrador es el caso normal.
  Deshacer dos veces no lo es: entre medio el turno pudo cambiar de estado, y un 200 en silencio
  le haría creer al operador que revirtió algo.
- **PHI mínima:** nombre, documento y nombre comercial de la oferta. **Nada clínico.** Por eso
  `TurnoDelDiaView` es un tipo aparte de `TurnoView` y no campos opcionales sobre el mismo: dos
  respuestas con necesidades distintas de PHI no deben compartir forma, porque el día que alguien
  agregue un campo se lo agrega a las dos.
- **`PacienteDirectory.findAll` nace para que la lista no haga N consultas.** Un día de agenda son
  decenas de turnos y cada uno necesita nombre y documento; resolverlos de a uno convierte la
  pantalla en tantas consultas como turnos haya — justo el día en que más se la necesita.
- **Hallazgo de plataforma, no de la etapa: MySQL REDONDEA `DATETIME(6)`, no trunca.** La respuesta
  del primer check-in sale de la entidad en memoria con nanos y la del segundo de la fila
  redondeada, así que difieren en dígitos que la base nunca guardó. El test compara **lo
  almacenado**, que es lo único que el sistema promete. Vale para toda marca de tiempo del
  proyecto.
- **Verificado contra MySQL real:** `RecepcionIT`, 5 escenarios — la fila refleja el check-in con
  su responsable, el doble click no mueve la hora guardada, deshacer limpia la hora y el historial
  conserva los dos eventos, la agenda del día trae los cancelados con motivo y el paciente
  resuelto, y un EN_ESPERA se cancela conservando su llegada.
- **Deudas: sin frontend, sin E2E y sin QA manual** del §6. La pantalla de recepción no existe
  todavía.
