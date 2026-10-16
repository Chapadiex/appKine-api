# AKINE-08.05 — Atención clínica grupal por participante (M14, M28)

Diseño de la etapa, con su **design challenge** (CLAUDE.md §3) en el §10.

Trazabilidad: RF-M14-011, RF-M14-012, RF-M14-013, RF-M14-014 (el lado negativo), RN-M14-001,
RN-M14-002, RN-M14-005, RN-M14-006, RN-M14-007, RN-M14-008, RN-M14-009, RN-M14-010; plan
`AKINE_IMPLEMENTATION_PLAN.md` etapa 08.05. Sale de 08.04 (`derivacion_clinica`, V63), 06.01–06.06
(la Sesión entera) y 04.03 (numeración dentro del Caso).

RF-M14-015 (`PrestacionRealizada`) **no** se implementa: RN-M14-011 la declara "abstracción
futura" y no hay consumidor (reporting no la pide). Ver §8.

---

## 1. La decisión de modelo: la atención de un participante **es una Sesión**

Se reusa `encounter.Sesion`, **una por participante y por Caso**. No hay agregado propio.

| Opción | Qué implicaba | Veredicto |
|---|---|---|
| **A. Reusar `Sesion`** con el origen grupal en tres columnas nuevas | Borrador, evaluación, tratamientos, mediciones, cierre con sus dos numeradores, enmienda versionada, lectura auditada, timeline y consumo de autorizaciones: **todo M14 sin una línea nueva** | **Elegida** |
| B. Agregado `AtencionGrupal` con filas por participante | Reescribir —y después mantener en paralelo— todo M14. La numeración dentro del Caso (RN-M14-010) y la enmienda (RN-M14-006) tendrían dos implementaciones | Rechazada |
| C. Una Sesión de la clase con N participantes | Es exactamente "una Sesión compartida entre pacientes", que el plan prohíbe, y la "evolución clínica global que sustituya registros individuales" que RF-M14-013 prohíbe | Rechazada |

Por qué A es la correcta y no sólo la barata:

1. **El plan lo dice literal**: "Reutilizar dominio clínico sin una Sesión compartida entre
   pacientes" y "no duplicar el agregado Sesión".
2. **RN-M14-009 y RN-M14-010**: "puede existir una prestación clínica individual por participante"
   y "la numeración continúa siendo por Caso Clínico y no por clase". Una Sesión por participante
   con su `caso_id` ya numera dentro del Caso al cerrar (04.03): no hay nada que agregar.
3. **CA-M14-013-06** ("cerrar la atención de María no finaliza la de Juan") y **CA-M14-012-06**
   ("dos pacientes de la misma clase obtienen números independientes según sus casos") salen
   **gratis**: son dos Sesiones distintas. `AtencionGrupalIT` lo mide.
4. **Las reglas maestras ya están en la Sesión**: propiedad del profesional (409 `sesion-ajena`,
   no 403), control optimista, idempotencia del cierre antes de pedir número, cierre clínico ≠
   cobro, enmienda con motivo. Un agregado nuevo tendría que reimplementarlas, y la segunda copia
   envejece sola.

Lo que **sí** cambia en la Sesión es su **origen**. Hasta hoy podía salir de un turno o de nada;
ahora puede salir de una **derivación clínica** (08.04). Dos diferencias, y ninguna más:

- **No tiene turno.** Turno ≠ Clase (DP-05). `turno_id` es `NULL`.
- **Tiene Caso siempre, y no cambia.** Derivar *es* elegir el Caso (08.04 §7). `cambiarCaso` sobre
  una Sesión grupal es 409 `caso-de-atencion-grupal`.

> **08.04 abre la puerta, 08.05 entra.** La derivación dice "esta participación pertenece a este
> Caso"; la Sesión dice "se le prestó una atención documentada". Ninguna transición administrativa
> prueba una prestación (DP-05): ni la asistencia, ni la derivación, ni abrir la Sesión. Lo prueba
> **cerrar** la Sesión, como siempre.

