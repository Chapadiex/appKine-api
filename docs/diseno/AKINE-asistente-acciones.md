# AKINE — Acciones del asistente (etapa 1)

> Diseño aprobado en conversación el 19/11/2026. Rama `akine-asistente-acciones` (api) y
> `akine-web-asistente-acciones` (web). Continúa `AKINE-asistente-de-datos.md` y
> `AKINE-dictado-por-voz.md`: léanse antes.

## 1. Para qué

Hoy cargar lo que pasó en el día obliga a recorrer pantallas. El pedido del usuario es que
cualquiera del centro —secretaria, administrador, kinesiólogo, profesor— pueda **decir o escribir
lo que pasó** y que la app lo cargue:

- "vinieron todos menos María"
- "Juan Pérez pagó 15.000 en efectivo"
- "hoy vino Juan e hizo ultrasonido y ejercicios"
- "agregá a Laura Díaz, teléfono 351 555 1234" · "cambiale el mail a Juan Pérez"

El asistente (`/asistente`, que ya tiene micrófono por el dictado) deja de ser sólo consulta: ante
una frase así **propone una acción en una tarjeta**, la persona la corrige si hace falta y la
confirma. Recién ahí se graba.

La etapa 2 —mandar el audio por WhatsApp a un número de AKINE— usa este mismo motor por otra
puerta. **No se construye ahora**, pero condiciona una decisión (§4).

## 2. Objetivos, no-objetivos y lo que sería un fracaso

**Objetivos**

1. Cuatro acciones, sobre operaciones que la API ya tiene: asistencia de clase, pago, atención con
   tratamientos, alta y cambio de datos de paciente. Más una quinta chica, la llegada (§6.4).
2. Nada se graba sin que una persona confirme lo que ve en la tarjeta.
3. Cada acción corre con los permisos de quien habla, por los servicios de aplicación de siempre.
4. Lo que el modelo extrae y no está en la frase no se usa (misma idea que `ControlDelDictado`).
5. Las consultas que el asistente ya responde siguen respondiendo igual: se vuelve a medir.

**No-objetivos**

- WhatsApp, Telegram o cualquier canal fuera de la app.
- Acciones encadenadas ("cobrale y dale turno"): una frase, una acción.
- Reservar, cancelar o reprogramar turnos; coberturas; domicilio (no existe en `Persona`).
- Abrir la caja, cerrar la clase, anular o corregir un cobro.
- Reglas de negocio nuevas: si una pantalla hoy no lo permite, el asistente tampoco.

**Sería un fracaso aunque funcione**

- Que algo se grabe sin confirmación, o que la confirmación grabe algo distinto de lo que mostraba
  la tarjeta.
- Que el asistente elija en silencio entre dos "Juan", dos clases o dos deudas.
- Que una secretaria termine cargando una evolución clínica.
- Que un doble clic en "Confirmar" cobre dos veces.

## 3. Lo que hay hoy y condiciona

- `AsistenteService.consultar` no guarda estado: el cliente manda el historial. No existe nada
  "pendiente de confirmar".
- El modelo local (`qwen3:4b-instruct`, Ollama) **sólo clasifica** y llena campos; el texto sale de
  plantillas. El prompt manda hoy todo pedido de cambio a `FUERA_DE_ALCANCE`, y
  `AsistenteService.pideCambio` (regex) lo refuerza.
