# AKINE C-6 — Las enmiendas versionan también tratamientos y mediciones

**Módulo:** `encounter` (M14) · **Migración:** `V71` · **Contrato:** `0.56.0` (aditivo)
**Trazabilidad:** RF-M14-010, RF-M24-005, RN-M14-006, RF-M14-004, RF-M14-005
**Cierra:** el ítem de 06.06 en `docs/fases/F6-atencion-clinica.md` —"`sesion_version` no incluye
tratamientos ni mediciones → corregir un tratamiento cerrado es imposible"—.
**Deja afuera, a propósito:** el permiso reforzado y la política temporal para enmendar. Es una
decisión pendiente del usuario (ver §7).

---

## 1. El problema

06.06 versionó el **relato** de una sesión cerrada —evaluación base y cierre— y dejó fuera los
tratamientos realizados (06.04) y las mediciones (06.03). Las dos cosas pasan a ser imposibles de
corregir una vez cerrada la atención: `TratamientoService` y `MedicionService` responden 409
`sesion-cerrada` ("corregirla exige una enmienda") y la enmienda no tiene campos para ellos. El
mensaje manda a una puerta que no existe.

Y aunque se abriera esa puerta editando en el lugar, `sesion_version` no guardaría lo que había: la
v1 diría "evolución: mejor" pero no qué intensidad de TENS ni qué ROM se midió ese día. Una
historia que se corrige sin conservar el original es historia reescrita en silencio.

## 2. Decisión: la versión lleva una foto completa de tratamientos y mediciones

Es el principio de 04.04 —**lo que puede cambiar cuelga de la versión**— aplicado a las dos tablas
hijas. Cada `sesion_version` lleva la foto **completa** de los tratamientos vigentes (con sus
parámetros) y de las mediciones de ese momento. No hay diff, igual que 06.06 §9.2: lo que hay que
poder leer es qué decía la historia en esa versión.

**Tablas y no `json`.** `V52` y `V55` dejaron escrito por qué tratamientos y mediciones son filas y
no `json`: los `CHECK` tipados que rechazan un parámetro sin tipo o un valor que no es del tipo
copiado. Una foto en `json` se saltearía justamente esos `CHECK`, y MySQL normaliza el `json` al
guardarlo (la trampa del outbox). La foto repite las columnas y los `CHECK` de las tablas vivas.

### `V71` — tres tablas, propietario `encounter`

| Tabla | Cuelga de | Contenido |
|---|---|---|
| `sesion_version_tratamiento` | `sesion_version` | `tratamiento_realizado_id`, `orden`, práctica (id + snapshot de código y nombre), técnica, zona, lateralidad, duración, profesional, espacio (id + nombre), observación |
| `sesion_version_tratamiento_parametro` | `sesion_version_tratamiento` | clave, tipo, los tres valores, unidad, orden |
| `sesion_version_medicion` | `sesion_version` | definición (id + snapshot completo), lateralidad, los tres valores, nota, registrada en/por |

Las tres llevan `organization_id NOT NULL`, uniques que empiezan por él, **sin `active`, sin
`deleted_at`, sin `@Version`, sin `updated_at`**: una foto es un hecho pasado, igual que
`sesion_version` (06.06 §4.1).

**Backfill.** Para cada `sesion_version` existente se copian los tratamientos vigentes y las
mediciones **actuales** de su sesión. Es exacto y no una aproximación: hasta esta etapa una sesión
cerrada no podía cambiar sus tratamientos ni sus mediciones (fail-closed en los dos servicios), así
que el estado de hoy es el estado de todas sus versiones.

### Las tablas vivas siguen siendo "el estado vigente"

`tratamiento_realizado` y `sesion_medicion` siguen diciendo lo vigente, como `sesion` sigue
guardando el relato vigente: la duplicación es la misma que 06.06 §2 declaró, y es la que permite
que la comparación de mediciones, el reporte y el consumo lean la tabla viva sin saber de
versiones. La enmienda escribe la tabla viva **y** la foto, en la misma transacción.

