# F4 — Dominio clínico

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~59 %**
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
- [ ] **`HistoriaClinicaController`**: obtener-o-abrir idempotente por persona, resumen,
  antecedentes. Con contrato y entrada desde el Paciente 360.
- [x] **`RelacionAsistencialProbe` real** sobre turnos y sesiones. Hoy solo existe
  `RelacionAsistencialSinAgenda`, que devuelve `false` siempre → **toda** lectura clínica exige
  justificación, para siempre.
- [ ] Lectura limitada para `ADMINISTRATIVO`.
- [ ] 04.05: los tipos `RESERVA` / `LIBERACION_DE_RESERVA` existen en el CHECK de `V50` y ningún
  camino los emite.
- [ ] 04.05: reversión automática del consumo ante sesión anulada (hoy manual).
- [ ] 04.05: validar cobertura y caso al consumir.
- [ ] 04.04: el plan puede activarse sin ítems.
- [ ] 04.03: unique de caso activo (RN-M10-002); `EstadoCaso` solo tiene `ACTIVO`/`CERRADO`.
- [ ] Gate RF-M10-007 (exigir caso al reservar o atender): etapa propia con ventana de migración.
  Se coordina con [F6](F6-atencion-clinica.md).

### Frontend (en este orden, cada una con su E2E contra backend real)
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
| Sin unique de caso activo, sin backfill de sesiones previas | Sí |
| Cierre sin saldo de autorización no falla | Sí, registro de 04.05 |
| Arista `person → encounter` invertida para evitar ciclo | Sí |
| No hay endpoint explícito de "consumir": solo nace del cierre | Sí |

## Decisiones del usuario que bloquean

- Puente Oferta↔Práctica (compartida con [F2](F2-operacion-del-consultorio.md)).
- Reversión del consumo ante sesión anulada: automática o manual.

## Para cerrar la fase

- [ ] `HistoriaClinicaController` + `RelacionAsistencialProbe` real, con ITs.
- [ ] Las cinco pantallas con su E2E.
- [ ] ADR del plan por práctica (o decisión explícita de quedarse en oferta).
- [ ] Registros de 04.02–04.05 promovidos de "avance" a "cierre" con los diez puntos.
