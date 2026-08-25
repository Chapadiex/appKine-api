# ADR-0021 — Los catálogos clínicos llevan `organization_id` nullable

- **Estado:** Aceptado
- **Fecha:** 2026-08-25
- **Etapa:** AKINE-02.05

## Contexto

El [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md) exige que toda tabla de negocio
lleve `organization_id NOT NULL` y que todo `UNIQUE` incorpore el alcance del tenant. El
[ADR-0019](0019-identidad-global-sin-organization-id.md) reunió las excepciones existentes en una
tabla única y la cerró con una condición explícita: *"una excepción nueva exige un ADR que la
agregue a esta tabla; un comentario en la migración no alcanza"*. El
[ADR-0020](0020-rol-de-plataforma-sin-organization-id.md) agregó la primera fila por esa vía.

AKINE-02.05 introduce el catálogo clínico de M06 —`especialidad`, `practica`, `nomenclador` y
`nomenclador_item`— y la especificación es explícita en que **un mismo concepto puede ser de la
plataforma o de un centro**:

- La etapa 02.05, en su apartado de seguridad: *"Admin plataforma para global; admin consultorio
  para conceptos contextuales/solicitudes"*.
- RF-M06-005 —*"Canalizar un concepto global no disponible"*— sólo tiene sentido si existe una
  población de conceptos **globales** que un tenant no puede crear por sí mismo. Si todos los
  conceptos fueran de un tenant, no habría nada que pedirle a nadie.
- La etapa 02.06, que depende de ésta, construye sobre la misma distinción: *"Servicio global y
  oferta por consultorio"*.

Los hechos concretos:

1. "Kinesiología" o el Nomenclador Nacional **no son de ningún centro**. Son la oferta común que
   todos los tenants ven en su selector, mantenida por la plataforma, exactamente como
   `plan`/`plan_limit`/`plan_feature`, que ya son una excepción reconocida por ADR-0019.
2. Al mismo tiempo, un centro **sí** tiene conceptos propios: una práctica que sólo él hace, un
   nomenclador interno. Ésos son datos suyos y nadie más los ve.
3. Las dos poblaciones se **consultan juntas**: un formulario clínico ofrece las prácticas de
   plataforma y las del centro en la misma lista, y no le importa de dónde salió cada una.

## Decisión

**`especialidad`, `practica`, `nomenclador` y `nomenclador_item` llevan `organization_id`
NULLABLE, donde `NULL` significa "concepto global de plataforma".**

Se agregan a la lista de excepciones de ADR-0019 —que hay que leer junto con éste y con
ADR-0020, porque un ADR aceptado no se edita— con esta fila:

| Objeto | Excepción | Motivo |
|---|---|---|
| `especialidad`, `practica`, `nomenclador`, `nomenclador_item` | `organization_id` nullable; los `UNIQUE` van sobre `owner_key = IFNULL(organization_id, 0)` | Catálogo clínico con **dos poblaciones en la misma tabla**: `NULL` = concepto de plataforma que ven todos los tenants; valor = concepto propio de ese tenant. Las dos se consultan juntas en el mismo selector |

**`catalogo_solicitud` NO es una excepción** y lleva `organization_id NOT NULL`: una solicitud
siempre la hace un tenant concreto, no existen solicitudes de plataforma.

### Por qué nullable y no dos tablas separadas

La alternativa evidente es `especialidad_global` (sin tenant) más `especialidad` (con tenant
`NOT NULL`), que dejaría ADR-0004 intacto. Se descarta:

- **La consulta principal las lee juntas.** Todo selector clínico pide "las especialidades que
  este centro puede elegir", que son las globales más las propias. Con dos tablas, eso es un
  `UNION` en cada consulta, dos índices que mantener y dos entidades que mapear — y el `ORDER BY
  name` de la búsqueda incremental deja de resolverse por índice.
- **Las referencias apuntarían a dos lados.** Una práctica cuelga de una especialidad que puede
  ser global o del tenant. Con dos tablas, `practica` necesitaría dos FK nullables y un `CHECK`
  de exclusividad, que es un modelo peor que el nullable que se quería evitar.
