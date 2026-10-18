# Decisiones pendientes del usuario — lista única

> Consolidada el **13/10/2026** a partir de los "Pendiente del usuario" de los registros de cierre
> del 12–13/10 de cada ficha de fase (`F0-F1` … `F8`; la de F7 entró con el PR #108 el
> 13/10). **No se inventó ninguna opción:** la pregunta, la opción recomendada y lo que destraba
> salen de la ficha citada. Donde la ficha no recomienda nada, se dice.
>
> Deduplicada: si dos fichas dejan la misma decisión, va una vez con las dos fuentes. El número
> entre paréntesis "CLAUDE §7 n.º X" es el de la lista "Decisiones pendientes del usuario" de
> `CLAUDE.md` §7, que queda como historia de cómo se abrió cada una.
>
> **Orden:** por lo que cuesta postergarla. Primero lo que bloquea el gate del MVP (07.09, DP-07),
> después lo que se encarece con el tiempo (migraciones, exposición de datos, cambios
> incompatibles), después alcance del MVP aditivo y, al final, lo que no bloquea nada.
>
> Los identificadores `U-NN` son de esta lista y no se reusan; no confundir con los paquetes
> (`A-9`, `C-9`, `B-7`…) de `01-trabajo-en-paralelo.md`.
>
> **Quien resuelva una:** escribirla como DP en `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` §7,
> tacharla acá con el número de DP y la fecha, y tacharla en la ficha de la fase.

## Resumen

