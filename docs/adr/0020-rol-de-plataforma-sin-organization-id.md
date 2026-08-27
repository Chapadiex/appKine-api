# ADR-0020 — `platform_role` no lleva `organization_id`

- **Estado:** Superseded by [ADR-0023](0023-tablas-globales-sin-organization-id.md)
- **Fecha:** 2026-08-23
- **Etapa:** AKINE-01.03

> **Superseded por [ADR-0023](0023-tablas-globales-sin-organization-id.md) (AKINE-02.06).**
>
> Este ADR pedía, en sus consecuencias negativas, que *"si llegan dos o tres más, corresponde un
> ADR que consolide y supersede a los anteriores"*. Eso es ADR-0023, que es la **autoridad
> vigente** sobre las excepciones a ADR-0004 y donde vive ahora la fila de `platform_role`.
> Este documento se conserva por su valor histórico: el argumento completo sobre por qué el rol
> de plataforma no pertenece a ningún tenant, y las alternativas descartadas, siguen acá.

## Contexto

El [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md) exige que toda tabla de negocio
lleve `organization_id NOT NULL` y que todo `UNIQUE` incorpore el alcance del tenant. El
[ADR-0019](0019-identidad-global-sin-organization-id.md) reunió en una sola tabla las excepciones
existentes hasta AKINE-01.02 y cerró esa lista con una condición explícita: *"una excepción nueva
exige un ADR que la agregue a esta tabla; un comentario en la migración no alcanza"*.

AKINE-01.03 introduce `platform_role`: la tabla que dice qué cuenta tiene el rol
`PLATFORM_ADMIN`, desde cuándo, hasta cuándo, quién se lo otorgó y con qué motivo. Es una
excepción nueva, y por lo tanto está bloqueada hasta que exista este ADR.

Los hechos que la hacen inevitable:

1. La matriz de permisos (`docs/seguridad/matriz-permisos-minima.md` §1.3) es explícita:
   **`PLATFORM_ADMIN` no tiene membership en ninguna organización**. Su alcance es la plataforma,
   no un tenant. Preguntarle a qué organización pertenece la fila no tiene respuesta.
2. Hasta hoy `AuthenticatedJwtPrincipal.platformAdmin()` compara el claim `rol` del token contra
   `"PLATFORM_ADMIN"`, y ese claim se llena con el rol de la membership del contexto. Nunca vale
   ese valor, así que hoy el rol de plataforma **no existe como dato**: `POST /organizations`,
   `POST …/subscription/transitions` y `POST …/subscription/plan-changes` están publicados en el
   contrato 0.3.0 y no los puede ejecutar nadie.
3. El usuario decidió, el 23/08/2026, que el rol salga de una tabla propia y que el primer
   `PLATFORM_ADMIN` entre por un seed de migración con un email concreto, versionado en el
   repositorio, a cambio de que el alta sea auditable y reproducible. Quedaron descartadas la
   lista de emails en `application.yml` y el comando administrativo fuera de HTTP.

La decisión 3 es la que fuerza este ADR: sin tabla no hay excepción que justificar, y con tabla
la excepción es obligatoria.

## Decisión

**`platform_role` no lleva `organization_id`, y su `UNIQUE` es global por cuenta.**

```
platform_role (
    id, account_id, role_code, granted_by_account_id, reason,
    valid_from, valid_until, active, deleted_at, version, created_at, updated_at,
    rol_activo VARCHAR(48) AS (IF(active = 1, role_code, NULL)) STORED,
    UNIQUE (account_id, rol_activo)
)
```

**Se agrega a la lista de excepciones de ADR-0019** —que hay que leer junto con este documento,
porque un ADR aceptado no se edita— con esta fila:

| Objeto | Excepción | Motivo |
|---|---|---|
| `platform_role` | Sin `organization_id`; `UNIQUE (account_id, rol_activo)` global | Rol **de la plataforma**, no de un tenant. La matriz §1.3 define `PLATFORM_ADMIN` como el rol que no tiene membership en ninguna organización: acotarlo a un tenant sería el rol contrario |

**Qué la aísla, ya que no es el tenant.** La tabla no contiene datos de ningún tenant: contiene
tres o cuatro filas que dicen quién opera la plataforma. El control es distinto y es de otro tipo:

1. **Es una tabla de sujeto, no de hecho de negocio.** Su clave es `account_id`, que ya es global
   ([ADR-0019](0019-identidad-global-sin-organization-id.md)). No hay fila de un tenant que
   pudiera filtrarse a otro.
