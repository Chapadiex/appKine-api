# F2 — Operación del consultorio

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~74 %**
> Etapas del plan: 02.01–02.07 (§13, líneas ~1070–1631). Registros de cierre: líneas ~6243–7450.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 02.01 Consultorios y onboarding | PARCIAL | 75 | ~~`ConsultorioDeactivationProbe` sin implementación~~ (E-1); primer box + horario (CA-M03-002) |
| 02.02 Espacios | PARCIAL | 70 | ~~`EspacioOccupancyProbe` sin implementación~~ (E-1) |
| 02.03 Colaboradores | PARCIAL | 70 | ~~La desvinculación no cuenta turnos~~ (E-1); la UI no muestra el impacto |
| 02.04 Disponibilidad | PARCIAL | 80 | ~~`DisponibilidadImpactProbeSinAgenda` sigue devolviendo `ninguno()`~~ (E-1) |
| 02.05 Catálogos | PARCIAL | 75 | `nomenclador_item` sin consumidor; resolver solicitudes sin pantalla |
| 02.06 Servicio y oferta | PARCIAL | 80 | **No existe el puente Oferta↔Práctica**; `esquema_cobro` sin interpretar |
| 02.07 Habilitaciones | PARCIAL | 70 | Escenario diferido 20 sin IT |

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
- [ ] **Puente Oferta↔Práctica (RF-M06-008).** Ni `servicio` ni `oferta` tienen `practica_id`.
  Lo necesitan el plan de tratamiento (F4), el consumo de autorizaciones (F6) y el arancel (F7).
  **Requiere decisión del usuario** y migración.
- [ ] Decidir el uso de `nomenclador_item`: hoy `convenio_arancel` (`V43`) referencia la práctica,
  no una versión de nomenclador, así que la regla "convenios referencian versión/vigencia" no se cumple.
- [ ] `esquema_cobro` se guarda y nadie lo interpreta (se retoma en F9 / 08.06).
- [ ] La capacidad efectiva de habilitaciones no considera clases; no se valida disciplina del profesional.
- [x] ~~`ofertaVersion` en `HabilitacionesResponse`.~~ → lectura y los dos reemplazos la devuelven (contrato 0.50.0). Tras un reemplazo es `leída + 1`, la que queda después del commit, así que dos reemplazos se encadenan sin releer; lo prueba `HabilitacionesVersionForzadaIT` contra MySQL.
- [ ] 02.07: el reemplazo de habilitaciones confía en `OPTIMISTIC_FORCE_INCREMENT` sobre la oferta,
  y contra MySQL eso no avanza la versión. Ver [deuda-force-increment.md](deuda-force-increment.md).
- [ ] Endpoint que le diga al frontend si quien mira tiene rol de plataforma (prerrequisito de RF-M06-005).
- [ ] Primer box + horario general en el alta de sede (CA-M03-002).

### Permisos
- [ ] `catalogo:read` / `catalogo:manage` siguen **propuestos**; rige una autorización interina
  (`resource/domain/PermissionCodes.java`).

### Frontend
- [ ] Mostrar el impacto de desvinculación (`getDesvinculacionImpacto` existe y nadie lo consume;
  `collaborators-page` revoca sin mostrar turnos afectados).
- [ ] Preview de turnos afectados al editar disponibilidad.
- [ ] Pantalla para resolver solicitudes de catálogo / consola de plataforma.

### Tests
- [x] ~~Unitarios de `ConsultorioService`, `EspacioService`, `CatalogoService`, `CatalogoSolicitudService` (hoy solo ITs).~~ → 70 unitarios (Mockito, sin Spring): `EspacioServiceTest` 24, `CatalogoServiceTest` 20, `CatalogoSolicitudServiceTest` 10, `ConsultorioServiceTest` 16. Destaparon que una edición de sede amparada en soporte dejaba **dos** `SUPPORT_ACCESS_USED`; corregido en `ConsultorioService.exigirSobreLaSede`.
- [x] ~~IT del escenario diferido 20 (que la `@Version` forzada de 02.07 avance contra MySQL real).~~ → `HabilitacionesVersionForzadaIT`: avanza una sola vez y el segundo guardado choca.
- [ ] ITs de baja y reducción de capacidad **con turnos reales**.
- [ ] E2E de alta → baja por cada pantalla del tramo. **No hay ni uno.**

## Desvíos

| Desvío | Documentado |
|---|---|
| Sondas sin conectar después de F5 | ~~No — silencioso~~ Cerrado por E-1 |
| El contrato promete `concurrent-modification`, el código devuelve `conflict` (`resource`, `espacio`, `catalogo`) | Declarado, sin resolver (decisión transversal) |
| Registro de 02.02 es reconstrucción, no acta; no hay diseño de etapa | Sí |
| Migraciones `V16`–`V18` aplicadas juntas; baja de sede solo `ORG_ADMIN`; horario general como columna | Sí, registro y ADR-0007 |
| Se quitó `GET /feriados`; cruces de medianoche como dos filas | Sí, registro 02.04 R10–R16 |

## Para cerrar la fase

- [x] ~~Las cuatro sondas implementadas, con ITs que den de baja con turnos futuros y verifiquen el 409.~~ E-1, `SondasDeImpactoIT`: 409 en sede y espacio (baja y capacidad); desvinculación y disponibilidad informan, no bloquean (RN-M05-004).
- [ ] Decisión + migración del puente Oferta↔Práctica.
- [ ] Frontend mostrando impacto de desvinculación y de cambio de disponibilidad.
- [ ] ~~Unitarios de los cuatro servicios~~ (hechos) y un E2E por pantalla (pendiente).
- [ ] QA manual del §6 del `CLAUDE.md` para el tramo.
