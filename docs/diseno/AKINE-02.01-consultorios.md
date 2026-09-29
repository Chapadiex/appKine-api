# AKINE-02.01 — Consultorios, onboarding y contexto operativo

- **Estado:** diseño propuesto, no implementado. D-7 y D-11 cerradas por el usuario el 23/08/2026
- **Fecha:** 2026-08-23 · **Última revisión:** 2026-08-23 (cierre de D-7 y D-11)
- **Etapa del plan:** AKINE-02.01 (`../producto/AKINE_IMPLEMENTATION_PLAN.md`, líneas 1008–1089)
- **Fuente funcional:** M03 (`AKINE_Requerimientos_Integrados.md`, líneas 907–1313) — RF-M03-001..005, RN-M03-001..004, RNF-M03-001..008
- **Depende de:** AKINE-01.03 (`AKINE-01.03-permisos.md`). Sin el evaluador de permisos de esa etapa, 02.01 no tiene con qué autorizar
- **Vinculante:** ADR-0008 (onboarding compuesto), ADR-0009 (contexto), ADR-0004 (persistencia multi-tenant), ADR-0007 (expandir–migrar–contraer), `docs/seguridad/matriz-permisos-minima.md`

> Este documento se escribió leyendo el árbol real: `organization/domain/Consultorio.java`,
> `application/OnboardingService.java`, `application/OrganizationService.java`,
> `application/PlanGateService.java`, `application/TenantUsageCounter.java`,
> `application/AccountContextService.java`, `domain/port/ConsultorioRepositoryPort.java`,
> la migración `V3__m01_consultorio_membership_contexto.sql`, y los tests de
> `src/test/java/com/akine/diferidos/`. Cada afirmación sobre "lo que ya existe" está tomada
> de ahí, no de la memoria del plan.

### Decisiones cerradas por el usuario el 23/08/2026

| # | Decisión | Dónde impacta |
|---|---|---|
| 1 | **D-11 cerrada: el orden de bloqueo único del sistema es `subscription` → `organization`.** Se reescribe la §6.1 de `AKINE-01.03-permisos.md` y **no** el `PlanGate`: el gate ya está implementado y probado con hilos reales, y cambiar papel es más barato que cambiar código verificado. El alta de sede de 02.01 ya toma ese orden y no cambia nada | §9.4, §11.8, D-11 |
| 2 | **D-7 cerrada: la baja de la última sede activa se rechaza con `409 last-consultorio-required`** (opción A). Sin ninguna sede activa el tenant queda sin contexto seleccionable y solo se recupera con SQL manual contra la base | §4.1, §5.2, §8.3, §10, D-7 |

**Lo que cambió en 01.03 y 02.01 tiene que asumir:** el usuario cerró la D-1 de esa etapa como
opción B — entra solo asignar y revocar rol sobre memberships que ya existen, **sin flujo de
invitación y sin alta de membership por API**. Es una desviación declarada de RF-M05-001 y
RF-M05-002 cuya etapa destino candidata es **ésta**, todavía sin fijar. Ver §8.3 y §9.4 para lo
que eso cambia acá.

---

## 0. Resumen de una línea

02.01 **expande** la tabla `consultorio` que 01.01 dejó mínima —zona horaria IANA propia,
datos institucionales, estado y vigencia—, le agrega alta adicional, edición y baja lógica,
y hace que el selector de contexto que ya funciona desde 01.02 respete el estado de la sede.
**No reescribe el onboarding compuesto.**

---

## 1. Alcance

### 1.1 Entra

| # | Capacidad | Traza |
|---|---|---|
| A-1 | Alta de consultorios **adicionales** (el primero ya lo crea el onboarding compuesto) | RF-M03-001; RN-M03-001 |
| A-2 | Zona horaria IANA propia del consultorio, con backfill de las sedes ya creadas | plan 02.01 "Base de datos"; `AGENT.md` §5 ("reglas locales con zona IANA explícita") |
| A-3 | Datos institucionales y de configuración mínima editables | RF-M03-003 |
| A-4 | Estado del consultorio (`ACTIVO` / `INACTIVO`) y qué operación admite cada uno | RN-M03-002, RN-M03-003 |
| A-5 | Baja lógica con motivo obligatorio y auditoría, sin borrado físico | RF-M03-004; RN-M03-002; regla maestra 10 |
| A-6 | Listado paginado de sedes, con filtro por estado | RNF-M03-004; el `GET …/consultorios` que ya existe se **expande** con un parámetro opcional |
| A-7 | Selección de consultorio de trabajo respetando el estado de la sede | RF-M03-005; ADR-0009 |
| A-8 | Revalidación por request de que la sede del contexto sigue activa | RN-M03-003; precedente T-7 de `MembershipDirectory` |
| A-9 | Punto de extensión para "referencias activas" que bloqueen la baja, sin que `organization` conozca `scheduling` | plan 02.01 "Casos borde: baja con turnos futuros"; `AGENT.md` §4 regla 2 |
| A-10 | Consumo real de `PlanGate.createWithinLimit` para `MAX_CONSULTORIOS` — hoy **no tiene ningún consumidor en `src/main`** | RF-M01-004; `LimiteDePlanConcurrenteIT` lo dice explícitamente |
| A-11 | Frontend: wizard breve de alta, pantalla de sedes, selector de consultorio existente y tratamiento de sede inactiva | plan 02.01 "Frontend" |

### 1.2 Queda afuera, con etapa destino

| Qué | Por qué | Etapa destino |
|---|---|---|
| **Primer box del onboarding de sede** (RF-M03-002 lo nombra literalmente) | `Box`/`Espacio` es M04 y el plan lo asigna a **AKINE-02.02**. `organization` no puede crear una tabla de `resource` sin violar ownership (`AGENT.md` §4 regla 1) | AKINE-02.02 — **y ver D-1: esto deja CA-M03-002 sin cubrir por completo** |
| **Horario general del consultorio e intervalo inicial** (RF-M03-002, RN-M03-004) | Ver D-2. Es agenda: la disponibilidad profesional individual es M05/M12 y el horario general solo tiene sentido junto a los slots que lo consumen | AKINE-02.02 / F5 — decisión abierta |
| Ofertas de servicio por consultorio (RF-M03-006, RF-M03-007, RN-M03-005..007) | M27 / módulo `offering`, que no existe | F4 / M27 |
| Bloqueo real de la baja por turnos futuros | `scheduling` no existe. 02.01 deja el puerto; no puede haber implementación | F5 (M12) |
| Reactivación de un consultorio dado de baja | **Ningún RF de M03 la pide.** RF-M03-004 solo describe la baja | decisión pendiente D-4 |
| Traslado de una sede entre organizaciones | Contradice RN-M03-001 y `organization_id` es `updatable = false` en la entity | no previsto |
| Borrado físico | Regla maestra 10, RN-M03-002 | nunca |

### 1.3 Lo que 02.01 hereda y no puede romper — verificado en el árbol hoy

| Hecho verificado | Dónde | Consecuencia para 02.01 |
|---|---|---|
| El onboarding compuesto crea Organización + Consultorio + Suscripción + Membership fundador en **una** transacción, con `Propagation.REQUIRED` y jamás `REQUIRES_NEW` | `OnboardingService.provision`, `OrganizationService.provisionTenant` | 02.01 **no toca ese camino**. Agrega un camino paralelo para sedes 2..N |
| `provisionTenant` crea el primer consultorio **siempre**, incluso en el alta administrativa | `OrganizationService.provisionTenant`, comentario explícito | El caso borde "primer consultorio" ya está resuelto; 02.01 no lo re-resuelve |
| El primer consultorio **no pasa por `PlanGate`** | `provisionTenant` llama `consultorioRepository.save` directo | Ver riesgo R-2: un plan con `MAX_CONSULTORIOS = 0` se violaría en silencio |
| `POST /api/v1/auth/context` ya canjea el token acotado a Organización + Consultorio | `AccountContextService.selectContext` | RF-M03-005 está **mayormente cubierto**. 02.01 solo agrega la regla de estado |
| `isContextAuthorized` ya resuelve la sede con `findByIdAndOrganizationIdAndActiveTrue` | `AccountContextService:139` | Una sede inactiva **ya** no es seleccionable. 02.01 no puede regresionar esto |
| `consultorio` ya lleva `organization_id NOT NULL` y `uk_consultorio_org_name (organization_id, name)` | `V3`, líneas 24–40 | Todo unique nuevo sigue esa forma |
| El comentario de la tabla dice que 02.01 la **EXPANDE**, no la reemplaza | `V3` línea 40 | Cero `DROP` de la tabla, cero tabla nueva de sede |
| `Consultorio` tiene `active`, `deleted_at` y `@Version` | `Consultorio.java` | El estado no necesita columna nueva. Ver §4 |
| `TenantUsageCounter` cuenta **solo filas activas** para `MAX_CONSULTORIOS` | `TenantUsageCounter.count` | La baja libera cupo. Es una decisión ya tomada, no una a rediscutir |
| `ProvisionalAuthorizationGuard` es el único punto de autorización del módulo y 01.03 lo **vacía** conservando sus firmas | `ProvisionalAuthorizationGuard:19`; 01.03 §3.3 | 02.01 autoriza por el `PermissionGuard` de 01.03. **No inventa mecanismo propio** |

---

## 2. Modelo de datos

### 2.1 Qué le falta a `consultorio`

Estado actual (`V3`): `id`, `organization_id`, `name`, `active`, `deleted_at`, `version`,
`created_at`, `updated_at`. Nada más.

| Columna nueva | Tipo | Nulabilidad final | Por qué | Traza |
|---|---|---|---|---|
| `timezone` | `VARCHAR(64)` | `NOT NULL` | Las reglas locales (día operativo, corte de caja, agenda) no se calculan sobre UTC. `Organization` ya tiene la suya; el consultorio no | `AGENT.md` §5; plan 02.01 "Base de datos" |
| `deleted_key` | `DATETIME(6)` generada, `STORED` | `NOT NULL` | Discriminador del unique de nombre. Ver §2.4 | ADR-0004 (uniques con alcance tenant) |
| `deactivation_reason` | `VARCHAR(280)` | `NULL` | RF-M03-004 exige preservar la información previa; la auditoría exige motivo | RF-M03-004; matriz §7 |
| `legal_name` | `VARCHAR(200)` | `NULL` | "información institucional" de M03 §1 | **D-5: la lista exacta de campos no está en ningún RF** |
| `tax_id` | `VARCHAR(32)` | `NULL` | ídem | D-5 |
| `address_line` | `VARCHAR(240)` | `NULL` | ídem | D-5 |
| `phone` | `VARCHAR(40)` | `NULL` | ídem | D-5 |
| `contact_email` | `VARCHAR(160)` | `NULL` | ídem | D-5 |