## 2. El reparto entre módulos

```
activity.api.AtencionGrupalController
  └─ activity.application.AtencionDeParticipantesService     valida lo GRUPAL
       ├─ contexto, sede, inscripcion:read, clase e inscripción de la sede
       ├─ la oferta de la CLASE genera registro clínico       (offering.spi)   RN-M14-008
       ├─ asistencia FOR UPDATE, la persona estuvo            (lock, READ_COMMITTED)
       └─ encounter.spi.AtencionGrupal.iniciar                 valida lo de la SESIÓN
            └─ encounter.application.AtencionGrupalService
                 ├─ sede del tenant, sesion:register, membership (= profesional dueño)
                 ├─ clinical.spi.DerivacionClinicaRegistry.paraAtender   valida lo CLÍNICO
                 │    └─ hc:write + relación asistencial o justificación, derivación
                 │       vigente de esa participación (elige o rechaza), audita el acceso
                 ├─ idempotencia: la Sesión viva de esa derivación
                 ├─ el Caso de la derivación sigue activo     (clinical.spi.CasoDirectory)
                 └─ INSERT sesion + SESION_INICIADA, en la MISMA transacción
```

Es la regla que 08.04 dejó escrita en `DerivacionClinicaRegistry`: **el llamador autoriza lo suyo
y cada dueño autoriza lo suyo; ninguna decisión se toma dos veces**. `activity` no lee ni guarda un
dato clínico: lo que recibe son ids, estados y números.

**Arista nueva: `activity → encounter.spi`**. Ninguna otra. `encounter → clinical.spi` existe desde
06.01; la sonda de la reversión (§5) la implementa `encounter` contra un `spi` de `clinical`, que es
la misma dirección.

**Por qué `encounter` resuelve la derivación y no `activity`**: si `activity` la consultara y le
pasara a `encounter` "la historia es H y el Caso es C", `encounter` estaría escribiendo historia
clínica con datos que afirma otro módulo. Lo que `activity` afirma es lo que sabe —clase,
asistencia, persona—; lo clínico lo pregunta quien escribe.

**Por qué un método nuevo `paraAtender` y no `consultar`**: `consultar` autoriza con `hc:read` y
devuelve todas las derivaciones. Abrir una Sesión es **escritura** clínica: `hc:write`. Y elegir la
derivación —una sola vigente, o la nombrada— es una regla clínica (§4), que vive en `clinical`.

## 3. La tabla: tres columnas en `sesion` (V102)

`sesion` es de `encounter`. **No hay tabla nueva.**

| Columna | Por qué | FK |
|---|---|---|
| `derivacion_id` | El ancla: de qué participación, persona y Caso. | → `derivacion_clinica`: misma dirección que `encounter → clinical.spi`, como `fk_sesion_historia_clinica` de V33 |
| `participacion_id` | `asistencia_actividad.id`. Redundante con la derivación y no sobra: el panel pregunta "qué atenciones tienen estas asistencias" sin pasar por `clinical`, que audita cada lectura | **Sin FK**: es de `activity`, y una FK iría contra la arista de código (mismo argumento que V63 §4) |
| `clase_id` | Filtro del panel (`ix_sesion_clase`) y marca de origen grupal en el hecho del cierre (§7) | **Sin FK**, ídem |

- `uk_sesion_derivacion (organization_id, derivacion_id, deleted_key)`: **una Sesión viva por
  derivación**, o sea por participante y Caso, porque la derivación ya es única por participación
  y Caso vigente (`uk_derivacion_destino`). Es el respaldo, no el mecanismo (§4).
- `ck_sesion_origen_grupal_completo`: las tres van juntas o ninguna.
- `ck_sesion_origen_grupal_sin_turno_con_caso`: grupal ⇒ sin turno y con Caso. La aplicación lo
  impide antes (409); el CHECK es la red.

## 4. Reglas de la apertura

