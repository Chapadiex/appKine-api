# Diseño — AKINE-04.04 · Plan de Tratamiento

> El *qué* normativo está en `docs/AKINE_IMPLEMENTATION_PLAN.md` §"Etapa AKINE-04.04"; las reglas
> vinculantes en `AGENT.md`. Acá va el *cómo*, y lo que la etapa decide **no** hacer.
> El desafío adversarial está en `docs/diseno/AKINE-04.04-challenge.md` y **manda sobre este
> archivo** donde discrepen.

Trazabilidad: RF-M11-001..008; RN-M11-001..006; reglas maestras 1 y 2.

---

## 1. La frase que ordena la etapa

**Planificado no es realizado.** Es RN-M11-001 y es la regla maestra 2 (Plan ≠ Turno ≠ Sesión)
aplicada al lugar donde más fácil se rompe: un plan que dice "10 sesiones" y un contador que alguien
incrementa a mano terminan, en tres meses, siendo la única fuente de verdad de algo que nunca pasó.

De ahí sale todo lo demás:

| Contador | De dónde sale | Se escribe |
|---|---|---|
| **Planificada** | La decisión clínica | Sí, es el dato de la etapa |
| **Autorizada** | La cobertura o el financiador | Sí, **declarada** — la costura real es 04.05 |
| **Realizada** | Los hechos: sesiones cerradas del Caso | **NUNCA**. Se deriva al leer |
| **Cancelada** | Los hechos: sesiones canceladas | **NUNCA**. Se deriva al leer |

