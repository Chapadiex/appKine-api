# AKINE-08.04 — Derivación de participante al circuito clínico (M28, M09, M10, M11)

Diseño de la etapa. **La revisión adversarial está en `AKINE-08.04-challenge.md` y manda sobre
este documento.**

Trazabilidad: RF-M28-008; RF-M09-007, RF-M09-008; RF-M10-007, RF-M10-008; RF-M11-007, RF-M11-008.
Sale de AKINE-08.03 (`asistencia_actividad`, el ciclo operativo de la clase), de AKINE-04.01
(Historia Clínica organizacional), de AKINE-04.03 (Caso Clínico) y de AKINE-04.04 (Plan de
Tratamiento).

---

## 1. La frontera que decide toda la etapa

**Derivar no es atender.** Esta etapa cruza la frontera entre el mundo grupal y el clínico, y lo
único que hace es **cruzarla**: deja escrito que esta participación pertenece a este contexto
clínico. **No documenta nada.**

| Hecho | Qué afirma | Dónde vive | Etapa |
|---|---|---|---|
| **Inscripción** | esta persona tiene reservado un lugar | `inscripcion_clase` | 08.02 |
| **Asistencia** | esta persona **estuvo** en esta clase | `asistencia_actividad` | 08.03 |
| **Derivación** | esta participación **pertenece** a este Caso Clínico | `derivacion_clinica` | **esta etapa** |
| **Sesión** | se le **prestó** una atención clínica documentada | `sesion` | 08.05 |

La línea es literal y hay que poder decirla en una frase: **08.04 abre la puerta, 08.05 entra.**
Derivar no prueba que hubo prestación —ninguna transición administrativa lo prueba, DP-05— y por
eso la derivación **no crea Sesión, no numera nada dentro del Caso y no consume una sola unidad de
autorización.** Las tres cosas son de 08.05.

### 1.1 Por qué la derivación es una fila y no un estado

La salida barata sería una columna `caso_clinico_id` en `asistencia_actividad`. No sirve, y por
cuatro motivos:

1. **Pondría un identificador clínico en una tabla que lee un instructor no clínico.** El detalle
   operativo de 08.03 —RF-M28-009— es la pantalla del mostrador: una columna clínica ahí viaja en
   cada respuesta y la pregunta deja de ser "quién puede verla" para ser "quién se acordó de
   filtrarla". RF-M28-008 paso 7 pide mantener privacidad individual y CA-M09-007-06 pide que una
   asistencia a Yoga quede afuera del timeline; una columna no se puede auditar al leerse.
2. **Una derivación tiene actor, instante, motivo y reversión con motivo.** Una columna no tiene
   ninguno de los cuatro. Es el mismo argumento con el que 08.03 se negó a que la asistencia fuera
   un estado de la inscripción.
3. **Una participación puede derivarse a más de un Caso** —la regla del plan es "una sola vez **por
   destino**", §5.3—. Una columna admite uno.
4. **`activity` no es dueño de nada clínico.** Ver §2.

## 2. Ownership: la tabla es de `clinical`

`derivacion_clinica` es **propiedad del módulo `clinical`**, y no de `activity`. Es la decisión
central del diseño y la pregunta 1 del challenge la desarma entera.

El resumen: el vínculo **es un hecho clínico** —de dónde vino este paciente y por qué está en este
Caso— y todo lo que se escribe o se lee de él tiene que pasar por la política de acceso clínico de
DP-03: permiso, relación asistencial o justificación declarada, y **auditoría de la lectura, no
sólo de la mutación**. Esa política vive en `clinical.application` y es privada del módulo. Si la
fila viviera en `activity`, habría que exportar la política —o, mucho más probable, reescribirla
peor—, y **DP-03 tendría dos implementaciones.**

`activity` no gana ni pierde una tabla. Lo que gana es **una arista**: `activity → clinical.spi`.

## 3. La orquestación va desde `activity`, y no puede ir al revés

`clinical` no puede leer una participación: `clinical → activity` cerraría el ciclo con la arista
nueva, y ArchUnit lo rechaza —comprobado, challenge pregunta 2—. Así que el comando entra por
`activity`, que es quien sabe qué es una clase:

```
activity.api.DerivacionController
  └─ activity.application.DerivacionService          valida lo GRUPAL
       ├─ clase / inscripción / asistencia en scope
       ├─ la oferta es clínica            (offering.spi)
       ├─ la persona es paciente          (person.spi)
       ├─ activar perfil si se pidió      (person.spi → PerfilPacienteService)
       └─ clinical.spi.DerivacionClinicaRegistry     valida lo CLÍNICO y escribe
            └─ clinical.application.DerivacionClinicaService
                 ├─ AutorizacionClinica  (permiso + relación/justificación)
                 ├─ HC asegurada, Caso activo, Plan del Caso, autorización vigente
                 ├─ INSERT derivacion_clinica
                 └─ AuditTrail, en la MISMA transacción
```

Es exactamente el patrón de 01.02 —`identity` orquesta hacia `organization` por el `spi`, nunca al
revés— y el de 04.05 —`encounter` llama a `person.spi` para consumir—.

### 3.1 Este puerto SÍ autoriza, y es el primero del repositorio que lo hace

`PacienteDirectory`, `CasoDirectory`, `HistoriaClinicaDirectory` y `OfertaDirectory` dicen los
cuatro, en su javadoc, que **no autorizan nada**. `DerivacionClinicaRegistry` se aparta a
propósito, y el motivo es el mismo que hace que la tabla sea de `clinical`: el control de acceso
clínico no es "un permiso", son **cuatro cosas acopladas** —permiso, relación asistencial,
justificación declarada y auditoría con la vía por la que se entró— y las cuatro son privadas de
`clinical.application`.

La alternativa era que `activity` evaluara `hc:write`, resolviera la relación asistencial y
escribiera el evento de auditoría clínico. Eso es DP-03 implementada dos veces, y la segunda
implementación envejece sola.

**El reparto queda así, y es la regla que 08.05 hereda:** `activity` autoriza lo suyo —ver la
clase, ver el participante— y `clinical` autoriza lo clínico. Ninguna decisión se toma dos veces.

## 4. Persona → Paciente: se expone la clase que ya existe, no se escribe otra

Es el riesgo más caro de la etapa. RF-M07-010 y AKINE-03.01 dejaron fijado que **una sola clase
del sistema inserta en `perfil_paciente`**: `person.application.PerfilPacienteService`. Y
`PacienteDirectory` lo dice con todas las letras: *"Un modulo clinico que pudiera convertir a
alguien en paciente por el camino de crear su historia seria RF-M07-010 violada desde afuera"*.

Un participante de una clase es una **Persona**. Derivarlo es, casi siempre, lo que lo convierte en
Paciente. El diseño lo resuelve así, y las tres partes importan:

1. **Nunca implícito.** El comando lleva `activarPerfilPaciente` (boolean, default `false`). Sin la
   bandera, una persona sin perfil vigente es **409 `persona-sin-perfil-paciente`**, con el
   `personaId` en el problema para que la pantalla pueda ofrecer el paso. Derivar no puede convertir
   a nadie en paciente de costado: el operador lo dice.
2. **Nunca una segunda clase que escriba.** La bandera activa un puerto nuevo,
   `person.spi.PerfilPacienteProvisioning`, cuyo **único implementador delega en
   `PerfilPacienteService.activar`** —el mismo método que usa la pantalla del padrón, con su misma
   idempotencia de dos capas, su mismo `paciente:manage` y su misma auditoría—. El adaptador no
   tiene un `INSERT`: tiene una llamada.
3. **Nunca sin el permiso del padrón.** `PerfilPacienteService` exige `paciente:manage` sobre la
   sede del contexto, y eso no se saltea por venir de otro módulo. Un profesional clínico que no
   gestione el padrón recibe 403 **en ese paso**, no en el anterior: el mensaje distingue "no podés
   derivar" de "no podés dar de alta pacientes".

La Historia Clínica no necesita nada equivalente: `HistoriaClinicaDirectory.asegurar` ya existe
desde 04.01, es idempotente por unique, y **exige perfil vigente** — o sea que si el paso 1 se
saltara, el paso siguiente frenaría igual. Son dos cerrojos y los dos quedan.

