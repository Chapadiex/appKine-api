# F6 — Atención clínica

> Auditoría del 01/10/2026 (~62 %), **reverificada el 12/10/2026**: ver el registro de cierre al final.
> Etapas del plan: 06.01–06.06 (§13, líneas ~2823–3304). Insumo de diseño: `docs/producto/plan_sesiones.txt`.

## Estado por etapa

| Etapa | Estado | % | Lo que falta, en una línea |
|---|---|---|---|
| 06.01 Sesión, inicio, autosave | PARCIAL | 70 | Caso obligatorio; atención sin turno |
| 06.02 Evaluación base | CUMPLIDA en lo esencial | 80 | E2E de sesión rápida |
| 06.03 Examen y mediciones | PARCIAL | 55 | **Sin frontend**; ROM/fuerza/marcha como estructura propia |
| 06.04 Tratamientos realizados | PARCIAL | 45 | **Defecto `NO_APLICA`**; sin frontend |
| 06.05 Cierre idempotente | CUMPLIDA con desvío | 80 | E2E cierre → timeline |
| 06.06 Enmiendas | PARCIAL | 60 | Versiona tratamientos y mediciones desde C-6; permiso no reforzado; sin frontend |

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
- [x] ~~06.01: "FK única a caso" — `sesion.caso_id` sigue nullable (`V48`) y el frontend nunca lo
  envía. Se resuelve junto con el gate RF-M10-007 ([F4](F4-dominio-clinico.md)).~~ **DP-43 (U-09):** el caso es obligatorio solo en ofertas con `requiere_caso_clinico`: iniciar, quitarlo y cerrar presente sin caso responden 409 `caso-clinico-requerido`; `caso_id` sigue nullable y las sesiones viejas no se migran.
- [x] ~~06.01: atención sin turno — `turno_id` es nullable pero el único inicio es
  `POST /sesiones/turnos/{turnoId}`.~~ **DP-43 (U-24):** `POST /sesiones` sin turno, con `sesion:register`, motivo obligatorio e `idempotencyKey` (`V119`).
- [x] ~~06.01: reemplazo autorizado del profesional.~~ **DP-43 (U-24):** `PUT /sesiones/{id}/profesional`, solo `sesion:reassign` (CONSULTORIO_ADMIN), motivo y registro en `sesion_reemplazo_profesional` (`V119`).
- [ ] 06.03: subbloques ROM, fuerza y marcha (hoy todo es medición genérica, desvío no documentado);
  obligatorios por modo/política; "copiar previo con confirmación".
- [ ] 06.04: parámetros obligatorios por práctica (**decisión pendiente**: no hay configuración que los declare).
- [x] ~~06.06: `sesion_version` no incluye tratamientos ni mediciones → corregir un tratamiento
  cerrado es imposible.~~ **Cerrado por C-6** (`V71`, contrato 0.56.0): cada version lleva la foto
  de tratamientos y mediciones, y la enmienda los corrige sin poder cambiar las practicas
  realizadas. Ver `docs/diseno/AKINE-C-6-enmiendas.md`.
- [x] ~~06.06: permiso reforzado y política temporal para enmendar.~~ **DP-43 (U-25):** `sesion:amend` y ventana de 30 días por organización (`politica_de_enmienda`, `V119`); fuera de ventana 409 `enmienda-fuera-de-ventana`.
- [ ] 06.05: el criterio pide "HC consistente aunque la economía falle y reintente"; hoy un
  observador que falla hace fallar el cierre. **Pendiente del usuario (DU-11)**: no hay arreglo
  acotado que no sea decidir el desacople.

### Frontend — frente clínico de pantalla, **otro chat** (D-d, D-g; web #4 para la HC)
- [ ] Mediciones (el cliente generado existe, ninguna pantalla lo usa).
- [ ] Tratamientos realizados.
- [ ] Enmiendas (no hay ruta en `clinical.routes.ts`).
- [ ] Asociar la sesión a un caso.

