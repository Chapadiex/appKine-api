# AKINE — Mapa del proyecto

> Generado el **2026-10-01** con `/project-map`, verificado contra el filesystem y git. Donde el
> `CLAUDE.md` o el plan contradicen lo observado, **gana lo observado** y la diferencia queda en
> §8 Discrepancias.

| Repo | Rama | Último commit | Sin commitear |
|---|---|---|---|
| `appKine-api` (worktree de integración) | `akine-integracion-total` = `main` local | `1dd5740` | 0 |
| `appKine-api` (checkout principal) | **`akine-01.02-identidad`** — no tiene nada de la integración | `a3e57b1` | — |
| `appKine-web` | `main`, **pusheado** | `9b9e375` | 0 |

---

## 1. Estado por etapa

El plan declara **103 etapas** (`grep -cE "^#+ .*AKINE-[0-9]{2}\.[0-9]{2}"` sobre
`AKINE_IMPLEMENTATION_PLAN.md`) y **44 tienen registro** de cierre o de avance.

| Etapa | Estado | Evidencia |
|---|---|---|
| 00.01 – 03.06 | ✅ cerradas | registro en el plan; código en `main` |
| 04.01 – 04.05 | ✅ cerradas | `V45`–`V50`; **04.03/04.04 tenían el defecto de `saveAll`**, corregido el 29/09 |
| 05.01 – 05.04 | ✅ cerradas | `V38`; la sonda de reservas se implementó el 28/09 |
| 06.01 – 06.06 | ✅ cerradas | `V51`–`V53`, `V55` |
| 07.01 – 07.07 | ✅ cerradas | `V54`, `V56`, `V57`, `V59` |
| 08.01 – 08.04, 08.06 | 🔶 escritas e integradas, **sin registro de cierre** las tres últimas | `V58`, `V60`, `V61`, `V63`, `V64`. Módulo `activity` |
| 08.05, 08.07+ | ⬜ pendientes | sin rama ni migración |

**08.01 salteó la dependencia 07.09** por considerarla de release y no técnica: F9 está abierta
antes de que el MVP tenga su gate. Es una decisión pendiente del usuario.

---

## 2. Backend — `appKine-api`

**14 módulos** (`find src/main/java/com/akine -maxdepth 1 -type d`). **58 controllers**,
**267 endpoints** (`grep -rhoE '@(Get|Post|Put|Patch|Delete)Mapping'`).

| Módulo | Endpoints | Controllers | Archivos |
|---|---|---|---|
| `activity` | 18 | 3 | 90 |
| `billing` | 36 | 5 | 163 |
| `clinical` | 31 | 5 | 186 |
| `contracting` | 19 | 4 | 90 |
| `encounter` | 15 | 3 | 94 |
| `identity` | 21 | 7 | 107 |
| `notification` | 0 | 0 | 32 |
| `offering` | 12 | 3 | 62 |
| `organization` | 32 | 9 | 196 |
| `person` | 36 | 7 | 173 |
| `platform` | 1 | 1 | 45 |
| `reporting` | 3 | 1 | 21 |
| `resource` | 31 | 7 | 172 |
| `scheduling` | 12 | 3 | 77 |

### Migraciones

**60 archivos** en `src/main/resources/db/migration`, `V1`–`V64` con `V26`, `V29`, `V31` y `V62`
vacías o no usadas. **97 `CREATE TABLE`**.

`V45`–`V64` —veinte migraciones— **se aplicaron contra un motor por primera vez el 29/09/2026**.
Las dos cosas que eso destapó quedan en el `CLAUDE.md` del repo: los nombres de `FOREIGN KEY` y de
`CHECK` son únicos **por esquema** en MySQL, no por tabla.

### Tests

**256 clases** (`find src/test -name "*.java"`), **2.420 anotaciones `@Test`/`@ParameterizedTest`**,
**52 clases `*IT.java`**. Última corrida completa, 29/09: **2.349 unitarias + 437 de integración**,
0 fallos, 1 diferida (escenario 7b).

**No se corrió nada en esta generación del mapa**: los números de ejecución son los de la última
corrida registrada, los de conteo salen del filesystem.

### Cobertura

instrucción **71,15 %** · línea **73,03 %** · rama **63,46 %**. El gate de `jacoco:check` está en
**`0.73` línea / `0.71` instrucción**, bajado desde `0.80` el 29/09 y declarado como deuda.
Por módulo, lo que falta: `billing` 35,1 %, `encounter` 40,8 %, `activity` 45,2 %.

---

## 3. Frontend — `appKine-web`

