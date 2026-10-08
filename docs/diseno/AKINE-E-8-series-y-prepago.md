# AKINE E-8 — Bandeja de series y prepago antes del check-in

**07/10/2026** · rama `akine-E-8-series-y-prepago` · sin migración (`V82` reservada y vacía) ·
contrato `0.68.0` · ficha [F5](../fases/F5-agenda-y-recepcion.md) · paquete E-8 de
[01-trabajo-en-paralelo](../fases/01-trabajo-en-paralelo.md) (parte backend).

Dos huecos chicos que reportaron las pantallas de E-3 y E-6. Los dos son lecturas: no cambia
ninguna regla de escritura.

## 1. Bandeja de series

E-3 dejó a la serie alcanzable sólo por su id (`verSerieDeTurnos`). Una pantalla que quiere
mostrar "las series de esta sede" o "las series de este paciente" no tenía de dónde sacarlas.

`GET /api/v1/consultorios/{consultorioId}/series-de-turnos?personaId=&estado=&page=&size=`
(`listarSeriesDeTurnos`), con `turno:read`, el mismo permiso que ver una serie.

| Campo de la fila (`SerieDeTurnosResumen`) | De dónde sale |
|---|---|
| `id`, `consultorioId`, `ofertaId`, `profesionalId` | `turno_serie` |
| regla resumida: `frecuencia`, `diasSemana`, `hora`, `fechaDesde`, `fechaHasta`, `cantidad`, `timezone`, `creadaEn` | `turno_serie`, igual que en `SerieDeTurnos` |
| `personaNombre`, `documento` | `person.spi.PacienteDirectory.findAll`, en lote |
| `ofertaNombre` | `offering.spi.OfertaDirectory`, una vez por oferta distinta |
| `totalTurnos`, `turnosPendientes`, `proximoTurnoInicio` | los turnos de las series de la página, en una consulta |
| `estado` | **derivado** de esos turnos (§1.1) |

Página con la forma de todos los listados (`content`, `page`, `size`, `totalElements`,
`totalPages`), recortada en la base, más nuevas primero (`id DESC`). `size` se acota a 100.

### 1.1 El estado es derivado, no una columna

La serie **no tiene estado** y E-3 lo decidió a propósito (ADR-0011): "la serie está cancelada" es
un hecho de sus turnos, no una columna que pueda contradecirlos. La bandeja necesita filtrar
"las que siguen" y "las que terminaron", así que el estado se **calcula al leer**:

- `VIGENTE`: le queda al menos un turno **pendiente** —`RESERVADO` o `CONFIRMADO`, con
  `deleted_at` nulo e `inicio > ahora`—.
- `FINALIZADA`: no le queda ninguno (todos pasaron, se cancelaron o se marcaron ausentes).

El filtro va en la base con un `EXISTS` sobre `turno` (lo sirve `ix_turno_serie_inicio`), así la
paginación y el total son correctos. El resumen de cada fila usa **el mismo predicado** en Java y
**el mismo instante** con que se filtró: si divergieran, una serie filtrada como `VIGENTE` podría
mostrarse `FINALIZADA`.

No se distingue "cancelada" de "terminada": las dos son "no queda nada por atender", y separarlas
exigiría decidir qué es una serie con dos turnos atendidos y ocho cancelados. Si la pantalla lo
necesita, es otro valor del enum (cambio aditivo).

> **Superado por DP-20 (08/10/2026):** el dueño del producto pidió distinguirlas. E-8b agregó
> `CANCELADA` —sin pendientes y con el último turno cancelado— y la serie de dos atendidos y ocho
> cancelados quedó resuelta como `CANCELADA`. Ver `docs/diseno/AKINE-E-8b-serie-cancelada.md`.

### 1.2 Costo por página

Cuatro consultas, ninguna por fila: el total, las series, los turnos de las series de la página
(una serie tiene a lo sumo 52) y los pacientes; más una por oferta distinta. Una página más allá
del total sale sin consultar series ni turnos.

### 1.3 Tenant

Toda consulta lleva `organization_id` y la sede. Sede de otro tenant → 404
(`ConsultorioNoAccesibleException`, como el resto del módulo). `personaId` de otro tenant →
página vacía: es un filtro que no encuentra nada, no un recurso que se intenta leer.

## 2. Prepago visible antes del check-in

E-6 calculaba el prepago sólo para turnos con recepción (`Recepcion.prepago`). La agenda del día
no sabía que una oferta lo exige hasta que alguien registraba la llegada, que es justo cuando ya
es tarde para avisar.