2. **Nadie la lee por criterio externo.** El único acceso es
   `PlatformRoleDirectory.isPlatformAdmin(accountId, at)`, resuelto desde el `sub` del token, una
   vez por request y sin caché. No existe listado de cuentas de plataforma accesible desde una
   ruta de tenant.
3. **Tener el rol no da acceso a datos de ningún tenant.** Para operar dentro de una organización
   hace falta además un `support_access` vigente para esa organización, que **sí** lleva
   `organization_id NOT NULL` y **sí** es auditado en cada uso (`SUPPORT_ACCESS_USED`). El rol
   abre las rutas `/platform/**`; el acceso al dato de un tenant lo abre otra tabla, acotada en
   tiempo y con motivo obligatorio.
4. **Toda alta y baja queda auditada** (`PLATFORM_ROLE_GRANTED` / `PLATFORM_ROLE_REVOKED`, con
   `organization_id = NULL` porque son eventos de plataforma, forma ya admitida por ADR-0019 para
   `audit_event`).

**El módulo propietario es `organization`, no `platform`.** `organization` ya es propietario del
catálogo de roles (`RoleCode`), del catálogo global de planes y del evaluador de permisos.
`platform` es el módulo base y no puede depender de ninguno funcional: consume el dato por el
puerto invertido `platform.spi.tenant.PlatformRoleDirectory`, que `organization` implementa —el
mismo patrón, ya probado, de `MembershipDirectory`.

**`role_code` admite un único valor: `PLATFORM_ADMIN`.** No es una tabla de roles genérica, y
`PLATFORM_ADMIN` queda **prohibido** como valor de `membership.role_code`.

**El seed del primero resuelve el email contra `cuenta`.** La migración inserta la fila con
`INSERT … SELECT` sobre `cuenta.email_normalizado`, idempotente (`WHERE NOT EXISTS`). Es el único
lugar del sistema donde una sentencia SQL cruza la frontera de propiedad entre módulos, y se
acepta a conciencia: la propiedad de tablas es una regla de compilación verificada por ArchUnit
sobre Java, y este bootstrap **no tiene llamador Java posible** —`organization → identity` está
prohibido sin excepciones—. Ver "Consecuencias" para lo que esto implica en un entorno nuevo.

## Alternativas consideradas

**`platform_role` con `organization_id NOT NULL`.** Cumpliría ADR-0004 a la letra. Descartada:
obligaría a inventar a qué organización pertenece un rol que por definición no pertenece a
ninguna. Las salidas serían una fila por tenant para el mismo administrador —que multiplica el
alta, la revocación y la auditoría por la cantidad de clientes del SaaS, y hace que revocar sea
una operación que se puede completar a medias— o una organización centinela "plataforma", que es
una fila fantasma en la tabla de tenants con la que cualquier conteo, reporte o listado tendría
que aprender a convivir.

**`organization_id` nullable, como `notification_outbox` y `audit_event`.** Formalmente la
columna existiría y la excepción sería "más chica". Descartada: la columna sería `NULL` en el
100 % de las filas. Una columna que nunca tiene valor no documenta nada y sí invita a que alguien
la use más adelante para "acotar un platform admin a un tenant", que es exactamente lo que la
matriz §1.3 prohíbe. Es peor que no tenerla, porque parece una puerta.

**No crear la tabla: el rol como membership con `role_code = 'PLATFORM_ADMIN'`.** El enum ya
tiene el valor y `membership` ya lleva `organization_id`, así que ADR-0004 quedaría intacto.
Descartada: contradice la matriz §1.3 de frente, y el efecto práctico sería que el permiso más
alto del sistema quedaría acotado al tenant de la fila, o replicado en todos. Además rompería el
alcance del evaluador, que resuelve la membership *que cubre un consultorio*: un rol global no
tiene consultorio que cubrir.

**Lista de emails en `application.yml` por perfil.** Es la opción trivial: cero tablas, cero
migraciones, cero excepciones a ADR-0004. Descartada por el usuario el 23/08/2026: el permiso más
alto del sistema quedaría sin auditoría, sin vigencia y sin revocación en caliente, y la matriz §7
exige que toda asignación de rol quede auditada. Un cambio de permisos pasaría a ser un
redespliegue, y quién lo tiene sería una pregunta que se responde leyendo un archivo de
configuración por ambiente en vez de consultando la base.

