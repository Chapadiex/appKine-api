# AKINE-08.02 — Inscripciones, cupos y lista de espera (M28, M12, M26)

Diseño de la etapa. **La revisión adversarial está en `AKINE-08.02-challenge.md` y manda sobre
este documento.**

Trazabilidad: RF-M28-002, RF-M28-003, RF-M28-004; RF-M12-010; RF-M26-006, RF-M26-007;
RN-M28-003, RN-M28-004, RN-M28-005, RN-M28-009. Sale de AKINE-08.01 (módulo `activity`,
`clase_programada`, `clase_evento`, `V58`) y de AKINE-03.01 (Persona).

---

## 1. El problema que decide toda la etapa

**Un cupo es una reserva atómica.** N inscripciones simultáneas sobre la última vacante es el
único caso que define si la etapa sirve; todo lo demás es formulario.

Y tiene una segunda mitad, que es la misma carrera al revés: **cuando alguien se da de baja se
libera un lugar, y promover a alguien de la lista de espera no puede promover dos veces a la
misma persona ni dejar la vacante sin promover.**

### 1.1 Por qué `SELECT count(*)` + `INSERT` está mal, y no "un poco mal"

Ningún unique puede expresar "quedan vacantes": no es un valor de columna, es un **conteo contra
un tope**. La tentación es contar y después insertar. Entre las dos sentencias hay una ventana, y
la ventana **es** el bug: dos transacciones cuentan 7 de 8, las dos insertan, la clase queda en 9.
No falla nada. Es el peor tipo de defecto.

Envolverlo en `synchronized` no sirve (hay más de un proceso), y bajo `REPEATABLE READ` tampoco
sirve leer de nuevo: InnoDB fija la foto en la primera lectura consistente.

### 1.2 La forma que sí lo expresa: el cupo se otorga con un `UPDATE` condicional

Es el patrón que el repositorio ya usa cuatro veces para "una resta que no puede pasar de cero":
07.02 al imputar contra el saldo de un cobro, 04.05 con la última unidad de una autorización,
07.03 con el saldo de caja y 07.04 con el del lote.

```sql
UPDATE clase_programada
   SET cupo_ocupado = cupo_ocupado + 1
 WHERE id = :claseId
   AND organization_id = :organizationId
   AND deleted_at IS NULL
   AND estado = 'PROGRAMADA'
   AND cupo_ocupado < LEAST(capacidad, :capacidadEfectiva)
```

**Una fila afectada es "tenés el lugar". Cero filas es "no hay lugar".** No hay lectura previa que
quede vieja: el `UPDATE` toma el lock exclusivo de la fila y lee la versión **commiteada más
reciente** —current read—, así que la segunda transacción evalúa su predicado contra el valor que
la primera ya escribió y su `WHERE` no da. No hay ventana porque no hay dos sentencias.

Los dos términos del `LEAST` están ahí por motivos distintos y ninguno sobra:

- **`capacidad`** es la columna de la propia fila y es el techo que **ningún llamador puede
  saltearse**. Un bug futuro que pase una capacidad efectiva inflada sigue sin poder sobrevender.
- **`:capacidadEfectiva`** es el `min(clase, oferta, espacio)` que 08.01 calcula al leer
  (RN-M28-002). No puede vivir en el `WHERE` como columna porque la capacidad de la oferta y la
  del box son de **otros módulos**: ninguna base puede comprobarlo sola y decirlo así es más
  honesto que fingir que sí.

### 1.3 El contador es el asignador, no una caché

`clase_programada.cupo_ocupado` **no es un resumen de las inscripciones**: es quien **otorga** el
lugar. La fila de `inscripcion_clase` es el recibo, no la fuente. Es exactamente la forma de 04.05
—`autorizacion.cantidad_consumida` más el ledger de movimientos— y tiene el mismo invariante
verificable:

> `cupo_ocupado` == cantidad de inscripciones de esa clase en un estado que consume cupo.

**Esto revierte una decisión escrita de 08.01**, que dijo que la ocupación se contaría al leer y
que materializarla sería una segunda copia de la verdad. Ver la pregunta 4 del challenge: el
motivo está ahí y es que sin columna la regla no se puede expresar en la base.

### 1.4 La segunda mitad: la promoción desde la lista de espera

Dar de baja una inscripción que consume cupo hace, en este orden y en **una sola transacción**:

```
1. transición condicional de la inscripción:
   UPDATE inscripcion_clase SET estado='CANCELADA', ... WHERE id=? AND estado IN (los que consumen)
   → 0 filas = ya estaba cancelada = idempotente, se corta acá
2. liberar el lugar:
   UPDATE clase_programada SET cupo_ocupado = cupo_ocupado - 1 WHERE id=? AND cupo_ocupado > 0
   ← ACÁ SE TOMA EL LOCK EXCLUSIVO DE LA FILA DE LA CLASE, y se retiene hasta el commit
3. leer la cabeza de la cola (ya bajo ese lock)
4. tomar el lugar para ella con el MISMO UPDATE condicional del punto 1.2
5. promoverla con una transición condicional:
   UPDATE inscripcion_clase SET estado='RESERVADA', ... WHERE id=? AND estado='LISTA_ESPERA'
   → 0 filas = otro ya la promovió = se devuelve el lugar y no se promueve a nadie
6. encolar el aviso en el outbox, dentro de esta misma transacción
```

**Dos bajas simultáneas se serializan en el paso 2**, porque las dos escriben la misma fila de
`clase_programada`. La segunda espera, y cuando entra lee la cola **ya sin** la persona que la
primera promovió. Por eso el paso 3 va **después** del paso 2 y no antes: leer la cola antes de
tomar el lock es leer una foto vieja, que es la misma clase de error que leer antes de bloquear.

El paso 5 es **defensa en profundidad**: hoy el lock ya lo garantiza, pero cualquier camino futuro
que promueva sin haber pasado por el paso 2 —una pantalla de "ofrecer la vacante a mano", un job—
sigue sin poder promover dos veces a la misma persona. Una promoción doble no es un error
cosmético: es una persona a la que se le prometió un lugar que no existe.

### 1.5 Aislamiento y orden de locks

- **`READ_COMMITTED` en las tres mutaciones** (inscribir, cancelar, promover). Con
  `REPEATABLE READ` el `UPDATE` seguiría siendo correcto —es current read— pero **la lectura de la
  cola del paso 3 devolvería la foto anterior al lock**, y volveríamos a promover a alguien que ya
  fue promovido. Es la misma razón por la que 05.02 y 08.01 están en `READ_COMMITTED`.
- **La inscripción NO toma el lock de `agenda_sede`.** Inscribir no ocupa un box ni un profesional:
  la clase ya los ocupó en 08.01 y el cupo no cambia la agenda. Y sobre todo: tomarlo sería una
  **inversión de orden de locks**. `ClaseService` toma `agenda_sede` y después la fila de la clase;
  si la inscripción tomara la fila de la clase y después `agenda_sede`, habría deadlock. El orden
  total es **`agenda_sede` → `clase_programada` → `inscripcion_clase`**, siempre, y esta etapa sólo
  usa el tramo de la derecha.
- **No hace falta crear ninguna fila-lock nueva**, y por eso no se repite el `INSERT ... ON
  DUPLICATE KEY UPDATE` en transacción aparte: la fila que se bloquea es la de la clase, que ya
  existe siempre —no se puede inscribir en una clase que no está—. La trampa que se pagó cuatro
  veces no aplica acá porque no hay creación perezosa.

### 1.6 Lo único que un lock no arregla: bajar la capacidad con gente adentro

`ClaseService.reprogramar` puede bajar la capacidad, y 08.01 ya rechaza bajarla por debajo de la
ocupación —hoy leyendo cero—. Encenderla no alcanza: lee la ocupación con un `SELECT` plano y
después escribe, así que una inscripción concurrente puede meterse en el medio y dejar
`cupo_ocupado > capacidad`.

**Arreglo: `reprogramar` y `cancelar` de clase leen la fila `FOR UPDATE`.** Ahí el `UPDATE`
condicional de la inscripción se queda esperando y, cuando entra, evalúa `LEAST(capacidad, ...)`
contra la capacidad **nueva**. Es una línea y cierra el último hueco.

---

## 2. Modelo

### 2.1 `inscripcion_clase` — propietario `activity`

Una fila por participante y por clase (RN-M28-003). **No es un turno** y ninguna fila de `turno`
la acompaña (CA-M28-001-06).

