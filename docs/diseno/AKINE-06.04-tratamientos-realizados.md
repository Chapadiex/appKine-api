# Diseño — AKINE-06.04 · Tratamientos realizados y espacios usados

> El *qué* normativo está en `docs/AKINE_IMPLEMENTATION_PLAN.md` §"Etapa AKINE-06.04"; las reglas
> vinculantes en `AGENT.md`. Acá va el *cómo*, y lo que la etapa decide **no** hacer.
> El desafío adversarial está en `docs/diseno/AKINE-06.04-challenge.md` y **manda sobre este
> archivo** donde discrepen.

Trazabilidad: RF-M14-005; RF-M04-005; RN-M14-004; `plan_sesiones.txt` §10.

---

## 1. La frase que ordena la etapa

**Planificado no es realizado** (RN-M14-004), y hasta hoy el sistema sólo sabía lo primero.

04.04 registró lo que un profesional **decidió** hacer. 06.01–06.05 registraron que **hubo una
atención**. Lo que nunca existió es el dato del medio: **qué prestaciones se aplicaron realmente en
esa atención**. El sistema podía decir "hubo una sesión de Kinesiología deportiva" y no podía decir
si en esa sesión hubo electroterapia, terapia manual o las dos.

Esta etapa crea ese dato. Y crearlo tiene una consecuencia que excede a M14, porque es **la única
tabla del sistema que vincula una atención con una práctica del catálogo M06** — y de eso vive el
§3.

---

## 2. Qué se registra, y por qué no alcanza una columna en `sesion`

`plan_sesiones.txt` §10.5 lo pide sin ambigüedad: *"la sesión debe permitir múltiples
intervenciones en una misma visita"*, cada una con **orden, duración, profesional si hubiera
co-atención y observación breve**. §10.2 agrega práctica, técnica, zona, parámetros y espacio.

Son dos tablas nuevas, las dos propiedad de `encounter`:

```
tratamiento_realizado    ← la intervención: práctica, técnica, zona, duración, quién, dónde
tratamiento_parametro    ← los parámetros TIPADOS de esa intervención. Cuelgan del tratamiento
```

### 2.1 Por qué los parámetros son una tabla y no una columna `JSON`

`plan_sesiones.txt` §10.4 pide campos distintos por tipo de práctica —intensidad y frecuencia en
electroterapia; series, repeticiones y carga en ejercicio terapéutico— y el plan de implementación
nombra el caso borde de frente: **"parámetro legado sin tipo/unidad que debe rechazarse o
normalizarse explícitamente"**.

Un `JSON` no puede **rechazar** nada: la validación quedaría en Java y la base aceptaría cualquier
forma que un endpoint futuro decidiera escribir. Con tabla, `tipo_dato NOT NULL` y un `CHECK` que
exige que el valor caiga en la columna que su tipo declara, **el rechazo es estructural**. Un
parámetro sin tipo no se puede insertar ni por error ni por un endpoint nuevo escrito con prisa.

Hay además un antecedente caro en este repositorio: **MySQL normaliza el `json` al guardarlo**
—reordena claves y reescribe números—, que es la causa raíz del outbox clavado. Un valor numérico
clínico que vuelve del motor con otra representación es exactamente lo que no se quiere en un
parámetro de dosificación.

El `borrador` de la sesión sigue siendo `json` opaco y eso no cambia: son cosas distintas. El
borrador es lo que el profesional está tipeando; esto es lo que asentó.

### 2.2 Snapshots, por la misma razón de siempre

`tratamiento_realizado` guarda `practica_id` **y** el código y el nombre congelados, y
`espacio_id` **y** el nombre congelado. Es lo que `resource.spi.CatalogoSnapshot` pide con esas
palabras —*"un hecho histórico guarda el id y, si necesita ser legible sin resolver nada, también
el código y el nombre del momento"*— y lo que `EspacioSnapshot` repite para RN-M04-003.

Una sesión de marzo leída en septiembre tiene que decir qué práctica fue **en marzo**.

---

## 3. La pregunta que la etapa está obligada a contestar

04.04 y 04.05 declararon, cada una en su registro, que 06.04 destraba un límite suyo:

> **El avance del plan cuenta por oferta, y la autorización de M17 es por práctica.** Mientras no
> exista el registro de tratamientos realizados, el consumo de autorizaciones **puede estar
> imputándose a la autorización equivocada**.