**El backend no acepta un contador de realizadas desde el frontend, y no porque sea peligroso: es
que no existe la columna.** El plan de implementación lo pide con esas palabras ("sin aceptar
contadores realizados desde frontend") y la única forma de cumplirlo que no depende de la disciplina
de quien escriba el próximo endpoint es **no tener dónde guardarlo**.

---

## 2. El avance se deriva, y la costura ya existe en la dirección correcta

`RF-M11-005 — consultar avance` se calcula al leer, como el timeline de 04.02 y los slots de 05.01.
La fuente son las **sesiones del Caso**, que vive en `encounter`.

`clinical.spi.RealizadoEnElCasoProbe`, **declarada en `clinical` e implementada en
`encounter.infrastructure`**. Es exactamente el patrón de `scheduling.spi.AtencionProbe` (05.03):
la sonda se declara en el módulo que la consume y la implementa el que tiene el dato, porque
`encounter → clinical.spi` **ya existe** desde 06.01 y la inversa rompe ArchUnit.

Devuelve, por ítem del plan, cuántas sesiones cerradas y cuántas canceladas hay para esa
práctica/oferta dentro del Caso. **No trae contenido clínico.**

> **Lo que esto deja sin resolver, declarado:** el conteo de realizadas es por **oferta**, no por
> práctica individual dentro de la sesión — los tratamientos realizados son 06.04, que está fuera de
> alcance. Una sesión cuenta como una realización del ítem cuya oferta coincide. Cuando 06.04
> llegue, el conteo se afina sin cambiar el contrato.

---

## 3. Versionado: el mismo mecanismo que la entrada clínica, y por el mismo motivo

RN-M11-003: **modificar el plan no cambia tratamientos históricos.** Dos tablas, `V49`:

```
plan_tratamiento          ← identidad, estado, caso, numeración
plan_tratamiento_version  ← el contenido: objetivos, frecuencia, duración. Una fila por versión
plan_item                 ← las prácticas y sus cantidades, colgadas DE LA VERSIÓN
```

**Los ítems cuelgan de la versión y no del plan, y es la decisión que hay que mirar de frente.** Si
colgaran del plan, modificar la cantidad estimada de un ítem reescribiría el pasado: el avance de
hace dos meses se recalcularía contra un plan que entonces no existía. Colgados de la versión, cada
versión tiene su foto de cantidades y el histórico queda legible.

El precio es duplicación de filas al versionar. Es barato —versionar un plan es excepcional— y la
alternativa es la que ADR-0011 prohíbe.

**El control optimista va sobre la cabecera y el `INSERT` de la versión en la misma transacción**,
con la numeración saliendo del contador de la cabecera y nunca de un `MAX+1`.

> **Y sin `OPTIMISTIC_FORCE_INCREMENT`.** La cabecera queda sucia —el contador vive en ella—, así
> que el `UPDATE` versionado normal ya da la garantía. Forzar el incremento la haría avanzar dos
> veces y la respuesta devolvería `leída+1`: es el defecto que 04.02 pagó y corrigió, y la regla
> quedó escrita como **force-increment sólo donde la escritura NO toca ninguna columna del padre.**

---

## 4. Estados, y el único que sorprende

`BORRADOR → ACTIVO → SUSPENDIDO → ACTIVO → FINALIZADO`

- **`BORRADOR`**: se edita libremente **sin versionar**. Un plan que nunca se activó no tiene
  historia que preservar, y versionar cada tecleo llenaría la tabla de ruido.
- **`ACTIVO`**: toda modificación relevante **versiona** (RF-M11-004).
- **`SUSPENDIDO`**: el tratamiento se discontinuó pero no se cerró. Es uno de los casos borde que el
  plan nombra ("tratamiento discontinuado") y merece estado propio: finalizar y reabrir perdería la
  distinción entre "terminó" y "se frenó".
- **`FINALIZADO`**: terminal. No se reabre — se crea un plan nuevo.

**Completar la cantidad estimada NO finaliza el plan ni cierra el caso** (RN-M11-004). El sistema
avisa; la decisión es clínica. Un cierre automático por contador es exactamente confundir
planificado con realizado en la dirección contraria.

## 5. Un solo plan activo por Caso

`UNIQUE (organization_id, caso_clinico_id, activo_key)` con el mismo centinela de siempre.

Es una decisión, no una obviedad: el Caso **es** el problema terapéutico, y dos planes activos para
el mismo problema significan que en realidad son dos problemas —o sea, dos Casos—. El caso borde
"segundo plan" que el plan de implementación nombra se resuelve así: **activar un plan nuevo
finaliza el anterior**, en la misma transacción y dejando rastro en el historial. No hay ventana en
la que existan dos.

Lo que **sí** puede haber son varios planes **finalizados** por caso. Ese es el histórico.

## 6. La cantidad autorizada, y por qué se declara

RF-M11-003 pide registrarla. `plan_item.cantidad_autorizada` la guarda **con su origen**
(`DECLARADA` hoy; `AUTORIZACION` cuando 04.05 llegue) y su referencia nullable a la autorización de
M17.

**No se conecta a `Autorizacion` en esta etapa**, y no es pereza: 04.05 es *"Integración y consumo
de autorizaciones"* y hacerlo acá sería adelantarla a medias. El registro de 03.06 ya declaró que
`consultarElegibilidadAdministrativa` **no tiene consumidor**; 04.05 es quien lo estrena, y meter
medio consumo acá deja dos caminos que después hay que unificar.

La columna existe desde ahora porque agregarla después obliga a decidir qué valor llevan las filas
viejas, y ninguna respuesta es buena. Es la regla de DP-10: **se corta alcance, no modelo.**

## 7. Snapshots de práctica y oferta

`plan_item` guarda `oferta_id` **y** el nombre y la práctica congelados al momento de planificar,
igual que la obligación de 07.01 congela el importe. Un plan de seis meses sobrevive al renombre de
una oferta y a su baja; sin snapshot, el plan de marzo se lee en septiembre con los nombres de
septiembre y nadie entiende qué se planificó.

La oferta debe estar **habilitada** al planificar (02.07), y se valida contra
`offering.spi.OfertaDirectory`. Que después se dé de baja **no invalida el plan**: misma regla que
02.06 dejó fijada —la baja de un servicio no cascadea, sólo impide crear nuevos—.

---

## 8. Permisos y auditoría

Sin permisos nuevos: `hc:read` y `hc:write` por `AutorizacionClinica`, con acceso justificado y
**auditoría de la lectura también** (DP-03).

> El plan pide "lectura administrativa mínima". Hoy eso se cumple **de rebote**: el administrativo
> no tiene `hc:read`, así que no llega. No se inventa un permiso para expresarlo — sería el tercer
> permiso clínico sin decisión de la matriz. **Queda como pregunta abierta para el usuario**, igual
> que en 04.03.

Eventos: `PLAN_TRATAMIENTO_CREATED`, `_ACTIVATED`, `_AMENDED`, `_SUSPENDED`, `_RESUMED`,
`_FINALIZED`, `_ACCESSED`.

## 9. API — contrato `0.32.0`

Aditivo puro → versión menor. **Sobre `0.31.0`, que 04.03 dejó sin regenerar** (§11).

```
POST   /api/v1/casos-clinicos/{id}/planes
GET    /api/v1/casos-clinicos/{id}/planes
GET    /api/v1/planes-tratamiento/{id}
PATCH  /api/v1/planes-tratamiento/{id}
POST   /api/v1/planes-tratamiento/{id}/activacion
POST   /api/v1/planes-tratamiento/{id}/suspension
POST   /api/v1/planes-tratamiento/{id}/reanudacion
POST   /api/v1/planes-tratamiento/{id}/finalizacion
GET    /api/v1/planes-tratamiento/{id}/versiones
GET    /api/v1/planes-tratamiento/{id}/avance
```

Ruta plana como el Caso y la entrada clínica: el servicio resuelve plan → caso → historia → persona
por sí solo.

`ProblemType` nuevos: `plan-no-accesible`, `plan-no-editable`, `plan-transicion-invalida`,
`caso-no-activo`, `oferta-no-habilitada`.

## 10. Lo que esta etapa NO hace

- **No crea turnos, series ni sesiones.** El plan propone una regla de recurrencia; **agendarla es
  de `scheduling`** y no está en esta etapa. Un plan que crea turnos es la regla maestra 2 rota.
- **No consume autorizaciones reales** (04.05).
- **No cuenta prácticas individuales dentro de la sesión** (06.04). Ver §2.
- **No cierra el caso** al completar cantidades (RN-M11-004).
- **No hay frontend**, por la misma causa que 04.02 y 04.03.

## 11. El bloqueo heredado, por tercera vez

Docker no arranca. El contrato queda en `0.32.0` con **tres** tandas de operaciones sin regenerar
(04.02, 04.03, 04.04). Condición de salida, sin cambios: regenerar **una sola vez, desde la rama que
las tenga todas**, con `./mvnw verify -Dakine.contract.update=true`, antes de cualquier merge.

## 12. Migraciones reservadas

| Contenido | Versión | Propietario |
|---|---|---|
| `plan_tratamiento`, `plan_tratamiento_version`, `plan_item`, `plan_numerador`, `plan_evento` | `V49` | `clinical` |
