# AKINE — Colaborador sin cuenta: alta manual y acceso posterior

**06/11/2026** · rama `akine-colaborador-sin-cuenta` (api) · migración reservada `V143` (y las que
siga necesitando, reservadas antes de escribir) · contrato desde `0.159.0` · módulos M05
(`organization`, `identity`) y M07 (`person`) · pedido del usuario del 06/11 ("Alta manual y gestión
integral de colaboradores").

> **Estado: borrador para aprobar.** Nada de esto está implementado. La decisión de fondo —la
> vinculación existente admite no tener cuenta, opción A— la tomó el usuario el 06/11. Lo marcado
> **(decisión del agente)** es mío y se puede revertir.

## 1. Para qué

Hoy un centro **no puede cargar a su kinesiólogo ni a su secretaria hasta que esa persona tenga
cuenta en AKINE**: el único camino es la invitación (que crea la cuenta al aceptar) o el vínculo
directo por email de una cuenta que ya existe. Mientras tanto no se le puede cargar horario,
habilitarlo en una oferta ni darle turnos.

Lo que se quiere: el administrador registra al colaborador con sus datos de persona y de función,
lo usa en la operación **desde ese momento**, y más adelante —si hace falta— le habilita el acceso
con una invitación que se **engancha al registro que ya existe**, sin crear otro.

Separar cuatro cosas que hoy están pegadas:

| Concepto | Dónde vive | Hoy |
|---|---|---|
| Persona | `persona` (de la organización) | existe, sólo la usan los pacientes |
| Colaborador | `membership` + ficha nueva | `membership` existe, exige cuenta |
| Profesional | ficha nueva (datos profesionales) | no existe |
| Usuario | `cuenta` (global) | existe |

## 2. Objetivos, no objetivos y antiobjetivos

**Objetivos**

1. Alta manual de colaborador **sin cuenta, sin email, sin contraseña ni invitación**, de tipo
   Profesional, Administrativo / Secretaría u Otro.
2. Que ese colaborador funcione **igual que uno con cuenta** en horarios, excepciones, disponibilidad,
   turnos, series, lista de espera, habilitaciones y duraciones de oferta, clases e instructores,
   equipo del caso y honorarios.
3. Ficha del colaborador con sus datos, su función y su sección **Acceso a AKINE**.
4. Invitar después y que **aceptar complete la vinculación existente**.
5. Detección de posibles duplicados al dar de alta, sin filtrar datos de otras organizaciones.

**No objetivos (quedan fuera de esta tanda)**

- Autoservicio del profesional sobre sus propios datos (alcance `OWN`, pospuesto por DP-22).
- Validación de matrícula contra un registro oficial: se guarda número + jurisdicción, sin consultar a
  nadie.
- Requisitos legales por servicio ("para prestar X hace falta matrícula de Y"): no hay RF que los
  declare. Se deja el lugar (ver §6.4) y no se implementa la regla.
- Catálogo de profesiones/disciplinas administrable por pantalla, más allá de lo necesario para
  elegir una (ver §6.3).

**Antiobjetivos (si pasa esto, está mal aunque compile)**

- **Usuarios ficticios**: ninguna `cuenta` sin dueño real para "tapar" el `NOT NULL`.
- **Acceso por email coincidente**: que una cuenta con el mismo email herede la vinculación sin
  aceptar una invitación válida.
- **Dos colaboradores para la misma persona** después de aceptar la invitación.
- **Turno doble entre sedes** para un profesional sin cuenta (el bloqueo de hoy va por cuenta, §5.3).
- Que el colaborador **aparezca como paciente** en el padrón o en el buscador de turnos
  (Persona ≠ Paciente, regla 8).
- Que trabajar en una sede **dé permisos** sobre ella.

## 3. Modelo

### 3.1 `membership` admite no tener cuenta

- `membership.account_id` pasa a **`NULL`**. Una vinculación sin cuenta se llama *operativa*; una con
  cuenta, *con acceso*.
- Se agrega `membership.persona_id` (FK `persona`, `NULL` sólo para vinculaciones viejas sin
  backfill, ver §3.4). **Toda vinculación nueva nace con persona**, tenga o no cuenta.
- El unique `uk_membership_org_account_scope (organization_id, account_id, consultorio_scope)` sigue
  sirviendo para las vinculaciones con cuenta (varios `NULL` no chocan en MySQL). Se agrega
  `uk_membership_org_persona_scope (organization_id, persona_id, consultorio_scope)` sobre las
  vigentes, para que una persona no tenga dos vinculaciones vivas con el mismo alcance.
- `ConsultorioMembershipSnapshot.accountId` pasa a `Long` (o `OptionalLong`), y **cada llamador se
  revisa uno por uno**: el inventario de §5 es la lista mínima, no la completa. Hay que buscar
  también `findByAccount`, `accountId()` y `AccountIdentityDirectory`.
- Una vinculación operativa **no autentica**: sin cuenta no hay login, ni contexto activo, ni grants
  efectivos. Los grants (`membership_grant`) se pueden cargar pero sólo rigen cuando hay cuenta
  **(decisión del agente: bloquear grants en vinculaciones sin cuenta, para no dejar permisos
  latentes que nadie revisó al invitar)**.

### 3.2 Ficha del colaborador — tabla nueva `colaborador_ficha`

Una fila por **persona dentro de la organización** (no por vinculación: una persona con dos
vinculaciones tiene una sola ficha).

| Columna | Nota |
|---|---|
| `organization_id`, `persona_id` | unique juntos |
| `tipo` | `PROFESIONAL`, `ADMINISTRATIVO`, `OTRO`. **Función, no rol** |
| `funcion` | texto corto: Secretaría, Recepción, Coordinación, "Mantenimiento"… Para `ADMINISTRATIVO` y `OTRO` |
| `fecha_incorporacion` | opcional |
| `observaciones` | internas, opcional |
| `version`, auditoría estándar, baja lógica (cuarteto de la regla 10) | |

Datos profesionales en tabla aparte, `colaborador_perfil_profesional` (1:1 con la ficha, sólo si
`tipo = PROFESIONAL`):

| Columna | Nota |
|---|---|
| `matricula_numero`, `matricula_jurisdiccion` | texto; sin formato universal. Unique por `(organization_id, jurisdicción normalizada, número normalizado)` vigente |
| `disciplina_id` | FK al catálogo de disciplinas (§6.3) |
| `especialidad_principal_id` | FK `especialidad` (catálogo existente) |
| `es_instructor` | booleano informativo; **no** es un rol (RN-M05-006) |
| `observaciones_profesionales` | |

Especialidades adicionales: `colaborador_especialidad (ficha_id, especialidad_id)`.

**Los servicios que puede prestar NO se guardan en la ficha**: ya existen como habilitaciones de
oferta (`oferta_profesional_habilitado`, por sede). La ficha los **muestra** y el alta permite
habilitarlo en ofertas en el mismo acto (§4.2), pero la fuente de verdad sigue siendo la
habilitación. Lo mismo la duración propia (`oferta_duracion_profesional`).

### 3.3 Sedes de trabajo vs. alcance de acceso

- **Alcance de acceso** = el `consultorio_scope` de la vinculación, como hoy (organización o una
  sede). Sólo importa cuando hay cuenta.
- **Sedes de trabajo** = tabla nueva `colaborador_sede (organization_id, membership_id, consultorio_id)`: dónde
  trabaja o presta servicio. Es lo que leen horarios, disponibilidad y los candidatos de
  habilitación.
- Regla: las sedes de trabajo tienen que estar **dentro** del alcance (una vinculación de sede X no
  trabaja en Y). Se valida al escribir.
- **(decisión del agente)**: el alta manual crea **una sola vinculación** con alcance organización
  cuando se eligen varias sedes o "todas", y alcance de sede cuando se elige una. "Todas" se guarda
  como la lista explícita de sedes al momento del alta, no como comodín: una sede nueva no suma
  colaboradores sola.
- **Compatibilidad**: para las vinculaciones que ya existen, `colaborador_sede` se completa en la
  migración con lo que hoy se deduce: alcance de sede → esa sede; alcance organización → **todas las
  sedes activas**, que es exactamente lo que hoy ven horarios y habilitaciones **(decisión del
  agente: cero cambio de conducta el día del deploy; el admin recorta después)**.
- Sumar una sede fuera del alcance de una vinculación de sede **amplía el alcance** de esa misma
  vinculación (`PATCH` existente), no crea otra **(decisión del agente)**.

### 3.4 Vinculaciones existentes (backfill)

`V143` (o la que toque) crea **una sola** `persona` por `(organization_id, account_id)` —compartida
por todas las vinculaciones de esa cuenta en la organización— para las que no tienen persona, con nombre y apellido de la cuenta y el email como contacto, y la engancha. Sin
documento: la detección de duplicados no puede unirla a un paciente que ya existe, y **no se une
nada automáticamente** (antiobjetivo). La ficha nace con `tipo` deducido del rol
(`PROFESIONAL` → `PROFESIONAL`; el resto → `ADMINISTRATIVO`).

Hecho esto, `membership.persona_id` puede pasar a `NOT NULL` en la misma migración o en una
siguiente. **(decisión del agente: en la misma, si el backfill no deja huecos medidos contra
`akine_qa`.)**

## 4. Flujos

### 4.1 Alta manual — `POST /api/v1/organizations/{orgId}/colaboradores`

Una sola operación, **una sola transacción**, idempotente por `Idempotency-Key`:

1. Validar (`colaborador:manage`; la organización sale del contexto autenticado, el `orgId` del path
   sólo se compara contra él).
2. Detección de duplicados (§4.4). Si hay candidatos y no viene `confirmaPosibleDuplicado`, 409
   `colaborador-posible-duplicado` con los candidatos **de esta organización**.
3. Crear la persona, o usar una existente si viene `personaId` (caso: el colaborador ya está en el
   padrón como paciente).
4. Crear la vinculación operativa (sin cuenta) con rol y alcance.
5. Crear la ficha y, si es profesional, el perfil profesional y sus especialidades.
6. Registrar las sedes de trabajo.
7. Opcional: habilitarlo en las ofertas elegidas (reusa `OfertaHabilitacionService`; si una
   habilitación falla, falla el alta entera).
8. Auditar `COLABORADOR_REGISTRADO` (más los eventos que ya emiten las piezas reusadas).

Respuesta: la ficha completa con `membershipId`, para que el frontend lleve a la ficha.

### 4.2 Edición

- `PATCH .../colaboradores/{fichaId}` — datos de persona, ficha y perfil profesional, con versión.
- Sedes de trabajo: `PUT .../memberships/{id}/sedes-de-trabajo` con `expectedVersion`.
- Rol y alcance siguen por el `PATCH` de `memberships` que ya existe.
- Suspender, reactivar y revocar: los de hoy, sin cambios. Revocar ya preserva la autoría histórica
  (RN-M05-003) y muestra el impacto en turnos futuros.

### 4.3 Acceso a AKINE

Estado derivado, no guardado:

| Estado | Condición |
|---|---|
| Sin cuenta | `account_id` NULL y ninguna invitación pendiente |
| Invitación pendiente | invitación `PENDIENTE` no vencida apuntando a la vinculación |
| Invitación vencida | `PENDIENTE` con `expira_en` pasado (se muestra para reenviar) |
| Cuenta activa | `account_id` no NULL y la vinculación `ACTIVA` |
| Acceso deshabilitado | vinculación `SUSPENDIDA` con cuenta |

**Invitar a una vinculación existente**: `colaborador_invitacion` gana `membership_destino_id`
(NULL = invitación clásica, que crea vinculación como hoy). La invitación lleva el email (si la
ficha no tiene, se pide en ese momento y se guarda como contacto de la persona).

**Aceptar** (`ColaboradorInvitacionService.aceptar`), si la invitación tiene destino:

1. Resolver la cuenta del que acepta: la crea si no existe; si existe, ver §8, pregunta 4 (hoy
   `aceptar` es público y vincula la cuenta existente **sólo con el token**, sin pedir sesión).
   **La prueba es el token de la invitación**, no la coincidencia de email.
2. En vez de `createFromInvitation`, **completar** la vinculación destino: `account_id = cuenta`,
   bajo lock de la fila y con versión. Si la vinculación ya tiene cuenta, o fue revocada, la
   aceptación falla con 409 y la invitación queda como está.
3. Si esa cuenta **ya tiene otra vinculación viva en la organización**, con cualquier alcance, y su
   persona no es la de la vinculación destino: 409 `colaborador-ya-vinculado`. Una cuenta = una
   persona por organización, para que el lock por persona (§5.3) siga serializando al mismo humano.
   No se fusionan personas ni vinculaciones solas **(decisión del agente)**.
4. Marcar `ACEPTADA` con `aceptada_por_account_id` y `membership_id = destino`. El CHECK
   `ck_..._resolucion_coherente` sigue valiendo tal cual.
5. Auditar `INVITACION_ACEPTADA` y `COLABORADOR_ACCESO_ACTIVADO`.

Reintento del mismo aceptar con la misma cuenta: devuelve el mismo resultado (idempotente). Una sola
invitación pendiente por vinculación destino (unique nuevo).

**Vincular cuenta existente** desde la ficha: **no se hace por email**. Se ofrece "Enviar
invitación" también para quien ya tiene cuenta: al aceptar, se autentica y confirma. El vínculo
directo por email de hoy (`POST /memberships`) queda para crear vinculaciones nuevas y **no
completa** una operativa **(decisión del agente: el pedido dice "no otorgar acceso basándose
únicamente en el email"; el vínculo directo de hoy hace justamente eso y por eso no se extiende)**.

**Deshabilitar acceso** = suspender la vinculación (existe). La cuenta global no se toca.

### 4.4 Duplicados

Buscados **sólo dentro de la organización del contexto**:

- Documento (tipo + número), si viene: coincidencia **fuerte**. Con la persona ya colaboradora → 409
  sin opción de seguir; con la persona sólo paciente → se ofrece usarla (`personaId`).
- Matrícula + jurisdicción: fuerte, bloquea.
- Email de contacto, nombre + apellido normalizados, teléfono: **débiles**, avisan y se puede seguir
  con `confirmaPosibleDuplicado`.

Reusar la lógica de `persona-posible-duplicado` del padrón. **No se buscan cuentas de AKINE por
email en el alta** (sería un oráculo de qué emails tienen cuenta, el mismo que el 404 del vínculo
directo acota con rate limit). Una persona que trabaja en otra organización se registra de nuevo en
esta: la persona es de la organización (regla 8) y la identidad global, si existe, es la cuenta, que
se une recién al aceptar.

## 5. Lo que deja de depender de la cuenta

Inventario mínimo del relevamiento del 06/11. **Cada punto con su test.**

### 5.1 Nombres

Agenda (`scheduling/application/NombresDeAgenda.java`), clínica (`clinical/application/
NombresDeProfesionales.java`) y ofertas (`OfertaHabilitacionService`) toman el nombre de la cuenta
vía `AccountIdentityDirectory`. Pasan a tomarlo **de la persona de la vinculación**, por un `spi` de
`person` o de `organization` (ArchUnit decide: poner la clase sonda antes, regla del 20/09). Con
backfill, toda vinculación tiene persona, así que hay una sola fuente.

### 5.2 "Mis vinculaciones"

`findByAccount` en `SesionService`, `TratamientoService`, `EncounterRelacionAsistencialProbe`,
`HistorialDeAgendaService`: siguen por cuenta, porque responden "quién soy yo, que inicié sesión".
Una vinculación sin cuenta simplemente **no aparece ahí** — no es un defecto: sin login no hay "yo".
**Una sesión clínica sólo la registra quien tiene cuenta**: el profesional sin cuenta puede tener
turnos, pero la atención la registra él cuando tenga acceso, o la registra otro con la regla de
reemplazo que ya existe (V122). Ver §8, pregunta 3.

### 5.3 Bloqueo de agenda entre sedes

`agenda_profesional (organization_id, account_id)` (V129) serializa las escrituras del mismo
profesional en distintas sedes, y `ResourceBloqueoDeAgendaDeProfesional` **saltea en silencio** una
vinculación sin cuenta. Pasa a ir por **`persona_id`**: misma persona = mismo profesional, tenga o
no cuenta. Migración: columna nueva, backfill desde la persona de cada vinculación, unique nuevo, se
borra el viejo. Es el punto de más riesgo; lleva IT de concurrencia contra MySQL real.

### 5.4 Snapshots y validaciones

`ConsultorioMembershipSnapshot.accountId` nullable (§3.1). Revisar cada `accountId()` sobre
vinculaciones: honorarios (`liquidacion_honorario`), egresos (`beneficiario_membership_id`),
notificaciones al profesional (sin cuenta no hay a quién notificar: se omite, no falla) y la
auditoría (el actor sigue siendo una cuenta: quien opera, nunca el colaborador sin cuenta).

## 6. Requisitos y catálogos

### 6.1 RF nuevo

No hay RF que cubra el alta sin cuenta (las reglas del repo piden RF/RN antes de código). Se agrega
**RF-M05-010 — Alta manual de colaborador sin acceso** y **RN-M05-008 — La vinculación operativa no
autentica**, en `docs/producto/AKINE_Requerimientos_Integrados.md`, M05, con los criterios de
aceptación de §9. Y una DP nueva en el plan con esta decisión.

### 6.2 Roles

`role_code` sigue `NOT NULL` y es **el rol que tendrá la cuenta cuando se active**. Por defecto sale
del tipo: Profesional → `PROFESIONAL`; Administrativo y Otro → `ADMINISTRATIVO`. Se puede cambiar en
el alta. **No se crea un rol `OTRO`** **(decisión del agente; ver §8, pregunta 1)**.

### 6.3 Disciplinas

No existe catálogo de profesiones/disciplinas (RF-M05-007 las menciona como "informativas"). Se
agrega `disciplina` al catálogo clínico de 02.05 (mismo esquema global/organización con
`owner_key`), sembrado con: Kinesiología, Fisioterapia, Fonoaudiología, Terapia ocupacional,
Nutrición, Psicología, Medicina, Educación física, Otra. El texto libre queda prohibido donde hay
catálogo.

### 6.4 Requisitos para prestar

Lugar reservado: al habilitar en una oferta a un profesional **sin matrícula cargada**, el backend
responde la habilitación con un aviso (`advertencias[]`), no un error. La regla real por servicio
espera RF.

## 7. Frontend (appKine-web)

Rama propia, después de publicar el contrato. **Lazy**: el bundle inicial está en 445,60 de 446 kB.

- **`/organizacion/colaboradores/nuevo`** se rehace como "Nuevo colaborador": selector de tipo en
  tres botones; secciones Datos personales (grilla 2–4 columnas), Información profesional o Función,
  Sedes de trabajo (lista de casillas con búsqueda si hay más de 8 sedes; no existe multi-select
  compartido y no se crea uno genérico), Ofertas a habilitar (sólo profesional, opcional), y Acceso
  con el texto fijo *"El colaborador se registrará sin acceso al sistema. Podrás enviarle una
  invitación después."*. El vínculo por email de hoy pasa a ser una acción secundaria ("Ya tiene
  cuenta en AKINE") dentro de la misma pantalla.
- Duplicados con el mismo patrón del padrón (candidatos + "registrar igual").
- **Ficha nueva `/organizacion/colaboradores/:id`** con `app-cabecera-de-ficha`: datos, información
  profesional o función, sedes, acceso, enlace a horario y a habilitaciones, historial (auditoría
  filtrada por la vinculación).
- **Listado**: columnas Nombre, Tipo, Función/especialidad, Sedes, Estado, Acceso, ⋯; búsqueda,
  filtros (tipo, estado, acceso, sede), orden y paginación del servidor.
- Los selectores de profesional (horarios, habilitaciones, clases) siguen por `membershipId`. El
  filtro de horarios `atiendeEn()` pasa a mirar las sedes de trabajo y el **tipo** de la ficha, no el
  rol.

## 8. Preguntas para el usuario

1. **Rol de acceso por defecto para "Otro"**: ¿`ADMINISTRATIVO`, o que se elija obligatoriamente al
   invitar (y la vinculación operativa nazca sin rol)? La segunda obliga a `role_code` nullable.
2. **Cupo del plan** (`MAX_MIEMBROS_ACTIVOS`): ¿una vinculación sin cuenta lo consume?
3. **Atención de un profesional sin cuenta**: ¿alcanza con que otro registre la sesión con la regla
   de reemplazo, o la recepción/secretaría tiene que poder registrar la atención a su nombre?
4. **Aceptar con una cuenta que ya existe**: ¿alcanza el token del correo, como hoy, o se exige
   iniciar sesión antes de completar la vinculación?

## 9. Pruebas que no pueden faltar

Unitarias de cada regla y estos ITs contra MySQL real (no mocks):

- Alta de profesional sin email; con email sin verificar; administrativo y otro sin cuenta.
- Profesional sin cuenta: horario semanal, disponibilidad efectiva, turno reservado, habilitación en
  oferta, instructor de clase.
- **Dos reservas simultáneas del mismo profesional sin cuenta en dos sedes: una gana** (§5.3).
- Duplicado por documento, por matrícula y débil por nombre; aislamiento entre organizaciones.
- Invitación a vinculación existente: aceptar con cuenta nueva y con cuenta existente → **misma
  `membership.id`**, sin fila nueva; reintento del aceptar; aceptar sobre una vinculación revocada;
  reenviar vencida; cancelar.
- Revocar un colaborador sin cuenta con turnos futuros: impacto y autoría histórica intactos.
- Backfill de `V143` sobre una base con vinculaciones viejas (copia de `akine_qa`).
- E2E web: alta manual → horario → turno → invitar → aceptar → la misma ficha con acceso activo.

## 10. Design challenge (`CLAUDE.md` §3)

1. **Ownership**: `person` ya importa `organization.spi` (13 clases), así que `organization →
   person.spi` cerraría un ciclo. Las cuatro tablas nuevas van a un **módulo nuevo `collaborator`**,
   que lee `organization.spi` (crear y leer vinculación, extendido) y `person.spi` (crear y leer
   persona) **(decisión del agente)**. `persona` sigue de `person`; `disciplina` de `catalog`
   (02.05); el lock `agenda_profesional` de `resource`. La invitación (`identity`) no escribe la
   persona: el email de contacto lo guarda `collaborator` antes de pedir la invitación.
2. **Ciclos**: aristas nuevas `collaborator → organization.spi`, `collaborator → person.spi` y, para
   los nombres (§5.1), `scheduling`/`clinical`/`offering` → `collaborator.spi`. Cada una se prueba con
   clase sonda y ArchUnit **antes** de escribir el servicio.
3. **Tenant**: todas las tablas nuevas llevan `organization_id` y lo tienen en sus uniques.
4. **Reglas maestras**: Persona ≠ Paciente se respeta (la ficha no toca `perfil_paciente`). Sesión ≠
   Turno: un turno puede ser de un profesional sin cuenta; la sesión la registra una cuenta.
5. **Baja lógica**: ficha y sedes de trabajo con baja lógica; revocar la vinculación no borra nada.
6. **Contrato**: aditivo salvo `MembershipResponse.accountId`/`accountEmail`, que pasan a opcionales.
   Para el cliente TypeScript es un cambio de tipo (`number` → `number | undefined`): se declara en el
   PR y web lo absorbe en el mismo minor.
7. **Ruta crítica**: los cimientos (membership, persona, invitación, catálogo, habilitaciones)
   existen. Falta sólo RF/RN, que va en el PR 1.
8. **El caso que rompe el diseño**: la secretaría registra a "Juan Pérez" sin documento; un mes
   después el admin invita a `juan@x.com`, que **ya tiene cuenta y ya es colaborador de esta misma
   organización** por un vínculo directo viejo. Aceptar no puede unir las dos vinculaciones solo
   (§4.3 paso 3): responde 409 y el admin tiene que revocar una. Pierde la automatización, gana que
   nadie herede turnos o permisos ajenos por un email.

## 11. Orden de entrega

| PR | Repo | Contenido |
|---|---|---|
| 1 | api | `V143`: nullable, persona, ficha, perfil, sedes de trabajo, disciplina, backfill; nombres desde persona; lock por persona; alta manual y edición; RF/RN/DP |
| 2 | api | Invitación con destino, estado de acceso, aceptar que completa |
| 3 | web | Cliente nuevo; alta y listado |
| 4 | web | Ficha y Acceso a AKINE; E2E del circuito |

Los PR 1–2 pueden ir juntos si el contrato sale en una sola versión menor.
