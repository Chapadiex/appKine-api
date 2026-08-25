# AKINE-02.05 — Especialidades, prácticas y nomencladores

> **Qué es este documento.** El registro de diseño de la etapa: las decisiones que se tomaron,
> las que quedaron abiertas y lo que la etapa deliberadamente no hizo. **No repite** lo que ya
> está escrito en otro lado y es autoridad ahí:
>
> | Dónde | Qué contiene |
> |---|---|
> | [ADR-0021](../adr/0021-catalogos-clinicos-globales-sin-organization-id.md) | Por qué las cuatro tablas del catálogo llevan `organization_id` nullable, qué las aísla y por qué el `UNIQUE` va sobre `owner_key` |
> | `V20__m06_catalogos_clinicos.sql` (encabezado) | El modelo tabla por tabla, los dos ejes de NULL en los `UNIQUE` de MySQL y las FK |
> | [Matriz de permisos §11](../seguridad/matriz-permisos-minima.md) | `catalogo:read` y `catalogo:manage` propuestos, la autorización interina y lo que la etapa NO habilita |
> | `openapi/akine-api.yaml` **0.9.0** | El contrato: 11 operaciones nuevas, aditivas |

## 0. Resumen de una línea

El catálogo clínico de M06 —especialidad, práctica, nomenclador y sus vigencias— con **dos
poblaciones en la misma tabla**: la de la plataforma, que ven todos los centros, y la propia de
cada centro; búsqueda incremental sobre las dos juntas, vigencias históricas que no se editan, y
el pedido de RF-M06-005 como única vía de un centro hacia el catálogo común.

## 1. Alcance

### 1.1 Entra

- **Cuatro conceptos**: `especialidad`, `practica`, `nomenclador` y `nomenclador_item`
  (la vigencia), más `catalogo_solicitud`.
- **Alta, edición y baja lógica** de conceptos, con motivo obligatorio en la baja y `version`
  obligatoria en la edición.
- **Búsqueda paginada** por nombre y código, normalizada, con filtros de estado
  (`ACTIVO`/`INACTIVO`/`TODOS`), alcance (`GLOBAL`/`ORGANIZACION`/`TODOS`) y especialidad.
- **Vigencias del nomenclador**: alta, listado filtrado por código o práctica, y baja. Sin
  edición, por RN-M06-002 — ver §4.
- **Solicitudes** de alta al catálogo global: crear, listar y resolver (RF-M06-005).
- **Frontend**: las tres pantallas de `features/catalog`, más el cliente regenerado y fijado
  en 0.9.0.

### 1.2 Queda afuera, con etapa destino

| Qué | Dónde va |
|---|---|
| Consola de plataforma: resolver solicitudes desde la interfaz | Etapa propia — ver §7.3 |
| Que un concepto aprobado se publique solo al catálogo global | Decisión tomada: **no**. La aprobación es una decisión registrada; el alta es un acto aparte (matriz §11.2) |
| Servicio global y oferta por consultorio | 02.06 |
| Que un convenio referencie una vigencia concreta | 03.x / M16 |
| Importación masiva de un nomenclador nacional | Sin etapa asignada — ver §8 |

## 2. Modelo — lo que hay que saber antes de escribir una consulta

Todo lo demás está en ADR-0021 y en el encabezado de `V20`. Lo que no se puede dejar de leer:

1. **Ninguna consulta filtra por `organization_id`.** Se filtra por `owner_key IN (:owners)`,
   con la lista armada por `CatalogoService` **después** de validar el contexto: `[0]` para lo
   global, `[orgId]` para lo propio, `[0, orgId]` para las dos poblaciones juntas.
   Un `WHERE organization_id = ?` escrito por reflejo compila, corre, y **esconde en silencio
   todo el catálogo global**.
2. **`owner_key = IFNULL(organization_id, 0)`**, columna generada `STORED`. Existe porque en
   MySQL varios `NULL` no colisionan en un índice único: sin el centinela, dos especialidades
   globales homónimas no chocarían, y el catálogo común sería el único sin protección contra
   duplicados.
3. **Un concepto global no puede depender de uno contextual.** Ninguna FK puede expresarlo
   —compara ids, no alcances—, así que lo sostiene `CatalogoService` con
   `409 catalogo-scope-mismatch` y tiene test dedicado.

## 3. Contrato — 11 operaciones, versión 0.9.0