> **Lo que NO se agrega, y por qué.** No hay columna `estado`. El estado del consultorio se
> **deriva** de `active` + `deleted_at`, exactamente como el estado operativo de la organización
> se deriva de la suscripción y nunca se persiste (`OrganizationService.operationalStatus`:
> *"valor calculado, nunca una columna"*). Una columna `estado` junto a un booleano `active`
> habilita la contradicción "active=1, estado=INACTIVO" que nadie sabe resolver, y obliga a un
> backfill y a un invariante extra que ningún RF pide. Si el usuario decide que hace falta un
> tercer estado —ver **D-3**— entonces sí hay que expandir a columna, y ese es el momento.

> **Tampoco se agregan `valid_from` / `valid_until`.** La vigencia del consultorio tiene un solo
> evento de cierre y ya está modelado: `deleted_at`. `valid_from` sería un duplicado de
> `created_at`. Una vigencia futura programada ("esta sede cierra el 1/12") no la pide ningún RF
> de M03 → **D-4**.

### 2.2 Migraciones previstas — expandir, migrar, contraer

ADR-0007 es vinculante: nunca `NOT NULL` sin default sobre una tabla con datos, nunca
`RENAME COLUMN`, nunca `DROP` en la misma release que introduce el reemplazo.

Numeración: el árbol tiene hoy hasta `V10__m01_membership_multiples_por_organizacion.sql`
—01.03 está en curso y reservó hasta `V15`—. 02.01 toma los primeros números libres al cerrar
01.03. Acá se los llama **C1/C2/C3** para no fijar un número que va a chocar.

| Paso | Migración | Contenido | Release |
|---|---|---|---|
| **Expandir** | `C1__m03_consultorio_expandir.sql` | `ALTER TABLE consultorio ADD COLUMN timezone VARCHAR(64) NULL`, más `deactivation_reason` y los cinco campos institucionales, todos `NULL`. Índice nuevo. **Ningún `NOT NULL`, ningún unique todavía** | N |
| **Migrar** | `C2__m03_consultorio_backfill_timezone.sql` | `UPDATE consultorio c JOIN organization o ON o.id = c.organization_id SET c.timezone = o.timezone WHERE c.timezone IS NULL`. Es exacto: hasta hoy toda sede se creó bajo la zona de su organización y no había otra fuente. Verificación en la misma migración: si queda alguna fila con `timezone IS NULL`, falla | N |
| **Contraer** | `C3__m03_consultorio_contraer.sql` | `MODIFY timezone VARCHAR(64) NOT NULL`, columna generada `deleted_key`, baja del unique viejo y alta de `uk_consultorio_org_name_vigente (organization_id, name, deleted_key)`. Comentario que referencia a C1 y C2, como pide ADR-0007 | **N+1** |

El código de la release N lee `timezone` con fallback a la zona de la organización **solo
durante la ventana de transición**, y ese fallback se borra en N+1. Escrito como TODO con etapa
de retiro, no como comportamiento permanente: un fallback permanente hace que editar la zona de
la organización mueva silenciosamente el día operativo histórico de una sede (riesgo R-5).

### 2.3 Reversibilidad práctica

C1 y C2 son reversibles: columnas nuevas nulables, y el backfill es idempotente y repetible.
C3 no lo es una vez que alguien dé de baja una sede y cree otra con el mismo nombre: al volver
al unique de dos columnas, esas dos filas colisionan. Es el mismo tipo de irreversibilidad que
01.03 anotó para su `V11` (su D-10). Se anota, no se resuelve.

### 2.4 Uniques e índices — y la trampa de los NULL en MySQL

**El problema.** `uk_consultorio_org_name (organization_id, name)` cubre todas las filas,
activas e inactivas. Con baja lógica obligatoria, dar de baja "Sede Centro" y volver a crear una
"Sede Centro" es imposible para siempre. Eso no lo pide ningún RF y es una molestia real.

**La trampa que hay que evitar.** El reflejo es escribir:

```sql
UNIQUE (organization_id, name, deleted_at)   -- ROTO
```

En MySQL/InnoDB, **una restricción UNIQUE no considera iguales a dos NULL**. Las filas activas
tienen todas `deleted_at IS NULL`, así que dos sedes activas con el mismo nombre **no
colisionan** y el unique deja de proteger justo el caso que importa. Es la inversión exacta de
lo que se buscaba: protege los históricos y desprotege lo vigente.

**La solución.** Una columna generada que nunca sea NULL:

```sql
ALTER TABLE consultorio
  ADD COLUMN deleted_key DATETIME(6)
    GENERATED ALWAYS AS (IFNULL(deleted_at, '1970-01-01 00:00:00.000000')) STORED NOT NULL;

ALTER TABLE consultorio DROP INDEX uk_consultorio_org_name;
ALTER TABLE consultorio
  ADD CONSTRAINT uk_consultorio_org_name_vigente
  UNIQUE (organization_id, name, deleted_key);
```

- Dos sedes **activas** con el mismo nombre comparten `deleted_key = 1970-01-01` → colisionan → 409.
- Una activa y una dada de baja no colisionan → el nombre se puede reusar.
- Dos bajas del mismo nombre colisionan solo si ocurrieron en el **mismo microsegundo**.
  `DATETIME(6)` lo hace prácticamente imposible; si ocurriera, el desenlace es un 409 en una
  baja, recuperable reintentando.
- **No se usa `id` como discriminador:** MySQL prohíbe que una columna generada referencie una
  columna `AUTO_INCREMENT`.
- **No se usa un índice único parcial:** MySQL 8.4 no tiene `UNIQUE ... WHERE` como PostgreSQL.
  La columna generada es el equivalente disponible.

**Índices.**

| Índice | Estado | Para qué |
|---|---|---|
| `ix_consultorio_org_active (organization_id, active)` | **ya existe** (V3) | Conteo de `MAX_CONSULTORIOS` y sedes activas del selector |
| `ix_consultorio_org_active_name (organization_id, active, name)` | nuevo | Listado paginado ordenado por nombre (RNF-M03-004). Cubre al anterior como prefijo; retirarlo es opcional y hay que medir antes (`AGENT.md` §10.6) |
| `uk_consultorio_org_name_vigente` | nuevo, reemplaza al anterior | §2.4 |

**Toda consulta filtra por `organization_id`.** `ConsultorioRepositoryPort` ya lo documenta y lo
hace: *"nunca se resuelve una sede del contexto con findById pelado"*. Los métodos nuevos
—`findByIdAndOrganizationId` (sin filtro de `active`, para poder leer una sede inactiva) y
`findAllByOrganizationId` paginado— **conservan esa forma**.

---

## 3. Zona horaria

### 3.1 Dónde vive

**En el consultorio, no en la organización.** La organización conserva su `timezone` y pasa a
tener un rol distinto y explícito: es el **valor por defecto que se propone al crear una sede
nueva**, no la zona efectiva de nada operativo.

Motivos:

1. `AGENT.md` §5 exige instantes UTC en la base y "zona IANA explícita" para las reglas locales.
   La regla local relevante —día operativo, agenda, corte de caja, vencimiento de turno— es del
   **lugar físico donde se atiende**, y ese lugar es el consultorio.
2. El plan 02.01 nombra "multi-sede" como caso borde. Una organización con sedes en dos husos
   comparte tenant y no comparte reloj de pared. Hoy Argentina tiene una sola zona, pero el
   modelo no puede depender de eso: el sistema es un SaaS y el costo de asumirlo mal es
   re-fechar historia clínica y económica.
3. El plan lo pide literalmente en "Contexto que deja disponible para la etapa siguiente":
   *"Consultorio activo, timezone y configuración estable"*.

### 3.2 Qué pasa con los datos ya creados

Ninguna sede existente tiene zona. El backfill (C2) copia `organization.timezone`. Es exacto, no
una aproximación: hasta hoy `provisionTenant` es el único camino que crea consultorios, y usa la
zona de la organización (o `America/Argentina/Cordoba` por defecto,
`OrganizationService.TIMEZONE_POR_DEFECTO`). **No existe ninguna sede cuya zona real difiera de
la de su tenant**, porque no existía forma de expresar una distinta.

### 3.3 Validación

- Se acepta únicamente un identificador de la **base IANA** que resuelva con `ZoneId.of(...)` y
  esté en `ZoneId.getAvailableZoneIds()`.
- Se **rechazan**: offsets fijos (`-03:00`, `UTC-3`, `Z`), abreviaturas de tres letras (`ART`,
  `EST`) y `GMT+X`. Un offset fijo no conoce el horario de verano; el día que un país lo
  reinstaure, toda la agenda de esa sede se corre una hora sin que nadie lo note.
- **La zona es editable** (RF-M03-003: "modificar datos, horarios y parámetros") y el cambio se
  audita con valor anterior y nuevo. Lo que el cambio **no** hace es re-interpretar el pasado:
  los instantes ya guardados son UTC y no se tocan; cambia cómo se proyectan de acá en adelante.
  La UI tiene que decirlo con esas palabras (§7.2).

---

## 4. Estados del consultorio

Dos estados, derivados, sin columna nueva (§2.1).

| Estado | Persistencia | Cómo se llega |
|---|---|---|
| `ACTIVO` | `active = 1`, `deleted_at IS NULL` | Alta (onboarding compuesto o alta adicional) |
| `INACTIVO` | `active = 0`, `deleted_at = <instante UTC>` | `Consultorio.deactivate(occurredAt)` — el método **ya existe** |

### 4.1 Qué admite cada estado

