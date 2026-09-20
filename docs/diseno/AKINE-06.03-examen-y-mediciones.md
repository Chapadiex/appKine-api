# Diseño — AKINE-06.03 · Evaluación completa, examen y mediciones

> El *qué* normativo está en `docs/AKINE_IMPLEMENTATION_PLAN.md` §"Etapa AKINE-06.03" y en
> `docs/plan_sesiones.txt` §§9, 13.2 y 14. El challenge está en
> `docs/diseno/AKINE-06.03-challenge.md` y **manda sobre este archivo**.

Trazabilidad: RF-M14-004.

---

## 1. Lo que esta etapa agrega y lo que ya existía

06.02 entregó la **evaluación base** —dolor, zona, lateralidad del dolor, evolución, objetivo,
limitación funcional— como **columnas de `sesion`**, y su registro dejó fijado el criterio que esta
etapa continúa: *"el JSON opaco de 06.01 pasa a columnas, y el requisito lo obliga: un JSON no se
consulta ni se indexa"*.

06.03 agrega lo que **no puede ser columna**: ROM, fuerza, función, marcha, signos vitales,
neuro/respiratorio y tests extensibles. Son decenas de medidas, distintas por especialidad y
**extensibles por el centro**. Una columna por medida es un `ALTER TABLE` por cada test nuevo.

**La respuesta no es volver al JSON: es una fila por medición, contra un catálogo.**

## 2. Dos tablas y dos módulos

| Tabla | Migración | Propietario | Qué es |
|---|---|---|---|
| `medicion_definicion` | `V51` | `resource` | El catálogo: qué se mide, en qué unidad, con qué rango |
| `sesion_medicion` | `V52` | `encounter` | El valor medido en una sesión |

**El catálogo va a `resource` y no a `encounter`**, porque `resource` ya es el dueño de los
catálogos clínicos de M06 —`especialidad`, `practica`, `nomenclador`, `nomenclador_item`— y expone
`CatalogoDirectory`. Meter un sexto catálogo en otro módulo sería tener dos dueños de la misma clase
de cosa.

Y **vale el patrón de 02.05, que es fácil de arruinar**: el catálogo es **global + por tenant**, y
se filtra por `owner_key = IFNULL(organization_id, 0)`, **nunca por `organization_id`** — varios
`NULL` no colisionan en MySQL (ADR-0021). La plataforma siembra las medidas universales (ROM de
rodilla, Borg, EVA) y cada centro agrega las suyas.

`encounter` valida la definición por `resource.spi` — arista que ya existe.

## 3. El valor es tipado, y la unidad se congela

```
tipo          NUMERICO | TEXTO | BOOLEANO | ESCALA
valor_numerico DECIMAL(10,3)   ← NUMERICO y ESCALA
valor_texto    VARCHAR(500)    ← TEXTO
valor_booleano TINYINT(1)      ← BOOLEANO
```

Un CHECK exige que esté seteado **exactamente el que corresponde al tipo** y ninguno más. Un valor
numérico en una columna de texto es el principio de una medición que después nadie puede comparar.

**`DECIMAL`, nunca `float`** — es la regla de `AGENT.md` §5 y acá importa igual que en los importes:
un ROM de 92,5° guardado como binario flotante deja de ser comparable consigo mismo.

**La unidad, el nombre y la versión de la definición se copian en la fila.** RN de la etapa: *"unidad
y significado históricos"*. Si el catálogo migra de centímetros a milímetros, o alguien corrige el
nombre de un test, **las mediciones viejas siguen diciendo lo que decían**. Es el mismo snapshot que
congela el importe en la obligación (07.01) y el nombre de la oferta en el ítem del plan (04.04).

## 4. Lateralidad: no existe `BILATERAL`

`lateralidad` toma `IZQUIERDA`, `DERECHA` o `NO_APLICA`.

**Una medición bilateral son dos filas**, y es una decisión, no una simplificación: los valores de
los dos lados **son distintos** —ese es el punto de medirlos— y una fila `BILATERAL` obligaría a
guardar dos números en un campo o a promediarlos, que es perder exactamente la información que la
medición existe para capturar. El caso borde "medición bilateral" del enunciado se resuelve así.