- **Tratamiento quitado** → baja lógica en `tratamiento_realizado` con el motivo de la enmienda
  como `deactivation_reason`. Nunca `DELETE`.
- **Medición quitada** → `DELETE` de `sesion_medicion`, que es lo que la tabla ya hacía en una
  sesión abierta (`MedicionService.borrar`). El valor no se pierde: queda en la foto de la versión
  anterior, que es inmutable.
- **Parámetros** → borrar y reinsertar, igual que `TratamientoService.reemplazar`: son atributos
  del tratamiento (cabecera de `V55`). La foto anterior los conserva.

## 3. El pedido

`POST .../sesiones/{id}/enmiendas` gana dos listas **opcionales**:

- `tratamientos`: la lista completa de tratamientos que debe quedar. Cada ítem con `tratamientoId`
  reemplaza ese tratamiento; sin `tratamientoId` es uno nuevo; un vigente que no aparece se da de
  baja.
- `mediciones`: la lista completa de mediciones, identificadas por `(definicionId, lateralidad)`.
  Lo que no aparece se quita.

**Ausente (`null`) = "no se tocan"; lista vacía = "no queda ninguno".** Es la única excepción al
"reemplazo completo" de 06.06 §8, y es deliberada: el contrato es aditivo y un cliente de 0.55 que
no conoce estos campos no puede, por omitirlos, borrar todos los tratamientos de una sesión
cerrada. La pantalla que los conoce manda la lista entera.

`SesionVersionResponse` gana `tratamientos` y `mediciones` con la foto de esa versión. Aditivo.

## 4. La regla que manda: una enmienda no puede cambiar el conjunto de prácticas realizadas

06.06 §5 dejó la enmienda sin efectos económicos porque "nada de lo enmendable los afecta": los
observadores del cierre leían asistencia, oferta y precio. **06.04 agregó una cuarta lectura que
06.06 no tenía en cuenta:** `SesionCerrada.practicasRealizadas()`, el conjunto de prácticas de los
tratamientos vigentes, que `ConsumoDeAutorizacionService` usa para elegir **qué autorización
consumir**. Abrir los tratamientos a la enmienda sin más reabriría esa bisagra: cambiar
"kinesiología" por "fonoaudiología" después del cierre dejaría consumida la autorización
equivocada, sin compensación — el mismo defecto que 06.04 corrigió, ahora por la puerta de atrás.

Por eso: **el conjunto (sin repetidos) de `practicaId` de los tratamientos vigentes tiene que ser
el mismo antes y después de la enmienda.** Si no, 409 `enmienda-cambia-practicas`. Se puede
corregir técnica, zona, lateralidad, duración, profesional, espacio, observación y parámetros;
agregar o quitar un tratamiento de una práctica que ya estaba; y nada que cambie qué se
consumió. Cambiar la práctica realizada es una compensación económica (revertir un consumo en M17
y consumir otro), con dueño en otro módulo, igual que la asistencia en 06.06 §9.1.

## 5. Validaciones al enmendar, y por qué no son las mismas que al registrar

Una enmienda corrige un hecho pasado; lo que no cambia no se revalida contra el catálogo de hoy.

- **Práctica**: si el ítem conserva la práctica que ya tenía, se conserva su snapshot sin exigir
  vigencia (la práctica pudo darse de baja en estos dos años). Uno nuevo se valida completo.
  Como el conjunto de prácticas no cambia (§4), una práctica nueva nunca entra por acá.
- **Espacio**: igual. Si cambia, se valida en el instante de la atención (`iniciadaEn`), que es lo
  que `TratamientoService` ya hace.
- **Profesional**: si no cambia, se conserva aunque hoy no tenga membership vigente. Si cambia, se
  exige membership vigente en la sede.