| Columna | Por qué |
|---|---|
| `organization_id`, `consultorio_id`, `clase_id` | tenant, sede y clase. `consultorio_id` va aunque sea derivable de la clase: sin él, filtrar por sede obliga a un join y habilita la consulta sin join que ningún test de una etapa detecta |
| `persona_id` | FK a `persona`. **Una Persona, no un Paciente**: inscribirse en Pilates no convierte a nadie en paciente (RF-M07-010) |
| `estado` | los seis de RN-M28-004: `RESERVADA`, `CONFIRMADA`, `ASISTIO`, `AUSENTE`, `CANCELADA`, `LISTA_ESPERA` |
| `posicion_espera` | posición de ingreso a la cola. Se **conserva** después de promover: es la prueba de que la prioridad se respetó |
| `inscripto_por_cuenta_id`, `inscripto_en` | actor e instante del alta |
| `confirmada_en`, `promovida_en` | instantes de las transiciones |
| `motivo_cancelacion`, `cancelada_en`, `cancelada_por_cuenta_id` | RF-M28-003: cancelar conserva historial |
| `idempotency_key` + `request_hash` | inscribir es retry-safe. El hash desde el primer día, para no repetir la deuda 7b |
| `deleted_at` + `deleted_key` generada | baja lógica. El centinela `'1970-01-01'` existe porque varios `NULL` no colisionan en MySQL |
| `version` | control optimista |

**Uniques**, los dos encabezados por `organization_id`:

- `uk_inscripcion_idempotencia (organization_id, idempotency_key)` — alcance organización, igual
  que el de clases y turnos: una clave reusada apuntando a otra clase sigue siendo la misma clave.
- `uk_inscripcion_clase_persona (organization_id, clase_id, persona_id, deleted_key)` — **la
  unicidad clase-persona activa** que pide el plan. Con `deleted_key` adentro, una persona que se
  dio de baja puede volver a inscribirse y su fila anterior queda. Es el mismo mecanismo que
  protege el histórico en todo el repositorio.

Índices: `(organization_id, clase_id, estado, posicion_espera, id)` —la cola, que es la consulta
que decide la prioridad— y `(organization_id, persona_id, id)` para "en qué clases estoy".

### 2.2 `clase_programada` gana dos columnas (ALTER en `V60`)

| Columna | Por qué |
|---|---|
| `cupo_ocupado INT NOT NULL DEFAULT 0` | el **asignador** del punto 1.3, no una caché |
| `ultima_posicion_espera INT NOT NULL DEFAULT 0` | el correlativo de la cola |

Más un `CHECK (cupo_ocupado >= 0 AND cupo_ocupado <= capacidad)` como **red de contención**: no es
quien hace cumplir la regla —eso es el `WHERE`— pero convierte un sobreventa que se escapara por
cualquier camino futuro en un fallo ruidoso en vez de en una clase con nueve personas en ocho
lugares.

**`cupo_ocupado` se escribe por SQL nativo y NO toca el `@Version` de la clase.** Es deliberado: si
cada inscripción hiciera avanzar la versión de la clase, el formulario de reprogramación que un
administrativo tiene abierto se invalidaría cada vez que alguien se anota, y comería un 409 que no
tiene nada que ver con él.

### 2.3 La posición en la cola se asigna con `+ 1`, nunca con `MAX + 1`

```sql
UPDATE clase_programada SET ultima_posicion_espera = ultima_posicion_espera + 1 WHERE ...
```
y después se lee el valor, bajo el lock que ese mismo `UPDATE` acaba de tomar. Es la regla del
repositorio —la misma de los correlativos de sesión, de caso y de comprobante— y el motivo es
idéntico: `MAX + 1` sobre una tabla donde las filas se pueden cancelar repite números y, sobre una
donde no, sigue teniendo la ventana entre el `SELECT` y el `INSERT`.

El contador **nunca baja**: una posición usada no se reutiliza aunque la persona cancele. Que las
posiciones tengan huecos es correcto; que dos personas tengan la 4 no lo es.

**Y la idempotencia se evalúa ANTES de pedir número y antes de pedir lugar.** Un reintento no
consume una posición ni una vacante.

### 2.4 Qué estados consumen cupo