**11 features** en `src/app/features`: `auth`, `billing`, `catalog`, `clinical`, `contracting`,
`offering`, `organization`, `person`, `platform`, `resource`, `scheduling`.

**17 rutas** en `app.routes.ts`. **110 archivos `*.spec.ts`** con **1.184 `it(`**, más **7 specs
e2e** con **43 `test(`**.

Última corrida registrada: 110 specs / 1.206 tests en verde, cobertura st 91,26 % · rama 83,61 % ·
fn 87,52 % · ln 92,37 %, y **16/16 auditorías de contraste** en Chromium real.

---

## 4. Contrato

| | Valor |
|---|---|
| Publicado por el backend | **0.44.0** — 198 paths, **267 `operationId`** |
| Consumido por el frontend | **0.29.0** (`src/environments/environment.ts`, `contractVersion`) |
| Drift endpoints ↔ contrato | **ninguno**: 267 endpoints y 267 `operationId`, y cero sufijos numéricos |

**El desfase es de 101 operaciones.** El frontend no tiene una sola pantalla de nada posterior a
F3: timeline clínico, caso, plan de tratamiento, examen y mediciones, tratamientos, enmienda, caja,
presentaciones, egresos, reportes y toda la segunda entrega.

> **El gate de contrato del frontend hoy pasa por un accidente.**
> `scripts/check-contract-version.mjs` lee `../appKine-api/openapi/akine-api.yaml`, o sea el
> **checkout principal**, que sigue en `akine-01.02-identidad` con el contrato en `0.29.0`. Coincide
> con lo que el frontend declara consumir, así que el gate da verde. En cuanto ese checkout pase a
> `main`, el gate va a marcar el desfase — **y va a tener razón**.

---

## 5. Trabajo sin commitear

**Ninguno, en ninguno de los dos repos** (`git status --short` vacío, `git ls-files --others
--exclude-standard` en 0).

**Todo pusheado al 01/10/2026**: `main` del backend en `47912db` y el del frontend en `4c6ae8b`,
los dos iguales a su remoto.

---

## 6. Runtime

| Qué | Estado |
|---|---|
| `akine-mysql` | **Up 2 days (healthy)** |
| `akine-mailpit` | **Up 2 days (healthy)** |
| `tup-coins-mysql`, `tup-coins-backend` | up — de otro proyecto, comparten el demonio |
| API en `localhost:8080` | **no responde** (`curl` da 000): el backend no está levantado |
| Web en `localhost:4200` | **no responde**: el dev server no está levantado |

Docker **funciona** (29.2.0). Fue el bloqueo que mandó sobre todo entre el 19 y el 29/09.

---

## 7. Checklist pendiente del repo

**15 items `- [ ]`** en `appKine-api/CLAUDE.md`. Los que más pesan: protección de rama en `main`,
el job de SonarQube comentado, observabilidad, las reglas `PACKAGE` de cobertura al 90 %, el gate
de `BRANCH` en JaCoCo y el escenario diferido 7b.

---

## 8. Discrepancias

| Dice | Observado |
|---|---|
| El `CLAUDE.md` del repo dice **13 módulos** ("los doce anteriores más `activity`") | Son **14**: falta nombrar **`reporting`**, que nació con 07.06 |
| El árbol del workspace y varios documentos hablan de `AKINE/docs/` | Esa carpeta **no existe** desde el 29/09: la especificación vive en `appKine-api/docs/producto/` |
| El checkout principal de `appKine-api` es donde se trabaja | Está en **`akine-01.02-identidad`**, catorce versiones de contrato atrás. Todo lo integrado vive en el worktree `.claude/worktrees/goofy-goodall-8e6772` |
| Los 24 worktrees de `.wt/` parecen trabajo en curso | Sus ramas están **todas contenidas en `main`**: son checkouts de trabajo ya integrado |

---

## 9. Próximo paso recomendado

1. **La etapa de tests de `billing` (35,1 %), `encounter` (40,8 %) y `activity` (45,2 %)**, que es
   lo que devuelve el gate de cobertura a `0.80`. Hoy esta en `0.73`/`0.71` como deuda declarada.
2. **Las pantallas de F4 en adelante.** El cliente TypeScript ya se regenero contra `0.44.0`, asi
   que el frontend por fin PUEDE llamar al backend; lo que no existe es la UI de timeline clinico,
   caso, plan de tratamiento, caja, presentaciones, reportes y segunda entrega.
3. **El QA manual del paragrafo 6**, sin correr desde 02.02 y declarado bloqueante para deploy.
   Docker funciona y el contrato esta al dia: ya no hay excusa tecnica.
