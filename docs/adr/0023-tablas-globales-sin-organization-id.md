# ADR-0023 — Qué tabla puede no llevar `organization_id`: criterio único y lista consolidada

- **Estado:** Aceptado
- **Fecha:** 2026-08-26
- **Etapa:** AKINE-02.06
- **Supersede:** [ADR-0019](0019-identidad-global-sin-organization-id.md),
  [ADR-0020](0020-rol-de-plataforma-sin-organization-id.md),
  [ADR-0021](0021-catalogos-clinicos-globales-sin-organization-id.md),
  [ADR-0022](0022-feriados-globales-sin-organization-id.md)

## Contexto

El [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md) es categórico: *"toda tabla de
negocio lleva `organization_id NOT NULL`. Sin excepción"*, y *"todo índice y todo `UNIQUE`
incorpora el alcance del tenant"*; un `UNIQUE` sin `organization_id` queda declarado ahí como
*"un bug de aislamiento, no una optimización pendiente"*.

Se escribió antes de la primera tabla funcional, mirando datos de negocio de un tenant: turnos,
sesiones, cobros. Desde entonces aparecieron cuatro familias de tablas que no son eso, y cada
una llegó con su propio ADR incremental:

| ADR | Etapa | Qué agregó |
|---|---|---|
| [0019](0019-identidad-global-sin-organization-id.md) | 01.02 | Identidad global (`cuenta`, tokens) + la primera lista de excepciones ya existentes |
| [0020](0020-rol-de-plataforma-sin-organization-id.md) | 01.03 | `platform_role` |
| [0021](0021-catalogos-clinicos-globales-sin-organization-id.md) | 02.05 | Catálogos clínicos con dos poblaciones en la misma tabla |
| [0022](0022-feriados-globales-sin-organization-id.md) | 02.04 | `feriado` |

**Los tres primeros dicen textualmente que esto tenía que parar.** ADR-0020, en sus
consecuencias negativas: *"si llegan dos o tres más, corresponde un ADR que consolide y supersede
a los anteriores"*. ADR-0021, todavía más explícito, en "Qué obliga a hacer": *"la etapa que
agregue la cuarta excepción a ADR-0004 **no escribe otro ADR incremental**: consolida los cuatro
en uno que los supersede"*. ADR-0022 **fue** esa cuarta excepción, y no lo hizo: su línea 117
reconoce la deuda y la difiere una vez más — *"para entonces son cinco archivos con la misma
lista repartida"*.

AKINE-02.06 introduce `servicio`, el catálogo global de servicios de M27 (RN-M27-001, §30.6).
Sería la **quinta**. La regla ya se pospuso una vez, y este ADR la cumple en vez de posponerla
de nuevo.

El problema concreto que la dispersión causa, y que no es teórico: **hoy, para responder si una
tabla nueva puede o no omitir `organization_id`, hay que leer cinco documentos en paralelo y
deducir el criterio, porque en ninguno está escrito.** Cada ADR argumenta *su* caso; ninguno dice
qué tienen en común. Eso es exactamente lo que la regla de consolidación existía para prevenir,
y es también lo que hace fácil que la sexta excepción entre por analogía floja: *"mi tabla se
parece a `feriado`"* no es un argumento verificable si nadie escribió qué hace a `feriado`
legítimo.

## Decisión

**Este ADR reemplaza a 0019, 0020, 0021 y 0022 como la autoridad vigente sobre las excepciones a
ADR-0004.** Los cuatro quedan marcados `Superseded by ADR-0023` y se conservan por su valor
histórico: el argumento completo de cada caso —el aislamiento por cuenta de identidad, el
descarte de la organización centinela, la trampa de los `NULL` en los `UNIQUE` de MySQL— sigue
viviendo ahí y se cita desde acá. Lo que se consolida es **el criterio y la lista**, que es lo
que hay que consultar al diseñar una tabla.

### El criterio, en un solo lugar

Una tabla puede omitir `organization_id NOT NULL` **si y sólo si cumple las tres condiciones**:

1. **El hecho que guarda no pertenece a ningún tenant.** No es que sea compartido por varios: es
   que preguntarle "¿de qué organización es esta fila?" **no tiene respuesta**. El 25 de mayo es
   feriado lo sepa o no un centro; una cuenta existe antes de que exista ninguna organización;
   `PLATFORM_ADMIN` es, por definición de la matriz §1.3, el rol que no tiene membership en
   ninguna. Si la respuesta existe pero es "de varias" o "de todas", la tabla **no** califica:
   eso es una tabla de tenant con datos repetidos, y la solución es otra tabla, no una excepción.

