# F4 — Dominio clínico

> Auditoría del 01/10/2026 (~59 %), **reverificada el 12/10/2026**: ver el registro de cierre al final.
> Etapas del plan: 04.01–04.05 (§13, líneas ~2108–2502).

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 04.01 Historia clínica | PARCIAL | 50 | **No hay API para abrir/obtener la HC**; relación asistencial nula |
| 04.02 Timeline y adjuntos | PARCIAL | 65 | Frontend completo |
| 04.03 Caso clínico | PARCIAL | 65 | Frontend; gate RF-M10-007 |
| 04.04 Plan de tratamiento | PARCIAL / DESVIADA | 60 | Ítems por oferta y no por práctica; frontend |
| 04.05 Consumo de autorizaciones | PARCIAL | 55 | Reserva; reversión automática; frontend |

**Las cinco etapas no tienen frontend ni E2E.** `appKine-web/src/app/features/clinical/` solo
tiene la pantalla de atención (06.x). Son ~36 operaciones sin consumidor. El cliente TypeScript ya
está en 0.44.0, así que no hay bloqueo técnico.

**Lo bueno:** el backend de 04.02–04.05 está verificado contra MySQL (los ITs que antes estaban
escritos sin correr ahora corren desde el 29/09).

## El hallazgo central: la HC no tiene puerta

**Verificado.** No existe `HistoriaClinicaController`. El contrato tiene rutas
`/api/v1/historias-clinicas/{historiaClinicaId}/{adjuntos,casos,entradas,timeline}`, pero ninguna
para **crear u obtener** la HC por persona, ni para su resumen o antecedentes. Consecuencias:

- Un paciente sin sesión previa no tiene HC alcanzable por REST.
- No se puede crear un caso para alguien que nunca fue atendido.
- `AntecedenteClinicoService` y `HistoriaClinicaService.actualizarResumen` son código muerto para la API.

Se declaró pendiente en el registro de 04.01, se re-declaró en 06.01, y **después desapareció de
los registros**. Es deuda documentada que quedó huérfana.

## Faltantes

### Backend / API
- [x] **`HistoriaClinicaController`**: obtener-o-abrir idempotente por persona, resumen,
  antecedentes. Con contrato y entrada desde el Paciente 360.
- [x] **`RelacionAsistencialProbe` real** sobre turnos y sesiones. Hoy solo existe
  `RelacionAsistencialSinAgenda`, que devuelve `false` siempre → **toda** lectura clínica exige
  justificación, para siempre.
- [x] ~~Lectura limitada para `ADMINISTRATIVO`.~~ **Hecho el 12/10/2026** (rama
  `akine-cierre-F4-F6-backend`, contrato 0.97.0, sin migración):
  `GET /api/v1/historias-clinicas/por-persona/{personaId}/datos-administrativos`
  (`verDatosAdministrativosDeHistoriaClinica`) con `paciente:read`: existencia, fecha de apertura,
  casos (número, estado, oferta, fechas) y equipo vigente; nunca resumen, antecedentes,
  diagnóstico, objetivo ni motivo de cierre (matriz §4). `MatrizDePermisosIT`: 200 para el
  personal, 403 para `PACIENTE`, y el `ADMINISTRATIVO` no recibe el resumen cargado.
- [x] 04.05: los tipos `RESERVA` / `LIBERACION_DE_RESERVA` existen en el CHECK de `V50` y ningún
  camino los emite. *No aplica (C-4): ningún RF de M17, M12 ni M13 dice cuándo se reserva una
  unidad, y el plan de 04.05 dice "reservar si la regla lo exige". Quedan en el modelo, sin emisor.
  Ver `docs/diseno/AKINE-C-4-consumo.md` §6.*
- [x] 04.05: ~~reversión automática del consumo ante sesión anulada (hoy manual)~~. *Resuelto por
  DP-13 (C-4): la reversión sigue manual y anular la obligación de la sesión deja una alerta
  "consumo a revisar" (`autorizacion_alerta`, `V76`, `GET /autorizaciones/{id}/alertas`) sin tocar
  el saldo; revertir el consumo la resuelve.*