| Ruta | Operaciones |
|---|---|
| `/api/v1/catalogos/{tipo}` | `createCatalogoConcepto`, `searchCatalogo` |
| `/api/v1/catalogos/{tipo}/{conceptoId}` | `getCatalogoConcepto`, `updateCatalogoConcepto` |
| `/api/v1/catalogos/{tipo}/{conceptoId}/deactivate` | `deactivateCatalogoConcepto` |
| `/api/v1/catalogos/nomencladores/{id}/items` | `createNomencladorVigencia`, `listNomencladorVigencias` |
| `/api/v1/catalogos/nomencladores/{id}/items/{itemId}/deactivate` | `deactivateNomencladorVigencia` |
| `/api/v1/catalogo-solicitudes` | `createCatalogoSolicitud`, `listCatalogoSolicitudes` |
| `/api/v1/catalogo-solicitudes/{id}/resolve` | `resolveCatalogoSolicitud` |

**El tipo va en la ruta y no en el cuerpo.** `{tipo}` es `especialidades`, `practicas` o
`nomencladores`. Es lo que permite que una sola pantalla —y un solo controller— sirva a los tres
sin un `switch` por operación, y lo que hace que un tipo inventado sea un `404` del router en vez
de un `400` de validación.

**Ninguna ruta lleva `orgId`.** El tenant sale del contexto ya revalidado contra la base. Un id
en la URL sería un segundo lugar desde donde elegir tenant, y el único que el token acota es el
del contexto.

**Diez códigos de problema nuevos**, todos en `ProblemType`: `catalogo-code-taken`,
`catalogo-name-taken`, `catalogo-inactive`, `catalogo-already-inactive`,
`catalogo-reference-inactive`, `catalogo-has-active-references`, `catalogo-scope-mismatch`,
`nomenclador-vigencia-overlap`, `catalogo-solicitud-duplicada`, `catalogo-solicitud-ya-resuelta`.

**Cambio aditivo**: ninguna operación previa cambió de `operationId`, ruta ni schema, así que un
cliente de 0.8.0 ve exactamente lo mismo.

## 4. La decisión clínica de la etapa: una vigencia no se corrige

`nomenclador_item` **no tiene `PATCH`**, y no es una omisión.

RN-M06-002 y RN-M06-003 dicen que lo que se presentó a un financiador conserva el código y el
valor que regían **en ese momento**. Una vigencia editable es exactamente la forma de reescribir
el pasado sin dejar rastro: el síntoma no sería un error, sería un número distinto en un reporte
viejo, meses después, sin nada que lo explique.

Lo que sí existe:

- **Cerrar** la vigencia vigente (baja lógica, con motivo) y **abrir** una nueva con el código
  nuevo. Las dos quedan en el listado.
- `409 nomenclador-vigencia-overlap` cuando dos vigencias del mismo código se pisan en el tiempo:
  sin eso, "qué valor regía el 3 de marzo" tendría dos respuestas.

La misma lógica, más floja, aplica a los conceptos: se editan nombre y descripción, **nunca el
código**. El código es la clave estable con la que el histórico resuelve.

## 5. Permisos — interinos, y dicho de frente

M06 necesita dos códigos que el catálogo vinculante de la matriz **no tiene**. La etapa **no los
inventó**, por la misma regla con la que 01.03 se negó a inventar un código de edición de
organización y 02.02 dejó `espacio:read` propuesto: agregarle una fila al catálogo es una
decisión de la matriz, no de una etapa.

Mientras tanto:

- **Las lecturas no evalúan ningún permiso**: se autorizan por pertenencia vigente. Consultar qué
  prácticas existen es lo que necesita cualquiera que registre una sesión.
- **Las mutaciones contextuales** usan `consultorio:manage`.
- **Las mutaciones globales** exigen rol de plataforma, sin permiso de tenant de por medio.

La propuesta completa —`catalogo:read` y `catalogo:manage`, su tabla por rol, y las **dos
diferencias** que su aprobación introduciría— está en la matriz §11.1. Cuando se aprueben, cambia
a qué se llama al evaluador y nada más: **los códigos HTTP de rechazo son los mismos**.

## 6. Aislamiento — qué se probó

Lo que ADR-0021 exige verificar, y que el QA no puede comprobar con "filtrá por
`organization_id`":

| Escenario | Esperado |
|---|---|
| Tenant A opera sobre un concepto propio de B | **404**, nunca 200 y nunca 403 |
| Admin de tenant edita un concepto de plataforma | **403**, nunca 200 |
| Concepto global que intenta colgar de uno contextual | **409 catalogo-scope-mismatch** |
| Dos vigencias del mismo código con ventanas que se pisan | **409 nomenclador-vigencia-overlap** |

