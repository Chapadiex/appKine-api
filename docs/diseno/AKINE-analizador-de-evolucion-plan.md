# Analizador de evolución, fase 1 — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un profesional abra la pestaña "Evolución" de un Caso Clínico y vea indicadores y
alertas calculados con reglas sobre las sesiones cerradas del caso, cada alerta con las sesiones que
la disparan.

**Architecture:** Todo el cálculo en `encounter`:

- `ReglasDeEvolucion` (dominio puro) recibe sesiones, plan y turnos y devuelve indicadores y hallazgos;
- `EvolucionDelCasoService` autoriza, reúne y audita;
- tres `spi` nuevos traen lo que no es de `encounter`: `clinical.spi.AccesoClinicoDirectory`,
  `clinical.spi.PlanVigenteDirectory` y `scheduling.spi.TurnosDelCasoDirectory`;
- `resource` suma el sentido de mejora a la definición de medición.

Sin modelo de lenguaje y sin tabla de resultados: se calcula al leer.

**Tech Stack:** Java 21, Spring Boot 4.1.x, PostgreSQL 18, Flyway, ArchUnit; Angular 22.

**Spec:** `docs/diseno/AKINE-analizador-de-evolucion.md` (15/11/2026). D-1, D-2 y D-4 resueltas el
15/11; el resto del §11 no frena esta fase.

## Global Constraints

- **Ramas:** backend `C:\Users\santo\Desktop\AKINE\wt-API-EVOLUCION` (`akine-analizador-evolucion`).
  Frontend: crear `wt-WEB-EVOLUCION` desde `origin/main` de `appKine-web` recién en la Tarea 9.
- **Commits:** locales y en prosa. Sin push, PR ni merge hasta que el usuario lo pida.
- **Migración `V17`.** `main` llega a `V14`; `V15` y `V16` las usa la rama
  `akine-servicio-personalizado`, sin mergear. Reservarla en `docs/fases/01-trabajo-en-paralelo.md`
  antes de escribirla y confirmar el número al rebasear.
- **Contrato `0.170.0`**, aditivo (`main` está en 0.169.0). Confirmar al rebasear: el asistente de
  datos y `akine-servicio-personalizado` también suben la minor.
- **Feature flag** `akine.evolucion.enabled`, por defecto `false`. Con el flag apagado el endpoint
  responde 404.
- **Permiso:** `hc:read` (`clinical.domain.PermissionCodes.HC_READ`). No se crea código nuevo.
- **No se materializa nada.** Ninguna tabla guarda indicadores ni hallazgos.
- **Lectura que audita:** la transacción del servicio **no** es `readOnly` (defecto 14 del 11/10).
- **Datos sintéticos** en todos los tests.
- **Qué correr:** sólo lo que toca cada tarea. La suite completa es del CI.
- **Umbrales** (propiedades `akine.evolucion.*`, valores por defecto del diseño §5.3):

  | Propiedad | Defecto |
  |---|---|
  | `minimo-sesiones` | 3 |
  | `dolor.ventana` | 4 |
  | `dolor.cambio-relevante` | 2 |
  | `dolor.piso` | 4 |
  | `dolor.ventana-aumento` | 3 |
  | `evolucion.peor-seguidas` | 2 |
  | `evolucion.igual-seguidas` | 4 |
  | `tolerancia.mala-seguidas` | 2 |
  | `medicion.minimo-puntos` | 4 |
  | `medicion.variacion-minima-porcentaje` | 5 |
  | `adherencia.ventana-citas` | 6 |
  | `adherencia.inasistencias-porcentaje` | 30 |
  | `frecuencia.ventana-semanas` | 4 |
  | `sin-mediciones.ventana` | 4 |

---

## Tarea 1 — Requisito funcional y reservas

**Archivos:** `docs/producto/AKINE_Requerimientos_Integrados.md`,
`docs/fases/01-trabajo-en-paralelo.md`, `docs/seguridad/matriz-permisos-minima.md`.

- [ ] Agregar la sección `# M31 — Evolución del caso clínico` después de M30 (termina en
  RF-M30-012), con el mismo formato que M30:
  - **RF-M31-001 — Analizar la evolución de un caso**: indicadores del diseño §5.2, sólo sesiones
    cerradas del caso, mínimo de 3 presentes.
  - **RF-M31-002 — Alertas de evolución**: los trece códigos del §5.3, cada una con las sesiones o
    turnos que la disparan.
  - **RF-M31-003 — Sentido de mejora de una medición**: `SUBE`, `BAJA` o sin definir.
  - **RN-M31-001**: el análisis no modifica sesión, caso ni plan.
  - **RN-M31-002**: no se guarda; se calcula al leer.
  - **RN-M31-003**: exige `hc:read` y relación asistencial o justificación; toda lectura se audita.
  - **RN-M31-004**: los umbrales son configuración y requieren aprobación clínica (U-02).
  - **CA** por RF, uno por alerta como mínimo en RF-M31-002.
  - La fase 2 (sugerencias con modelo) queda nombrada como fuera de alcance de M31 por ahora.