- **Tocar el prompt del interpretador mueve fallas de lugar** (bitácora #3, #45, #62). La
  convención es: reglas en el backend, y medir los sets de `docs/asistente/evaluacion/` ante
  cualquier cambio.
- La SPI `assistant.spi.HerramientaDelAsistente` es de sólo lectura; cada módulo la implementa en
  su `infrastructure` y `assistant` no importa a nadie.
- Las operaciones de escritura existen y están en el informe del §6. `activity` y `billing` no
  exponen `spi` de escritura, pero no hace falta: la implementación de la acción vive **dentro**
  del módulo dueño y llama a su propio servicio de aplicación.

## 4. Enfoques considerados

| | Qué es | A favor | En contra |
|---|---|---|---|
| **A. Propuesta guardada y confirmada por id** (elegido) | El servidor guarda la propuesta con vencimiento; el cliente confirma por identificador mandando lo que quedó en la tarjeta | Auditoría de qué se propuso y qué se confirmó; un doble clic no duplica; WhatsApp puede confirmar la misma propuesta con un "sí" | Una tabla y una migración |
| B. Sin estado: el cliente ejecuta | El asistente devuelve la propuesta y el frontend llama a los endpoints existentes | Cero backend de ejecución | La orquestación (iniciar clase, lote, sesión + tratamientos + cierre) queda en el navegador; no sirve para WhatsApp; sin rastro de la propuesta |
| C. Abrir la pantalla precargada | La tarjeta es un enlace a la pantalla de siempre con datos puestos | Lo más barato | Suma clics, que es justo lo que se quiere sacar; no sirve para WhatsApp |

El usuario dejó la forma de construirlo a criterio del agente; se eligió **A**.

## 5. Diseño

### 5.1 Recorrido

```
frase ──> ¿acciones prendidas y permiso?  ── no ──> recorrido de hoy, sin cambios
              │ sí
              v
        ¿es una pregunta?  ── sí ──> recorrido de hoy (interpretador de consultas)
              │ no
              v
        interpretador de ACCIONES (otro prompt, otra llamada)
              │
        NINGUNA ──> recorrido de hoy
              │ una acción
              v
        resolver persona (aclaración si hay varias, como hoy)
              v
        AccionDelAsistente.preparar(...)   <- módulo dueño, sólo lee
              v
        guardar propuesta ──> respuesta con `accion` (la tarjeta)
              ...
        confirmar(id, datos de la tarjeta) ──> AccionDelAsistente.ejecutar(...) ──> resultado
```

### 5.2 Interpretar: un prompt aparte

**El prompt de consultas no se toca.** Las acciones tienen su propio interpretador
(`InterpretadorDeAcciones`, `asistente/prompt-acciones.txt`), con **JSON schema** (el adaptador de
Ollama ya lo soporta y hoy no se usa) y temperatura 0.

Qué frase va a cuál lo decide el backend, no el modelo:

1. Con las acciones apagadas, o sin `asistente:acciones`: todo como hoy.
2. `EsPregunta` (regla): signos `¿`/`?` o arranque interrogativo plegado (`quien`, `quienes`,
   `cuanto`, `cuantos`, `cual`, `que`, `como`, `cuando`, `donde`, `hay`, `tiene`...). Una pregunta
   va al interpretador de consultas, sin pasar por el de acciones.
3. Lo demás va al interpretador de acciones. Si devuelve `NINGUNA`, sigue el recorrido de hoy
   (segunda llamada al modelo).

Costo aceptado: una consulta escrita sin forma de pregunta ("turnos del viernes") paga dos
llamadas. **A medir**; si pesa, el constructor puede sumar a la regla 2 una lista de arranques de
consulta. La lista exacta de `EsPregunta` es del constructor y sale de la medición.

Salida del interpretador de acciones (todo opcional salvo `accion`):

```json
{"accion": "ASISTENCIA_CLASE | PAGO | ATENCION | PACIENTE_ALTA | PACIENTE_EDICION | NINGUNA",
 "persona": "Juan Pérez",
 "clase": "yoga",
 "asistencia": {"modo": "TODOS_MENOS | SOLO", "nombres": ["María"]},
 "monto": "15000", "medio": "EFECTIVO",
 "practicas": ["ultrasonido", "ejercicios"],
 "datos": {"nombre": "Laura", "apellido": "Díaz", "telefono": "3515551234",
           "email": null, "documento": null, "fechaNacimiento": null}}
```

**Control de lo extraído** (`ControlDeAccion`, sin modelo): cada valor tiene que poder leerse en la
frase —nombres y prácticas por texto plegado, monto y teléfono por sus dígitos—. Lo que no
aparece se descarta y la tarjeta lo muestra vacío. El modelo **no** redacta la nota de la atención:
la nota es la frase original, editable.

Largo máximo de la frase para acciones: 600 caracteres (hoy 300 para preguntas).

`pideCambio` se mantiene para lo que no es acción (sigue evitando el SQL libre).

### 5.3 SPI nueva

En `assistant.spi`, al lado de la de lectura:

```java
public interface AccionDelAsistente {
    TipoDeAccion tipo();
    /** Sólo lee. Resuelve, valida precondiciones y arma la tarjeta. */
    PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido);
    /** Revalida todo y escribe por el servicio de aplicación del módulo. */
    ResultadoDeAccion ejecutar(ActorDelAsistente actor, DatosConfirmados datos);
}
```

- Una implementación por tipo, `@Component` en el `infrastructure` del módulo dueño, indexadas
  igual que las herramientas (dos para el mismo tipo: no arranca).
- `preparar` devuelve la tarjeta tipada (§5.5), `avisos` (se puede confirmar igual) y `bloqueos`
  (no se puede confirmar: caja cerrada, sin permiso, clase cancelada).
- `ejecutar` **no confía en la propuesta**: recibe lo que el usuario confirmó y vuelve a validar
  por el camino normal del servicio. Un `AccessDeniedException` se propaga y el asistente lo
  convierte en "sin permiso" en un solo lugar, como hoy.
- Un rechazo de negocio (409 del módulo) vuelve como resultado `RECHAZADA` con el motivo legible,
  no como excepción: la propuesta queda abierta para corregir.

### 5.4 La propuesta guardada

Tabla `asistente_accion`, del módulo `assistant` (migración **`V46`**, nacida como `V29`, a reservar según
`docs/fases/01-trabajo-en-paralelo.md` y confirmar al rebasar):

| Columna | |
|---|---|
| `id`, `organization_id`, `consultorio_id`, `account_id` | quién y dónde; índices con `organization_id` |
| `tipo` | `TipoDeAccion` |
| `estado` | `PROPUESTA`, `EN_CURSO`, `CONFIRMADA`, `DESCARTADA` |
| `referencias` (jsonb) | sólo ids y enums de lo propuesto: `personaId`, `claseId`, `turnoId`... |
| `resultado` (jsonb) | ids de lo creado al confirmar (cobro, sesión, persona) |
| `creada_en`, `vence_en`, `resuelta_en` | vence a los 15 minutos |

- **No guarda textos**: ni la frase, ni nombres, ni la nota clínica. El contenido de la tarjeta
  vive en el cliente y vuelve en la confirmación. La fila sirve para tres cosas: que sólo se
  confirme lo que se propuso (mismo `tipo`, mismo sujeto en `referencias`), que no se confirme dos
  veces y que quede rastro.
- Sólo la confirma o descarta la cuenta que la creó, en la misma sede. Otra cuenta: 404.
- Confirmar toma la fila con `UPDATE ... SET estado='EN_CURSO' WHERE id=? AND estado='PROPUESTA'
  AND vence_en > now()`. Cero filas: si ya está `CONFIRMADA` se devuelve su resultado
  (idempotente); si venció o se descartó, 409 `accion-no-vigente`.
- Éxito → `CONFIRMADA` con `resultado`. Rechazo de negocio o sin permiso → vuelve a `PROPUESTA`.
  Una caída en el medio deja `EN_CURSO` hasta vencer; por eso cada `ejecutar` es además idempotente
  por su cuenta donde la operación lo permite (§6).
- Sin borrado físico. Vencida es un estado derivado de `vence_en`, no un job.

### 5.5 API (aditiva; contrato **0.192.0**)

- `POST /consultorios/{cid}/asistente/consultas` — igual. `RespuestaAsistenteResponse` gana
  `accion` (nullable) y `resultado` gana el valor `PROPUESTA`.
- `POST /consultorios/{cid}/asistente/acciones/{id}/confirmacion` — cuerpo: los datos de la
  tarjeta tal como quedaron. Responde `ResultadoDeAccionResponse {estado: CONFIRMADA | RECHAZADA,
  texto, enlace, detalles[]}`.
- `POST /consultorios/{cid}/asistente/acciones/{id}/descarte` — 204.
- Problem types nuevos: `accion-no-vigente` (409), `acciones-deshabilitadas` (409).

`accion` es un solo schema con el bloque del tipo que corresponda, para que el cliente generado
no necesite uniones:

```
AccionPropuesta { id, tipo, titulo, venceEn, avisos[], bloqueos[],
                  asistencia?, pago?, atencion?, llegada?, paciente? }
```

El detalle de cada bloque está en §6. Los mismos bloques, sin los campos de sólo lectura, son el
cuerpo de la confirmación.

### 5.6 Interruptores, permiso y auditoría

- `akine.asistente.acciones.enabled`, **apagado por defecto**. Exige `akine.asistente.enabled`.
- Por centro: el interruptor `ASISTENTE` que ya existe (`organization_ia`). No se agrega columna.
- Permiso nuevo `asistente:acciones`, por base a ORG_ADMIN, CONSULTORIO_ADMIN, PROFESIONAL y
  ADMINISTRATIVO. Es sólo la puerta: lo que cada acción puede lo decide el permiso del servicio
  que la ejecuta.
- El frontend necesita saber si las acciones están prendidas para cambiar el aviso "Sólo consulta:
  no modifica nada". Se publica en el estado de funciones de IA que ya consulta (el constructor
  elige el campo).
