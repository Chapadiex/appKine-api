# Diseño — AKINE-04.03 · Caso Clínico y numeración contextual

> **Alcance.** El *qué* normativo está en `docs/AKINE_IMPLEMENTATION_PLAN.md` §"Etapa AKINE-04.03";
> las reglas vinculantes en `AGENT.md`. Acá va el *cómo*, y lo que la etapa decide **no** hacer.
> El desafío adversarial está en `docs/diseno/AKINE-04.03-challenge.md` y **manda sobre este
> archivo** donde discrepen.

Trazabilidad: RF-M10-001..006; RN-M10-001..007; reglas maestras 1, 2 y 3.
**RF-M10-007 y RF-M10-008 quedan fuera**, argumentado en §8.

---

## 1. El problema que esta etapa existe para resolver

La regla maestra 3 dice que **las sesiones se numeran dentro del Caso Clínico**, y hoy el sistema
las numera dentro de la **Historia Clínica**, porque DP-10 cortó 04.03 y 06.05 tuvo que colgar el
correlativo de algo que existiera. El registro de cierre de 06.05 lo dejó escrito con todas las
letras: *"cuando 04.03 llegue, el número por Caso se agrega **al lado**: renumerar sesiones
cerradas es reescribir historia clínica, y ADR-0011 lo prohíbe"*.

Esta etapa cobra esa deuda. Y la cobra **agregando**, no corrigiendo.

---

## 2. La decisión que ordena la etapa: dos correlativos que conviven

`sesion` conserva `numero_sesion` —el correlativo por Historia Clínica, que 06.05 ya asigna y que
está impreso en informes y visto por usuarios— y **gana** `numero_en_caso`, nullable.

```
sesion.numero_sesion   → correlativo por historia_clinica. Existe, no se toca, no se renumera.
sesion.caso_id         → nullable. El gancho que V33 dejó declarado en su cabecera.
sesion.numero_en_caso  → nullable. Correlativo por caso. NULL cuando la sesión no tiene caso.
```

**Por qué no se migra.** Un backfill tendría que inventar a qué caso pertenece cada sesión ya
cerrada, y no hay dato que lo decida: el caso no existía cuando se cerraron. Inventarlo es peor que
no tenerlo, porque produce un número que parece clínico y no lo es. Las sesiones anteriores a esta
etapa quedan **sin caso y sin número de caso**, que es exactamente lo que fueron.

**Por qué el viejo no se retira.** Es la regla 10 y el ADR-0011. Además hay una razón práctica: el
número por HC es el único correlativo que existe para una sesión **sin** caso, y RF-M14-002 admite
atención sin turno y sin caso.

> **Consecuencia que hay que saber leer:** a partir de esta etapa "la sesión 8" es ambigua si no se
> dice de qué. Los DTO devuelven los dos números **con nombres distintos** y ninguna pantalla puede
> mostrar uno solo sin decir cuál es.

---

## 3. Ownership: el Caso es de `clinical`, la Sesión sigue siendo de `encounter`

Esto es lo que más fácil se rompe. El Caso vive en `clinical`. `sesion` es de `encounter` y **sigue
siéndolo**: `clinical` no escribe una sola columna de esa tabla.

- `clinical` publica `spi.CasoDirectory` —existencia, vigencia y pertenencia de un caso a una
  historia— y `spi.CasoSnapshot`, pobre a propósito, sin contenido clínico. Es el mismo patrón y el
  mismo argumento que `HistoriaClinicaDirectory`.
- **`encounter` es quien asigna `numero_en_caso`**, en su propia transacción de cierre, pidiéndole
  el número a `clinical` por el `spi`. La dirección `encounter → clinical.spi` ya existe desde
  06.01; la inversa rompe ArchUnit y además sería `clinical` escribiendo en la tabla de otro.

---

## 4. Esquema

### `V47` — el Caso (propietario `clinical`)

| Tabla | Para qué |
|---|---|
| `caso_clinico` | El agregado: estado, diagnóstico, objetivo, oferta, correlativo |
| `caso_numerador` | Asignación atómica del correlativo del caso dentro de la historia |
| `caso_profesional` | El equipo tratante |
| `caso_evento` | Historial de estados, **append-only** |
| `caso_sesion_numerador` | Asignación atómica del correlativo de sesión **dentro del caso** |

