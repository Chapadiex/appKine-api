# Acciones del asistente — plan de implementación

> **Para quien ejecute:** tarea por tarea, con `superpowers:executing-plans` o
> `superpowers:subagent-driven-development`. Los pasos usan `- [ ]`.

**Objetivo:** que el asistente proponga, en una tarjeta confirmable, asistencia de clase, pago,
atención, llegada y alta/edición de paciente a partir de una frase dicha o escrita.

**Arquitectura:** un interpretador de acciones aparte (prompt y JSON schema propios) decide la
acción; el módulo dueño la prepara y la ejecuta detrás de una SPI nueva en `assistant.spi`;
`assistant` guarda la propuesta (sólo ids) y la confirma una sola vez.

**Stack:** Java 21, Spring Boot 4.1, PostgreSQL 18, Flyway, springdoc; Angular 22 en el frontend.

**Spec:** `docs/diseno/AKINE-asistente-acciones.md` — se lee junto con este plan.

## Restricciones globales

- Sin TDD (regla del repo, `CLAUDE.md` §4): código primero; tests sólo de reglas de negocio,
  concurrencia y control de lo extraído.
- `assistant` no importa ningún otro módulo de negocio; los módulos importan `assistant.spi`.
  ArchUnit en verde.
- Ninguna implementación de `AccionDelAsistente` abre transacción propia (lo exigen
  `CobroService`, `AsistenciaService.registrarLote` y `SesionService.cerrar`).
- La frase, los nombres y la nota **no** se guardan ni van al log ni a la auditoría.
- `akine.asistente.acciones.enabled` apagado por defecto: con él apagado, ningún test existente
  cambia de resultado.
- Migración `V46` (nació como `V29`); contrato 0.192.0, renumerados al integrar el 23/11/2026.
- Mensajes al usuario en español rioplatense, voseo, como `Plantillas`.
- Commits locales en `akine-asistente-acciones`; sin push hasta que el usuario lo pida.
- Tests en local: sólo los del módulo tocado (`./mvnw -o test -Dtest="com.akine.<modulo>.**.*Test"
  -Djacoco.skip=true`).

## Foco de revisión

1. Texto de Whisper sin signos ni tildes y con el monto en letras ("quince mil") → la tarjeta sale
   con el monto vacío y sin poder confirmar; nunca un 500 ni un monto inventado. *(Tarea 2)*
2. Dos confirmaciones simultáneas de la misma propuesta → una sola ejecuta. *(Tarea 3)*
3. Confirmación cuya tarjeta nombra otra persona o clase que la propuesta → rechazo, nada se
   graba. *(Tarea 3)*
4. Propuesta vencida o de otra cuenta → 409 / 404. *(Tarea 3)*
5. Un nombre que coincide con dos inscriptos → fila sin marcar y aviso. *(Tarea 4)*

---

### Tarea 1 — SPI, tabla, permiso e interruptor

**Archivos**

- Crear en `assistant/spi/accion/`: `TipoDeAccion`, `AccionDelAsistente`, `PedidoDeAccion`,
  `PropuestaDeAccion`, `Tarjeta` (+ los cinco records), `Opcion`, `ResultadoDeAccion`.
- Crear `db/migration/V29__asistente_accion.sql`; `assistant/domain/AccionPropuesta` (entidad),
  `assistant/domain/EstadoDeAccion`, `assistant/domain/port/AccionPropuestaRepositoryPort`,
  `assistant/infrastructure/AccionPropuestaRepository`.
- Modificar: `organization/domain/PermissionCode.java`, `RolePermissions.java`,
  `assistant/domain/PermissionCodes.java`, `docs/seguridad/matriz-permisos-minima.md` §23,
  `PermissionEvaluatorServiceTest`, `PermisosEsquemaIT` (listas exactas), `application.yml`
  (`akine.asistente.acciones.enabled: false`), `AsistenteProperties`, `AsistenteAjustes`
  (`boolean acciones()`), `docs/fases/01-trabajo-en-paralelo.md` §6 (reserva de `V46`).

**Interfaces que produce**