- [ ] Reservar `V17` y `0.170.0` en `docs/fases/01-trabajo-en-paralelo.md` (tabla de la línea ~178).
- [ ] En la matriz, anotar en la fila de `hc:read` que cubre "Ver evolución del caso (M31)".
- [ ] Commit: `docs: RF de M31 y reservas de V17 y 0.170.0`.

## Tarea 2 — Sonda de ArchUnit

Antes de comprometer aristas (`akine-archunit-ciclos-largos`).

- [ ] Crear tres clases vacías: `encounter/infrastructure/SondaEvolucion.java` con un campo de tipo
  `com.akine.knowledge.spi.BibliotecaDirectory`; `clinical/spi/SondaPlanVigente.java`;
  `scheduling/spi/SondaTurnosDelCaso.java`. La primera importa también las otras dos.
- [ ] Correr:

```bash
./mvnw -o test -Dtest="com.akine.architecture.ModuleArchitectureTest" -Djacoco.skip=true
```

- [ ] Verde → borrar las tres sondas, sin commit. Rojo → **parar** y volver al diseño §4.1 con el
  ciclo que ArchUnit nombre.

## Tarea 3 — `resource`: sentido de mejora (RF-M31-003)

**Archivos:** `src/main/resources/db/migration/V17__m31_sentido_de_mejora_de_medicion.sql`,
`resource/domain` (entidad de `medicion_definicion`), `resource/spi/MedicionDefinicionSnapshot.java`,
nuevo `resource/spi/SentidoDeMejora.java`, DTOs de alta, edición y lectura de la definición.

- [ ] Migración:

```sql
ALTER TABLE medicion_definicion ADD COLUMN mejora_cuando varchar(10);
ALTER TABLE medicion_definicion ADD CONSTRAINT ck_medicion_definicion_mejora_cuando
    CHECK (mejora_cuando IS NULL OR mejora_cuando IN ('SUBE', 'BAJA'));
```

- [ ] Enum `SentidoDeMejora { SUBE, BAJA }` en `resource.spi`. Campo nullable en la entidad, en el
  snapshot y en los DTOs. Sólo válido para `NUMERICO` y `ESCALA`: con `TEXTO` o `BOOLEANO` → 400
  `validation-error`.
- [ ] **No** se copia a `sesion_medicion`: el analizador lo lee vivo de `MedicionDirectory` por
  `definicion_id`. Un cambio de sentido reinterpreta la serie entera, que es lo que se quiere.
- [ ] Test unitario de la validación por tipo. Correr los de `resource`:

```bash
./mvnw -o test -Dtest="com.akine.resource.**.*Test" -Djacoco.skip=true
```

- [ ] Commit: `feat(resource): sentido de mejora en la definición de medición (RF-M31-003)`.

## Tarea 4 — `clinical.spi`: acceso clínico y plan vigente

**Archivos nuevos:** `clinical/spi/AccesoClinicoDirectory.java`, `clinical/spi/ActorClinico.java`,
`clinical/spi/PlanVigenteDirectory.java`, `clinical/spi/PlanVigenteSnapshot.java`,
`clinical/application/AccesoClinicoDesdeOtroModulo.java`,
`clinical/application/PlanVigenteDelCaso.java`.

- [ ] `ActorClinico(long accountId, boolean platformAdmin, Long organizationId, Long consultorioId)`.
- [ ] `AccesoClinicoDirectory.exigirLectura(ActorClinico actor, long personaId, String justificacion, String operacion)`.
  La implementación delega en `AutorizacionClinica.exigir(...)` con `PermissionCodes.HC_READ`, sin
  duplicar ninguna regla. Lanza las mismas excepciones, que ya mapea `ClinicalProblemHandler`.
  - Verificar que `EncounterProblemHandler` no las intercepte y que el 403 salga con `type`
    `justificacion-de-acceso-requerida`. Si no sale, mover el mapeo a un advice que cubra los dos
    paquetes, **no** a `GlobalExceptionHandler`.
