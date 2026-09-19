# Design challenge — AKINE-04.04

Paso obligatorio de `CLAUDE.md` §3. Ocho preguntas respondidas por escrito antes de `tasks`.
**Este documento manda sobre `AKINE-04.04-plan-de-tratamiento.md`** donde discrepen.

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

`plan_tratamiento`, `plan_tratamiento_version`, `plan_item`, `plan_numerador` y `plan_evento`:
**propietario `clinical`**, ningún otro módulo las lee ni las escribe.

**El caso que había que mirar es `plan_item.cantidad_realizada`, y la respuesta es que esa columna
no existe.** Si existiera, su dueño real sería `encounter` —que es quien sabe cuándo una sesión se
cerró— y tendríamos una columna de `clinical` que sólo otro módulo puede mantener correcta. Ese es
el camino por el que un contador se desincroniza: no por mala fe, sino porque el dueño del dato y el
dueño de la fila son distintos. **Derivar el avance al leer elimina la pregunta en vez de
contestarla.**

**Veredicto: pasa.** Regla vinculante para la implementación: **ninguna columna de este esquema
guarda cantidades realizadas ni canceladas, y ningún DTO de entrada las acepta.**

## 2. Ciclos — ¿la dependencia es unidireccional? ¿Pasa ArchUnit?

Aristas nuevas: `clinical → offering.spi` (validar la oferta; ya existe desde 04.03) y la sonda
`clinical.spi.RealizadoEnElCasoProbe`, **declarada en `clinical` e implementada en `encounter`**.

Esa segunda es la que hay que justificar: `clinical` necesita un dato que vive en `encounter`. La
forma equivocada es que `clinical` importe `encounter`; la correcta es la que 05.03 ya usó con
`scheduling.spi.AtencionProbe` —la sonda se declara donde se consume y la implementa quien tiene el
dato— porque **`encounter → clinical.spi` ya existe** desde 06.01 y 04.03. No se agrega ninguna
arista nueva: se agrega peso a una que ya va en ese sentido.

**Veredicto: pasa.** El riesgo real es el atajo: un agente que necesite el estado de una sesión va a
querer importar `encounter.domain`. **Prohibido.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Las cinco, y todos los uniques e índices empiezan por él. `plan_tratamiento_version` y `plan_item`
lo llevan **aunque sea derivable**, por el mismo motivo de 04.02 y 04.03: derivarlo obliga a un join
para filtrar por tenant, y el día que alguien escriba la consulta sin el join tiene una fuga que
ningún test de la etapa ve, porque los tests de una etapa usan un solo tenant.

El unique que importa: `UNIQUE (organization_id, caso_clinico_id, activo_key)` — un solo plan activo
por Caso.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿confunde HC/Caso/Sesión, Plan/Turno/Sesión, u Obligación/Cobro/Caja?

La que esta etapa puede romper es la **2** (Plan ≠ Turno ≠ Sesión), y la rompe de tres formas
distintas si uno se descuida:

1. **Guardando realizadas.** Resuelto en §1: no hay columna.
2. **Creando turnos.** El plan propone una regla de recurrencia y **no agenda nada**. Agendar es de
   `scheduling`, y este módulo no lo importa ni por el `spi`. El día que se agende desde el plan,
   el que llama es `scheduling`, no al revés.
3. **Cerrando el caso al completar el contador.** RN-M11-004 lo prohíbe explícitamente. El sistema
   avisa; decide el profesional.

**Veredicto: pasa, con las tres escritas como vinculantes.** La tercera es la que más se parece a
una mejora de producto y por eso es la más peligrosa.

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

No. El plan no se borra: se **finaliza**. Las versiones **no se borran nunca, ni lógicamente** —no
tienen `active`, igual que `entrada_clinica_version`—: una versión es un hecho pasado y darla de
baja sería reescribir historia clínica (ADR-0011).

Los ítems tampoco se borran al modificar el plan: **la versión nueva trae su propio juego de
ítems** y los de la versión anterior quedan intactos. Ese es el punto de colgarlos de la versión.

`plan_evento` es append-only, como `caso_evento` y `turno_evento`.

**Veredicto: pasa.**

## 6. Contrato — ¿aditivo o incompatible?

Aditivo puro: diez operaciones nuevas, ninguna existente cambia de forma. **`0.31.0` → `0.32.0`**.