```java
enum TipoDeAccion { ASISTENCIA_CLASE, PAGO, ATENCION, LLEGADA, PACIENTE_ALTA, PACIENTE_EDICION }

interface AccionDelAsistente {
    TipoDeAccion tipo();
    PropuestaDeAccion preparar(ActorDelAsistente actor, PedidoDeAccion pedido);
    ResultadoDeAccion ejecutar(ActorDelAsistente actor, long accionId, Tarjeta confirmada);
}

record PedidoDeAccion(TipoDeAccion tipo, Long personaId, String clase, String modoAsistencia,
        List<String> nombres, BigDecimal monto, String medio, List<String> practicas,
        DatosDePersona datos, String frase, LocalDate hoy, ZoneId zona) {}
record DatosDePersona(String nombre, String apellido, String documento, String telefono,
        String email, LocalDate fechaNacimiento) {}

/** sinTarjeta: respuesta de texto (no hay clase hoy, no tiene turno). */
record PropuestaDeAccion(String titulo, Tarjeta tarjeta, Map<String, Long> referencias,
        List<String> avisos, List<String> bloqueos, String sinTarjeta) {}

sealed interface Tarjeta permits TarjetaDeAsistencia, TarjetaDePago, TarjetaDeAtencion,
        TarjetaDeLlegada, TarjetaDePaciente {}
record Opcion(long id, String etiqueta) {}
record TarjetaDeAsistencia(Long claseId, List<ClaseEnTarjeta> clases) {}
record ClaseEnTarjeta(long claseId, String titulo, Instant inicio, String estado, List<ItemDeAsistencia> items) {}
record ItemDeAsistencia(long inscripcionId, String nombre, String resultado, String yaRegistrado) {}
record TarjetaDePago(long personaId, String nombre, BigDecimal total, String medio, String moneda,
        List<ImputacionEnTarjeta> imputaciones, BigDecimal anticipo, boolean cajaAbierta) {}
record ImputacionEnTarjeta(long obligacionId, String concepto, LocalDate fecha, BigDecimal saldo, BigDecimal importe) {}
record TarjetaDeAtencion(long personaId, String nombre, Long turnoId, List<Opcion> turnos,
        Long ofertaId, List<Opcion> ofertas, Long casoId, List<Opcion> casos,
        List<TratamientoEnTarjeta> tratamientos, String nota, boolean cerrar, int tratamientosPrevios) {}
record TratamientoEnTarjeta(String texto, Long practicaId, String nombre) {}
record TarjetaDeLlegada(long personaId, String nombre, long turnoId, Instant hora) {}
record TarjetaDePaciente(Long personaId, Long version, DatosDePersona datos, String tipoDocumento,
        DatosDePersona anterior, List<Opcion> posiblesDuplicados, boolean confirmaPosibleDuplicado) {}

record ResultadoDeAccion(Estado estado, String texto, String enlace, List<String> detalles,
        Map<String, Long> referencias) { enum Estado { CONFIRMADA, RECHAZADA } }
```

`referencias` usa las claves `personaId`, `claseId`, `turnoId` (las que apliquen): es lo que la
confirmación compara contra la tarjeta que vuelve.

Puerto:

```java
interface AccionPropuestaRepositoryPort {
    AccionPropuesta guardar(AccionPropuesta nueva);
    Optional<AccionPropuesta> buscar(long id, long organizationId, long consultorioId, long accountId);
    /** UPDATE condicional PROPUESTA -> EN_CURSO con vence_en > ahora; filas afectadas. */
    int tomar(long id, Instant ahora);
    void soltar(long id);                                   // EN_CURSO -> PROPUESTA
    void confirmar(long id, String resultadoJson, Instant ahora);
    int descartar(long id, Instant ahora);                  // sólo desde PROPUESTA
}
```

Tabla (§5.4 del spec): `asistente_accion(id, organization_id, consultorio_id, account_id, tipo,
estado, referencias jsonb, resultado jsonb, creada_en, vence_en, resuelta_en)`, CHECK de `tipo` y
`estado` con nombre `ck_asistente_accion_*`, índice `(organization_id, account_id, estado)`.
Los UPDATE de estado son nativos y la entidad no tiene `@Version`.

- [ ] SPI y records.
- [ ] Migración, entidad, puerto y repositorio.
- [ ] Permiso `asistente:acciones` por base a los cuatro roles de personal; matriz y tests de lista.
- [ ] Interruptor en `application.yml`, `AsistenteProperties` y `AsistenteAjustes`.
- [ ] `./mvnw -o test -Dtest="com.akine.organization.**.*Test,com.akine.assistant.**.*Test,*Arch*" -Djacoco.skip=true` en verde.
- [ ] Commit.