- [ ] `PlanVigenteSnapshot(long planId, String estado, Instant activadoEn, String objetivos,
  String indicaciones, Integer frecuenciaSemanal, Integer duracionSemanas, List<ItemDelPlan> items)`
  con `ItemDelPlan(long ofertaId, String ofertaNombre, int planificadas, int realizadas, int canceladas, boolean completo)`.
- [ ] `PlanVigenteDirectory.delCaso(long organizationId, long casoId)` devuelve el plan `ACTIVO` o
  `SUSPENDIDO` con su última versión y el avance, reusando el cálculo de
  `PlanTratamientoService.avance` **sin** su control de permiso (el permiso ya lo exigió quien
  llama). Extraer el cálculo a un método interno compartido; no copiarlo.
- [ ] Agregar a `CasoSnapshot` lo que falte de: `historiaClinicaId`, `personaId`, `estado`,
  `diagnosticoPresuntivo`, `objetivoTerapeutico`, `abiertoEn`. Revisar primero qué trae hoy.
- [ ] Test unitario de `PlanVigenteDelCaso`: sin plan, con plan activo, con suspendido.
- [ ] Commit: `feat(clinical): spi de acceso clínico y de plan vigente del caso`.

## Tarea 5 — `scheduling.spi`: turnos del caso

**Archivos nuevos:** `scheduling/spi/TurnosDelCasoDirectory.java`,
`scheduling/spi/TurnoDelCasoSnapshot.java`, `scheduling/infrastructure/TurnosDelCasoSobreTurnos.java`;
consulta nueva en `TurnoRepository`.

- [ ] `TurnoDelCasoSnapshot(long turnoId, Instant inicio, String estado, String origenCancelacion, Long profesionalMembershipId)`.
- [ ] La consulta filtra por `organization_id` y `caso_id` y **no** filtra `deleted_at`: cancelar
  sella `deleted_at` (defecto 16 del 11/10) y los cancelados son justo lo que se busca. Orden por
  `inicio`.
- [ ] Un turno reprogramado es una sola fila (reprogramar mueve): no se cuenta como inasistencia.
- [ ] IT `TurnosDelCasoIT`: un caso con un turno atendido, uno ausente, uno cancelado por el
  paciente y uno cancelado por la institución; otro caso y otra organización no aparecen.

```bash
./mvnw -o verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=TurnosDelCasoIT -Djacoco.skip=true
```

- [ ] Commit: `feat(scheduling): spi de turnos del caso`.

## Tarea 6 — `encounter`: lectura de las sesiones del caso

**Archivos:** `encounter/domain/port/SesionRepositoryPort.java` y su repositorio JPA; nuevo
`encounter/application/SesionParaEvolucion.java` (record plano).

- [ ] `SesionParaEvolucion`: `sesionId`, `numeroEnCaso`, `cerradaEn`, `asistencia`, `dolorEva`,
  `dolorZona`, `dolorLateralidad`, `evolucion`, `tolerancia`, `proximaConducta`,
  `profesionalMembershipId`, `mediciones` (`definicionId`, `codigo`, `nombre`, `unidad`, `tipo`,
  `lateralidad`, `valorNumerico`) y `tratamientos` (`practicaId`, `practicaNombre`, `tecnica`, `zona`).
- [ ] Tres consultas fijas, sin N+1: sesiones `CERRADA` sin baja del caso ordenadas por
  `cerrada_en`; mediciones `NUMERICO`/`ESCALA` de esas sesiones; tratamientos activos de esas
  sesiones. Se arman en memoria.
  - La fila de `sesion` ya tiene los valores vigentes después de una enmienda: no se lee
    `sesion_version`. **Verificar** en `SesionService` (enmienda) antes de darlo por cierto.
- [ ] Conteo aparte: sesiones cerradas de la **misma historia** con `caso_id IS NULL` cerradas
  entre la primera y la última del caso (para la advertencia de D-8).
- [ ] Tope de **200** sesiones, las más recientes; si se corta, advertencia en la respuesta.
- [ ] Sin test propio: lo cubre el IT de la Tarea 8.
- [ ] Commit: `feat(encounter): lectura de sesiones cerradas del caso`.

## Tarea 7 — `encounter`: `ReglasDeEvolucion`

**Archivos nuevos:** `encounter/domain/evolucion/ReglasDeEvolucion.java`, `UmbralesDeEvolucion.java`
(record), `DatosDelCaso.java`, `AnalisisDeEvolucion.java`, `Indicadores.java`, `Hallazgo.java`,
`CodigoDeHallazgo.java` (enum con los trece códigos más `DATOS_INSUFICIENTES`), `Severidad.java`;
test `ReglasDeEvolucionTest.java`.

