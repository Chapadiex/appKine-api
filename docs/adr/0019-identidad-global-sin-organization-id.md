# ADR-0019 — Las tablas de identidad no llevan `organization_id`

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-01.02

## Contexto

El [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md) es categórico: "toda tabla de
negocio lleva `organization_id NOT NULL`. Sin excepción", y "todo índice y todo `UNIQUE`
incorpora el alcance del tenant"; un `UNIQUE` sin `organization_id` queda declarado ahí como
"un bug de aislamiento, no una optimización pendiente".

Las tres tablas que `identity` introduce en AKINE-01.02 —`cuenta`, `token_verificacion` y
`refresh_token`— **no llevan `organization_id`**, y `cuenta.email_normalizado` tiene un
`UNIQUE` **global**. Leído contra ADR-0004, eso es tres veces el bug que ADR-0004 nombra.

No lo es, y el motivo está en el
[ADR-0009](0009-identidad-unica-con-seleccion-de-contexto.md): la Cuenta es una **identidad
única cross-tenant**. Una persona, una cuenta, que actúa en N organizaciones y elige contexto
después de autenticarse; la misma persona puede ser profesional en un centro y paciente en
otro. Ponerle `organization_id` reintroduce exactamente el modelo que ADR-0009 descarta —una
cuenta por tenant—, duplica humanos en la base y deja rota de entrada la deduplicación de
Personas de M07. Y si el `UNIQUE` del email tuviera alcance de tenant, la identidad única
(RF-M02-001) dejaría de ser una invariante verificable: sería una intención documentada que la
base no sostiene.

El problema de fondo es que ADR-0004 se escribió antes de la primera tabla funcional, mirando
datos de negocio de un tenant: turnos, sesiones, cobros. La identidad **precede** al tenant —al
registrarse todavía no hay organización— y lo **atraviesa**. No es la única tabla del proyecto
en esa situación, y hasta hoy cada excepción vivía como comentario suelto en su migración.

## Decisión

**Las tablas de identidad global no llevan `organization_id`, y sus `UNIQUE` son globales.**
`cuenta` sin tenant, con `uk_cuenta_email_normalizado UNIQUE (email_normalizado)` global, que
**es** la materialización en la base de RF-M02-001. `token_verificacion` y `refresh_token`
tampoco: cuelgan de `cuenta_id`, que ya es global, y agregarles tenant sería contradictorio.
`refresh_token.context_organization_id` y `context_consultorio_id` existen y son nullable, pero
no son ownership de tenant: son el **alcance del token**, un snapshot del último contexto
seleccionado para que el refresh reemita el access sobre el mismo contexto.

**Qué las aísla, ya que no es el tenant.** El aislamiento es **por cuenta**:

1. **Todo acceso es por `cuenta_id`**, resuelto desde el `sub` del token. No hay consulta que
   parta de un criterio externo y devuelva cuentas.
2. **La API no expone ningún listado de cuentas cross-tenant.** No existe `GET /accounts`; el
   listado de personas de una organización es de `organization` y sale de `membership`, que sí
   lleva `organization_id`.
3. **La autorización sobre una cuenta ajena pasa por la membership del actor.** Los endpoints
   administrativos de `/accounts/{id}` verifican vía `organization.spi` (`hasActiveMembership`)
   que la cuenta objetivo tenga membership en la organización del actor. Si no la tiene, se
   responde **`404`**, como cualquier recurso de otro tenant —nunca `403`, que confirmaría su
   existencia—.
4. **El `email_normalizado` no es consultable como oráculo:** los endpoints públicos responden
   de forma uniforme ([ADR-0018](0018-anti-enumeracion-uniforme.md)), así que el `UNIQUE` global
   no filtra existencia por colisión.

### Dónde no aplica la regla de ADR-0004, y por qué — lista única

| Objeto | Excepción | Motivo |
|---|---|---|
| `organization` | Sin `organization_id`; `slug` `UNIQUE` global | **Es** el tenant: su `id` es el `organization_id`. El slug es su clave pública en URLs; dos tenants con el mismo slug serían indistinguibles desde afuera |
| `plan`, `plan_limit`, `plan_feature` | Sin `organization_id`; `plan.code` `UNIQUE` global | Catálogo global de plataforma: no es dato de un tenant, es la oferta comercial que todos consumen |
| `cuenta` | Sin `organization_id`; `email_normalizado` `UNIQUE` global | Identidad única cross-tenant (ADR-0009). Aislamiento por cuenta, ver arriba |
| `token_verificacion`, `refresh_token` | Sin `organization_id`; `token_hash` `UNIQUE` global | Cuelgan de la cuenta, que es global. El hash es la clave de búsqueda y el token se presenta antes de que haya contexto |
| `organization_onboarding.idempotency_key`, `onboarding_registro.clave_idempotencia` | `UNIQUE` global | La clave nace **antes** del tenant: la genera el cliente al abrir el formulario. No hay tenant al que acotarla |
| `notification_outbox` | `organization_id` nullable; `clave_idempotente` `UNIQUE` global | Hay notificaciones previas al tenant (activación, recuperación). `NULL` = evento de identidad global, operable solo por admin de plataforma |
| `audit_event` | `organization_id` nullable | `NULL` solo para eventos de plataforma sin tenant. Todo evento de negocio lo lleva |
| `account_active_context` | Lleva `organization_id`, pero `UNIQUE (account_id)` es global | Un contexto activo por cuenta: el sujeto es la cuenta, cross-org por naturaleza. La columna queda para poder auditar la fila |
| `platform_schema_info` | Sin `organization_id` | Tabla técnica de infraestructura, no de negocio |