**El correlativo del caso sale de `caso_numerador` con `UPDATE ... ultimo_numero + 1`, nunca de un
`MAX+1`** —dos `MAX` simultáneos dan el mismo número— y el unique
`(organization_id, historia_clinica_id, numero_caso)` es la red debajo, no el mecanismo. Es
textualmente el patrón de `sesion_numerador` (06.05).

**La fila del numerador se crea en una transacción aparte, con `INSERT ... ON DUPLICATE KEY
UPDATE`.** La creación perezosa dentro de la transacción que la bloquea produce deadlock entre las
primeras N escrituras concurrentes, y el `try/catch` no salva: atrapar una excepción de persistencia
no des-marca la transacción y Spring lanza `UnexpectedRollbackException` al commitear. **Ya se pagó
cuatro veces** en este repositorio (`agenda_sede`, `consultorio_calendario`, `sesion_numerador`,
`autorizacion_persona_lock`). No se paga una quinta.

**`estado`: `ACTIVO` | `CERRADO`.** Dos valores y nada más. No hay `SUSPENDIDO` ni `EN_PAUSA`
porque ningún RF los pide y un estado sin transición que lo produzca es modelo muerto. **La
reapertura devuelve a `ACTIVO`** y queda en `caso_evento`: RF-M10-006 pide reabrir, no un estado
nuevo.

