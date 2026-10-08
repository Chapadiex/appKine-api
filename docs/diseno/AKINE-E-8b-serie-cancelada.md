# AKINE E-8b — Una serie cancelada figura como `CANCELADA` (DP-20)

**08/10/2026** · rama `akine-E-8b-serie-cancelada` · contrato **0.75.0** · **sin migración**.

## 1. Qué cambia

E-8 publicó el estado derivado de una serie con dos valores, `VIGENTE` y `FINALIZADA`, y una serie
cancelada se veía igual que una que se agotó. DP-20 pide distinguirlas. `EstadoDeSerie` gana un
tercer valor, `CANCELADA`, en la fila de la bandeja (`SerieDeTurnosResumen.estado`) y en el filtro
`estado` de `listarSeriesDeTurnos`.

## 2. La regla

Sobre **todos** los turnos de la serie —también los cancelados, que desde 05.03 llevan
`deleted_at` para liberar el lugar—, en un instante `ahora`:

| Estado | Cuándo |
|---|---|
| `VIGENTE` | le queda un turno RESERVADO o CONFIRMADO, vivo y con `inicio > ahora`. Manda sobre las otras dos |
| `CANCELADA` | no le queda ninguno y **su último turno está cancelado**: ningún turno no cancelado empieza en el mismo instante o después que el último cancelado |
| `FINALIZADA` | cualquier otro caso: su último turno pasó (atendido o no) o quedó `AUSENTE` |

Lo que decide es **cómo terminó la serie**: si se la cortó o si llegó a su fin.

| Caso | Estado |
|---|---|
| "toda la serie" cancelada antes de empezar | `CANCELADA` |
| atendida la primera, "este y los siguientes" desde la segunda | `VIGENTE` mientras la primera no pase; después `CANCELADA` |
| los tres turnos pasaron, uno cancelado suelto en el medio | `FINALIZADA` |
| el último quedó `AUSENTE` y los anteriores cancelados | `FINALIZADA` |
| empate de instante entre un cancelado y uno no cancelado | `FINALIZADA` |
| el último cancelado suelto, con pendientes antes | `VIGENTE` |

Dónde vive: `EstadoDeSerie.de(turnos, ahora)` en el dominio, y la misma regla en JPQL en
`TurnoSerieRepository.WHERE_BANDEJA`, armada con tres constantes (`TURNO_DE_LA_SERIE`,
`HAY_PENDIENTE`, `ULTIMO_CANCELADO`) que comparten la consulta de la página y la del total. El
servicio sigue calculando el resumen y filtrando con **el mismo `ahora`**.

## 3. Alternativas descartadas

- **Leer `turno_evento` o la auditoría** para saber si la cancelación vino de una operación con
  alcance. Acopla el estado a *cómo* se canceló, y cancelar suelto el último turno también es
  cortar la serie. Además la auditoría vive en otro módulo.
- **"Todos sus turnos no atendidos están cancelados".** Una serie agotada con una cancelación en
  el medio y el resto atendido quedaría `CANCELADA`, que es justo lo que no es.
- **Persistir el estado en `turno_serie`.** E-3 (ADR-0011, DP-04) decidió que la serie no guarda
  estado: una columna puede contradecir a sus turnos. No hace falta: la regla se calcula al leer.

## 4. Design challenge

1. **Ownership.** Sin tablas nuevas. Todo queda en `scheduling`.
2. **Ciclos.** Ninguna dependencia nueva entre módulos.
3. **Tenant.** Los subqueries correlacionan por `organization_id` y `serie_id`, igual que el de E-8.
4. **Reglas maestras.** El estado sigue sin persistirse ni gobernar sus turnos (DP-04). Turno ≠
   Sesión: un turno pasado RESERVADO cuenta como "llegó a ocurrir", sin afirmar que hubo atención.
5. **Baja lógica.** Sólo lecturas. La regla **lee** los cancelados, que tienen `deleted_at`: por
   eso el `deleted_at IS NULL` quedó sólo en el predicado de "pendiente".
6. **Contrato.** Aditivo: un valor más en el enum del filtro y en `allowableValues` de la fila.
   Minor `0.75.0`. Un cliente viejo que reciba `CANCELADA` en un `String` no se rompe; el cliente
   TypeScript regenerado gana el literal.
7. **Ruta crítica.** Todo construido: E-3 y E-8 están en `main`.
8. **El caso que rompe el diseño.** Que el filtro y la fila diverjan: una serie filtrada como
   `CANCELADA` que se muestre `FINALIZADA`. Lo cierran dos cosas: la regla de la fila y la del
   filtro son la misma, escrita una vez en Java y una vez en JPQL con el mismo `>=` del empate; y
   `SerieDeTurnosIT#bandeja_distingue_cancelada_de_finalizada` comprueba contra MySQL que **cada**
   filtro devuelve exactamente las filas que el listado sin filtro muestra con ese estado, con el
   total que cuadra. El segundo caso adverso es el costo: el subquery de `ULTIMO_CANCELADO` es un
   `NOT EXISTS` anidado, pero los dos niveles se resuelven por `ix_turno_serie_inicio
   (organization_id, serie_id, inicio)` sobre a lo sumo 52 turnos por serie (tope de E-3).

## 5. Tests

- `EstadoDeSerieTest` (7 unitarias): cada fila de la tabla del §2 y la serie sin turnos.
- `SerieDeTurnosServiceTest#resumen_cuenta_pendientes`: el caso que E-8 llamaba "terminada"
  —pasado + cancelado futuro— pasa a `CANCELADA`, y uno con último `AUSENTE` queda `FINALIZADA`.
- `SerieDeTurnosIT` contra MySQL: `bandeja_de_series` ahora espera `CANCELADA` para la serie
  cancelada entera, y `bandeja_distingue_cancelada_de_finalizada` arma las cuatro (entera, cortada
  con una atención previa, agotada por fecha con una cancelación en el medio, vigente) y compara
  cada filtro contra el listado.