**Orden de los gates** (fijo, y cada uno sin efectos si falla):

1. Contexto, sede del tenant, `inscripcion:read`, clase e inscripción de la sede (404 uniforme).
2. La oferta **de la clase** genera registro clínico, o 409 `oferta-no-clinica` (RN-M14-008,
   RF-M14-014: una clase de Yoga no llega a `encounter`).
3. Asistencia leída **con `FOR UPDATE`**; la persona estuvo (`PRESENTE`/`PRESENTE_TARDE`) o 409
   `participacion-sin-asistencia`.
4. `sesion:register` en la sede; membership activa (quien abre es el profesional dueño).
5. `clinical`: `hc:write` con relación asistencial o justificación (403
   `justificacion-de-acceso-requerida`); derivación vigente de esa participación y esa persona.
   Ninguna, o la nombrada no vigente → 409 `participacion-sin-derivacion`; varias sin nombrar →
   409 `derivacion-ambigua` con `derivacionIds`. Se audita `DERIVACION_CLINICA_ACCESSED` con
   `para = ATENDER`.
6. **Idempotencia**: si la derivación ya tiene Sesión viva, se devuelve tal cual (`yaExistia`).
7. El Caso de la derivación sigue activo y es de su historia (409 `caso-clinico-cerrado`).
8. INSERT + `SESION_INICIADA` con `origen`, `claseId`, `derivacionId`.

**La idempotencia va después del acceso clínico**, al revés que el inicio desde un turno:
devolverle una Sesión existente a quien no tiene acceso a ese paciente le diría que ese paciente
está en tratamiento. Y va **antes** del Caso: una atención ya abierta no se niega porque el Caso se
cerró después, mismo criterio que el cierre de `SesionService`.

**Concurrencia.** Abrir, derivar y revertir la misma participación **serializan sobre la fila de
`asistencia_actividad`** (`FOR UPDATE`, `READ_COMMITTED`). Dos inicios simultáneos: el segundo
espera y su relectura ve la Sesión del primero (`AtencionGrupalIT.inicios_simultaneos`). Una
reversión simultánea a un inicio: la que llega segunda ve lo que la primera commiteó. Para eso
**`DerivacionService.revertir` pasó a tomar el mismo lock** (antes leía la asistencia sin lock y
en `REPEATABLE READ`).

**El profesional dueño.** La Sesión es de la membership de quien la abre. Otro profesional de la
misma sede que intenta escribir en ella recibe **409 `sesion-ajena`** (regla de 06.01, sin cambios).
El "cambio de profesional" del plan es el reemplazo autorizado que 06.01 declaró inexistente y
que es **U-24**: hasta que exista, fail-closed. Que el profesional no sea el instructor de la clase
es legítimo: una clase puede tener instructor y además un kinesiólogo que atiende.

## 5. El cerrojo que 08.04 dejó anunciado

"Revertir una derivación de la que cuelga una Sesión" queda **imposible**: 409
`derivacion-con-sesion`. `clinical` no puede leer `sesion` (`clinical → encounter` cerraría el
ciclo), así que la pregunta es una sonda: `clinical.spi.AtencionesDeLaDerivacionProbe`, implementada
por `encounter.infrastructure.EncounterAtencionesDeLaDerivacionProbe`. Mismo patrón que
`RealizadoEnElCasoProbe`. Se evalúa después del acceso (un 409 confirma que la derivación existe) y
sólo sobre una vigente.

Consecuencia que hay que saber: **la Sesión no se borra** (regla maestra 10) y no existe anulación
de Sesión en M14, así que una atención abierta por error deja la derivación fija. Se corrige
cerrándola con lo que pasó —`AUSENTE` si no hubo atención— o enmendando la nota.

## 6. API — contrato `0.115.0`, aditivo

Tres operaciones nuevas, todas colgando de la clase de la sede:

| Método | Ruta | `operationId` |
|---|---|---|
| `POST` | `/api/v1/consultorios/{c}/clases/{claseId}/inscripciones/{inscripcionId}/atencion-clinica` | `iniciarAtencionDeParticipante` |
| `POST` | `/api/v1/consultorios/{c}/clases/{claseId}/atencion-clinica/lote` | `iniciarAtencionGrupal` |
| `GET` | `/api/v1/consultorios/{c}/clases/{claseId}/atencion-clinica` | `verAtencionGrupal` |

- La individual responde **201** con `Location` a la Sesión, o **200** con `yaExistia = true`.
  Cuerpo opcional `{ derivacionId }`.
- El lote responde **200 aunque haya fallos parciales**, cada item en su transacción y con el mismo
  `problemType` que la operación individual (patrón de 08.03). Lo que vale para todo el lote —sede,
  clase, permiso, oferta clínica— se valida una vez y sale como error del pedido. Tope 200.
- El panel lista las asistencias con sus atenciones (ids, estado, números). **Sin contenido
  clínico** y **sin leer derivaciones** (eso es una lectura clínica auditada por persona, y el panel
  la haría en loop). Exige `sesion:register` y se audita una vez (`SESION_GRUPAL_CONSULTADA`). En
  una clase no clínica responde `clinica = false` sin consultar nada clínico.
- **Todo lo demás es la Sesión**, por sus rutas de siempre: borrador, evaluación, tratamientos,
  mediciones, cierre, enmienda, versiones. Ninguna ruta paralela.

Aditivo en lo existente:

- `Sesion` (schema) gana `derivacionId` y `claseId`, nullables.
- `cambiarCasoDeSesion` puede responder 409 `caso-de-atencion-grupal`.
- `revertirDerivacionDeParticipante` puede responder 409 `derivacion-con-sesion`.
- Problem types nuevos: `participacion-sin-derivacion`, `derivacion-ambigua`,
  `derivacion-con-sesion`, `caso-de-atencion-grupal`.

**El lote abre y no cierra.** Cerrar en lote obligaría a mandar en un cuerpo la evolución de
varios pacientes —la "evolución clínica global" que RF-M14-013 prohíbe— e invita a la misma nota
para todos. Cada cierre lleva lo de su paciente y va por la Sesión.

## 7. Economía: convenio sí, particular no (U-42, RF-M21-009)

El cierre de una Sesión dispara `billing.ObligacionDevengador` (F-4): con cobertura aplicable
devenga la parte del **financiador** y el **coseguro** desde el arancel congelado; sin cobertura,
el **particular** al precio de la oferta (DP-17), y si no hay precio **bloquea el cierre** con 409
`oferta-sin-precio`.

- **Con cobertura, igual que siempre.** RF-M21-009 pide que las atenciones de Ofertas cubiertas
  entren al circuito de presentación, y CA-M21-009-06 un renglón por paciente. Con una Sesión por
  participante eso sale solo: cada una devenga su obligación del financiador y aparece en la bandeja
  de prestaciones elegibles de 07.04. **08.09 lo dejó afuera esperando este modelo**; queda cubierto
  sin código en `billing` más allá de no romperlo.
- **Sin cobertura, no se devenga particular.** La clase ya se cobra por su esquema —pase, abono o
  clase, sobre la **asistencia** (08.06–08.08)— y devengar también en cada Sesión **cobraría dos
  veces la misma clase**. Y bloquear el cierre clínico por el precio de una deuda que no corresponde
  es DP-06 al revés: `exigePrecioParticular` responde `false` para una Sesión grupal.

`SesionCerrada` lleva `claseId` para que `billing` lo sepa. El consumo de autorizaciones **sigue
igual** (por Caso: 08.04 §6 lo dejó a esta etapa). Si el particular sin pase debería cobrarse, y si
el coseguro se suma a lo que el paciente paga por la clase, es **U-42**.

## 8. Lo que esta etapa NO hace

