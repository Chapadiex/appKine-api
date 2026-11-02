# AKINE DP-45 (2) — Turnos de una sede por rango de fechas

> DP-45 punto 2 (`docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7). Contrato **0.145.0**, sin migración.

## 1. Qué se agrega

`GET /api/v1/consultorios/{consultorioId}/turnos/calendario` — `operationId: listarTurnosDeSede`.
Ruta propia porque `GET /turnos?fecha=` ya es la agenda del día y dos operaciones con igual método
y ruta no caben en OpenAPI.

| Parámetro | |
|---|---|
| `desde`, `hasta` | fechas locales de la sede; `hasta` **exclusivo**; obligatorios |
| `profesionalId` | membership, opcional |
| `ofertaId` | opcional |
| `incluirCancelados` | por defecto `false` |

- **Permiso:** `turno:read` (el mismo de la agenda del día, vía `SoporteEnAgenda`, que audita el
  acceso de soporte).
- **Tope:** 42 días (`VentanaDeAgenda.VENTANA_MAXIMA_TURNOS_DIAS`). Más de eso, o `hasta <= desde`:
  400 `validation-error` (`maxDays` en el problema). Sin paginación: el tope la acota.
- **Respuesta:** `TurnosDeSede { desde, hasta, timezone, turnos[] }`; cada elemento es
  `TurnoDeCalendario { turno: TurnoDelDia, profesionalNombre }`. Reusa `TurnoDelDia` completo
  (paciente, oferta, espacio, recepción, serie, cobertura, prepago) y no cambia ese schema.
  Orden: inicio, luego id. Sin contenido clínico.
- **Qué no hace:** no incluye clases (eso es la agenda unificada), no pagina, no filtra por estado
  más allá de cancelados, no agrega índice (la consulta es la misma de la agenda del día, por
  `organization_id, consultorio_id, inicio`).

## 2. Cómo

`RecepcionService.delRango` reutiliza `findDeLaSedeEnVentana` y `componer` (pacientes, ofertas,
recepciones y prepago en lote). Profesionales y ofertas se filtran en memoria sobre el lote ya
acotado por el tope. Nombres de profesional: `ConsultorioMembershipDirectory.findTodas` +
`AccountIdentityDirectory.identidadesDe` (dos consultas para todo el rango, como `ProyeccionDeClase`).
Un IT cuenta sentencias: iguales con 1 y con 5 turnos/profesionales.

## 3. Design challenge

1. **Ownership:** no hay tablas nuevas. Lee `turno` (scheduling) y, por `spi`, persona, oferta,
   organization e identity.
2. **Ciclos:** `scheduling` ya dependía de `organization.spi` y de `platform.spi.identity`;
   no se agrega arista entre módulos. ArchUnit pasa.
3. **Tenant:** todo filtra por `organization_id` y `consultorio_id`; sede ajena → 404
   (`ConsultorioNoAccesibleException`), sin contexto → 403.
4. **Reglas maestras:** es solo lectura de la reserva (Turno); no mezcla con Sesión ni cobros.
5. **Baja lógica:** no borra nada; los cancelados quedan en la base y se ven con la bandera.
6. **Contrato:** aditivo (operación y dos schemas nuevos), minor 0.145.0.
7. **Ruta crítica:** los cimientos (agenda, recepción, nombres) existen; no se adelanta nada.
8. **Caso que rompe:** una sede con agenda muy llena y rango de 42 días devuelve miles de filas
   sin paginar. Se acota con el tope y con los filtros; si el frontend lo necesita, se baja el tope
   en un solo lugar. Otro: turno cuyo profesional ya no resuelve (`profesionalNombre` ausente) o
   paciente dado de baja (marcador de la recepción): la fila no se esconde.