Los cuatro están cubiertos por `CatalogosIT` contra MySQL real.

## 7. Frontend — `appKine-web`

### 7.1 Tres pantallas, montadas en `/catalogo`

| Ruta | Pantalla |
|---|---|
| `/catalogo/:tipo` | Listado con búsqueda incremental, filtros, alta, edición y baja |
| `/catalogo/nomencladores/:id/vigencias` | Vigencias de un nomenclador |
| `/catalogo/solicitudes` | Pedidos al catálogo de la plataforma |

`authGuard` + `contextGuard` en el padre; **ningún `permissionGuard`**, por lo mismo que en
espacios: exigir `consultorio:manage` para mirar la tabla dejaría afuera a todo el equipo
clínico, que es justamente quien la consulta. Las acciones van detrás de `*akinePermiso`.

### 7.2 Las tres cosas que la interfaz no aplana

1. **De quién es cada concepto.** Las dos poblaciones se listan juntas —así las consulta el
   selector clínico— y la columna de alcance lo dice fila por fila. **Las filas globales no traen
   acciones**: el backend responde `403` y un botón que siempre falla es peor que ninguno. En su
   lugar, el enlace al pedido.
2. **Activo no es vigente.** `estado` es administrativo; `vigente` lo calcula el backend. La
   pantalla lo muestra en vez de recalcularlo con el reloj del navegador, que está en otra zona.
3. **Pedir no es dar de alta.** La pantalla de solicitudes dice, antes del botón, que un concepto
   propio se puede dar de alta ya mismo. Sin eso, un centro espera una resolución que no necesita
   para trabajar.

### 7.3 Lo que el frontend NO hace, y por qué

- **No crea conceptos globales.** `alcance: ORGANIZACION` va fijo en el alta, sin selector: crear
  uno global exige rol de plataforma y **hoy el frontend no tiene ninguna forma de saber si quien
  mira lo tiene** — no hay endpoint que lo diga. Un selector de alcance sería, para casi todos,
  una opción que termina en `403`.
- **No resuelve solicitudes.** Misma causa. `resolveCatalogoSolicitud` existe en el contrato y no
  tiene consumidor. Ver §8.
- **No edita vigencias.** Porque el contrato no lo publica, y el javadoc de la pantalla dice por
  qué antes de que alguien busque el botón.

## 8. Deuda diferida

| Deuda | Destino |
|---|---|
| Consola de plataforma: resolver solicitudes y administrar el catálogo global desde la interfaz | Etapa propia. **Bloqueante para RF-M06-005 de punta a punta**: hoy una solicitud se resuelve por API, no por pantalla |
| Endpoint que le diga al frontend si quien mira es administrador de plataforma | Prerrequisito del anterior |
| `catalogo:read` y `catalogo:manage` aprobados en la matriz §5 | Pendiente de decisión — matriz §11.1 |
| ADR que consolide y supersede a ADR-0019, ADR-0020 y ADR-0021 | Antes de la cuarta excepción a ADR-0004 |
| E2E de las tres pantallas contra el stack real | No corrido en esta etapa — ver §9 |
| Importación masiva de un nomenclador nacional | Sin etapa asignada. Hoy se carga vigencia por vigencia |

## 9. Verificación

**Backend**: `./mvnw verify` en verde — 90 tests de integración contra MySQL real (8 de ellos de
`CatalogosIT`), 46 tests de catálogo entre unitarios e integración, gates de cobertura cumplidos.

**Frontend**: `npm run build`, `npm run lint` y `npm run test:ci` en verde — **361 tests
unitarios**, cobertura 85,97 % instrucción / 80,25 % rama sobre un piso de 80 %. Las tres
pantallas nuevas auditan con axe.

**No corrido**: los E2E de Playwright contra el stack levantado, y el QA manual con validación
contra la base. Las tres pantallas nunca se abrieron en un navegador real.

## 10. Contexto para la etapa siguiente

- **`CatalogoDirectory` es el único punto de entrada de otros módulos** al catálogo
  (`com.akine.resource.spi`). M14 y M16 no leen las tablas: piden un `CatalogoSnapshot`.
- **02.06 hereda la distinción global/contextual entera**: el servicio global y la oferta por
  consultorio se construyen sobre la misma pareja de poblaciones, con el mismo `owner_key`.
- **Toda tabla futura que referencie un concepto tiene que aceptar que la referencia puede ser
  global**: no puede asumir que el `organization_id` del concepto coincide con el suyo.