1. **No crea `PrestacionRealizada`** (RF-M14-015): RN-M14-011 la declara futura y no hay consumidor.
2. **No cierra en lote** (§6).
3. **No cierra Sesiones al cerrar la clase**: "clase cerrada no sustituye cierre clínico individual".
   El cierre de la clase (08.03) no toca `encounter`.
4. **No reacciona a una corrección posterior de la asistencia**: si alguien pasa a `AUSENTE` a un
   participante que ya tiene Sesión, la Sesión queda; se cierra con `AUSENTE` si no hubo atención.
5. **No reemplaza al profesional** (U-24).
6. **No tiene frontend**: el panel, la ficha por participante y el progreso son de `appKine-web`.

## 9. Tests

- `AtencionDeParticipantesServiceTest` (activity): clase no clínica no llega a `encounter`, ausente,
  el pedido que se arma, lote con éxito parcial y error no previsto que no filtra su mensaje, panel.
- `AtencionGrupalServiceTest` (encounter): la Sesión nace con la historia, el Caso y la oferta de la
  derivación, sin turno y del profesional; idempotencia; el acceso clínico antes que la
  idempotencia; Caso cerrado; Caso fijo; panel auditado.
- `DerivacionClinicaServiceTest` (clinical): `paraAtender` elige, rechaza y audita; la reversión con
  Sesión es 409.
- `ObligacionDevengadorTest`: el cierre grupal sin cobertura no devenga ni exige precio; con
  cobertura devenga financiador y coseguro.
- **`AtencionGrupalIT`** (MySQL, corre en el CI): V102 y sus CHECK, dos pacientes con Sesiones y
  números independientes, cierre de uno sin tocar al otro, cero obligaciones particulares, lote con un fallo,
  reversión con Sesión, `cambiarCaso`, clase no clínica, otro tenant, y **N inicios simultáneos con
  una sola Sesión**.

## 10. Design challenge

**1. Ownership.** No hay tabla nueva. Las tres columnas son de `sesion`, de `encounter`, y sólo
`encounter` la escribe y la lee. `activity` ve las atenciones por `encounter.spi.AtencionGrupal`;
`clinical` pregunta por `AtencionesDeLaDerivacionProbe`, que implementa `encounter`.
`derivacion_clinica` sigue siendo sólo de `clinical`: la FK desde `sesion` no le da a `encounter`
acceso a la fila, le da al motor la garantía de que existe.

**2. Ciclos.** La única arista nueva es `activity → encounter.spi`. **Medido, no razonado:**
`ModuleArchitectureTest` pasa con el código real (5/5), y el **control negativo** —una interfaz
sonda `activity.spi.F905Sonda` y una clase `encounter.infrastructure.F905SondaUso` que la usa,
`encounter → activity`— hace fallar `sin_ciclos_entre_modulos` con **dos ciclos detectados**
(el directo y uno largo). La sonda se borró, junto con sus `.class` de `target/`. Nadie depende de
`activity` hoy, y por eso la arista no puede cerrar nada.

**3. Tenant.** `sesion` ya lleva `organization_id` y `consultorio_id`. `uk_sesion_derivacion` y
`ix_sesion_clase` lo incluyen primero. Toda lectura nueva filtra por organización (y por sede en el
panel). Un actor de otro tenant recibe 404 en la sede antes de tocar nada (`AtencionGrupalIT`).

**4. Reglas maestras.** HC ≠ Caso ≠ Sesión: la Sesión cuelga de la HC, se numera en el Caso, y el
Caso lo eligió la derivación. Turno ≠ Sesión ≠ Clase: `turno_id` es `NULL` y el CHECK lo exige; la
clase es un id de origen, no un estado. Ninguna transición administrativa prueba la prestación:
abrir no prueba nada, cerrar sí. Obligación ≠ Cobro ≠ Caja: el cierre grupal devenga sólo por convenio (§7) y
no se toca ni cobro ni caja. La HC es de la organización y todo acceso se justifica y se audita:
`paraAtender` exige `hc:write` con relación o justificación y audita; la Sesión audita su inicio y
el panel audita su lectura. El profesional dueño: 409 `sesion-ajena`, no 403.