### Tarea 2 — Interpretar la acción

**Archivos:** crear `assistant/application/accion/EsPregunta`, `InterpretadorDeAcciones`,
`AccionInterpretada`, `ControlDeAccion`; `src/main/resources/asistente/prompt-acciones.txt`;
bean en `AsistenteConfig`; tests `EsPreguntaTest`, `ControlDeAccionTest`,
`InterpretadorDeAccionesTest`.

**Interfaces que produce**

```java
final class EsPregunta { static boolean es(String frase); }

record AccionInterpretada(String accion /* ASISTENCIA_CLASE|PAGO|ATENCION|PACIENTE_ALTA|PACIENTE_EDICION|NINGUNA */,
        String persona, String clase, String modoAsistencia, List<String> nombres,
        BigDecimal monto, String medio, List<String> practicas, DatosDePersona datos) {
    static final AccionInterpretada NINGUNA; boolean ninguna();
}

class InterpretadorDeAcciones {            // MAX_LARGO = 600
    InterpretadorDeAcciones(Supplier<ModeloDeLenguaje> modelo, String prompt, Duration plazo);
    AccionInterpretada interpretar(String frase);   // JSON ilegible o acción desconocida -> NINGUNA
}

final class ControlDeAccion { static AccionInterpretada controlar(AccionInterpretada leida, String frase); }
```

- `InterpretadorDeAcciones` llama a `completarJson(mensajes, ESQUEMA, plazo)` con el JSON schema
  del §5.2 del spec. `ModeloNoDisponibleException` se traduce igual que en `Interpretador`.
- `ControlDeAccion`: nombres, clase y prácticas sobreviven si su texto plegado está en la frase
  plegada; `monto` si sus dígitos aparecen en la frase quitando puntos y espacios de miles;
  teléfono y documento por dígitos; email literal; `medio` sólo si la frase contiene una palabra
  del medio (efectivo, transferencia, débito, crédito, tarjeta); `fechaNacimiento` sólo si la
  frase trae una fecha. Lo que no pasa queda `null`.
- `EsPregunta`: contiene `¿` o `?`, o la primera palabra plegada está en la lista del §5.2.
- Prompt: mismas convenciones que `prompt-interpretador.txt`; ejemplos = las frases del §1 del
  spec más dos `NINGUNA` (un saludo, "turnos del viernes").

Tests (después del código):

- [ ] `ControlDeAccionTest`: `"juan pago quince mil en efectivo"` con monto 15000 del modelo →
  `monto == null`; `"Juan pagó 15.000"` → `15000`; práctica "magnetoterapia" ausente de la frase →
  eliminada; `medio` EFECTIVO sin la palabra en la frase → `null`.
- [ ] `EsPreguntaTest`: `"¿vino Juan?"`, `"quienes faltaron a yoga"`, `"cuanto debe Ana"` → true;
  `"vinieron todos menos María"`, `"Juan pagó 15000"` → false.
- [ ] `InterpretadorDeAccionesTest`: JSON roto → `NINGUNA`; acción fuera del catálogo → `NINGUNA`;
  frase de 601 caracteres → `NINGUNA` sin llamar al modelo.
- [ ] Commit.

### Tarea 3 — Proponer, confirmar, descartar

**Archivos:** crear `assistant/application/accion/AccionesDelAsistente`; modificar
`AsistenteService` (enrutado), `RespuestaDelAsistente` (`accion`, `Resultado.PROPUESTA`),
`AuditoriaDelAsistente` (`registrarAccion(evento, ...)`); crear `assistant/api/AccionesController`,
DTOs `AccionPropuestaResponse`, `ConfirmarAccionRequest`, `ResultadoDeAccionResponse`; modificar
`RespuestaAsistenteResponse`, `AsistenteProblemHandler` (`accion-no-vigente`,
`acciones-deshabilitadas`), excepciones en `assistant/domain/exception/`.

**Consume:** Tareas 1 y 2. **Produce:**

```java
class AccionesDelAsistente {
    /** Vacío si la frase no es una acción: el llamador sigue con el recorrido de consultas. */
    Optional<RespuestaDelAsistente> proponer(ActorDelAsistente actor, ZoneId zona, String frase, Long personaElegida);
    ResultadoDeAccion confirmar(ActorDelAsistente actor, long consultorioId, long accionId, Tarjeta tarjeta);
    void descartar(ActorDelAsistente actor, long consultorioId, long accionId);
}
```