- [x] 04.05: validar cobertura y caso al consumir. *C-4: la cobertura de la autorización tiene que
  estar vigente el día de la atención, y no se consume una autorización atada al plan de otro caso
  (RF-M17-007). Además DP-12: una unidad por autorización involucrada.*
- [x] 04.04: el plan puede activarse sin ítems.
- [x] 04.03: unique de caso activo (RN-M10-002); `EstadoCaso` solo tiene `ACTIVO`/`CERRADO`. *No aplica: RN-M10-002 admite varios casos activos; ver V47 y docs/diseno/AKINE-04.03-challenge.md. Tampoco se agrega otro estado: M10 solo pide `ACTIVO` y `CERRADO` (RF-M10-002 "activos primero y luego cerrados", RF-M10-005 "estado CERRADO"; no define ningún otro).*
- [ ] Gate RF-M10-007 (exigir caso al reservar o atender): etapa propia con ventana de migración.
  Se coordina con [F6](F6-atencion-clinica.md). **Pendiente del usuario (C-9).**
- [x] ~~04.03: `CasoClinicoService#cambiarEquipo` confía en `OPTIMISTIC_FORCE_INCREMENT`~~ **Medido y
  corregido el 12/10/2026.** El IT nuevo mostró que la versión de la fila **sí** avanzaba, pero la
  respuesta anunciaba la leída y dos cambios concurrentes terminaban en **deadlock** sobre
  `caso_profesional`. Pasó al `UPDATE … WHERE version = :esperada` de `Sesion`: avanza una vez,
  la respuesta devuelve `leída + 1` y el concurrente recibe conflicto de versión
  (`CasoClinicoConcurrenteIT` (e), `CasoClinicoCicloIT`). Ver [deuda-force-increment.md](deuda-force-increment.md).

### Frontend — frente clínico de pantalla, **otro chat** (web #4 abierto: D-a, D-b, D-c; D-f en web #58)
- [ ] Entrada a la HC desde el Paciente 360 (abrir / ver resumen / antecedentes).
- [ ] Timeline filtrable con paginación por cursor, entradas clínicas y adjuntos con preview seguro.
- [ ] Casos: listado activos/históricos, alta compacta, detalle, cierre, reapertura, equipo.
- [ ] Plan de tratamiento: editor estructurado, versiones, avance, advertencias de vigencia.
- [ ] Selector explicable de autorización, saldo y alertas.

### Tests
- [ ] E2E por cada pantalla de arriba.
- [ ] Prueba de acceso clínico permitido / denegado.
- [ ] QA manual del §6: ninguna etapa de F4 lo corrió.

## Desvíos

| Desvío | Documentado |
|---|---|
| HC "reducida" (DP-10) | Sí, registro de 04.01 |
| API de HC pendiente "para 06.01" y después olvidada | **Documentada y huérfana** |
| **Ítems del plan por oferta/servicio, no por práctica** (contra RF-M11-002) | Decisión pendiente 6 del `CLAUDE.md`, **sin ADR** |
| Editar un borrador de plan borra físicamente sus ítems | Sí, registro y `V49` |
| Storage de adjuntos duplicado (`person` y `clinical`), con salida "al tercer consumidor" | Sí |
| Sin unique de caso activo (**no aplica**: RN-M10-002 admite varios casos activos; ver V47 y `AKINE-04.03-challenge.md`), sin backfill de sesiones previas | Sí |
| Cierre sin saldo de autorización no falla | Sí, registro de 04.05 |
| Arista `person → encounter` invertida para evitar ciclo | Sí |
| No hay endpoint explícito de "consumir": solo nace del cierre | Sí |

## Decisiones del usuario que bloquean

- Puente Oferta↔Práctica (compartida con [F2](F2-operacion-del-consultorio.md)).
- ~~Reversión del consumo ante sesión anulada: automática o manual.~~ Resuelta: DP-13 (manual con
  alerta), implementada en C-4.