| Estado | Consume | Por qué |
|---|---|---|
| `RESERVADA` | sí | tiene el lugar |
| `CONFIRMADA` | sí | ídem, confirmada por mostrador |
| `ASISTIO` | sí | vino y lo usó (08.03) |
| `AUSENTE` | sí | **no vino, pero el lugar estuvo reservado y nadie más pudo usarlo.** Liberarlo retroactivamente falsearía el histórico de ocupación (08.03) |
| `LISTA_ESPERA` | no | RN-M28-005 |
| `CANCELADA` | no | liberó |

La función vive en el enum `EstadoInscripcion.consumeCupo()`, en `domain`, **en un solo lugar**.

### 2.5 No hay tabla `inscripcion_evento`, y es una decisión

Una clase se puede reprogramar N veces y por eso 08.01 le dio historial append-only. **Una
inscripción tiene un ciclo lineal y acotado** —se crea, a lo sumo se promueve, a lo sumo se
confirma, a lo sumo se marca asistencia, a lo sumo se cancela— y cada transición ocurre **una sola
vez**. Columnas con instante y actor lo expresan sin perder nada, y una segunda tabla sólo
agregaría una escritura por operación.

La trazabilidad completa la da además `AuditTrail`, que registra **cada** transición **dentro de la
transacción del negocio** —nunca post-commit, que deja la mutación hecha y sin rastro—.

---

## 3. Operaciones

Cinco, todas bajo `/api/v1/consultorios/{consultorioId}/clases/{claseId}`.

### 3.1 Inscribir (RF-M28-002)

`POST .../inscripciones`, con `Idempotency-Key` opcional. Permiso `inscripcion:manage`.

Cuerpo: `personaId`, `aceptaListaEspera` (default `false`), `idempotencyKey`.

Orden: contexto (403) → sede del tenant (404) → permiso (403) → **idempotencia** (409 si la clave
se reusó con otro cuerpo; 200 con la inscripción anterior si el cuerpo es el mismo) → clase viva y
`PROGRAMADA` (404 / 409) → clase no empezada (409) → persona del tenant y ficha activa (404 / 409)
→ **no duplicada** (409) → **`UPDATE` condicional del cupo**.

Desenlaces:

| | Resultado |
|---|---|
| el `UPDATE` afectó 1 fila | `RESERVADA`, con el lugar tomado. **201** |
| 0 filas y `aceptaListaEspera = true` | `LISTA_ESPERA` con posición asignada. **201** |
| 0 filas y `aceptaListaEspera = false` | **409 `clase-completa`**, con `capacidadEfectiva` y `ocupados` en el problema para que la pantalla pueda ofrecer la espera sin volver a preguntar |

El duplicado se comprueba con una consulta **y** con el unique. Las dos: la consulta da el 409
explicable, el unique cubre la carrera entre dos altas simultáneas de la misma persona.

### 3.2 Confirmar (RN-M28-004)

`POST .../inscripciones/{inscripcionId}/confirmacion`. `RESERVADA` → `CONFIRMADA`.
**No toca el cupo**: los dos estados lo consumen, así que la transición no otorga ni libera nada.
Idempotente: confirmar una confirmada devuelve la misma fila sin auditar de nuevo.

### 3.3 Cancelar (RF-M28-003)

`POST .../inscripciones/{inscripcionId}/cancelacion`, con motivo obligatorio. La secuencia
completa está en §1.4.

- **Idempotente**: una inscripción ya cancelada se devuelve tal cual, sin liberar un segundo lugar
  y sin promover a nadie. Cuando 08.07 cuelgue de acá la devolución de créditos, esa idempotencia
  es lo que impide devolver dos veces.
- **Cancelar una inscripción no cancela la clase ni a los demás** (CA-M28-003-06).
- Cancelar a alguien que estaba en `LISTA_ESPERA` no libera nada —no tenía lugar— y no promueve.
- La respuesta dice **si hubo promoción y a quién**, porque el mostrador que dio la baja es quien
  tiene que avisarle a la persona que entró.

### 3.4 Participantes (seguridad de la etapa)

`GET .../inscripciones`. Permiso **`inscripcion:read`**, que es distinto de `clase:read`.

08.01 decidió que **ninguna respuesta de clase lleva lista de participantes** y esta etapa **no lo
deshace**: `ClaseResponse` sigue sin nombres. La lista vive en su propio endpoint, con su propio
permiso, y ahí es donde se decide quién la ve. CA-M26-006-06 —"notificar sin exponer la lista
completa"— sale de la misma decisión: cada aviso va a un destinatario y nunca nombra a otro.

