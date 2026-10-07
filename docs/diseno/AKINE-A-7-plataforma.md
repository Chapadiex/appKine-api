# AKINE-A-7 — Consola de plataforma (parte backend)

> Paquete A-7 de `docs/fases/01-trabajo-en-paralelo.md`. Depende de A-4 (DP-14), que dejó un
> `PLATFORM_ADMIN` alcanzable. **Sin migración. Contrato `0.64.0`** (aditivo, más un cambio de
> comportamiento sobre un endpoint que nadie consume todavía — ver §2).

Tres piezas, de las cuales entran dos:

| # | Pieza | Estado |
|---|---|---|
| 1 | `GET /api/v1/me/platform-role`: "¿tengo rol de plataforma?" | **Entra** |
| 2 | Resolver solicitudes de catálogo de punta a punta (RF-M06-005): aprobar **publica** el concepto global | **Entra** |
| 3 | Catálogo global de financiadores (F3, M15) | **Diseñada, no implementada**: decisión pendiente **DU-13** (§3) |

## 1. "¿Tengo rol de plataforma?"

**El hueco.** El frontend no tiene forma de saber si quien mira administra la plataforma. El
token no sirve: el claim `rol` no se usa para autorizar (ADR-0020, `AccessTokenClaims`) y el rol
se revalida contra `platform_role` en cada request. `GET /me/permissions` tampoco: exige contexto
de tenant y un `PLATFORM_ADMIN` no lo tiene (responde 403 `missing-tenant-context`).

**Diseño.**

- `GET /api/v1/me/platform-role` → `200 {"platformAdmin": true|false}`. Vive en
  `MeContextController` (`organization.api`), al lado de `/me/contexts` y `/me/permissions`.
- La respuesta sale de `AuthenticatedPrincipal.platformAdmin()`, que `JwtAuthenticationFilter`
  acaba de revalidar contra `platform_role` en **este** request (`PlatformRoleDirectory`, sin
  cache). No hay consulta nueva ni puerto nuevo.
- **Exceptuado de `TenantContextFilter` por ruta exacta**, como `/me/contexts`: se consulta con
  el token `pre_context`, antes de elegir contexto, que es justo cuando el frontend decide si
  muestra la consola de plataforma o el selector de contexto. Sin la excepción, una cuenta de
  tenant con token `pre_context` recibiría 403 `missing-tenant-context` en vez de `false`.
- Sin sesión → 403 (`forbidden`), jamás 401. Con sesión, **siempre 200**: `false` no es un error.
- No se audita: es la cuenta preguntando por sí misma, no una lectura de datos de nadie.
- Es **insumo de UX, no seguridad**: cada endpoint de plataforma sigue verificando el rol.

## 2. Resolver solicitudes de catálogo: aprobar publica el concepto

**Lo que ya existía.** `POST /catalogo-solicitudes` (tenant pide), `GET` (tenant ve las suyas,
plataforma la bandeja cross-tenant) y `POST /{id}/resolve` (solo plataforma). Lo que faltaba para
cerrar RF-M06-005 era la pantalla —que necesita §1— y que aprobar **hiciera algo**: hasta acá
aprobar dejaba una fila `APROBADA` y la plataforma tenía que ir al alta normal a crear el concepto,
sin que nada los vinculara (`concepto_id` quedaba siempre NULL aunque la columna existe desde
`V20` "para el concepto creado al aprobar").

**Cambio de decisión, declarado.** La matriz §11.2 y el javadoc de 02.05 decían "aprobar no crea el
concepto global", con un motivo bueno: crearlo desde el **texto del pedido** llenaría el catálogo
común de duplicados con nombres parecidos. A-7 lo invierte sin perder ese motivo:

- Aprobar **publica** el concepto global **en la misma transacción** que resuelve la solicitud, y
  deja su id en `conceptoId`. CA-M06-005-04: si la publicación falla (código o nombre tomados en el
  catálogo global, especialidad inexistente o inactiva) la solicitud **sigue PENDIENTE** y no
  queda ningún concepto: es todo o nada.
