# Design challenge — AKINE-06.03

Paso obligatorio de `CLAUDE.md` §3. **Manda sobre `AKINE-06.03-examen-y-mediciones.md`.**

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva?

- `medicion_definicion` → **`resource`**, que ya es dueño de `especialidad`, `practica`,
  `nomenclador` y `nomenclador_item` (M06) y expone `CatalogoDirectory`.
- `sesion_medicion` → **`encounter`**, que es dueño de `sesion`.

`encounter` **no escribe** el catálogo: lo valida por `resource.spi`. `resource` **no sabe** que
existen mediciones tomadas.

**El caso que había que mirar es `medicion_definicion`, porque la intuición la pone en
`encounter`** —"es parte de la evaluación"—. No lo es: una definición de medida es un **catálogo**,
vive más que cualquier sesión, la comparten todas las especialidades y **la plataforma siembra las
universales**. Ponerla en `encounter` daría dos módulos dueños de la misma clase de cosa y dejaría a
`resource` sin la mitad de su catálogo.

**Veredicto: pasa.**

## 2. Ciclos — ¿unidireccional? ¿Pasa ArchUnit?

Arista: **`encounter → resource.spi`**. ¿Existe ya? `encounter` depende hoy de `clinical.spi` y de
`offering.spi`. **Hay que verificarlo, no suponerlo**, y el método es el que 04.05 dejó fijado a
golpes: **poner una clase sonda y dejar que ArchUnit hable**, porque `SlicesRuleDefinition` busca
ciclos de **cualquier longitud** y razonar el grafo de memoria sólo encuentra los de dos saltos —
que es exactamente el error que cometió el challenge de 04.05 al dar por buena la arista
`person → encounter.spi`.

**Condición vinculante para la implementación:** antes de escribir el servicio, **poné la sonda y
corré `ModuleArchitectureTest`**. Si `encounter → resource.spi` cierra un ciclo, la salida es
invertir la arista —declarar el puerto de validación en `encounter.spi` e implementarlo en
`resource`—, no relajar la regla.

**Veredicto: pasa condicionado a esa verificación.**

## 3. Tenant — ¿`organization_id` en todo? ¿Uniques e índices?

`sesion_medicion`: sí, y encabeza su unique
`(organization_id, sesion_id, definicion_id, lateralidad)`. Lo lleva **aunque sea derivable** de la
sesión, por el mismo motivo de todas las etapas de F4.

`medicion_definicion` es la excepción **ya establecida y aprobada**: es un catálogo global + por
tenant, con `organization_id` **nullable** y `owner_key = IFNULL(organization_id, 0)` en el unique.
ADR-0021 y ADR-0023 lo cubren; 02.05 pagó el error de filtrar por `organization_id` en vez de por
`owner_key`.

> **La trampa concreta, escrita para que nadie la repita:** filtrar por `organization_id` deja fuera
> las definiciones globales, y filtrar con `IS NULL OR = :org` en un unique **no funciona** porque
> varios `NULL` no colisionan en MySQL. Es `owner_key`, siempre.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿confunde algo?

- **Sesión ≠ Turno ≠ Plan:** una medición cuelga de la **sesión**, que es la atención realizada. No
  toca turnos ni planes.
- **Historia clínica no se reescribe (regla 10, ADR-0011):** una medición de una sesión **cerrada**
  no se edita ni se borra. Enmendar una sesión cerrada es **06.06** y no está acá. Sobre una sesión
  **en curso**, `PUT` y `DELETE` son el autosave y no reescriben nada histórico.
- **La propiedad no es un permiso** (06.01): escribir en la atención ajena es **409, no 403**.

> **El riesgo real de esta etapa es el snapshot.** Si la unidad y el nombre no se congelan en la
> fila, un `UPDATE` sobre el catálogo **reescribe el significado de mediciones pasadas** sin tocar
> una sola fila de `sesion_medicion`. Es reescritura de historia clínica por la puerta de atrás, y
> ningún test de la etapa la vería porque el dato cambia en otra tabla.