**Comando administrativo fuera de HTTP (perfil `local` o CLI) para crear la primera fila.**
Conserva la tabla y evita poner un email en el repositorio. Descartada por el usuario en la misma
decisión: agrega un paso operativo no versionado a cada despliegue nuevo, no queda registro de
cuándo ni por quién se ejecutó, y el alta deja de ser reproducible desde cero. Se prefirió que el
email quede versionado a cambio de que el alta sea auditable y repetible.

**Mover la tabla al módulo `platform` para que ADR-0004 "no la alcance".** Descartada por el
mismo motivo que ADR-0019 descartó mover `refresh_token` a otro esquema: cambia dónde está la
tabla para no explicar por qué es distinta. Y `platform` no puede depender de `organization`, así
que el evaluador de permisos quedaría partido en dos módulos.

## Consecuencias

### Positivas

- El rol de plataforma pasa a ser un **dato consultable, con vigencia, motivo y autor**, en vez
  de un claim que nunca se emite o de una línea de configuración. Quién puede operar la
  plataforma se responde con un `SELECT`.
- La revocación es inmediata: `PlatformRoleDirectory` se consulta una vez por request y sin
  caché, igual que la membership (decisión T-7). La ventana de revocación es cero.
- Los tres endpoints de plataforma publicados en el contrato 0.3.0 dejan de ser inalcanzables.
- La lista de excepciones a ADR-0004 sigue siendo explícita y citable: esta se agrega por ADR, no
  por comentario en una migración.

### Negativas

- **La lista única de ADR-0019 dejó de estar en un solo archivo.** Como un ADR aceptado no se
  edita, la lista completa es ahora "la tabla de ADR-0019 más la fila de ADR-0020". Cada
  excepción futura agrega un salto más. Si llegan dos o tres más, corresponde un ADR que
  consolide y supersede a los anteriores.
- **El email del primer administrador queda versionado en el repositorio.** Es una consecuencia
  aceptada explícitamente, no un descuido, pero es un dato personal en un repo y hay que elegir
  una casilla institucional y estable, no la de una persona.
- **El seed cruza la frontera de propiedad entre módulos en SQL.** ArchUnit no ve las
  migraciones: esa regla la sostiene la revisión humana de cada `.sql`, y este ADR es el único
  permiso escrito para hacerlo.
- **Un test genérico "toda tabla lleva `organization_id`" necesita una entrada más en su lista de
  exclusión**, que hay que mantener sincronizada con dos ADRs en vez de con uno.
- La tabla concentra el permiso más alto del sistema en tres o cuatro filas sin alcance de
  tenant. Un `UPDATE` directo sobre ella es una escalada total de privilegios, y la única barrera
  es el control de acceso a la base de producción.

### Qué obliga a hacer

- La migración que crea `platform_role` cita este ADR en su encabezado, junto a ADR-0004 y
  ADR-0019.
- El seed del primer `PLATFORM_ADMIN` es **idempotente** (`INSERT … SELECT … WHERE NOT EXISTS`) y
  **no falla** si el email todavía no corresponde a ninguna cuenta: inserta cero filas. Por lo
  tanto, en un entorno nuevo hay que verificar explícitamente que la fila exista antes de dar el
  despliegue por bueno — un entorno sin ninguna fila activa en `platform_role` es un entorno
  donde el alta administrativa de tenants no funciona, y no lo avisa nadie.
- Un `CHECK` en `membership` y un test impiden `role_code = 'PLATFORM_ADMIN'` en esa tabla.
- `PlatformRoleDirectory` se consulta **una vez por request y sin caché**. Cachearlo requiere un
  ADR nuevo, igual que cachear `MembershipDirectory`.
- Ningún módulo lee `platform_role` fuera de `organization`; `platform` la ve solo por el puerto
  invertido. ArchUnit lo verifica.
- Toda alta o baja de un rol de plataforma escribe `PLATFORM_ROLE_GRANTED` /
  `PLATFORM_ROLE_REVOKED` en `audit_event` con `organization_id = NULL`, dentro de la transacción
  de la operación (`Propagation.MANDATORY`).
- Tener `PLATFORM_ADMIN` **no** habilita por sí solo a leer datos de un tenant: hace falta un
  `support_access` vigente para esa organización, y cada operación amparada por él deja
  `SUPPORT_ACCESS_USED`.