- Auditoría: `ASISTENTE_ACCION_PROPUESTA`, `_CONFIRMADA`, `_RECHAZADA`, `_DESCARTADA`, con tipo e
  ids, sin la frase. Además, la auditoría normal de cada servicio (cobro, sesión, persona).

## 6. Las acciones

Regla común: **si hay más de un candidato, la tarjeta lo muestra para elegir**; nunca se elige
solo. La persona se resuelve con la aclaración que ya existe (`ACLARACION` + `personaId`).

### 6.1 Asistencia de clase — `activity`

"vinieron todos menos María" · "a yoga vinieron Ana y Juan"

- **Clase:** las de **hoy** en la sede (día de la sede), no canceladas. Si la frase nombra una
  actividad, se filtra por título. Una sola → esa. Varias → selector en la tarjeta, con la que
  está `EN_CURSO` primero. Ninguna → respuesta de texto, sin tarjeta.
- **Inscriptos:** los confirmados (ni cancelados ni en lista de espera).
- **Nombres:** contra los inscriptos, plegando texto, por nombre o apellido. Un nombre que
  coincide con dos inscriptos, o con ninguno, queda como aviso y esa fila sin marcar.
- **Modo:** `TODOS_MENOS` → presentes todos, ausentes los nombrados. `SOLO` → presentes los
  nombrados, **ausentes el resto** (decisión del agente: "vinieron Ana y Juan" describe la clase
  entera; la tarjeta lo muestra y se corrige ahí).
