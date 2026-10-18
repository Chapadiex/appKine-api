# F9 — Segunda entrega (clases, participación, pases y abonos)

> **Reverificada el 16/10/2026** · backend `main` `eef0c87` (contrato **0.118.0**, 333 operaciones,
> última migración **`V103`**) · frontend `main` `c5d803d` (cliente **0.118.0**, web #105) · **~82 %**.
> Auditoría original del 01/10/2026 sobre backend `37ff201` / frontend `4c6ae8b` (~23 %).
> **17/10/2026:** los escenarios diferidos 39–42, 46–48 y 54–59 tienen IT y corrieron contra MySQL
> (`akine-f9-escenarios-diferidos`); dos defectos de 08.03 corregidos. La fase pasa a **~86 %**.
> Etapas del plan: 08.01–08.09 (§13). Leer DP-07 y DP-10 (§7).

## La fase dejó de estar congelada, y la DP que lo registre no existe

**DP-07** pide que la segunda entrega empiece después del gate del MVP. El 14/10 el usuario abrió
F9 por instrucción directa y desde entonces entraron **diez PR de F9 al backend** (#125, #127–#129,
#135, #138–#143) y **once al frontend** (web #79, #81, #91, #92, #98, #101–#105). Lo dejan escrito
`docs/diseno/AKINE-08.06-pases.md` §challenge 7 y `AKINE-08.07-08.08-ledger-y-abonos.md`
("se hizo porque el usuario abrió F9"), pero **no hay DP en el plan que derogue el orden de DP-07**
ni tachado de DU-12. El gate del MVP sigue sin pasar ([F8](F8-cierre-del-mvp.md)).

**Pendiente del usuario:** escribir la DP que registre la apertura (o confirmar que DU-12 se cierra
así). No frena código: es trazabilidad para la tesis.

## Estado por etapa

| Etapa | Estado | % | Qué entró | Lo que falta |
|---|---|---|---|---|
| 08.01 Clases y agenda unificada | HECHA | 95 | Backend desde 20/09; nombres de oferta, profesional y box y **box elegido** al programar y reprogramar (#128, 0.102.0 → 0.103.0 en `main`). Pantalla web #81 y #91; E2E `flujo-clases` ; escenarios 39–42 con IT el 17/10 (`ClaseContraTurnoConcurrenteIT`, `MigracionesF9IT`, `ActividadAislamientoIT`, `ActividadRestIT`) | — |
| 08.02 Inscripciones, cupos, espera | HECHA | 95 | `UltimaVacanteConcurrenteIT`, `PromocionDesdeEsperaConcurrenteIT`, `CupoOcupadoInvarianteIT` verdes desde el 06/10; `aceptaListaEspera` opcional (#125). Pantalla web #81 ; escenarios 46–48 con IT el 17/10 (`CapacidadContraInscripcionIT`, `MigracionesF9IT`, `ActividadRestIT`) | — |
| 08.03 Asistencia | PARCIAL | 80 | Consumo de crédito por asistencia (#129), la asistencia toma la clase `FOR SHARE` contra la cancelación (#135). Pantalla web #79; E2E `flujo-asistencia`. Escenarios 54–59 con IT el 17/10 (`AsistenciaConcurrenteIT`, `MigracionesF9IT`, `ActividadRestIT`), que destaparon el ingreso sin inscripción roto y el cierre concurrente en 500 | ~~El devengo `POR_CLASE` espera U-39~~ **Implementado (DP-24, `V107`, 0.126.0)**: la asistencia presente devenga, la corrección a ausente anula (con cobros no), la cancelación anula; `DevengoPorClaseIT` |
| 08.04 Derivación al circuito clínico | HECHA en backend | 80 | API de cuatro operaciones (#125, 0.101.0); carrera de la derivación concurrente confirmada y corregida (#132, `DerivacionConcurrenteIT`). `DerivacionParticipanteIT`, `LecturaAuditadaIT` | **Pantalla para derivar**: el frontend llama `listarDerivacionesDeParticipante` y `revertirDerivacionDeParticipante`, pero no `derivarParticipante` ni `verElegibilidadClinicaDeParticipante` (medido en `appKine-web` `main`). Sin ella, la atención grupal no tiene cómo empezar desde la pantalla |
| 08.05 Atención clínica grupal | HECHA | 90 | Una `Sesion` por participante y Caso desde su derivación, lote y panel (#141, 0.115.0, `V102`); nombres, título de clase, número de caso y `derivacionIds` (#143, 0.118.0). Pantalla web #104/#105; E2E `flujo-atencion-grupal` | **U-42** (consecuencia económica). Reemplazo de profesional sigue en U-24 |
| 08.06 Productos, packs, venta | HECHA | 90 | Catálogo de productos, venta idempotente con obligación `VENTA_PASE` (#129, `V96`). Pantalla web #92; E2E `flujo-pases` | ~~`POR_CLASE` (U-39)~~ hecho, ver 08.03 |
| 08.07 Ledger de créditos | HECHA | 90 | Consumo (#129), devolución por cancelación, ajuste con `pase:adjust`, anulación y vencimiento programado (#135, `V101`); `causa`, `moneda`, `creditosUsados`, `anulable` (#138); ledger paginado (#140) y ajuste `sinMovimientos` (#142). Pantallas web #98, #101, #103 | U-40 (c), (d), (e) |
| 08.08 Abonos | HECHA | 90 | Venta por período y cobertura de la asistencia (#135); **renovación automática opt-in, avisos de vencimiento y saldo bajo por el outbox** (#140, `V103`, 0.116.0); `renovacionAutorizadaPor`, `renovadoPorPaseId` (#142). Pantalla web #103 | U-40 (a), (b) y las tres de #140: **U-43, U-44, U-45** |
| 08.09 Integración económica | PARCIAL | 60 | Venta → deuda → cobro → caja → anulación probado contra MySQL; la deuda de una venta ya no se anula sola (409 `obligacion-de-venta`, #139, 0.114.0); RF-M21-010 verificado; RF-M21-009 lo cubre 08.05 | **RF-M22-006/007** (liquidación por clase) la liquidación espera su implementación (**U-41** decidida el 18/10/2026, DP-38; diseño en 08.09 §5). Reportes de clases y pases: van a 09.01 (F10) |

El porcentaje de la fase es el promedio simple de las nueve etapas.

## Lo que hay en `main`

- Migraciones de F9: `V58` (clase), `V60` (inscripción), `V61` (asistencia), `V63` (derivación
  clínica), `V64` (producto, pase, movimiento), **`V96`** (asistencia en el movimiento de pase),
  **`V101`** (anulación y vencimiento de pases), **`V102`** (Sesión de origen grupal), **`V103`**
  (renovación de abonos y avisos).
- Módulo `activity`: clases, inscripciones, asistencia, derivación (lado grupal), atención grupal,
  productos, pases y abonos. Aristas: `activity → billing.spi` (devengo y anulación de ventas),
  `activity → clinical.spi` (derivación), `activity → encounter.spi` (atención grupal). ArchUnit 5/5.
- **Tres jobs programados** en `activity.infrastructure`: `VencimientoDePasesWorker`,
  `RenovacionDeAbonosWorker`, `AvisosDePasesWorker`. Cómo se operan:
  [`../operaciones/runbooks.md`](../operaciones/runbooks.md) R7.
- ITs de F9 contra MySQL (13 en `src/test/.../activity`): `AbonoRenovacionYAvisosIT`,
  `AtencionGrupalIT`, `ClaseEspacioElegidoIT`, `ConsumoDePaseConcurrenteIT`,
  `CupoOcupadoInvarianteIT`, `DerivacionConcurrenteIT`, `DerivacionParticipanteIT`,
  `DevolucionYAjusteConcurrenteIT`, `PaseAbonoAjusteYVencimientoIT`, `PaseVentaYConsumoIT`,
  `PromocionDesdeEsperaConcurrenteIT`, `UltimaVacanteConcurrenteIT`,
  `VentaDePaseEnElCircuitoEconomicoIT`. El CI de `eef0c87` corrió **4.009 unitarias + 880 de
  integración, 0 fallos**. Después entraron los de 09.01–09.03 (#145–#147) y, el 17/10, los de los
  escenarios diferidos: `ClaseContraTurnoConcurrenteIT`, `CapacidadContraInscripcionIT`,
  `AsistenciaConcurrenteIT`, `MigracionesF9IT` y, en `diferidos`, `ActividadRestIT`.

## Defectos que F9 destapó y corrigió (14–17/10)

| PR o fecha | Defecto |
|---|---|
| #129 | Declarar cualquier `esquemaCobro` en una oferta violaba `ck_oferta_politica_devengo` (V64) y respondía 500 |
| #132 | Cuatro derivaciones simultáneas del mismo participante: tres terminaban en 500 (`null identifier` en una sesión de Hibernate rota) |
| #135 | Una asistencia que consumía crédito mientras otro cancelaba la clase perdía el crédito sin error |
| #139 | `DELETE /obligaciones/{id}` anulaba la deuda de una venta y dejaba el pase `ACTIVO` con sus créditos |
| 17/10 (escenario 56) | **El ingreso sin inscripción no funcionó nunca desde 08.03**: pedía el lugar con el `tomarCupo` de 08.02, que exige `PROGRAMADA`, sobre una clase que tiene que estar `EN_CURSO`. Respondía `clase-completa` con lugares libres. Ahora `tomarCupoEnCurso` |
| 17/10 (escenario 55) | Dos cierres simultáneos de la misma clase: los perdedores chocaban contra `uk_asistencia_clase_persona` y respondían 500. Ahora `cerrar` toma la clase `FOR UPDATE` en `READ_COMMITTED` y el segundo es idempotente de verdad |

## Ramas estacionadas

| Rama | Contenido | Qué hacer |
|---|---|---|
| `akine-08.06-productos` (`3e7d8d1`, pusheada) | WIP viejo de `EsquemaCobro`/`MomentoDevengo`, **611 commits detrás de `main`** | **Superada por #129.** Se puede borrar del remoto; no integrar |

## Faltantes, en orden de retome

1. [ ] **Pantalla de derivación** (08.04): `derivarParticipante` y `verElegibilidadClinicaDeParticipante`
   en el frontend. Es lo único que impide recorrer 08.04 → 08.05 entero desde la pantalla.
2. [x] ~~**ITs diferidos de 08.01–08.03** (escenarios 39–42, 46–48, 54–59)~~ — corridos el 17/10 contra MySQL, con el aislamiento
   de 09.02 (`ActividadAislamientoIT`) reutilizado para 41, 48 y 59. Ver `docs/tests-diferidos.md`.
3. [x] **U-39** → devengo `POR_CLASE` (08.03). **Decidida el 17/10/2026 (DP-24)**: con la asistencia, sin no-show por defecto; en `POR_PACK`, sin pase solo se informa. **Implementada** en `akine-u39-devengo-por-clase` (`V107`, contrato 0.126.0, diseño `AKINE-08.06-pases.md` §4.1).
4. [ ] ~~**U-41** → liquidación de honorarios por clase (08.09).~~ **Decidida el 18/10/2026 (DP-38)**: por participante presente, borrador por período confirmado como egreso; se implementa después del RC.
5. [x] U-40, U-42, U-44 **confirmadas el 17/10/2026** (DP-25, DP-26, DP-27); U-43 y U-45 **decididas el 18/10/2026 (DP-40)**; se implementan después del RC.
6. [ ] Autoservicio del paciente: depende del alcance `OWN`, post-MVP por DP-22.

## Desvíos

| Desvío | Documentado |
|---|---|
| F9 abierta sin el gate del MVP | Por instrucción del usuario el 14/10; diseño de 08.06 y 08.07; **sin DP formal** |
| La vacante se impone al promover desde lista de espera (sin ventana de aceptación) | Sí |
| ~~`POR_CLASE` responde 400 hasta implementar DP-24~~ cerrado por `akine-u39-devengo-por-clase` | Sí, #129 y DP-24 |
| La atención grupal sin cobertura no devenga particular | Sí, U-42 (confirmado, DP-26) |
| Avisos de pases configurables por despliegue, no por centro | Sí, U-44 (confirmado, DP-27) |
| La derivación cuelga de `/inscripciones/{id}` y no de `/participantes/{id}` como dice el diseño §8 | Sí, #125 |
| `clase:*` propios en vez de `turno:*` | Sí, decisión pendiente 9 |

## Para cerrar la fase

- [ ] DP formal de la apertura de F9 (o DU-12 cerrada).
- [ ] Pantalla de derivación.
- [x] Escenarios diferidos 39–59 con IT o descartados con motivo (17/10).
- [x] U-39 implementada (decidida, DP-24; `V107`). U-41 decidida (DP-38, 18/10/2026), implementación posterior al RC.
