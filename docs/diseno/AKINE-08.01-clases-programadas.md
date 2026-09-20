# AKINE-08.01 — Clases programadas y agenda unificada (M22 / M28, M12)

Diseño de la etapa. **La revisión adversarial está en `AKINE-08.01-challenge.md` y manda sobre
este documento.**

Trazabilidad: RF-M12-009, RF-M12-011, RF-M12-012, RF-M12-013; RF-M28-001, RF-M28-005, RF-M28-006;
RN-M28-001, RN-M28-002, RN-M28-009. RF-M12-010 (cupos) y RF-M28-002..004 quedan para 08.02.

---

## 1. El problema que decide toda la etapa

Una clase ocupa **un espacio y un profesional durante una franja**, exactamente igual que un turno.
Si la clase no participa del mismo mecanismo de exclusión que el turno, se reserva un turno encima
de una clase y **nadie se entera**: no hay unique que lo impida y no puede haberlo — 09:00–10:00 y
09:30–10:00 no comparten un valor de columna, y MySQL 8.4 no tiene exclusion constraints.

En este repositorio la regla la hace cumplir **el lock de `agenda_sede`**, tomado antes de leer un
solo turno, dentro de una transacción en `READ_COMMITTED`. `agenda_sede` es propiedad de
`scheduling`, y `clase_programada` va a ser propiedad de `activity`.

**Decisión: la clase se disputa la misma fila de `agenda_sede`.** No se crea un segundo punto de
serialización. Dos locks exigirían un orden total entre ellos para no deadlockear, y el primer
olvido de ese orden es un deadlock en producción que ningún test unitario reproduce.

Para que eso sea posible sin ciclo entre módulos, `scheduling` publica tres cosas en su `spi` y
`activity` las consume; y para el camino inverso —que la reserva de un turno vea las clases— se
usa la **inversión estándar del proyecto**: el que pregunta declara el contrato, el que sabe lo
implementa. Es la misma forma de `scheduling.spi.AtencionProbe`, que `encounter` implementa.

```
   activity  ──────────────→  scheduling.spi          (lock + ocupación de turnos)
   activity  ──implementa──→  scheduling.spi.*Probe   (ocupación de clases + lectura unificada)
   scheduling ─── NO importa nada de activity ───
```

Una sola arista de compilación, y va de `activity` a `scheduling`. `activity` es hoja: nadie
depende de ella.

### 1.1 Lo que se agrega en `scheduling.spi`

| Contrato | Quién lo implementa | Para qué |
|---|---|---|
| `AgendaDeSede` | `scheduling.infrastructure` | `asegurar()` (REQUIRES_NEW) y `bloquear()` (FOR UPDATE) la fila de la sede; `ocupacionDeTurnos(...)` de profesional y espacio que cruzan un intervalo |
| `OcupacionExternaProbe` | `activity.infrastructure` | `profesionalOcupado(...)` / `espacioOcupado(...)` por eventos que **no** son turnos |
| `EventoExternoDeAgenda` | `activity.infrastructure` | proyección de los eventos no-turno de una sede en una ventana, para la agenda unificada |

`OcupacionExternaProbe` y `EventoExternoDeAgenda` son dos interfaces y no una, aunque las
implemente la misma clase: una se consulta bajo el lock en el camino de escritura y la otra fuera
de toda transacción en el camino de lectura. Tienen razones distintas para cambiar.

**No se deja una implementación provisoria de ninguna de las dos.** `activity` nace en esta misma
etapa y las implementa de verdad. 05.01 dejó un `ReservaProbeSinTurnos` que devolvía vacío, 05.02
no lo reemplazó y la agenda ofreció durante dos etapas huecos ya vendidos: **una costura con
implementación provisoria no avisa cuando le llega el momento.**

### 1.2 La secuencia de una escritura de clase

Es la de `TurnoService.reservar` con los mismos pasos y en el mismo orden:

```
0. agendaDeSede.asegurar(org, sede)        ← transacción APARTE (REQUIRES_NEW)
1. agendaDeSede.bloquear(org, sede)        ← ANTES de leer nada
2. idempotencia (bajo el lock)
3. oferta GRUPAL vigente, profesional, espacio
4. capacidad efectiva
5. solapamiento contra TURNOS   (scheduling.spi)
   solapamiento contra CLASES   (repositorio propio)
6. INSERT + evento de historial + auditoría
```

El paso 0 va en su propia transacción y no dentro de la del negocio: crear la fila-lock
perezosamente dentro de la transacción que la bloquea produce **deadlock** entre las primeras N
escrituras de una sede, y el `try/catch` no salva —atrapar una excepción de persistencia no
des-marca la transacción y Spring lanza `UnexpectedRollbackException` al commitear. Ya se pagó
cuatro veces en este repositorio.

