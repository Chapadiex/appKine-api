# ADR-0025 — `servicio` admite servicios propios del centro

- **Estado:** Aceptado
- **Fecha:** 2026-11-09
- **Decisión del usuario:** 09/11/2026 (DP-46 del plan de implementación), en la sesión de clases grupales del "Centro QA A"
- **Supersede:** la fila de `servicio` en [ADR-0023](0023-tablas-globales-sin-organization-id.md)
- **Migración:** `V143__m27_servicio_propio_de_la_organizacion.sql` · **Contrato:** 0.163.0

## Contexto

`servicio` nació global puro (AKINE-02.06): sin `organization_id`, una sola población, y sólo
`PLATFORM_ADMIN` la mutaba. Se apoyaba en RN-M27-001 (*"un Servicio es catálogo
global/conceptual"*) y en CA-M06-006-06 (*"Pilates Reformer puede existir una sola vez en
catálogo"*).

Al armar un centro real apareció el hueco: la administradora quería ofrecer Yoga y no podía darlo
de alta. El único camino era pedírselo a la plataforma (RF-M06-005), que no es autoservicio. Los
actores de M27 incluyen al administrador de organización y de consultorio, y RF-M27-001 dice
*"deja el Servicio disponible para ser utilizado por consultorios autorizados"*. Pero ninguna regla
le daba a un centro un servicio propio.

Se le presentaron al usuario tres opciones: (A) servicio de la organización en la misma tabla con
`owner_key`, como ya hacen `especialidad`, `practica` y `medicion_definicion`; (B) copia adoptada,
como los financiadores de DP-43; (C) no tocar el catálogo y usar sólo la solicitud a plataforma.
**Eligió (A).**

## Decisión

1. `servicio.organization_id` **nullable**: `NULL` es el catálogo global, que ven todos; un valor es
   un servicio propio de esa organización, que no ve ninguna otra.
2. `owner_key = IFNULL(organization_id, 0)` y los uniques de código y nombre pasan a
   `(owner_key, …, deleted_key)`. Es la forma de ADR-0021 por el mismo motivo: en MySQL varios
   `NULL` no colisionan, y sin el centinela el catálogo global quedaría sin protección.
3. **El alcance lo decide quien llama, no el cuerpo.** `POST /servicios` con rol de plataforma crea
   en el global; con un usuario de un centro y `catalogo:manage` crea un servicio propio. No hay un
   campo `alcance` en el pedido que alguien pueda falsear.
4. **Permiso:** `catalogo:manage`, el mismo que el catálogo clínico propio (DP-36): `ORG_ADMIN`
   con alcance de organización y administrador de sede con el de su sede. El global sigue siendo
   del rol de plataforma, sin código.
5. **Lectura:** el catálogo global más los servicios de la organización del contexto. El rol de
   plataforma ve sólo el global. Un servicio de otro centro es 404, nunca 403.
6. `/api/v1/servicios` **deja de estar exceptuado de `TenantContextFilter`**: la respuesta depende
   del contexto. El rol de plataforma no pierde nada, porque el filtro ya lo deja pasar sin
   contexto.
7. Una oferta sólo puede materializar un servicio visible para su organización.

## Consecuencias

### Positivas

- Un centro arma su oferta sin esperar a la plataforma.
- Lo existente no cambia: toda fila previa queda con `organization_id NULL`, que es lo que ya era.

### Negativas

- Puede haber un "Yoga" global y un "Yoga" propio en el mismo selector. La respuesta publica
  `alcance` (`GLOBAL` u `ORGANIZACION`) para que la pantalla los distinga.
- CA-M06-006-06 se cumple sólo dentro del catálogo global. Un centro puede crear su propio Pilates.
- Un usuario de un centro con un token sin contexto ya no puede listar servicios: recibe 403
  `missing-tenant-context`, igual que en cualquier otra ruta de negocio.

### Qué obliga a hacer

- **Toda lectura acotada filtra por `owner_key`, nunca por `organization_id`.** Es la trampa de
  02.05: `WHERE organization_id = ?` esconde en silencio el catálogo global.
- `EsquemaMultiTenantIT` registra `servicio` como `NULLABLE` en vez de `SIN_COLUMNA`.