**Y por tercera vez queda sin regenerar.** `0.30.0` y `0.31.0` tampoco se regeneraron. La deuda es
consciente y su condición de salida no cambia: **una sola regeneración, desde la rama que tenga las
tres, antes de cualquier merge.** El riesgo que sí crece es otro: cuantas más operaciones se
acumulan sin regenerar, **menos sabemos si springdoc va a desambiguar algún `operationId`**, que es
un problema que este repositorio ya tuvo.

**Veredicto: pasa el cambio, no pasa la verificación.**

## 7. Ruta crítica — ¿tiene sus cimientos, o lo estoy adelantando?

`... → Historia Clínica → Caso → **Plan** → Turno → Check-in → Sesión → ...`

Dependencias declaradas: **04.03** (escrita, en la rama de la que ésta sale) y **02.07** (cerrada).
Están.

**Lo que sí se adelantaría es el consumo de autorizaciones**, y por eso la cantidad autorizada se
**declara** en vez de leerse de M17: 04.05 es la etapa que estrena
`consultarElegibilidadAdministrativa`, que hoy no tiene un solo consumidor. Meter medio consumo acá
deja dos caminos que después hay que unificar.

**Veredicto: pasa.**

## 8. El caso que rompe el diseño

**"Un paciente con un plan activo de 20 sesiones, 8 realizadas, al que el profesional le cambia la
frecuencia y le agrega una práctica — mientras una sesión se está cerrando."**

Rompe cuatro cosas si el diseño está mal:

1. **El avance se recalcula contra el plan nuevo y las 8 sesiones viejas quedan contadas contra
   ítems que no existían.** Lo resuelve que los ítems cuelguen de la **versión**: el avance de cada
   versión se lee contra sus propios ítems. **Consecuencia que hay que aceptar y mostrar:** el
   avance "8 de 20" de la versión 1 y el "8 de 24" de la versión 2 son **dos números distintos y los
   dos correctos**. La pantalla tiene que decir de qué versión habla.
2. **La sesión que se está cerrando no participa de ninguna transacción del plan**, y está bien: el
   cierre no toca el plan y el plan no toca la sesión. El avance se lee después y la incluye o no
   según haya commiteado. **No hay lectura sucia posible porque no hay contador que actualizar.**
   Es la ventaja concreta de derivar.
3. **Dos modificaciones concurrentes del plan** numeran la misma versión. Lo resuelve el contador de
   la cabecera con `UPDATE ... ultimo_numero + 1` y el unique
   `(organization_id, plan_id, numero_version)` como red. **Sin `OPTIMISTIC_FORCE_INCREMENT`**: la
   cabecera queda sucia, así que forzarlo la haría avanzar dos veces y devolvería `leída+1` — el
   defecto que 04.02 pagó y corrigió.
4. **Activar un plan nuevo mientras el viejo sigue activo.** La finalización del anterior y la
   activación del nuevo van en **la misma transacción**, y el unique de plan activo por caso es lo
   que hace que dos activaciones concurrentes no dejen dos vivos: la segunda choca y recibe 409.

> **Quinta cosa, la que no rompe hoy pero va a doler en 04.05:** el ítem cuenta realizadas **por
> oferta**, no por práctica individual, porque los tratamientos realizados son 06.04. Cuando 04.05
> compare "autorizadas" contra "realizadas" para decidir elegibilidad, va a estar comparando dos
> granularidades distintas. **Queda declarado ahora para que 04.05 no lo descubra al final.**

**Veredicto: pasa, con las cinco anotadas como vinculantes.**

---

## Veredicto global

**El diseño pasa el challenge.** Se avanza a `tasks`.

Cuatro cosas quedan declaradas como límite, no como resuelto:

1. **El avance se cuenta por oferta, no por práctica.** Se afina cuando llegue 06.04, y 04.05 tiene
   que saberlo antes de empezar.
2. **La cantidad autorizada se declara a mano.** La costura real es 04.05.
3. **El contrato queda en drift por tercera vez**, y el riesgo que crece con la acumulación es la
   desambiguación silenciosa de `operationId`.
4. **"Lectura administrativa mínima" se cumple de rebote** —el administrativo no tiene `hc:read`—,
   no por una decisión de la matriz de permisos. Pregunta abierta para el usuario, igual que en
   04.03.
