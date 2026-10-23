# AKINE DP-45 · 1 — Búsqueda por texto y orden en los listados paginados

**Decisión:** DP-45, punto 1 (`docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7).
**Contrato:** 0.144.0. **Migración:** ninguna (ver "Índices").
**Rama:** `akine-dp45-1-busqueda-y-orden`.

## Qué se pide

Que el frontend conserve un solo buscador y pueda ordenar las tablas paginadas por el servidor.
Ordenar la página visible engaña: con 20 filas de 300, "ordenado por nombre" es mentira.

## Forma del contrato

### `sort`

- **Un solo valor** `campo,asc` o `campo,desc`, en minúsculas. **No es repetible**: una tabla se
  ordena por la columna que elige quien mira, y el desempate lo pone siempre el servidor.
  Mandarlo dos veces llega a Spring como un texto unido por coma (`a,asc,b,desc`) y se rechaza.
- **Cada operación declara su lista blanca como `enum` del parámetro** (`allowableValues` de
  springdoc): el cliente generado recibe una unión de literales.
- **Valor fuera del enum → 400 `validation-error`**. Lo lanza `OrdenDeListado.leer` como
  `IllegalArgumentException`, que `GlobalExceptionHandler` ya traduce. El mensaje lista los campos
  válidos y **no repite** el valor recibido (XSS reflejado).
- **Sin `sort`, el orden es exactamente el de antes** —`leer` devuelve `Optional.empty()`, no un
  orden por defecto—. Es lo que hace aditivo el cambio.
- **Desempate único siempre**: el id, en la misma dirección que el campo pedido (o el orden
  histórico completo detrás, en las consultas nativas). El paginado es estable aunque muchas filas
  compartan el valor del campo.
- El texto del cliente **nunca llega al SQL**: cada módulo traduce el campo a su propiedad JPA
  (`Sort`), a su comparador en memoria, o a una rama `CASE WHEN :orden = 'campo,dir'` que sólo
  compara contra literales.

### `q`

"Contiene", sin distinguir mayúsculas ni acentos. En la base lo resuelve la collation
`utf8mb4_0900_ai_ci` con el patrón de `TextoDeBusqueda.patronLike` (comodines del cliente
escapados, igual que `FinanciadorBusqueda`); en memoria, `TextoDeBusqueda.contiene`, que pliega
los dos lados (NFD sin marcas, minúsculas).

### Piezas compartidas — `platform.spi.listado`

| Clase | Qué hace |
|---|---|
| `OrdenDeListado` | Lee `sort` contra la lista blanca; da `Sort` de Spring Data, comparador en memoria o la clave para el `CASE` nativo |
| `TextoDeBusqueda` | Patrón `LIKE` escapado, plegado y "contiene" en memoria, comparador de texto |
| `ListadoEnMemoria` | Filtrar y ordenar los listados que ya se paginaban en memoria |

## Operación por operación

| `operationId` | `q` busca en | `sort` admite | Cómo |
|---|---|---|---|
| `searchCatalogo` | (ya existía: nombre y código) | `codigo`, `nombre`, `alcance` | en memoria (ya paginaba en memoria) |
| `searchMedicionDefiniciones` | (ya existía: nombre y código) | `codigo`, `nombre`, `tipo` | en memoria |
| `listEspacios` | nombre | `nombre`, `tipo`, `capacidad` | en memoria |
| `listOrganizationConsultorios` | nombre de la sede | `nombre` | en memoria |
| `listMemberships` | nombre y correo de la cuenta | `nombre`, `email`, `rol`, `estado`, `vigenteDesde` | en memoria **sólo con `q` o `sort`** (ver abajo) |
| `listAuditEvents` | tipo de evento, tipo de entidad; si es número, id de entidad o de cuenta actora | `ocurridoEn`, `tipoEvento`, `tipoEntidad` | JPQL + `Sort` |
| `listSubscriptionTransitions` | — | `ocurridoEn`, `estadoNuevo` | derivada + `Sort` |
| `listPlatformOrganizations` | (ya existía: nombre y slug) | `nombre`, `slug`, `creadaEn` | JPQL + `Sort` |
| `listNotifications` | tipo que contiene el texto, o destinatario **exacto** | `creadaEn`, `estado`, `tipo`, `intentos` | JPQL + `Sort` |
| `buscarPersonas` | (ya existía) | `apellido`, `nombre`, `documento`, `nacimiento`, `alta` | nativa + `CASE` |
| `listarAdjuntosDePersona` | — | `subidoEn`, `titulo`, `categoria`, `tamano` | nativa + `CASE` |
| `listarAdjuntosClinicos` | — | `subidoEn`, `titulo`, `categoria`, `tamano` | nativa + `CASE` |
| `getHistorialDeAutorizacion` | — | `ocurridoEn`, `tipo` | nativa + `CASE` |
| `listarMovimientosDePase` | — | `ocurridoEn`, `tipo`, `cantidad` | JPQL + `Sort` |
| `listarSeriesDeTurnos` | apellido, nombre y documento del paciente; oferta; profesional | `creadaEn`, `fechaDesde`, `fechaHasta` | sin `q`: JPQL + `Sort`; con `q`: en memoria (ver abajo) |
| `detalleOperativo` | — | `apellido`, `inscriptoEn`, `estado`, `posicionEspera` | en memoria (la clase está acotada) |
| `buscarEgreso` (corta en 50) | beneficiario (nombre y documento), concepto, número de comprobante | — | nativa |
| `buscarPresentacion` (corta en 50) | número de factura; si es número, el lote | — | nativa |

### Dónde no va `q`, y por qué

- **Movimientos de pase, historial de autorización, transiciones de suscripción:** son el
  historial de *un* objeto, pocas filas sin texto propio; se leen por tipo y fecha, que es lo que
  da `sort`.
- **Adjuntos (de persona y clínicos):** son los de *una* persona o *una* historia, ya se filtran
  por categoría (y por entrada clínica). En los clínicos, buscar en títulos sería una lectura
  clínica más sin necesidad.
- **Detalle operativo de una clase:** a lo sumo la capacidad más la espera; la pantalla la recorre.
- **`cobrosDeLaPersona`:** no corta ni pagina —devuelve los cobros de *un* paciente—. Agregarle
  `q` no ayuda a nadie; se deja como está.

### Decisiones que conviene confirmar

1. **`listAuditEvents` no busca por nombre del actor.** El nombre vive en `identity`; la
   auditoría es de `platform` y no puede leer esa tabla. Un `q` numérico encuentra la cuenta por
   id. Tampoco busca en el motivo ni en los detalles: sin `auditoria:read-clinica` se redactan, y
   buscar en ellos dejaría adivinar lo redactado por coincidencia.
2. **`listNotifications`: el destinatario se busca EXACTO, no "contiene".** El listado lo
   devuelve enmascarado; un "contiene" sobre la casilla real permitiría reconstruirla letra por
   letra.
3. **`listMemberships` con `q` o `sort` lee las memberships del tenant enteras** y resuelve las
   cuentas en una consulta a `AccountIdentityDirectory`: el nombre y el correo son de `identity`.
   Están acotadas por el límite de miembros del plan. Sin `q` ni `sort` el camino es el de siempre.
4. **`listarSeriesDeTurnos` con `q` lee todas las series del filtro** (sede, persona, estado) y
   resuelve paciente, oferta y profesional en tres consultas en lote —`person.spi`,
   `offering.spi`, `organization.spi` + `platform.spi.identity`—, y corta la página en memoria.
   Las series de una sede son pocas comparadas con sus turnos. Si una sede llegara a miles de
   series, el siguiente paso sería un `spi` de búsqueda por texto en `person`.
5. **Egresos y presentaciones ya paginan** con `limite`/`desplazamiento` (50 por defecto, el
   "corte"): se agrega sólo `q`. Agregar `page`/`size` daría dos formas de pedir lo mismo.
6. **`buscarPresentacion` no busca por nombre del financiador**: es de `contracting` y el `spi`
   lo resuelve por id; para eso está `financiadorId`.

## Índices

**Ninguna migración.** `V125` no se usó:

- Los `q` nuevos filtran **dentro** de un conjunto que ya acota un índice existente (la sede, la
  organización y el estado, la entidad, el actor, el período), y un `LIKE '%x%'` no usa índice
  de todas formas.
- Los `sort` sobre consultas nativas con `CASE` hacen filesort por diseño; son listados acotados
  (una persona, una historia, una autorización). Los JPQL con `Sort` ordenan dentro del filtro
  indexado.

## Design challenge

1. **Ownership.** No hay tablas nuevas. Cada consulta sigue en su módulo propietario. Los nombres
   que viven en otro módulo (cuenta, paciente, oferta) se piden por su `spi`, nunca con un join.
2. **Ciclos.** `platform.spi.listado` no depende de nadie. `scheduling` suma
   `organization.spi.ConsultorioMembershipDirectory` (ya lo usaba `AgendaService`) y
   `platform.spi.identity`; ninguno depende de `scheduling`. ArchUnit en verde.
3. **Tenant.** Todos los filtros nuevos se agregan **dentro** del `WHERE` por organización (y
   sede) que ya existía; los caminos en memoria parten de listas ya filtradas por tenant.
   Cross-tenant sigue siendo 404 por el mismo camino: el `sort` se valida antes o después de la
   autorización según la operación, y en las que validaban el aislamiento primero
   (`listAuditEvents`, `listMemberships`, sedes) se lee después.
4. **Reglas maestras.** No toca modelo.
5. **Baja lógica.** No hay escrituras.
6. **Contrato.** Aditivo: dos parámetros opcionales por operación y un 400 nuevo sólo para un
   `sort` inválido. Sin `q` ni `sort`, misma respuesta y mismo orden. Minor 0.144.0.
7. **Ruta crítica.** Todos los listados existen; esto los extiende.
8. **El caso que rompe el diseño.** Un `sort` que el cliente arma con SQL
   (`nombre;DROP TABLE`). No llega a ninguna consulta: `leer` lo rechaza contra la lista blanca, y
   en las nativas el valor sólo se compara contra literales en un `CASE`. El otro: una búsqueda que
   filtre por un dato que el que mira no puede ver (el motivo redactado de la auditoría, el correo
   enmascarado de una notificación) y lo revele por coincidencia; se resolvió no buscando en el
   primero y exigiendo igualdad exacta en el segundo.