### 3.5 Cupos (RF-M12-010)

`GET .../cupos`. Permiso `clase:read`. Devuelve `capacidad`, `capacidadEfectiva`, `ocupados`,
`disponibles` y `enEspera`. Es lo que sostiene CA-M12-010-06: "la agenda muestra 6/8 y admite
exactamente dos confirmaciones adicionales".

**No lleva ningún dato de persona.** Es la misma proyección que ve un paciente en una pantalla
pública futura.

### 3.6 Lo que cambia en las operaciones de 08.01

- `contarOcupacion(clase)` deja de devolver `0` y devuelve `clase.getCupoOcupado()`. Es el único
  lugar que 08.01 dejó preparado, y por eso el cambio es de una línea: la regla de no bajar la
  capacidad por debajo de la ocupación **se enciende sin escribir nada nuevo**.
- `reprogramar` lee la clase **`FOR UPDATE`** (§1.6) y, si cambió el horario, **encola un aviso por
  cada inscripto** (RF-M26-006).
- `cancelar` clase resuelve lo que 08.01 difirió: **cancela todas las inscripciones vivas**, pone
  `cupo_ocupado = 0` para que el invariante siga cerrando, y avisa a cada una. **No promueve a
  nadie** —no hay clase a la que promover— y **no devuelve créditos ni plata**: eso es 08.07.

---

## 4. Notificaciones (RF-M26-006, RF-M26-007)

Dos tipos nuevos en `notification.spi.NotificationType`, ninguno con enlace seguro:

| Tipo | Cuándo |
|---|---|
| `CLASE_MODIFICADA` | la clase se reprogramó o se canceló |
| `CUPO_LIBERADO` | la persona fue promovida desde la lista de espera |

Se encolan por `NotificationOutbox`, **dentro de la transacción del negocio** (propagación
`MANDATORY`): si el negocio no comitea, el aviso no sale; si comitea, el aviso existe. Es el
patrón que 01.02 dejó fijado y la única forma de que no se mande un mail sobre algo que no pasó.

La **clave idempotente** se deriva del hecho, nunca del reloj: `clase-modificada:{claseId}:{inscripcionId}:{instante de la reprogramación}` y
`cupo-liberado:{inscripcionId}`. Reintentar la transacción no manda dos mails.

El correo de la persona **no está en `PacienteSnapshot`**, y eso es deliberado de 03.01: ese
snapshot lo consumen módulos clínicos y RN-M09-003 les prohíbe copiarse los datos de contacto. Se
agrega un puerto **aparte**, `person.spi.ContactoDirectory`, que devuelve sólo lo que hace falta
para redactar un aviso. Separado y no agregado al snapshot existente: así un consumidor clínico
sigue sin poder obtener el mail por accidente.

Tres claves nuevas en la lista blanca de `SanitizedPayload`: `claseTitulo`, `claseInicio`,
`consultorioNombre`. Agregar una clave es una decisión y queda escrita acá.

**La ventana de aceptación NO se implementa.** Ver la pregunta 7 del challenge.

---

## 5. Permisos

Dos códigos nuevos en `organization.domain.PermissionCode`:

| Código | Quién lo tiene |
|---|---|
| `inscripcion:read` | mismo reparto que `clase:read`, incluido `PLATFORM_ADMIN` con alcance SOPORTE |
| `inscripcion:manage` | mismo reparto que `clase:manage`. **`PLATFORM_ADMIN` no lo recibe**: anotar a alguien en una clase es operación del centro |

`activity` no importa `organization.domain`: los declara como texto en
`activity.domain.PermissionCodes` y los resuelve por `organization.spi.PermissionGuard`.

**El autoservicio del paciente queda afuera y no por olvido**: no existe vínculo entre `cuenta` y
`persona` en ningún lado del sistema —es un hueco declarado del repositorio, el mismo que hace que
el alcance `OWN` no esté implementado—. Sin ese vínculo, "inscribirme a mí mismo" no se puede
autorizar sin abrir "inscribir a cualquiera". Staff únicamente.

---

## 6. Contrato