**La respuesta es que son dos mitades y esta etapa cierra una sola.** Cerrar la que cierra es
obligatorio; la otra no se puede cerrar acá sin tomar una decisión que no es del agente.

### 3.1 La mitad que SÍ se cierra: la imputación del consumo

Hoy `ConsumoDeAutorizacionService.consumirPorSesion` elige así:

```java
autorizaciones.aprobadasDePersona(org, persona).stream()
        .filter(a -> a.habilitaEl(fecha))
        .findFirst();          // la que vence antes. NADIE mira practica_id
```

El comentario que la acompaña lo dice sin disimulo: *"puede consumir una autorización de otra
práctica, y es el límite declarado de la etapa"*. Con tratamientos registrados, ese límite deja de
tener excusa: **la sesión ahora sabe qué prácticas se aplicaron**.

El cambio es quirúrgico y va por la costura que ya existe:

1. `encounter.spi.SesionCerrada` crece un campo `practicasRealizadas` (`Set<Long>`). Es
   exactamente lo que el javadoc de ese record autoriza: *"se agranda cuando a un consumidor le
   falta un dato; no se le abre `domain`"*, y es lo mismo que 04.05 hizo con `cerradaPorCuentaId`.
   **No es dato clínico**: un id de práctica dice qué prestación se facturó, no qué le duele al
   paciente. Es el mismo criterio con el que `ofertaId` ya viaja.
2. `person.spi.ConsumoPorSesion` crece el mismo campo.
3. `ConsumoDeAutorizacionService` filtra: **si la sesión trae prácticas, sólo son elegibles las
   autorizaciones cuya `practica_id` esté entre ellas.** El desempate —la que vence antes— no
   cambia.

### 3.2 Y qué pasa cuando ninguna autorización cubre la práctica realizada

**No se consume, y hay desenlace propio: `SIN_AUTORIZACION_PARA_LA_PRACTICA`.**

Es la decisión que hay que mirar de frente, porque la alternativa —caer de nuevo a "cualquiera que
habilite"— es cómoda y es peor:

- Gastar una unidad de la autorización de kinesiología para pagar una sesión de fonoaudiología le
  **come al paciente** unidades que sí iba a necesitar, y deja intacta la autorización que
  correspondía.
- Frente al financiador, una unidad consumida contra una práctica que no se prestó es una
  **declaración falsa**. No es un error de redondeo: es el hecho que el centro después presenta a
  cobro.
- Y es silencioso. Hoy nadie lo detecta porque no hay con qué compararlo.

**No consumir ya es un desenlace benigno y previsto por 04.05**: el observador no lanza, el cierre
clínico no se bloquea (DP-06) y el circuito administrativo lo resuelve después con otra
autorización o facturándole al paciente. Agregar un cuarto desenlace no cambia ninguna de esas
garantías: **cambia de "consumió mal y nadie se entera" a "no consumió y queda dicho"**.

### 3.3 La compatibilidad hacia atrás es un `if`, y es deliberado

**Una sesión sin tratamientos registrados conserva exactamente el comportamiento de hoy.**

No es una concesión: son **todas** las sesiones anteriores a esta etapa, y también las sesiones de
ofertas que no registran prácticas —una consulta, una evaluación inicial—. Exigir tratamientos
para consumir dejaría sin descontar a todo lo ya cerrado y a los flujos que nunca van a tener
prácticas. El conjunto vacío significa *"no sé qué prácticas fueron"*, y ante esa ignorancia el
comportamiento correcto es el anterior, no uno más estricto.

### 3.4 La mitad que NO se cierra, y por qué no es una omisión

**El avance del plan sigue contándose por oferta.** Y no se puede arreglar acá:

| Tabla | Granularidad |
|---|---|
| `plan_item` (V49) | `oferta_id`, `oferta_consultorio_id`, `servicio_id`. **Sin `practica_id`** |
| `autorizacion` (V44) | `practica_id` |
| `servicio` / `oferta_servicio_consultorio` (V24) | **Sin `practica_id`. No hay puente** |

Verificado contra el esquema, no de memoria: **no existe ninguna tabla que vincule una Oferta con
una Práctica.** 04.05 ya lo había anotado —*"no existe tabla puente Oferta↔Práctica, V24 la dejó
afuera"*— y sigue siendo cierto.