**Veredicto: pasa, con el snapshot como condición dura.**

## 5. Baja lógica — ¿borrado físico?

En el catálogo, no: baja lógica con el cuarteto de siempre, y **no cascadea**.

En `sesion_medicion` **sí hay `DELETE` físico**, y está acotado: **sólo sobre sesión en curso**, y es
el "borrar una medición que cargué por error" del autosave. Sobre sesión cerrada, la operación no
existe. Es el mismo criterio con el que 04.04 admitió borrar ítems de un plan en `BORRADOR`: **lo
que nunca se cerró no es información histórica.**

**Condición vinculante:** el `DELETE` tiene que rechazar con 409 si la sesión está cerrada, y eso se
decide **en el servicio**, no en la pantalla.

**Veredicto: pasa.**

## 6. Contrato — ¿aditivo?

Ocho operaciones nuevas, ninguna existente cambia. **`0.33.0` → `0.34.0`**, menor.

**Quinta tanda sin regenerar.** El riesgo que crece es siempre el mismo y ya no es hipotético:
**cuantas más operaciones se apilan, menos sabemos si springdoc desambiguó algún `operationId`** —
problema que este repositorio ya tuvo y cuyo gate **no se puede correr**.

**Veredicto: pasa el cambio, no pasa la verificación.**

## 7. Ruta crítica — ¿tiene sus cimientos?

Dependencia declarada: **06.02** (cerrada). El catálogo se apoya en **02.05** (cerrada) y la sesión
en **06.01** (cerrada). Están.

**Veredicto: pasa.**

## 8. El caso que rompe el diseño

**"Una kinesióloga mide ROM de rodilla izquierda y derecha en la primera evaluación, el centro
cambia la unidad del test seis meses después, y en la re-evaluación quiere comparar."**

1. **La comparación miente.** Si la unidad no está congelada en la fila, los 90 de marzo y los 90 de
   septiembre se muestran como iguales y son medidas distintas. **Lo resuelve el snapshot**, y la
   comparación tiene que **mostrar la unidad de cada lado, no asumir que coinciden** — y **negarse a
   calcular un delta cuando difieren**. Esa es la condición que un test tiene que fijar.
2. **Los dos lados se pisan.** Lo impide que `lateralidad` esté en el unique. Y es la razón de que
   `BILATERAL` no exista: una sola fila para dos lados es un promedio disfrazado.
3. **La re-evaluación no encuentra baseline** porque la sesión anterior no está cerrada, o no
   existe. **No es un error**: `anterior` viene `null`.
4. **El autosave duplica la medición.** Lo impide el unique más el `PUT` idempotente: repetir
   actualiza, no inserta.

> **Quinta cosa, la que el diseño no resuelve y hay que declarar:** "la sesión cerrada anterior del
> mismo paciente" es ambiguo cuando el paciente tiene **varios Casos abiertos** —una rodilla y un
> hombro, que 04.03 permite explícitamente—. Comparar el ROM de rodilla contra la sesión del hombro
> es comparar contra nada. **Decisión: la comparación se acota al mismo Caso cuando la sesión tiene
> caso**, y cae a "la anterior del paciente" sólo cuando no lo tiene. Y aun así, comparar mediciones
> de la misma definición entre casos distintos seguiría siendo posible por otro camino: **queda
> fuera de alcance y declarado.**

**Veredicto: pasa, con las cinco anotadas.**

---

## Veredicto global

**Pasa.** Cuatro límites declarados:

1. **La arista `encounter → resource.spi` está supuesta, no verificada.** Se verifica con una clase
   sonda **antes** de escribir el servicio.
2. **La comparación se acota al Caso**, y comparar entre casos distintos queda fuera de alcance.
3. **"Completo" se informa, no se gatea**: no bloquea el cierre de la sesión.
4. **Quinta tanda de contrato sin regenerar.**
