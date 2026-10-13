# F2 — Operación del consultorio

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · ~74 %.
> **Revisada el 12/10/2026** contra `main` (backend `e47b662`, frontend `a954680`) y los PR mergeados:
> **~95 % técnico.** Lo que queda son tres decisiones del usuario y el QA manual. Ver el
> [registro de cierre](#registro-de-cierre--12102026) al final.
> Etapas del plan: 02.01–02.07 (§13, líneas ~1070–1631). Registros de cierre: líneas ~6243–7450.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 02.01 Consultorios y onboarding | CERRADA (técnica) | 100 | ~~`ConsultorioDeactivationProbe`~~ (E-1, api #38); ~~primer box + horario (CA-M03-002)~~ (A-8 api #60 / web #22; A-8b api #73 / web #39); E2E `flujo-sedes` (web #72) |
| 02.02 Espacios | CERRADA (técnica) | 100 | ~~`EspacioOccupancyProbe`~~ (E-1, api #38); E2E `flujo-espacios` (web #68) |
| 02.03 Colaboradores | CERRADA (técnica) | 100 | ~~La desvinculación no cuenta turnos~~ (E-1, api #38); ~~la UI no muestra el impacto~~ (A-10, web #17, 07/10); E2E `flujo-colaboradores` (web #64) y `flujo-desvinculacion` (web #72) |
| 02.04 Disponibilidad | CERRADA (técnica) | 100 | ~~Sonda nula~~ (E-1, api #38); ~~preview de turnos afectados~~ (A-10 web #17, A-11 api #64 / web #29); E2E `flujo-horarios` (web #68) |
| 02.05 Catálogos | PARCIAL | 90 | ~~Resolver solicitudes sin pantalla~~ (A-7, api #53 / web #13, 07/10); E2E `flujo-catalogo` (web #68). **Pendiente del usuario:** uso de `nomenclador_item` y aprobar `catalogo:*` |
| 02.06 Servicio y oferta | CERRADA (técnica) | 95 | ~~Puente Oferta↔Práctica~~ (A-9, api #46 / web #32); E2E `flujo-servicios` y `flujo-practicas-y-aranceles`. `esquema_cobro` es de F9 (08.06), no de F2 |
| 02.07 Habilitaciones | CERRADA (técnica) | 95 | ~~Escenario diferido 20~~ (`HabilitacionesVersionForzadaIT`, api #23/#36); E2E de habilitaciones dentro de `flujo-servicios` y `flujo-espacios`. Capacidad con clases = F9; disciplina = decisión del usuario |

Todas tienen backend **y** pantallas. Lo que falta es lo que cada etapa dejó "para cuando exista F5"
y F5 nunca conectó.

## El hallazgo central: cuatro sondas desconectadas

Cada etapa de F2 dejó una sonda (`*Probe`) para preguntar "¿esto afecta turnos futuros?", con
destino F5. F5 cerró sin implementarlas. **Hoy se puede dar de baja una sede, un espacio o un
profesional con turnos futuros sin ningún aviso.** Es un desvío silencioso.

| Sonda | Estado |
|---|---|
| `ConsultorioDeactivationProbe` | ~~sin implementación~~ → `scheduling.infrastructure.SedeConTurnosPendientes` (E-1): 409 `consultorio-has-active-references` |
| `EspacioOccupancyProbe` | ~~sin implementación~~ → `scheduling.infrastructure.EspacioOcupadoPorTurnos` (E-1): pico simultáneo; 409 en baja y en reducción de capacidad |
| `DisponibilidadImpactProbeSinAgenda` | ~~implementación nula~~ → borrada y reemplazada por `scheduling.infrastructure.DisponibilidadImpactoSobreTurnos` (E-1); informa, cota superior |
| `ResourceDesvinculacionProbe` | cuenta bloques; ~~**no turnos**~~ → ahora la precede `scheduling.infrastructure.ProfesionalConTurnosPendientes` (E-1, `@Order(0)`); informa |

Los `ProblemType` reservados para estos casos existen y nunca se emiten. Las implementaciones van
en `scheduling` (turnos) y en `activity` (clases, cuando F9 se retome).

## Faltantes

### Backend
- [x] ~~Implementar las cuatro sondas contra `scheduling` (y dejar el punto de extensión para `activity`).~~ E-1. Sede, espacio y colaborador se inyectan como lista: `activity` suma la suya con una clase. La de disponibilidad es bean único por diseño (`resource.spi.DisponibilidadImpactProbe`).
- [x] ~~**Puente Oferta↔Práctica (RF-M06-008).** Ni `servicio` ni `oferta` tienen `practica_id`.
  Lo necesitan el plan de tratamiento (F4), el consumo de autorizaciones (F6) y el arancel (F7).
  **Requiere decisión del usuario** y migración.~~ → A-9 (DP-11): tabla `oferta_practica` (`V75`)
  con práctica principal, `GET`/`PUT /consultorios/{id}/ofertas/{ofertaId}/practicas` (contrato
  0.58.0) y `offering.spi.PracticasDeOfertaDirectory` para C-4 y F-4. Diseño en
  [AKINE-A-9-oferta-practica.md](../diseno/AKINE-A-9-oferta-practica.md). Falta la pantalla.
- [ ] **Pendiente del usuario (12/10).** Decidir el uso de `nomenclador_item`: hoy `convenio_arancel` (`V43`) referencia la práctica,
  no una versión de nomenclador, así que la regla "convenios referencian versión/vigencia" no se cumple.
- [x] ~~`esquema_cobro` se guarda y nadie lo interpreta~~ → **fuera de F2** (12/10): es de F9 / 08.06; `activity.AsistenciaService` documenta que no es evaluable hasta que exista la política.
- [ ] La capacidad efectiva de habilitaciones no considera clases (**F9**, cuando existan clases que ocupen espacio); **pendiente del usuario:** si se valida la disciplina del profesional al habilitarlo (RF-M05-007 paso 5 las declara "informativas").
- [x] ~~`ofertaVersion` en `HabilitacionesResponse`.~~ → lectura y los dos reemplazos la devuelven (contrato 0.50.0). Tras un reemplazo es `leída + 1`, la que queda después del commit, así que dos reemplazos se encadenan sin releer; lo prueba `HabilitacionesVersionForzadaIT` contra MySQL.
- [x] ~~02.07: el reemplazo de habilitaciones confía en `OPTIMISTIC_FORCE_INCREMENT` sobre la oferta,
  y contra MySQL eso no avanza la versión.~~ → **No aplica** (06/10/2026): medido contra MySQL, la
  versión de la oferta avanza una vez por reemplazo (`HabilitacionesVersionForzadaIT`, PR #23 y
  #36). Ver la corrección en [deuda-force-increment.md](deuda-force-increment.md).
- [x] ~~Endpoint que le diga al frontend si quien mira tiene rol de plataforma (prerrequisito de RF-M06-005).~~ → A-7: `GET /api/v1/me/platform-role` (contrato 0.64.0), sin contexto de tenant y revalidado contra `platform_role` en cada request. Además aprobar una solicitud **publica** el concepto global en la misma transacción (`conceptoId`); diseño en [AKINE-A-7-plataforma.md](../diseno/AKINE-A-7-plataforma.md).
- [x] ~~Primer box + horario general en el alta de sede (CA-M03-002).~~ → A-8 (`V83`, contrato
  0.69.0): el alta acepta `primerBox` y `horarioGeneral` y los crea en la misma transacción
  (`AltaDeSedeEnUnActoIT`: alta completa y rollback con horario solapado). El horario general es
  parte del calendario de la sede (`resource`, tabla `consultorio_horario`), se edita por el `PUT`
  del calendario y es informativo: la agenda no lo lee (RN-M03-004). Falta la pantalla.

### Permisos
- [ ] **Pendiente del usuario (12/10).** `catalogo:read` / `catalogo:manage` siguen **propuestos** (`docs/seguridad/matriz-permisos-minima.md` §11.1); rige una autorización interina
  (`resource/domain/PermissionCodes.java`).

### Frontend
- [x] ~~Mostrar el impacto de desvinculación~~ → A-10 (web #17, 07/10): el panel de revocar consulta la sonda y avisa; E2E `flujo-desvinculacion` (web #72).
- [x] ~~Preview de turnos afectados al editar disponibilidad.~~ → A-10 (web #17) y cuenta exacta con A-11 (api #64 / web #29, 08/10); E2E `flujo-horarios` (web #68).
- [x] ~~Pantalla para resolver solicitudes de catálogo / consola de plataforma.~~ → A-7 web (#13, 07/10): `/plataforma/solicitudes`; E2E `flujo-catalogo` (web #68).

### Tests
- [x] ~~Unitarios de `ConsultorioService`, `EspacioService`, `CatalogoService`, `CatalogoSolicitudService` (hoy solo ITs).~~ → 70 unitarios (Mockito, sin Spring): `EspacioServiceTest` 24, `CatalogoServiceTest` 20, `CatalogoSolicitudServiceTest` 10, `ConsultorioServiceTest` 16. Destaparon que una edición de sede amparada en soporte dejaba **dos** `SUPPORT_ACCESS_USED`; corregido en `ConsultorioService.exigirSobreLaSede`.
- [x] ~~IT del escenario diferido 20 (que la `@Version` forzada de 02.07 avance contra MySQL real).~~ → `HabilitacionesVersionForzadaIT`: avanza una sola vez y el segundo guardado choca.
- [x] ~~ITs de baja y reducción de capacidad **con turnos reales**.~~ → `SondasDeImpactoIT` (E-1, PR #38), por HTTP contra MySQL.
- [x] ~~E2E de alta → baja por cada pantalla del tramo.~~ → espacios, catálogo, horarios, servicios y padrón (web #68, 12/10), prácticas de oferta y colaboradores (web #64), sedes y desvinculación (web #72, 12/10).

## Desvíos

| Desvío | Documentado |
|---|---|
| Sondas sin conectar después de F5 | ~~No — silencioso~~ Cerrado por E-1 |
| El contrato promete `concurrent-modification`, el código devuelve `conflict` (`resource`, `espacio`, `catalogo`) | ~~Declarado, sin resolver~~ Cerrado por DP-21 (api #74, web #40, 08/10) |
| Registro de 02.02 es reconstrucción, no acta; no hay diseño de etapa | Sí |
| Migraciones `V16`–`V18` aplicadas juntas; baja de sede solo `ORG_ADMIN`; horario general como columna | Sí, registro y ADR-0007 |
| Se quitó `GET /feriados`; cruces de medianoche como dos filas | Sí, registro 02.04 R10–R16 |

## Para cerrar la fase

- [x] ~~Las cuatro sondas implementadas, con ITs que den de baja con turnos futuros y verifiquen el 409.~~ E-1, `SondasDeImpactoIT`: 409 en sede y espacio (baja y capacidad); desvinculación y disponibilidad informan, no bloquean (RN-M05-004).
- [x] ~~Decisión + migración del puente Oferta↔Práctica.~~ DP-11 + `V75` (A-9).
- [x] ~~Frontend mostrando impacto de desvinculación y de cambio de disponibilidad.~~ web #17 y #29.
- [x] ~~Unitarios de los cuatro servicios~~ (api #26) y ~~un E2E por pantalla~~ (web #64, #68, #72).
- [ ] QA manual del §6 del `CLAUDE.md` para el tramo — **del usuario**, con `docs/qa/02-organizacion-sedes-colaboradores.md`, `docs/qa/03-catalogo-servicios-ofertas.md` y la parte de disponibilidad de `docs/qa/05-agenda-turnos-recepcion.md`.

## Registro de cierre — 12/10/2026

> Revisión contra `main` de los dos repos (backend `e47b662`, frontend `a954680`) y la lista de PR
> mergeados de GitHub. **F2 queda cerrada en lo técnico**: todo lo que era código entró. Lo que no
> cierra es lo que no le toca a un agente: tres decisiones de producto y el QA manual.

### Lo que quedó, con su evidencia

| Ítem de la ficha | Cierre | Evidencia |
|---|---|---|
| Cuatro sondas de impacto contra turnos reales | E-1 | api #38 (`SondasDeImpactoIT`, por HTTP contra MySQL), descripciones del contrato api #41 |
| Puente Oferta↔Práctica (RF-M06-008) | A-9 / DP-11, `V75` | api #46; pantalla `/servicios/ofertas/:id/practicas` web #32; E2E `flujo-practicas-y-aranceles` web #64 |
| Primer box + horario general en el alta de sede (CA-M03-002) | A-8, `V83`; A-8b el horario limita la agenda (DP-19) | api #60, #73; web #22, #39; `AltaDeSedeEnUnActoIT`; E2E `flujo-sedes` web #72 |
| Rol de plataforma y resolver solicitudes de catálogo (RF-M06-005) | A-7 | api #53 (`GET /me/platform-role`); web #13 (`/plataforma/solicitudes`); E2E `flujo-catalogo` web #68 |
| Impacto de desvinculación en pantalla | A-10 | web #17; E2E `flujo-desvinculacion` web #72 (count 1, tipo `turnos`, la revocación pasa y el turno sigue `RESERVADO`) |
| Turnos afectados al editar disponibilidad | A-10 + A-11 (cuenta exacta) | web #17, api #64, web #29; E2E `flujo-horarios` web #68 |
| `ofertaVersion` en habilitaciones y escenario diferido 20 | — | api #36, #23 (`HabilitacionesVersionForzadaIT`) |
| Unitarios de `ConsultorioService`, `EspacioService`, `CatalogoService`, `CatalogoSolicitudService` | 70 unitarios | api #26; cobertura de `offering` sobre 80 % api #69 |
| E2E de alta → baja por pantalla | espacios, catálogo, horarios, servicios, padrón; prácticas y colaboradores; sedes y desvinculación | web #68 y #64 (mergeados 12/10); web #72 (abierto, este cierre) |
| `concurrent-modification` vs `conflict` | DP-21 | api #74, web #40 |
| `esquema_cobro` sin interpretar | Fuera de F2 | Es de F9 / 08.06 |
| Capacidad de habilitaciones sin clases | Fuera de F2 | Es de F9: no hay clases que ocupen espacio hasta que `activity` lo declare |

**Lo que hizo este cierre:** dos E2E contra el backend real, `e2e/flujo-sedes.spec.ts` y
`e2e/flujo-desvinculacion.spec.ts` (web #72), que eran las dos pantallas del tramo sin E2E. Sin
migración ni cambio de contrato. Tipados, lint y formato limpios; **no se corrieron localmente** —no
se levantan backend ni frontend en esta máquina—, los corre el job E2E del CI.

### Pendiente del usuario

1. **`nomenclador_item`.** `convenio_arancel` (`V43`) referencia la práctica y no una versión de
   nomenclador, así que la regla "los convenios referencian versión/vigencia" no se cumple y
   `nomenclador_item` no tiene consumidor. *Pregunta:* ¿el arancel se ata a un ítem de nomenclador
   vigente, o el nomenclador queda como catálogo informativo? **Recomendado:** declararlo
   informativo para el MVP (el arancel ya congela importe y vigencia en `ArancelCongelado`) y
   anotar la regla como desvío aceptado; atarlo después es migración en `contracting` y reabre B-7.
2. **`catalogo:read` / `catalogo:manage`.** Siguen propuestos en
   `docs/seguridad/matriz-permisos-minima.md` §11.1 y rige la autorización interina por pertenencia
   / `ORG_ADMIN`. *Pregunta:* ¿se aprueban como están en §11.1? **Recomendado:** aprobarlos; el
   costo es que un `PACIENTE` con membership deja de leer el catálogo (hoy pasa), que es lo correcto
   después de DP-22.
3. **Disciplina del profesional al habilitarlo.** RF-M05-007 paso 5 las declara "informativas" y
   hoy no se valida que la disciplina del profesional corresponda a la oferta. *Pregunta:* ¿se
   valida? **Recomendado:** no validar en el MVP —el requerimiento las llama informativas y RN-M05-005
   separa disciplina de habilitación—; dejarlo como aviso si después se pide.
4. **QA manual del §6** para el tramo, con la guía de `docs/qa/`:
   `02-organizacion-sedes-colaboradores.md` (ORG-01 a ORG-07, incluido ORG-03b alta en un acto),
   `03-catalogo-servicios-ofertas.md` y la parte de disponibilidad de
   `05-agenda-turnos-recepcion.md`. Es lo único que mira la persistencia contra la base después de
   cada escritura.
