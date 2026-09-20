# Design challenge — AKINE-04.03

Paso obligatorio de `CLAUDE.md` §3. Ocho preguntas respondidas por escrito antes de `tasks`.
**Este documento manda sobre `AKINE-04.03-caso-clinico.md`** donde discrepen.

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

`caso_clinico`, `caso_numerador`, `caso_profesional`, `caso_evento` y `caso_sesion_numerador`:
**propietario `clinical`**, ningún otro módulo las lee ni las escribe.

`sesion` **sigue siendo de `encounter`** y gana dos columnas en `V48`, que es una migración de
`encounter`. `clinical` no escribe ni una columna de esa tabla.

**El caso que había que mirar es `caso_sesion_numerador`, y casi se decide mal.** Numera sesiones,
así que la intuición dice que es de `encounter`. Pero cuenta **por caso**, y el caso es de
`clinical`: si viviera en `encounter`, ese módulo tendría que conocer el ciclo de vida del caso
—cuándo empieza a contar, qué pasa al reabrir— que es justamente lo que el `spi` existe para no
tener que saber. Queda en `clinical`, y `encounter` **pide el número**, no lo calcula.

**Veredicto: pasa.** Con una regla vinculante para la implementación: `encounter` obtiene
`numero_en_caso` llamando a `clinical.spi`, **dentro de su transacción de cierre**, y no con un
`SELECT MAX` propio.

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

Aristas: `encounter → clinical.spi` (ya existe desde 06.01 y 04.02 la usa) y
`clinical → offering.spi` (para validar la oferta). **Ninguna nueva en sentido contrario.**

El riesgo real no es el ciclo, es el atajo: un agente que necesite el estado de una sesión va a
querer importar `encounter.domain` desde `clinical`. **Queda prohibido.** Si `clinical` necesita
saber si un caso tiene sesiones, se declara una sonda en `clinical.spi` que `encounter` implementa
—exactamente el patrón de `scheduling.spi.AtencionProbe` (05.03), declarado en `scheduling` e
implementado en `encounter` porque la dependencia ya va en ese sentido—.

**Veredicto: pasa.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Las cinco, y todos los uniques e índices empiezan por él.

Los dos que importan:
`uk_caso_numero (organization_id, historia_clinica_id, numero_caso)` y
`uk_sesion_numero_en_caso (organization_id, caso_id, numero_en_caso)`.

`caso_profesional` y `caso_evento` llevan `organization_id` **aunque sea derivable** del caso, por
el mismo motivo que `entrada_clinica_version` en 04.02: derivarlo obliga a un join para filtrar por
tenant, y el día que alguien escriba la consulta sin el join tiene una fuga que ningún test de la
etapa ve, porque los tests de una etapa usan un solo tenant.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?

Esta etapa **es** la regla maestra 1 y la 3, así que la pregunta no es si las confunde sino si las
implementa bien.

- **HC ≠ Caso:** el caso cuelga de la historia y la historia sigue existiendo sin casos. Una
  sesión sin caso sigue siendo válida (RF-M14-002).
- **Caso ≠ Sesión:** un caso contiene muchas sesiones y muchos planes. El caso **no** guarda
  contador de sesiones como dato propio: lo guarda `caso_sesion_numerador`, que es un numerador y
  no un `COUNT(*)` cacheado — la distinción es la misma que `sesion_numerador` hizo en 06.05 y la
  razón es que un cache se desincroniza y un numerador no puede, porque nunca vuelve atrás.
- **Regla 3, "las sesiones se numeran dentro del Caso":** se cumple **para las sesiones que tienen
  caso**. Para las que no, no hay caso dentro del cual numerar, y el número por historia es lo que
  queda. **Esto es una desviación declarada de la regla maestra, no un descuido**, y su causa es
  DP-10: 06.05 se ejecutó sin 04.03.
- **Regla 4 (Turno ≠ Sesión):** el caso no toca turnos. No aparece en `scheduling`.

**Veredicto: pasa, con la desviación de la regla 3 declarada y con su alcance acotado a las
sesiones previas a esta etapa.**

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

No, y además **no hay baja lógica de caso**: cerrar no es borrar. Un caso abierto por error se
cierra con motivo. Agregar `active` al lado de `estado` daría dos formas de que un caso "no esté",
que es cómo se construye una consulta que se olvida de una.

`caso_evento` es append-only. `caso_profesional` usa vigencia y **no borra**: un profesional
desvinculado de la organización sigue figurando en el equipo del caso que trató, porque lo trató.

**Veredicto: pasa.**

## 6. Contrato — ¿aditivo o incompatible?

Aditivo puro: ocho operaciones nuevas, más un parámetro opcional de filtro por caso en el timeline
de 04.02. Ninguna operación existente cambia de forma. **`0.30.0` → `0.31.0`**, versión menor.

**Pero hay algo que decir antes de tocarlo:** `0.30.0` **nunca se regeneró**. Esta rama va a dejar
dos tandas de operaciones sin reflejar en el YAML. Es deuda consciente y tiene condición de salida:
regenerar **una sola vez desde la rama que tenga las dos**, con Docker arriba, antes de cualquier
merge.

**Veredicto: pasa el cambio, no pasa la verificación.** Y esa diferencia es la que impide cerrar.

## 7. Ruta crítica — ¿tiene sus cimientos, o lo estoy adelantando?