- **La duplicación se multiplica por cuatro**, una vez por concepto, y se propaga a M16, que
  referencia prácticas y códigos sin que le importe de quién son.

### Qué las aísla, ya que el `NOT NULL` no lo hace

1. **Toda consulta se acota por `owner_key`**, la columna generada `IFNULL(organization_id, 0)`.
   El servicio arma la lista de dueños visibles —`[0]`, `[orgId]` o `[0, orgId]`— **después** de
   validar el contexto, y el cliente no puede nombrar ninguna otra: las rutas del catálogo no
   llevan `orgId`, el tenant sale del contexto ya revalidado contra la base. Un concepto de otro
   tenant no aparece en ninguna de las tres listas y por lo tanto **no resuelve nunca**.
2. **Un concepto de otro tenant se responde 404**, como cualquier recurso ajeno, nunca 403
   ([ADR-0018](0018-anti-enumeracion-uniforme.md)).
3. **Mutar un concepto global exige rol de plataforma**, sin ningún permiso de tenant de por
   medio. Un administrador de tenant lo *ve* y recibe 403 si intenta tocarlo; su camino es
   RF-M06-005.
4. **Un `PLATFORM_ADMIN` no ve ni muta conceptos contextuales de ningún tenant.** Se aparta hacia
   el lado que no concede de más, igual que §10.2 de la matriz hizo con los espacios. Como
   consecuencia, tampoco hace falta `support_access` en este módulo: lo único que la plataforma
   toca es el catálogo común, que no es dato de nadie.
5. **Un concepto global no puede depender de uno contextual.** Una práctica global sólo cuelga de
   una especialidad global; una vigencia de un nomenclador global sólo codifica una práctica
   global. Sin esa regla, un tenant decidiría por sí solo el destino de un concepto que ven todos
   los demás. La FK no puede expresarlo —compara ids, no alcances— así que lo sostiene
   `CatalogoService` con un 409 `catalogo-scope-mismatch`.

### El `UNIQUE`: por qué `owner_key` y no `organization_id`

Ésta es la mitad técnica de la decisión, y sin ella la excepción sería el bug que ADR-0004
nombra. **En MySQL varios `NULL` no colisionan en un índice único.** Un
`UNIQUE (organization_id, codigo)` dejaría de proteger exactamente la población más sensible:
todas las filas globales tienen `organization_id IS NULL`, con lo que **dos especialidades
globales homónimas no chocarían**, y el catálogo de plataforma —el único que ven todos los
tenants a la vez— sería el único sin protección contra duplicados.

La solución es el **centinela numérico**, la forma que V10 ya usa:

```sql
owner_key BIGINT AS (IFNULL(organization_id, 0)) STORED NOT NULL
UNIQUE (owner_key, codigo, deleted_key)
UNIQUE (owner_key, name,   deleted_key)
```

`0` no es ni puede ser el id de ninguna organización: `organization.id` es `AUTO_INCREMENT` y
arranca en 1. Con él, **todas** las filas entran al índice y las globales se comparan entre sí.
Un `CHECK` aparte no sirve: ningún `CHECK` puede expresar "dos `NULL` son iguales".

`deleted_key` es el otro discriminador, el centinela de fecha de V18/V19, y está por el motivo de
siempre: RN-M06-001 exige baja lógica y RN-M06-002 que el histórico conserve su significado, así
que el código de un concepto dado de baja tiene que poder reusarse sin borrar la fila vieja.

## Alternativas consideradas

**Dos tablas por concepto, una global y una por tenant.** Cumpliría ADR-0004 a la letra.
Descartada por los tres motivos de arriba: `UNION` en la consulta principal, FK duplicadas y
nullables en las referencias, y ocho tablas donde hay cuatro.

**Una organización centinela "plataforma" con `organization_id NOT NULL` apuntando a ella.**
Formalmente no habría excepción alguna. Descartada: es una fila fantasma en la tabla de tenants
con la que todo conteo, reporte, listado de clientes y facturación tendría que aprender a
convivir. Es la misma alternativa que ADR-0020 ya descartó para `platform_role`, por la misma
razón.