- **Tarjeta:** `{claseId, titulo, hora, opcionesDeClase[], items[{inscripcionId, nombre,
  resultado, yaRegistrado}]}`. `resultado`: PRESENTE / AUSENTE / sin marcar.
- **Ejecutar:** si la clase está `PROGRAMADA`, iniciarla (`clase:manage`; sin ese permiso es un
  bloqueo, no un error al confirmar). Después el lote de asistencia. Los que ya tenían resultado y
  cambian van con motivo "Corrección desde el asistente". El lote admite fallos parciales: el
  resultado los lista.
- No cierra la clase. Idempotente por el hecho (clase, persona), como ya es.
- Permiso: `asistencia:manage` (los cuatro roles).

### 6.2 Pago — `billing`

"Juan Pérez pagó 15.000 en efectivo"

- **Deudas:** las del paciente en estado `PENDIENTE` o `PARCIAL` cuyo responsable es el paciente
  (no las del financiador).
- **Imputación propuesta:** de la más vieja a la más nueva hasta agotar el monto; lo que sobra,
  `anticipo` (saldo a favor). Sin deudas: todo anticipo. La tarjeta muestra cada deuda con su
  saldo y lo imputado, editable.
- **Medio:** el dicho. Si no se dijo, **queda sin elegir y no se puede confirmar** (no hay medio
  por defecto con plata). Un solo medio por pago en esta etapa.
- **Monto:** si no se dijo o no pasó el control, vacío y sin poder confirmar.
- **Bloqueo:** efectivo con la caja de la sede cerrada. La tarjeta lo dice; no abre la caja.
- **Tarjeta:** `{personaId, nombre, total, medio, moneda, imputaciones[{obligacionId, concepto,
  fecha, saldo, importe}], anticipo, cajaAbierta}`.
- **Moneda:** un cobro es de una sola moneda. Se imputa sólo a deudas de la moneda de la más
  vieja; si hay deudas en otra, aviso y quedan afuera. Sin deudas (todo anticipo) la moneda es un
  campo de la tarjeta con `ARS` puesto, como en la pantalla de caja.