- [ ] Función pura: `AnalisisDeEvolucion analizar(DatosDelCaso datos, UmbralesDeEvolucion umbrales, LocalDate hoy)`.
  Sin Spring, sin reloj propio, sin repositorios. `hoy` lo pone el servicio con la zona de la sede.
- [ ] Indicadores del diseño §5.2. Detalles que no pueden quedar a criterio:
  - Las series usan sólo sesiones `PRESENTE`, en orden de `cerradaEn`.
  - Una serie de medición se agrupa por `definicionId` + `lateralidad`. Si la unidad cambia dentro
    de la serie: `unidadesDifieren = true` y ninguna alerta la evalúa.
  - Adherencia: citas = turnos del caso que no fueron cancelados por `PROFESIONAL` ni `INSTITUCION`,
    más las sesiones sin turno. Inasistencias = turnos `AUSENTE` + turnos `CANCELADO` con origen
    `PACIENTE` + sesiones cerradas `AUSENTE` **cuyo turno no esté ya contado**. Los dos orígenes
    viajan por separado en el indicador además del total.
  - Frecuencia real: sesiones presentes de los últimos `ventana-semanas` × 7 días, dividido las semanas.
- [ ] Alertas del §5.3, una función privada por código. Cada `Hallazgo` lleva `codigo`,
  `severidad`, `sesiones` (ids), `turnos` (ids) y `valores` (mapa plano para la plantilla del
  texto). El texto **no** se arma en el dominio.
  - `MEDICION_EMPEORA`: sólo con `mejoraCuando` definido; dispara si, con `minimo-puntos` o más, la
    diferencia entre el primero y el último va en contra del sentido y supera
    `variacion-minima-porcentaje` del rango.
  - `MEDICION_SIN_CAMBIO` y `MEDICION_EMPEORA` no se evalúan sin `minimo` y `maximo` en la definición.
  - `CONDUCTA_SIN_SEGUIR`: mira la **penúltima** sesión presente con conducta `ALTA`, `DERIVA` o
    `REEVALUA` y exige que el plan vigente tenga la misma versión que antes de esa sesión. Si no hay
    forma de saber la versión del plan en esa fecha, la alerta **no se implementa** y se anota en el
    diseño §5.3; no se inventa una aproximación.
  - `PLAN_COMPLETO_ABIERTO` y `PLAN_EXCEDIDO_EN_TIEMPO` no disparan con plan `SUSPENDIDO`.
- [ ] Con menos de `minimo-sesiones` presentes: indicadores completos y un único hallazgo
  `DATOS_INSUFICIENTES` (`INFO`).
- [ ] Tests, pocos y de regla: por cada código, un caso que dispara y el borde que no; más
  unidad que cambia, medición sin rango, menos de 3 sesiones, cancelación de la institución que no
  cuenta, y ausencia registrada en turno y en sesión que cuenta una vez.

```bash
./mvnw -o test -Dtest="com.akine.encounter.domain.evolucion.*Test" -Djacoco.skip=true
```

- [ ] Commit: `feat(encounter): reglas de evolución del caso (RF-M31-001, RF-M31-002)`.

## Tarea 8 — `encounter`: servicio, endpoint, auditoría y contrato

**Archivos nuevos:** `encounter/application/EvolucionDelCasoService.java`,
`encounter/application/EvolucionDelCasoView.java`, `encounter/application/TextosDeHallazgo.java`,
`encounter/api/EvolucionDelCasoController.java`, `encounter/api/dto/EvolucionDelCasoResponse.java`
y sus anidados, `encounter/infrastructure/config/EvolucionProperties.java`; se tocan
`encounter/application/AuditEvents.java`, `application.yml`, `openapi/akine-api.yaml`; IT
`EvolucionDelCasoIT.java`.

- [ ] `GET /api/v1/consultorios/{consultorioId}/casos/{casoId}/evolucion`, `operationId`
  `verEvolucionDelCaso`, header opcional `X-Justificacion-Acceso`. `produces` incluye
  `application/json`.
- [ ] Orden dentro del servicio, en una transacción que **no** es `readOnly`:
  1. flag apagado → 404;
  2. `CasoDirectory` por organización del actor; no existe o es de otra organización → **404**;
  3. `AccesoClinicoDirectory.exigirLectura(...)` con la persona del caso, operación `EVOLUCION_DEL_CASO`;
  4. sesiones (Tarea 6), `PlanVigenteDirectory`, `TurnosDelCasoDirectory`, definiciones por
     `MedicionDirectory` en **una** llamada por lote (agregar `findAll(ids)` al `spi` si no existe);
  5. `ReglasDeEvolucion.analizar` con `hoy` en la zona de la sede (`organization.spi.ZonaDeSede`);
  6. `TextosDeHallazgo` arma el texto de cada hallazgo con una plantilla por código;
  7. auditar `EVOLUCION_DEL_CASO_ANALIZADA`: caso, cantidad de sesiones, códigos de hallazgo y vía
     de acceso. Sin texto clínico. Con `PLATFORM_ADMIN`, además `SUPPORT_ACCESS_USED`.