## 5. La tabla

`derivacion_clinica` (`V63`). Dueño: `clinical`.

| Columna | Por qué |
|---|---|
| `organization_id`, `consultorio_id` | AGENT.md §5. La sede es la de la clase, congelada: la HC es de la organización pero la derivación ocurrió en una sede concreta y la auditoría la necesita |
| `origen` | `CLASE_PROGRAMADA` hoy. Existe para que 08.05 y lo que venga después no tengan que adivinar de qué tabla es `participacion_id` |
| `participacion_id` | `asistencia_actividad.id`. Ver §5.1 |
| `persona_id` | Redundante con la HC y **no sobra**: sin él, una derivación podría quedar atada a la historia de otro y el error sería invisible. Mismo criterio que `AutorizacionDirectory` con `personaId` |
| `historia_clinica_id`, `caso_clinico_id` | El destino. FK a tablas propias |
| `plan_tratamiento_id` | Nullable. RF-M11-007. Un Caso sin plan activo se deriva igual |
| `oferta_id` | La oferta clínica que **justificó** la derivación, congelada. Si mañana le apagan `genera_registro_clinico`, la derivación pasada sigue explicándose |
| `autorizacion_id` | Nullable. RF-M11-008. Ver §6 |
| `estado` | `VIGENTE` / `REVERTIDA`. Ver §5.3 |
| `derivada_en`, `derivada_por_cuenta_id`, `motivo` | Quién, cuándo, por qué |
| `revertida_en`, `revertida_por_cuenta_id`, `motivo_reversion` | Motivo **obligatorio** al revertir |
| `revertida_key` | Columna generada `IFNULL(revertida_en, '1970-01-01')`. Existe sólo para el unique: en MySQL varios `NULL` no colisionan |
| `version` | Bloqueo optimista |

### 5.1 El ancla es la asistencia, no la inscripción

08.03 lo dejó escrito: *"Esta etapa le deja la fila de `asistencia_actividad` con su unique, que es
el ancla de la que va a colgar el vínculo."*

Y es la elección correcta por una razón de fondo: **no se deriva a quien no vino.** Una inscripción
es una reserva; derivar una reserva sería abrir un contexto clínico para alguien que quizás nunca
apareció. Por eso la precondición es que exista asistencia y que su `resultado.estuvo()` sea
`true` —`PRESENTE` o `PRESENTE_TARDE`—. Un `AUSENTE` es **409 `participacion-sin-asistencia`**.

Y tiene una consecuencia práctica que conviene decir: **si el mostrador marcó ausente por error, el
camino es corregir la asistencia** —08.03 dejó la corrección con motivo y evento—, no forzar la
derivación.

### 5.2 Sin FK hacia `asistencia_actividad`, y a propósito

`asistencia_actividad` es de `activity`. Una FK desde `derivacion_clinica` sería
`clinical → activity` **en el esquema**, justo al revés de la dependencia de código que esta etapa
introduce. No es lo mismo que la FK `asistencia_actividad → persona` de 08.03: esa acompaña a
`activity → person.spi`, va en la misma dirección. Ésta iría en contra, y dejaría al motor
impidiendo que `activity` borre filas que `clinical` referencia — que es exactamente la clase de
acoplamiento invisible que el monolito modular existe para evitar.

Lo que se paga: `participacion_id` puede quedar colgado si alguien borra físicamente una
asistencia. **Nadie puede**: `asistencia_actividad` no tiene borrado físico —regla maestra 10— y su
puerto no expone `delete`. El riesgo es teórico y el acoplamiento no lo era.

### 5.3 Reversión, no baja

**Una derivación se revierte y no se borra.** `estado = REVERTIDA`, motivo obligatorio, actor e
instante, y la fila queda: RF-M28-008 postcondición *"los históricos previos permanecen
consultables"*.

Uniques:

- `uk_derivacion_destino (organization_id, origen, participacion_id, caso_clinico_id, revertida_key)`
  — el plan pide literalmente *"una participación se deriva una sola vez por destino"*. Dos
  derivaciones **vigentes** de la misma participación al mismo Caso son imposibles; a **Casos
  distintos** son posibles y es deliberado: un paciente en rehabilitación de rodilla y de hombro
  tiene dos Casos abiertos y una misma clase puede tocar los dos. `revertida_key` deja volver a
  derivar después de una reversión sin estorbar al histórico.
- `ix_derivacion_participacion (organization_id, origen, participacion_id)` — la consulta de
  estado.
- `ix_derivacion_caso (organization_id, caso_clinico_id, id)` — lo que 08.05 va a recorrer.

**Lo que la reversión va a tener que impedir, y hoy no puede:** revertir una derivación de la que
cuelga una Sesión. Hoy no cuelga ninguna, porque las Sesiones de origen grupal son 08.05. Se deja
escrito acá, en el javadoc de `revertir` y en §8 del challenge: **08.05 tiene que cerrar ese
cerrojo cuando cree la tabla que lo hace posible.** Construir hoy una sonda contra una tabla que no
existe sería anticipar la decisión de otra etapa en el esquema, que es lo que 08.03 se negó a hacer
con `derivable`.

## 6. Autorización: se mira, no se consume

El objetivo del plan dice *"únicamente cuando la Oferta sea clínica y exista autorización válida"*,
y hay que leerlo con cuidado porque la lectura literal rompe el alcance vigente: el **Circuito
Particular** es el alcance cortado del proyecto y un paciente particular no tiene ninguna
autorización. `ResultadoDeConsumo.SIN_AUTORIZACION_ELEGIBLE` lo dice en su javadoc: *"es el caso
más frecuente, no una anomalía"*.

La regla que se implementa: **`autorizacionId` es opcional; si viene, tiene que habilitar.** Se
valida con `person.spi.AutorizacionDirectory.find(org, persona, autorizacionId, fecha)` —puerto que
ya existe desde 04.04, cero aristas nuevas— contra el **día local de la sede**, no contra el
instante UTC. Si no habilita: **409 `autorizacion-no-elegible`** con el `motivoNoElegible` que el
snapshot ya calcula (`VENCIDA`, `AGOTADA`, `AUN_NO_VIGENTE`, `NO_APROBADA`). El caso borde
"autorización agotada" del plan cae acá.

**Y no se consume ni una unidad.** Consumir es de `person`, lo dispara el cierre de una Sesión
(`ConsumoDeAutorizaciones.consumirPorSesion`) y esa Sesión es de 08.05. Una derivación que
descontara saldo estaría cobrando una prestación que todavía no ocurrió — el error estructural 3
del plan, en miniatura.

> **Se declara el apartamiento:** la etapa **no** exige autorización para derivar. El enunciado
> literal la exige; el alcance vigente del producto la haría imposible de cumplir. Queda escrito
> acá, en la pregunta 4 del challenge, y elevado como decisión del usuario en el reporte.

## 7. La oferta clínica: el gate duro

RF-M09-007 lo pone en una línea: *"`generaRegistroClinico=false` impide crear evolución clínica"*.

`oferta_servicio_consultorio` ya tiene las dos columnas desde `V24` —`genera_registro_clinico` y
`requiere_caso_clinico`— y `V24` es explícita en que **la Oferta manda** sobre el default del
Servicio. Lo que faltaba es que viajaran: `OfertaSnapshot` no las llevaba.

Esta etapa **extiende el record** con `requiereCasoClinico` y `generaRegistroClinico`. Es el mismo
criterio con el que ese record se negó a llevar el precio: no se agrega "por si acaso", se agrega
cuando hay un consumidor que lo necesita para decidir. Acá lo hay y la decisión es la de la etapa.

Reglas:

- `generaRegistroClinico = false` → **409 `oferta-no-clinica`**. Clase de Yoga, Pilates preventivo,
  gimnasia. **No se toca la HC, no se abre historia, no se escribe una fila.** CA-M09-007-06.