Endpoints (operationId): `POST .../asistente/acciones/{accionId}/confirmacion`
(`confirmarAccionDelAsistente`), `POST .../asistente/acciones/{accionId}/descarte`
(`descartarAccionDelAsistente`, 204, con `application/json` en `produces`).

Reglas:

- `AsistenteService.consultar`, después de los controles de hoy y antes de `interpretador`:
  si `ajustes.acciones()`, hay `asistente:acciones` (evaluado, no exigido) y `!EsPregunta.es(frase)`
  → `acciones.proponer(...)`; si devuelve respuesta, se audita y se devuelve.
- `proponer`: interpretar → controlar → `NINGUNA` ⇒ vacío. `ATENCION` sin `sesion:register`
  (evaluado con `PermissionEvaluator`) pasa a `LLEGADA`. Persona: `PAGO`, `ATENCION`, `LLEGADA` y
  `PACIENTE_EDICION` la resuelven con la herramienta `BUSCAR_PERSONA` y la misma aclaración de hoy
  (`FALTA_PERSONA`, `PERSONA_NO_ENCONTRADA`, `ACLARACION`). `preparar` con `AccessDeniedException`
  ⇒ `SIN_PERMISO`. `sinTarjeta` ⇒ respuesta `RESPONDIDA` con ese texto, sin guardar nada. Con
  tarjeta ⇒ guarda la fila (`vence_en = ahora + 15 min`) y responde `PROPUESTA` con texto
  "Entendí esto. Revisalo y confirmá.".
- En respuestas de acción, `interpretacion` es `FUERA_DE_ALCANCE` (el historial no hereda nada).
- `confirmar`: buscar (otra cuenta/sede/tenant ⇒ 404) → el tipo de la tarjeta y sus ids deben
  coincidir con `tipo` y `referencias` guardados, si no `IllegalArgumentException` ⇒ 400 → `tomar`;
  0 filas: si la fila está `CONFIRMADA` devuelve su resultado guardado, si no 409
  `accion-no-vigente` → `ejecutar` fuera de transacción → `CONFIRMADA`: `confirmar(...)`;
  `RECHAZADA`, `AccessDeniedException` o cualquier excepción: `soltar` (y relanzar la excepción
  que no sea de permiso).
- Auditoría: `ASISTENTE_ACCION_PROPUESTA|CONFIRMADA|RECHAZADA|DESCARTADA` con `tipo` e ids.

Tests:

- [ ] `AccionConfirmacionIT` (con una `AccionDelAsistente` de prueba registrada en el contexto del
  test): dos `confirmar` en paralelo ⇒ `ejecutar` corre una vez y las dos respuestas son la misma
  `CONFIRMADA` o una es 409; tarjeta con otro `personaId` ⇒ 400 y `ejecutar` no corre; `vence_en`
  pasado ⇒ 409; otra cuenta ⇒ 404; `RECHAZADA` ⇒ la fila vuelve a `PROPUESTA` y se puede
  reconfirmar.
- [ ] `AsistenteServiceTest`: con acciones apagadas, `"Juan pagó 15000"` sigue devolviendo la
  ayuda de hoy; con acciones prendidas y `"¿vino Juan?"`, el interpretador de acciones no se llama.
- [ ] Commit.

### Tarea 4 — Asistencia de clase (`activity`)

**Archivos:** crear `activity/infrastructure/AsistenciaPorElAsistente` (+ test de las reglas).

- `preparar`: `ClaseService.listar` del día de la sede (`pedido.hoy()` en `pedido.zona()`), sin
  canceladas; filtro por `pedido.clase()` con `ClasesEnElAsistente.elegir`-style (título u oferta
  plegados); orden: `EN_CURSO` primero, después por inicio. Sin clases ⇒ `sinTarjeta` "No encontré
  una clase de hoy…". Participantes con `AsistenciaService.detalleOperativo` (sólo inscripciones
  confirmadas). `claseId` de la tarjeta = la única, o `null` si hay varias.