## Para cerrar la fase

- [ ] `HistoriaClinicaController` + `RelacionAsistencialProbe` real, con ITs.
- [ ] Las cinco pantallas con su E2E.
- [ ] ADR del plan por práctica (o decisión explícita de quedarse en oferta).
- [ ] Registros de 04.02–04.05 promovidos de "avance" a "cierre" con los diez puntos.

## Registro de cierre — 12/10/2026

Verificado contra el código de `main` (`6d5e97b` + esta rama) y los PR mergeados de appKine-api.

| Etapa | Backend | Pantalla (otro chat) |
|---|---|---|
| 04.01 Historia clínica | **95 %** — C-2 (#5, 04/10), C-3 `RelacionAsistencialProbe` real (#8, 04/10), `asegurar` idempotente con `INSERT IGNORE` (#97, 13/10), lectura limitada del `ADMINISTRATIVO` (esta rama). Falta sólo lo que espera decisión | D-e: sin PR |
| 04.02 Timeline y adjuntos | **100 %** — defectos 3 y 6 de la integración del 29/09 corregidos, `TimelineIT`, `AdjuntoClinicoIT`; cobertura de `clinical` > 0,90 (#94, 12/10) | D-a en web #4 (abierto) |
| 04.03 Caso clínico | **90 %** — C-5 (#7); `cambiarEquipo` medido y corregido (esta rama). Falta el gate RF-M10-007 (C-9) | D-b en web #4 |
| 04.04 Plan de tratamiento | **85 %** — el plan no se activa sin ítems (#7), activación concurrente (#20). Ítems por oferta y no por práctica: decisión | D-c en web #4 |
| 04.05 Consumo de autorizaciones | **100 %** de lo que tiene RF — C-4 (#47, 07/10), DP-12/DP-13, `consumosARevisar` por fila (#98) | D-f en web #58 (mergeado 11/10) |

**Backend, con evidencia (esta rama `akine-cierre-F4-F6-backend`, contrato 0.97.0, sin migración):**

- `cambiarEquipo`: IT en rojo primero (`CasoClinicoConcurrenteIT` (e)): la fila avanzaba, la
  respuesta traía la versión leída y dos cambios concurrentes daban `CannotAcquireLockException`
  (deadlock). Fix con `CasoClinicoRepositoryPort#avanzarVersion`. Verde: `CasoClinicoConcurrenteIT`
  10/10, `CasoClinicoCicloIT#el_cambio_de_equipo…`, `CasoClinicoServiceTest` 14/14.
- Datos administrativos de la HC (matriz §32/§4): `HistoriaClinicaAdministrativaService` +
  controller propio. `HistoriaClinicaAdministrativaServiceTest` 5/5, `MatrizDePermisosIT` 8/8,
  `OpenApiContractIT` 5/5 (310 operaciones), ArchUnit 5/5, `SchemaNameCollisionTest`.

**Pantalla:** frente clínico de pantalla — otro chat (web #4: D-a, D-b, D-c; D-e pendiente; D-f
mergeado en web #58).

**Pendiente del usuario:**

1. **C-9 / RF-M10-007, caso obligatorio al reservar o atender.** ¿Se exige ya, con ventana de
   migración de las sesiones sin caso? *Recomendado:* exigirlo sólo para ofertas con
   `requiere_caso_clinico = true` y dejar el resto opcional.
2. **Plan por práctica o por oferta (RF-M11-002).** Con DP-11 ya existe `oferta_practica`.
   *Recomendado:* quedarse en oferta y derivar la práctica por la principal de la oferta, con DP
   escrita; pasar a `plan_item.practica_id` reabre 04.04 y necesita migración.
3. **`caso:create` propio o absorbido en `hc:write`** (G-5). *Recomendado:* enmendar la matriz y
   absorberlo.
4. **Si los datos administrativos de la HC deben auditarse** (hoy no: no exponen contenido
   clínico). *Recomendado:* dejarlo así.
