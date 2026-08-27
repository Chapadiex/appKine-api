# AKINE-02.07 — Habilitación de profesionales y espacios por Oferta

**Fecha:** 27/08/2026. **Depende de:** 02.02 (espacios), 02.04 (disponibilidad), 02.06 (oferta).
**Módulo propietario:** `offering`.

---

## 0. Lo que esta etapa es, en una frase

Decir **quién** puede prestar cada oferta y **dónde** puede prestarse, con vigencia propia y sin
inferirlo del nombre de nada.

---

## 1. Qué queda de 02.07 después de 02.06

El plan lista nueve RF para esta etapa. **Cuatro ya están cerrados** y conviene decirlo antes de
volver a construirlos:

| RF | Estado | Dónde |
|---|---|---|
| RF-M27-004 — comportamiento clínico | ✅ **cerrado en 02.06** | `requiereCasoClinico` y `generaRegistroClinico` de la Oferta |
| RF-M27-005 — modalidad y capacidad | ✅ **cerrado en 02.06** | `modalidad` y `capacidad` de la Oferta |
| RF-M27-008 — vigencia y baja | ✅ **cerrado en 02.06** | Ventana de vigencia + baja lógica con motivo |
| RF-M27-007 — cobertura y esquema de cobro | 🔶 **declarado, no resuelto** | `admiteObraSocial` y `esquemaCobro` se guardan; nadie los interpreta porque M15/M16/M18 no existen (RN-M27-006). **Esta etapa no lo avanza**: resolverlo sin aranceles ni convenios sería inventar la regla |
| **RF-M27-006 — profesionales y espacios habilitados** | ⬜ **es el objetivo** | Nuevo |
| **RF-M04-008 — espacios por oferta** | ⬜ **es el objetivo** | Nuevo |
| RF-M04-009 — validar capacidad física al reservar | 🔶 **la mitad que se puede** | Se puede calcular la **capacidad efectiva**; *reservar* es de la etapa de agenda |
| **RF-M05-007 — servicios habilitados por profesional** | ⬜ **es el objetivo** | Nuevo, es la otra cara de RF-M27-006 |
| RF-M05-008 — validar habilitación al programar | 🔶 **la mitad que se puede** | Se publica un endpoint de validación explicable; *programar* es de agenda |
| RF-M05-009 — disponibilidad por consultorio y servicio | ⬜ **fuera de alcance, ver §8** | Cruza disponibilidad con habilitación y no tiene consumidor hasta que exista el turno |

---

## 2. Decisiones

### 2.1 Dos tablas, no una

`oferta_profesional_habilitado` y `oferta_espacio_habilitado`. **No** una tabla polimórfica con
`tipo_recurso`, aunque las dos tengan casi las mismas columnas.

Un profesional y un espacio no son el mismo tipo de cosa: uno se resuelve contra `membership` con
su rol y su disciplina, el otro contra `espacio` con su capacidad física. Una tabla única obliga a
una FK nullable por cada destino, hace imposible el constraint que impide habilitar dos veces el
mismo recurso, y convierte cada consulta en un `WHERE tipo = ...` que el índice tiene que cargar.
Lo que se ahorra —una tabla— no compensa lo que se pierde.

### 2.2 Ausencia de habilitaciones significa "todos", no "ninguno"

**Es la decisión con más consecuencias de la etapa.**

Una oferta recién creada no tiene ninguna fila de habilitación. Si eso significara "ningún
profesional puede prestarla", toda oferta nacería inutilizable y el alta de 02.06 —que
deliberadamente no pide quince datos— quedaría rota: habría que configurar habilitaciones antes de
poder usar lo que se acaba de crear.

Entonces: **lista vacía = sin restricción**. En cuanto se agrega la primera habilitación, la oferta
pasa a estar restringida y sólo los habilitados pueden prestarla. Volver a lista vacía vuelve a
"todos", y eso es coherente: quitar la última restricción es quitar la restricción.

> **El riesgo de esto está en el otro lado y hay que nombrarlo:** un administrador que cree estar
> restringiendo y borre la última fila abre la oferta a todos sin darse cuenta. Por eso la API
> devuelve siempre `restringida: true|false` explícito y la pantalla lo dice con palabras, en vez
> de dejar que el usuario deduzca el significado de una tabla vacía.

### 2.3 Habilitación ≠ permiso

El plan lo pide literalmente y el modelo lo cumple estructuralmente: estas tablas **no otorgan
ningún permiso** y no se consultan nunca desde `PermissionGuard`. Un profesional habilitado para
una oferta sigue necesitando su `membership` vigente para entrar, y un `ORG_ADMIN` sin habilitación
sigue pudiendo administrar la oferta aunque no pueda prestarla.

Dicho al revés: **habilitación responde "puede prestar esto"; permiso responde "puede tocar esto"**.
Se cruzan en la agenda, que exigirá las dos.

### 2.4 Capacidad efectiva = mínimo, calculado al leer