- [ ] Nombres de schema únicos en todo el contrato: prefijo `EvolucionDelCaso…` y
  `HallazgoDeEvolucion…` (`SchemaNameCollisionTest`).
- [ ] La respuesta sigue el JSON del diseño §5.4 y suma `plan` (avance) y `tratamientos`.
- [ ] `EvolucionDelCasoIT`, contra PostgreSQL:
  - caso con 6 sesiones, plan activo y turnos → 200 con `DOLOR_SIN_MEJORA` y `BAJA_ADHERENCIA`, y
    una fila en `audit_event`;
  - sin `hc:read` → 403; sin relación ni justificación → 403 `justificacion-de-acceso-requerida`;
    con justificación → 200;
  - caso de otra organización → 404;
  - flag apagado → 404.
- [ ] Regenerar el contrato y subirlo a `0.170.0` (`pom.xml`, `application.yml`, YAML):

```bash
./mvnw verify -Dakine.contract.update=true -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test="OpenApiContractIT,EvolucionDelCasoIT" -Djacoco.skip=true
```

- [ ] ArchUnit y colisiones de schema:

```bash
./mvnw -o test -Dtest="ModuleArchitectureTest,SchemaNameCollisionTest" -Djacoco.skip=true
```

- [ ] Commit: `feat(encounter): evolución del caso clínico, contrato 0.170.0`.

## Tarea 9 — Frontend: pestaña "Evolución"

Worktree `wt-WEB-EVOLUCION` desde `origin/main` de `appKine-web`. Leer antes su `CLAUDE.md`,
`DESIGN.md` y `docs/ux/00-plan-refactor-ux-ui.md` §3.2 y §3.4.

- [ ] Regenerar el cliente contra `0.170.0` (`npm run api:check` en verde).
- [ ] Sentido de mejora en el formulario de definición de medición del catálogo: selector con
  "Sin definir", "Mejora cuando sube", "Mejora cuando baja", visible sólo para numérica y escala.
- [ ] Pestaña "Evolución" en la pantalla del Caso, visible con `hc:read` y con el flag expuesto por
  el backend (si no hay forma de leer el flag, la pestaña se oculta ante el 404).
  - Arriba, las alertas: una tarjeta por hallazgo, severidad con texto además de color, y enlaces a
    cada sesión que la dispara.
  - Debajo, los indicadores: serie de dolor y series de mediciones como tabla compacta (fecha,
    valor, diferencia); adherencia; frecuencia real contra plan; avance del plan.
  - Sin gráficos en esta tanda: el bundle inicial no tiene margen. Si se quieren, van con `@defer`.
  - Estado `DATOS_INSUFICIENTES` como mensaje único, no como alerta.
  - Texto fijo bajo el título, detrás del "?": "Cálculo automático sobre las sesiones cerradas del
    caso. No reemplaza el criterio profesional."
  - Sin justificación (403 `justificacion-de-acceso-requerida`): el mismo diálogo de motivo que usa
    la historia clínica.
- [ ] Tests: el componente con `DATOS_INSUFICIENTES`, con alertas y con el 403. Sin E2E (en pausa).
- [ ] Captura de la pestaña en claro y oscuro, a 375 px y de escritorio, para mostrarle al usuario.
- [ ] Commit local. Avisar antes de pushear.

## Tarea 10 — Cierre

- [ ] `/simplify` y `/code-review` sobre el diff de cada repo.
- [ ] Probar en una pila aislada con datos: un caso real de la base de QA con sesiones cargadas, y
  verificar en la base la fila de `audit_event`.
- [ ] Registro de avance al final de `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`: qué entró, qué
  alertas quedaron afuera y por qué, y que los umbrales esperan la aprobación clínica.
- [ ] Preguntar al usuario por el push y los PR. Orden: api primero, web después.

## Fuera de este plan

- Fase 2 completa (modelo, biblioteca, validador, `sugerencia_evolucion`): plan propio después de
  D-3 y D-7 y del set de casos del diseño §9.
- Sembrar definiciones de medición (ROM, fuerza): decisión de catálogo, no de este paquete.
- Aviso de alertas al abrir la sesión siguiente (D-6).
- Gráficos de las series.
