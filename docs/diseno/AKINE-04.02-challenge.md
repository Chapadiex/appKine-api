# Design challenge — AKINE-04.02

Paso obligatorio de `CLAUDE.md` §3. Ocho preguntas, respondidas por escrito antes de `tasks`.
**Este documento manda sobre `AKINE-04.02-timeline-y-adjuntos-clinicos.md`** donde discrepen.

---

## 1. Ownership — ¿qué módulo es propietario de cada tabla nueva? ¿Algún otro la toca?

`entrada_clinica`, `entrada_clinica_version` y `adjunto_clinico`: **propietario `clinical`**, sin
excepción. Ningún otro módulo las lee ni las escribe.

El caso que había que mirar es `encounter`: la Sesión produce evolución clínica y podría tentar a
escribir una `entrada_clinica` por cada cierre. **No lo hace.** `encounter` aporta al timeline
implementando `EventoClinicoContributor` sobre **sus propias** tablas de sesión. Si escribiera
entradas, dos módulos serían dueños de la misma fila y la evolución viviría duplicada en
`sesion.evolucion` y en `entrada_clinica_version.cuerpo`, con la garantía de que en algún momento
discrepan.

**Veredicto: pasa.**

## 2. Ciclos — ¿la dependencia que introduce es unidireccional? ¿Pasa ArchUnit?

Dependencias nuevas: `encounter → clinical.spi` (para implementar el contribuyente). `encounter`
**ya** depende de `clinical.spi` desde 06.01 (`HistoriaClinicaDirectory`), así que no hay arista
nueva, sólo más peso sobre una que ya existe y ya es unidireccional.

`clinical` **no gana ninguna dependencia**: consume `List<EventoClinicoContributor>` que Spring le
inyecta, sin conocer a ningún implementador. Esa es la razón de que la costura sea una lista de
interfaces y no una llamada directa, y 04.01 la dejó escrita con ese argumento.

**Veredicto: pasa.** El riesgo real no es el ciclo sino el descuido: un agente que necesite el
título de una sesión va a querer importar `encounter.domain`. **Queda prohibido: el título viaja
dentro del `EventoClinico` que `encounter` construye.**

## 3. Tenant — ¿toda tabla nueva lleva `organization_id`? ¿Los uniques e índices lo incluyen?

Sí, las tres. Todos los uniques e índices empiezan por `organization_id`.

Un detalle que hay que hacer bien y es fácil de arruinar: `entrada_clinica_version` lleva
`organization_id` **aunque sea derivable** de su `entrada_clinica_id`. Derivarlo obliga a un join
para filtrar por tenant, y el día que alguien escriba la consulta sin el join tiene una fuga que
ningún test de la etapa va a ver porque los tests de una etapa usan un solo tenant.

El unique de contenido de `adjunto_clinico` es
`(organization_id, historia_clinica_id, checksum_sha256, deleted_key)`, con el mismo centinela
`'1970-01-01'` de V27/V40: varios `NULL` no colisionan en MySQL, y un `UNIQUE (..., deleted_at)`
protegería el histórico y desprotegería lo vigente.

**Veredicto: pasa.**

## 4. Reglas maestras — ¿confunde HC/Caso/Sesión, Turno/Sesión, u Obligación/Cobro/Caja?

La que esta etapa puede romper es la **1** (HC ≠ Caso ≠ Sesión) y la **4** (Turno ≠ Sesión).

- **HC ≠ Caso:** la entrada clínica cuelga de la **HC**, no de un Caso que todavía no existe. La
  tentación era inventar un "caso por defecto" para que el timeline pudiera agrupar; se rechaza.
  Un Caso implícito es un Caso mal hecho que después hay que desarmar, y 04.03 tendría que decidir
  qué hacer con todas las entradas que quedaron colgando de un caso fantasma. `caso_id` llega
  **nullable** cuando 04.03 llegue.
- **Turno ≠ Sesión:** el Turno **no contribuye al timeline**. Está argumentado en §2.3 del diseño
  y es la aplicación directa de DP-05 y RN-M09-005.
- **HC ≠ Sesión:** el timeline indexa la sesión, no la copia. `EventoClinico` no trae contenido
  clínico y su javadoc de 04.01 ya lo fijaba: *"el timeline es un índice, no un visor"*.

**Veredicto: pasa, con una regla que hay que sostener en la implementación:** ningún
`EventoClinico` puede llevar texto de evolución, diagnóstico ni medición. Sólo `titulo`, que es
una etiqueta de tipo de hecho, no contenido.

## 5. Baja lógica — ¿hay algún borrado físico de información histórica relevante?