| Operación | `ACTIVO` | `INACTIVO` | Traza |
|---|---|---|---|
| Aparecer en `GET /me/contexts` | sí | **no** | ADR-0009; `AccountContextService` ya filtra por activas |
| Ser seleccionado como contexto (`POST /auth/context`) | sí | **no → 404** | RF-M03-005; RN-M03-003 |
| Recibir operaciones de negocio nuevas (turnos, sesiones, cobros) | sí | **no → 409** | **RN-M03-003** |
| Ser leído por id | sí | **sí** | RF-M03-004: "los históricos previos permanecen disponibles" |
| Aparecer en el listado | sí | sí, con filtro explícito | RNF-M03-004 |
| Ser editado (nombre, datos, zona) | sí | **no → 409** | RF-M03-003, flujos alternativos: "si el estado actual no admite la operación, devolver conflicto funcional" |
| Contar contra `MAX_CONSULTORIOS` | sí | **no** | `TenantUsageCounter`: solo activas. La baja libera cupo |
| Ser dado de baja | sí, **salvo que sea la última activa del tenant → 409 `last-consultorio-required`** (D-7, §9.6-bis) | **no → 409** `consultorio-already-inactive` | RF-M03-004, flujos alternativos; D-7 |
| Conservar su historia (auditoría, turnos pasados, sesiones) | sí | **sí, intacta** | RN-M03-002; regla maestra 10 |

**La regla en una línea:** un consultorio inactivo **no origina hechos nuevos**, pero **responde
a toda consulta sobre hechos viejos**. Un endpoint que devuelva 404 sobre una sede inactiva
estaría borrando historia por la puerta de atrás.

### 4.2 El usuario que está parado adentro cuando la dan de baja

Es el caso que más rompe. Hoy el token acotado al contexto vive minutos y el
`TenantContextFilter` revalida la **membership** por request, sin caché (T-7), pero **no revalida
el estado de la sede**. Sin cambio, un usuario opera sobre una sede dada de baja hasta que vence
su token.

02.01 extiende la revalidación por request al consultorio del contexto, con el mismo patrón de
puerto invertido de `MembershipDirectory`: `platform` declara qué necesita, `organization` lo
implementa. Si la sede ya no está activa, el request entra al camino existente de "contexto no
resoluble" —**404** sobre ruta de negocio, nunca 401— y el frontend limpia el contexto y lleva al
selector con explicación (mecánica ya diseñada en 01.03 §4.3 y §9).

**Costo:** un seek indexado más por request, sobre `ix_consultorio_org_active`. 01.03 D-10
advierte que ya hay dos (membership + `platform_role`) y que *"hay que medirlo antes de agregar
el tercero"*. **Éste es el tercero.** Se anota como riesgo R-3 y como medición obligatoria antes
de cerrar la etapa; la alternativa —no revalidar— es peor: RN-M03-003 dejaría de cumplirse
durante todo el TTL del token.

---

## 5. Contrato API

**Todos los cambios son aditivos.** 01.03 lleva el contrato de `0.3.0` a `0.4.0`; 02.01 lo lleva
a **`0.5.0` (minor)**. Ningún endpoint publicado cambia de firma, de esquema de respuesta ni de
significado.

Dos matices que hay que mirar de frente antes de afirmar "aditivo":

1. `GET /api/v1/organizations/{orgId}/consultorios` gana un parámetro de query opcional. El
   **default conserva el comportamiento actual** (solo activas), así que un cliente viejo no ve
   ninguna diferencia. Aditivo real.
2. `POST /api/v1/auth/context` no cambia. Una sede inactiva ya falla ahí hoy
   (`findByIdAndOrganizationIdAndActiveTrue`). No hay cambio observable.

### 5.1 Endpoints nuevos

| Método y ruta | RF | Permiso | Notas |
|---|---|---|---|
| `POST /api/v1/organizations/{orgId}/consultorios` | RF-M03-001 | `consultorio:manage` alcance **organización** | `Idempotency-Key` obligatoria (§34). Pasa por `PlanGate.createWithinLimit(MAX_CONSULTORIOS)` |
| `GET /api/v1/organizations/{orgId}/consultorios/{id}` | RF-M03-001, RF-M03-003 | `consultorio:manage`, o membership vigente en esa sede (ver D-10) | Devuelve también sedes **inactivas** |
| `PATCH /api/v1/organizations/{orgId}/consultorios/{id}` | RF-M03-003 | `consultorio:manage` | `version` obligatoria en el cuerpo, mismo patrón que `OrganizationController.update`. 409 si quedó vieja |
| `POST /api/v1/organizations/{orgId}/consultorios/{id}/deactivate` | RF-M03-004 | `consultorio:manage` alcance **organización** (ver D-6) | `reason` obligatorio. Baja lógica. **No borra** |
| `GET /api/v1/organizations/{orgId}/consultorios` *(expandido)* | RF-M03-001 | el que ya tiene | Nuevo query param `estado` (`ACTIVO` \| `INACTIVO` \| `TODOS`), default `ACTIVO` |
| `POST /api/v1/organizations/{orgId}/consultorios/{id}/onboarding` | RF-M03-002 | `consultorio:manage` | **Solo si D-1 se resuelve como A.** Ver D-1 y D-2 |

RF-M03-005 **no genera endpoint nuevo**: lo cumple `POST /api/v1/auth/context`, que existe desde
01.02. Lo único que 02.01 le agrega es la regla de estado, que ya está implementada. Decirlo por
escrito evita que alguien "implemente RF-M03-005" duplicando el canje de token.

### 5.2 Códigos de respuesta

Se reusa la tabla de 01.03 §8 tal cual. Lo específico de 02.01:

| Situación | Código | `type` | Por qué |
|---|---|---|---|
| Sede de otra organización | **404** | `.../not-found` | Un 403 confirma que existe: se enumeran las sedes del SaaS probando ids. Regla heredada de 01.01 |
| Actor sin `consultorio:manage` sobre una sede **de su propia organización** | **403** | `.../forbidden` | El 404 acá no protege nada y le miente al usuario |
| Nombre repetido entre sedes vigentes del tenant | **409** | `.../consultorio-name-taken` | Traducción de `uk_consultorio_org_name_vigente` |
| Tope `MAX_CONSULTORIOS` alcanzado | **409** | `.../plan-limit-exceeded` | `PlanLimitExceededException`, ya mapeada por `OrganizationProblemHandler` |
| Suscripción `SUSPENDIDA` + alta de sede | **409** | `.../subscription-suspended` | Camino existente del `PlanGate` |
| Baja de una sede ya inactiva | **409** | `.../consultorio-already-inactive` | "estado que no admite la operación" (RF-M03-004) |
| Edición de una sede inactiva | **409** | `.../consultorio-inactive` | Ídem RF-M03-003 |
| Baja de la **última** sede activa del tenant | **409** | `.../last-consultorio-required` | **D-7 cerrada (opción A).** Ver §8.3 y §9.6-bis |
| Baja con referencias activas (turnos futuros) | **409** | `.../consultorio-has-active-references` | Reservado; sin implementación posible hasta F5. Ver §8.4 y D-8 |
| Zona horaria inválida | **400** | `.../validation-error` | §3.3 |
| Versión desactualizada en el `PATCH` | **409** | `.../concurrent-modification` | `OptimisticLockingFailureException` |
| `Idempotency-Key` repetida con payload distinto | **409** | `.../idempotency-key-conflict` | Mismo `type` que ya usa `OrganizationProblemHandler` |

### 5.3 Idempotencia del alta

RF-M03-001 exige explícitamente que los reintentos no dupliquen efectos (CA-M03-001-05). Dos
mecanismos, y hacen falta los dos:

1. **`uk_consultorio_org_name_vigente`** cierra la carrera de verdad. Dos altas simultáneas con
   el mismo nombre: una entra, la otra recibe `DataIntegrityViolationException` → 409.
2. **`Idempotency-Key`** cubre el reintento por timeout de red, donde el cliente no sabe si la
   primera llegó. Sin ella, un reintento después de un 504 con nombre distinto crearía dos sedes.
   El precedente del repo es `organization_onboarding` con `request_hash`; la deduplicación por
   contenido del registro es justamente el escenario diferido 7b que 01.03 cierra. **02.01 reusa
   el mismo patrón, no inventa otro.** Dónde vive el registro es **D-9**.

---

## 6. Permisos

**Sale entero de `docs/seguridad/matriz-permisos-minima.md` y del evaluador que construye 01.03.
02.01 no define ningún mecanismo de autorización propio.**

### 6.1 El permiso

`consultorio:manage` — matriz §5, fase **"F1 (mínimo) / F2"**. La §1.2 del diseño de 01.03 dice
literalmente: *"01.03 define el **permiso**; los endpoints que lo consumen son de 02.01"*. Esta
etapa es el consumidor previsto.

Asignación base (matriz §6, vinculante):

| Rol | `consultorio:manage` | Qué puede hacer en 02.01 |
|---|---|---|
| `PLATFORM_ADMIN` | Global | Todo, pero **solo con acceso de soporte vigente** (matriz §3, valor "Soporte"), y cada operación auditada con `SUPPORT_ACCESS_USED` |
| `ORG_ADMIN` | Org | Crear, editar y dar de baja cualquier sede del tenant |
| `CONSULTORIO_ADMIN` | Consultorio | **Editar su propia sede.** No puede crear una sede nueva; la baja de la propia es D-6 |
| `PROFESIONAL`, `ADMINISTRATIVO`, `PACIENTE` | — | Nada. Solo leen el listado como insumo del selector (D-10) |

### 6.2 Por qué `CONSULTORIO_ADMIN` no crea

No es una restricción inventada: cae del propio algoritmo del evaluador (01.03 §3.4):

```
permisos(...) = base(rol) ∪ grants ∩ alcance(membership)
```

El alcance de un `CONSULTORIO_ADMIN` es **un** consultorio. Un alta no tiene consultorio objetivo
—la sede todavía no existe—, así que la intersección con el alcance es vacía y el evaluador
devuelve `OUT_OF_SCOPE`. No hay caso especial que escribir: es la fórmula operando.

La **baja de la propia sede** sí requiere una decisión explícita, porque formalmente cae dentro
del alcance: el actor pide la operación sobre el único consultorio que puede tocar. Es la misma
forma del `self-revoke` que 01.03 §6.2 resuelve rechazando —la operación destruye el alcance del
propio actor y él no puede deshacerla—. **Se propone rechazarla con 403**, y queda como **D-6**
porque ningún RF la resuelve.

### 6.3 Lectura del listado — dependencia abierta de 01.03

`GET .../consultorios` hoy usa `authorizationGuard.requireMember`: **cualquier miembro vigente**
lo lee. Tiene que seguir siendo así, porque es el insumo del selector de contexto: si se
restringe a `consultorio:manage`, un `PROFESIONAL` deja de poder elegir sede y el login queda sin
salida.

