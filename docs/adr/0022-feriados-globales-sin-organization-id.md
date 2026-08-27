# ADR-0022 — `feriado` es global, sin `organization_id`

- **Estado:** Superseded by [ADR-0023](0023-tablas-globales-sin-organization-id.md)
- **Fecha:** 2026-08-26
- **Etapa:** AKINE-02.04

> **Superseded por [ADR-0023](0023-tablas-globales-sin-organization-id.md) (AKINE-02.06).**
>
> Este ADR fue la **cuarta** excepción y no consolidó, como le exigían ADR-0020 y ADR-0021: su
> propia línea final reconoce la deuda y la difiere una vez más. La deuda la paga ADR-0023,
> escrito en AKINE-02.06 al aparecer la quinta —`servicio`—, que es la **autoridad vigente** y
> donde vive ahora la fila de `feriado`. Este documento se conserva por su valor histórico.

## Contexto

El [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md) exige que toda tabla de negocio
lleve `organization_id NOT NULL` y que todo `UNIQUE` incorpore el alcance del tenant. Tres
excepciones ya están declaradas por ese motivo exacto: [ADR-0019](0019-identidad-global-sin-organization-id.md)
(identidad), [ADR-0020](0020-rol-de-plataforma-sin-organization-id.md) (rol de plataforma) y
[ADR-0021](0021-catalogos-clinicos-globales-sin-organization-id.md) (catálogo clínico global y
contextual en la misma tabla). Los tres cierran con la misma condición: *"una excepción nueva
exige un ADR que la agregue a esta lista; un comentario en la migración no alcanza"*.

AKINE-02.04 introduce el calendario de feriados nacionales, primer paso hacia la disponibilidad
semanal de M05 (`docs/diseno/AKINE-02.04-disponibilidad.md` §2.1, RF-M05-004). El calendario
necesita saber que el 25 de mayo es feriado en Argentina. Ese hecho:

1. **No es de ningún centro.** El 25 de mayo es feriado nacional lo sepa o no un tenant en
   particular, y lo será para todos los que operen en el país al mismo tiempo.
2. **No puede discreparse entre tenants.** Si `feriado` llevara `organization_id`, dos centros
   podrían terminar con calendarios distintos sobre el mismo hecho público — uno con el 20 de
   junio cargado y otro sin él — por un simple error de carga, no por una decisión de negocio.
3. **Es un insumo, no una decisión operativa.** Lo que cada sede decide —si cierra ese día o
   atiende igual— es un dato propio y vive en `consultorio_calendario` (`docs/diseno/AKINE-02.04-disponibilidad.md`
   §2.2, V23), que **sí** lleva `organization_id NOT NULL` como corresponde a una tabla de
   negocio. `feriado` es el calendario público; `consultorio_calendario.cierra_por_feriado` es la
   política de la sede sobre ese calendario.

Es exactamente el caso de los catálogos globales de ADR-0021, pero más simple: acá no hay una
segunda población contextual conviviendo en la misma tabla. `feriado` es enteramente global, así
que no hace falta el mecanismo de `owner_key` nullable — alcanza con la ausencia lisa y llana de
la columna, como en ADR-0019 y ADR-0020.

## Decisión

**`feriado` no lleva `organization_id`.** Es una excepción declarada a ADR-0004, con el mismo
fundamento que ADR-0019 (identidad global) y ADR-0021 (catálogos globales): un feriado nacional
es un hecho del calendario público, no un dato de ningún tenant.

Se agrega a la lista de excepciones —dispersa entre ADR-0019, ADR-0020 y ADR-0021, y que esos
mismos ADR ya anotan como deuda a consolidar— con esta fila:

| Objeto | Excepción | Motivo |
|---|---|---|
| `feriado` | Sin `organization_id`, ninguna columna de tenant | Hecho del calendario público nacional, no un dato de ningún tenant. La decisión que sí es del centro —si cierra o no ese día— vive en `consultorio_calendario`, que lleva `organization_id NOT NULL` |

El `UNIQUE (pais, fecha)` no necesita centinela ni columna generada: no hay dos poblaciones que
distinguir como en ADR-0021, así que el problema de los `NULL` que no colisionan en MySQL no
aplica acá. Dos filas con el mismo país y la misma fecha son, sin ninguna ambigüedad, el mismo
feriado duplicado.