No. Entradas y adjuntos usan el cuarteto `active`/`deleted_at`/`deactivation_reason`/`deleted_key`.
Las versiones de contenido **no se borran nunca, ni lógicamente**: no tienen `active`, a propósito.
Una versión es un hecho pasado; darla de baja sería reescribir historia clínica y ADR-0011 lo
prohíbe.

El binario del adjunto dado de baja **tampoco se borra del disco** en esta etapa. Un job de
limpieza es otra cosa y necesita una política de retención que nadie escribió; borrarlo acá
significaría que dar de baja por error es irreversible.

**Veredicto: pasa.**

## 6. Contrato — ¿el cambio de API es aditivo o incompatible?

Aditivo puro: doce operaciones nuevas, ninguna existente cambia de forma. **`0.29.0` → `0.30.0`**,
versión menor. Bump en `pom.xml` (`akine.contract.version`) **y** en `application.yml`; los dos, o
el test de versión falla contra un proceso viejo.

La única firma que cambia es la de `EventoClinicoContributor`, que es **interna** —paquete `spi`,
no HTTP— y hoy tiene cero implementaciones. No toca el contrato.

**Veredicto: pasa.**

## 7. Ruta crítica — ¿tiene sus cimientos, o lo estoy adelantando?

`Tenant → Consultorio → Membership → Espacios → Persona/Paciente → Cobertura/Convenio →
Historia Clínica → Caso → ...`

04.02 se apoya en **04.01** (HC, cerrada) y **03.02** (adjuntos y su storage, cerrada). Las dos
dependencias que el plan §14 declara están construidas y verificadas. No se adelanta ningún
consumidor: el timeline es lectura, y sus contribuyentes son módulos ya cerrados.

**El que sí se adelantaría es el Caso**, y por eso no se construye acá.

**Veredicto: pasa.**

## 8. El caso que rompe el diseño

**"Un paciente crónico de seis años, con 900 sesiones, 400 entradas y 200 estudios, y un
administrativo que abre su ficha desde el mostrador."**

Rompe tres cosas a la vez si el diseño está mal:

1. **El timeline sin cursor trae 1.500 filas** de cuatro fuentes y tumba el percentil 95 del
   endpoint más usado del módulo. Lo resuelve el keyset con tope por contribuyente. **Lo que el
   diseño NO resuelve y queda declarado: el costo crece linealmente con la cantidad de fuentes, y
   hoy son cuatro.**
2. **El administrativo no debería estar viendo eso.** `hc:read` con justificación declarada lo
   deja pasar y lo audita, que es exactamente lo que DP-03 pide y es **deliberadamente** más débil
   que negarlo: negar por rol rompe la recepción real de un centro. El control es la auditoría, y
   por eso `TIMELINE_ACCESSED` y `ADJUNTO_CLINICO_DOWNLOADED` no son opcionales.
3. **Dos profesionales enmiendan la misma entrada al mismo tiempo.** Las dos enmiendas son
   versiones **nuevas**, así que ninguna pisa a la otra — que es la ventaja de versionar en filas
   sobre versionar en columnas. Lo que sí hay que serializar es la **numeración**: dos
   `MAX(numero_version)+1` simultáneos dan el mismo número. Se resuelve con el unique
   `(organization_id, entrada_clinica_id, numero_version)` **y** el lock optimista
   `OPTIMISTIC_FORCE_INCREMENT` sobre la cabecera — que es la lección exacta de 02.07: un
   `@Version` sobre el padre no protege escrituras que sólo tocan tablas hijas **a menos que se
   fuerce el incremento**. El perdedor recibe 409 y reintenta.

> **Cuarta cosa, la que más cuesta si se olvida:** el `INSERT` de la versión y el `UPDATE` de la
> cabecera van en la **misma transacción**, y la numeración se resuelve **antes** de escribir
> contenido. Es el patrón de 06.05 (`UPDATE ... ultimo_numero + 1`, no `MAX+1`) y de la regla 2 del
> Paquete B.

**Veredicto: pasa, con las cuatro condiciones anotadas como vinculantes para la implementación.**

---

## Veredicto global

**El diseño pasa el challenge.** Se avanza a `tasks`.

Tres cosas quedan declaradas como límite, no como resuelto:

1. El salteo de eventos con timestamp idéntico al cursor y más de `limite` colisiones en una sola
   fuente (§2.1 del diseño).
2. El costo lineal del timeline en cantidad de fuentes.
3. La duplicación del adaptador de storage, con su condición de salida escrita: **tercer consumidor
   → se extrae a `platform`.**