`... → Persona/Paciente → Cobertura/Convenio → Historia Clínica → **Caso** → Plan → Turno → ...`

Dependencias declaradas del plan: **04.02** (hecha, en la rama de la que ésta sale) y **02.07**
(cerrada). Los cimientos están.

**Lo que sí se adelantaría es el gate de RF-M10-007** —exigir caso al reservar o al atender—, y por
eso no se construye acá: hoy **todas** las sesiones del sistema no tienen caso, así que encender
esa validación rompe la vertical que funciona. Primero tiene que existir el caso y poder asignarse.

**Veredicto: pasa.**

## 8. El caso que rompe el diseño

**"Dos administrativos abren un caso para el mismo paciente y la misma oferta, al mismo tiempo,
desde dos sedes."**

Rompe cuatro cosas si el diseño está mal:

1. **Los dos correlativos colisionan.** `MAX+1` simultáneo da el mismo número. Lo resuelve
   `caso_numerador` con `UPDATE ... ultimo_numero + 1` y el unique como red. Y **la fila del
   numerador tiene que existir antes**, creada en transacción aparte con `INSERT ... ON DUPLICATE
   KEY UPDATE`: la creación perezosa dentro de la transacción que la bloquea es el deadlock que
   este repositorio ya pagó cuatro veces.
2. **Quedan dos casos duplicados.** El chequeo de "posible duplicado" tiene ventana de carrera y
   **no se pretende que no la tenga**: el resultado correcto no es rechazar, es que los dos entren
   y alguien los unifique después, porque **dos casos activos de la misma oferta pueden ser
   legítimos** (RN-M10-002). Un unique acá sería un bug disfrazado de protección.
3. **El 409 de duplicado se confunde con el 409 de concurrencia.** Son `problemType` distintos y
   tienen que serlo: uno se resuelve confirmando, el otro releyendo.
4. **La numeración de sesión dentro del caso** tiene el mismo problema y la misma solución, un
   numerador más — pero con una trampa propia: **el cierre de sesión ya pide un número (el de la
   historia) y ahora pide dos.** Los dos se piden **en la misma transacción** y **antes** de
   escribir el cierre, y el orden de los dos numeradores tiene que ser **siempre el mismo**
   (historia primero, caso después) o dos cierres concurrentes de sesiones de casos cruzados se
   bloquean mutuamente. **Ese es el deadlock que esta etapa puede introducir y ninguna anterior
   tuvo**, porque es la primera vez que una transacción toma dos numeradores.

> **Quinta cosa, la que no rompe hoy pero rompe en 04.04:** reabrir un caso no reinicia
> `caso_sesion_numerador`. La sesión siguiente a una reapertura es la 9, no la 1. Renumerar sería
> reescribir historia clínica.

**Veredicto: pasa, con las cinco condiciones anotadas como vinculantes.** La cuarta —el orden fijo
de los dos numeradores— es la más fácil de olvidar y la más cara.

---

## Veredicto global

**El diseño pasa el challenge.** Se avanza a `tasks`.

Cuatro cosas quedan declaradas como límite, no como resuelto:

1. **La regla maestra 3 se cumple sólo para las sesiones con caso.** Las anteriores a esta etapa no
   tienen caso y no se les inventa uno.
2. **RF-M10-007 no se implementa como gate**, y sin ese gate un centro puede seguir atendiendo sin
   caso indefinidamente. Es una etapa propia con ventana de migración.
3. **El contrato queda en drift por segunda vez.** Sin Docker no hay regeneración, y por lo tanto
   tampoco frontend.
4. **"Administrativos sin contenido clínico salvo permiso explícito"** se cumple hoy de rebote
   —el administrativo no tiene `hc:read`—, no por una decisión de la matriz. **Pregunta abierta
   para el usuario**, no resuelta acá.

---

## Addendum del 19/09/2026 — el deadlock de §8.4 es estructuralmente imposible

Escribir los tests de integración de la etapa obligó a construir el ciclo de espera, y **no se
puede construir con datos válidos.**

Un Caso pertenece a **exactamente una** Historia Clínica. Entonces:

- Dos sesiones que comparten Caso **comparten Historia**, así que ya quedan serializadas en el
  primer numerador y nunca llegan a pelear por el segundo.
- Dos sesiones de Historias distintas **no comparten ninguno** de los dos numeradores.

No hay par de transacciones que pueda tomar los dos locks en orden inverso. **El ciclo requeriría
una sesión de la Historia B con un Caso de la Historia A, dato que `iniciar` rechaza.**

**Esto no deroga la condición vinculante, la reclasifica.** El orden fijo (historia primero, caso
después) sigue siendo obligatorio, pero deja de ser *lo que evita un deadlock posible* y pasa a ser
*lo que mantiene imposible un deadlock que hoy lo es por la forma del modelo*. La diferencia importa
para quien venga después: **el día que un tercer numerador entre en esa transacción, o que algo
permita que un Caso cuelgue de más de una Historia, la garantía se cae y el orden fijo vuelve a ser
lo único que queda.**

Lo que los tests sí ejercen es lo observable —que las combinaciones de máximo solapamiento terminen
las dos, sin deadlock y con correlativos coherentes en las dos dimensiones— más el escenario que sí
puede fallar de verdad: **cinco cierres simultáneos sobre un caso sin fila de
`caso_sesion_numerador`**, o sea el deadlock del lazy-create que este repositorio ya pagó cuatro
veces.
