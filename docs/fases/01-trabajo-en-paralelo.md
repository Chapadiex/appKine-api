# Trabajo en paralelo — cómo repartir lo que falta entre varias personas

> Escrito el **02/10/2026** sobre `main` = `2cdf28f` (backend) y `344a8d6` (frontend).
> Diagrama de los **grupos de trabajo** (§9), pensado para repartir y marcar el avance:
> [`01-trabajo-en-paralelo.excalidraw`](01-trabajo-en-paralelo.excalidraw) (se abre en
> <https://excalidraw.com> o con la extensión de VS Code) y su render
> [`01-trabajo-en-paralelo.png`](01-trabajo-en-paralelo.png).

Este documento convierte las fichas `F*.md` en **paquetes de trabajo** que distintas personas
pueden tomar a la vez sin pisarse. No reemplaza las fichas: cada paquete apunta a la suya, y el
detalle del faltante se lee ahí.

## 1. Cómo se reconcilia con `00-orden-recomendado.md`

`00-orden` dice "entre fases, no se paraleliza". La razón de fondo es no construir consumidores
antes que sus cimientos (`AGENT.md` §9). Con varias personas, esa barrera por fase deja a la
mayoría esperando, así que acá se reemplaza por **dependencias explícitas entre paquetes**:

- Un paquete arranca cuando **sus** dependencias están en `main`, no cuando la fase anterior entera
  cerró.
- Lo que sigue valiendo igual: los **criterios de cierre de fase** de `00-orden` (CA del plan,
  pantalla + E2E contra backend real, ITs en `verify`, registro de cierre, ficha tachada). Una fase
  se declara cerrada recién cuando todos sus paquetes cerraron.
- **F9 y F10 siguen congeladas** hasta el gate del MVP (DP-07). No forman parte de este reparto.

## 2. Estado de partida (verificado el 02/10)

| Ítem del paso 0/1 de `00-orden` | Estado hoy |
|---|---|
| Rama de tests `akine-integracion-total` | **Integrada** en `main` (PR #2). Worktrees `agent-*` ya no existen |
| Fichas de fase | **Integradas** (PR #1 en los dos repos) |
| Rama por defecto de GitHub | **Sigue en `claude/goofy-goodall-8e6772`** (`origin/HEAD`). Hay que cambiarla a `main` antes de abrir el primer PR paralelo |
| `V55` rechaza `NO_APLICA` | **Sigue abierto** (`V55__m14_tratamiento_realizado.sql:192`) |
| `reporte:read` fuera de `RolePermissions` | **Sigue abierto** |
| `cuentaCorriente` pagina fijo `200, 0` / `sumarAnuladoEnElReporte` sin `deletedAt` | A verificar (paquete F-1 / G-2) |
| Última migración | `V64`. La próxima libre es **`V65`** |
| Contrato | `0.44.0`, cliente del frontend regenerado contra `0.44.0` |

> **Al 06/10/2026** (`main` = `d110aaf`, PR #39): `V55` con `NO_APLICA` resuelto por C-1 (`V65`);
> `cuentaCorriente` y `sumarAnuladoEnElReporte` resueltos por F-1 y G-2; última migración **`V69`**;
> contrato **`0.54.0`**, con el cliente del frontend en `0.46.0`. `reporte:read` sigue abierto
> (G-1, espera DU-3). La tabla de arriba queda como foto del 02/10.
>
> **Al 07/10/2026** (`main` = `8fe1fae`, PR #60; `appKine-web` `main` = `e22d75d`, PR #23):
> `reporte:read` resuelto por G-1 (DP-15); última migración **`V83`**; contrato **`0.69.0`** con
> 301 operaciones, y el cliente del frontend **también en `0.69.0`**. La rama por defecto de
> GitHub es `main` en `appKine-web`, pero en `appKine-api` **sigue siendo
> `claude/goofy-goodall-8e6772`** (consultado por la API el 07/10): L-0 sigue abierto.

## 3. Reglas de convivencia — leer antes de tomar un paquete

Estas reglas existen porque el proyecto **ya pagó** cada una (ver `CLAUDE.md` §7).

1. **Un worktree por persona y por paquete.** Dos sesiones en el mismo árbol rompen Flyway y
   `target/`. `git worktree add ../wt-<paquete> -b akine-<paquete>-<slug>`.
2. **Misma rama en los dos repos.** Si un paquete toca backend y frontend, la rama se llama igual
   en `appKine-api` y `appKine-web`: el job `contrato` del CI del frontend clona el backend **en la
   rama del mismo nombre** y compara contra ese contrato.
3. **Migraciones reservadas antes de escribir código** (tabla §6). Flyway corre con
   `out-of-order` en falso: **las migraciones tienen que entrar a `main` en orden ascendente**. Si
   tu número quedó por debajo de uno ya mergeado, renumerá al rebasear (es barato mientras no haya
   un ambiente compartido desplegado) y borrá el volumen de MySQL local
   (`docker compose down -v`). Un número reservado y abandonado queda vacío, como `V26`.
4. **Nombres de constraint con la tabla adelante** (`fk_<tabla>_<ref>`, `ck_<tabla>_<campo>`): en
   MySQL son únicos por esquema.
5. **El contrato es un punto caliente.** `openapi/akine-api.yaml` y `akine.contract.version`
   (`pom.xml`) los tocan todos los paquetes con API. Regla: **subí la minor en tu rama, y al
   rebasear sobre `main` regenerá** (`./mvnw verify -Dakine.contract.update=true`) y volvé a
   subirla por encima de la que encontraste. Nunca resuelvas el conflicto del YAML a mano.
6. **El cliente TypeScript se regenera en la rama del frontend que lo necesita**, contra el
   contrato de la rama homónima del backend (o de `main` si el backend ya entró). Nunca se edita a
   mano.
7. **Puntos calientes compartidos** — cambios chicos, rebase frecuente, y avisar en el canal antes
   de tocarlos:
   - Backend: `organization/domain/RolePermissions.java`, `PermissionCode.java`,
     `platform/api/GlobalExceptionHandler.java`, `application.yml`, `pom.xml`.
   - Frontend: `app.routes.ts`, la navegación lateral, `api/generated/`.
   - Docs: `CLAUDE.md` §7, `AKINE_IMPLEMENTATION_PLAN.md` (registros de cierre: **agregar al
     final**, nunca reescribir el de otro).
8. **Cada carril es dueño de sus módulos** (tabla §4). Si un paquete necesita cambiar un módulo de
   otro carril, se coordina con su dueño; en general lo correcto es pedir un método nuevo en el
   `spi` de ese módulo, no meterse adentro.
9. **Puertos locales.** Si dos personas levantan el backend en la misma máquina, cambiar
   `server.port` y el puerto de MySQL de `compose.yaml` por variable de entorno; los ITs usan
   Testcontainers y no chocan.
10. **Flujo por paquete:** el del `CLAUDE.md` (brainstorming → diseño → design challenge → TDD →
    `/simplify` → `verify` → `/code-review`). PR a `main`, revisado por **alguien de otro carril**.
    Al cerrar: tachar el ítem en la ficha `F*.md` **en el mismo PR**.

## 4. Carriles

Un carril es una línea de trabajo con módulos propios. Cada persona toma un carril (o dos chicos).

| Carril | Fases | Repo | Módulos / carpetas que le pertenecen |
|---|---|---|---|
| **A — Plataforma y operación** | F0–F1, F2 | api + web | `identity`, `organization`, `notification`, `resource`, `offering` · web: `auth`, `organization`, `platform`, `resource`, `offering`, `catalog` |
| **B — Personas y cobertura** | F3 | api + web | `person`, `contracting` · web: `person`, `contracting` |
| **C — Clínico backend** | F4, F6 | api | `clinical`, `encounter` |
| **D — Clínico frontend** | F4, F6 | web | `features/clinical` |
| **E — Agenda y recepción** | F5 (+ sondas de F2) | api + web | `scheduling` · web: `scheduling` |
| **F — Economía** | F7 | api + web | `billing` · web: `billing` |
| **G — Calidad y release** | F8 | los dos | `reporting`, `platform` (observabilidad), CI, Docker, k6 · web: a11y, dashboards |
| **L — Lead / decisiones** | transversal | — | Decisiones del usuario, ADR/DP, rama por defecto, revisión del orden de merge |

> Las **sondas de impacto de F2** van al carril **E** aunque la ficha sea F2: se implementan en
> `scheduling`, que es de E. El carril A consume el resultado en la UI.

## 5. Decisiones que bloquean — el carril L las resuelve primero

Ninguna se inventa en silencio: cada una sale como ADR o DP escrita (`AGENT.md` §2).

| # | Decisión | Desbloquea | Urgencia |
|---|---|---|---|
| ~~**DU-1**~~ | ~~**Puente Oferta↔Práctica**~~ → **resuelta: DP-11**, tabla N:M `oferta_practica` con práctica principal (06/10/2026) | A-9 → B-3, C-4, F-4. **Ruta crítica de la economía** | **ola 0** |
| ~~**DU-2**~~ | ~~Bootstrap del `PLATFORM_ADMIN`~~ → **resuelta: DP-14**, bootstrap por `AKINE_BOOTSTRAP_ADMIN_EMAIL` con enlace de activación (07/10/2026) | A-4 → A-7, B-catálogo global | ola 0 |
| ~~**DU-3**~~ | ~~Alcance de `reporte:read`~~ → **resuelta: DP-15**, completo según la matriz, PROFESIONAL solo su actividad (07/10/2026) | G-1 (arreglo rápido) | ola 0 |
| ~~**DU-4**~~ | ~~¿N tratamientos consumen N unidades o 1?~~ → **resuelta: DP-12**, una unidad por autorización involucrada (06/10/2026) | C-4, F-4 | ola 1 |
| **DU-5** | Unificar `concurrent-modification` y `conflict` | G-5 (hallazgo alto 07.07); cambia respuestas de varios módulos | ola 1 |
| **DU-6** | Alcance `OWN` / `paciente:read` (vínculo cuenta↔persona) | B-8, G-5; autoservicio de E | ola 1 |
| ~~**DU-7**~~ | ~~Reversión del consumo: automática o manual~~ → **resuelta: DP-13**, manual con alerta al anular la deuda (06/10/2026) | C-4 | ola 1 |
| **DU-8** | Historial de autorización: tabla propia o auditoría | B-4 | ola 1 |
| ~~**DU-9**~~ | ~~¿Máquina de recepción propia?~~ → **resuelta: DP-16**, sí, como DP-05; `EN_ESPERA` sale del turno (07/10/2026) | E-4 | ola 1 |
| **DU-10** | `egreso:manage` propio o `caja:operate`; adjunto binario del egreso (¿storage a `platform.spi`?) | F-5 | ola 2 |
| **DU-11** | Parámetros obligatorios por práctica; ¿un observador que falla debe hacer fallar el cierre? | C-8 | ola 2 |
| **DU-12** | ¿F9 espera al gate del MVP? (DP-07) y `clase:*` vs `turno:*` | F9 entera | después del gate |
| **DU-13** | Catálogo global de financiadores: el centro **referencia** la fila global (`owner_key` en `financiador`/`plan_cobertura`) o la **adopta** como copia (tabla global aparte). Recomendada la copia; opciones y costos en `docs/diseno/AKINE-A-7-plataforma.md` §3 | la parte de A-7 que no entró (F3) | ola 2 |

## 6. Migraciones reservadas

Se reserva **antes** de escribir código, editando esta tabla en un PR chico (o en el primer
commit del paquete). Si una fila resulta innecesaria, se marca "vacía" y el número no se reusa.

| Versión | Paquete | Contenido previsto |
|---|---|---|
| `V65` | C-1 | **Usada**: `V65__c1_tratamiento_lateralidad_no_aplica.sql` — recrea `ck_tratamiento_lateralidad` con `NO_APLICA` |
| `V66` | A-9 | **Vacía**: A-9 la reservó y quedó por debajo de migraciones ya mergeadas (Flyway corre sin `outOfOrder`); usó `V75`. No se reusa |
| `V67` | C-5 | **Vacía**: C-5 no la usó, porque RN-M10-002 admite varios casos activos y el unique sería un defecto. No se reusa |
| `V68` | B-4 | Historial de estados de autorización (si DU-8 = tabla). **Quedó por debajo de `V83`** (07/10): como Flyway corre sin `outOfOrder`, B-4 tiene que tomar la siguiente libre por encima de la última mergeada y dejar `V68` vacía |
| `V69` | F-3 | **Usada**: `V69__m19_anticipos_y_reintegro.sql` — saldo a favor y anulación en `cobro`, imputación posterior en `cobro_imputacion`, tabla `cobro_reintegro`, origen `REINTEGRO` en `movimiento_caja` |
| `V70` | E-3 | **Vacía**: E-3 la reservó y se renumeró a `V74` al integrar, porque `V71` (C-6) entró antes. No se reusa |
| `V71` | C-6 | **Usada**: `V71__c6_sesion_version_tratamientos_y_mediciones.sql` — tablas `sesion_version_tratamiento`, `sesion_version_tratamiento_parametro` y `sesion_version_medicion` (foto inmutable por version) con backfill desde el estado vivo |
| `V72` | F-4 | **Vacía**: F-4 la reservó y quedó por debajo de `V76` (C-4), ya mergeada; Flyway corre sin `outOfOrder`, así que usó `V77`. No se reusa |
| `V73` | E-5 | **Vacía / liberada**: E-5 la reservó y entró sin migración (`notification_outbox.tipo` es `VARCHAR(40)` sin CHECK). No se reusa |
| `V74` | E-3 | **Usada**: `V74__m12_serie_de_turnos.sql` (nació como `V70` y se renumeró al integrar: `V71` ya estaba en `main` y Flyway corre sin `outOfOrder`) — tabla `turno_serie` (regla semanal, idempotencia) y `turno.serie_id` nullable con `fk_turno_turno_serie` e `ix_turno_serie_inicio` |
| `V75` | A-9 | **Usada**: `V75__m27_oferta_practica.sql` — tabla `oferta_practica` (DP-11) con práctica principal sostenida por `uk_oferta_practica_principal` |
| `V76` | C-4 | **Usada**: `V76__c4_autorizacion_alerta_consumo_a_revisar.sql` — tabla `autorizacion_alerta` (DP-13) e índice `ix_movimiento_origen` en `autorizacion_movimiento` |
| `V77` | F-4 | **Usada**: `V77__f4_obligacion_del_financiador.sql` — `obligacion.concepto` (`PARTICULAR`/`FINANCIADOR`/`COSEGURO`), práctica y cobertura aplicadas, alerta DP-11 y el snapshot entero del convenio y del arancel (los tres importes y los requisitos de RF-M21-003), con cinco CHECK de coherencia |
| `V78` | E-4 | **Usada**: `V78__m13_recepcion_maquina_propia.sql` — tablas `recepcion` (una vigente por turno, `uk_recepcion_turno_vigente`) y `recepcion_evento` (append-only), migración de los turnos `EN_ESPERA` y de los cancelados con llegada, `ck_turno_estado` sin `EN_ESPERA`. No borra columnas de `turno` (ADR-0007) |
| `V79` | A-7 | **Vacía**: A-7 la tuvo asignada y entró sin migración (#53); después entraron `V80`–`V83`, así que ya no se puede usar. No se reusa |
| `V80` | B-3 | **Usada**: `V80__b3_arancel_por_oferta_y_precio_particular.sql` — `convenio_arancel.oferta_id` nullable (`fk_convenio_arancel_oferta`, `ix_convenio_arancel_oferta`; dueño `contracting`) y tabla `oferta_precio_particular` (precio particular por vigencia; dueño `offering`). Los dos no-solapamientos los hace cumplir un lock, ningún índice |
| `V81` | E-6 | **Usada**: `V81__e6_prepago_politica_y_anticipo_de_turno.sql` — `oferta_servicio_consultorio.exige_prepago` (política de prepago, DP-06) y `cobro.turno_id` con la columna generada `turno_prepago_vigente` y `uk_cobro_prepago_turno_vigente` (un solo prepago vigente por turno) |
| `V82` | E-8 | **Vacía**: E-8 la reservó por si el listado de series necesitaba índice y no hizo falta: el filtro por sede usa el índice de `fk_turno_serie_consultorio` (que en InnoDB ya ordena por `id`) y el estado derivado usa `ix_turno_serie_inicio`. No se reusa |
| `V83` | A-8 | **Usada**: `V83__m03_horario_general_de_sede.sql` — tabla `consultorio_horario` (dueño `resource`): franjas semanales del horario general de la sede, baja lógica al reemplazar, sin unique (el solapamiento lo valida la aplicación). `V82` es de otro paquete en paralelo |

## 7. Paquetes de trabajo

Tamaño: **S** ≈ 1–2 días · **M** ≈ 3–5 días · **L** ≈ 1–2 semanas. Son órdenes de magnitud para
repartir, no compromisos. "Dep." son paquetes que tienen que estar en `main` antes de empezar.

### Ola 0 — arranque (cualquiera puede tomarlos; son chicos y desbloquean)

| ID | Qué | Repo | Tam. | Dep. | Ficha |
|---|---|---|---|---|---|
| L-0 | Cambiar la rama por defecto de GitHub a `main` en los dos repos; protección de rama | — | S | — | F0-F1 |
| L-1 | Sesión de decisiones DU-1, DU-2, DU-3 → ADR/DP | docs | S | — | 00-orden |
| ~~C-1~~ | ~~Migración `V65`: CHECK de lateralidad con `NO_APLICA`, IT que lo reproduce primero~~ — hecho (`V65`, antes del 06/10) | api | S | — | F6 |
| ~~G-1~~ | ~~Otorgar `reporte:read` según DU-3, con test~~ — hecho (DP-15, 07/10): todos los roles según la matriz, `PROFESIONAL` con alcance `ACTIVIDAD_PROPIA`, `PLATFORM_ADMIN` con soporte; sin migración ni cambio de contrato | api | S | DU-3 | F8 |
| ~~F-1~~ | ~~Verificar y arreglar `cuentaCorriente` (filtro por sede + paginación fija)~~ — hecho (#14, 06/10) | api | S | — | F7 |
| ~~G-2~~ | ~~Verificar y arreglar `sumarAnuladoEnElReporte` sin `deletedAt`~~ — hecho (#13, 06/10) | api | S | — | F8 |
| ~~A-2~~ | ~~ADR que ratifica `encounter` como dueño de la Sesión; `AGENT.md` §4~~ — hecho (ADR-0024, antes del 06/10) | docs | S | — | F0-F1 |

### Ola 1 — sin dependencias pendientes: arrancan todos a la vez

| ID | Qué | Repo | Tam. | Dep. | Ficha |
|---|---|---|---|---|---|
| ~~**A-3**~~ | ~~`POST /notifications/{id}/retry` con permiso administrativo y filtro por tenant~~ — hecho (#15, contrato 0.47.0, 06/10) | api | S | — | F0-F1 |
| ~~A-5~~ | ~~Slice tests de `MembershipController`, `AuditEventController` y plataforma; unitarios de los 4 servicios de F2~~ — hecho (#25 slices, #26 unitarios de F2, 06/10) | api | M | — | F0-F1, F2 |
| A-6 | E2E: crear organización; registro → activación → login → reset con canje real; acceso denegado y auditoría | web | M | — | F0-F1 |
| ~~A-8~~ | ~~CA-M03-002: primer box + horario general en el alta de sede~~ — **hecho** (07/10): backend #60 (`V83`, contrato 0.69.0): `POST /organizations/{orgId}/consultorios` acepta `primerBox` y `horarioGeneral` opcionales y los crea en la transacción del alta vía `organization.spi.AltaDeSedeExtension` (implementada en `resource`); si algo falla no queda nada. El horario general vive con el calendario de la sede (`GET`/`PUT /consultorios/{id}/calendario`), es informativo y la agenda no lo lee (RN-M03-004). Pantalla en web #22. El onboarding compuesto de la primera sede sigue sin box ni horario | api + web | M | — | F2 |
| ~~**B-1**~~ | ~~Búsqueda por número de afiliado (RF-M07-001)~~ — hecho (contrato 0.46.0, antes del 06/10) | api + web | S | — | F3 |
| ~~**B-2**~~ | ~~`spi` "cobertura vigente de esta persona para esta práctica en esta fecha"~~ — hecho (`cfa85d8`, antes del 06/10) | api | M | — | F3 |
| ~~B-5~~ | ~~Contribuyente de cobertura al Paciente 360~~ — **hecho**: backend #34 (06/10, sin cambio de contrato), pantalla web #16 (07/10) | api + web | S | — | F3 |
| B-6 | E2E persona → paciente → cobertura → orden → autorización → vencimiento; `AdjuntoConcurrenteIT` | web + api | M | — | F3 |
| ~~**C-2**~~ | ~~`HistoriaClinicaController`: obtener-o-abrir idempotente por persona, resumen, antecedentes~~ — hecho (contrato 0.45.0, antes del 06/10) | api | M | — | F4 |
| ~~**C-3**~~ | ~~`RelacionAsistencialProbe` real sobre turnos y sesiones (implementación fuera de `clinical`, vía `spi`)~~ — hecho (antes del 06/10) | api | M | — | F4 |
| ~~C-5~~ | ~~04.03 unique de caso activo (`V67`); 04.04 el plan no se activa sin ítems~~ — hecho sin `V67`: RN-M10-002 admite varios casos activos (antes del 06/10) | api | S | — | F4 |
| ~~C-7~~ | ~~ITs de tratamientos (39–43), mediciones y enmiendas; auditoría `SESION_AMENDED`~~ — hecho (antes del 06/10) | api | M | C-1 | F6 |
| **D-a** | Timeline de la HC (cursor, entradas, adjuntos con preview seguro) + E2E | web | L | — | F4 |
| **D-b** | Casos: listado, alta, detalle, cierre, reapertura, equipo + E2E | web | L | — | F4 |
| D-c | Plan de tratamiento: editor, versiones, avance + E2E | web | L | — | F4 |
| D-d | Mediciones y tratamientos realizados en `atencion-page` + E2E | web | M | C-1 | F6 |
| ~~**E-1**~~ | ~~Implementar las 4 sondas de impacto de F2 contra turnos reales, con ITs que devuelvan 409~~ — hecho (#38, 06/10) | api | M | — | F2 |
| E-2 | Reescribir los E2E de agenda contra backend real; E2E de ciclo y recepción; ~~IT de `ReservaProbeSobreTurnos`~~ (hecho: `AgendaDescuentaReservasIT`, #22, 06/10) | web + api | M | — | F5 |
| ~~E-5~~ | ~~Notificaciones de reserva, cancelación y reprogramación por outbox (RF-M26-002/003)~~ — hecho (#37, 06/10) | api | M | — | F5 |
| ~~F-2~~ | ~~Defectos de `billing` reportados el 01/10 (`debitar`, advice propio, CSV, `FINANCIADOR_DISTINTO` → cambio de contrato)~~ — hecho (#16, #17, #18 con contrato 0.48.0, 06/10) | api | M | — | F7 |
| ~~**F-3**~~ | ~~Anticipos, imputación posterior, anulación y reintegro (`V69`)~~ — hecho en el backend (#39, contrato 0.54.0, 06/10); sin pantalla | api | L | — | F7 |
| ~~F-6~~ | ~~Pantallas de caja diaria + E2E cierre → deuda → cobro → caja~~ — **pantalla hecha** (web #7, 07/10): `/caja` con apertura, movimientos, reversión de manuales, arqueo y cierre. **El E2E no se escribió** | web | L | — | F7 |
| ~~F-7~~ | ~~Pantallas de presentaciones (bandejas, armado, factura, débito, pagos)~~ — **hecho** (web #9, 07/10): `/presentaciones` y `/presentaciones/:id` con el ciclo completo del lote. Sin E2E | web | L | — | F7 |
| F-8 | ITs diferidos 33–48 (caja concurrente, presentaciones, egresos) — **cerrado** (07/10): corridos 33, 34 (#28), 37, 39, 40 (#30), 41, 42, 45, 46, 48 (#24), 43, 47 (#19) y 35, 36, 38, 44 (`akine-F-8-diferidos-finales`). El 38 destapó que el débito no persistía la marca del ítem (`clearAutomatically` lo desacoplaba) | api | M | — | F7 |
| ~~G-3~~ | ~~Dockerfile de los dos repos + SBOM~~ — **hecho**: backend #29 (06/10), frontend web #18 (07/10: nginx no root, proxy `/api` a `AKINE_API_URL` en runtime, SBOM CycloneDX y job `imagen` en el CI; `docs/imagen-docker.md`) | los dos | M | — | F8 |
| ~~**G-4**~~ | ~~Observabilidad: logging JSON con correlación, Micrometer/Prometheus, OpenTelemetry~~ — hecho (`akine-G-4-observabilidad`, 08/10): log JSON `logstash` fuera de `local`, `X-Request-Id` + `traceId`/`spanId`/tenant en el MDC, `/actuator/prometheus` con histograma y buckets de SLO detrás de token de scraper, trazas OTLP opt-in. Ver `docs/observabilidad.md` | api | L | — | F8 |
| G-6 | Sonar, Dependabot/OWASP, reglas JaCoCo `PACKAGE` | los dos | S | L-0 | F0-F1, F8 |
| G-7 | a11y: WCAG 1.4.11, `:hover`/`:focus-visible`, foco por teclado | web | M | — | F8 |
| ~~G-8~~ | ~~Dashboards de reportes en el frontend~~ — **hecho** (web #12, 07/10): `/reportes` con fuente y criterio de fecha por indicador, secciones omitidas y CSV. Destapó la colisión de schemas que corrigió #58 (contrato 0.67.0) | web | M | G-1 | F8 |

### Ola 2 — necesitan una decisión o un cimiento de la ola 1

| ID | Qué | Repo | Tam. | Dep. | Ficha |
|---|---|---|---|---|---|
| ~~**A-9**~~ | ~~**Puente Oferta↔Práctica**~~ — **backend hecho** (`V75`, contrato 0.58.0, 06/10): tabla `oferta_practica`, `GET`/`PUT …/ofertas/{ofertaId}/practicas` y `offering.spi.PracticasDeOfertaDirectory` para C-4 y F-4; falta la pantalla | api + web | M | DU-1 | F2 |
| ~~A-4~~ | ~~Bootstrap del `PLATFORM_ADMIN`~~ — **hecho** (07/10, DP-14): `AKINE_BOOTSTRAP_ADMIN_EMAIL` re-apunta la cuenta de `V15` y le encola el enlace de activación al arrancar; sin migración ni cambio de contrato (`docs/diseno/AKINE-A-4-bootstrap.md`) | api | M | DU-2 | F0-F1 |
| A-7 | Endpoint "¿tengo rol de plataforma?" + consola de solicitudes de catálogo; catálogo global de financiadores — **hecho salvo el catálogo de financiadores** (07/10): backend #53 (contrato 0.64.0, sin migración): `GET /me/platform-role` y aprobar una solicitud publica el concepto global; consola `/plataforma/solicitudes` en web #13. El catálogo global de financiadores quedó **diseñado y no implementado**: espera DU-13 (`docs/diseno/AKINE-A-7-plataforma.md`) | api + web | M | A-4 | F2, F3 |
| ~~A-10~~ | ~~UI de impacto de desvinculación y preview de turnos al editar disponibilidad~~ — **hecho** (web #17, 07/10). Límite: para disponibilidad no hay consulta previa en el contrato; `turnosAfectados` llega en la respuesta del PUT/DELETE y es cota superior | web | M | E-1 | F2 |
| A-11 | Impacto previo al editar disponibilidad y coberturas del 360 como campos — **backend hecho** (contrato 0.70.0, 08/10, sin migración): consultas previas sin efectos `POST …/disponibilidad/{bloqueId}/impacto-de-edicion`, `GET …/{bloqueId}/impacto-de-baja`, `POST /consultorios/{id}/excepciones/impacto-de-alta` y `GET …/excepciones/{id}/impacto-de-baja`, con la lista mínima de turnos; el impacto de bloques pasa de cota superior a cuenta exacta (cierra el límite (a) de la decisión pendiente 8). Los hitos de la sección `coberturas` del 360 traen `cobertura` con financiador, plan, afiliado enmascarado, vigencia como fechas, principal y estado de credencial (`docs/diseno/AKINE-A-11-impacto-previo-y-360.md`); falta la pantalla (A-10, B-5 web) | api | M | E-1, B-5 | F2, F3 |
| ~~B-3~~ | ~~Cobertura aplicable por oferta (RF-M08-006/007); arancel por oferta; importación masiva de convenio~~ — **hecho salvo la importación masiva** (07/10): backend #55 (`V80`, contrato 0.65.0): `GET /personas/{id}/cobertura-aplicable`, arancel por oferta (RF-M16-008) y precio particular por vigencia (RF-M16-009); pantallas en web #15. La importación masiva (RF-M16-007) quedó diseñada y fuera (`docs/diseno/AKINE-B-3-cobertura-por-oferta.md`) | api + web | L | A-9 | F3 |
| B-4 | Historial de estados de autorización (`V68`); estado de la orden médica | api | M | DU-8 | F3 |
| ~~C-4~~ | ~~04.05: `RESERVA`/`LIBERACION_DE_RESERVA`, reversión del consumo, validar cobertura y caso~~ — hecho en el backend (`V76`, contrato 0.59.0): DP-12 una unidad por autorización involucrada, DP-13 alerta "consumo a revisar" al anular la deuda, cobertura vigente y caso al consumir. `RESERVA` queda afuera: ningún RF dice cuándo se reserva (`docs/diseno/AKINE-C-4-consumo.md`) | api | M | DU-4, DU-7, B-2 | F4 |
| C-6 | Enmiendas que versionan tratamientos y mediciones (`V71`) + permiso reforzado; `X-Justificacion-Acceso` en `encounter` | api | M | C-7 | F6 |
| C-8 | 06.01 atención sin turno y reemplazo del profesional; 06.03 subbloques ROM/fuerza/marcha | api | L | DU-11 | F6 |
| D-e | Entrada a la HC desde el Paciente 360 + E2E de acceso clínico permitido/denegado | web | M | C-2 | F4 |
| D-f | Selector explicable de autorización, saldo y alertas | web | M | C-4 | F4 |
| D-g | Enmiendas en la UI + E2E sesión rápida y cierre → timeline | web | M | C-6 | F6 |
| ~~**E-3**~~ | ~~Modelo de serie de turnos (`V70`): diseño con design challenge, comandos de alcance, UI de confirmación~~ — **hecho** (06/10 y 07/10): backend #44 (`V74`, renumerada desde `V70`; contrato 0.57.0), pantallas de alta con previsualización, detalle y cancelación con alcance en web #10. La reprogramación con alcance existe en el contrato y no tiene pantalla | api + web | L | — | F5 |
| ~~E-4~~ | ~~Recepción con elegibilidad administrativa y camino "Particular"~~ — **hecho** (07/10): backend #52 (`V78`, contrato 0.63.0, DP-16): `Recepcion` con máquina propia, validación por `person.spi.ElegibilidadAdministrativaDirectory`, Particular con motivo; recepción del día sobre la máquina nueva en web #8 | api + web | M | B-2, DU-9 | F5 |
| F-5 | Egresos y pagos a profesionales (backend pendiente + pantallas) | api + web | M | DU-10 | F7 |
| G-5 | Hallazgos altos de 07.07: `OWN`, unificar errores, threat model, matriz de permisos con tests | api | L | DU-5, DU-6 | F8 |
| G-9 | Job E2E del CI del frontend contra el backend | web | M | G-3 | F0-F1, F8 |

### Ola 3 — convergencia: tocan varios carriles

| ID | Qué | Repo | Tam. | Dep. | Ficha |
|---|---|---|---|---|---|
| ~~**F-4**~~ | ~~**Obligación del financiador**~~ — **backend hecho** (`V77`, contrato 0.60.0, 07/10): el cierre de una sesión cubierta por un convenio devenga la parte del financiador y el coseguro con el snapshot del arancel; la bandeja de 07.04 y el reporte de 07.06 tienen datos. Diseño en `docs/diseno/AKINE-F-4-obligacion-financiador.md`; falta la pantalla | api + web | L | A-9, B-2, C-4 | F7 |
| C-9 | Gate RF-M10-007: caso obligatorio al reservar/atender, con ventana de migración | api + web | L | C-2, D-b, E-3 | F4, F5, F6 |
| ~~E-6~~ | ~~Prepago de recepción como anticipo~~ — **hecho** (07/10): backend #54 (`V81`, contrato 0.66.0): política `exigePrepago` en la oferta, `Recepcion.prepago` como alerta sin bloqueo, cobro con `turnoId` como anticipo de F-3 e imputación automática a la deuda del paciente después del commit del cierre (`docs/diseno/AKINE-E-6-prepago.md`); pantallas de oferta, recepción y registro de cobro en web #14 | api + web | M | E-4, F-3 | F5 |
| ~~**E-7**~~ | ~~"Atender como Particular" llega a la obligación~~ — **hecho** (#56, 07/10, sin migración ni cambio de contrato, RF-M08-007): la recepción resuelta como Particular viaja en `SesionCerrada.particularPorRecepcion` (leída por `scheduling.spi.TurnoDirectory#atendidoComoParticular`); el cierre devenga sólo `PARTICULAR` al precio del día y no consume autorización. Diseño en `docs/diseno/AKINE-E-7-particular-en-obligacion.md` | api | S | E-4, B-3, F-4, E-6 | F5, F7 |
| ~~E-8~~ | ~~Bandeja de series y prepago antes del check-in~~ — **hecho** (07/10): backend #59 (sin migración, `V82` vacía, contrato 0.68.0): `GET /consultorios/{c}/series-de-turnos` (`listarSeriesDeTurnos`, `turno:read`) paginado, filtrable por persona y por estado **derivado** (`VIGENTE`/`FINALIZADA`), y `TurnoDelDia.prepago` calculado al leer también sin recepción (`docs/diseno/AKINE-E-8-series-y-prepago.md`). Pantallas: bandeja `/agenda/series` en web #20 y prepago antes del check-in en web #21 | api + web | S | E-3, E-6 | F5 |
| G-10 | Carga con k6 contra los SLO de ADR-0016; LCP del frontend | los dos | M | G-4 | F8 |
| G-11 | Cobertura de vuelta a 0,80 (cada carril sube la de sus módulos; G vigila el gate) — **avance** (06/10): unitarios de `activity` (#31) y `encounter` (#32) | api | M | ola 2 | F8 |

### Ola 4 — gate del MVP (todo el equipo)

| ID | Qué | Dep. |
|---|---|---|
| G-12 | QA manual del §6 del `CLAUDE.md`, fase por fase, contra la DB | olas 1–3 de cada fase |
| G-13 | Backup/restore, rollback, smoke post-deploy, release notes, runbooks | G-3, G-4 |
| G-14 | Aprobación clínica y legal (DP-08); deploy y ventana de estabilización (DP-07) | todo |
| L-2 | Decisión DU-12 → abrir F9 (o no) | G-14 |

## 8. La ruta crítica

El camino más largo, y el que conviene que nunca espere:

```
DU-1 ─▶ A-9 (puente Oferta↔Práctica) ─┐
B-2 (spi cobertura vigente) ─────────┼─▶ C-4 (consumo) ─▶ F-4 (obligación del financiador) ─▶ G-12 QA ─▶ gate MVP
DU-4 / DU-7 ───────────────────────────┘
```

Sin F-4, la bandeja de presentaciones devuelve lista vacía y el reporte de financiadores da cero:
**es el hueco funcional más grave del MVP**. Por eso DU-1 va en la ola 0 aunque A-9 se implemente en
la ola 2, y B-2 arranca el primer día.

> **Actualizado el 07/10/2026:** F-4 entró en el backend (`V77`, contrato 0.60.0). La bandeja de
> elegibles y la sección de financiadores del reporte tienen datos reales por primera vez
> (`ObligacionDelFinanciadorIT`). Lo que queda de la ruta crítica es la pantalla y G-12.

## 9. Grupos de trabajo: cómo repartirlo según cuánta gente haya

Los paquetes del §7 se juntan en **nueve grupos**. Cada grupo tiene sus módulos, sus migraciones
y un orden interno, así que **lo toma una sola persona a la vez**: partirlo entre dos haría que dos
personas toquen los mismos módulos y las mismas migraciones. Con más gente se toman más grupos en
paralelo; con menos, una persona toma varios. El diagrama dibuja una fila por grupo.

Carga ≈ días-persona con S ≈ 2, M ≈ 4 y L ≈ 8. Es un orden de magnitud para combinar grupos, no un
compromiso.

| Grupo | Qué cubre | Repo · migraciones | Tramo 1 | Tramo 2 | Tramo 3 | Carga |
|---|---|---|---|---|---|---|
| **G1 · Cobertura + ruta crítica** | person, contracting, devengo | api · V66 V68 V72 | B-1 · **B-2** | **C-4** (espera DU-4, DU-7) · **A-9** (espera DU-1) · B-4 (espera DU-8) | **F-4** (espera A-9, C-4) · B-3 | ≈ 34 |
| **G2 · Clínico backend** | clinical, encounter | api · V65 V67 V71 | C-1 · C-2 (**mergear rápido**: lo espera G3) · C-3 · C-5 · C-7 · A-2 | C-6 | C-8 (espera DU-11) · C-9 (espera D-b, E-3) | ≈ 38 |
| **G3 · Historia clínica en pantalla** | web clínico (F4) | web | D-a · D-b · D-c | D-e (espera C-2) | D-f (espera C-4) | ≈ 32 |
| **G4 · Atención y caja en pantalla** | web clínico (F6) y billing | web | F-6 · F-7 | D-d (espera C-1) | D-g (espera C-6) | ≈ 24 |
| **G5 · Agenda y recepción** | scheduling + sondas de F2 | api + web · V70 | E-1 · E-2 | E-4 (espera B-2, DU-9) · E-3 · E-5 | E-6 (espera E-4, F-3) | ≈ 28 |
| **G6 · Economía backend** | billing | api · V69 | F-1 · F-2 · F-3 | F-8 | F-5 (espera DU-10) | ≈ 22 |
| **G7 · Plataforma y operación** | identity, organization, resource | api + web | A-3 · A-5 · B-5 | A-8 | A-4 (espera DU-2) · A-7 · A-10 (espera E-1) | ≈ 24 |
| **G8 · CI, release y reportes** | reporting, platform, CI, Docker | api + web | G-1 (espera DU-3) · G-2 · G-3 · G-6 | G-4 · G-9 (espera G-3) | — | ≈ 22 |
| **G9 · E2E, a11y y dashboards** | web transversal | web | A-6 · B-6 | G-7 · G-8 (espera G-1) | — | ≈ 16 |

**Cierre del MVP, todos juntos**, cuando los grupos terminaron su tramo 3: G-5 hallazgos altos
(espera DU-5, DU-6), G-10 carga (espera G-4), G-11 cobertura 0,80, **G-12 QA manual**, G-13
backup/rollback y G-14 aprobación y deploy → **gate del MVP** → DU-12 (¿abrir F9?).

**Decisiones:** las toma una sola persona, la que decide producto. **DU-1, DU-2 y DU-3 el día 1**,
junto con L-0 (rama por defecto a `main`). DU-4, DU-7, DU-8 y DU-9 durante el tramo 1, porque las
necesita el tramo 2. El resto, antes del tramo que las necesita.

### 9.1 Cómo combinar los grupos

| Personas | Grupos por persona |
|---|---|
| **9** | Uno cada uno: G1 … G9 |
| **6** | G1 · G2 · G3 · G4+G9 · G5+G7 · G6+G8 |
| **5** | G1+G8 · G2 · G3+G9 · G4+G6 · G5+G7 |
| **4** | G1+G8 · G2+G7 · G3+G4+G9 · G5+G6 |
| **3** | G1+G2 · G3+G4+G9 · G5+G6+G7+G8 |
| **10+** | Sumar gente a G2 y G3 (los más largos), partiéndolos por paquete con acuerdo explícito de quién toca qué módulo, o al cierre |

Reglas del reparto:

- **G1 es la ruta crítica.** Quien lo tenga no suma otro grupo pesado hasta que F-4 esté en `main`
  (G8, que es liviano y arranca con arreglos rápidos, sí entra en sus huecos del tramo 1).
- **Al combinar, juntar grupos del mismo repo:** menos cambio de contexto y menos setup.
- **Quien termina su grupo toma un grupo entero libre**, o ayuda en el cierre. No se adelanta a un
  tramo posterior de otro grupo.
- **Cada grupo anota su dueño** en el diagrama y pinta la casilla de cada paquete al mergear.

## 10. Prompt para abrir el chat de un paquete

```text
Vamos a trabajar el paquete <ID> de AKINE (grupo <Gn>). Leé AGENT.md, CLAUDE.md,
docs/fases/01-trabajo-en-paralelo.md (reglas §3, tu paquete §7, tu grupo §9) y la ficha
docs/fases/<ficha>.md. Verificá contra el código que el faltante siga vigente y que sus
dependencias estén en main. Estoy en el worktree <ruta>, rama akine-<ID>-<slug>; la migración
reservada es <Vnn | ninguna>. Antes de proponer código, decime qué módulos vas a tocar y si
alguno es de otro grupo.
```

## 11. Mantener esto vivo

- Quien toma un grupo pone su nombre como dueño en el diagrama (y en el PR de cada paquete).
- Quien lo cierra lo tacha acá **y** en la ficha de la fase, en el mismo PR.
- Si aparece trabajo nuevo, se agrega como paquete con su dependencia; no se mete "de paso" en otro.
- El diagrama se actualiza cuando cambia una ola o la ruta crítica, no por cada paquete cerrado.