> **Actualizado el 17/10/2026.** El usuario aceptó en bloque las recomendaciones de F9/F10: **7
> resueltas** (U-39, U-40, U-42, U-44, U-46, U-48, U-49 → DP-24 a DP-30) y **4 postergadas** después
> del release candidate (U-41, U-43, U-45, U-50 → DP-31). El mismo día, más tarde, **3 resueltas**
> más del bloque A: **U-03** —junto con U-46 (e)— → DP-33, **U-05** → DP-34 y **U-06** → DP-35.
> U-47 no existe. El **18/10/2026** el usuario aceptó las recomendaciones de **U-11** y **U-28**
> → DP-37 (api #122) y de **U-04, U-07 y U-08** → DP-36 (api #123). El **18/10/2026**, más tarde,
> el usuario resolvió las 4 postergadas —**U-41 → DP-38, U-50 → DP-39, U-43 y U-45 → DP-40**— y
> **U-37 → DP-41**. Quedan **29 abiertas**, ninguna postergada.

| Bloque | Abiertas | Resueltas el 17/10 y el 18/10 |
|---|---|---|
| A. Bloquean el gate del MVP (U-01 a U-06) | 2 (2 acciones) | U-03, U-05, U-06; U-04 (18/10) |
| B. Se encarecen al postergarlas (U-07 a U-14, U-38) | 6 | U-39, U-42, U-46, U-48, U-49; U-07, U-08, U-11 (18/10) |
| C. Alcance del MVP, aditivas (U-15 a U-34) | 19 | U-28 (18/10) |
| D. No bloquean nada (U-35 a U-37) + ex postergadas U-41, U-43, U-45, U-50 | 2 | U-40, U-44; U-37, U-41, U-43, U-45, U-50 (18/10) |
| **Total** | **29** (de 49) | **20** |

### Resueltas el 18/10/2026

| U | Resolución | DP |
|---|---|---|
| U-41 | honorarios por clase: importe por participante presente, declarado en la oferta con vigencia; varios instructores, partes iguales; borrador por período que el administrador confirma como egreso, con snapshot. | DP-38 |
| U-50 | el egreso gana oferta/servicio opcional (los de honorarios ya ligados); rentabilidad = ingresos − egresos imputados; el cobro de varias deudas se reparte en caja proporcional a lo imputado. | DP-39 |
| U-43 | el período renovado sigue a nombre de quien activó la renovación; si se desvincula, la renovación se apaga sola y se avisa al administrador. | DP-40 |
| U-45 | período no calendario anclado al día de la primera venta (último día del mes si no existe), sin correrse. | DP-40 |
| U-37 | Angular 22 y Java 25 después del despliegue del RC, cuando esté estable. | DP-41 |
| U-04 | `caso:create` absorbido en `hc:write`: la matriz se enmienda y el código sale del catálogo. Sin cambio de respuestas. | DP-36 |
| U-07 | `convenio:read` solo para el personal (como DP-22); el `PACIENTE` recibe 403 en las lecturas de `contracting`. | DP-36 |
| U-08 | `catalogo:read` / `catalogo:manage` como en la matriz §11.1; el `PACIENTE` recibe 403 al leer el catálogo. | DP-36 |
| U-11 | la conciliación salda lo cobrado (presentado − debitado) y deja `PENDIENTE` sólo lo debitado, re-presentable; estados `DEBITADO_PARCIAL` y `ACEPTADO_PARCIAL`, migración `V110`. | DP-37 |
| U-28 | orden y autorización faltantes cuando el convenio las exige → **reparo** (`SIN_ORDEN`, `SIN_AUTORIZACION`, bloquean confirmar); credencial → **aviso** (`CREDENCIAL_FALTANTE` en `avisos`). | DP-37 |

### Resueltas el 17/10/2026

| U | Resolución | DP |
|---|---|---|
| U-39 | (a) con la asistencia, sin no-show por defecto; (b) en `POR_PACK`, solo se informa. | DP-24 |
| U-40 | las cinco quedan como están. | DP-25 |
| U-42 | (a): solo por convenio; sin cobertura, nada particular. | DP-26 |
| U-44 | por despliegue. | DP-27 |
| U-46 | (a), (b), (f) como están; (c) el PROFESIONAL no ve `paseId`/`saldo`; (d) el título no viaja en el correo; (e) resuelta con U-03. | DP-28, DP-33 |
| U-03 | la propiedad de la Sesión (y del panel grupal) **es** la relación asistencial de DP-03; sin `X-Justificacion-Acceso` en `encounter`, `reason` nulo documentado. | DP-33 |
| U-05 | Connector/J **9.6.0** (no hay 9.x corregida; 9.6.0 sin CVE en el NVD); fuera la excepción. Web: `overrides` `basic-ftp@^6`. | DP-34 |
| U-06 | SAST **CodeQL** y DAST **ZAP baseline**, no bloqueantes (api #119, web #77); Sonar sigue salteándose sin secretos. | DP-35 |
| U-48 | el lock solo en abonos; los packs resuelven la clave por el unique. | DP-29 |
| U-49 | (a) hecha en #152; (c) preparada y **apagada por defecto** (`lotes-concurrentes-por-sede`), se enciende solo si el entorno lo exige; (b) no. | DP-30 |

### Postergadas después del RC

Ninguna: el 18/10/2026 las cuatro (U-41, U-43, U-45, U-50) pasaron a resueltas (DP-38 a DP-40). DP-31 las había postergado el 17/10/2026.

> **Revisado el 16/10/2026** (etapa 09.04): U-39 (#129), U-40 (#135), U-41 (#139) y U-42 (#141)
> estaban; de las cuatro "Decisiones a confirmar" de #140 (08.08), la primera —renovar un abono
> impago— ya vivía como U-40 (b) y las otras tres entran como **U-43, U-44 y U-45**. Seis tenían una
> propuesta en borrador: U-06 (api #119), U-11 y U-28 (api #122) y U-04, U-07 y U-08 (api #123).

Ya resueltas y fuera de la lista (las fichas las mencionan): el **escenario 7b** (`request_hash`
en `onboarding_registro`), que la ficha de F0–F1 daba abierto y cerró el PR #104 el 13/10/2026
(`V87`); el **alcance `OWN` / autoservicio del paciente**, decidido post-MVP por DP-22 (F3 n.º 5,
F5 n.º 1) — de él dependen U-29 y U-30, que por eso no se pueden ejercer hasta que exista autoservicio.

---

## A. Bloquean el gate del MVP

Sin estas, F8 no cierra y F9 no se abre (DP-07). Fuente principal: F8, "Lo que bloquea el gate y
depende del usuario".

### U-01 · Correr el QA manual del §6 *(acción, no decisión)*

- **Pregunta:** ¿cuándo se ejecuta la guía de `docs/qa/` y quién la corre?
- **Recomendado:** correr los doce circuitos (`docs/qa/01` … `12`), registrando cada corrida en
  `docs/qa/corridas/AAAA-MM-DD.md` como pide `docs/qa/README.md` (no existe ninguna todavía, F3).
  Las fichas señalan las guías de su tramo: F2 → `02`, `03` y la disponibilidad de `05`; F3 → `04`;
  F5 → `05` (AGE-01 a AGE-14); F7 → `07`, `08` (con PRE-10) y `12`.
- **Fase / RF:** todas (F0–F1, F2, F3, F5, F6, F7, F8 — 07.09). Bloqueante para deploy desde 02.02.
- **Destraba:** el criterio "QA manual corrido" del gate y el deploy.

### U-02 · Aprobación clínica y legal, deploy y ventana de estabilización *(acción)*

- **Pregunta:** ¿quién aprueba (DP-08) y cuándo se despliega con su ventana de estabilización
  (DP-07, G-14)?
- **Recomendado:** la ficha no fija fecha ni responsable; los artefactos están (imagen Docker,
  backup/restore, rollback, smoke, runbooks, release notes — G-13, api #77).
- **Fase / RF:** F8 — 07.09, DP-07, DP-08.
- **Destraba:** el cierre de F8 y, con eso, la decisión de abrir F9 (L-2 / DU-12).

### ~~U-03 · Justificación de acceso en `encounter`~~ — RESUELTA el 17/10/2026 (DP-33)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): la propiedad de la Sesión
> —y la del panel de atención grupal— es la relación asistencial que pide DP-03. `encounter` no
> exige `X-Justificacion-Acceso`; `reason` queda nulo y documentado; la auditoría existente se
> mantiene. Cierra también U-46 (e). Sin cambio de contrato. Ver DP-33.

- **Pregunta:** ¿`X-Justificacion-Acceso` obligatorio en las operaciones de Sesión, opcional y
  auditado, o se declara que la propiedad de la sesión ya es relación asistencial?
- **Recomendado:** declarar que la propiedad de la sesión es la relación asistencial que pide
  DP-03 y dejar `reason` nulo, documentándolo (F6 n.º 6; G-5 opción (c) para el dueño, (a) sólo si
  se abre la lectura de sesiones ajenas).
- **Fase / RF:** F8 (07.07, hallazgo alto) y F6. CLAUDE §7 n.º 21.
- **Destraba:** el criterio "cero hallazgos altos" de 07.07. Las opciones (a) y (b) cambian el
  contrato.

### ~~U-04 · `caso:create`~~ — RESUELTA el 18/10/2026 (DP-36)

> **Resuelta el 18/10/2026** por el usuario (acepta la recomendación) e implementada en api #123:
> `caso:create` absorbido en `hc:write` y retirado del catálogo; matriz §18.3. Sin cambio de
> respuestas. Ver DP-36.

- **Pregunta:** ¿se cablea como permiso propio o se absorbe en `hc:write` enmendando la matriz?
- **Recomendado:** enmendar la matriz y absorberlo en `hc:write` (F4 n.º 3; G-5 opción (b), "la
  más barata"; desde G-5 las dos dan el mismo resultado observable).
- **Fase / RF:** F8 (07.07) y F4 (04.03). CLAUDE §7 n.º 22.
- **Destraba:** "cero hallazgos altos" de 07.07.

### ~~U-05 · `mysql-connector-j` 9.7.0 con CVE sin corrección — vence el 09/11/2026~~ — RESUELTA el 17/10/2026 (DP-34)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación) e implementada: Maven
> Central no tiene 9.x posterior a 9.7.0 (sólo la línea 26.7.0); el NVD no registra CVE para
> 9.6.0, así que `mysql.version` baja a **9.6.0** y sale la excepción de
> `dependency-check-suppressions.xml`. En el frontend, `overrides` `basic-ftp@^6`. Ver DP-34.

- **Pregunta:** ¿esperar la versión corregida o bajar a 9.6.0? Y en el frontend, ¿se aplica el
  `overrides` de `basic-ftp@^6` (dependencia de desarrollo, cruza una mayor)?
- **Recomendado:** la ficha no recomienda entre las dos; sí fija el plazo: decidir **antes del
  09/11/2026**, cuando vence la excepción de `dependency-check-suppressions.xml` y el job
  `vulnerabilidades` se pone rojo.
- **Fase / RF:** F8 (07.07). CLAUDE §7 n.º 23. Frontend: `appKine-web/docs/calidad.md`.
- **Destraba:** "cero hallazgos altos" de 07.07 y que el CI de `main` siga verde después del 09/11.

### ~~U-06 · SAST y DAST; secretos de Sonar y NVD~~ — RESUELTA el 17/10/2026 (DP-35)

> **Resuelta el 17/10/2026** por el usuario (acepta la propuesta de api #119 / web #77): SAST
> **CodeQL**, DAST **OWASP ZAP baseline**, los dos no bloqueantes. Sonar sigue salteándose
> mientras no se carguen sus secretos; `NVD_API_KEY` también sigue sin cargar. Ver DP-35.

- **Pregunta:** ¿CodeQL o SonarQube como SAST? ¿Se agrega un DAST (OWASP ZAP baseline)? Cargar
  `SONAR_TOKEN`, `SONAR_HOST_URL` y `NVD_API_KEY` como secretos de Actions.
- **Recomendado:** la ficha no elige SAST. Lo que sí dice: el job `sonar` ya existe en los dos
  `ci.yml` y se saltea limpio sin secretos (F0–F1), así que para Sonar sólo faltan los secretos;
  el DAST, como job no bloqueante contra la imagen (G-5 opción (c)). SCA ya está (G-6).
- **Fase / RF:** F8 (07.07) y F0–F1 (§2–§3). CLAUDE §7 n.º 20.
- **Destraba:** el criterio "SAST y DAST" del gate y el 5 % que le falta a F0–F1.
- **Estado (14/10/2026): propuesta en PR #119** (y appKine-web #77), en borrador y sin mergear.
  SAST = **GitHub CodeQL** (`java-kotlin` y `actions` acá; `javascript-typescript` y `actions` en
  el web), gratis porque los dos repos son públicos. DAST = **OWASP ZAP baseline** pasivo contra el
  backend levantado en el CI, con el reporte como artifact. Los dos **no bloqueantes**; cómo
  volverlos bloqueantes en `docs/calidad.md` §5. Lo que queda del usuario: aceptar o cambiar la
  elección, y cargar los secretos de Sonar y NVD (eso no cambia con el PR).

---

## B. Se encarecen al postergarlas

Exponen datos hoy, o su arreglo necesita migración o un cambio incompatible que crece con cada
fila nueva.

### ~~U-07 · `convenio:read`~~ — RESUELTA el 18/10/2026 (DP-36)

> **Resuelta el 18/10/2026** por el usuario (acepta la recomendación) e implementada en api #123:
> sin migración, contrato 0.130.0 solo con descripciones; matriz §18.1. Ver DP-36.

- **Pregunta:** ¿las lecturas de `contracting` (financiadores, planes, convenios, aranceles) pasan
  a exigir un permiso? Hoy se autorizan por pertenencia y una membership `PACIENTE` lee la
  configuración económica del centro.
- **Recomendado:** repetir DP-22: `convenio:read` sólo para el personal (`ORG_ADMIN`,
  `CONSULTORIO_ADMIN`, `PROFESIONAL`, `ADMINISTRATIVO`), `PACIENTE` con 403; sin migración,
  contrato con sólo descripciones de 403.
- **Fase / RF:** F3 (03.03–03.05). Ficha F3 n.º 4.
- **Destraba:** cerrar el mismo hueco que DP-22 cerró para el padrón.

### ~~U-08 · `catalogo:read` / `catalogo:manage`~~ — RESUELTA el 18/10/2026 (DP-36)

> **Resuelta el 18/10/2026** por el usuario (acepta la recomendación) e implementada en api #123:
> la propuesta de §11.1 tal cual, también para los tipos de medición y las solicitudes de alta;
> matriz §18.2. Ver DP-36.

- **Pregunta:** ¿se aprueban como están propuestos en `docs/seguridad/matriz-permisos-minima.md`
  §11.1? Hoy rige la autorización interina por pertenencia / `ORG_ADMIN`.
- **Recomendado:** aprobarlos. Costo: un `PACIENTE` con membership deja de leer el catálogo, que
  es lo correcto después de DP-22.
- **Fase / RF:** F2 (02.05). Ficha F2 n.º 2.
- **Destraba:** sacar la autorización interina de `resource/domain/PermissionCodes.java`.

### U-09 · C-9 / RF-M10-007 — caso obligatorio al reservar o atender

- **Pregunta:** ¿se exige ya, con ventana de migración de las sesiones sin caso?
- **Recomendado:** exigirlo sólo para ofertas con `requiere_caso_clinico = true` y dejar el resto
  opcional (F4 n.º 1).
- **Fase / RF:** F4 (04.03), F5 (05.02, "caso en la reserva"), F6 (06.01, `sesion.caso_id`
  nullable). Paquete C-9.
- **Destraba:** el último 10 % de 04.03, 06.01 y el "caso en la reserva" de 05.02. Cada sesión
  cerrada sin caso agranda la migración.

### U-10 · Plan de tratamiento por práctica o por oferta (RF-M11-002)

- **Pregunta:** ¿los ítems del plan siguen por oferta o pasan a `plan_item.practica_id`?
- **Recomendado:** quedarse en oferta y derivar la práctica por la principal de la oferta
  (`oferta_practica`, DP-11), escribiéndolo como DP. Pasar a `plan_item.practica_id` reabre 04.04
  y necesita migración (F4 n.º 2).
- **Fase / RF:** F4 (04.04). Antes, decisión pendiente 6 del `CLAUDE.md` del workspace.
- **Destraba:** el 15 % de 04.04 y el ADR que la ficha de F4 pide.

### ~~U-11 · Débito parcial de una presentación~~ — RESUELTA el 18/10/2026 (DP-37)

> **Resuelta el 18/10/2026** por el usuario (acepta la recomendada) e implementada en api #122,
> contrato **0.129.0**, migración **`V110`** (antes `V95`). Ver DP-37.

- **Pregunta:** lo debitado ¿se re-presenta, se pasa al paciente o se da de baja como pérdida? Hoy
  debitar deja el ítem `DEBITADO` entero y, al conciliar, la obligación del financiador sigue
  `PENDIENTE` por el total.
- **Recomendado:** la conciliación salda lo cobrado (presentado − debitado) y deja `PENDIENTE` sólo
  lo debitado, re-presentable en otro lote; pasarlo al paciente lo decide el administrativo, no el
  sistema. **Necesita migración** (F7 n.º 4).
- **Fase / RF:** F7 (07.04). CLAUDE §7 n.º 24; `docs/qa/90-conocido-y-aceptado.md` §5.
- **Destraba:** el saldo correcto del financiador después de cada conciliación con débito.
- **Estado:** **aprobada el 18/10/2026 e implementada en PR #122.** La
  recomendada, con `V110` (nació como `V95`) y contrato 0.129.0: estados de ítem `DEBITADO_PARCIAL` (ocupa hasta
  conciliar) y `ACEPTADO_PARCIAL` (libera lo debitado). Diseño y design challenge en
  `docs/diseno/AKINE-07.04b-reparos-y-debito-parcial.md` §3.

### U-12 · DU-13 — catálogo global de financiadores

- **Pregunta:** ¿el centro *referencia* la fila global (`owner_key` en `financiador` /
  `plan_cobertura`) o la *adopta* como copia?
- **Recomendado:** la copia: no toca ninguna lectura existente ni los snapshots ya congelados (F3
  n.º 1; opciones y costos en `docs/diseno/AKINE-A-7-plataforma.md` §3).
- **Fase / RF:** F3 (03.03) y F2 (A-7). CLAUDE §7 n.º 11.
- **Destraba:** la parte de A-7 que quedó diseñada y sin implementar. Referenciar se encarece con
  cada financiador cargado por centro.

### U-13 · Uso de `nomenclador_item`

- **Pregunta:** ¿el arancel se ata a un ítem de nomenclador vigente, o el nomenclador queda como
  catálogo informativo? Hoy `convenio_arancel` (`V43`) referencia la práctica y la regla "los
  convenios referencian versión/vigencia" no se cumple.
- **Recomendado:** informativo para el MVP (el arancel ya congela importe y vigencia en
  `ArancelCongelado`) y anotar la regla como desvío aceptado; atarlo después es migración en
  `contracting` y reabre B-7 (importación de aranceles) (F2 n.º 1).
- **Fase / RF:** F2 (02.05) y F3 (03.05).
- **Destraba:** el 10 % de 02.05.

### U-14 · DU-11 — ¿un observador que falla debe hacer fallar el cierre?

- **Pregunta:** el criterio de 06.05 pide "HC consistente aunque la economía falle y reintente";
  hoy un observador que falla hace fallar el cierre clínico.
- **Recomendado:** mantenerlo (la deuda se devenga en la transacción del cierre, regla 7) y
  escribirlo como DP (F6 n.º 2).
- **Fase / RF:** F6 (06.05).
- **Destraba:** el 10 % de 06.05. Desacoplar después obliga a reconciliar cierres sin deuda.

### U-38 · Qué es "hoy" para una operación de la organización sin sede

- **Pregunta:** la JVM corre en UTC y entre las 21 y las 24 de Córdoba una vigencia que vence ese
  día se lee vencida. Las operaciones **con sede** ya usan la zona de la sede (rama
  `akine-hoy-en-la-zona-de-la-sede`, `organization.spi.ZonaDeSede`). Las **de la organización sin
  sede** —padrón, coberturas de la persona, autorizaciones, órdenes, planes del financiador— no
  tienen zona: ¿zona por organización (migración en `organization`), la de la sede del contexto
  cuando la hay, o que el cliente mande siempre `fecha`?
- **Recomendado:** ninguna ficha recomienda; no se decidió en la rama que corrigió las de sede.
- **Sitios:** 28, listados uno por uno en [`../zonas-horarias.md`](../zonas-horarias.md) §(b).
- **Fase / RF:** transversal (F3, F4; AKINE-02.01 fija la zona de la sede).
- **Destraba:** que una cobertura, autorización u orden que vence hoy no se lea vencida a la noche.
  Se encarece con cada sitio nuevo que copie `LocalDate.now()`.

### ~~U-39 · Cuándo se devenga una clase `POR_CLASE`, y qué pasa sin pase en `POR_PACK`~~ — RESUELTA el 17/10/2026 (DP-24)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): (a) con la asistencia, sin no-show por defecto; (b) en `POR_PACK`, solo se informa. Ver DP-24 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

- **Pregunta:** (a) en una oferta `POR_CLASE`, ¿la deuda nace con la **asistencia** o con la
  **inscripción**, y el no-show cobra? (b) en una oferta `POR_PACK`, ¿qué se hace con quien asiste
  **sin pase vigente**: sólo se informa (hoy), se bloquea la asistencia, o se devenga una clase
  suelta?
- **Hoy (rama `akine-f9-pases-abonos`):** (a) declarar `POR_CLASE` en la oferta responde 400, porque
  `ck_oferta_politica_devengo` (V64) exige elegir el momento y elegirlo es esta decisión; RF-M18-008
  queda sin disparador. (b) la asistencia se registra igual y la respuesta trae
  `consumoDeCredito.efecto = SIN_PASE_ELEGIBLE`, auditado como `PASE_SIN_CREDITO_ELEGIBLE`
  (RN-M29-005, CA-M19-009-06). Ningún RF pide devengar ahí.
- **Recomendado:** (a) asistencia, sin no-show por defecto (`devenga_no_show = 0`, ya en V64).
  (b) informar, como hoy.
- **Fase / RF:** F9 (08.03, 08.07); RF-M18-008, RN-M29-005. Diseño en
  [`../diseno/AKINE-08.06-pases.md`](../diseno/AKINE-08.06-pases.md) §4 y §9.
- **Destraba:** el devengo de la asistencia (RF-M18-008 / RF-M19-009). Agregarlo después no
  cambia esquema: `obligacion` necesita un origen nuevo (`ck_obligacion_origen`), que es un
  `DROP CHECK` cuidadoso.

### ~~U-42 · Qué consecuencia económica tiene la atención clínica de un participante de clase~~ — RESUELTA el 17/10/2026 (DP-26)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): (a): solo por convenio; sin cobertura, nada particular. Ver DP-26 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

- **Pregunta:** la Sesión de un participante de una clase clínica (08.05) se cierra como cualquier
  Sesión. ¿Qué devenga? (a) **sólo lo del convenio** cuando hay cobertura (financiador + coseguro,
  y así entra a la presentación, RF-M21-009), y **nada particular**: la clase se cobra por su
  esquema —pase, abono, clase— sobre la asistencia; (b) **también el particular** al precio de la
  oferta, como una atención de turno (DP-17), y entonces la asistencia a esa clase no debería
  consumir pase ni devengar; (c) **nada**, ni siquiera por convenio. Y una sub-pregunta: con
  cobertura, ¿el coseguro se suma a lo que el paciente ya paga por la clase?
- **Hoy (rama `akine-08.05-atencion-grupal`):** (a). `SesionCerrada.claseId` marca el origen;
  `billing.ObligacionDevengador` devenga por convenio igual que siempre (cada participante con
  cobertura deja su renglón en la bandeja, CA-M21-009-06) y **sin cobertura no devenga ni exige
  precio** (el 409 `oferta-sin-precio` no bloquea un cierre clínico por una deuda que no
  corresponde). El consumo de autorizaciones corre por Caso, como dejó escrito 08.04 §6. El coseguro
  se devenga como dice el convenio.
- **Recomendado:** (a), que es lo que está; revisar el coseguro si un centro cobra la clase aparte.
- **Fase / RF:** F9 (08.05, 08.09); RF-M14-014 paso 6, RF-M21-009, RF-M18-008, RF-M19-009, DP-17.
  Diseño en [`../diseno/AKINE-08.05-atencion-grupal.md`](../diseno/AKINE-08.05-atencion-grupal.md) §7.
- **Destraba:** el cobro particular de una prestación clínica grupal sin pase. Se encarece con cada
  Sesión grupal cerrada sin deuda: un backfill tendría que decidir a posteriori qué se cobró por la
  clase.

### ~~U-46 · Lo que el hardening de F9 (09.02) dejó para decidir~~ — RESUELTA el 17/10/2026 (DP-28; (e) DP-33)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): (a), (b), (f) como están; (c) el PROFESIONAL no ve `paseId`/`saldo`; (d) el título no viaja en el correo; (e) resuelta con U-03: la propiedad del panel de atención grupal es la relación asistencial, sin justificación declarada (DP-33). Ver DP-28 y DP-33 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

09.02 corrigió los defectos de aislamiento, auditoría y entradas de `activity` (registro en
[`F10-consolidacion-final.md`](F10-consolidacion-final.md)). Estas seis no son defectos: son
criterios que la matriz y la especificación no fijan.

- **(a) Una sede hermana responde 403, no 404.** Un miembro con alcance de sede A2 que pide
  `/consultorios/A1/...` (A1 de su misma organización) recibe 403 `forbidden`, por regla del
  evaluador (`PermissionEvaluatorService`: la sede existe para él). El 403 sale **antes** de mirar
  la clase, el pase o el producto —`ActividadAislamientoIT` lo verifica—, así que no delata ningún
  id; sólo confirma que la sede existe, que el actor ya ve en el listado de sedes de su
  organización. Opciones: dejarlo así (recomendado: es transversal a todos los módulos y no filtra
  datos) o pasar a 404 toda sede fuera del alcance de la membership, en el evaluador.
- **(b) Ningún permiso de F9 es otorgable por grant.** `GRANT_POR_ROL` no tiene filas de la segunda
  entrega y la matriz §32 no tiene filas de clases ni de pases (es la DU-12 del plan en paralelo).
  Consecuencias: no existe el "instructor que toma lista pero no inscribe", el `PROFESIONAL` no
  puede recibir `pase:read`, y el `ADMINISTRATIVO` no puede recibir `producto:manage` ni
  `pase:adjust`. `MatrizDePermisosIT` fija hoy que esos grants son 400. Opciones: dejarlo cerrado
  (recomendado hasta tener filas en la matriz) o declarar celdas "Según permiso" para F9.
- **(c) El `PROFESIONAL` ve el saldo del pase al tomar asistencia.** La respuesta de
  `POST .../asistencias` (y del lote y de los ingresos) lleva `consumoDeCredito {paseId, saldo}`,
  y el profesional no tiene `pase:read`. `DetalleOperativoResponse` excluye los pases a propósito
  para quien toma lista. Opciones: omitir `paseId`/`saldo` sin `pase:read` (cambio de contrato
  aditivo) o aceptarlo, porque el consumo lo dispara su propio acto.
- **(d) Dos textos libres que nada impide que lleven datos de salud.** Las `observaciones` de la
  asistencia —la columna dice "operativa, no clínica" y la ve cualquiera con `inscripcion:read`— y
  el `titulo` de la clase, que viaja en el correo de cancelación o reprogramación
  (`claseTitulo`). Un título "Rehabilitación post-ACV" le cuenta un dato de salud a quien lea la
  casilla. Opciones: aviso en pantalla, no mandar el título en el correo, o aceptarlo.
- **(e) El panel de atención grupal no pide justificación.** `GET .../clases/{c}/atencion-clinica`
  exige `sesion:register` y se audita (`SESION_GRUPAL_CONSULTADA`, que desde 09.02 lista las
  historias expuestas), pero no pide `X-Justificacion-Acceso`. Es la misma decisión abierta de la
  justificación en `encounter` (CLAUDE.md §7 n.º 21): se resuelve junto con ella.
- **(f) Las claves de idempotencia son por organización, no por sede.** Reusar desde la sede A una
  clave usada en B responde 409 `idempotency-key-conflict` en vez de 404. Para aprovecharlo hay que
  conocer una clave UUID ajena. Opciones: aceptarlo (recomendado) o sumar la sede al unique, con
  migración.
- **Fase / RF:** F10 (09.02); §32, RNF-M28-002, RN-M24-003.
- **Destraba:** (b) y (c) se encarecen con cada pantalla de F9 que asuma el reparto actual; (a) es
  transversal y no bloquea nada.

---

## C. Alcance del MVP, aditivas

Ninguna necesita migración de datos existentes según su ficha, o la ficha recomienda dejarlas
fuera del MVP. Ordenadas por fase.

### U-15 · Contradicción de la matriz sobre editar la propia organización

- **Pregunta:** la matriz se contradice (§4 contra §5 y §6) sobre quién edita la propia
  organización. ¿Cuál manda?
- **Recomendado:** la ficha no recomienda.
- **Fase / RF:** F0–F1 (01.03). Ficha F0–F1 n.º 3.
- **Destraba:** el registro de 01.03 sin contradicción abierta.

### U-16 · Cambio de email de una cuenta

- **Pregunta:** ¿entra al alcance? Ningún RF de M02 (RF-M02-001…005) lo pide.
- **Recomendado:** la ficha lo declara fuera de alcance del MVP; si se quiere, RF nuevo en M02 y
  etapa propia (tabla de cambio pendiente, token al correo nuevo, aviso al viejo).
- **Fase / RF:** F0–F1 (01.02). Ficha F0–F1 n.º 3.
- **Destraba:** nada del MVP.

### U-17 · Disciplina del profesional al habilitarlo

- **Pregunta:** ¿se valida que la disciplina del profesional corresponda a la oferta?
- **Recomendado:** no validar en el MVP —RF-M05-007 paso 5 las llama informativas y RN-M05-005
  separa disciplina de habilitación—; dejarlo como aviso si se pide (F2 n.º 3).
- **Fase / RF:** F2 (02.07).
- **Destraba:** el 5 % de 02.07.

### U-18 · Obligatoriedad de requisitos por financiador o por prestación

- **Pregunta:** ¿los requisitos (orden, autorización, documentación) se configuran también por
  financiador o por práctica, además del convenio?
- **Recomendado:** dejarlos en el convenio (RF-M16-005) y cerrar el ítem como no requerido; si
  hace falta un default, que sea la clonación de U-19 (F3 n.º 2).
- **Fase / RF:** F3 (03.06).
- **Destraba:** el 3 % de 03.06.

### U-19 · Clonación controlada de convenio

- **Pregunta:** ¿se quiere? Sólo aparece en la sección Frontend de 03.05 del plan; ningún RF de M16
  la pide.
- **Recomendado:** sin decisión, fuera del MVP. Si se quiere: `POST .../convenios/{id}/clonar`
  con vigencia desde una fecha, copia de aranceles y requisitos y modo `PREVIEW` como la
  importación; ~1 día de backend + pantalla (F3 n.º 3).
- **Fase / RF:** F3 (03.05).
- **Destraba:** el 8 % de 03.05.

### U-20 · Si los datos administrativos de la HC se auditan

- **Pregunta:** la lectura limitada del `ADMINISTRATIVO` (PR #109) no se audita porque no expone
  contenido clínico. ¿Debe auditarse?
- **Recomendado:** dejarlo así (F4 n.º 4).
- **Fase / RF:** F4 (04.01).
- **Destraba:** nada; confirma el diseño de #109.

### U-21 · Salida "se retiró sin ser atendido" y Particular desde `EN_ESPERA`

- **Pregunta:** ¿se agrega la salida y se admite "atender como Particular" desde `EN_ESPERA`?
- **Recomendado:** una transición `RETIRADO` desde `LLEGO`/`EN_ESPERA` con motivo obligatorio, sin
  tocar el turno; y admitir Particular desde `EN_ESPERA`, que hoy obliga a anular y volver a llegar
  (F5 n.º 5).
- **Fase / RF:** F5 (05.04). CLAUDE §7 n.º 18.
- **Destraba:** el 8 % de 05.04.

### U-22 · Alta de serie todo o nada

- **Pregunta:** un feriado hace fallar el alta entera de una serie. ¿Se mantiene?
- **Recomendado:** omitir la ocurrencia y listarla en la previsualización, que ya existe (F5 n.º 6).
- **Fase / RF:** F5 (05.03, E-3). CLAUDE §7 n.º 19.
- **Destraba:** series que atraviesan feriados.

### U-23 · Qué eventos de turno notifican

- **Pregunta:** hoy avisan reservar, cancelar y reprogramar; confirmar y ausente no. ¿Alcanza?
- **Recomendado:** dejarlo así (F5 n.º 4).
- **Fase / RF:** F5 (05.02–05.03, RF-M26-002/003). CLAUDE §7 n.º 7.
- **Destraba:** nada; confirma E-5.

### U-24 · C-8 / DU-11 — atención sin turno y reemplazo autorizado del profesional

- **Pregunta:** ¿se puede iniciar una sesión sin turno y quién reemplaza al profesional?
- **Recomendado:** `POST /sesiones` sin turno con `sesion:register` y motivo obligatorio;
  reemplazo sólo por `CONSULTORIO_ADMIN` con auditoría (F6 n.º 1).
- **Fase / RF:** F6 (06.01). Paquete C-8.
- **Destraba:** el 20 % de 06.01 (junto con U-09).

### U-25 · Permiso reforzado y ventana temporal para enmendar

- **Pregunta:** hoy la enmienda usa `sesion:register` más propiedad. ¿Permiso propio y ventana?
- **Recomendado:** `sesion:amend` propio y ventana de 30 días configurable por organización (F6
  n.º 5).
- **Fase / RF:** F6 (06.06). Paquete C-6 (la parte abierta).
- **Destraba:** el 15 % de 06.06.

### U-26 · Subbloques ROM, fuerza y marcha

- **Pregunta:** ¿el examen pasa a estructura propia por dominio o sigue como medición genérica?
- **Recomendado:** quedarse en la medición genérica con catálogo de tipos y declarar el desvío
  (F6 n.º 3).
- **Fase / RF:** F6 (06.03, `plan_sesiones.txt`). Paquete C-8.
- **Destraba:** el 20 % de 06.03 (declarado como desvío).

### U-27 · Parámetros obligatorios por práctica

- **Pregunta:** ¿qué parámetros exige cada práctica? No hay configuración que los declare;
  `plan_sesiones.txt` §10.4 los pide condicionales por tipo.
- **Recomendado:** configuración por práctica en `offering`, en una etapa propia (F6 n.º 4).
- **Fase / RF:** F6 (06.04). Antes, decisión pendiente 11 del `CLAUDE.md` del workspace.
- **Destraba:** el 10 % de 06.04.

### ~~U-28 · RF-M21-003 — orden, autorización y credencial en la presentación~~ — RESUELTA el 18/10/2026 (DP-37)

> **Resuelta el 18/10/2026** por el usuario (acepta la recomendada) e implementada en api #122,
> contrato **0.129.0**. Ver DP-37.

- **Pregunta:** un faltante ¿es **reparo** (bloquea confirmar el lote) o **aviso**?
- **Recomendado:** reparo para orden y autorización cuando el convenio las exige; aviso para la
  credencial vencida. Es un valor nuevo en `HallazgoDeValidacion`: contrato aditivo (F7 n.º 3).
- **Fase / RF:** F7 (07.04).
- **Destraba:** parte del 10 % de 07.04 y menos débitos del financiador.
- **Estado:** **aprobada el 18/10/2026 e implementada en PR #122.** La
  recomendada, contrato 0.129.0: `SIN_ORDEN` y `SIN_AUTORIZACION` en `hallazgos` (bloquean
  confirmar), `CREDENCIAL_FALTANTE` en la lista nueva `avisos` (no bloquea). Se evalúan al validar y
  confirmar, no al incluir. Diseño en `docs/diseno/AKINE-07.04b-reparos-y-debito-parcial.md` §2.

### U-29 · Ventana de cancelación del paciente *(depende de `OWN`, post-MVP por DP-22)*

- **Pregunta:** ¿cuántas horas antes cancela el paciente, por oferta o por sede, y alcanza al staff?
- **Recomendado:** por sede, sólo para el paciente, 24 h por defecto; el staff cancela siempre con
  motivo (F5 n.º 2).
- **Fase / RF:** F5 (05.03). Ningún RF de M12 la fija.
- **Destraba:** el 10 % de 05.03, cuando exista autoservicio.

### U-30 · Vista pública de slots *(depende de `OWN`)*

- **Pregunta:** ¿hay reserva online sin login?
- **Recomendado:** no en el MVP; el payload ya no lleva PII, así que abrirla después es un
  endpoint y una regla de rate limit (F5 n.º 3).
- **Fase / RF:** F5 (05.01).
- **Destraba:** el 5 % de 05.01.

### U-31 · DU-10 — `egreso:manage` propio

- **Pregunta:** ¿permiso propio o seguir reusando `caja:operate`?
- **Recomendado:** seguir con `caja:operate` en el MVP y abrir `egreso:manage` cuando aparezca un
  rol que opere la caja sin poder egresar (F7 n.º 1).
- **Fase / RF:** F7 (07.05). Antes, decisión pendiente 10 del `CLAUDE.md` del workspace.
- **Destraba:** el cierre formal de 07.05 sin decisión abierta.

### U-32 · Permiso propio de presentaciones

- **Pregunta:** ¿permiso propio o seguir con `cobro:register`?
- **Recomendado:** mantenerlo en el MVP, igual que U-31 (F7 n.º 2).
- **Fase / RF:** F7 (07.04).
- **Destraba:** ídem.

### U-33 · Adjunto binario del comprobante de egreso (RF-M22-003)

- **Pregunta:** ¿alcanza la referencia documental (tipo, número, fecha) o se extrae el storage a
  `platform.spi` (refactor de `person` y `clinical`)?
- **Recomendado:** declarar el MVP cubierto por la referencia y hacer la extracción como etapa
  propia después del gate (F7 n.º 7).
- **Fase / RF:** F7 (07.05). Antes, decisión pendiente 7 del `CLAUDE.md` del workspace.
- **Destraba:** el 10 % de 07.05.

### U-34 · Arancel 0, crédito y bono, liquidación a profesionales

Tres preguntas de alcance económico de la misma ficha, agrupadas porque las tres recomiendan
dejar el MVP como está:

- **Arancel 0** — ¿prestación sin cargo legítima o debe exigir precio? *Recomendado:* aceptarla
  sin deuda, como hoy, pero dejar el rastro en el reporte (prestación sin cargo) (F7 n.º 5).
- **Crédito y bono de RF-M18-011** — no hay modelo para ninguno. ¿Entran? *Recomendado:* no; el
  esquema del MVP es financiador + coseguro + particular (F7 n.º 8).
- **Liquidación a profesionales (RF-M22-006/007)** — ¿se confirma fuera del MVP? *Recomendado:*
  sí, segunda entrega; el MVP registra el pago como egreso con beneficiario `COLABORADOR` (F7 n.º 6).
- **Fase / RF:** F7 (07.01, 07.05).
- **Destraba:** el 5 % de 07.01 y la frontera MVP / segunda entrega de M22.

> U-34 es una entrada con tres preguntas. Los totales del resumen cuentan entradas, no preguntas.

---

## D. No bloquean nada

### U-35 · Gate de `BRANCH` en JaCoCo

- **Pregunta:** ¿se agrega? La rama está en 77,91 % (BUNDLE, 11/10); ponerlo por encima rompe el
  build.
- **Recomendado:** la ficha no recomienda un umbral.
- **Fase / RF:** F8 (07.09). CLAUDE §7 n.º 2.
- **Destraba:** nada del gate (la cobertura de línea e instrucción ya está gateada en 0,80).

### U-36 · Confirmar el desvío del export síncrono de 07.06

- **Pregunta:** ¿se confirma el export síncrono y acotado (366 días, 500 filas por sección) en vez
  del asíncrono?
- **Recomendado:** la ficha no lo dice en esas palabras, pero registra que el reporte midió p95
  38 ms con 500 usuarios y que "no hay hoy un dato que pida el asíncrono".
- **Fase / RF:** F8 (07.06).
- **Destraba:** el 10 % de 07.06 (como desvío declarado).

### ~~U-37 · Saltos de versión mayor de Dependabot~~ — RESUELTA el 18/10/2026 (DP-41)

> **Resuelta el 18/10/2026** por el usuario: Angular 22 y Java 25 después del despliegue del RC, cuando esté estable. Ver DP-41 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

- **Pregunta:** ¿se toman Angular 22 (web #53, #55–#57), Vitest coverage 5 (web #70) y Java 25 en
  la imagen (api #87)?
- **Recomendado:** la ficha no recomienda; no se mergean en el cierre.
- **Fase / RF:** F8.
- **Destraba:** nada del MVP.

### ~~U-40 · Las políticas de pases y abonos que M29 no define~~ — RESUELTA el 17/10/2026 (DP-25)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): las cinco quedan como están. Ver DP-25 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

- **Pregunta:** cinco, todas de producto: (a) ¿un abono puede no tener tope de asistencias por
  periodo? (b) ¿el abono se activa al vender o al cobrarse ("según política de pago",
  RF-M29-007 paso 7)? (c) ¿se puede anular un pase ya usado, prorrateando la deuda? (d) ¿un ajuste
  manual puede dejar el saldo por encima de lo comprado (bonificación)? (e) ¿hay ventana de gracia
  para consumir un pase que el job ya venció, si la clase fue dentro de su vigencia?
- **Hoy (rama `akine-m29-abonos-y-ajustes`):** (a) el abono lleva tope (`creditos > 0`, V64); uno
  "libre" se modela con un tope alto. (b) se activa al vender, como el pack. (c) 409
  `pase-no-ajustable`: se ajusta el saldo en su lugar. (d) no: el saldo final queda en
  `[0, creditos comprados]`. (e) no: RF-M29-006 dice "no consumir créditos luego del vencimiento".
- **Recomendado:** dejar las cinco como están hasta que un centro lo pida; (a) y (d) cambian
  CHECKs de V64, (b) depende de la política de prepago (ADR-0013).
- **Desde 08.08 (rama `akine-08.08-renovacion-y-avisos`, 16/10/2026), (b) alcanza también a la
  renovación automática:** el período renovado se activa al venderse y la renovación **no mira si
  el anterior se pagó**, por la misma regla. Decidir "al cobrarse" cambia la venta y la renovación
  juntas (diseño §10.1 y §10.8).
- **Fase / RF:** F9 (08.07, 08.08); RF-M29-005, RF-M29-006, RF-M29-007, RN-M29-009. Diseño en
  [`../diseno/AKINE-08.07-08.08-ledger-y-abonos.md`](../diseno/AKINE-08.07-08.08-ledger-y-abonos.md) §9.
- **Destraba:** nada del MVP.

### ~~U-41 · El esquema de honorarios que RF-M22-006 da por configurado~~ — RESUELTA el 18/10/2026 (DP-38)

> **Resuelta el 18/10/2026** por el usuario: importe por participante presente, en la oferta con vigencia, partes iguales entre instructores, borrador por período confirmado como egreso. Ver DP-38 en `AKINE_IMPLEMENTATION_PLAN.md` §7. (Postergada antes por DP-31; el texto de abajo es el planteo original.)

- **Pregunta:** RF-M22-006 dice *"aplica esquema de honorario configurado: por clase, hora,
  participante u otro soportado"*, y ese esquema **no existe** en el modelo ni en la especificación.
  Hay que fijar: (a) qué esquemas entran (por clase, por hora, por participante presente); (b) dónde
  se declaran —en la oferta, en el vínculo del profesional, o en una regla por profesional y oferta—
  y con qué vigencia; (c) si una clase con dos instructores se reparte o se paga entera a cada uno
  (caso borde "instructor múltiple" del plan); (d) si la liquidación es un **borrador calculado** que
  la persona confirma como egreso, o un egreso automático.
- **Hoy:** el egreso de M22 (07.05) lleva beneficiario `COLABORADOR`, categoría
  `HONORARIOS_PROFESIONALES` y período, con el importe **declarado por una persona** y sin vínculo
  con clases ni sesiones (RN-M22-003). U-34 ya confirmó que la liquidación es de la segunda entrega.
- **Recomendado:** ninguna ficha recomienda. La forma técnica que no cierra ciclos está escrita en
  [`../diseno/AKINE-08.09-integracion-economica.md`](../diseno/AKINE-08.09-integracion-economica.md)
  §5: la base se calcula en `activity` y entra a `billing` por `spi` como detalle del egreso, con
  snapshot de los importes (CA-M22-007-06).
- **Fase / RF:** F9 (08.09); RF-M22-006, RF-M22-007.
- **Destraba:** RF-M22-006/007, la mitad de 08.09.

### ~~U-48 · El lock del producto en la venta de un pack~~ — RESUELTA el 17/10/2026 (DP-29)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): el lock solo en abonos; los packs resuelven la clave por el unique. Ver DP-29 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

- **Pregunta:** ¿la venta de un **pack** sigue tomando el producto con `FOR UPDATE`, o sólo la de
  un abono?
- **Hoy:** toda venta lo toma (08.08): en un abono serializa los períodos de la misma persona para
  que no se superpongan; en un pack sólo sirve para leer una baja recién commiteada. Con un solo
  producto, todas las ventas de la sede van en fila: p95 **723 ms** a 4,2 ventas/s
  (`docs/carga.md` §12). 09.03 sumó una segunda razón para el lock: ahí se vuelve a mirar la
  `Idempotency-Key` de dos ventas simultáneas.
- **Recomendado:** bloquear sólo los abonos y, en un pack, resolver la carrera de la clave con el
  unique `uk_pase_idempotency` (relectura en `READ_COMMITTED` al chocar). La baja recién
  commiteada que se cuela es una venta del último instante, que el mostrador puede anular.
- **Fase / RF:** F10 (09.03); RF-M29-002.
- **Destraba:** el SLO de la venta de pases con varios mostradores.

### ~~U-49 · El costo de la asistencia en lote~~ — RESUELTA el 17/10/2026 (DP-30)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): (a) hecha en #152; (c) solo si el entorno lo exige; (b) no. Ver DP-30 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

- **Pregunta:** ¿se cambia cómo se transacciona la asistencia en lote?
- **Hoy:** cada ítem es su propia transacción (RF-M13-008, "actualización transaccional
  independiente") con ~18 sentencias. Un lote de 25 tarda 1,5 s de mediana y, a 2 lotes por
  segundo, satura el pool de 10 y arrastra a toda la API (`docs/carga.md` §12: p95 de lecturas de
  ~95 ms a ~2,2 s).
- **Opciones:** (a) sacar del ítem lo que se repite igual en todos —sede, zona, esquema de cobro—,
  3 de 18 sentencias, sin cambio de semántica; (b) agrupar ítems de a varios por transacción,
  manteniendo el resultado parcial por ítem —cambia "independiente"—; (c) limitar los lotes
  concurrentes por sede.
- **(a) hecha el 17/10/2026** (rama `akine-asistencia-lote-costo`, `docs/carga.md` §12 "09.05"),
  sin cambiar ninguna regla —misma transacción por ítem, mismos locks y en el mismo orden, mismas
  auditorías—: **de 18 a 11 sentencias por participante en el alta, de 15 a 12 en la corrección a
  ausente y de 18 a 12 en la corrección a presente** (contexto, sede y permiso una vez por pedido;
  zona y esquema de cobro una vez por pedido; sin neto de una asistencia recién creada; saldo leído
  bajo el lock). **Lo que no se ganó:** cada ítem sigue con su COMMIT durable, que en la máquina de
  desarrollo cuesta 4–11 ms según la carga del disco, tanto como todas sus sentencias juntas. La
  carga de `actividad.js` no distingue la mejora del ruido de la máquina (seis corridas de 5 min y
  ocho de 2 min, intercaladas). Estimado: ~3 ms de conexión menos por ítem, ~70 ms por lote de 25.
  **Los 25 commits por lote quedan**, y son lo que satura el pool: bajar de ahí es (b) o (c).
- **Recomendado:** (c) si el entorno de destino lo confirma; (b) sólo si un RF lo admite. Con (a)
  hecha, ése es el número con el que se decide.
- **Fase / RF:** F10 (09.03); RF-M13-008, RF-M28-007.
- **Destraba:** el SLO con asistencia en lote bajo carga.

### ~~U-43 · A nombre de quién se vende el período que renueva el job~~ — RESUELTA el 18/10/2026 (DP-40)

> **Resuelta el 18/10/2026** por el usuario: sigue a nombre de quien activó la renovación; si se desvincula, se apaga y se avisa. Ver DP-40 en `AKINE_IMPLEMENTATION_PLAN.md` §7. (Postergada antes por DP-31; el texto de abajo es el planteo original.)

- **Pregunta:** la renovación automática de un abono (08.08) vende el período siguiente sin que
  nadie apriete un botón. ¿Quién figura como vendedor: la cuenta que prendió la renovación, o una
  cuenta técnica de sistema?
- **Hoy (PR #140, `V103`):** la cuenta que autorizó la renovación
  (`renovacion_autorizada_por_cuenta_id`), porque `vendido_por_cuenta_id` es `NOT NULL` y **no
  existe cuenta técnica**. La auditoría `ABONO_RENOVADO` va con actor nulo (sistema) y el
  autorizante en los detalles. Desde #142 la pantalla lo muestra (`Pase.renovacionAutorizadaPor`).
- **Recomendado:** ninguna fuente recomienda; el diseño la deja a confirmar. Una cuenta técnica
  pide una migración en `identity` y decidir qué rol tiene.
- **Fase / RF:** F9 (08.08); RF-M29-007, RF-M18-010. Diseño
  [`../diseno/AKINE-08.07-08.08-ledger-y-abonos.md`](../diseno/AKINE-08.07-08.08-ledger-y-abonos.md)
  §10.2 y §10.8 n.º 7.
- **Destraba:** nada del MVP. Importa si un colaborador que prendió renovaciones se desvincula: sus
  abonos se siguen vendiendo a su nombre.

### ~~U-44 · Umbrales de aviso de pases por despliegue o por centro~~ — RESUELTA el 17/10/2026 (DP-27)

> **Resuelta el 17/10/2026** por el usuario (acepta la recomendación): por despliegue. Ver DP-27 en `AKINE_IMPLEMENTATION_PLAN.md` §7.

- **Pregunta:** los avisos de vencimiento próximo y de saldo bajo (RF-M26-008) se configuran con
  `akine.activity.pases.avisos.dias-antes-del-vencimiento` (3) y `.saldo-bajo` (1), **iguales para
  todos los centros del despliegue**. ¿Hace falta configurarlos por organización o por producto?
- **Hoy (PR #140):** por despliegue. Un umbral por centro o producto pide una pantalla que ningún
  RF describe, y el padrón no tiene preferencias de notificación (el único canal es el correo).
- **Recomendado:** dejarlo por despliegue hasta que un centro lo pida (diseño §10.3).
- **Fase / RF:** F9 (08.08); RF-M26-008, CA-M26-008-06. Diseño §10.3 y §10.8 n.º 8.
- **Destraba:** nada del MVP. Cómo se operan hoy: [`../operaciones/runbooks.md`](../operaciones/runbooks.md) R7.

### ~~U-45 · Qué período sigue al renovar un abono que no es un mes calendario~~ — RESUELTA el 18/10/2026 (DP-40)

> **Resuelta el 18/10/2026** por el usuario: anclado al día de la primera venta. Ver DP-40 en `AKINE_IMPLEMENTATION_PLAN.md` §7. (Postergada antes por DP-31; el texto de abajo es el planteo original.)

- **Pregunta:** el job renueva un mes calendario completo por el mes siguiente (noviembre →
  diciembre). Cualquier otro período se renueva por `vigenciaDias` desde el día siguiente al fin, y
  **se corre**: 30 días desde el 5 de noviembre terminan el 4 de diciembre. ¿Se admiten otras reglas
  "naturales" (quincena, semana calendario) o se ancla al día de la primera venta?
- **Hoy (PR #140):** mes calendario o `vigenciaDias`; nada más.
- **Recomendado:** ninguna fuente recomienda (diseño §10.8 n.º 9).
- **Fase / RF:** F9 (08.08); RF-M29-007. Diseño §10.2 paso 3.
- **Destraba:** nada del MVP.

### ~~U-50 · Cómo se costea un servicio para medir su rentabilidad~~ — RESUELTA el 18/10/2026 (DP-39)

> **Resuelta el 18/10/2026** por el usuario: egreso imputable a una oferta, rentabilidad = ingresos − egresos imputados, cobro prorrateado en caja. Ver DP-39 en `AKINE_IMPLEMENTATION_PLAN.md` §7. (Postergada antes por DP-31; el texto de abajo es el planteo original.)

- **Pregunta:** RF-M23-009 se titula *"rentabilidad e ingresos por servicio"* y pide *"consolidar
  obligaciones, cobros y caja por Oferta/Servicio"*. Los ingresos tienen fuente; **los costos no**.
  Hay que fijar: (a) si un egreso (M22) se imputa a una oferta o servicio —directo al registrarlo, o
  por una regla de prorrateo (por horas, por sesiones, por participantes)—, y qué pasa con los
  egresos generales (alquiler, insumos) que no son de ningún servicio; (b) si los honorarios entran
  por el egreso declarado de hoy o por la liquidación calculada que espera **U-41**; (c) si la
  **caja** por servicio hace falta además del cobrado imputado, y con qué regla se reparte un cobro
  que paga deudas de dos ofertas con dos medios; (d) si el `PROFESIONAL` ve la continuidad de los
  pacientes a los que él dio el alta (RF-M23-008), que hoy se le omite porque su `reporte:read` es
  de actividad propia y la sección cuenta lo que pasa después en servicios de otros.
- **Hoy (AKINE-09.01b, contrato 0.123.0):** el reporte `SERVICIOS` da devengado y cobrado imputado
  por oferta y concepto (prestación, clase, pack, abono), anticipos sin imputar y créditos
  consumidos, y declara siempre la advertencia `rentabilidad-sin-costos`. `egreso` no tiene ninguna
  columna que lo ligue a oferta ni a servicio (V57): su beneficiario es un colaborador o un externo y
  su categoría es contable. La caja no se reparte. `CONTINUIDAD` se omite al `PROFESIONAL`.
- **Recomendado:** ninguna fuente recomienda. Lo único cerrado es que **no se inventa**: sin (a) el
  reporte no muestra margen.
- **Fase / RF:** F10 (09.01b); RF-M23-009, RF-M23-008, RF-M22-006. Diseño
  [`../diseno/AKINE-09.01b-continuidad-y-rentabilidad.md`](../diseno/AKINE-09.01b-continuidad-y-rentabilidad.md) §3.
- **Destraba:** la rentabilidad de RF-M23-009. Nada del MVP.

---

## Abiertas en `CLAUDE.md` §7 que ninguna ficha recoge

No entran en los totales porque ningún registro de cierre del 12–13/10 las deja como "pendiente
del usuario"; siguen vivas en la lista numerada de `CLAUDE.md` §7 y se listan para que no se
pierdan: **n.º 4** (`ORG_ADMIN` cambia el plan de su organización), **n.º 9** (permisos de anular y
reintegrar un cobro), **n.º 10** (permiso del reintento de notificaciones), **n.º 12(c)** (la deuda
es una foto del cierre; F3 la menciona como abierta), **n.º 13** (límites de A-8b), **n.º 14**
(prepago `PENDIENTE` antes del check-in), **n.º 15(b–e)** (forma del arancel por oferta), **n.º 16**
(prepago de recepción), **n.º 19** (lo que dejaron C-4, A-9 y G-1) y **n.º 25** (nombres de
`SituacionOrdenMedica`).

La **n.º 6** (medir `cambiarEquipo`) quedó resuelta el 12/10/2026 por el PR #109: medido con
`CasoClinicoConcurrenteIT` y pasado al `UPDATE … WHERE version` (ficha F4).