- `requiereCasoClinico` **no** decide si se puede derivar: decide si 08.05 **va a exigir** Caso al
  atender. Derivar exige Caso siempre, porque derivar *es* elegir el Caso. La columna viaja
  congelada en la fila para que 08.05 la lea sin volver a la oferta.
- La oferta se resuelve por `offering.spi.OfertaDirectory.find(org, sede, ofertaId)` con el
  `ofertaId` de la **clase**, no uno del cliente.

## 8. API — cuatro operaciones, contrato `0.45.0`

Todas cuelgan del participante, que es el recurso del que habla la pantalla:

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/v1/clases/{claseId}/participantes/{inscripcionId}/elegibilidad-clinica` | El veredicto, sin efectos. RF-M28-008 "explicación de bloqueos" |
| `POST` | `/api/v1/clases/{claseId}/participantes/{inscripcionId}/derivaciones` | El comando |
| `GET` | `/api/v1/clases/{claseId}/participantes/{inscripcionId}/derivaciones` | Estado: las derivaciones de esa participación |
| `POST` | `.../derivaciones/{derivacionId}/reversion` | Revertir con motivo |

Ninguna responde 204, así que la trampa del `Accept: application/problem+json` que rompió
activación de cuenta no aplica; igual el `produces` de clase declara los dos tipos.

Las cuatro leen **`X-Justificacion-Acceso`** (`required = false`): la cabecera de 04.01, con el
mismo nombre y la misma semántica —quien decide si hacía falta es la política clínica, y si hacía
falta y no vino el rechazo es 403 con `requiereJustificacion`, no 400—. Una cabecera y no un campo
del cuerpo, porque dos de las cuatro son `GET` y dos mecanismos para el mismo dato es la forma más
barata de que una operación futura se olvide del que le tocaba.

El YAML **no se edita a mano** y queda en drift hasta que alguien regenere con Docker.

## 9. Lo que esta etapa NO hace

Dicho por su nombre, porque cada uno es una tentación real:

1. **No crea Sesión.** Ni una. Es 08.05.
2. **No abre el Caso Clínico.** El `casoId` viene en el comando y tiene que existir. Abrir un Caso
   pide diagnóstico presuntivo, objetivo terapéutico y equipo —contenido clínico— y meterlo en el
   cuerpo de un comando de `activity` sería **copiar PHI al módulo grupal**, que es exactamente lo
   que la sección de base de datos del plan prohíbe. La pantalla abre el Caso con el endpoint de
   M10 que existe desde 04.03 y después deriva. Dos pasos, una sola fuente de verdad.
3. **No crea ni modifica el Plan de Tratamiento.** Mismo argumento. Se referencia uno existente.
4. **No consume autorización.** §6.
5. **No devenga.** Deuda, cobro y caja son tres cosas y ninguna es ésta. El devengo de una clase
   sigue siendo la decisión del usuario que 08.03 elevó y que 08.06 tiene que resolver.
6. **No toca el timeline clínico.** `EventoClinicoContributor` es el mecanismo por el que el
   timeline de 04.02 junta eventos, y una derivación **no es un evento clínico**: es metadata de
   procedencia. Publicarla en el timeline pondría "vino a Pilates" al lado de una evolución. Lo que
   sí va al timeline es la **Sesión** que 08.05 cree.

## 10. Tests

El usuario pidió explícitamente **menos tests**. Un archivo,
`activity/application/DerivacionServiceTest.java`, con los casos que sostienen la etapa:

1. Oferta no clínica → `OfertaNoClinicaException`, y **no se llama al registro clínico**.
2. Participante ausente → `ParticipacionSinAsistenciaException`.
3. Persona sin perfil y sin la bandera → `PersonaSinPerfilPacienteException`, y **no se activa
   ningún perfil**.
4. Persona sin perfil **con** la bandera → se activa por el puerto y se deriva.
5. Happy path → se registra con la oferta y la persona correctas.

Lo que **no** se simula y va a `docs/tests-diferidos.md` desde el escenario **65**: que `V63`
ejecute, que el unique de destino sostenga la idempotencia bajo concurrencia, y que la delegación
de `PerfilPacienteProvisioning` escriba de verdad en `perfil_paciente`.