El paso 1 va antes de leer: leer primero y bloquear después es una escalada S→X sobre las mismas
filas y las dos transacciones se esperan mutuamente.

Aislamiento **`READ_COMMITTED`** en las tres mutaciones (crear, reprogramar, cancelar). Con
`REPEATABLE READ` InnoDB fija la foto en la primera lectura consistente —que ocurre antes del
lock— y el lock deja de servir para lo único que sirve.

### 1.3 El camino inverso: el turno que se reserva encima de la clase

`RevalidadorDeSlot` —compartido por reserva y reprogramación de turno desde 05.03— gana dos
controles, después de los que ya hace:

- profesional: `ocupacionExterna.profesionalOcupado(...)` → `RecursoOcupadoException("profesional")`
- espacio: al elegir box, un candidato con ocupación externa se descarta; si no queda ninguno,
  `RecursoOcupadoException("espacio")`

**No se inventa un tipo de error nuevo.** Para la pantalla de turnos el desenlace es idéntico —ese
recurso está tomado en ese horario— y publicar un segundo código obligaría al cliente a manejar dos
para un mismo caso. Lo que cambia es el `detail`, que ya viaja.

Que esto corra **bajo el mismo lock** es lo que lo hace correcto: turno y clase se serializan entre
sí porque las dos escrituras se disputan la misma fila antes de leer.

---

## 2. Modelo

### 2.1 `clase_programada` — propietario `activity`

Un evento grupal único. **No es N turnos** (RF-M12-009, CA-M28-001-06): la clase existe una sola
vez cualquiera sea el número de participantes, y ninguna fila de `turno` la representa.

| Columna | Por qué |
|---|---|
| `organization_id`, `consultorio_id` | tenant y sede. RN-M28-001: la clase pertenece a un consultorio |
| `oferta_id` | RN-M28-001: pertenece a una Oferta **GRUPAL**. FK a `oferta_servicio_consultorio` |
| `profesional_membership_id`, `espacio_id` | nullable, igual que en `turno`, porque la oferta puede no requerirlos |
| `inicio`, `fin` | UTC, `fin` exclusivo. **Los dos se guardan**, como en `turno`: editar la duración de la oferta no puede mover una clase ya programada |
| `capacidad` | capacidad **propia** de la clase (RN-M28-002), congelada al crear |
| `estado` | `PROGRAMADA` \| `CANCELADA`. 08.03 agrega los operativos; agregar valores es aditivo |
| `titulo` | rótulo opcional de la ocurrencia. La oferta da el nombre comercial; esto distingue "Pilates — grupo avanzado" |
| `idempotency_key` + `request_hash` | igual que `turno` y por el mismo motivo: la clave sola no alcanza, reusarla con otro cuerpo es 409 explícito. Se guarda el hash **desde el primer día**, para no repetir la deuda 7b |
| `motivo_cancelacion`, `cancelado_en`, `cancelado_por_cuenta_id` | RN-M28-009 |
| `deleted_at` + `deleted_key` generada | baja lógica. `deleted_key = IFNULL(deleted_at,'1970-01-01')` porque varios `NULL` no colisionan en MySQL |
| `version` | control optimista |

**No hay ningún unique de solapamiento y no puede haberlo.** Lo dice el mismo comentario que
encabeza `V30`; quien venga a buscar "el unique que falta" tiene que leer esto antes.

Índices: `(organization_id, profesional_membership_id, deleted_key, inicio, fin)`,
`(organization_id, espacio_id, deleted_key, inicio, fin)` —los dos que sostienen la consulta de
solapamiento, que es la que decide la correctitud de la etapa— y
`(organization_id, consultorio_id, inicio)` para la agenda del día.

**No lleva `active` ni `deactivation_reason`.** La clase ya tiene `estado`, y `active` sería una
segunda fuente de verdad sobre el mismo hecho — exactamente lo que este proyecto prohíbe.
`deleted_at` + `deleted_key` + `motivo_cancelacion` es la forma que `turno` usa desde `V30` para un
evento de agenda, y una clase es un evento de agenda. No se elimina nada físicamente.

### 2.2 `clase_evento` — propietario `activity`

Append-only: `CREACION`, `REPROGRAMACION`, `CANCELACION`, con actor, instante y el detalle del
cambio. Sin `version`, sin `updated_at`, sin baja, y **su puerto no declara `update` ni `delete`**
—que es la única garantía real de que el historial sea inmutable—. Misma forma que `turno_evento`,
`caso_evento` y `autorizacion_movimiento`.

Cubre RN-M28-009 y CA-M12-012-06 / CA-M28-005-06: reprogramar conserva la identidad de la clase y
su trazabilidad.

### 2.3 Capacidad efectiva