**Fuera de esta lista, la regla de ADR-0004 se aplica sin discusión.** Una excepción nueva exige
un ADR que la agregue a esta tabla; un comentario en la migración no alcanza.

## Alternativas consideradas

**`cuenta` con `organization_id` y `UNIQUE (organization_id, email_normalizado)`.** Cumpliría
ADR-0004 a la letra y haría el aislamiento de identidad idéntico al del resto del sistema.
Descartada: es literalmente el modelo "una cuenta por tenant" que ADR-0009 descartó con
argumentos que no cambiaron. La misma persona tendría dos contraseñas, dos recuperaciones, dos
identidades para auditar, y la deduplicación de Personas de M07 nacería rota.

**Cuenta global más una tabla puente que replique el email por tenant.** Conserva la identidad
única y satisface a quien exige ver `organization_id` en algún lado. Descartada: esa tabla
puente ya existe y se llama `membership` —lleva `organization_id`, es de `organization`, y es
donde vive el vínculo—. Duplicar el email sería desnormalizar un dato personal en N copias que
hay que mantener sincronizadas.

**Mover `refresh_token` a un esquema separado "no de negocio" para que ADR-0004 no lo alcance.**
Descartada como truco de definiciones: cambia dónde está la tabla para no explicar por qué es
distinta. Además Flyway es la única autoridad del esquema
([ADR-0003](0003-flyway-como-unica-autoridad-del-esquema.md)) y partirlo agrega operación sin
ganar aislamiento.

**Dejar la excepción solo en los comentarios de las migraciones**, que es como estaba antes de
este ADR. Descartada: los comentarios se leen cuando ya se está mirando el archivo, no cuando
alguien diseña la tabla número treinta y se pregunta si su caso califica. El criterio tiene que
estar en un solo lugar y ser citable.

## Consecuencias

### Positivas

- Una persona = una cuenta, verificable **consultando la base**: el `UNIQUE` global es la
  invariante, no una promesa del código. M07 y la auditoría de M24 referencian una identidad
  estable.
- El registro y la recuperación funcionan antes de que exista ningún tenant, sin filas fantasma
  ni organización placeholder.
- Existe **una sola lista** de excepciones a ADR-0004: el design challenge #3 de cada etapa se
  resuelve consultándola, en vez de reconstruir el criterio por arqueología de migraciones.

### Negativas

- **El aislamiento de identidad no es verificable con la misma consulta que el resto.** El QA no
  puede comprobarlo con "filtrá por `organization_id`": hay que revisar que cada acceso parta de
  `cuenta_id` y que ningún endpoint devuelva cuentas ajenas. Más caro y más fácil de olvidar.
- El aislamiento depende de **disciplina en la capa de aplicación**, no de una columna. Un
  endpoint futuro que busque cuentas por email o por nombre sin pasar por membership abriría una
  fuga que la base no impide. La barrera es el código y sus tests.
- Un test genérico "toda tabla tiene `organization_id`" necesita una lista de exclusión, que es
  otra cosa más que mantener sincronizada.
- Un email tomado por una cuenta de otra organización es indistinguible de un email libre para
  quien registra, lo que obliga al `202` uniforme del
  [ADR-0018](0018-anti-enumeracion-uniforme.md) y a resolverlo por el canal del email.
- La lista de excepciones es una invitación a estirarla. Cada "mi tabla también es especial" hay
  que discutirlo, y la respuesta por defecto es no.

### Qué obliga a hacer

- Toda migración nueva se revisa contra la lista de arriba **antes** de commitearla: si la tabla
  no está ahí, lleva `organization_id NOT NULL` y sus `UNIQUE` incluyen el tenant
  ([ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md)).
- `identity` es el único propietario de `cuenta`, `token_verificacion` y `refresh_token`; ningún
  otro módulo las consulta, y `organization` **jamás** importa `identity`
  ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)).
- Todo endpoint que reciba un `cuentaId` ajeno verifica membership en la organización del actor
  vía `organization.spi` y responde `404` si no la hay. Sin excepciones y con test dedicado.
- El QA de identidad prueba el caso cross-tenant: un admin de la organización A operando sobre
  una cuenta con membership solo en B. Esperado `404`, **nunca `200`** y nunca `403`.
- **AKINE-01.03**, al implementar la matriz de permisos, reemplaza la autorización interina de
  01.02 sin relajar esta verificación: la matriz decide *qué* puede hacer el actor, la
  membership sigue decidiendo *sobre quién*.
