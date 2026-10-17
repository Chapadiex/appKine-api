# F9 — Segunda entrega (clases, participación, pases y abonos)

> **Reverificada el 16/10/2026** · backend `main` `eef0c87` (contrato **0.118.0**, 333 operaciones,
> última migración **`V103`**) · frontend `main` `c5d803d` (cliente **0.118.0**, web #105) · **~82 %**.
> Auditoría original del 01/10/2026 sobre backend `37ff201` / frontend `4c6ae8b` (~23 %).
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
| 08.01 Clases y agenda unificada | HECHA salvo ITs | 85 | Backend desde 20/09; nombres de oferta, profesional y box y **box elegido** al programar y reprogramar (#128, 0.102.0 → 0.103.0 en `main`). Pantalla web #81 y #91; E2E `flujo-clases` | Escenarios diferidos 39 (clase contra turno con dos transacciones reales), 40, 41 (tenant) y 42 (REST) de `docs/tests-diferidos.md`. `ClaseEspacioElegidoIT` cubre cuatro programaciones simultáneas sobre el mismo box, no la carrera contra un turno |
| 08.02 Inscripciones, cupos, espera | HECHA salvo ITs | 85 | `UltimaVacanteConcurrenteIT`, `PromocionDesdeEsperaConcurrenteIT`, `CupoOcupadoInvarianteIT` verdes desde el 06/10; `aceptaListaEspera` opcional (#125). Pantalla web #81 | Escenarios 46 (bajar la capacidad contra una inscripción), 47 y 48 |
| 08.03 Asistencia | PARCIAL | 70 | Consumo de crédito por asistencia (#129), la asistencia toma la clase `FOR SHARE` contra la cancelación (#135). Pantalla web #79; E2E `flujo-asistencia` | **El devengo `POR_CLASE` (RF-M18-008/RF-M19-009) espera U-39**: declarar `POR_CLASE` responde 400. Escenarios 54–59 sin IT propio, entre ellos el unique de asistencia, el cierre concurrente y el lote con transacciones independientes |
| 08.04 Derivación al circuito clínico | HECHA en backend | 80 | API de cuatro operaciones (#125, 0.101.0); carrera de la derivación concurrente confirmada y corregida (#132, `DerivacionConcurrenteIT`). `DerivacionParticipanteIT`, `LecturaAuditadaIT` | **Pantalla para derivar**: el frontend llama `listarDerivacionesDeParticipante` y `revertirDerivacionDeParticipante`, pero no `derivarParticipante` ni `verElegibilidadClinicaDeParticipante` (medido en `appKine-web` `main`). Sin ella, la atención grupal no tiene cómo empezar desde la pantalla |
| 08.05 Atención clínica grupal | HECHA | 90 | Una `Sesion` por participante y Caso desde su derivación, lote y panel (#141, 0.115.0, `V102`); nombres, título de clase, número de caso y `derivacionIds` (#143, 0.118.0). Pantalla web #104/#105; E2E `flujo-atencion-grupal` | **U-42** (consecuencia económica). Reemplazo de profesional sigue en U-24 |
| 08.06 Productos, packs, venta | HECHA | 90 | Catálogo de productos, venta idempotente con obligación `VENTA_PASE` (#129, `V96`). Pantalla web #92; E2E `flujo-pases` | `POR_CLASE` (U-39) |
| 08.07 Ledger de créditos | HECHA | 90 | Consumo (#129), devolución por cancelación, ajuste con `pase:adjust`, anulación y vencimiento programado (#135, `V101`); `causa`, `moneda`, `creditosUsados`, `anulable` (#138); ledger paginado (#140) y ajuste `sinMovimientos` (#142). Pantallas web #98, #101, #103 | U-40 (c), (d), (e) |
| 08.08 Abonos | HECHA | 90 | Venta por período y cobertura de la asistencia (#135); **renovación automática opt-in, avisos de vencimiento y saldo bajo por el outbox** (#140, `V103`, 0.116.0); `renovacionAutorizadaPor`, `renovadoPorPaseId` (#142). Pantalla web #103 | U-40 (a), (b) y las tres de #140: **U-43, U-44, U-45** |
| 08.09 Integración económica | PARCIAL | 60 | Venta → deuda → cobro → caja → anulación probado contra MySQL; la deuda de una venta ya no se anula sola (409 `obligacion-de-venta`, #139, 0.114.0); RF-M21-010 verificado; RF-M21-009 lo cubre 08.05 | **RF-M22-006/007** (liquidación por clase) espera **U-41**. Reportes de clases y pases: van a 09.01 (F10) |

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
  integración, 0 fallos**.

## Defectos que F9 destapó y corrigió (14–16/10)

| PR | Defecto |
|---|---|
| #129 | Declarar cualquier `esquemaCobro` en una oferta violaba `ck_oferta_politica_devengo` (V64) y respondía 500 |
| #132 | Cuatro derivaciones simultáneas del mismo participante: tres terminaban en 500 (`null identifier` en una sesión de Hibernate rota) |
| #135 | Una asistencia que consumía crédito mientras otro cancelaba la clase perdía el crédito sin error |
| #139 | `DELETE /obligaciones/{id}` anulaba la deuda de una venta y dejaba el pase `ACTIVO` con sus créditos |

## Ramas estacionadas

| Rama | Contenido | Qué hacer |
|---|---|---|
| `akine-08.06-productos` (`3e7d8d1`, pusheada) | WIP viejo de `EsquemaCobro`/`MomentoDevengo`, **611 commits detrás de `main`** | **Superada por #129.** Se puede borrar del remoto; no integrar |

## Faltantes, en orden de retome

1. [ ] **Pantalla de derivación** (08.04): `derivarParticipante` y `verElegibilidadClinicaDeParticipante`
   en el frontend. Es lo único que impide recorrer 08.04 → 08.05 entero desde la pantalla.
2. [ ] **ITs diferidos de 08.01–08.03** (escenarios 39–42, 46–48, 54–59). Los de concurrencia los
   está escribiendo 09.03 y los de aislamiento 09.02 (F10, en curso el 16/10).
3. [ ] **U-39** → devengo `POR_CLASE` (08.03). Sin decisión no hay código que escribir.
4. [ ] **U-41** → liquidación de honorarios por clase (08.09).
5. [ ] U-40, U-42, U-43, U-44, U-45: confirmar lo que hoy hace el código (no bloquean).
6. [ ] Autoservicio del paciente: depende del alcance `OWN`, post-MVP por DP-22.

## Desvíos

| Desvío | Documentado |
|---|---|
| F9 abierta sin el gate del MVP | Por instrucción del usuario el 14/10; diseño de 08.06 y 08.07; **sin DP formal** |
| La vacante se impone al promover desde lista de espera (sin ventana de aceptación) | Sí |
| `POR_CLASE` responde 400 hasta decidir U-39 | Sí, #129 y U-39 |
| La atención grupal sin cobertura no devenga particular | Sí, U-42 |
| Avisos de pases configurables por despliegue, no por centro | Sí, U-44 |
| La derivación cuelga de `/inscripciones/{id}` y no de `/participantes/{id}` como dice el diseño §8 | Sí, #125 |
| `clase:*` propios en vez de `turno:*` | Sí, decisión pendiente 9 |

## Para cerrar la fase

- [ ] DP formal de la apertura de F9 (o DU-12 cerrada).
- [ ] Pantalla de derivación.
- [ ] Escenarios diferidos 39–59 con IT o descartados con motivo.
- [ ] U-39 y U-41 decididas e implementadas, o declaradas fuera de la entrega.