- **Medición**: la definición tiene que existir. Una medición **nueva** exige definición activa;
  una existente se corrige aunque la definición se haya dado de baja ("la baja del catálogo no
  cascadea", 06.03). El rango se valida siempre. **Una medición que no cambió no se reescribe**: si
  no, la foto atribuiría al que enmienda una medida que no tocó.
- **Motivo**: se exige al principio, antes de escribir nada, porque además es el
  `deactivation_reason` de los tratamientos que se quitan.

## 6. Concurrencia

Sin cambios respecto de 06.06 §10, y por eso funciona: la enmienda **ensucia la cabecera**
(`ultimo_numero_version`), el `saveAndFlush` emite el `UPDATE ... WHERE version = N` **antes** de
tocar tratamientos o mediciones, y la segunda enmienda concurrente se queda esperando el lock de
fila y termina en 409. Los tratamientos y mediciones se escriben **sin** `avanzarVersion`: la
recíproca de la regla —force-increment solo donde la escritura no toca el padre— dice que acá
avanzaría dos veces.

## 7. Lo que esta etapa NO hace

1. **Permiso reforzado y política temporal para enmendar.** Sigue siendo `sesion:register` +
   propiedad, sin ventana. Es decisión pendiente del usuario (F6, segundo ítem de 06.06).
2. **Cambiar la práctica realizada** (§4) ni la asistencia (06.06 §9.1).
3. **`X-Justificacion-Acceso` en `encounter`**, que la tabla de paquetes asocia a C-6: depende de
   C-7 y no es parte de versionar.
4. **Frontend** (D-g).

## 8. Design challenge

1. **Ownership.** Las tres tablas son de `encounter`, que ya es dueño de `sesion_version`,
   `tratamiento_realizado` y `sesion_medicion`. Ningún otro módulo las lee: el consumo sigue
   leyendo `practicasRealizadas` del hecho `SesionCerrada`, que no cambia.
2. **Ciclos.** No hay aristas nuevas entre módulos. `SesionService` pasa a usar
   `TratamientoService` y `MedicionService`, los tres en `encounter.application`. Las lecturas de
   catálogo, espacio y membership ya existían por `resource.spi` y `organization.spi`.
3. **Tenant.** Las tres llevan `organization_id NOT NULL` con FK, y sus uniques empiezan por él
   (`EsquemaMultiTenantIT` lo verifica). La foto se arma con consultas filtradas por organización.
4. **Reglas maestras.** HC/Caso/Sesión: la foto cuelga de la versión de **la sesión**, no del
   caso ni del plan; planificado ≠ realizado se respeta (no se toca `plan_item`). Obligación/cobro:
   ninguna enmienda devenga, anula ni consume (§4 lo vuelve estructural para las prácticas).
5. **Baja lógica.** Tratamiento quitado = baja lógica con motivo. Los dos borrados físicos
   (parámetros y medición quitada) son sobre filas que **ya** se borraban así en una sesión
   abierta, y su contenido queda en la foto inmutable de la versión anterior. Ninguna versión ni
   foto se borra ni se modifica (`updatable = false` en todas las columnas).
6. **Contrato.** Aditivo: dos campos opcionales en el request, dos en la respuesta de versiones y
   un `type` nuevo. `0.54.0 → 0.56.0` (la `0.55.0` está tomada por otro paquete).
7. **Ruta crítica.** Los cimientos existen: 06.03, 06.04, 06.05 y 06.06 están en `main` con ITs.
8. **El caso que rompe el diseño.** Un profesional corrige, dos semanas después, "Electroterapia"
   por "Magnetoterapia" en una sesión de un paciente con autorizaciones de las dos prácticas. Sin
   §4, la tabla viva diría magnetoterapia, la foto también, y la unidad consumida seguiría siendo
   de electroterapia: el financiador recibiría una prestación que la historia clínica niega. Con
   §4 es 409 y el registro sigue coherente con lo consumido; corregirlo es una compensación
   explícita en M17. Segundo caso: dos enmiendas concurrentes, una que quita un tratamiento y otra
   que corrige su zona. La segunda se queda en el lock de `sesion` antes de leer los tratamientos y
   termina en 409: nunca corrige la zona de un tratamiento que la primera ya dio de baja.