`tratamiento_realizado` es un puente **observado** ("en esta sesión, de esta oferta, se hicieron
estas prácticas"), no **configurado** ("esta oferta presta estas prácticas"). Sirve para imputar un
hecho consumado —que es §3.1— y **no** sirve para contar el avance de un plan, porque un plan
planifica antes de que ninguna sesión exista.

Hacer que el avance cuente por práctica exige `plan_item.practica_id`, y eso:

- altera una tabla de **`clinical`**, que no es propietaria esta etapa;
- reabre una etapa cerrada (04.04);
- y obliga a decidir algo que ningún RF resuelve: **¿un plan se planifica por oferta o por
  práctica?** RF-M11-002 dice "prácticas planificadas" y V49 implementó ofertas. Si la respuesta es
  "por práctica", `plan_item` cambia de eje y hay ventana de migración.

**Es una decisión del usuario, no del agente.** Queda escrita como tal en §9.

---

## 4. Concurrencia: el `@Version` de la Sesión, forzado

Escribir un tratamiento **no toca ni una columna de `sesion`**. Es literalmente el caso de 02.07
(`b8bbc67`) y el de `CasoClinicoService.cambiarEquipo`: un `@Version` sobre el padre **no protege
una escritura que sólo toca tablas hijas**.

Entonces las mutaciones de tratamiento leen la sesión con
**`OPTIMISTIC_FORCE_INCREMENT`** (`findWithLockByIdInScope`) y la guardan aunque no cambie ninguna
de sus columnas, que es lo que materializa el avance de versión.

**Y la recíproca se respeta**, que es la otra mitad de la regla y la que 04.02 pagó: acá la
escritura **no ensucia** la sesión por ningún otro camino, así que la versión avanza **una** vez y
la respuesta devuelve `leída+1`. Si alguna vez se agregara una columna de resumen en `sesion`
—cuántos tratamientos, minutos totales— **habría que sacar el force-increment**, porque entonces la
versión avanzaría dos veces y el cliente comería un 409 del que no puede salir. Ese resumen no
existe y §8 dice por qué no debe existir.

### 4.1 El `orden`, y por qué acá `MAX+1` no es el `MAX+1` prohibido

La regla del repositorio es que los correlativos se asignan con `UPDATE ... ultimo_numero + 1` y
**nunca** con `MAX+1`. Esta etapa **no crea un numerador** y asigna `orden` como el máximo de la
sesión más uno. Hay que justificarlo o sacarlo.

Se justifica, y la diferencia no es de gusto:

- El `MAX+1` prohibido es el que **no está serializado por nada**: dos transacciones leen el mismo
  máximo, las dos insertan, las dos commitean. Es el caso de `numero_sesion` y `numero_en_caso`,
  donde el correlativo es de una Historia o de un Caso y los escritores son sesiones distintas que
  no comparten ninguna fila.
- Acá **todos los escritores del mismo `orden` comparten la fila `sesion`**, y la leen con
  `OPTIMISTIC_FORCE_INCREMENT`. Dos altas concurrentes en la misma sesión: la primera commitea, la
  segunda emite `UPDATE sesion SET version = N+1 WHERE version = N`, afecta cero filas y recibe
  409. **Nunca llegan las dos.**
- Y el `UNIQUE (organization_id, sesion_id, orden, deleted_key)` es el **respaldo** del mecanismo,
  no el mecanismo — mismo reparto que `uk_sesion_numero` en V35.

Un numerador aparte sería una tabla y una fila-lock por sesión para proteger algo que la fila de la
sesión ya protege, con el costo conocido: toda fila-lock hay que crearla en transacción aparte con
`INSERT ... ON DUPLICATE KEY UPDATE` o se produce deadlock. **Se paga una complejidad que acá no
compra nada.**

### 4.2 El orden no se reordena, y es una decisión

`orden` es **monótono y no se reutiliza**: se asigna sobre el máximo de todas las filas de la
sesión, **incluidas las dadas de baja**. Un tratamiento borrado no libera su número.

No hay operación de reordenar. Intercambiar dos `orden` bajo un unique exige un valor intermedio o
un `DEFERRABLE` que MySQL no tiene, y el dato no lo justifica: el orden es **cronológico** —en qué
secuencia se aplicaron las intervenciones—, no una preferencia de presentación. Quien se equivocó
de orden da de baja y vuelve a cargar, y el histórico muestra las dos cosas.

---

## 5. El espacio realmente utilizado (RF-M04-005)

**Va en el tratamiento, no en la sesión.** `plan_sesiones.txt` lo lista entre los campos base del
Bloque D (§10.2) y le dedica §10.6, y el dato real lo exige: un paciente arranca en el box de
electroterapia y termina en el gimnasio. Una sola columna en `sesion` obligaría a elegir cuál de
los dos miente.

Consecuencia colateral que conviene tener a la vista: **esta etapa no hace `ALTER TABLE sesion`**.
`V55` sólo crea tablas nuevas de `encounter`.

Se valida contra `resource.spi.EspacioDirectory`:

- El espacio tiene que ser **del tenant y de la sede de la sesión**. Si no, **404** —no 403—: un
  403 confirmaría que el id existe y bastaría probar ids consecutivos para censar los boxes de otro
  centro.
- Tiene que estar **en servicio en el instante de la atención** (`sesion.iniciadaEn`), no en el
  instante en que alguien tipea el registro. Si no, **409** `espacio-no-operable`. Evaluarlo
  "ahora" haría que registrar el viernes una atención del lunes fallara porque el box se dio de
  baja el miércoles, y eso es negar un hecho que ocurrió.

### 5.1 Lo que NO se valida, y es la decisión más contraintuitiva de la etapa

**No se valida ocupación ni capacidad.**

El plan las nombra entre las validaciones, y aplicarlas acá sería **la regla maestra 4 al revés**.
Un tratamiento realizado es un **hecho consumado**: la atención ya ocurrió, en ese box, a esa hora.
Rechazar el registro porque el box figuraba ocupado no impide la sobreocupación —ya pasó— sino que
impide **documentarla**, y deja la historia clínica diciendo que la sesión no tuvo espacio.

La ocupación es una regla de **reserva**, y su lugar es la reserva del Turno (05.02) y la asignación
de espacio al turno (RF-M04-004). Acá se registra lo que pasó. Es la misma distinción que 04.04
hizo entre planificado y realizado, aplicada al espacio.

---

## 6. Co-atención, y por qué no relaja la propiedad

`profesional_membership_id` por tratamiento resuelve la co-atención de §10.5: dos profesionales en
la misma visita son dos tratamientos con distinto profesional. Por defecto es el de la sesión.

**Quien escribe sigue siendo únicamente el dueño de la sesión.** Anotar que otro profesional
participó no es lo mismo que darle permiso de escritura: `Sesion#exigirPropiedadDe` se sigue
aplicando y escribir en la atención ajena sigue siendo **409, no 403** —la propiedad no es un
permiso—. El co-atendiente queda registrado por quien conduce la atención, que es como funciona en
la práctica clínica.

El membership declarado se valida contra `organization.spi.ConsultorioMembershipDirectory`: tiene
que estar **vigente en esa sede**. Uno de otra sede o dado de baja es **409**
`profesional-no-asignable`.

---

## 7. Estado, baja lógica y el borde con 06.06

**Sólo se escriben tratamientos en una sesión abierta.** Cerrada → `SesionCerradaException` (409),
que ya existe. Corregir lo que dice una atención cerrada es una **enmienda**, y la enmienda es
06.06. Fail-closed, igual que el borrador y la evaluación.

La baja usa el cuarteto completo `active` / `deleted_at` / `deactivation_reason` / `deleted_key`
con el centinela `'1970-01-01'`, y **`deleted_key` entra en el unique de `orden`**: en MySQL varios
`NULL` no colisionan, así que un unique sobre `deleted_at` a secas desprotegería justo el caso que
importa.

> **Se podría argumentar que un tratamiento en borrador no es información histórica relevante** y
> que el borrado físico bastaría. Se elige la baja lógica igual, por dos razones: el motivo de baja
> es él mismo un dato clínico —*"se suspendió la electroterapia porque el paciente refirió
> molestia"*— y `orden` no se reutiliza, lo que sólo tiene sentido si la fila sigue existiendo.

---

## 8. Lo que se deriva y no se guarda

**No hay columna de resumen en `sesion`**: ni cantidad de tratamientos, ni minutos totales.

Es la regla que 04.02 dejó fijada para el timeline y 04.04 para el avance: *una tabla de resumen es
una segunda copia de la verdad*. La duración total de una sesión es `SUM(duracion_minutos)` y se
calcula al leer. Además —§4— una columna así rompería el force-increment.

---

## 9. Permisos

**Sin permisos nuevos.** `sesion:register` sobre la sede, más la propiedad de la sesión. Es el
mismo par que gobierna el borrador y la evaluación, y un tratamiento es contenido de la atención
como cualquier otro.

La lectura se audita como toda lectura clínica (DP-03): el tratamiento realizado **es** contenido
clínico —dice qué se le hizo al paciente y en qué zona—, a diferencia del id de práctica suelto que
viaja en `SesionCerrada` para facturar.

---

## 10. API — contrato `0.37.0`

Aditivo puro → versión menor. Sobre `0.33.0`, que 04.05 dejó sin regenerar, y compartiendo tanda
con 06.03, 06.06 y 07.03 (§12).

```
POST   /api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/tratamientos
GET    /api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/tratamientos
PUT    /api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/tratamientos/{tratamientoId}
DELETE /api/v1/consultorios/{consultorioId}/sesiones/{sesionId}/tratamientos/{tratamientoId}
```

Ruta anidada bajo la sesión, no plana: a diferencia del Caso o del Plan, un tratamiento **no tiene
identidad fuera de su sesión** y el control de sede ya está en el prefijo de `SesionController`.

`PUT` reemplaza el tratamiento completo **con sus parámetros**, en lugar de exponer un endpoint por
parámetro. Es lo que el enunciado pide con esas palabras: *"evitar endpoint por cada tipo si modelo
polimórfico existente resuelve"*. Los parámetros se borran y se reinsertan dentro de la misma
transacción: son hijos sin identidad propia hacia afuera.

**`DELETE` responde 204 sin cuerpo y por eso el controller declara `application/json` en sus
`produces`** además de `application/problem+json`. Sin eso el cliente generado manda
`Accept: application/problem+json` y el `produces` de clase lo corta con **406 antes de entrar al
método**. Rompió activación de cuenta y recuperación de contraseña, y ningún test lo agarró.

Todas las mutaciones exigen `version` de la **sesión** (§4) y devuelven la sesión con su versión
nueva, para que la pantalla no tenga que repedirla.

`ProblemType` nuevos: `tratamiento-no-accesible`, `practica-no-utilizable`, `espacio-no-operable`,
`profesional-no-asignable`, `parametro-invalido`.

---

## 11. Lo que esta etapa NO hace

- **No unifica el avance del plan con la práctica** (§3.4). **Decisión pendiente del usuario.**
- **No valida qué parámetros exige cada práctica.** `plan_sesiones.txt` §10.4 pide campos
  condicionales por tipo de práctica y **no existe ninguna configuración que los declare**: M06
  tiene prácticas, no esquemas de parámetros. Inventar un catálogo acá sería decidir por el
  usuario. Se valida que cada parámetro esté **tipado** (que es lo que el caso borde exige) y no
  cuáles son obligatorios. **Segunda decisión pendiente.**
- **No valida ocupación ni capacidad del espacio** (§5.1).
- **No reordena tratamientos** (§4.2).
- **No cambia la cantidad consumida por sesión.** Sigue siendo **una unidad por sesión**, aunque la
  sesión tenga cinco tratamientos. Cobrar una unidad por práctica es una decisión económica con
  plata de por medio, sin RF que la respalde, y cambiarla acá de callado sería peor que el defecto
  que esta etapa corrige. **Tercera decisión pendiente.**
- **No toca `sesion`**: sin `ALTER TABLE` (§5).
- **No hay frontend**, por la misma causa que 04.02 a 04.05: el cliente TypeScript se genera desde
  el contrato y el contrato no se puede regenerar sin Docker.

---

## 12. Migración reservada y bloqueo heredado

| Contenido | Versión | Propietario |
|---|---|---|
| `tratamiento_realizado`, `tratamiento_parametro` | `V55` | `encounter` |

`V51` y `V52` (06.03), `V53` (06.06) y `V54` (07.03) están tomadas por etapas en vuelo en otros
worktrees. **La versión se reservó antes de escribir código**, que es la lección de `V26`.

Docker no arranca. El contrato queda en `0.37.0` **sin regenerar**, y `OpenApiContractIT` queda en
drift. Condición de salida, sin cambios respecto de las cuatro etapas anteriores: **una sola
regeneración desde la rama que las tenga todas**, antes de cualquier merge. El YAML **no se edita a
mano** para tapar el drift.