**Copiar el catálogo global en cada tenant al crearlo.** `organization_id NOT NULL` sin
excepciones y sin centinela. Descartada: convierte cada corrección de la plataforma —un nombre
mal escrito, un código actualizado— en un backfill sobre N tenants, con la garantía de que
algunos quedan atrás. Y RF-M06-005 dejaría de tener sentido: no habría un catálogo común al que
pedirle nada.

**`organization_id NOT NULL` y que cada centro cargue todo su catálogo desde cero.** Es la
opción trivial. Descartada porque contradice la etapa —que exige la distinción global/contextual
de frente— y porque el producto quedaría obligando a cada centro nuevo a tipear el Nomenclador
Nacional entero antes de poder registrar una sesión.

**Dejar la excepción sólo en el comentario de la migración**, como estaba antes de ADR-0019.
Descartada por la regla que ADR-0019 fijó: los comentarios se leen cuando ya se está mirando el
archivo, no cuando alguien diseña la tabla número cuarenta y se pregunta si su caso califica.

## Consecuencias

### Positivas

- El catálogo común existe como dato, no como convención: "esta práctica es de plataforma" se
  responde con un `SELECT`, y una corrección de la plataforma la ven todos los tenants sin
  backfill.
- Un centro nuevo empieza con un catálogo usable el primer día y agrega lo suyo encima.
- La consulta que más se ejecuta —el selector de prácticas— es una sola, sobre una sola tabla y
  un solo índice.
- El catálogo global queda protegido contra duplicados, que es justamente lo que la forma
  ingenua del `UNIQUE` habría dejado sin proteger.

### Negativas

- **El aislamiento de estas cuatro tablas no se verifica con la consulta de siempre.** El QA no
  puede comprobarlo con "filtrá por `organization_id`": hay que revisar que toda consulta pase
  por `owner_key` y que la lista de dueños la arme el servidor. Más caro y más fácil de olvidar.
- **`owner_key` es una columna que hay que entender antes de escribir cualquier consulta nueva.**
  Un `WHERE organization_id = ?` escrito por reflejo en una etapa futura compila, corre, y
  **esconde silenciosamente todo el catálogo global** — el síntoma es un selector que se quedó
  corto, no un error.
- **Un test genérico "toda tabla lleva `organization_id NOT NULL`" necesita cuatro entradas más**
  en su lista de exclusión, sincronizada ahora con tres ADR en vez de con uno.
- **La lista única de ADR-0019 quedó repartida en tres archivos.** Ya lo anotaba ADR-0020 como
  consecuencia; con esta excepción son tres saltos. **Corresponde un ADR que consolide y
  supersede a los tres** antes de que llegue la cuarta.
- Un administrador de plataforma que se equivoca al dar de baja un concepto global lo saca del
  selector de todos los tenants a la vez. La baja es lógica y el histórico sobrevive, pero no hay
  reactivación: la corrección es un concepto nuevo.

### Qué obliga a hacer

- La migración que crea estas tablas cita este ADR en su encabezado, junto a ADR-0004, ADR-0019 y
  ADR-0020. `V20__m06_catalogos_clinicos.sql` lo hace.
- **Ninguna consulta nueva sobre estas tablas filtra por `organization_id`**: se filtra por
  `owner_key IN (:owners)`, con la lista armada por el servicio después de validar el contexto.
- Toda tabla futura que referencie un concepto del catálogo tiene que aceptar que la referencia
  puede ser global: no puede asumir que el `organization_id` del concepto coincide con el suyo.
- **Un concepto global nunca depende de uno contextual**, y eso se valida en la aplicación con
  test dedicado, porque ninguna FK puede sostenerlo.
- El QA prueba el caso cruzado: un tenant A operando sobre un concepto propio de B. Esperado
  `404`, **nunca `200`** y nunca `403`. Y el caso global: un admin de tenant editando un concepto
  de plataforma. Esperado `403`, nunca `200`.
- La etapa que agregue la cuarta excepción a ADR-0004 **no escribe otro ADR incremental**:
  consolida los cuatro en uno que los supersede.
