# F6 — Atención clínica

> Auditoría del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · **~62 %**
> Etapas del plan: 06.01–06.06 (§13, líneas ~2823–3304). Insumo de diseño: `docs/producto/plan_sesiones.txt`.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 06.01 Sesión, inicio, autosave | PARCIAL | 70 | Caso obligatorio; atención sin turno |
| 06.02 Evaluación base | CUMPLIDA en lo esencial | 80 | E2E de sesión rápida |
| 06.03 Examen y mediciones | PARCIAL | 55 | **Sin frontend**; ROM/fuerza/marcha como estructura propia |
| 06.04 Tratamientos realizados | PARCIAL | 45 | **Defecto `NO_APLICA`**; sin frontend |
| 06.05 Cierre idempotente | CUMPLIDA con desvío | 80 | E2E cierre → timeline |
| 06.06 Enmiendas | PARCIAL | 45 | No versiona tratamientos ni mediciones; permiso no reforzado; sin frontend |

La pantalla `atencion-page` cubre inicio, evaluación y cierre (06.01/06.02/06.05). **El frontend
se cortó ahí**: mediciones, tratamientos y enmiendas no tienen UI (`atencion-page` todavía dice
"06.04 no existe").

## Defecto vigente — verificado

**`V55` rechaza `NO_APLICA`.** El contrato y los DTO (`RegistrarTratamientoRequest`,
`EnmendarSesionRequest`) aceptan `lateralidad = NO_APLICA`, y el comentario de la propia columna
en `V55` dice "NO_APLICA incluido". Pero el CHECK no lo incluye:

```sql
CONSTRAINT ck_tratamiento_lateralidad
    CHECK (lateralidad IS NULL
        OR lateralidad IN ('IZQUIERDA', 'DERECHA', 'BILATERAL')),
```

Registrar un tratamiento en zona central (lumbar, cervical) termina en violación de CHECK en
runtime. No apareció porque **nunca se ejecutó un IT de tratamientos**. Arreglo: migración nueva
que recree el CHECK (no editar `V55`), con IT que lo reproduzca primero.

## Faltantes

### Backend / API
- [ ] 06.01: "FK única a caso" — `sesion.caso_id` sigue nullable (`V48`) y el frontend nunca lo
  envía. Se resuelve junto con el gate RF-M10-007 ([F4](F4-dominio-clinico.md)).
- [ ] 06.01: atención sin turno — `turno_id` es nullable pero el único inicio es
  `POST /sesiones/turnos/{turnoId}`.
- [ ] 06.01: reemplazo autorizado del profesional.
- [ ] 06.03: subbloques ROM, fuerza y marcha (hoy todo es medición genérica, desvío no documentado);
  obligatorios por modo/política; "copiar previo con confirmación".
- [ ] 06.04: parámetros obligatorios por práctica (**decisión pendiente**: no hay configuración que los declare).
- [ ] 06.06: `sesion_version` no incluye tratamientos ni mediciones → corregir un tratamiento
  cerrado es imposible.
- [ ] 06.06: permiso reforzado y política temporal para enmendar (hoy usa el mismo `sesion:register`).
- [ ] 06.05: el criterio pide "HC consistente aunque la economía falle y reintente"; hoy un
  observador que falla hace fallar el cierre. Decidir si se mantiene como contrapartida (escribirla
  como DP) o se desacopla.

### Frontend
- [ ] Mediciones (el cliente generado existe, ninguna pantalla lo usa).
- [ ] Tratamientos realizados.
- [ ] Enmiendas (no hay ruta en `clinical.routes.ts`).
- [ ] Asociar la sesión a un caso.

### Tests
- [ ] ITs de tratamientos (escenarios 39–43 de `docs/tests-diferidos.md`), mediciones y enmiendas.
- [x] Test de permiso y de auditoría `SESION_AMENDED`.
- [ ] E2E de sesión rápida y de cierre → timeline.
- [ ] Verificar que el administrativo no ve el detalle clínico.

> **En curso al 01/10:** la rama `akine-integracion-total` agregó `TratamientoServiceTest`,
> `MedicionServiceTest` y `EncounterProblemHandlerTest` (unitarios). No cubren el CHECK de la base:
> el IT sigue haciendo falta.

## Desvíos

| Desvío | Documentado |
|---|---|
| Sesión cuelga de la HC y no del caso; correlativo principal por HC | Sí, DP-10 y registros |
| Caso opcional | Sí, javadoc de `SesionService` |
| Examen como modelo genérico de mediciones, no por dominios | **No — silencioso** |
| Enmienda sin permiso reforzado | **No — silencioso** |
| Ruta de mediciones bajo `/consultorios/{id}/sesiones/...` | Sí, registro §6 |
| Se quitó la validación de ocupación del espacio en tratamientos; parámetros con borrado físico | Sí |
| Un observador que falla hace fallar el cierre | Sí, como "contrapartida asumida", no como DP |

## Decisiones del usuario que bloquean

- ¿Una sesión con N tratamientos consume N unidades de autorización o 1? (hoy 1)
- Parámetros obligatorios por práctica.

## Para cerrar la fase

- [x] Migración que corrija el CHECK de lateralidad, con IT.
- [ ] Caso obligatorio y atención sin turno (coordinado con F4 y F5).
- [ ] Enmiendas que versionen tratamientos y mediciones, con permiso reforzado.
- [ ] Pantallas de mediciones, tratamientos y enmiendas con E2E.
- [ ] Registros de 06.03, 06.04 y 06.06 promovidos a cierre.