`capacidadEfectiva = min(clase.capacidad, oferta.capacidad, espacio.capacidad)` — RN-M28-002: la
clase tiene capacidad propia **limitada además** por la del espacio. Se calcula **al leer**, nunca
se materializa: el mismo criterio que la disponibilidad efectiva de 02.04 y los slots de 05.01. Si
se guardara, cambiar el box de un espacio dejaría clases prometiendo lugares que no existen.

La **ocupación** (cuántos inscriptos consumen cupo) es de 08.02 y en esta etapa vale `0`, en un
único lugar del servicio y con la etapa destino escrita al lado. No se declara ninguna costura
entre módulos para eso: 08.02 vive en `activity`, el mismo módulo.

La regla de RF-M12-012 —**no mover a un espacio de capacidad inferior a la ocupación confirmada**—
**se escribe ahora** aunque hoy lea cero. Escribirla después es escribirla en el momento en que
empieza a poder romperse.

---

## 3. Operaciones

### 3.1 Crear (RF-M12-009, RF-M28-001)

`POST /api/v1/consultorios/{consultorioId}/clases`, con `Idempotency-Key` opcional.
Permiso `clase:manage` con alcance de sede.

Validaciones, en orden: sede del tenant (404) → permiso (403) → oferta de esa sede (404) → oferta
**GRUPAL**, activa y vigente **ese día** (409) → capacidad pedida dentro de la efectiva (400/409) →
profesional habilitado, vigente y dentro de su disponibilidad efectiva (409) → espacio habilitado,
en servicio y libre (409) → solapamiento contra turnos y contra clases (409).

Estado inicial `PROGRAMADA`, ocupación 0 (CA-M12-009-06).

El profesional y el espacio se validan reusando lo que ya existe: `offering.spi` para
habilitaciones —con la regla de `V28`, **lista vacía significa TODOS, no ninguno**—,
`resource.spi.DisponibilidadDirectory` para la franja y `resource.spi.EspacioDirectory` para el
box. No se duplica ninguna de esas reglas.

### 3.2 Reprogramar (RF-M12-012, RF-M28-005)

`POST .../clases/{claseId}/reprogramacion`. Cambia horario, profesional, espacio y/o capacidad.

**No recrea la clase ni sus participantes**: es un `UPDATE` sobre la misma fila, y su identidad no
cambia (CA-M28-005-06). Revalida todo lo de crear, **excluyéndose a sí misma** de la consulta de
solapamiento —una clase que se corre media hora chocaría contra sí misma—, exactamente como
`RevalidadorDeSlot.turnoExcluidoId`.

Rechaza bajar la capacidad efectiva por debajo de la ocupación confirmada (validación obligatoria
de RF-M12-012 y RF-M28-005). Una clase `CANCELADA` no se reprograma: 409.

Control optimista por `version` de la propia fila. **No hace falta `OPTIMISTIC_FORCE_INCREMENT`**:
la escritura toca columnas del propio agregado, así que la versión avanza una sola vez. Forzarla
además la haría avanzar **dos** y el cliente comería un 409 del que no puede salir — la recíproca
que 04.02 pagó.

### 3.3 Cancelar (RF-M12-012, RF-M28-006)

`POST .../clases/{claseId}/cancelacion`, con motivo obligatorio.
`estado = CANCELADA`, `deleted_at`, `motivo_cancelacion`, actor e instante. La fila queda.

**Idempotente** (CA-M28-006-06): cancelar una clase ya cancelada devuelve la misma clase, con el
mismo motivo original, **sin** registrar un segundo evento de historial. Es el mismo criterio que
`TurnoService.confirmar`: un evento por cada doble click llena el historial de filas que no cuentan
ningún hecho nuevo.

Desde el momento en que se cancela **deja de ocupar el recurso**: las consultas de solapamiento
filtran por `deleted_key`, así que el horario queda libre para un turno en el acto.

Resolver inscripciones, créditos y reversas económicas —pasos 6 y 7 de RF-M28-006— es de 08.02 y
08.07: hoy no hay inscripciones que resolver. Notificar a participantes, de 08.02.

### 3.4 Leer

- `GET .../clases/{claseId}` — detalle. Permiso `clase:read`.
- `GET .../clases?desde=&hasta=` — clases de la sede en una ventana acotada.
- `GET .../clases/{claseId}/historial` — eventos.