2. **Dos tenants no pueden discrepar legítimamente sobre su contenido.** Si dos centros
   *deberían* poder tener valores distintos, el dato es de ellos y lleva tenant. El corolario es
   la prueba más útil: **toda excepción de esta lista tiene, o puede tener, una tabla hermana con
   `organization_id NOT NULL` donde vive la decisión del centro sobre ese hecho.** `feriado` es el
   calendario público y `consultorio_calendario.cierra_por_feriado` es la política de la sede.
   `servicio` es el concepto y `oferta_servicio_consultorio` es cómo lo presta un centro. `cuenta`
   es la identidad y `membership` es el vínculo con la organización. Si al diseñar una tabla
   global no se puede nombrar esa hermana, es señal de que el dato contextual se quiere meter en
   la tabla global — que es el error que este criterio previene.

3. **El aislamiento está resuelto por otro mecanismo, nombrado y verificable.** La columna de
   tenant es la barrera por defecto; quien la omite tiene que decir cuál usa en su lugar. Las
   formas admitidas hasta hoy son tres, y ninguna es "confiar en el código":
   - **Aislamiento por sujeto** (identidad, rol de plataforma): todo acceso parte de `cuenta_id`
     resuelto desde el `sub` del token, y no existe endpoint que devuelva filas por un criterio
     externo. Ver ADR-0019 §"Qué las aísla".
   - **No hay nada que aislar** (`plan`, `feriado`, `servicio`): la tabla es pública para todos
     los tenants por diseño. El control es de **escritura**, no de lectura: mutarla exige rol de
     plataforma, y un administrador de tenant recibe `403` si lo intenta.
   - **Centinela `owner_key`** (catálogos clínicos): cuando conviven dos poblaciones en la misma
     tabla. Ver la sección siguiente, que es la mitad técnica sin la cual la excepción sería el
     bug que ADR-0004 nombra.

**Las tres condiciones son conjuntivas.** Fallar una alcanza para que la tabla lleve
`organization_id NOT NULL`, y la respuesta por defecto ante *"mi tabla también es especial"*
sigue siendo **no**.

### La lista consolidada de excepciones

Vigente a AKINE-02.06. **Fuera de esta lista, ADR-0004 se aplica sin discusión.**

| Objeto | Forma de la excepción | Por qué califica | Origen |
|---|---|---|---|
| `organization` | Sin `organization_id`; `slug` `UNIQUE` global | **Es** el tenant: su `id` *es* el `organization_id`. El slug es su clave pública en URLs | 0019 |
| `plan`, `plan_limit`, `plan_feature` | Sin `organization_id`; `plan.code` `UNIQUE` global | Catálogo comercial de la plataforma. No es dato de un tenant: es la oferta que todos consumen | 0019 |
| `cuenta` | Sin `organization_id`; `email_normalizado` `UNIQUE` global | Identidad única cross-tenant ([ADR-0009](0009-identidad-unica-con-seleccion-de-contexto.md)). El `UNIQUE` global **es** la materialización de RF-M02-001 | 0019 |
| `token_verificacion`, `refresh_token` | Sin `organization_id`; `token_hash` `UNIQUE` global | Cuelgan de la cuenta, que es global. El token se presenta **antes** de que haya contexto | 0019 |
| `organization_onboarding.idempotency_key`, `onboarding_registro.clave_idempotencia` | `UNIQUE` global | La clave nace antes del tenant: la genera el cliente al abrir el formulario | 0019 |
| `notification_outbox` | `organization_id` nullable; `clave_idempotente` `UNIQUE` global | Hay notificaciones previas al tenant (activación, recuperación). `NULL` = evento de identidad global | 0019 |
| `audit_event` | `organization_id` nullable | `NULL` **sólo** para eventos de plataforma sin tenant. Todo evento de negocio lo lleva | 0019 |
| `account_active_context` | Lleva `organization_id`, pero `UNIQUE (account_id)` es global | Un contexto activo por cuenta: el sujeto es la cuenta, cross-org por naturaleza | 0019 |
| `platform_schema_info` | Sin `organization_id` | Tabla técnica de infraestructura, no de negocio | 0019 |
| `platform_role` | Sin `organization_id`; `UNIQUE (account_id, rol_activo)` global | Rol **de la plataforma**, no de un tenant. La matriz §1.3 lo define como el rol sin membership en ninguna organización: acotarlo a un tenant sería el rol contrario | 0020 |
| `especialidad`, `practica`, `nomenclador`, `nomenclador_item` | `organization_id` **nullable**; los `UNIQUE` van sobre `owner_key = IFNULL(organization_id, 0)` | **Dos poblaciones en la misma tabla**: `NULL` = concepto de plataforma que ven todos; valor = concepto propio del tenant. Se consultan juntas en el mismo selector | 0021 |
| `feriado` | Sin `organization_id`, ninguna columna de tenant | Hecho del calendario público nacional. La decisión de la sede —si cierra ese día— vive en `consultorio_calendario`, que sí lleva `organization_id NOT NULL` | 0022 |
| **`servicio`** | **Sin `organization_id` y sin `owner_key`** | **Concepto del catálogo global de la plataforma (RN-M27-001, §30.6, que no lista `organizationId` entre sus campos). Cómo lo presta un centro concreto vive en `oferta_servicio_consultorio`, que sí lleva `organization_id NOT NULL` (regla maestra 14)** | **0023** |