- Marcado (función pura `marcar(List<participante>, modo, nombres)`): `TODOS_MENOS` ⇒ PRESENTE
  salvo nombrados AUSENTE; `SOLO` ⇒ nombrados PRESENTE, resto AUSENTE; sin modo ⇒ todos sin
  marcar. Un nombre casa si su texto plegado es igual al nombre o al apellido plegado, o al
  "nombre apellido" completo; 0 o 2+ coincidencias ⇒ aviso `«María» no está entre los inscriptos`
  / `Hay más de una «María»: marcala a mano` y esas filas sin marcar.
- Bloqueo si la clase elegida está `PROGRAMADA` y el actor no tiene `clase:manage`.
- `ejecutar`: la clase de la tarjeta tiene que ser de la sede; `AsistenciaService.iniciar` si
  `PROGRAMADA`; `registrarLote` con los ítems que tienen resultado; a los que cambian un resultado
  ya registrado, motivo "Corrección desde el asistente". Resultado: "Registré N presentes y M
  ausentes en <clase>", `detalles` con los ítems que fallaron, enlace `/asistencia`.

- [ ] Código. - [ ] `AsistenciaPorElAsistenteTest` sobre `marcar`: los tres modos y el nombre
  duplicado (Foco 5). - [ ] Commit.

### Tarea 5 — Pago (`billing`)

**Archivos:** crear `billing/infrastructure/PagoPorElAsistente` (+ test de la imputación).

- `preparar`: obligaciones de la persona con `Responsable` paciente y estado `PENDIENTE`/`PARCIAL`,
  de la más vieja a la más nueva; moneda = la de la más vieja (las de otra moneda: aviso); función
  pura `imputar(deudas, monto)` ⇒ imputaciones + anticipo. Sin monto: imputaciones en cero.
  Sin deudas: moneda `ARS`. `cajaAbierta` = jornada abierta en la sede; bloqueo si `medio` es
  `EFECTIVO` y está cerrada. Título "Pago de <nombre>".
- `ejecutar`: exige `total > 0` y `medio`; arma `CobroCommand` con un medio, las imputaciones con
  importe > 0, `anticipo`, `moneda` e `idempotencyKey = "asistente-accion-" + accionId`. Las
  excepciones de negocio de `billing` (cobro que no cuadra, caja cerrada, saldo insuficiente) ⇒
  `RECHAZADA` con su mensaje. Resultado: "Registré el cobro de $X a <nombre>", enlace a la cuenta
  de la persona.

- [ ] Código. - [ ] `PagoPorElAsistenteTest` sobre `imputar`: 15000 contra deudas de 10000 y 8000
  ⇒ 10000 + 5000, anticipo 0; 15000 contra una de 10000 ⇒ anticipo 5000; sin deudas ⇒ todo
  anticipo. - [ ] Commit.

### Tarea 6 — Paciente (`person`)

**Archivos:** crear `person/infrastructure/AltaDePacientePorElAsistente`,
`EdicionDePacientePorElAsistente`.

- Alta: bloqueo si falta nombre o apellido; `posiblesDuplicados` con la búsqueda de coincidencias
  del servicio; documento ya registrado ⇒ bloqueo con el nombre de esa persona. `ejecutar`:
  `PersonaService.crear` (con `confirmaPosibleDuplicado` de la tarjeta) y
  `PerfilPacienteService.activar` con motivo "Alta desde el asistente", en una transacción
  (`TransactionTemplate`; aquí sí, las dos son `@Transactional` comunes). Enlace `/pacientes/{id}`.
- Edición: `anterior` = datos actuales; `datos` = sólo los campos dichos; `version` = la leída.
  Sin ningún campo ⇒ `sinTarjeta` "Decime qué dato cambiar…". `ejecutar`: edición con
  `expectedVersion`; conflicto de versión ⇒ `RECHAZADA` "Alguien modificó la ficha, volvé a
  pedirlo".

- [ ] Código. - [ ] Commit.

### Tarea 7 — Llegada (`scheduling`) y atención (`encounter`)

**Archivos:** crear `scheduling/infrastructure/LlegadaPorElAsistente`,
`encounter/infrastructure/AtencionPorElAsistente`.

- Llegada: turnos de hoy de la persona en la sede, no cancelados; ninguno ⇒ `sinTarjeta`; varios ⇒
  el más próximo a ahora, con aviso. Aviso fijo si `pedido.practicas()` no está vacío: "Los
  tratamientos los registra el profesional". `ejecutar`:
  `CicloDeRecepcionService.registrarLlegada`.