- **La plataforma dispone, el centro propone.** El cuerpo del `resolve` acepta `codigo`, `nombre`,
  `descripcion` y `especialidadId` (esta última obligatoria para una práctica). Sin `codigo` o sin
  `nombre` se usan los propuestos; sin código propuesto ni código en la resolución → 400. Así el
  concepto publicado es el normalizado por la plataforma, no el texto del pedido, y el unique de
  código y nombre entre los globales vigentes (`owner_key = 0`) rechaza el duplicado con 409
  `catalogo-code-taken` / `catalogo-name-taken`.
- Si el concepto **ya existe** en el catálogo común, el desenlace correcto es **rechazar** con una
  nota que lo diga. No se agrega "aprobar vinculando a un concepto existente": ningún RF lo pide.
- Un rechazo con datos de concepto → 400: es un error de la pantalla, no algo a ignorar.
- La publicación pasa por `CatalogoService.crear` con alcance `GLOBAL`: mismas validaciones, mismo
  `CATALOGO_CREATED` en auditoría (sin tenant), mismo chequeo de rol. La resolución deja además
  `CATALOGO_SOLICITUD_RESOLVED` **bajo el tenant que pidió** —para que el centro vea en su
  auditoría quién decidió y por qué— con `conceptoId` en los detalles.

**Compatibilidad.** El esquema del request crece con campos opcionales (aditivo). El
comportamiento cambia en un caso: aprobar una solicitud **sin código** (ni propuesto ni en la
resolución) pasa de 200 a 400. No hay consumidor: el frontend no tiene pantalla de resolución
(es justamente A-7 web). Se publica como minor, `0.64.0`, y se declara en el PR.

**Lo que no cambia.** La bandeja cross-tenant sigue sin pasar por `support_access`: devuelve el
pedido —nombre propuesto, justificación—, no datos del centro (matriz §11.2, criterio de §9.7). Un
tenant no resuelve (403). La plataforma no pide (403). La resolución sigue siendo terminal.

## 3. Catálogo global de financiadores — diseñado, decisión pendiente (DU-13)

**Por qué no entra.** El RF no alcanza para definirlo, y la decisión que falta cambia el modelo
entero, no un detalle:

- M15 dice "catálogo de financiadores y planes **reutilizable** por pacientes y convenios", con
  actores "Administrador de plataforma" y "Administrador autorizado". La matriz §3 da al
  `PLATFORM_ADMIN` "Catálogo global: solo financiadores/planes globales, nunca convenios de un
  tenant". **Ninguno dice qué significa reutilizar**: si el centro *referencia* la fila global o
  la *copia* a la suya.
- `V41` decidió que el financiador es dato del centro (código interno, contacto, CUIT, condición)
  y su cabecera escribió un camino —`organization_id` nullable + `owner_key`, el patrón de
  ADR-0021—. Ese camino **es la opción "referencia"**, y arrastra que el plan, la cobertura del
  paciente, el convenio, el arancel y la autorización pasen a leer con "dueños visibles" (`IN
  (0, :org)`) en todo `contracting` —hoy cada consulta filtra por `organization_id = :org`—, y que
  un centro no pueda tener su propio código interno o contacto para la obra social global.

**Las dos opciones, con lo que cuesta cada una.**

| | (A) Referencia — `owner_key` en `financiador` y `plan_cobertura` | (B) Plantilla — tabla global aparte que el centro **adopta** (copia) |
|---|---|---|
| Esquema | `V__`: `organization_id` NULL-able, `owner_key` generado, uniques reescritos en las dos tablas | `V__`: `financiador_catalogo` y `plan_catalogo` sin `organization_id` (excepción ADR-0023); `financiador.catalogo_id` NULL-able |
| Código existente | Toca **todas** las lecturas de `contracting` (financiador, plan, convenio, arancel, cobertura, orden, autorización) y sus `spi` | No toca nada existente: el financiador adoptado es una fila del centro como cualquier otra |
| Dato propio del centro | Se pierde (contacto, código interno compartidos) o exige una tabla de "personalización" | Se conserva: la copia es del centro |
| Cambios del catálogo global | Se ven en vivo en todos los centros | No se propagan solos: hace falta "re-sincronizar" (otra decisión) |
| Choque con trabajo en curso | Alto: B-3 trabaja en `contracting` ahora | Bajo: archivos nuevos |