**Lo que NO es excepción, y por qué se anota:** `catalogo_solicitud` lleva `organization_id NOT
NULL` —una solicitud siempre la hace un tenant concreto—, y `consultorio_calendario`,
`profesional_disponibilidad`, `disponibilidad_excepcion` y `oferta_servicio_consultorio` también,
aunque referencien filas de tablas globales. **Referenciar una tabla global no presta alcance
global a nadie.**

### La mitad técnica: cuándo hace falta `owner_key` y cuándo sería decorativo

Es la distinción que más se presta a copiarse mal, y esta etapa la ejercita en las dos
direcciones.

**En MySQL varios `NULL` no colisionan en un índice único.** Por lo tanto, en una tabla donde
conviven filas globales (`organization_id IS NULL`) y filas de tenant, un
`UNIQUE (organization_id, codigo)` **deja de proteger exactamente la población más sensible**:
todas las filas globales tienen la columna en `NULL`, con lo que dos conceptos globales homónimos
no chocarían, y el catálogo que ven todos los tenants a la vez sería el único sin protección. La
solución es el centinela numérico:

```sql
owner_key BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL
UNIQUE (owner_key, codigo, deleted_key)
```

`0` no es ni puede ser el id de ninguna organización: `organization.id` es `AUTO_INCREMENT` y
arranca en 1. Un `CHECK` no sirve: ninguno puede expresar "dos `NULL` son iguales".

**Pero el centinela sólo tiene sentido si hay dos poblaciones.** En una tabla **enteramente**
global —`feriado`, `servicio`— no hay ninguna fila con `organization_id`, así que la columna no
existe, no hay `NULL` que colisionar y `owner_key` sería una columna generada que nunca distingue
nada. La regla:

> **`owner_key` no es "la forma de las tablas globales": es la forma de las tablas con DOS
> poblaciones.** Una tabla enteramente global no lleva `organization_id` en absoluto, y sus
> `UNIQUE` son globales sin más.

`deleted_key` es un asunto **independiente** y se decide aparte: lo lleva toda tabla con baja
lógica cuyo código o nombre deba poder reusarse después de la baja (V18, V19, V20, V24). Que una
tabla sea global no dice nada sobre si necesita `deleted_key`, y `servicio` lo necesita.

### Qué NO califica

Esta es la mitad que evita la sexta excepción por analogía floja, y es la que faltaba escrita.

- **"Es un catálogo, y los catálogos son globales."** No. `plan` y `feriado` son globales porque
  el hecho no es de nadie, no porque la palabra "catálogo" aparezca. Un catálogo de motivos de
  cancelación que cada centro configura es un catálogo **del tenant** y lleva
  `organization_id NOT NULL`.
- **"Es de sólo lectura para los tenants."** El modo de acceso no cambia de quién es el dato. Una
  tabla de tenant que hoy nadie edita sigue siendo de ese tenant.