### Alternativa descartada — una fila por organización

Duplicaría el mismo feriado tantas veces como tenants haya y haría que dos centros puedan
discrepar sobre si el 25 de mayo existe. Es la misma alternativa que ADR-0021 descartó para el
catálogo clínico —"copiar el catálogo global en cada tenant al crearlo"— y por el mismo motivo:
convierte cada corrección del calendario público en un backfill sobre N tenants, con la garantía
de que alguno queda desactualizado. La decisión que sí es del centro —si cierra o no ese
día— vive en `consultorio_calendario`, que **sí** lleva `organization_id`.

### Alternativa descartada — `organization_id` nullable con `owner_key` como ADR-0021

Formalmente sería consistente con el catálogo clínico. Descartada porque `feriado` no tiene la
segunda población que justifica ese mecanismo: no existe un "feriado contextual de un tenant".
Agregar `owner_key` acá sería el mismo patrón sin el problema que lo motiva, y un lector futuro
se preguntaría con razón para qué sirve una columna generada que nunca distingue nada.

### Alternativa descartada — tabla de catálogo por país mantenida por la plataforma con flujo de aprobación

RF-M05-004 no pide un circuito de solicitud como el de RF-M06-005: el feriado nacional es un
hecho verificable contra el Boletín Oficial, no una oferta de servicio que un tenant proponga y
la plataforma apruebe. Un flujo de aprobación acá sería resolver un problema que la etapa no
tiene.

## Consecuencias

### Positivas

- El calendario nacional existe como dato consultable con un único `SELECT`, sin `UNION` entre
  tenants y sin que cada centro tenga que cargar su propio calendario desde cero.
- Una corrección del seed —un feriado mal fechado, un decreto nuevo— se aplica una sola vez y la
  ven todos los tenants sin backfill.
- El `UNIQUE (pais, fecha)` es la forma más simple de las tres que este repositorio ya usa para
  proteger duplicados (ver V10, V12, V18/V19, V20): sin centinela porque no hace falta.

### Negativas

- **Ninguna consulta a `feriado` puede recibir un `organizationId`.** Si alguien se lo agrega —a
  la firma del repositorio, al `WHERE`, al `spi`— es que entendió mal el alcance de la tabla, y
  el síntoma no es un error visible sino un filtro que no hace nada porque la columna no existe
  para filtrar por ella.
- **El seed envejece.** Los feriados trasladables y los puentes se fijan por decreto cada año, y
  ningún seed puede adelantarse a un decreto que todavía no se firmó. La migración V22 documenta
  esto explícitamente y por eso la sede siempre puede cargar una excepción propia en
  `consultorio_calendario`/excepciones (V23) sin depender de que este seed esté al día: el seed
  es una comodidad, nunca la autoridad.
- **Un test genérico "toda tabla lleva `organization_id` NOT NULL" necesita una entrada más** en
  su lista de exclusión, sincronizada ahora con cuatro ADR en vez de con tres. La consolidación
  que ADR-0020 y ADR-0021 ya venían anotando como deuda sigue pendiente, y con esta es la cuarta
  vez que se pospone.

### Qué obliga a hacer

- La migración que crea la tabla (`V22__m05_feriado_global.sql`) cita este ADR en su encabezado,
  junto a ADR-0003 y ADR-0004.
- `consultorio_calendario` y cualquier tabla futura que referencie `feriado` (excepciones de
  disponibilidad, V23) llevan **su propio** `organization_id NOT NULL`: la referencia a un
  feriado no presta alcance de tenant a nada.
- Ninguna query de `feriado` filtra por tenant, y por lo tanto **ninguna query de esta tabla
  puede recibir un `organizationId`**: el aislamiento multi-tenant de esta etapa vive en
  `consultorio_calendario` y en las tablas de excepciones, no acá.
- La próxima excepción a ADR-0004 que se agregue **no vuelve a escribir un ADR incremental
  aislado**: para entonces son cinco archivos con la misma lista repartida y corresponde
  consolidarlos en uno que los supersede, como ya lo pedían ADR-0020 y ADR-0021.