**5. Baja lógica.** No hay ningún borrado. La reversión de la derivación ya era lógica; ahora además
se niega si hay Sesión, justamente para no dejar una atención huérfana. La Sesión no se borra.

**6. Contrato.** Aditivo: tres operaciones nuevas, dos campos nullables en `Sesion`, cuatro problem
types nuevos y dos 409 nuevos en operaciones existentes que antes no podían ocurrir (no había
Sesiones grupales). Minor: `0.115.0`. Sin `operationId` repetido.

**7. Ruta crítica.** Los cimientos están: Sesión completa (06.01–06.06), Caso y numeración (04.03),
derivación con su API (08.04, `DerivacionService` en `main`), asistencia (08.03). Lo que **no**
está es la integración económica de servicios (08.09) —por eso §7 no devenga particular y lo eleva— y
la DP formal que abre F9 sin el gate del MVP (F9 ficha, "fase congelada"), que no es de esta etapa.

**8. El caso que rompe el diseño.** *Una participante derivada a dos Casos —rodilla y hombro— en
la misma clase, el profesional aprieta "atender" dos veces mientras otro revierte una de las
derivaciones.* Cómo se resuelve:

- Sin `derivacionId`: 409 `derivacion-ambigua` con los dos ids; **nunca se elige por el cliente**,
  porque numerar la Sesión en el Caso equivocado no se corrige sin reescribir historia clínica.
- Con `derivacionId`: los dos clics y la reversión serializan sobre la fila de la asistencia. El
  primer clic crea la Sesión; el segundo la devuelve con `yaExistia`. La reversión, si llega
  después, choca con 409 `derivacion-con-sesion`; si llega antes, el clic ve la derivación
  revertida y recibe 409 `participacion-sin-derivacion`. Nunca queda una Sesión colgada de una
  derivación revertida.
- La otra derivación (el otro Caso) admite su propia Sesión: una por participante **y Caso**.

Un segundo caso adverso, más cotidiano: *el mostrador marca ausente por error, el profesional
quiere atender.* Es 409 `participacion-sin-asistencia`; el camino es corregir la asistencia (08.03
dejó la corrección con motivo), no forzar la atención. La corrección en el sentido contrario —a
`AUSENTE` después de abrir la Sesión— queda fuera (§8.4).

## 11. Pedidos de la pantalla (web #104) — contrato `0.118.0`, aditivo

Cuatro campos, todos calculados al leer, sin migración:

| Dónde | Campo | De dónde sale |
|---|---|---|
| `ParticipanteEnAtencionGrupal` (`verAtencionGrupal`) | `nombre`, `apellido` | `person.spi.PacienteDirectory.findAll`, **una** consulta para toda la clase y **después** de los gates (si `encounter` niega el panel, no se resuelve ningún nombre). Ni documento ni contacto |
| `Sesion` | `claseTitulo` | El título de la clase, o el nombre comercial de su oferta si no tiene. `encounter.spi.TituloDeClaseGrupal`, implementada por `activity.infrastructure.TituloDeClaseParaLaSesion`: la arista sigue siendo sólo `activity → encounter.spi`. Ausente si la Sesión no es grupal |
| `DerivacionDeParticipante` | `numeroCaso` | `DerivacionSnapshot.numeroCaso`, que `clinical` resuelve con una consulta por historia (no por derivación) en `consultar`. Es el mismo dato que `CasoSnapshot` ya publicaba: no es contenido clínico |
| `ItemDeAtencionGrupal` (`iniciarAtencionGrupal`) | `derivacionIds` | Sólo con `derivacion-ambigua`. `ConTipoDeProblema.propiedadesDelProblema()` lleva al ítem lo mismo que el `ProblemHandler` pone en el Problem Detail individual |