### Tests
- [x] ITs de tratamientos (escenarios 39–43 de `docs/tests-diferidos.md`), mediciones y enmiendas.
- [x] Test de permiso y de auditoría `SESION_AMENDED`.
- [ ] E2E de sesión rápida y de cierre → timeline.
- [x] ~~Verificar que el administrativo no ve el detalle clínico.~~ `MatrizDePermisosIT` (G-5, #78):
  historia, timeline y casos dan 403 al `ADMINISTRATIVO`; desde el 12/10 además ve sólo los datos
  administrativos (ver F4).

> **En curso al 01/10:** la rama `akine-integracion-total` agregó `TratamientoServiceTest`,
> `MedicionServiceTest` y `EncounterProblemHandlerTest` (unitarios). No cubren el CHECK de la base:
> el IT sigue haciendo falta.

## Desvíos

| Desvío | Documentado |
|---|---|
| Sesión cuelga de la HC y no del caso; correlativo principal por HC | Sí, DP-10 y registros |
| Caso opcional | Sí, javadoc de `SesionService` |
| Examen como modelo genérico de mediciones, no por dominios | **No — silencioso** |
| Enmienda sin permiso reforzado | ~~No — silencioso~~ Cerrado por DP-43: `sesion:amend` y ventana |
| Ruta de mediciones bajo `/consultorios/{id}/sesiones/...` | Sí, registro §6 |
| Se quitó la validación de ocupación del espacio en tratamientos; parámetros con borrado físico | Sí |
| Un observador que falla hace fallar el cierre | Sí, como "contrapartida asumida", no como DP |

## Decisiones del usuario que bloquean

> **Decididas el 19/10/2026** (el usuario aceptó las recomendaciones; ver `decisiones-pendientes.md`). U-24 (C-8) sesión sin turno con `sesion:register` y reemplazo solo por `CONSULTORIO_ADMIN`: se implementa, PR en curso (DP-43). U-25 `sesion:amend` propio y ventana de 30 días por organización: se implementa, PR en curso (DP-43). U-14 (DU-11) un observador que falla hace fallar el cierre: se deja como está (DP-42). U-26 ROM/fuerza/marcha como medición genérica: se deja como está (DP-42). U-27 parámetros por práctica: después del RC (DP-44).

- ¿Una sesión con N tratamientos consume N unidades de autorización o 1? (hoy 1)
- Parámetros obligatorios por práctica.

## Para cerrar la fase

- [x] Migración que corrija el CHECK de lateralidad, con IT.
- [x] ~~Caso obligatorio y atención sin turno (coordinado con F4 y F5).~~ DP-43.
- [ ] Enmiendas que versionen tratamientos y mediciones (**hecho en C-6**), con permiso reforzado (**DP-43**: `sesion:amend` y ventana).
- [ ] Pantallas de mediciones, tratamientos y enmiendas con E2E.
- [ ] Registros de 06.03, 06.04 y 06.06 promovidos a cierre.

## Registro de cierre — 12/10/2026

| Etapa | Backend | Pantalla (otro chat) |
|---|---|---|
| 06.01 Sesión, inicio, autosave | **80 %** — falta caso obligatorio (C-9), atención sin turno y reemplazo del profesional (C-8): decisiones | atención-page existente |
| 06.02 Evaluación base | **100 %** | E2E pendiente (D-g) |
| 06.03 Examen y mediciones | **80 %** — ITs (C-7), versionado en enmiendas (C-6 #43). Subbloques ROM/fuerza/marcha: decisión | D-d sin PR |
| 06.04 Tratamientos realizados | **90 %** — `NO_APLICA` corregido (C-1 #6, `V65`), versión que avanza una vez (C-7), DP-12. Parámetros obligatorios por práctica: decisión | D-d sin PR |
| 06.05 Cierre idempotente | **90 %** — auditoría de la Sesión restituida (#78), 409 `oferta-sin-precio` (#72). Desacople de la economía: decisión | D-g sin PR |
| 06.06 Enmiendas | **85 %** — versionan tratamientos y mediciones (C-6, #43, `V71`). Permiso reforzado y ventana: decisión | D-g sin PR |

**Backend, con evidencia:** C-1 (#6, 04/10), C-7 ITs de tratamientos, mediciones y enmiendas
(#10, 04/10), C-6 (#43, 06/10), auditoría `SESION_*` restituida y matriz ejecutable (#78, 11/10),
cobertura de `encounter` (#32). Esta rama no toca `encounter`: el "administrativo no ve el
detalle clínico" queda verificado por `MatrizDePermisosIT` y la lectura limitada está en F4.

**Pantalla:** frente clínico de pantalla — otro chat (D-d, D-g).

**Pendiente del usuario:**

1. **C-8 / DU-11: atención sin turno y reemplazo autorizado del profesional.** *Recomendado:*
   `POST /sesiones` sin turno con `sesion:register` y motivo obligatorio; reemplazo sólo por
   `CONSULTORIO_ADMIN` con auditoría.
2. **DU-11: ¿un observador que falla debe hacer fallar el cierre?** *Recomendado:* mantenerlo
   (la deuda se devenga en la transacción del cierre, regla 7) y escribirlo como DP.
3. **Subbloques ROM, fuerza y marcha.** *Recomendado:* quedarse en la medición genérica con
   catálogo de tipos y declarar el desvío.
4. **Parámetros obligatorios por práctica.** *Recomendado:* configuración por práctica en
   `offering`, en una etapa propia.
5. **Permiso reforzado y ventana temporal para enmendar.** *Recomendado:* `sesion:amend` propio
   y ventana de 30 días configurable por organización.
6. **Justificación de acceso en `encounter`** (G-5). *Recomendado:* declarar que la propiedad de la
   sesión ya es relación asistencial.