**No hay baja lógica de caso, y es a propósito.** Cerrar **no** es borrar (RN de la etapa: "el
cierre no borra") y un caso no se elimina: si se abrió por error, se cierra con motivo. Agregar
`active` al lado de `estado` daría dos formas de que un caso "no esté", que es cómo se construye
una consulta que se olvida de una.

**`caso_evento` es append-only** —sin `version`, sin `updated_at`, sin baja— y su puerto no declara
`delete` ni `update`. Mismo diseño que `turno_evento` (05.03).

### `V48` — el gancho en la Sesión (propietario `encounter`)

`ALTER TABLE sesion ADD COLUMN caso_id BIGINT NULL, ADD COLUMN numero_en_caso INT NULL`, con
`uk_sesion_numero_en_caso (organization_id, caso_id, numero_en_caso)` y un CHECK que ata los dos:
**no puede haber `numero_en_caso` sin `caso_id`**.

Dos migraciones y no una porque son de **módulos distintos**, y la regla 1 de `AGENT.md` §4 no
admite que la migración de un módulo toque la tabla de otro. Las versiones se reservan **antes** de
escribir código, que es la lección de `V26`.

---

## 5. Reglas de negocio que el código tiene que hacer cumplir

1. **Un paciente puede tener varios casos, y más de uno activo** (RN-M10-001, RN-M10-002). **No hay
   unique de "un solo caso activo por historia".** Es tentador ponerlo y sería un bug: una rodilla
   y un hombro son dos casos legítimos al mismo tiempo.
2. **Duplicado razonable, no duplicado prohibido** (caso borde del plan: "paciente con casos
   similares"). Un alta que coincide en oferta con un caso **activo** de la misma historia se
   responde con **409 y la lista de casos candidatos**, y se confirma reenviando. Es el mismo
   mecanismo de RN-M07-001 en el alta de Persona (03.01), que ya está probado y que los usuarios de
   este sistema ya conocen. **No se rechaza de plano:** el segundo caso puede ser correcto.
3. **La necesidad de Caso la determina la Oferta efectiva** (RN-M10-006). El caso guarda
   `oferta_id` y se valida contra `offering.spi.OfertaDirectory`: oferta de la organización y
   vigente. **Lo que esta etapa NO hace es exigir caso al reservar un turno o al abrir una sesión**
   — eso es RF-M10-007 y está en §8.
4. **Cerrar exige motivo.** Sin motivo, un cierre es indistinguible de un abandono, y el historial
   deja de servir para lo único que sirve.
5. **Un caso cerrado no admite sesiones nuevas ni edición de contenido clínico**: 409. Reabrirlo sí,
   con motivo, y queda registrado.
6. **Editar exige `expectedVersion`.** Dos profesionales del equipo editando el objetivo del mismo
   caso son el caso normal, no el raro.
7. **El profesional desvinculado no desaparece del equipo** (caso borde del plan). `caso_profesional`
   lleva su propia vigencia: quien trató al paciente lo trató, y borrarlo del equipo reescribe
   historia. Lo que sí cambia es que deja de poder escribir.

---

## 6. Permisos y auditoría

Sin permisos nuevos: `hc:read` y `hc:write`, evaluados por `AutorizacionClinica` igual que el resto
del módulo — permiso + relación asistencial o **justificación declarada**, y **auditoría de la
lectura también** (DP-03).

> **"Administrativos sin contenido clínico salvo permiso explícito"**, dice el plan. Hoy eso se
> cumple por una vía indirecta y conviene decirlo: el administrativo **no tiene `hc:read`**, así
> que no llega al caso. No se inventa un permiso `caso:read` para expresarlo: sería un tercer
> permiso clínico sin una decisión de la matriz que lo respalde, y la matriz es un documento
> aprobado. **Queda anotado como pregunta para el usuario**, no resuelto acá.

Eventos nuevos: `CASO_CLINICO_OPENED`, `CASO_CLINICO_UPDATED`, `CASO_CLINICO_CLOSED`,
`CASO_CLINICO_REOPENED`, `CASO_EQUIPO_CHANGED`, `CASO_CLINICO_ACCESSED`.

---

## 7. API — contrato `0.31.0`

Aditivo puro → versión menor. **Sobre `0.30.0`, que 04.02 dejó sin regenerar** (§9).

```
POST   /api/v1/historias-clinicas/{id}/casos
GET    /api/v1/historias-clinicas/{id}/casos
GET    /api/v1/casos-clinicos/{id}
PATCH  /api/v1/casos-clinicos/{id}
POST   /api/v1/casos-clinicos/{id}/cierre
POST   /api/v1/casos-clinicos/{id}/reapertura
PUT    /api/v1/casos-clinicos/{id}/equipo
GET    /api/v1/casos-clinicos/{id}/eventos
```

El caso **sí** queda en ruta plana —al revés que el adjunto clínico, que 04.02 anidó—: su servicio
resuelve caso → historia → persona por sí solo, igual que la entrada clínica.

`ProblemType` nuevos: `caso-clinico-no-accesible`, `caso-clinico-cerrado`,
`caso-clinico-posible-duplicado`, `caso-sin-motivo-de-cierre`, `oferta-no-vigente`.

El timeline de 04.02 gana **un filtro opcional por caso**, que es un parámetro más en los
contribuyentes de `clinical` — no una tabla ni una consulta nueva.

---

## 8. Lo que esta etapa NO hace

- **RF-M10-007 (validar requisito de Caso por Oferta) no se implementa como gate.** Exigir caso al
  reservar un turno o al abrir una sesión toca `scheduling` y `encounter`, y **hoy todas las
  sesiones del sistema no tienen caso**: encender esa validación rompe la vertical que funciona. El
  dato queda en el modelo —la Oferta ya sabe decir si requiere caso— y el gate es una etapa propia
  con su ventana de migración de datos.
- **RF-M10-008 (caso por participante en actividad grupal)** es M28, segunda entrega.
- **No se renumeran sesiones existentes.** Ver §2.
- **No hay Plan de Tratamiento** (04.04) ni consumo de autorizaciones (04.05).
- **No hay frontend**, por la misma causa que 04.02: sin contrato regenerado no hay cliente
  TypeScript. Ver §9.

---

## 9. El bloqueo heredado, dicho antes de empezar

**Docker no arranca en esta máquina** y por eso 04.02 quedó con el contrato en drift: `pom.xml`,
`application.yml` y el `info.version` dicen `0.30.0` y los `paths` son los de `0.29.0`.

Esta etapa **hereda el problema y lo agranda**: va a dejar el contrato en `0.31.0` con dos tandas
de operaciones sin regenerar. Es una decisión consciente —la alternativa es no avanzar— y tiene una
condición: **ninguna de las dos ramas se mergea hasta correr
`./mvnw verify -Dakine.contract.update=true` con el motor arriba**, y cuando eso pase hay que
regenerar **una sola vez, desde la rama que tenga las dos**.

## 10. Migraciones reservadas

| Contenido | Versión | Módulo propietario |
|---|---|---|
| `caso_clinico`, `caso_numerador`, `caso_profesional`, `caso_evento`, `caso_sesion_numerador` | `V47` | `clinical` |
| `sesion.caso_id`, `sesion.numero_en_caso` | `V48` | `encounter` |