- **Ejecutar:** `CobroService.registrar` con `idempotencyKey = "asistente-accion-" + id`, **fuera
  de toda transacción** (lo exige el numerador de comprobantes): la confirmación commitea
  `EN_CURSO` antes de llamar. Las sumas las valida el servicio (`cobro-no-cuadra`).
- El responsable de cada deuda sale de `Obligacion.getResponsable()`.
- Permiso: `cobro:register` (el profesional no lo tiene: recibe "sin permiso").

### 6.3 Atención — `encounter`

"hoy vino Juan e hizo ultrasonido y ejercicios" — **sólo si quien habla tiene `sesion:register`**
(el profesional).

- **Turno:** el de **hoy** de esa persona con este profesional, no cancelado y sin sesión cerrada.
  Uno → se usa. Varios → selector. Ninguno → **atención sin turno**: hace falta una oferta; si el
  profesional está habilitado en una sola vigente, esa; si no, selector. Motivo: "Registrada desde
  el asistente".
- **Caso clínico:** sólo si la oferta lo exige. Un caso abierto → ese; varios → selector; ninguno
  → bloqueo con enlace a la ficha.
- **Tratamientos:** cada nombre dicho se busca primero en las prácticas de la oferta y después en
  el catálogo. Una coincidencia → esa. Varias o ninguna → fila "no reconocido" con buscador; no se
  inventa ni se elige la más parecida.
- **Nota de cierre:** la frase original, editable. Obligatoria (lo exige el cierre con PRESENTE).
- **Cerrar:** marcado por defecto (decisión del agente: "vino e hizo" describe una atención
  terminada). Desmarcado, la sesión queda abierta con sus tratamientos para completarla en la
  pantalla de atención.
- **Tarjeta:** `{personaId, nombre, turnoId?, opcionesDeTurno[], ofertaId?, opcionesDeOferta[],
  casoId?, opcionesDeCaso[], tratamientos[{texto, practicaId?, nombre?}], nota, cerrar}`.
- **Ejecutar**, en **tres pasos, cada uno en su transacción**: iniciar la sesión (con turno, o sin
  turno con `idempotencyKey` de la acción) → un tratamiento por práctica, releyendo la versión de
  la sesión entre uno y otro → cerrar con PRESENTE. **No es atómico y no puede serlo** sin tocar
  el núcleo de `encounter`: `SesionService.cerrar` se niega a correr dentro de una transacción en
  el primer cierre (`FilaLockPrevia`, la guarda de G-10), `TratamientoService.registrar` avanza la
  versión con un UPDATE que deja vieja la entidad cargada, e `iniciarSinTurno` resuelve su carrera
  en una transacción aparte. La implementación **no abre transacción**.
- **Si el cierre falla** (por ejemplo `oferta-sin-precio`), la sesión queda **abierta con sus
  tratamientos**, que es el mismo estado de "Cerrar" desmarcado: el resultado lo dice y enlaza a
  la pantalla de atención. No se deshace lo cargado.