Es **la misma tensión que 01.03 dejó abierta en su D-9** para `GET /organizations/{orgId}`
(`requireMember` vs. `tenant:read` de la matriz). La matriz no tiene un `consultorio:read`; el
catálogo §5 solo lista `consultorio:manage`. **02.01 no puede resolverlo solo.** Ver **D-10**.

### 6.4 Cómo se invoca

Igual que 01.03 lo dejó: `organization.spi.PermissionGuard.requirePermission(...)` con
`AuthorizationRequest(accountId, "consultorio:manage", organizationId, consultorioId,
targetAccountId=null, at)`. La evaluación corre **dentro de la transacción de negocio**, no en el
filtro (01.03 §4.2 y §6.3). El mapeo de `DenialKind` a HTTP ya está centralizado: 02.01 no decide
códigos por su cuenta.

---

## 7. Frontend — `appKine-web`

### 7.1 Qué se reusa

| Qué | Dónde | Estado |
|---|---|---|
| `features/organization/` como feature lazy | `organization.routes.ts` | Existe. Se le agregan rutas hijas |
| `organization.css` compartido por las páginas del feature | `features/organization/organization.css` | Existe. Lo usan las tres páginas actuales |
| Patrón de estado discriminado (`cargando` \| `eligiendo` \| `vacio` \| `error`) | `context-selector-page.ts` | Existe y es la convención del repo (ADR-0005). El wizard lo copia, no inventa otro |
| `TenantContextStore` + `contextEpoch` para invalidar datos al cambiar de contexto | `core/services/tenant-context.store.ts` | Existe. Las pantallas nuevas dependen de `contextEpoch`, **no** del id |
| `SessionService.cargarContextos()` y el canje contra `POST /auth/context` | `core/services/session.service.ts` | Existe. **RF-M03-005 ya está implementado acá.** No se reescribe |
| Cliente generado | `src/app/api/generated/` | Se regenera desde el contrato `0.5.0`. `npm run api:check` es el gate |
| `permissions.store.ts`, `permission.guard.ts`, directiva `*akinePermiso` | `core/`, `shared/directives/` | **Los crea 01.03.** 02.01 los consume. Hoy `shared/directives/` y `shared/components/` están vacíos |

### 7.2 Qué es nuevo

| Qué | Dónde | Notas |
|---|---|---|
| Listado de sedes | `features/organization/pages/consultorios/` | Paginado, filtro por estado, distintivo de sede inactiva. Estados: carga, vacío, error, permiso insuficiente, éxito |
| **Wizard breve de alta** | `pages/consultorios/alta/` | Dos pasos, no más: (1) nombre + zona horaria; (2) datos institucionales, **todos opcionales y salteables**. El plan dice "wizard breve" y RNF-M03-005 pide "interacciones compactas". Un wizard de cinco pasos para crear una sede es lo que ADR-0008 descartó para el registro |
| Selector de zona horaria | `pages/consultorios/` | Se alimenta de `Intl.supportedValuesOf('timeZone')`, con la zona de la organización preseleccionada y buscador. Sin lista fija en código: el tz database cambia |
| Edición de sede | `pages/consultorios/detalle/` | Manda `version`; maneja el 409 de `concurrent-modification` con "alguien más la editó, recargá" |
| Diálogo de baja | `pages/consultorios/` | **Motivo obligatorio**, confirmación explícita, y los 409 de `last-consultorio-required` / `consultorio-has-active-references` mostrados con su mensaje real (`AGENT.md` §8: nunca un genérico) |
| Tratamiento de **sede inactiva** | transversal | Tres lugares: (a) el selector no la lista —ya lo hace el backend—; (b) el detalle la muestra en modo lectura con aviso y sin acciones de mutación; (c) si la sede del contexto activo se da de baja mientras el usuario trabaja, el 404 sobre ruta de negocio con contexto activo limpia el contexto y lleva al selector con explicación, mecánica que 01.03 §9 ya le asigna a `error.interceptor.ts`. **02.01 no agrega un cuarto camino** |
| Aviso al cambiar la zona horaria | formulario de edición | Texto explícito: "los turnos ya registrados no se mueven; cambia cómo se muestran de acá en adelante" |

### 7.3 Lo que el frontend NO hace

Ocultar el botón "crear sede" a un `CONSULTORIO_ADMIN` es UX, no seguridad (`AGENT.md` §6).
Preferir **deshabilitar con explicación** a ocultar, siguiendo la regla que 01.03 §9 ya fijó para
la directiva: una acción que desaparece deja al usuario sin saber a quién pedirle acceso.

---

## 8. Casos borde del plan, uno por uno

### 8.1 Fallo parcial de onboarding

**Ya resuelto por ADR-0008 y verificado en `RollbackDeOnboardingIT`.** `provision` corre con
`Propagation.REQUIRED` dentro de la transacción que abrió `identity`, y el JavaDoc del método
explica por qué jamás `REQUIRES_NEW`: con transacción propia, un fallo posterior en `identity`
dejaría organización, consultorio y membership apuntando a un `account_id` inexistente.

**Qué agrega 02.01:** nada a ese camino. Lo que sí hace es garantizar la misma propiedad en el
camino nuevo: el alta de una sede adicional —consultorio + auditoría + registro de idempotencia,
más los defaults si D-1/D-2 los incorporan— vive **dentro del `Supplier` que recibe
`PlanGate.createWithinLimit`**, o sea dentro de la transacción que el gate abre. Un fallo en
cualquier paso revierte todo, incluido el consumo del cupo. No hay estado intermedio observable.

Y una advertencia concreta para el implementador: el bloque `catch` de la clave duplicada
**no puede volver a tocar JPA** (§9.3). Es la lección 3 y ya costó un 500 en 01.02.

### 8.2 Primer consultorio

**No es un caso de 02.01.** `provisionTenant` lo crea siempre, incluso en el alta administrativa,
con el motivo escrito en su JavaDoc: el contexto de trabajo es Organización + Consultorio
(ADR-0009), y un tenant sin sede no ofrece ningún contexto seleccionable. 02.01 hereda el hecho
de que **toda organización tiene al menos una sede desde el instante 0** y lo convierte en
invariante explícito (§8.3, D-7).

Lo que sí hay que hacer: el primer consultorio nace **sin zona horaria** hasta C2 —lo arregla el
backfill— y **sin pasar por el `PlanGate`** —riesgo R-2—.

### 8.3 Multi-sede

| Sub-caso | Comportamiento |
|---|---|
| Membership con `consultorio_id = NULL` (el fundador) | Alcanza toda la organización. Una sede nueva **aparece inmediatamente** en su `GET /me/contexts`: `AccountContextService` itera todas las sedes activas del tenant para ese caso. No hay que hacer nada |
| Membership acotada a una sede | **No** ve la sede nueva. Sumarla sería un alta de membership, y **con la D-1 de 01.03 cerrada como B eso no existe todavía por API**: hasta que RF-M05-001/002 lleguen, el único que ve una sede nueva es quien tiene membership de alcance organización. 02.01 no crea memberships y no puede taparlo |
| Nombres de sede | Únicos por tenant entre las vigentes (§2.4). Dos tenants distintos pueden tener "Sede Centro" |
| Zonas horarias distintas por sede | Soportado por diseño (§3.1) |
| Última sede activa | **Se rechaza la baja con 409 `last-consultorio-required`** — D-7 cerrada por el usuario el 23/08/2026. Sin ninguna sede activa, `GET /me/contexts` devuelve vacío para todos, nadie puede canjear contexto y el tenant queda operativamente muerto, y solo se recupera con SQL manual contra la base: la misma clase de daño irreversible que el invariante "último admin" de 01.03 §6.1. **Ningún RF lo dice**: es una restricción agregada al comportamiento de RF-M03-004 y va declarada como tal en el registro de cierre. Su carrera está en §9.6-bis |

### 8.4 Baja con turnos futuros

**El módulo `scheduling` no existe** (llega en F5, M12). `organization` no puede consultar turnos
sin violar la regla 1 de `AGENT.md` §4. Y `organization → scheduling` sería, además, una flecha
hacia un módulo consumidor: ciclo garantizado.

**Lo que 02.01 hace: deja el puerto, invertido, y ninguna implementación.**

```java
package com.akine.organization.spi;

/** Un modulo que registra hechos sobre una sede declara aca si algo bloquea su baja. */
public interface ConsultorioDeactivationProbe {
    ActiveReferences activeReferencesOn(long organizationId, long consultorioId, Instant at);
}
```

- Lo **declara** `organization`, lo **implementará** `scheduling` en F5. Dirección de la
  dependencia: `scheduling → organization.spi`. Permitida, unidireccional, pasa ArchUnit. Es el
  mismo patrón ya probado de `platform.spi.tenant.MembershipDirectory` implementado por
  `organization.infrastructure.tenant`.
- En F1 la lista de implementaciones es **vacía**, así que la baja siempre procede. Eso no es
  simular funcionalidad: es declarar la forma para que agregarla en F5 sea sumar una clase, no
  rediseñar la baja.
- El código `409 consultorio-has-active-references` se reserva en el contrato desde ya, para que
  aparecer en F5 no sea un cambio de comportamiento sorpresivo para el frontend.

**Qué debe pasar cuando haya turnos futuros: D-8.** RN-M03-003 solo dice que un consultorio
inactivo **no recibe turnos nuevos**; no dice nada de los ya reservados. Y ADR-0011 (DP-04)
prohíbe una cancelación en cascada sin confirmación explícita, motivo y auditoría por turno.

---

## 9. Concurrencia

Sección escrita después de leer `src/test/java/com/akine/diferidos/`. Los tres bugs de esa
carpeta no son anécdotas: dos de ellos tocan **exactamente** el camino que 02.01 estrena.

### 9.1 Las tres lecciones, y dónde pega cada una

