# AKINE-A-11 — Impacto previo de disponibilidad y coberturas del 360 como campos

> Paquete A-11 de `docs/fases/01-trabajo-en-paralelo.md`. Contrato **0.70.0** (aditivo). Sin
> migración (`V84` no se usó). Dos huecos que reportaron las pantallas de A-10 y B-5 web.

## 1. Impacto previo al editar disponibilidad (RN-M05-004, RF-M05-005)

### Problema

Desde E-1 la cantidad de turnos afectados viaja en `BloqueResponse.turnosAfectados` **después**
de aplicar el `PUT`/`DELETE`. La pantalla necesita saberlo **antes** de confirmar. Además el número
era una **cota superior**: `DisponibilidadImpactProbe.turnosEn` recibía la ventana y no el bloque,
así que contaba también los turnos de otro bloque vigente del mismo profesional (límite (a) de la
decisión pendiente 8).

### Diseño

- **El `spi` se invierte en contenido, no en dirección.** `DisponibilidadImpactProbe` deja de
  devolver una cuenta y pasa a devolver los turnos pendientes de la ventana
  (`pendientesEn(org, sede, membershipId|null, desde, hasta)` → `TurnoPendiente(id, membership,
  inicio, fin)`). `scheduling` la sigue implementando; la arista `scheduling → resource.spi` ya
  existía y no se agrega ninguna. `null` en la membership pide los de toda la sede (excepción de
  alcance sede): consulta nueva `findPendientesEnLaSede`.
- **`resource` decide qué queda afuera**, con `SimuladorDeImpacto`: lee bloques y excepciones de
  la ventana, arma la versión posterior **sobre copias transitorias**, corre
  `DisponibilidadEfectivaCalculator` antes y después y marca afectado el turno que estaba cubierto
  entero antes y no después (las franjas contiguas se unen). Es el mismo calculador que alimenta la
  disponibilidad efectiva y los slots, así que la respuesta es coherente con lo que la agenda va a
  ofrecer.
- **Cuatro consultas previas sin efectos** (sin lock, sin escritura, sin auditoría,
  `@Transactional(readOnly = true)`, `consultorio:manage` igual que la mutación):
  - `POST …/profesionales/{m}/disponibilidad/{bloqueId}/impacto-de-edicion` — cuerpo
    `SimularEdicionBloqueRequest` (el de la edición sin `version`).
  - `GET …/disponibilidad/{bloqueId}/impacto-de-baja`.
  - `POST /consultorios/{id}/excepciones/impacto-de-alta` — cuerpo `CreateExcepcionRequest`.
  - `GET /consultorios/{id}/excepciones/{excepcionId}/impacto-de-baja`.
  Responden `ImpactoDisponibilidadResponse`: `turnosAfectados` (cuenta completa),
  `primerTurnoAfectado`, `turnos` (hasta 50: `turnoId`, `membershipId`, `inicio`, `fin`, sin dato
  del paciente) y `evaluadoHasta` (fecha local exclusiva hasta la que se miró).
- **El `PUT` y el `DELETE` de bloque usan el mismo simulador**: `turnosAfectados` pasa de cota a
  cuenta exacta sin cambiar de forma. La ventana se mantiene: de ahora al fin de vigencia más lejano
  entre el anterior y el nuevo, con horizonte de 90 días.

### Decisiones

1. **Exacto en vez de cota.** Un turno que ya estaba fuera de la disponibilidad antes del cambio
   (sobreturno) tampoco cuenta: no es este cambio el que lo deja afuera.
2. **No se mira el vínculo.** Un turno de un profesional desvinculado está en conflicto por la
   desvinculación, que tiene su propia sonda.
3. **La consulta previa no compara versión ni adelanta los 409 de la mutación** (sede inactiva,
   vínculo no vigente): contesta el impacto, la mutación sigue validando. Sí da 409 si el bloque o
   la excepción ya están dados de baja.
4. **Las excepciones no devuelven impacto en su alta/baja real** (`ExcepcionResponse` no cambió):
   la pantalla usa la consulta previa. Agregarlo es aditivo si hiciera falta.