- **Si el turno ya tiene una sesión abierta** de este profesional, los tratamientos dichos se
  **agregan** a los que tenga y la tarjeta lo avisa ("Ya hay una atención abierta con N
  tratamientos"). Si ya está cerrada, bloqueo.
- **Si el turno de hoy es de otro profesional**, bloqueo: "Tiene turno hoy con X". No se crea una
  atención sin turno al lado de un turno vivo (dejaría dos sesiones y dos deudas); el camino es el
  reemplazo de profesional que ya existe.
- La sesión es del profesional que habla. No se registra la atención de otro profesional.

### 6.4 Llegada — `scheduling`

La misma frase dicha por quien **no** tiene `sesion:register` (secretaria, administrador).

- Propone marcar la llegada del paciente en la recepción del turno de hoy. Los tratamientos dichos
  no se cargan, y la tarjeta lo avisa: "Los tratamientos los registra el profesional".
- Sin turno hoy: respuesta de texto, sin tarjeta.
- Operación: `CicloDeRecepcionService.registrarLlegada` (estado `LLEGO`, idempotente). Permiso
  `turno:manage`, que tienen administradores y secretaria y no el profesional.

### 6.5 Paciente — `person`

**Alta** — "agregá a Laura Díaz, teléfono 351 555 1234"

- Nombre y apellido obligatorios; teléfono, email, documento y fecha de nacimiento si se dijeron.
- `preparar` busca posibles duplicados (`buscarCoincidencias`) y los muestra. Confirmar con
  duplicados a la vista manda `confirmaPosibleDuplicado`. Documento ya registrado: bloqueo con
  enlace a esa persona.
- El documento es tipo + número y la frase sólo trae el número: el tipo es un campo de la tarjeta
  con el valor por defecto de la pantalla de alta.
- **Ejecutar:** crear la persona y activar el perfil de paciente, en una transacción.
- No tiene protección propia contra repetición: la da el estado `EN_CURSO` de la propuesta.

**Edición** — "cambiale el teléfono a Juan Pérez, 351 555 9999"

- Campos: teléfono, email, documento, fecha de nacimiento. La tarjeta muestra antes → después.
- **Ejecutar:** `PATCH` con la `expectedVersion` leída al preparar; una versión vieja vuelve como
  rechazo "alguien modificó la ficha, volvé a pedirlo".

Permiso de las dos: `paciente:manage`.

## 7. Frontend

- `charla-del-asistente` dibuja `accion` debajo de la burbuja con un componente por tipo
  (`tarjeta-de-asistencia`, `-pago`, `-atencion`, `-llegada`, `-paciente`) y un marco común:
  título, avisos, bloqueos, **Descartar** y **Confirmar** (deshabilitado con bloqueos o campos
  obligatorios vacíos).
- Al confirmar: la tarjeta pasa a sólo lectura con el resultado y el enlace ("Ver el cobro", "Ver
  la sesión"). Un rechazo deja la tarjeta editable con el motivo arriba.
- Vencida (15 min) o al cambiar de sede: la tarjeta se desactiva con "Volvé a pedirlo".
- El aviso fijo pasa a "Antes de grabar algo te muestro qué entendí" cuando las acciones están
  prendidas y la persona tiene el permiso.
- Norma de diseño del repo (`DESIGN.md`), modo táctil y los componentes `shared` existentes. Va
  en el panel flotante igual que en `/asistente`.

## 8. Medición

- Set nuevo `docs/asistente/evaluacion/acciones.json`, 40 frases o más: las del usuario, variantes
  con errores de dictado y sin puntuación, y frases que **no** son acción.
- Se vuelven a correr los sets existentes con las acciones prendidas: ninguna consulta medida
  puede terminar en tarjeta ni cambiar de resultado.
- Piso propuesto para dar la etapa por buena: acción correcta en 90 % del set, y **cero** casos en
  que el control deje pasar un valor que no está en la frase. Los números reales van a
  `docs/asistente/evaluacion/` con fecha.

## 9. Design challenge

1. **Ownership:** `asistente_accion` es de `assistant` y nadie más la toca.
2. **Ciclos:** los módulos dependen de `assistant.spi`, como ya hacen; `assistant` sigue sin
   importar a nadie. ArchUnit no cambia.
3. **Tenant:** la tabla lleva `organization_id` y sus índices lo incluyen.
4. **Reglas maestras:** la llegada (turno) y la atención (sesión) son dos acciones separadas por
   permiso; el pago registra un cobro y no toca la deuda más que por imputación; cerrar no cobra.
5. **Baja lógica:** no hay borrado; descartar y vencer son estados.
6. **Contrato:** aditivo. `resultado` gana un valor de enum: el cliente generado se regenera en el
   mismo PR web.
7. **Ruta crítica:** todo lo que se usa está en `main`.
8. **El caso que rompe:** doble confirmación simultánea de un pago → el `UPDATE` condicional deja
   pasar una sola; la otra recibe el resultado de la primera o, si llega antes de que termine, 409
   `accion-no-vigente`. Y si el proceso cae después de cobrar y antes de marcar `CONFIRMADA`, la
   `idempotencyKey` impide el segundo cobro.

## 10. Decisiones del agente, a confirmar

1. `SOLO` marca ausente al resto (§6.1).
2. La atención se cierra por defecto (§6.3).
3. Sin medio de pago dicho no se asume efectivo (§6.2).
4. La propuesta no guarda textos y vence a los 15 minutos (§5.4).
5. El interruptor por centro es el del asistente; no hay uno propio (§5.6).
6. `asistente:acciones` va por base a los cuatro roles de personal (§5.6).
7. La secretaria que dicta una atención obtiene la llegada, no un error (§6.4).
8. La atención no es atómica: si el cierre falla, queda abierta con lo cargado (§6.3).
9. Sobre una atención ya abierta, los tratamientos se agregan (§6.3).
10. Turno de hoy con otro profesional: bloqueo, no atención sin turno (§6.3).
11. Pago en una sola moneda; anticipo puro en `ARS` salvo que se cambie (§6.2).

## 10 bis. Dónde se toca (verificado contra el código el 19/11)

- Permiso: `organization/domain/PermissionCode.java` y `RolePermissions.java`,
  `assistant/domain/PermissionCodes.java`, `docs/seguridad/matriz-permisos-minima.md` §23 y los
  tests de lista exacta (`PermissionEvaluatorServiceTest`, `PermisosEsquemaIT`). Sin migración.
- Interruptor: `application.yml` bajo `akine.asistente` y `AsistenteProperties`.
- `AsistenciaService.iniciar` y `registrarLote` se llaman sin transacción abierta.
- Contrato: quedó en **0.192.0** al integrar sobre cobro mensual (0.191.0) el 23/11/2026.

## 11. Queda para el constructor

- La lista exacta de `EsPregunta` y el texto del prompt de acciones, guiados por la medición.
- Nombres de clases, records y campos; el campo que le avisa al frontend que hay acciones.
- Tests: los que la regla del repo pide (reglas de negocio, concurrencia de la confirmación, lo
  que reproduzca un defecto). Sin TDD.

## 12. Lo que cambió al construirlo (19/11/2026)

Lo que la ejecución y las revisiones corrigieron respecto de lo escrito arriba:

- **El schema decide más que el prompt.** Con sólo `accion` obligatoria el modelo omitía montos y
  nombres; con los doce campos obligatorios pasa de 12/35 a 33/35 frases completas (§8).
- **`ControlDeAccion` hace más que comprobar que el valor esté en la frase.** Un valor dicho puede
  estar en el papel equivocado. Reglas agregadas tras el set adverso: "nadie", "faltaron todos" o
  "los demás faltaron" dejan la asistencia sin marcar; una cantidad ("faltaron tres") no es un
  nombre; "ayer" o "mañana" no es asistencia de hoy; "lucas", "k" y "palos" multiplican; dos
  importes o "cada una" dejan el monto vacío; "debe", "no pagó" o "necesito saber" no son un cobro;
  un tratamiento negado, contraindicado, pasado o futuro no se carga; inscribir a una clase no es
  un alta; una frase con instrucciones o llaves no es una acción.
- **La fila confirmada guarda sólo `enlace` y `referencias`.** Guardaba también el texto, que nombra
  a la persona. Confirmar por segunda vez responde "Esto ya estaba confirmado".
- **`tomada_en`.** Una propuesta que quedó `EN_CURSO` por un pedido que murió se puede volver a tomar
  a los dos minutos, sólo en pago, asistencia y llegada (repetidas no graban dos veces).
- **La confirmación tiene topes**: nota de 2.000 caracteres, 30 tratamientos, 50 imputaciones, 200
  inscriptos.
- **Atención:** no se cierra con tratamientos sin reconocer; al confirmar sin turno se vuelve a
  mirar que no haya un turno vivo; si la sesión se abrió y los tratamientos fallan queda
  `CONFIRMADA` con aviso.
- **Pago:** sólo deudas de la sede activa. **Llegada:** elige primero un turno sin llegada.
  **Asistencia:** la clase tiene que ser de hoy también al confirmar.
- **Dos métodos nuevos de `spi`**: `TurnoDirectory.deLaPersonaEnVentana` y
  `OfertaDirectory.activasDeLaSede`.

Sin hacer, respecto de lo diseñado:

- **El buscador para elegir a mano un tratamiento no reconocido** (§6.3 y §7). La tarjeta lo muestra
  como "no reconocido" y obliga a quitarlo o a dejar la atención abierta.
- **Con "Cerrar" desmarcado la nota no se guarda** como borrador de la sesión.
- **Tope de propuestas por cuenta** y purga de `asistente_accion`.
- **La clave de idempotencia del cobro es adivinable** (`asistente-accion-<id>`): sospecha de la
  revisión de seguridad, sin confirmar.
- **Medición sin GPU**, y la bitácora de problemas en `docs/asistente/`.