**Ninguna respuesta lleva lista de participantes** (seguridad de la etapa: "vista pública sin lista
de participantes"). En 08.01 no existen; la decisión queda escrita para que 08.02 no la agregue
por inercia a esta proyección.

### 3.5 Agenda unificada (RF-M12-011, RF-M12-013)

`GET /api/v1/consultorios/{consultorioId}/agenda?fecha=` — **en `scheduling`**, porque es M12 y es
la agenda. Permiso `turno:read`.

Devuelve una lista de `EventoDeAgenda` **discriminada por `tipo`** (`TURNO` | `CLASE`) con los
campos comunes: id, tipo, oferta, horario, profesional, espacio, capacidad, ocupación y estado
(RF-M12-013, paso 5). Los turnos los lee el propio módulo; las clases llegan por
`EventoExternoDeAgenda`.

**Es una proyección de lectura y nada más.** No hay tabla `evento_agenda`, no hay herencia JPA, no
se migra un solo turno y las dos entidades siguen siendo entidades separadas con sus propias reglas
y sus propios comandos (RF-M12-013: "no migrar automáticamente históricos de Turno a una nueva
entidad destructiva"; el plan: "no polimorfismo prematuro destructivo"). Las operaciones se delegan
al agregado que corresponda, cada uno en su propio endpoint.

`GET .../turnos?fecha=` **no se toca**: sigue devolviendo lo que devolvía. La agenda unificada es
un endpoint nuevo.

---

## 4. Permisos

Dos códigos nuevos en `organization.domain.PermissionCode`, que es el catálogo:

| Código | Quién lo tiene |
|---|---|
| `clase:read` | mismo reparto que `turno:read`, incluido `PLATFORM_ADMIN` con alcance SOPORTE: soporte mira |
| `clase:manage` | mismo reparto que `turno:manage` — `ORG_ADMIN` (organización), y con alcance de sede `CONSULTORIO_ADMIN`, `ADMINISTRATIVO` y `PROFESIONAL`. **`PLATFORM_ADMIN` no lo recibe**: programar una clase es una acción operativa del centro, y la matriz §32 le dice "No" a todas las filas operativas |

`activity` no importa `organization.domain`: declara los códigos como texto en
`activity.domain.PermissionCodes` y los resuelve por `organization.spi.PermissionGuard`, igual que
`scheduling`, `resource` y `person`.

---

## 5. Contrato

**Aditivo.** Siete operaciones nuevas, ninguna existente cambia de forma. `0.33.0` → **`0.40.0`**
(el salto lo fija la etapa: `0.34`–`0.39` están tomadas por etapas en vuelo en otros worktrees).

Las respuestas 204 sin cuerpo —no hay en esta etapa— y todas las demás declaran
`application/json` **y** `application/problem+json` en `produces`, o el cliente generado come un
406 antes de entrar al método.

Un `ActivityProblemHandler` propio mapea las excepciones del módulo. **Nunca en
`GlobalExceptionHandler`**: eso obligaría a `platform.api` a importar `activity.domain` y cerraría
un ciclo.

Tipos de problema nuevos: `clase-no-programable` (oferta no grupal / no vigente), y
`clase-transicion-no-permitida`. Se **reusan** `not-found`, `recurso-ocupado`, `slot-no-disponible`
e `idempotency-key-conflict`: son la misma situación y publicar un segundo código para cada una
obligaría al cliente a manejar dos.

---

## 6. Migración

**`V58`.** `V51`/`V52` (06.03), `V53` (06.06), `V54` (07.03), `V55` (06.04), `V56` (07.04) y `V57`
(07.05) están tomadas por etapas en vuelo. El número se reservó **antes** de escribir código:
`V26` quedó vacía para siempre porque dos etapas nacieron con el mismo número y Flyway no arranca
con versiones duplicadas.

Dos tablas nuevas, ninguna alteración de tabla existente, ningún backfill. Reversible por `DROP`.

---

## 7. Alcance — qué entra y qué no

| Entra en 08.01 | Queda para |
|---|---|
| `ClaseProgramada`: crear, reprogramar, cancelar, historial | — |
| Capacidad efectiva (min de clase, oferta y espacio) | — |
| Exclusión mutua clase ↔ turno bajo el lock de `agenda_sede` | — |
| Agenda unificada discriminada (RF-M12-013) | — |
| Inscripciones, cupos ocupados, lista de espera (RF-M12-010, RF-M28-002..004) | **08.02** |
| Notificar a inscriptos al reprogramar/cancelar | **08.02** |
| Asistencia individual y consecuencia económica (RF-M28-007, RF-M28-009) | **08.03** |
| Derivación al circuito clínico (RF-M28-008) | **08.04** |
| Créditos y reversas al cancelar | **08.07** |
| Frontend de la agenda unificada y de clases | tras regenerar el contrato |

---

## 8. Lo que esta etapa NO verifica

Docker no arranca en esta máquina, así que:

- **La exclusión concurrente entre una clase y un turno no se ejerció contra MySQL real.** Es
  precisamente lo que un mock no puede probar: un mock que devuelve cero filas no reproduce el
  gestor de locks de InnoDB. Queda anotado en `docs/tests-diferidos.md` con etapa destino, **no
  simulado**.
- **`V58` nunca se aplicó contra un motor.**
- **El contrato no se regeneró**: `OpenApiContractIT` queda en drift, declarado y esperado. El YAML
  **no se editó a mano** para taparlo.