**Aditivo.** `0.40.0` → **`0.42.0`**. El número lo fija la etapa y el salto no es un error de
cuenta: `0.41` queda reservada, igual que 08.01 saltó de `0.33` a `0.40` porque otras etapas en
vuelo tienen las del medio. Cinco
operaciones nuevas; ninguna existente cambia de forma. Lo único que cambia de **valor** es
`ClaseResponse.ocupados`, que deja de ser siempre `0` — el campo ya existía y ya estaba declarado.

Todas declaran `application/json` **y** `application/problem+json` en `produces`. No hay
operaciones 204 en esta etapa; la regla se cumple igual porque el `produces` es de clase.

Tipos de problema nuevos, **dos**, en `platform.spi.problem.ProblemType`:

- **`clase-completa`** — lo pide el plan explícitamente ("error específico de clase completa").
  Lleva `capacidadEfectiva` y `ocupados`, para que la pantalla ofrezca la lista de espera sin otra
  vuelta al servidor.
- **`inscripcion-transicion-no-permitida`** — la inscripción no admite esa operación.

Se **reusan**: `not-found`, `idempotency-key-conflict`, `conflict` (duplicado) y
`concurrent-modification`. Publicar un código nuevo para el duplicado obligaría al cliente a
manejar dos para el mismo desenlace.

El `ActivityProblemHandler` existente gana los mapeos. **Nunca en `GlobalExceptionHandler`**: eso
obligaría a `platform.api` a importar `activity.domain` y cerraría un ciclo.

---

## 7. Migración

**`V60`.** `V51`–`V58` están tomadas por etapas en vuelo y `V59` por 07.06. El número se reservó
**antes** de escribir código: `V26` quedó vacía para siempre porque 02.07 y 03.01 nacieron las dos
como `V26` y Flyway no arranca con versiones duplicadas.

Una tabla nueva, un `ALTER` sobre `clase_programada` y **ningún backfill**: `V58` nunca se aplicó
contra un motor, así que no hay una sola clase existente cuya ocupación haya que recalcular. Si
alguna vez la hubiera, el backfill sería
`UPDATE clase_programada c SET cupo_ocupado = (SELECT COUNT(*) ...)` y queda escrito acá por si
`V58` llega a producción antes que `V60`.

**No se edita `V58`.** Tentaba, porque nunca corrió, pero una migración publicada no se toca y la
regla no admite excepciones cómodas.

---

## 8. Alcance — qué entra y qué no

| Entra en 08.02 | Queda para |
|---|---|
| `InscripcionClase`, los seis estados, inscribir / confirmar / cancelar | — |
| El cupo como reserva atómica y la ocupación real | — |
| Lista de espera ordenada y promoción automática al liberarse un lugar | — |
| Cupos de RF-M12-010 y lista de participantes protegida | — |
| Avisos de reprogramación, cancelación y cupo liberado | — |
| Ventana de aceptación de la vacante ofrecida | **decisión del usuario** — ver challenge §7 |
| Asistencia individual (`ASISTIO` / `AUSENTE`) y su consecuencia | **08.03** |
| Derivación al circuito clínico | **08.04** |
| Créditos, reversas y devolución al cancelar | **08.07** |
| Abonos y pases que habilitan la inscripción | **08.08** |
| Integración económica de la clase | **08.09** |
| Frontend | tras regenerar el contrato |

**No se devenga nada.** Una inscripción no es una prestación: reservar un lugar no prueba que
nadie haya entrenado (RN-M28-007, DP-05). Ninguna línea de esta etapa toca `billing`.

---

## 9. Lo que esta etapa NO verifica

Docker no arranca en esta máquina, así que:

- **La última vacante disputada no se ejerció contra MySQL real**, y es precisamente lo que un mock
  no puede contestar: un repositorio falso que devuelve `0` no reproduce el gestor de locks de
  InnoDB ni la semántica de current read del `UPDATE`. **No se simula.** Queda en
  `docs/tests-diferidos.md` con etapa destino.
- **La promoción doble tampoco**, por el mismo motivo.
- **El invariante `cupo_ocupado == count(consumidores)` no se comprobó contra una base.** Es el
  gemelo exacto de la deuda que 04.05 dejó con el ledger de autorizaciones.
- **`V60` nunca se aplicó contra un motor**, y `V58` tampoco.
- **El contrato no se regeneró**: `OpenApiContractIT` queda en drift, declarado y esperado. El YAML
  **no se editó a mano** para taparlo.