`capacidadEfectiva = min(oferta.capacidad, min(capacidad de los espacios habilitados))`.

Cuando no hay espacios habilitados —oferta sin restringir— la efectiva es la de la oferta: no hay
ningún espacio concreto contra el cual acotarla todavía.

**Se calcula al leer y no se materializa**, con el mismo criterio que la disponibilidad efectiva de
02.04. Guardarla obligaría a recalcularla cada vez que cambia la capacidad de un espacio, en filas
de otro módulo, y la primera vez que alguien olvide hacerlo la agenda va a sobrevender un box.

### 2.5 La habilitación tiene vigencia propia

`validFrom` / `validUntil` en las dos tablas, con `validUntil` **exclusivo** y NULL como "sin fin
previsto", igual que en espacio, oferta y disponibilidad. Un profesional que se va tres meses no se
borra de las habilitaciones: se le pone fin de vigencia y el histórico sigue explicando por qué
atendió lo que atendió.

### 2.6 Sin permisos nuevos

`consultorio:manage` sobre la sede de la oferta, igual que 02.06. Configurar quién presta qué es
administrar la sede.

---

## 3. Modelo de datos — migración `V26`

Las dos tablas llevan `organization_id NOT NULL` y todos sus índices empiezan por él (ADR-0004).
**No hay excepción que declarar acá**: a diferencia de `servicio`, una habilitación pertenece
siempre a un tenant, porque la oferta pertenece a uno.

### 3.1 `oferta_profesional_habilitado`

| Columna | Tipo | Nota |
|---|---|---|
| `id` | bigint PK | |
| `organization_id` | bigint NOT NULL | FK a `organization` |
| `consultorio_id` | bigint NOT NULL | Redundante con la oferta **a propósito**: los índices de la agenda van a consultar por sede sin pasar por `oferta` |
| `oferta_id` | bigint NOT NULL | FK a `oferta_servicio_consultorio` |
| `membership_id` | bigint NOT NULL | FK a `membership`. **El profesional se identifica por membership y no por cuenta**: la misma persona puede ser profesional en un centro y administrativa en otro |
| `valid_from` / `valid_until` | datetime(6) | `valid_until` exclusivo, NULL = sin fin |
| `active` / `deleted_at` / `deactivation_reason` | | Baja lógica con motivo, con su check de coherencia |
| `deleted_key` | datetime(6) GENERATED | Centinela para el UNIQUE, mismo patrón que espacio y catálogo |
| `version`, `created_at`, `updated_at` | | |

`UNIQUE (organization_id, oferta_id, membership_id, deleted_key)` — un profesional no se habilita
dos veces vigente para la misma oferta, y el de una habilitación dada de baja se puede volver a
habilitar.

### 3.2 `oferta_espacio_habilitado`

Idéntica cambiando `membership_id` por `espacio_id` (FK a `espacio`) y su UNIQUE correspondiente.

### 3.3 Lo que NO se modela, y por qué

- **Disciplina del profesional por habilitación.** RF-M05-007 nombra "disciplinas y servicios". La
  disciplina ya vive en el catálogo clínico (`especialidad`, 02.05) y atarla acá la duplicaría. Lo
  que esta etapa habilita es la **oferta**, que ya cuelga de un servicio; si más adelante hace
  falta habilitar por disciplina entera, es una regla derivada, no una tabla nueva.
- **Prioridad u orden entre habilitados.** Ningún RF lo pide y sería la semilla de un asignador
  automático que nadie especificó.

---

## 4. Contrato — OpenAPI 0.13.0

Cambio **aditivo**: bump menor.

| Método | Path | Autorización |
|---|---|---|
| `GET` | `/api/v1/consultorios/{cid}/ofertas/{oid}/habilitaciones` | pertenencia |
| `PUT` | `/api/v1/consultorios/{cid}/ofertas/{oid}/habilitaciones/profesionales` | `consultorio:manage` |
| `PUT` | `/api/v1/consultorios/{cid}/ofertas/{oid}/habilitaciones/espacios` | `consultorio:manage` |
| `GET` | `/api/v1/consultorios/{cid}/ofertas/{oid}/validacion` | pertenencia |

**Los dos `PUT` reemplazan el conjunto completo, no agregan de a uno.** Es lo que la pantalla
necesita —una lista de casillas que se guarda entera— y evita que el cliente tenga que diffear
para saber qué crear y qué dar de baja. El servidor hace el diff: lo que entra y no estaba se crea,
lo que estaba y no entra se da de baja **con motivo automático**, y lo que sigue no se toca. Cada
`PUT` lleva `expectedVersion` de la **oferta**, que es lo que serializa dos administradores
editando la misma configuración.

`GET /validacion` responde la pregunta de RF-M05-008 y RF-M04-009 de forma **explicable**: dado un
`membershipId` y/o un `espacioId` opcionales, dice si esa combinación puede prestar la oferta y,
si no, **cuál de las condiciones falla** —oferta inactiva, fuera de vigencia, profesional no
habilitado, espacio no habilitado, espacio fuera de servicio, capacidad insuficiente—. Es el
endpoint que la agenda va a consumir; publicarlo ahora lo deja probado antes de que exista el
turno.

