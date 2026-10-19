# F3 — Personas y cobertura

> Auditoría original del 01/10/2026 · backend `37ff201` · frontend `4c6ae8b` · ~79 %.
> **Reverificada el 12–13/10/2026** contra `main` de los dos repos (backend `e47b662`, PR #98;
> frontend `main` con #68) y la lista de PR mergeados de la API de GitHub. **~96 %**: todo lo que
> es código está hecho; lo que falta son decisiones del usuario y el QA manual.
> Etapas del plan: 03.01–03.06 (§13, líneas ~1632–2107).

## Estado por etapa

| Etapa | Estado | % | Lo que quedaba al 01/10 → con qué se cerró | Lo que falta |
|---|---|---|---|---|
| 03.01 Persona, perfil paciente, dedup | CERRADA (código) | 97 | Búsqueda por afiliado → B-1 (api #12 · web #3, 05/10). `paciente:read` → DP-22 (api #76 · web #40, 08/10). E2E de padrón → web #68 (12/10) | Alcance `OWN` (autoservicio, post-MVP por DP-22). QA manual |
| 03.02 Paciente 360 y adjuntos | CERRADA (código) | 97 | Cobertura en el 360 → B-5 (api #34 · web #16, 06–07/10) y A-11 por campos (api #64 · web #29, 08/10). `AdjuntoConcurrenteIT` → B-6 (api #68, 08/10, con el defecto de idempotencia corregido). Subida multipart de punta a punta → `AdjuntoMultipartIT` (este cierre) | QA manual |
| 03.03 Financiadores y planes | CERRADA (código) | 90 | Moneda ISO 4217 → api #21 (06/10) | Catálogo global de financiadores (**DU-13**) y `convenio:read` (**decisión**, ver abajo). QA manual |
| 03.04 Coberturas del paciente | CERRADA (código) | 97 | Cobertura aplicable RF-M08-006/007 → B-3 (api #55 · web #15 y #23, 07–08/10). La cobertura viaja: recepción guarda `cobertura_id` (E-4, `V78`, api #52) y la obligación copia `cobertura_id` + snapshot del convenio y del arancel (F-4, `V77`, api #49); Particular llega a la obligación (E-7, api #56) | QA manual |
| 03.05 Convenios y aranceles | CERRADA (código) | 92 | `congelar` con consumidor → F-4 (`ObligacionDevengador`, api #49). Arancel por oferta y precio particular → B-3 (`V80`). Importación masiva con vista previa RF-M16-007 → B-7 (api #63 · web #28, 08/10) | Clonación controlada de convenio (**decisión**, sin RF). QA manual |
| 03.06 Órdenes y autorizaciones | CERRADA (código) | 97 | Historial y estado de la orden → B-4 (DP-23, `V85`, api #75 · web #39, 08/10). Saldo, ledger y alertas en pantalla → D-f (web #58, 11/10). E2E persona → cobertura → orden → autorización → vencimiento → B-6 (web #36, 08/10); consumo → alerta → reversión (web #58) | Obligatoriedad de requisitos por financiador/prestación (**decisión**). QA manual |

**Las seis tienen backend y pantalla.** Los registros de cierre de 03.02–03.06 en el plan dicen
"sin frontend"; es falso desde antes del 19/09 — ver la fe de erratas al final.

## El hallazgo central del 01/10 — ya no es cierto

La auditoría decía que F3 estaba aislada. Al 13/10 está conectada en los tres puntos:

- ~~`ArancelDirectory#congelar` no tiene consumidor~~ → lo consume `billing.ObligacionDevengador`
  (F-4, api #49): copia el convenio y el arancel enteros en la obligación.
- ~~La cobertura elegida no se copia en el turno, ni en la sesión, ni en la obligación~~ → la
  recepción guarda la cobertura validada (`recepcion.cobertura_id`, E-4) y la obligación la copia
  (`obligacion.cobertura_id`, F-4). **La sesión no la lleva**: el devengado la vuelve a resolver
  al cerrar con el mismo `CoberturasAplicablesDirectory` que usa la recepción. Si entre la
  recepción y el cierre cambia la cobertura del paciente, gana la del cierre (es la decisión
  abierta 12(c) del `CLAUDE.md`: la deuda es una foto del cierre).
- ~~No existe obligación con responsable `FINANCIADOR`~~ → existe desde F-4 (`V77`).

## Faltantes

### Backend / API
- [x] ~~Búsqueda por número de afiliado (RF-M07-001).~~ → B-1, api #12 (05/10), contrato 0.46.0.
- [x] ~~RF-M08-006/007: cobertura aplicable por oferta.~~ → B-3, api #55 (07/10), contrato 0.65.0. Pantalla: web #15 (07/10).
- [x] ~~RF-M16-008/009 (oferta asociada al convenio, arancel particular por oferta).~~ → B-3 (`V80`). Pantalla: web #15 y #23.
- [x] ~~RF-M16-007 (importación masiva con preview).~~ → B-7, api #63 (08/10), contrato 0.71.0 (`importArancelesDeConvenio`, `PREVIEW | CONFIRMAR`). Pantalla: web #28. E2E: `flujo-practicas-y-aranceles` (web #64).
- [x] ~~Validar moneda contra ISO 4217.~~ → api #21 (06/10).
- [x] ~~Historial de estados de la autorización.~~ → B-4 / DP-23, api #75 (08/10), `V85`. Pantalla: web #39.
- [x] ~~Estado de la orden médica.~~ → B-4 (`SituacionOrdenMedica`, `sesionesConsumidas`). Pantalla: web #39.
- [x] ~~Contribuyente de cobertura al Paciente 360.~~ → B-5, api #34; por campos en A-11, api #64. Pantalla: web #16 y #29.
- [x] ~~`ArancelDirectory#congelar` sin consumidor.~~ → F-4, api #49 (07/10).
- [ ] **Obligatoriedad de requisitos configurable por financiador o por prestación** — *pendiente del usuario*, ver abajo.
- [ ] **Catálogo global de financiadores (DU-13)** — *pendiente del usuario*, ver abajo.
- [ ] **`convenio:read`** — *pendiente del usuario*, ver abajo.

### Seguridad
- [x] ~~`paciente:read`: una membership `PACIENTE` lee el padrón entero.~~ → DP-22, api #76 (08/10): 403 en todas las lecturas de `person` (`PacienteReadIT`).
- [ ] Alcance `OWN` (autoservicio del paciente) — **diferido por decisión** (DP-22), post-MVP.

### Frontend
- [x] ~~Previsualización de impacto~~ de una carga de aranceles → vista previa de la importación (B-7, web #28).
- [ ] **Clonación controlada de convenio** — *pendiente del usuario*, ver abajo.

### Tests
- [x] ~~No hay un solo E2E de F3.~~ → `e2e/cobertura-autorizacion.spec.ts` (B-6, web #36, 08/10): persona → perfil → cobertura → orden → autorización aprobada → vencimiento, contra el backend real. Además `flujo-autorizaciones` (web #58), `flujo-padron` (web #68) y `flujo-practicas-y-aranceles` (web #64).
- [x] ~~`AdjuntoConcurrenteIT`~~ → api #68 (08/10); destapó la idempotencia rota de la subida (`REPEATABLE READ`) y la corrigió.
- [x] ~~Probar la subida multipart de verdad.~~ → `src/test/java/com/akine/diferidos/AdjuntoMultipartIT.java` (este cierre): cuerpo `multipart/form-data` armado a mano sobre HTTP real, idempotencia (201 y después 200 con el mismo id, una sola fila), descarga byte a byte con `attachment` y `nosniff`, y un texto declarado `application/pdf` rechazado con 400 `archivo-no-aceptado` sin dejar fila.
- [ ] **QA manual del §6** — *pendiente del usuario*. La guía existe: `docs/qa/04-personas-coberturas-autorizaciones.md` (G-12, api #84 y #95), con las consultas validadas contra el esquema. **No hay ninguna corrida registrada** (`docs/qa/corridas/` no existe).

## Desvíos

| Desvío | Documentado |
|---|---|
| Historial de autorización reemplazado por auditoría | **Corregido por B-4 (DP-23)**: tabla propia append-only |
| Afiliado diferido a 03.04 y nunca cableado | **Corregido por B-1** (api #12) |
| Sin URLs firmadas: cada descarga se autoriza en la aplicación | Sí, intencional (RN-M25-002) |
| Paciente fusionado no implementado; la baja no valida turnos ni deuda | Sí |
| Financiador como dato de la organización, sin alcance global | Sí, registro y cabecera de `V41`; espera DU-13 |
| Única principal y no-solapamiento por lock, no por constraint | Sí, intencional |
| Sin columna `prioridad` en convenio; ~~arancel por práctica y no por oferta~~ (B-3: `convenio_arancel.oferta_id`, `V80`) | Sí, cabecera de `V43` y `V80` |
| ~~Faltan diseño y challenge de 03.03, 03.05 y 03.06 en `docs/diseno/`~~ | **Reconstruidos abajo** ("Diseño vigente"), declarados como reconstrucción y no acta |

## Para cerrar la fase

- [x] ~~E2E de la vertical persona → cobertura → autorización contra backend real.~~ → web #36 (08/10).
- [x] ~~Búsqueda por afiliado~~ (B-1) y ~~RF-M08-006/007~~ (B-3, backend y pantalla).
- [x] ~~Decisión sobre el historial de autorización.~~ → DP-23.
- [x] ~~`spi` de cobertura vigente listo para que F5 (recepción) y F7 (devengado) lo consuman.~~ → B-2: `person.spi.CoberturasAplicablesDirectory`; lo consumen E-4 y F-4.
- [x] ~~Corregir los registros de cierre (frontend entregado) y escribir los diseños que faltan.~~ → fe de erratas y "Diseño vigente", abajo.
- [ ] Las cuatro decisiones del usuario y el QA manual (abajo).

## Pendiente del usuario

> **Decididas el 19/10/2026** (el usuario aceptó las recomendaciones; ver `decisiones-pendientes.md`). U-12 (DU-13) el centro adopta una copia del catálogo global: se implementa, PR en curso (DP-43). U-18 requisitos solo en el convenio: se deja como está (DP-42). U-20 datos administrativos de la HC sin auditar: se deja como está (DP-42). U-19 clonar convenio fuera del MVP: después del RC (DP-44).

Ninguna se implementó porque ningún RF la resuelve; cada una trae la opción recomendada.

1. **DU-13 — catálogo global de financiadores.** ¿El centro *referencia* la fila global
   (`owner_key` en `financiador`/`plan_cobertura`, reabre todas las lecturas de `contracting`) o
   la *adopta* como copia (tabla global aparte, el centro copia lo que usa)? **Recomendada: la
   copia** — no toca ninguna lectura existente ni los snapshots ya congelados. Opciones y costos en
   `docs/diseno/AKINE-A-7-plataforma.md` §3.
2. **Obligatoriedad de requisitos por financiador o por prestación.** RF-M16-005 pone los
   requisitos (orden, autorización, documentación) **en el convenio**, que es donde están hoy. Un
   nivel por financiador o por práctica no tiene RF. **Recomendado: dejarlo en el convenio** y
   cerrar el ítem como no requerido; si hace falta un default, que sea "copiar los requisitos del
   convenio anterior" al crear uno nuevo, que es la clonación de abajo.
3. **Clonación controlada de convenio.** Sólo aparece en la sección Frontend de 03.05 del plan
   ("clonación controlada y previsualización de impacto"); **ningún RF de M16** la pide
   (RF-M16-001…010). La previsualización ya existe para la importación (B-7). **Recomendado:**
   si se quiere, una operación `POST .../convenios/{id}/clonar` que crea un convenio nuevo con
   vigencia a partir de una fecha, copia aranceles y requisitos, y responde primero en modo
   `PREVIEW` como la importación; ~1 día de backend + pantalla. Sin decisión, fuera del MVP.
4. **`convenio:read`.** Las lecturas de `contracting` (financiadores, planes, convenios, aranceles)
   se autorizan por **pertenencia**: una membership `PACIENTE` lee la configuración económica del
   centro. Es el mismo hueco que DP-22 cerró para el padrón, y la cabecera de
   `contracting.domain.PermissionCodes` dice que una etapa no inventa códigos de la matriz.
   **Recomendado:** repetir DP-22 — `convenio:read` sólo para el personal (`ORG_ADMIN`,
   `CONSULTORIO_ADMIN`, `PROFESIONAL`, `ADMINISTRATIVO`), `PACIENTE` con 403; sin migración
   (catálogo en código), contrato con sólo descripciones de 403.
5. **Alcance `OWN`** — ya decidido por DP-22: post-MVP. Sin acción.
6. **QA manual del §6** con `docs/qa/04-personas-coberturas-autorizaciones.md` (PER-01 en
   adelante), registrando la corrida en `docs/qa/corridas/AAAA-MM-DD.md` como pide
   `docs/qa/README.md`. Es la única verificación que falta y es bloqueante para deploy.

## Diseño vigente de 03.03, 03.05 y 03.06 — reconstrucción, no acta

Estas tres etapas se hicieron sin documento en `docs/diseno/`. Lo que sigue **no** es su diseño
original —no existe— sino un índice de dónde vive hoy cada decisión, escrito el 13/10/2026 por
alguien que no estuvo en esas sesiones.

| Etapa | Tablas (dueño) | Decisiones vigentes y dónde están |
|---|---|---|
| **03.03** Financiadores y planes (M15) | `financiador`, `plan_cobertura` — `contracting` (`V41`) | Financiador como dato de la organización (cabecera de `V41`, con el camino de migración a global). Sin no-solapamiento entre planes, a propósito. `spi` separa lectura viva (`find*`) de copia (`congelar`). Permiso `convenio:manage` con la sede del contexto (`contracting.domain.PermissionCodes`, matriz §13). Moneda ISO 4217 (api #21) |
| **03.05** Convenios y aranceles (M16) | `convenio`, `convenio_arancel`, `convenio_lock` — `contracting` (`V43`, `V80`) | RN-M16-002 sin índice: la cumple el lock de `convenio_lock` (cabecera de `V43`). Resolución determinista en `contracting.domain.ResolutorDeArancel`; arancel por oferta manda sobre el general (`docs/diseno/AKINE-B-3-cobertura-por-oferta.md`). Importación con vista previa (`docs/diseno/AKINE-B-7-importacion-aranceles.md`). Snapshot copiado al devengar (`docs/diseno/AKINE-F-4-obligacion-financiador.md`) |
| **03.06** Órdenes y autorizaciones (M17) | `orden_medica`, `autorizacion` (`V44`), `autorizacion_movimiento` (`V50`), `autorizacion_alerta` (`V76`), `autorizacion_evento` (`V85`) — `person` | La orden o autorización satisface el requisito del convenio y `consultarElegibilidadAdministrativa` lo responde (la consume la recepción, E-4). Consumo con ledger (`docs/diseno/AKINE-04.05-consumo-de-autorizaciones.md`, `AKINE-C-4-consumo.md`). Historial y situación de la orden (`docs/diseno/AKINE-B-4-historial-autorizacion.md`) |

## Fe de erratas de los registros de cierre

Los registros de cierre de 03.02 a 03.06 en `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` dicen que
la etapa cerró **sin frontend**. Es falso: el frontend llegó en `appKine-web` antes del 19/09,
sobre `akine-01.02-identidad`, y está en `main`. La corrección vive acá y no en el plan.

| Etapa | El registro dice | Lo que hay | Commit en `appKine-web` |
|---|---|---|---|
| 03.01 | — (correcto) | Padrón con búsqueda previa al alta | `0a87c27`, `1133ea7` |
| 03.02 | Sin frontend | Ficha 360, bajas lógicas y adjuntos | `8920331` |
| 03.03 | Sin frontend | Catálogo de financiadores y planes | `58f55ed`, `79c13e3` |
| 03.04 | Sin frontend | Coberturas del paciente | `0d13807` |
| 03.05 | Sin frontend | Convenios de la sede y grilla de aranceles | `a0b0e10`, `4c3a1a8` |
| 03.06 | Sin frontend | Órdenes, autorizaciones y elegibilidad | `e5fc5d2` |

Y una segunda errata, del registro de 03.02 (§5): la subida idempotente "probada por el unique"
no lo estaba — la primera corrida con dos hilos (B-6, api #68) encontró que el perdedor recibía 409.

## Registro de cierre — 12/10/2026

**Qué se hizo en este cierre** (rama `akine-cierre-F3`, desde `main` `e47b662`):

- Se reverificó cada ítem de la ficha del 01/10 contra el código de `main` y los PR mergeados de
  los dos repos (lista completa por la API de GitHub). Todo lo que era código estaba hecho por
  otros paquetes salvo **una** prueba: la subida multipart de punta a punta.
- `AdjuntoMultipartIT` (nuevo): dos escenarios contra MySQL real (Testcontainers) y HTTP real.
- `AdjuntoConcurrenteIT`: el javadoc seguía diciendo "DEFECTO" como estado actual; ahora aclara
  que el arreglo entró en #68.
- Esta ficha: estado por etapa con % medidos, faltantes tachados con PR y fecha, el hallazgo
  central del 01/10 desmentido punto por punto, la fe de erratas de los registros y el índice de
  diseño de 03.03/03.05/03.06.
- **Sin migración ni cambio de contrato.**

**Evidencia:** PR mergeados citados en cada fila (api #12, #21, #34, #49, #52, #55, #56, #63, #64,
#68, #75, #76; web #3, #15, #16, #23, #28, #29, #36, #39, #40, #58, #64, #68). Corrida local de
`AdjuntoMultipartIT` en este PR; el resto lo corre el CI.

**Qué queda, todo del usuario:** las cuatro decisiones de "Pendiente del usuario" (DU-13,
requisitos por financiador/prestación, clonación de convenio, `convenio:read`) y el QA manual del
§6 con `docs/qa/04-personas-coberturas-autorizaciones.md`. Con eso F3 pasa de ~96 % a cerrada.