**Recomendación: (B).** Respeta la decisión de `V41` (el financiador es dato del centro), no
reabre `contracting` mientras B-3 trabaja ahí, y "copiar en vez de leer" es el criterio que el
módulo ya usa para todo lo que tiene que sobrevivir a una edición (`congelar`, 03.03/03.04).
Alcance mínimo de (B) cuando se decida: CRUD de `financiador_catalogo`/`plan_catalogo` con rol de
plataforma (exceptuado de `TenantContextFilter`, como `/servicios`), lectura para cualquier
membership, y `POST /financiadores/adoptar/{catalogoId}` con `convenio:manage` que crea el
financiador del centro y sus planes vigentes en una transacción, idempotente por
`uk (organization_id, catalogo_id, deleted_key)`. `PLATFORM_ADMIN` recibe entonces la celda
"Catálogo global" de la matriz sobre esas tablas y **nunca** `convenio:manage` sobre las del tenant
(13.2 sigue valiendo).

**Lo que queda para quien decida:** (A) o (B); si (B), si los cambios del catálogo se propagan a
las copias y cómo; y si un plan global con copago fija importe en moneda o queda solo como dato.

## Design challenge

1. **Ownership.** Ninguna tabla nueva. `catalogo_solicitud` y los conceptos son de `resource`; la
   publicación la hace `CatalogoSolicitudService` llamando a `CatalogoService` **dentro del mismo
   módulo**. El endpoint de rol vive en `organization.api` y lee el principal que llena
   `platform`; no toca `platform_role` directamente.
2. **Ciclos.** Ninguna arista nueva entre módulos. `resource → organization.spi` ya existía
   (`PermissionGuard`). `ModuleArchitectureTest` lo verifica.
3. **Tenant.** Sin tablas nuevas. El concepto publicado es global (`organization_id` NULL,
   `owner_key = 0`), que es la población que la solicitud pide. La auditoría de la resolución
   queda bajo el tenant que pidió; la del concepto, sin tenant (ADR-0019 lo admite).
4. **Reglas maestras.** No aplica HC/Caso/Sesión ni Obligación/Cobro/Caja: es catálogo.
5. **Baja lógica.** No hay borrado. Una solicitud resuelta no se reabre; un concepto publicado por
   error se da de baja por el camino normal, con motivo.
6. **Contrato.** Aditivo (operación nueva, campos opcionales nuevos). Un caso endurece: aprobar sin
   código posible → 400. Sin consumidor; minor `0.64.0`, declarado.
7. **Ruta crítica.** Cimientos construidos: catálogo y solicitudes (02.05), rol de plataforma
   revalidado (01.03), bootstrap (A-4). Lo único que no tiene cimiento —el catálogo global de
   financiadores— es lo que no entra.
8. **El caso que rompe el diseño.** Dos administradores de plataforma aprueban **la misma**
   solicitud a la vez, con códigos distintos. Los dos crean su concepto; el `UPDATE` de la
   solicitud lleva `version`, en `READ_COMMITTED` el segundo espera el lock de fila del primero y
   al commitear éste encuentra cero filas → excepción de bloqueo optimista → **toda** su
   transacción se deshace, concepto incluido. Queda un concepto y una resolución. Con el mismo
   código, el segundo choca antes contra el unique de `owner_key` y responde 409
   `catalogo-code-taken`, y su solicitud no cambia. Lo prueba `ConsolaDePlataformaIT`.