- Atención — `preparar`, según §6.3 del spec: turno de hoy con este profesional; turno de otro
  profesional ⇒ bloqueo "Tiene turno hoy con <X>"; sin turno ⇒ ofertas vigentes en que el
  profesional está habilitado; caso si la oferta lo exige; prácticas contra las de la oferta y
  después el catálogo (coincidencia única por nombre plegado contenido); `nota = pedido.frase()`;
  `cerrar = true`; `tratamientosPrevios` si ya hay sesión abierta; sesión cerrada ⇒ bloqueo.
- Atención — `ejecutar`, sin transacción: `iniciar` o `iniciarSinTurno` (motivo "Registrada desde
  el asistente", `idempotencyKey` de la acción) → por cada tratamiento con `practicaId`:
  releer la sesión y `TratamientoService.registrar` con su versión → si `cerrar`:
  releer y `SesionService.cerrar` con PRESENTE y la nota. Fallo en el cierre ⇒ **`CONFIRMADA`**
  con texto "Cargué la atención pero no pude cerrarla: <motivo>. Quedó abierta." y enlace a la
  atención (lo cargado no se deshace; reintentar duplicaría tratamientos). Fallo antes de cargar
  ningún tratamiento ⇒ `RECHAZADA`.
- Cómo se obtiene el profesional del actor y las consultas de turno/oferta/caso: por los `spi` que
  `encounter` ya usa; si falta una lectura, se agrega al `spi` del módulo dueño (ArchUnit con
  clase sonda antes de comprometer la arista).

- [ ] Llegada. - [ ] Atención. - [ ] ArchUnit en verde. - [ ] Commit.

### Tarea 8 — Contrato, medición y prueba de punta a punta

- [ ] `AsistenteAccionesIT` con el modelo `prueba` devolviendo el JSON de un pago: consulta ⇒
  `PROPUESTA` con tarjeta; confirmación ⇒ existe el cobro en la base con la `idempotencyKey`;
  segunda confirmación ⇒ mismo resultado, un solo cobro.
- [ ] Subir `akine.contract.version` y `./mvnw verify -Dakine.contract.update=true`
  (`OpenApiContractIT`); revisar que el diff del YAML sea sólo aditivo.
- [ ] `docs/asistente/evaluacion/acciones.json` (40+ frases, §8 del spec) y soporte en
  `evaluar.mjs`; correr contra Ollama con GPU; correr también los sets existentes; anotar
  `resultados-<fecha>.md`. Ajustar prompt y `EsPregunta` hasta el piso del §8.
- [ ] Bitácora: `docs/asistente/bitacora-de-implementacion.md` con los problemas encontrados.
- [ ] Commit.

### Tarea 9 — Frontend: marco y charla (`appKine-web`, worktree `wt-WEB-ACCIONES`)

- [ ] Rama `akine-web-asistente-acciones` desde `origin/main`; regenerar el cliente contra el
  contrato del worktree del backend.
- [ ] `AsistenteApi.confirmarAccion(id, tarjeta)` y `descartarAccion(id)`.
- [ ] `tarjeta-de-accion` (marco: título, avisos, bloqueos, Descartar/Confirmar, estados
  propuesta / enviando / confirmada / rechazada / vencida) y su uso en `charla-del-asistente`.
- [ ] Aviso fijo según permiso y estado de acciones (§7 del spec).

### Tarea 10 — Frontend: las cinco tarjetas

- [ ] `tarjeta-de-asistencia` (selector de clase si hay varias; lista con presente/ausente).
- [ ] `tarjeta-de-pago` (monto, medio, moneda, imputaciones editables, anticipo calculado).
- [ ] `tarjeta-de-atencion` (turno/oferta/caso, tratamientos con buscador de prácticas, nota,
  cerrar). - [ ] `tarjeta-de-llegada`. - [ ] `tarjeta-de-paciente` (alta y edición antes→después).
- [ ] Specs sólo de la lógica de `puedeConfirmar` de cada tarjeta.

### Tarea 11 — Verificación en la app real

- [ ] Pila aislada (base vacía desde V1, perfiles `local,ia`, acciones prendidas) y las cinco
  acciones de punta a punta en el navegador, con captura y verificación en la base.