| # | Lección | Evidencia en el árbol | Dónde le pega a 02.01 |
|---|---|---|---|
| 1 | **Un lock serializa el acceso, no la visibilidad.** En `REPEATABLE READ`, una lectura consistente previa fija el snapshot y el conteo posterior al `FOR UPDATE` lee de antes del commit del competidor | `LimiteDePlanConcurrenteIT`; `PlanGateService.verificarIsolation` | **De lleno.** El alta de sede es un conteo contra `MAX_CONSULTORIOS`: el mismo protocolo que ya falló con memberships |
| 2 | **Un `INSERT` hijo toma lock compartido sobre el padre por la FK y al commit escala a exclusivo** → deadlock entre transacciones simétricas | `TransicionConcurrenteDeSuscripcionIT`, líneas 29–35 | `INSERT INTO consultorio` toma S sobre la fila de `organization` por `fk_consultorio_organization`. Ver §9.4, el hallazgo más serio de este documento |
| 3 | **Una sesión JPA reusada después de un flush fallido tira `AssertionFailure` → 500** | `OnboardingService`, bloque `catch (DataIntegrityViolationException)`; `IdempotenciaYUniquesIT` | El alta con nombre repetido choca `uk_consultorio_org_name_vigente`. El `catch` **no puede volver a tocar JPA** |

### 9.2 Alta de sede contra el límite de plan — el protocolo, textual

`PlanGate.createWithinLimit` **ya resuelve esto** y hoy no tiene ningún consumidor en `src/main`
—`LimiteDePlanConcurrenteIT` lo dice: *"la primera alta de consultorio llega en 02.01"*—. 02.01
es el primero. Dos restricciones del contrato que no son negociables y que un implementador
desprevenido va a romper:

1. **`createWithinLimit` se niega a unirse a una transacción existente** y lanza
   `IllegalStateException` si hay una activa. Motivo escrito en el código: al unirse, Spring
   **ignora la isolation declarada** y el alta correría en `REPEATABLE READ`, reintroduciendo el
   bug 1 en silencio.
   → **Consecuencia dura: el método de `ConsultorioService` que crea una sede NO lleva
   `@Transactional`.** La transacción la abre el gate. Quien "arregle" eso agregando la anotación
   rompe el límite de plan sin que ningún test unitario se entere.
2. `evaluateCreationAndLock` es `Propagation.MANDATORY` y verifica la isolation en runtime.
   Bloquear, **después** contar, **después** decidir. El orden es la regla, no estilo.

Forma resultante:

```java
// SIN @Transactional. La transaccion la abre el gate, con READ COMMITTED.
public ConsultorioView create(long organizationId, ConsultorioAltaCommand cmd, Actor actor) {
    return planGate.createWithinLimit(
        organizationId,
        LimitCode.MAX_CONSULTORIOS,
        () -> usageCounter.count(LimitCode.MAX_CONSULTORIOS, organizationId),
        () -> {
            permissionGuard.requirePermission(...);      // dentro de la transaccion (01.03 6.3)
            return persistirConsultorioYAuditar(organizationId, cmd, actor);
        });
}
```

La evaluación de permiso va **dentro** del supplier, no antes: fuera de la transacción, una
revocación concurrente se colaría entre la autorización y el commit (01.03 §6.3).

### 9.3 Nombre duplicado — el camino que no vuelve a tocar JPA

Dos altas simultáneas con el mismo nombre en el mismo tenant: `uk_consultorio_org_name_vigente`
deja pasar una. La otra: `saveAndFlush` → `DataIntegrityViolationException` →
`ConsultorioNameTakenException` → **409**.

**Sin releer, sin `save`, sin `merge` después del flush fallido.** `OnboardingService` documenta
en veinte líneas por qué: lo que salía de ahí era un `AssertionFailure` o un "Transaction marked
as rollbackOnly", o sea un 500 donde el contrato promete un conflicto. Y la traducción se hace
**donde se sabe qué unique chocó** —no en un `catch` genérico arriba, que respondería mal los dos
casos, tal como ya pasó con `uk_organization_slug` y `uk_onboarding_key`—.

### 9.4 Orden de bloqueo entre 02.01 y 01.03 — **cerrado (D-11, opción A)**

Éste era el problema serio del documento y no se podía cerrar dentro de 02.01. Lo cerró el
usuario el 23/08/2026.

**El orden de bloqueo único del sistema es `subscription` → `organization`.**

Lo que estaba en juego:

| Transacción | Locks, en orden |
|---|---|
| **Alta de sede (02.01)** | 1) `X` sobre `subscription` del tenant (`findByOrganizationIdForUpdate`, dentro del gate) → 2) `S` sobre la fila de `organization`, implícito, por `fk_consultorio_organization` al insertar el consultorio |
| **Mutación de membership (01.03 §6.1, versión anterior)** | 1) `X` sobre la fila de `organization` como **primera sentencia** → 2) si la mutación era un alta, `X` sobre `subscription` por `MAX_MIEMBROS_ACTIVOS` |

Los órdenes eran **inversos**, y dos transacciones simétricas en la misma organización se
bloqueaban cruzado: exactamente la forma del bug 2 de `src/test/java/com/akine/diferidos/`, con
otros nombres de tabla y repartido entre dos etapas, que es donde nadie lo busca. InnoDB mata a
una y devuelve `CannotAcquireLockException`, que el handler genérico mapea hoy a **500**.

**Qué se decidió y por qué.** Se reescribe **01.03 §6.1**, que pasa a tomar
`SELECT id FROM subscription WHERE organization_id = ? FOR UPDATE` como primera sentencia. **No
se toca el `PlanGate`:** ya está implementado, probado con hilos reales contra MySQL real
(`LimiteDePlanConcurrenteIT`) y su negativa a unirse a una transacción existente es deliberada
—al unirse heredaría la isolation del llamador y el límite se violaría en silencio—. Cambiar
papel es más barato que cambiar código verificado. La suscripción serializa por tenant igual de
bien que la organización: hay exactamente una fila por tenant, o sea el mismo grano y la misma
contención.

Descartadas: **B** (orden `organization → subscription`, con una variante del gate que acepte un
lock ya tomado), porque toca el contrato del gate y cualquier error ahí reabre el bug del límite
de plan; **C** (aceptar el deadlock ocasional y mapear `CannotAcquireLockException` a 409),
porque convierte en normal un error que hoy es 500 y le traslada al usuario un reintento que el
diseño puede evitar.

**Qué cambia en 02.01: nada.** El alta de sede ya tomaba ese orden y es la única que no podía
cambiarlo. Lo que sí queda es la **obligación de conservarlo**: cualquier transacción futura de
cualquier módulo que necesite las dos filas las toma en ese orden, y el test K-3 es el que lo
vigila.

**Y una consecuencia del cierre de la D-1 de 01.03 que hay que anotar acá.** El escenario original
de K-3 era "un alta de sede contra una aceptación de invitación". Con la invitación fuera de
01.03, **el alta de membership no existe en ninguna etapa hasta que RF-M05-001/002 vuelvan**, así
que la transacción simétrica de la que había que defenderse todavía no se puede escribir. K-3 se
reformula (§10.3) para correr contra la mutación de membership que sí existe —cambio de rol o
revocación—, que es la que toma el lock y por lo tanto la que prueba el orden. El día que vuelva
el alta, K-3 recupera su forma original **y además pasa por el gate**, que toma la suscripción él
mismo: el servicio no debe tomarla antes ni envolver al gate en una transacción propia.

### 9.5 Baja concurrente y cupo liberado

Una baja concurrente con un alta hace que el conteo del alta pueda ver una fila que ya se dio de
baja. El error es **conservador**: se rechaza un alta que en realidad entraba (falso 409), nunca
se acepta una de más. Aceptable y explícito. No se agrega serialización para esto: el costo sería
contención en el camino caliente para evitar un reintento del usuario.

### 9.6 Doble baja de la misma sede

Dos clics, dos pestañas. `@Version` sobre `Consultorio` ya existe: el perdedor recibe
`ObjectOptimisticLockingFailureException` → **409 `concurrent-modification`**. Si la segunda llega
después del commit de la primera, la precondición de estado la rechaza con
**409 `consultorio-already-inactive`**. En ningún caso se escriben dos eventos de auditoría de
baja para la misma sede.

### 9.6-bis Baja de la última sede activa — el invariante de D-7 y su carrera

D-7 quedó cerrada como opción A: **la baja de la última sede activa del tenant se rechaza con
`409 last-consultorio-required`**. Eso convierte a la baja en una operación con invariante de
conteo, que es exactamente la forma del "último admin" de 01.03 §6.1 — y por lo tanto tiene la
misma carrera: dos bajas simultáneas de las dos últimas sedes, cada una contando "queda otra
activa además de la que estoy bajando", las dos ven a la otra, las dos pasan, y el tenant queda
sin ninguna sede.

El protocolo es el mismo, y hay que decir lo que implica:

```
@Transactional(isolation = Isolation.READ_COMMITTED)
1. SELECT id FROM subscription WHERE organization_id = :orgId FOR UPDATE   ← PRIMERA sentencia
2. evaluar el permiso del actor
3. SELECT COUNT(*) FROM consultorio
     WHERE organization_id = :orgId AND active = 1 AND id <> :objetivo
   FOR SHARE                                                              ← lectura con lock
4. si el conteo es 0 → 409 last-consultorio-required
5. deactivate + auditoría
```

**La baja bloquea la fila de `subscription` aunque no consuma ni libere cupo de plan.** Es
contraintuitivo y es deliberado: el orden de bloqueo del sistema es único (§9.4), y un orden que
se respeta solo cuando hay límites de por medio no es un orden. El costo es que dos bajas de
sedes distintas del mismo tenant se serializan; es una operación administrativa poco frecuente y
el costo es aceptable.

**El paso 3 no puede ser una lectura consistente.** Es la lección 1 otra vez: el lock serializa
el acceso, no la visibilidad. Con un `SELECT` común en `REPEATABLE READ`, las dos transacciones
cuentan la sede de la otra y las dos pasan.

**Y el invariante no se puede hacer valer con el `PlanGate`:** el gate cuenta hacia arriba, contra
`MAX_CONSULTORIOS`, y esto cuenta hacia abajo, contra un piso de 1 que ningún plan declara. Es
lógica de 02.01, no del gate.

### 9.7 Selección de contexto concurrente con la baja de esa sede

`isContextAuthorized` lee sin lock. Un token puede emitirse para una sede dada de baja un
milisegundo después. No se cierra con locks —serializaría el login contra la administración—: se
cierra con la revalidación por request de §4.2. La ventana pasa de "el TTL del token" a "un
request".

### 9.8 Isolation asumida

- **Por defecto:** el de MySQL 8.4, `REPEATABLE READ`. No se cambia globalmente.
- **`READ COMMITTED` únicamente dentro de la transacción que abre `PlanGate.createWithinLimit`.**
  No la declara 02.01: la declara el gate, y `verificarIsolation` falla ruidosamente si alguien la
  elude. **02.01 no debe declarar isolation por su cuenta en el alta.**