Reglas heredadas, sin excepción: cross-tenant → **404**, falta de contexto → **403**, el 409 de
concurrencia con `type` **`conflict`**.

---

## 5. Frontend

Una pantalla nueva colgando de la oferta: **`HabilitacionesDeLaOfertaPage`**, en la feature
`offering` que ya existe. Dos listas de casillas —profesionales de la sede y espacios de la sede—
con el estado "sin restringir" dicho **con palabras** y no como una tabla vacía, según §2.2.

La capacidad efectiva se muestra al lado de la comercial cuando difieren, con la explicación de
cuál espacio la está limitando. Un número más chico sin explicación es un bug reportado.

---

## 6. Design challenge

**1. Ownership.** `offering` es propietario de las dos tablas. Nadie más las toca. Lee `espacio`
por `resource.spi.EspacioDirectory` y `membership` por
`organization.spi.ConsultorioMembershipDirectory`, que ya existen y ya se usan.

**2. Ciclos.** `offering → resource.spi` y `offering → organization.spi`. Las dos direcciones ya
existían en el proyecto y nadie importa `offering`. Unidireccional; ArchUnit lo cubre sin cambios.

**3. Tenant.** Las dos tablas llevan `organization_id NOT NULL` y sus índices y uniques empiezan
por él. Sin excepción que declarar, a diferencia de ADR-0023.

**4. Reglas maestras.** La 21–23 son el objetivo. **Habilitación ≠ permiso** se cumple
estructuralmente: estas tablas no se consultan desde `PermissionGuard` y no hay ningún camino por
el que puedan otorgar acceso. La 15 y la 20 se cumplen porque no hay ninguna condición por nombre:
todo sale de filas explícitas.

**5. Baja lógica.** `active` / `deleted_at` / `deactivation_reason` con check de coherencia en las
dos. Quitar una habilitación no borra: cierra. El histórico tiene que poder explicar por qué un
profesional atendió una oferta el año pasado.

**6. Contrato.** Aditivo. `0.12.0 → 0.13.0`.

**7. Ruta crítica.** Las tres dependencias declaradas —02.02, 02.04 y 02.06— están cerradas. Lo que
esta etapa **no** puede cerrar es todo lo que necesita un Turno, y por eso RF-M04-009 y RF-M05-008
entregan su mitad calculable y no la de reserva.

**8. El caso que rompe el diseño.** Un espacio habilitado para una oferta **se da de baja** en
`resource`, que no sabe nada de `offering`. La habilitación queda apuntando a un espacio inactivo.

No se resuelve con una FK ni con un trigger: `resource` no puede depender de `offering` sin cerrar
un ciclo, y cascadear la baja borraría el histórico que RN-M03-006 protege. Se resuelve **al leer**,
igual que la capacidad efectiva: la habilitación sigue existiendo y `GET /validacion` responde que
ese espacio no está en servicio, nombrando la condición que falla. La pantalla lo muestra como una
fila tachada con su motivo, no la esconde: esconderla dejaría al administrador sin entender por qué
la capacidad efectiva cambió sola.

El caso simétrico —el `membership` de un profesional habilitado se desvincula— se resuelve igual, y
además ya tiene su costura: `ColaboradorDesvinculacionProbe` existe desde 02.03 y esta etapa la
implementa para que la desvinculación **avise** cuántas habilitaciones deja colgando, sin
bloquearla.

---

## 7. Lo que esta etapa deja abierto

| Pendiente | Destino |
|---|---|
| Reservar validando habilitación y capacidad (RF-M04-009, RF-M05-008 completos) | La etapa de agenda |
| RF-M05-009 — disponibilidad cruzada con servicio | Necesita el turno para tener consumidor; ver §8 |
| RF-M27-007 — resolver el esquema de cobro | M15 / M16 / M18 |
| Habilitación por disciplina entera | Regla derivada, si algún RF la pide |

## 8. Por qué RF-M05-009 queda afuera

Pide que "la disponibilidad general del profesional se combine con restricciones específicas de
servicios cuando el consultorio lo requiera": una segunda capa de disponibilidad, por oferta,
encima de la de 02.04.

Se deja afuera por dos razones y las dos son de diseño, no de tiempo. **No tiene consumidor**: sin
Turno, nadie puede leer esa disponibilidad cruzada para nada, y construirla ahora es materializar
una regla que no se puede probar contra ningún caso real. Y **02.04 dejó fijado que la
disponibilidad efectiva se calcula al leer**; agregarle una dimensión antes de que exista quien la
lea multiplicaría los casos de ese cálculo sin un solo escenario que los valide.

Cuando exista el turno, esta etapa ya le deja lo que necesita: quién está habilitado, dónde, y con
qué capacidad efectiva.