`NO_APLICA` es para lo que no tiene lado: frecuencia cardíaca, Borg, saturación.

Unique: `(organization_id, sesion_id, definicion_id, lateralidad)`. Una medición por test y por lado
en cada sesión. Repetir el registro **actualiza**, que es lo que el autosave necesita.

## 5. La comparación se calcula al leer

*"Comparación anterior/actual"* (API) y *"copiar-previo-y-ajustar"* (frontend).

**No se guarda ningún delta ni ninguna referencia a la medición anterior.** El endpoint devuelve,
por definición, el valor de esta sesión y el de **la sesión cerrada anterior del mismo paciente**.
Es lo mismo que el timeline de 04.02 y el avance de 04.04: guardarlo sería una segunda copia de la
verdad que miente el día que alguien enmiende la sesión anterior.

**"Re-evaluación sin baseline" no es un error**: el anterior viene `null` y eso es una respuesta
válida.

**"Copiar nunca guarda sin revisión" es una regla de la pantalla, y el backend la sostiene no
teniendo cómo romperla:** no hay endpoint de "copiar". Hay uno que **devuelve** los valores
anteriores; escribirlos es un registro normal, con el profesional detrás.

## 6. El rango valida al registrar, no al leer

`medicion_definicion` lleva `minimo` y `maximo` opcionales. Un valor fuera de rango es **400 al
registrar**, contra la versión **vigente en ese momento**.

**Nunca se revalida al leer.** Si mañana el catálogo estrecha el rango, las mediciones viejas no se
vuelven inválidas: fueron válidas cuando se tomaron. Es la misma lógica del snapshot de §3.

## 7. Test discontinuado

Baja lógica en el catálogo, y **no cascadea**: las mediciones existentes siguen legibles y
comparables; lo único que se impide es **registrar nuevas** con esa definición, con 409
`medicion-definicion-inactiva`. Es exactamente la regla que 02.06 dejó fijada para la baja de un
servicio.

## 8. Permisos, auditoría y persistencia parcial

Sin permisos nuevos. Registrar una medición es escribir en la atención: el permiso es el de la
sesión, y **la propiedad no es un permiso** —escribir en la atención ajena es **409, no 403**
(06.01)—. El catálogo se gestiona con los permisos de M06 que 02.05 fijó.

**La persistencia parcial ya existe**: el autosave de 06.01 con control optimista. Una medición se
guarda sola y la sesión sigue en curso. **Esta etapa no inventa un mecanismo de borrador nuevo.**

## 9. API — contrato `0.34.0`

```
GET    /api/v1/mediciones/definiciones
POST   /api/v1/mediciones/definiciones
PATCH  /api/v1/mediciones/definiciones/{id}
DELETE /api/v1/mediciones/definiciones/{id}
PUT    /api/v1/sesiones/{id}/mediciones/{definicionId}
DELETE /api/v1/sesiones/{id}/mediciones/{definicionId}
GET    /api/v1/sesiones/{id}/mediciones
GET    /api/v1/sesiones/{id}/mediciones/comparacion
```

`PUT` y no `POST` para registrar: la operación es **idempotente por naturaleza** —una medición por
test y por lado— y el autosave la va a repetir.

`ProblemType` nuevos: `medicion-definicion-no-accesible`, `medicion-definicion-inactiva`,
`medicion-fuera-de-rango`, `medicion-tipo-incompatible`.

## 10. Lo que esta etapa NO hace

- **No registra tratamientos realizados ni espacios usados** (06.04).
- **No enmienda sesiones cerradas** (06.06).
- **No define "completo"** como un gate que bloquee el cierre. El enunciado dice *"completo sólo
  cuando corresponde"*: la completitud se **calcula y se informa**, y el cierre sigue siendo la
  decisión del profesional. Bloquear el cierre por una medición faltante es lo mismo que 04.04
  rechazó al no cerrar el caso por contador.
- **No hay frontend**, por la misma causa que F4.

## 11. Migraciones reservadas y bloqueo heredado

`V51` (`resource`) y `V52` (`encounter`). Docker sigue caído: el contrato queda en `0.34.0` con
**cinco** tandas sin regenerar. Condición de salida sin cambios: **una sola regeneración desde la
rama que las tenga todas**, antes de cualquier merge.