- **"Hoy todas las filas tendrían el mismo valor."** Es un argumento sobre el volumen actual, no
  sobre el modelo. Al revés: una tabla donde todas las filas tienen el mismo `organization_id`
  hoy y podrían tener distintos mañana **debe** llevar la columna desde el día uno, porque
  agregarla después es un backfill sobre datos vivos.
- **"Es una tabla técnica / de infraestructura."** Sólo vale si de verdad no guarda ningún hecho
  de negocio, como `platform_schema_info`. Una tabla de auditoría, de outbox o de jobs **sí**
  guarda hechos de negocio, y por eso `audit_event` y `notification_outbox` llevan la columna
  nullable en vez de no llevarla.
- **"Moverla a otro esquema/módulo para que ADR-0004 no la alcance."** Descartado dos veces ya
  (ADR-0019 para `refresh_token`, ADR-0020 para `platform_role`): cambia dónde está la tabla para
  no explicar por qué es distinta. Flyway es la única autoridad del esquema
  ([ADR-0003](0003-flyway-como-unica-autoridad-del-esquema.md)).
- **"Una organización centinela «plataforma» y `organization_id NOT NULL` apuntando a ella."**
  Descartado dos veces (0020, 0021): es una fila fantasma en la tabla de tenants con la que todo
  conteo, reporte, listado de clientes y facturación tendría que aprender a convivir.
- **Y el caso puntual de esta etapa: "es global, entonces lleva `owner_key` como el catálogo
  clínico".** No. Ver la sección anterior: sin segunda población, el centinela no discrimina nada.

## Alternativas consideradas

**No consolidar y escribir ADR-0023 como quinta excepción incremental.** Es lo más barato y es
lo que hicieron 0020, 0021 y 0022. Descartada porque es literalmente lo que 0020 y 0021 prohíben
por escrito y lo que 0022 reconoce como deuda. El costo de seguir difiriéndolo no es estético:
son seis archivos que hay que leer juntos para responder una pregunta que se hace en cada design
challenge #3, y el criterio seguiría sin estar escrito en ningún lado.

**Consolidar sólo la tabla de excepciones, dejando los cuatro ADR vigentes.** Un documento nuevo
"lista de excepciones", sin superseder nada. Descartada: dos documentos vigentes sobre lo mismo
es peor que uno disperso, porque nada dice cuál gana cuando difieren. La regla 2 del
`docs/adr/README.md` —*"si una decisión cambia, se escribe uno nuevo que la supersede y se marca
el anterior"*— ya prevé la forma correcta.

**Reescribir los cuatro ADR editándolos en su lugar.** Quedaría un solo archivo por tema, siempre
actualizado. Descartada: la regla 1 del README —*"los ADR aceptados no se editan… el registro es
histórico: reescribirlo destruye justamente lo que lo hace útil"*—. Marcarlos como superseded es
una edición del **encabezado**, que es el mecanismo que el propio README define; reescribir el
cuerpo no lo es.

**Reemplazar todo por una lista de exclusión en el código (un test de convenciones con su
array).** El test que exige `organization_id` necesita esa lista de todos modos. Descartada como
*sustituto*: un array en un test dice **qué** está excluido y nunca **por qué**, y es justo el
porqué lo que hace falta al diseñar la tabla número cuarenta. La lista del test es la
materialización de este ADR, no su reemplazo, y tiene que citarlo.

**Relajar ADR-0004 para que diga "casi toda tabla".** Descartada: el valor de ADR-0004 está en
que es categórico. Una regla con excusa incorporada deja de ser una barrera y pasa a ser una
sugerencia, y el aislamiento multi-tenant es la propiedad que este producto no puede permitirse
discutir caso por caso en un code review.

## Consecuencias

### Positivas

- **Existe una sola pregunta con una sola respuesta:** el design challenge #3 de cada etapa se
  resuelve consultando este archivo, sin arqueología de migraciones ni de ADR.
- **El criterio es citable y verificable.** "Mi tabla se parece a `feriado`" deja de ser un
  argumento: hay que mostrar las tres condiciones, y en particular **nombrar la tabla hermana con
  tenant** donde vive la decisión del centro. Esa exigencia sola descarta la mayoría de los casos
  dudosos.
- **La distinción `owner_key` / sin columna queda escrita**, con las dos direcciones ejercitadas
  en la misma etapa. Era la parte más fácil de copiar mal: `servicio` es global **y** no lleva
  `owner_key`, algo que la lectura ingenua de ADR-0021 sugeriría al revés.