- **Ninguna lectura que decida un invariante es una lectura consistente.** El único conteo que
  decide en 02.01 es el de `MAX_CONSULTORIOS`, y lo hace el gate, después del lock y en
  `READ COMMITTED`.
- **Nunca `SERIALIZABLE`.**

---

## 10. Plan de tests

### 10.1 Unitarios — estados y reglas

| # | Qué prueba | Traza |
|---|---|---|
| U-1 | `deactivate` deja `active=false` y `deleted_at` seteado | RN-M03-002 |
| U-2 | Una sede inactiva rechaza edición, rechaza segunda baja y **permite lectura** | §4.1; RF-M03-003/004 |
| U-2b | La baja de la única sede activa del tenant se rechaza; con dos activas, procede | D-7; §9.6-bis |
| U-3 | Validador de zona: acepta `America/Argentina/Cordoba`; rechaza `-03:00`, `ART`, `GMT-3`, vacío y nulo | §3.3 |
| U-4 | El alta hereda la zona de la organización cuando el comando no la trae | §3.1 |
| U-5 | Cambiar la zona no altera ningún instante persistido | `AGENT.md` §5 |
| U-6 | El evaluador niega `consultorio:manage` con alcance consultorio sobre un alta (`OUT_OF_SCOPE`) | §6.2; matriz §6 |
| U-7 | El servicio de alta **no** está anotado `@Transactional` — test de arquitectura, no unitario | §9.2 |

### 10.2 Integración — transaccional y de tenant

| # | Qué prueba |
|---|---|
| I-1 | Migraciones C1→C2→C3 sobre una base con sedes preexistentes: ninguna queda con `timezone` NULL, y el valor coincide con el de su organización |
| I-2 | `uk_consultorio_org_name_vigente`: dos activas con el mismo nombre → violación; una activa y una dada de baja con el mismo nombre → **permitido**. **Es el test que prueba que la trampa de los NULL no volvió** |
| I-3 | Un fallo al persistir la auditoría revierte el consultorio: no queda sede sin rastro (T-2) |
| I-4 | Token del tenant B sobre una sede del tenant A → **404**, en los cuatro endpoints. Nunca 403 |
| I-5 | Alta con la suscripción `SUSPENDIDA` → 409, sin crear nada |
| I-6 | Alta que supera `MAX_CONSULTORIOS` → 409 **y auditoría de rechazo** (`PlanLimitRejectionAuditor`) |
| I-7 | Reintento con la misma `Idempotency-Key` y mismo payload → mismo resultado, una sola sede |
| I-8 | Reintento con la misma `Idempotency-Key` y payload distinto → 409 `idempotency-key-conflict` |
| I-9 | Dar de baja una sede la saca de `GET /me/contexts`, y `POST /auth/context` sobre ella devuelve 404 |
| I-9b | La baja de la última sede activa responde **409 `last-consultorio-required`** y la sede sigue activa **en la base**, no solo en la respuesta |
| I-10 | Una sede inactiva sigue siendo legible por id y sus eventos de auditoría siguen consultables |
| I-11 | Un token emitido para una sede que se da de baja después deja de servir en el **request siguiente** (§4.2) |

### 10.3 Concurrencia — hilos reales contra MySQL real

Reusan `com.akine.diferidos.Concurrencia` (latch + barrera) y afirman sobre el **estado final de
la base**, no sobre el orden de las respuestas. Un test con mocks pasa con el diseño roto: es
precisamente por eso que los tres bugs de esta semana no habían aparecido antes.

| # | Escenario | Invariante |
|---|---|---|
| K-1 | Dos altas simultáneas de la sede número `MAX_CONSULTORIOS` | Una entra, la otra 409. **Nunca dos.** Es el gemelo de `LimiteDePlanConcurrenteIT` sobre el recurso que 02.01 estrena |
| K-2 | Dos altas simultáneas con el mismo nombre | Una fila. La segunda es **409, no 500** — regresión de la lección 3 |
| K-3 | Alta de sede concurrente con una **mutación de membership** de 01.03 en la misma organización —cambio de rol o revocación, que es lo que esa etapa dejó vivo (D-1)— | **Ninguna muere por `CannotAcquireLockException`.** Es el test del orden único de §9.4: con el orden invertido falla, con `subscription` → `organization` pasa. Cuando vuelva el alta de membership, se agrega la variante que además pasa por el gate |
| K-4 | Dos bajas simultáneas de la misma sede | Una gana; la otra 409. Un solo evento de auditoría |
| K-5 | Baja de la sede concurrente con la selección de ese contexto | O el canje falla, o el token emitido deja de servir en el request siguiente. Nunca una sesión operando indefinidamente sobre una sede muerta |
| K-6 | N=5 altas simultáneas con el cupo en 2 | Entran exactamente 2 |
| K-7 | Dos bajas simultáneas cuando quedan **dos** sedes activas | Sobrevive **exactamente una**. Una transacción recibe 409 `last-consultorio-required`. Es el invariante de D-7 y tiene la misma forma que el "último admin" de 01.03 §6.1: el conteo que decide es una lectura con lock, no una lectura consistente |

### 10.4 Frontend (Vitest)

| # | Qué |
|---|---|
| F-1 | Wizard: paso 1 obligatorio, paso 2 salteable, envío con la zona seleccionada |
| F-2 | Los cinco estados de la pantalla de sedes: carga, vacío, error, permiso insuficiente, éxito |
| F-3 | 409 de nombre repetido → mensaje real del backend, no genérico (`AGENT.md` §8) |
| F-4 | 409 de límite de plan → mensaje accionable con salida a la pantalla de suscripción |
| F-5 | Sede inactiva → detalle en modo lectura, sin acciones de mutación disponibles |
| F-6 | Cambio de `contextEpoch` → la lista de sedes se recarga y no sobrevive ningún dato del tenant anterior |
| F-7 | El diálogo de baja no habilita "confirmar" sin motivo |
| F-8 | Con una sola sede activa, la acción de baja aparece **deshabilitada con explicación** —no oculta— y el 409 `last-consultorio-required` que llegue igual se muestra con su mensaje real |

### 10.5 E2E (Playwright) — alta, selección, baja

**E-1, el recorrido completo de la etapa:** registro self-service → login → contexto → crear una
segunda sede con zona distinta → verificar que aparece en el selector → cambiar el contexto a la
sede nueva → volver → dar de baja la sede nueva con motivo → verificar que desapareció del
selector, que su detalle sigue siendo legible y que su evento de baja está en la auditoría.

**E-2:** un `PROFESIONAL` acotado a una sede no ve la sede nueva en su selector y recibe 403 al
intentar el alta por API directa (la UI puede ocultar; el backend rechaza igual).

> **Problema de montaje de E-2, y hay que resolverlo antes de escribirlo.** Ese `PROFESIONAL`
> acotado a una sede es una membership que **ningún endpoint puede crear** mientras la D-1 de
> 01.03 siga cerrada como B. Un E2E que siembra memberships por SQL deja de ser de punta a punta
> justamente en el paso que importa. Las salidas son dos: sembrar con una utilidad de test y
> declararlo en el registro de cierre, o que 02.01 sea la etapa que recupere RF-M05-001/002 —que
> es la candidata que la D-1 de 01.03 dejó anotada y sin fijar—. Es una decisión del usuario, no
> del implementador.

Los E2E corren con backend y frontend **en la misma rama** (`../CLAUDE.md`, regla 6).

---

## 11. Design challenge — respondido

### 1. Ownership

| Tabla | Propietario | Quién más la toca |
|---|---|---|
| `consultorio` (expandida) | `organization` | Nadie. `platform` solo la ve por el puerto invertido de revalidación de contexto, que devuelve un record del `spi` |
| `consultorio_alta` (si D-9 elige tabla propia) | `organization` | Nadie |

02.01 **no crea ninguna tabla en otro módulo** y no lee ninguna ajena. El bloqueo de baja por
turnos futuros, que sería el único motivo para hacerlo, se resuelve con un puerto declarado en
`organization.spi` e implementado por `scheduling` en F5 (§8.4).

### 2. Ciclos

Las flechas que 02.01 usa ya existen y todas van en la dirección permitida:

```
identity ──► organization.spi                                   (existe, 01.02)
organization.infrastructure.tenant ──implementa──► platform.spi  (existe, 01.01)
scheduling ──► organization.spi                                  (nueva, F5: consumidor → cimiento)
```

02.01 **no agrega ninguna dependencia de módulo nueva en F1**. ArchUnit pasa sin tocar
`ModuleArchitectureTest`.

### 3. Tenant

`consultorio` ya lleva `organization_id NOT NULL` con FK. El unique nuevo
`uk_consultorio_org_name_vigente` **lo incluye como primera columna**; los dos índices también.
Los métodos nuevos del port llevan `organizationId` en la firma, igual que los tres que ya
existen. Referencia fuera del alcance → **404**, nunca 403 (regla heredada de 01.01).

La columna generada `deleted_key` **no** debilita el alcance tenant: el unique sigue empezando
por `organization_id`.

### 4. Reglas maestras

No se tocan HC, Caso, Sesión, Turno, Obligación, Cobro ni Caja: ninguna existe todavía. Sí se
respetan la regla 10 (nada histórico se borra) y la 11 (multi-tenancy). Y una específica:
**RN-M03-004 — el horario general no reemplaza la disponibilidad individual del profesional.**
Por eso el horario general no entra en 02.01 sin decisión (D-2): modelarlo antes de que exista
disponibilidad profesional invita a que alguien lo use como fuente de verdad de la agenda, que es
exactamente lo que la regla prohíbe.

### 5. Baja lógica

Cero borrados físicos. `deactivate` ya existe en la entity y solo escribe `active` y
`deleted_at`. La sede inactiva sigue siendo legible, con su auditoría y sus hechos pasados
intactos (§4.1). El unique nuevo permite reusar el nombre **sin tocar la fila vieja**.

### 6. Contrato

**Aditivo.** `0.4.0` → `0.5.0` (minor). Cuatro endpoints nuevos, un query param opcional con
default que preserva el comportamiento, ningún cambio de firma ni de semántica. El único cambio
de comportamiento observable —la revalidación de la sede por request— **endurece** una regla ya
prometida (RN-M03-003) y se declara en el changelog del contrato.