## 2. Coberturas del Paciente 360 como campos

### Problema

La sección `coberturas` (B-5, #34) viaja como hitos genéricos y la pantalla parte el `titulo`
("Swiss Medical · SMG20 · Afiliado ···8765 · hasta 2027-01-06") para separar los datos.

### Diseño

- `person.spi.HitoDeResumen` suma un componente opcional `CoberturaDeResumen cobertura`, con un
  constructor de seis argumentos que lo deja en `null`: los contribuyentes de `scheduling` y
  `billing` no cambian.
- `HitoResponse` suma `cobertura` (nullable) de tipo `CoberturaDelHitoResponse`
  (`@Schema(name)` propio, sin colisión): `tipo`, `financiadorId`, `financiadorNombre`, `planId`,
  `planNombre`, `afiliadoEnmascarado`, `vigenciaDesde`/`vigenciaHasta` como **fechas sin hora**,
  `principal`, `estadoCredencial` (`VIGENTE` · `VENCIDA` · `SIN_VENCIMIENTO`) y
  `credencialVigenciaHasta`.
- El `titulo` y el resto del contrato genérico de secciones no cambian. El afiliado sigue
  enmascarado con la misma función.

## 3. Design challenge

1. **Ownership** — No hay tablas nuevas. La consulta nueva lee `turno`, de `scheduling`, desde
   `scheduling`. `resource` sigue sin leer `turno`.
2. **Ciclos** — La única arista involucrada es `scheduling → resource.spi`, que ya existía; el
   `spi` cambia de firma, no de dirección. `person.spi` gana un record propio, sin arista nueva.
   ArchUnit corre en la suite unitaria.
3. **Tenant** — Las dos consultas de turnos filtran por `organization_id` y `consultorio_id`; la
   sede sale del contexto revalidado. El IT prueba que el token de otro tenant no ve nada.
4. **Reglas maestras** — Disponibilidad ≠ turno se respeta: la consulta informa, no cancela ni
   mueve turnos (ADR-0011, RN-M05-004).
5. **Baja lógica** — No se borra nada; las consultas previas no escriben.
6. **Contrato** — Aditivo: cuatro operaciones nuevas, un campo nullable en `HitoResponse`. El
   número de `BloqueResponse.turnosAfectados` puede bajar (deja de contar turnos de otros bloques):
   es la corrección de un defecto declarado, no un cambio de forma. Minor `0.70.0`.
7. **Ruta crítica** — No toca la economía; cimientos (E-1, B-5, calculador de 02.04) ya existen.
8. **El caso que rompe el diseño** — Una edición que corre el bloque a otro día y en la misma
   operación existe una apertura puntual que cubre el turno: el simulador aplica las excepciones
   antes y después, así que el turno cubierto por la apertura no cuenta. El caso que sí queda
   abierto: un turno reservado **entre** la consulta previa y la mutación. Lo cubre la respuesta
   del `PUT`/`DELETE`, que recalcula con el mismo simulador dentro de la transacción y bajo el lock
   de la sede. Otro: la simulación de una edición **no** muta la entidad leída —en la edición real
   se calcula antes de `updateDatos`, y siempre sobre una copia—, porque en la misma sesión JPA
   el bloque leído por `findVigentesEn` es la misma instancia que se edita.

## 4. Tests

- `SimuladorDeImpactoTest`: baja que cuenta sólo su bloque (no el de otro día ni el sobreturno),
  recorte de horario sin mutar la entidad, ampliación en cero, cierre de sede y apertura.
- `ImpactoPrevioDeDisponibilidadIT` (MySQL, HTTP): dos bloques del mismo profesional, baja → 1
  (E-1 decía 2), edición que recorta → 1, que amplía → 0, cierre de sede → 1; la base queda igual
  (bloque, excepciones, `audit_event`, turno) y el token de otro tenant no ve nada.
- Ajustes: `SondasDeImpactoTest`, `SondasDeImpactoIT`, `DisponibilidadServiceTest`,
  `CoberturasEnElResumenDePersonaTest`.