- **Se paga una deuda que ya se había pospuesto una vez**, y con el precedente sentado de que la
  regla de consolidación se cumple. La sexta excepción, si llega, agrega una fila **acá**.

### Negativas

- **Cuatro ADR aceptados cambian de estado**, y cualquier documento, migración o comentario que
  los cite sigue apuntando a archivos superseded. Se mitiga dejando en cada uno un puntero
  explícito a este, pero las citas viejas —las cabeceras de V6..V9, V12, V20, V22, V23— siguen
  nombrando el ADR original, y eso es correcto: **citan la decisión vigente al momento de
  escribirse**. Quien las lea tiene que seguir el puntero.
- **Consolidar no elimina la lectura de los originales.** El argumento completo de cada caso
  —por qué el aislamiento por cuenta alcanza, por qué se descartó la organización centinela— no
  se copia acá, así que para discutir un caso análogo hay que abrir el ADR de origen. Lo que este
  documento garantiza es que **el criterio y la lista** están en un solo lugar, no que toda la
  argumentación lo esté.
- **La lista sigue siendo una invitación a estirarla.** Trece filas se leen como "hay muchas
  excepciones" y no como "hay trece casos, cada uno peleado". La sección "Qué no califica" existe
  por eso, y hay que hacerla valer en cada review.
- **El aislamiento de estas tablas no se verifica con la consulta de siempre.** El QA no puede
  comprobarlo con "filtrá por `organization_id`". Para identidad hay que revisar que todo acceso
  parta de `cuenta_id`; para los catálogos, que toda consulta pase por `owner_key` y que la lista
  de dueños la arme el servidor. Más caro y más fácil de olvidar.
- **`owner_key` sigue siendo una columna que hay que entender antes de escribir cualquier
  consulta nueva** sobre los catálogos clínicos: un `WHERE organization_id = ?` escrito por
  reflejo compila, corre y **esconde en silencio todo el catálogo global**. El síntoma es un
  selector que se quedó corto, no un error.

### Qué obliga a hacer

- **Toda migración nueva se revisa contra la lista de este ADR antes de commitearla.** Si la
  tabla no está acá, lleva `organization_id NOT NULL` y todos sus `UNIQUE` e índices empiezan por
  él ([ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md), `AGENT.md` §5).
- **Una excepción nueva agrega una fila a la tabla de este ADR y demuestra las tres condiciones**,
  incluida la de nombrar la tabla hermana con tenant. Un comentario en la migración no alcanza, y
  un ADR incremental nuevo tampoco: **la lista vive acá**. Si el criterio mismo tiene que cambiar,
  entonces sí corresponde un ADR que supersede a éste.
- La migración que crea una tabla de esta lista **cita ADR-0023 en su encabezado**, junto a
  ADR-0004. `V24__m27_servicio_y_oferta.sql` lo hace.
- **Ninguna consulta sobre `especialidad`, `practica`, `nomenclador` o `nomenclador_item` filtra
  por `organization_id`**: se filtra por `owner_key IN (:owners)`, con la lista armada por el
  servicio después de validar el contexto.
- **Ninguna consulta sobre `feriado` o `servicio` recibe un `organizationId`.** Si alguien se lo
  agrega —a la firma del repositorio, al `WHERE`, al `spi`— entendió mal el alcance de la tabla,
  y el síntoma no es un error visible sino un filtro que no filtra nada.
- **Mutar una fila global exige rol de plataforma**, sin permiso de tenant de por medio. Un
  administrador de tenant la *ve* y recibe `403` si intenta tocarla; un recurso de otro tenant se
  responde `404`, nunca `403` ([ADR-0018](0018-anti-enumeracion-uniforme.md)).
- **Una fila global nunca depende de una contextual.** Una práctica global sólo cuelga de una
  especialidad global; una `oferta_servicio_consultorio` sí puede referenciar un `servicio`
  global, porque la dependencia va en la dirección permitida. Ninguna FK puede expresarlo —compara
  ids, no alcances— así que lo sostiene la aplicación con test dedicado.
- **El test genérico "toda tabla lleva `organization_id NOT NULL`" mantiene su lista de exclusión
  sincronizada con la tabla de este ADR**, y sólo con ella. Ya no hay que consultar cinco
  archivos.