### 7. Ruta crítica

`Tenant → Consultorio → Membership/Permisos → …`. Los cimientos existen: `organization` (01.01),
`identity` (01.02), permisos (01.03). 02.01 es el segundo eslabón, completado tarde a propósito
porque necesitaba el evaluador.

**Lo que hay que decir en voz alta:** al escribirse este documento 01.02 no estaba cerrada
—escenario diferido 8 y 50 rutas sin commitear— y 01.03 está en curso (`V10` ya aplicada en el
árbol). Arrancar 02.01 con 01.03 abierta viola el plan §10.2.5. El orden correcto es cerrar
01.03, publicar `0.4.0`, regenerar el cliente, y recién entonces esta etapa. D-11 ya está cerrada: el orden de
bloqueo del sistema es `subscription` → `organization` y 01.03 se reescribió para respetarlo
(§9.4).

### 8. El caso que rompe el diseño

**El escenario, concreto.** Una organización en el plan BÁSICO, con una sede y cupo para dos. Son
las 9:03 de un lunes. La dueña, `ORG_ADMIN`, aprieta "crear sede" para dar de alta la sucursal
nueva. En el mismo segundo, otro administrador le cambia el rol a un colaborador del centro.
Dos requests, misma organización, milisegundos de diferencia. (El escenario original era una
aceptación de invitación; con D-1 de 01.03 cerrada como B la invitación no existe todavía, y la
forma del problema es la misma: cualquier mutación de membership toma el lock del tenant.)

**Por qué rompe un diseño razonable.** Las dos operaciones parecen no tener nada que ver: una
crea una sede, la otra toca una membership. Tablas distintas, endpoints distintos, pantallas
distintas. Pero las dos serializan sobre el mismo tenant, así que las dos terminan tomando los
mismos dos locks — y en la versión original de 01.03, **en orden inverso**:

- El alta de sede entra por `PlanGate.createWithinLimit`, que toma `X` sobre la fila de
  `subscription`, y **después** el `INSERT INTO consultorio` toma `S` sobre la fila de
  `organization` por la FK.
- La mutación de membership, tal como 01.03 §6.1 la especificaba antes del cierre de D-11, tomaba
  `X` sobre la fila de `organization` **como primera sentencia**; y si era un alta, después
  necesitaba el gate para `MAX_MIEMBROS_ACTIVOS`, o sea `X` sobre `subscription`.

A espera a B por `organization`; B espera a A por `subscription`. InnoDB detecta el ciclo, mata a
una y devuelve `CannotAcquireLockException`, que el handler genérico de hoy mapea a **500**. La
dueña ve "error del servidor" al crear una sede, o el otro administrador lo ve al cambiar un rol,
sin ninguna relación aparente entre ambas cosas y sin reproducirlo jamás en un entorno de un solo
usuario. Es el bug 2 de la carpeta `diferidos` —transacciones simétricas,
escalada de lock sobre la fila padre— reencarnado **entre dos etapas distintas**, que es
exactamente donde nadie lo busca.

Y hay una segunda capa: es **irresoluble desde 02.01**. `createWithinLimit` rechaza por diseño
unirse a una transacción existente, con un `IllegalStateException` cuyo mensaje explica que al
unirse heredaría la isolation del llamador y el límite se violaría en silencio. O sea que no
existe la opción "tomar primero el lock de `organization` y después llamar al gate". La única
salida estaba del lado del contrato del gate o del lado de 01.03, y se eligió la segunda.

**Cómo se resolvió.** 02.01 lo detectó, lo nombró y lo escaló como D-11, y **el usuario lo cerró
el 23/08/2026**: el orden de bloqueo único del sistema es `subscription` → `organization`, 01.03
§6.1 se reescribe para tomar la suscripción como primera sentencia, y el `PlanGate` no se toca
porque ya está probado con hilos reales. Era la única salida alcanzable sin tocar el gate.

Lo que 02.01 aporta es el test que lo vigila: **K-3**, dos hilos reales contra MySQL real, un alta
de sede y una mutación de membership en la misma organización, afirmando que **ninguno de los dos
muere por infraestructura**. Es la misma forma de test que encontró los tres bugs de esta semana,
y la única que distingue el diseño correcto del incorrecto: con mocks, K-3 pasa con el deadlock
intacto.

**Y queda una advertencia, porque la decisión no elimina la clase de bug, solo esta instancia.**
El orden de bloqueo vive en dos lugares que ninguna herramienta compara: una primera sentencia en
01.03 y el interior del `PlanGate`. No hay ArchUnit para esto. La única defensa es K-3 y un
comentario en cada uno de los dos lugares que cite al otro.

---

## 12. Riesgos y decisiones

Nada de lo que sigue abierto se implementa hasta que el usuario decida. Cada una tiene opciones y
consecuencias, no una recomendación disfrazada de hecho.

| Estado | Decisiones |
|---|---|
| **Cerradas el 23/08/2026 por el usuario** | D-7 (rechazar la baja de la última sede activa) y D-11 (orden de bloqueo `subscription` → `organization`) |
| **Abiertas, bloqueantes para implementar 02.01** | D-1 (primer box), D-2 (horario general), D-5 (campos institucionales), D-6 (baja de la propia sede por `CONSULTORIO_ADMIN`), D-9 (registro de idempotencia), D-10 (lectura del listado) |
| **Abiertas, decidibles después** | D-3 (tercer estado), D-4 (reactivación), D-8 (turnos futuros — se decide ahora, se implementa en F5) |

### D-1 — RF-M03-002 nombra "primer box", y `Box` es de 02.02

RF-M03-002 dice literalmente: *"Crear consultorio, primer box, horario general e intervalo
inicial"*. El plan asigna espacios y boxes a **AKINE-02.02**. 02.01 no puede crear una tabla de
`resource` sin violar ownership.

| Opción | Consecuencia |
|---|---|
| **A. 02.01 cubre solo el consultorio y declara CA-M03-002 parcialmente cubierto**, con etapa destino 02.02 | Honesto y alineado con la arquitectura. Deja un CA sin cerrar en el registro de cierre, igual que 01.01 hizo con sus once escenarios |
| **B. El onboarding de sede se difiere entero a 02.02** y 02.01 no expone `POST .../onboarding` | Un solo endpoint de onboarding, coherente, cuando existan todas sus piezas. 02.01 entrega alta simple y nada más |
| **C. 02.01 crea el box** | Viola `AGENT.md` §4 regla 1, verificada por ArchUnit. **No se recomienda ni como excepción** |

### D-2 — ¿El horario general entra en 02.01?

RF-M03-002 lo nombra; RN-M03-004 lo acota. El plan de 02.01 no lo menciona en "Base de datos" ni
en "API"; 02.02 tampoco lo nombra explícitamente. Es un hueco entre dos etapas.

| Opción | Consecuencia |
|---|---|
| **A. Entra en 02.01** como configuración del consultorio (días y franjas por defecto) | Cierra RF-M03-002 más completo. Agrega una tabla hija, su unique con alcance tenant, y el riesgo de que la agenda futura lo tome como fuente de verdad contra RN-M03-004 |
| **B. Se difiere a la etapa de agenda (F5)** | El horario general nace junto a los slots que lo consumen y a la disponibilidad profesional que lo acota. Deja RF-M03-002 más incompleto todavía |
| **C. Solo el intervalo por defecto** (`slot_minutes`) como columna de `consultorio` | Una columna, cero tablas nuevas, y es el único parámetro que la agenda va a necesitar sí o sí. No cubre "horario general" |

### D-3 — ¿Hacen falta más de dos estados?

El diseño propone `ACTIVO`/`INACTIVO` derivados, sin columna (§2.1). ¿Existe un caso real de
"suspendido temporalmente" —refacción, cierre de verano— distinto de la baja definitiva? Ningún
RF de M03 lo pide.

| Opción | Consecuencia |
|---|---|
| **A. Dos estados derivados** (lo propuesto) | Cero migración de estado, cero invariante nuevo, coherente con el precedente de `operationalStatus` |
| **B. Columna `estado` con un tercer valor `SUSPENDIDO`** | Modela un caso real del negocio. Obliga a definir qué admite ese estado, a un backfill y a un invariante que impida `active=1` con `estado=INACTIVO` |

### D-4 — ¿Se puede reactivar una sede dada de baja?

**Ningún RF de M03 lo pide.** RF-M03-004 solo describe la baja.

| Opción | Consecuencia |
|---|---|
| **A. No existe reactivación** | Menos superficie. Una baja por error se arregla creando una sede nueva, y la historia queda partida en dos sedes |
| **B. Existe `POST .../reactivate` con `consultorio:manage`** | Arregla el error humano. Endpoint sin RF que hay que justificar; y reactivar puede violar `MAX_CONSULTORIOS` si el cupo se llenó mientras tanto, así que también tendría que pasar por el gate |
| **C. Existe pero solo para `PLATFORM_ADMIN` con acceso de soporte** | Trata la baja como casi definitiva y deja una salida operativa auditada. La D-2 de 01.03 ya está cerrada (el rol existe y sale de `platform_role`); **sigue dependiendo de la D-3**, el modelo de acceso de soporte, que continúa abierta |

### D-5 — Qué campos institucionales lleva un consultorio

M03 §1 dice "información institucional" y no enumera nada. Los cinco campos de §2.1 son una
propuesta, no una traza. Preguntas concretas: ¿el CUIT es del consultorio o de la organización?
¿La dirección es texto libre o se estructura (calle / número / ciudad / provincia / CP)? ¿Hace
falta más de un teléfono? **Cada campo que se agregue sin RF hay que declararlo como decisión en
el registro de cierre.**

### D-6 — ¿Un `CONSULTORIO_ADMIN` puede dar de baja su propia sede?

La matriz §6 le da `consultorio:manage` con alcance consultorio, lo que **literalmente lo
permite**. El diseño propone rechazarlo (§6.2) por analogía con el `self-revoke` de 01.03.

| Opción | Consecuencia |
|---|---|
| **A. Se rechaza (403)** | Coherente con el invariante "no destruís tu propio alcance". Se aparta de la lectura literal de la matriz y hay que dejarlo escrito |
| **B. Se permite** | Fiel a la matriz. Un `CONSULTORIO_ADMIN` distraído se deja a sí mismo sin contexto de trabajo, y solo un `ORG_ADMIN` puede rescatarlo |