`TurnoDelDia.prepago` (schema `PrepagoDeRecepcion`, el mismo de E-6, para que el cliente use un
solo tipo) se calcula para **todos** los turnos de la agenda y de `verTurno`:

```
con recepción vigente           -> exactamente la regla de E-6 (mismo objeto que recepcion.prepago)
sin recepción:
  hay anticipo vigente          -> REGISTRADO (también si el turno se canceló: hay plata para reintegrar)
  la oferta no exige prepago    -> NO_EXIGIDO
  turno CANCELADO o AUSENTE     -> NO_EXIGIDO
  cualquier otro caso           -> PENDIENTE, con el precio particular sugerido
```

- **Antes de la llegada no se sabe si hay cobertura**: eso lo decide la validación. `PENDIENTE` se
  lee "si se atiende como particular", y pasa a `NO_EXIGIDO` si la recepción valida con cobertura.
  Es la misma alerta sin bloqueo de DP-06: nada la consulta para decidir.
- **Sin N+1**: `PrepagoDeRecepcion.de(lote)` hace una sola pregunta a `billing` por
  `scheduling.spi.PrepagoDeTurnoProbe` para todo el día y una lectura de precio por oferta
  distinta. Antes se hacía lo mismo pero sólo con los turnos con recepción.
- **PHI mínima intacta**: el campo es económico-administrativo (estado, importe, moneda, cobro). No
  agrega nada del paciente ni de la prestación.

## 3. Contrato `0.68.0`, aditivo

- Operación nueva `listarSeriesDeTurnos` con los schemas `SerieDeTurnosPage` y
  `SerieDeTurnosResumen`.
- Propiedad nueva `TurnoDelDia.prepago` (`PrepagoDeRecepcion`).

## 4. Design challenge (CLAUDE.md §3)

1. **Ownership.** Sin tablas nuevas. `turno_serie` y `turno` son de `scheduling` y sólo
   `scheduling` las lee. El anticipo sigue en `billing` y se pregunta por el `spi` invertido de E-6.
2. **Ciclos.** Ninguna arista nueva: `scheduling → person.spi`, `→ offering.spi` y la sonda
   invertida ya existían. `ModuleArchitectureTest` 5/5.
3. **Tenant.** Las dos consultas nuevas filtran por `organization_id` (y la de series, por sede).
   IT: la sede ajena no ve las series, la sede de otro tenant es 404, el paciente de otro tenant da
   vacío, y lo que cobró un tenant no cambia la agenda del otro.
4. **Reglas maestras.** El estado de la serie no se persiste ni gobierna sus turnos (DP-04). El
   prepago sigue siendo alerta: Turno ≠ Recepción ≠ Sesión y Deuda ≠ Cobro intactos.
5. **Baja lógica.** Sólo lecturas.
6. **Contrato.** Aditivo, minor `0.68.0`.
7. **Ruta crítica.** Cimientos en `main`: series (E-3), recepción (E-4), prepago (E-6).
8. **El caso que rompe el diseño.** *Una serie cuyo último turno pendiente empieza mientras se
   pagina.* El filtro y el resumen usan el mismo `ahora`, tomado una vez por pedido: en la página
   la serie es coherente consigo misma; en el pedido siguiente pasa a `FINALIZADA`. Lo que no puede
   pasar es que aparezca filtrada como `VIGENTE` con `turnosPendientes = 0`.
   *Un turno con recepción `ANULADA`*: la recepción anulada no es vigente, así que la agenda usa la
   regla sin recepción y el prepago vuelve a `PENDIENTE` si la oferta lo exige; es correcto, la
   llegada no valía.

## 5. Verificación

- Unitarios: `SerieDeTurnosServiceTest` (bandeja sin N+1, página fuera de rango, resumen de
  pendientes) y `PrepagoDeRecepcionTest` (antes de la llegada, lote con y sin recepción).
- IT contra MySQL: `SerieDeTurnosIT#bandeja_de_series` (orden, filtros por persona y estado,
  paginado, aislamiento) y `PrepagoDeRecepcionIT#la_agenda_del_dia_ve_el_prepago_antes_del_check_in`
  (PENDIENTE sin recepción, REGISTRADO al cobrar, igual a `recepcion.prepago` tras la llegada, y
  tenant).

## 6. Fuera de alcance

- Las pantallas (mitad web).
- Filtrar la bandeja por profesional u oferta: ninguna pantalla lo pidió; son dos parámetros más
  sobre el mismo `WHERE`.
- Nombre del profesional en la fila: `TurnoDelDia` tampoco lo resuelve; la pantalla ya tiene la
  lista de profesionales de la sede.