### D-7 — ¿Se puede dar de baja la última sede activa del tenant? — **CERRADA (opción A)**

**Decidida por el usuario el 23/08/2026: se rechaza con `409 last-consultorio-required`.** Sin
sedes activas, `GET /me/contexts` devuelve vacío para todos, nadie puede canjear contexto y el
tenant queda operativamente muerto; solo se recupera con SQL manual contra la base.

Ningún RF lo prohíbe: es una **restricción agregada** al comportamiento de RF-M03-004, análoga al
invariante "último admin" de 01.03 §6.1, y va declarada como tal en el registro de cierre.

Descartadas: **B** (permitirlo), fiel a la letra del RF y con la puerta abierta al tenant sin
contexto; **C** (permitirlo solo si la organización también se da de baja), correcto
conceptualmente pero acopla dos operaciones que hoy son independientes y la baja de organización
no existe todavía.

**Lo que el cierre arrastra, y no es menor:**

- La baja pasa a ser una operación con **invariante de conteo**, con la misma carrera que el
  último admin y el mismo protocolo: lock de `subscription` como primera sentencia, conteo con
  `FOR SHARE`, `READ COMMITTED`. Está en §9.6-bis, y suma el test K-7.
- **La baja bloquea la fila de `subscription` aunque no toque el plan.** Es el precio de tener un
  orden de bloqueo único (§9.4).
- **La última sede queda indeleble mientras la organización exista.** Un tenant que se equivoca de
  nombre en su única sede tiene que editarla, no rehacerla; y si alguna vez existe la baja de una
  organización, tendrá que resolver el orden entre "dar de baja el tenant" y "no podés bajar la
  última sede".

### D-8 — Qué pasa con los turnos futuros al dar de baja (se implementa en F5, se decide ahora)

RN-M03-003 solo dice que un consultorio inactivo no recibe turnos **nuevos**.

| Opción | Consecuencia |
|---|---|
| **A. La baja se bloquea (409) mientras haya turnos futuros** | Nadie pierde un turno por sorpresa. La sede no se puede cerrar hasta reagendar o cancelar uno por uno |
| **B. La baja procede; los turnos futuros sobreviven y se atienden** | Fiel a la letra de RN-M03-003. Deja turnos vivos en una sede "cerrada", un estado que nadie va a entender mirando la agenda |
| **C. La baja procede y cancela los turnos futuros en cascada** | Es lo que la gente espera operativamente. **Choca con ADR-0011/DP-04**: cancelar turnos futuros exige confirmación explícita, motivo obligatorio y auditoría por turno. Sería una operación mucho más grande, con notificaciones a pacientes |

### D-9 — Dónde vive el registro de idempotencia del alta de sede

Tabla propia `consultorio_alta` (duplica el patrón de `organization_onboarding`) vs. generalizar
`organization_onboarding` (toca una tabla con datos y un camino ya cerrado, y sus columnas
`account_id` y `membership_id` son `NOT NULL`, que acá no aplican). Ver §5.3.

### D-10 — Lectura del listado de sedes: `requireMember` vs. la matriz

**Es la misma tensión que la D-9 de 01.03.** La matriz §5 no tiene `consultorio:read`; el listado
hoy lo lee cualquier miembro y **tiene que seguir así**, o el selector de contexto se rompe para
todo rol que no sea admin.

| Opción | Consecuencia |
|---|---|
| **A. El listado queda con `requireMember`, documentado como excepción** | Nada se rompe. Deja una lectura sin permiso en el catálogo, que es lo que §32 quiere evitar |
| **B. Se agrega `consultorio:read` al catálogo** con base para todos los roles | Coherente con el modelo. **Enmienda un documento vinculante**: requiere aprobación explícita |
| **C. Se alinea con lo que se decida en la D-9 de 01.03** | Lo más consistente. **Bloquea 02.01 hasta que la D-9 de 01.03 se resuelva** |

### D-11 — Orden de bloqueo entre el `PlanGate` y las mutaciones de membership de 01.03 — **CERRADA (opción A)**

**Decidida por el usuario el 23/08/2026: el orden único del sistema es `subscription` →
`organization`.** Se reescribe 01.03 §6.1 y no se toca el `PlanGate`. Desarrollado en §9.4 y en
el design challenge punto 8; el test que lo vigila es K-3.

Lo que queda vivo de esta decisión es una **regla permanente**, no una tarea: toda transacción de
cualquier módulo que necesite las dos filas las toma en ese orden, y el que necesite solo la de
`organization` la sigue tomando después de la suscripción si además la va a necesitar. Un orden
de bloqueo se pierde en silencio: la única señal es un `CannotAcquireLockException` esporádico
que nadie reproduce con un solo usuario, así que el comentario en el código y el test K-3 son la
memoria del sistema.

### D-12 — Riesgos operativos, sin decisión pero con vigilancia

| # | Riesgo |
|---|---|
| R-1 | **La migración C3 no es reversible en la práctica** una vez que exista una sede dada de baja y otra activa con el mismo nombre. Mismo tipo de irreversibilidad que la `V11` de 01.03 |
| R-2 | **El primer consultorio no pasa por el `PlanGate`.** Hoy es inocuo porque todo plan del catálogo permite al menos una sede. Un plan futuro con `MAX_CONSULTORIOS = 0` lo violaría en silencio, sin error y sin auditoría de rechazo |
| R-3 | **Tercer seek indexado por request.** La D-10 de 01.03 advierte que hay que medir antes de agregar el tercero, y la revalidación de la sede del contexto (§4.2) es ese tercero. Medición obligatoria antes del cierre |
| R-4 | **Un cambio de plan a la baja puede dejar más sedes activas que el tope.** `PlanLimit` decide altas, no estados existentes. Ninguna etapa definió qué pasa con el excedente; 01.01 dejó la lectura informativa de límites excedidos y nada más |
| R-5 | El fallback "zona del consultorio → zona de la organización" de la ventana N→N+1 **tiene que borrarse**. Si sobrevive, editar la zona de la organización mueve el día operativo histórico de sedes que nunca fijaron la suya |

---

## 13. Estado de implementación — backend, 24/08/2026

Backend implementado y en verde. **Dos decisiones las tomó el implementador y quedan pendientes
de confirmación del usuario.** No están escondidas en el código: cada una está anotada donde se
aplica, y acá está el resumen para revisarlas juntas.

### 13.1 Decisión A — el horario general no entra; solo el intervalo, como columna

**Qué se hizo.** `consultorio.slot_minutes INT NOT NULL DEFAULT 30`. Cero tablas hijas de
horarios. Es la **opción C de la D-2**.

**Por qué.** RN-M03-004 dice que el horario general **no sustituye** la disponibilidad
profesional individual. Una tabla `consultorio_horario` en F1 —antes de que exista esa
disponibilidad (M05/M12) y antes de los slots que la consumen (F5)— invita a que la agenda de F5
la tome como fuente de verdad, que es exactamente lo que la regla prohíbe. El intervalo, en
cambio, es el único parámetro que la agenda va a necesitar sí o sí y no expresa disponibilidad
por sí mismo.

**Qué cuesta.** RF-M03-002 queda más incompleto de lo que ya quedaba por el box. Ver
`docs/tests-diferidos.md` §AKINE-02.01.

**Dónde está anotado:** cabecera de `V16__m03_consultorio_expandir.sql`, el `COMMENT` de la
propia columna en la base, y el javadoc de `Consultorio.slotMinutes`.

**Cómo se revierte si el usuario decide lo contrario:** se agrega la tabla hija en F5 sin tocar
esta columna ni migrar un solo dato.

### 13.2 Decisión B — solo `ORG_ADMIN` da de baja una sede

**Qué se hizo.** `POST .../consultorios/{id}/deactivate` evalúa `consultorio:manage` con
**alcance ORGANIZACIÓN** (`consultorioId = null` en la `PermissionQuery`). Un
`CONSULTORIO_ADMIN` recibe **403** incluso sobre su propia sede. La edición y la lectura sí se
evalúan **con la sede como alcance**, así que un `CONSULTORIO_ADMIN` edita la suya con
normalidad: la restricción es solo sobre la baja. Es la **opción A de la D-6**.

**Por qué se aparta de la matriz.** La matriz §6 le da `consultorio:manage` con alcance
consultorio, y la baja de la propia sede cae literalmente dentro de ese alcance. Se rechaza por
analogía con el `self-revoke` que 01.03 ya prohíbe: **nadie destruye el alcance desde el que
opera**. Un `CONSULTORIO_ADMIN` que da de baja su única sede se deja sin contexto de trabajo y
sin forma de deshacerlo; solo un `ORG_ADMIN` puede rescatarlo.

**Es una enmienda a un documento vinculante y está pendiente de confirmación**, igual que se
hizo con la enmienda §9.1 de la matriz. Mientras no se confirme, la implementación es la
estricta: es el lado en el que conviene equivocarse.

**Dónde está anotado:** javadoc de `ConsultorioService.deactivate` y la descripción OpenAPI del
endpoint, que la publica al frontend.

**Cómo se revierte si se confirma la lectura literal:** pasar `consultorioId` en la
`PermissionQuery` de ese método. Una línea. El invariante de la última sede activa sigue
protegiendo el caso peor.

### 13.3 Lo que la implementación encontró y el diseño decía distinto

- **§4.2 del diseño dice que 02.01 tiene que extender la revalidación por request al estado de
  la sede. Ya estaba hecho.** `OrganizationMembershipDirectory.resolveMembership` —el camino que
  corre en CADA request desde 01.01— resuelve la sede con
  `findByIdAndOrganizationIdAndActiveTrue`. Una sede dada de baja deja de resolver contexto en el
  request siguiente sin que 02.01 agregue nada. **No se agregó ningún puerto nuevo y no hay un
  tercer seek por request**, así que el riesgo R-3 y su "medición obligatoria" no aplican.
- **D-7 se cumple con dos mecanismos y no con uno.** El lock de `subscription` como primera
  sentencia serializa el ACCESO; el conteo con `FOR SHARE` serializa la VISIBILIDAD. Con el lock
  solo, en `REPEATABLE READ` las dos bajas simétricas cuentan la sede de la otra y las dos pasan.
- **El plan por defecto del alta self-service no permite una segunda sede** (`BASICO`,
  `MAX_CONSULTORIOS = 1`). Ver la nota final de `docs/tests-diferidos.md`.
