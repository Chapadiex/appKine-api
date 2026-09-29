# Reporte de baseline — AKINE-00.02

**Fecha de medición:** 22/08/2026
**Máquina:** Windows 11 · Java 21.0.10 (Zulu) · Maven 3.9.0 · Node 24.13.0 · npm 11.6.2 · Docker 29.2.0
**Criterio de aceptación de la etapa:** *"Existe un reporte reproducible de build/test, arquitectura y fallos; no se modificó comportamiento."*

---

## 1. Cómo reproducir este reporte

Todos los números de abajo salen de estos comandos. **No hay ningún dato estimado ni copiado
de otra corrida.**

### Backend — `appKine-api`

```bash
docker compose up -d
```

```bash
./mvnw clean verify
```

### Frontend — `appKine-web`

```bash
npm ci
```

```bash
npm run format:check && npm run lint && npm run build && npm run test:ci
```

```bash
npm run api:check
```

### E2E — requiere el stack completo levantado

```bash
npm run e2e
```

> **Precondición de los E2E:** ambos repos en la **misma rama**, base levantada y backend
> corriendo en `localhost:8080`. Playwright arranca el frontend por su cuenta.

---

## 2. Resultado del build

| Verificación | Comando | Resultado |
|---|---|---|
| Backend — build limpio | `./mvnw clean verify` | ✅ BUILD SUCCESS — 1 min 29 s |
| Frontend — build de producción | `npm run build` | ✅ 230,04 kB (64,54 kB transferidos) |
| Frontend — formato | `npm run format:check` | ✅ |
| Frontend — lint | `npm run lint` | ✅ |
| Contrato — alineación de versión | `npm run api:check` | ✅ 0.1.0 |

### Versiones de runtime verificadas

`maven-enforcer-plugin` falla el build fuera de estos rangos:

| Regla | Exigido | Resultado |
|---|---|---|
| `requireJavaVersion` | `[21,22)` | ✅ |
| `requireMavenVersion` | `[3.9.0,)` | ✅ |
| `dependencyConvergence` | sin conflictos de versión | ✅ |
| `banDuplicatePomDependencyVersions` | sin dependencias duplicadas | ✅ |

Lockfiles: `package-lock.json` presente y consistente (`npm ci` sin resolución adicional).
El backend fija versiones por el BOM de `spring-boot-starter-parent` y por propiedades
explícitas.

---

## 3. Resultado de las pruebas

### Backend — 32 pruebas

| Suite | Tipo | Cantidad | Resultado |
|---|---|---|---|
| `CodingConventionsTest` | Arquitectura (ArchUnit) | 12 | ✅ |
| `ModuleArchitectureTest` | Arquitectura (ArchUnit) | 5 | ✅ |
| `VersionControllerTest` | Slice web | 2 | ✅ |
| `GlobalExceptionHandlerTest` | Slice web | 4 | ✅ |
| `AkineApiApplicationIT` | Integración (Testcontainers) | 7 | ✅ |
| `OpenApiContractIT` | Contrato | 2 | ✅ |

### Frontend — 37 pruebas

| Suite | Tipo | Cantidad | Resultado |
|---|---|---|---|
| `app.spec.ts` | Layout y accesibilidad | 4 | ✅ |
| `estado.spec.ts` | Página, estados obligatorios | 4 | ✅ |
| `auth-token.store.spec.ts` | Seguridad del token | 6 | ✅ |
| `tenant-context.store.spec.ts` | Aislamiento multi-tenant | 6 | ✅ |
| `auth.interceptor.spec.ts` | Seguridad de credenciales | 5 | ✅ |
| `error.interceptor.spec.ts` | Contrato de errores | 5 | ✅ |
| `smoke.spec.ts` | E2E contra el stack real | 7 | ✅ |

### Total: **69 pruebas, 0 fallos**

---

## 4. Cobertura

### Backend

| Contador | Medido | Piso exigido |
|---|---|---|
| Instrucciones | **100,0 %** | 80 % |
| Líneas | **100,0 %** | 80 % |
| Complejidad | **100,0 %** | — |

Excluido: `AkineApiApplication.class` (el `main()` de arranque, sin lógica que probar).
Gate: `jacoco:check` en la fase `verify`. **Falla el build por debajo del piso.**

### Frontend

| Contador | Medido | Piso exigido |
|---|---|---|
| Statements | **98,69 %** | 80 % |
| Branches | **92,30 %** | 80 % |
| Functions | **96,29 %** | 80 % |
| Lines | **98,88 %** | 80 % |

Excluido: `src/app/api/generated/**` (código generado, ver ADR-0002 del frontend),
`src/main.ts`, `src/environments/**`, specs.
Gate: `scripts/check-coverage.mjs`, encadenado en `npm run test:ci`.

> El builder `@angular/build:unit-test` de Angular 21 permite configurar **qué** se mide,
> pero no expone umbrales: publica el reporte y sale con éxito aunque la cobertura sea del
> 5 %. Sin el script, el número sería decorativo. El gate se verificó en ambos sentidos:
> pasa por encima del piso y **falla por debajo**.

---

## 5. Arquitectura verificada

Las reglas no son documentación: son tests que fallan el build (ADR-0006 del backend).

### Backend — 17 reglas ArchUnit

**Límites entre módulos y capas** (`ModuleArchitectureTest`):

| Regla | Qué previene |
|---|---|
| `sin_ciclos_entre_modulos` | Dos módulos que en realidad son uno mal separado |
| `modulos_solo_se_alcanzan_por_su_spi` | Que se pierda el ownership de datos |
| `capas_respetan_su_direccion` | Que `domain` dependa de la infraestructura |
| `application_no_conoce_http` | Reglas de negocio inejecutables fuera de un controller |
| `domain_no_conoce_web` | Un dominio atado al framework |

**Convenciones de código** (`CodingConventionsTest`): 12 reglas — entities que no salen por
la API, sin punto flotante en el dominio, sin `java.util.Date`, sin inyección por campo, sin
`System.out`, y ubicación obligatoria de controllers, entities, repositorios, servicios,
configuración y DTO.

### Frontend — reglas ESLint propias

| Regla | Qué previene |
|---|---|
| `no-restricted-globals` sobre `localStorage`/`sessionStorage` | Que el token de historia clínica quede legible para un XSS (ADR-0001) |
| `no-console` (salvo `warn`/`error`) | Un dato clínico en la consola del navegador |
| `templateAccessibility` | Incumplir WCAG 2.1 AA |
| `ignores: src/app/api/generated/**` | Romper el gate de drift al lintear código generado |

**Verificación de que las reglas realmente disparan:** se introdujo un archivo sonda con
`localStorage.setItem`, `sessionStorage.setItem` y `console.log`. Las tres reglas fallaron
con su mensaje. La sonda se eliminó.

La única excepción a la regla del storage está en `auth-token.store.spec.ts`, con
`eslint-disable` y justificación escrita: es el test que **verifica** que el token no está
en el storage, y para probarlo necesita leerlo.

---

## 6. Base de datos

| Verificación | Resultado |
|---|---|
| Migración aplicada desde base vacía | ✅ `V1__baseline_tecnico.sql` |
| Verificado contra MySQL real (no solo Testcontainers) | ✅ |
| `flyway_schema_history` | `version=1`, `success=1` |
| `platform_schema_info` | `baseline_stage='AKINE-00.01'`, `contract_version='0.1.0'` |
| Hibernate `ddl-auto` | `validate` — Flyway es la única autoridad (ADR-0003) |
| Ownership de esquema | Toda tabla pertenece a un módulo. Hoy solo `platform` |
| Estrategia expandir–migrar–contraer | Documentada en ADR-0007 |

**Naming verificado:** `V<n>__<descripcion_en_snake_case>.sql`, charset `utf8mb4` con
collation `utf8mb4_0900_ai_ci`, tipos según ADR-0004.

**Sin tablas funcionales**, como exige la etapa AKINE-00.01: los modelos de M01–M29 se
crean en la etapa que los aprueba.

---

## 7. Contrato API

| Verificación | Resultado |
|---|---|
| Contrato publicado y versionado | ✅ `openapi/akine-api.yaml`, OpenAPI 3.1.0, SemVer `0.1.0` |
| Sincronización contrato ↔ implementación | ✅ `OpenApiContractIT` — falla ante cualquier drift |
| Doble verificación en CI | ✅ `git status --porcelain openapi/` |
| Cliente TS regenerable y sin drift | ✅ job `contrato` del pipeline del frontend |
| Versión declarada por el consumidor | ✅ `npm run api:check` → 0.1.0 |

Endpoints publicados: `GET /api/v1/version`, `GET /actuator/health`, `GET /v3/api-docs.yaml`,
`GET /swagger-ui.html`. **Ningún endpoint de negocio**, como corresponde al baseline.

---

## 8. Seguridad

| Verificación | Resultado |
|---|---|
| Secretos versionados | ✅ Ninguno. `application.yml` lee todo valor sensible por `${VAR}` |
| Credenciales en el repo | Solo las del contenedor local descartable (`akine`/`akine`), con override por variable de entorno |
| Headers de seguridad | ✅ Verificado por E2E: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, HSTS, `Referrer-Policy` |
| Filtrado de internals en errores | ✅ Verificado en dos niveles: `GlobalExceptionHandlerTest` y E2E — la respuesta no contiene `com.akine`, `org.springframework` ni `stacktrace` |
| CORS | Orígenes explícitos por configuración, nunca comodín |
| Token en el frontend | Solo en memoria. Verificado por test y por regla de lint |
| Datos de prueba | Exclusivamente sintéticos |

**Los logs de esta corrida no imprimieron variables sensibles.**

---

## 9. Fallos encontrados durante la etapa

La etapa exige que *"el baseline fallido se documenta de forma reproducible; no se oculta ni
se arregla de paso"*. Los fallos encontrados y su resolución:

| # | Fallo | Diagnóstico | Resolución |
|---|---|---|---|
| 1 | **Falso verde en `GlobalExceptionHandlerTest`** | Dos tests esperaban `500` y lo obtenían, pero por un 404 de recurso estático: el controller anidado no se registraba con `@WebMvcTest(clase)`. Pasaban sin ejercitar el handler | `@Import` explícito del controller + `controllers =` para no arrastrar `VersionController` |
| 2 | **Lint imposible de pasar** | Los 34 errores estaban en el cliente generado, que no se edita | `ignores` en `eslint.config.js`, igual que en `.prettierignore` |
| 3 | **Config de cobertura ignorada** | `vitest.config.ts` no lo lee el builder de Angular: excludes y umbrales no se aplicaban, y el reporte incluía el cliente generado | `coverageExclude` en `angular.json` + gate propio en `scripts/check-coverage.mjs` |
| 4 | **Gate de cobertura en la ruta equivocada** | El builder anida el reporte bajo `coverage/<proyecto>/`, no `coverage/` | El script busca en ambas ubicaciones |
| 5 | **La regla de lint atrapaba su propio test** | El test que verifica que el token no está en storage necesita leer el storage | `eslint-disable` acotado, con justificación escrita |

Ninguno quedó sin resolver. **Ninguno se resolvió relajando una verificación.**

---

## 10. Estado de deuda técnica

| Deuda | Etapa origen | Estado |
|---|---|---|
| Umbral de cobertura sin activar | AKINE-00.01 | ✅ **Resuelto** — activo en ambos repos |
| Sin ESLint en el frontend | AKINE-00.01 | ✅ **Resuelto** — con reglas propias de AKINE |
| Sin ADRs | AKINE-00.01 | ✅ **Resuelto** — 7 en backend, 5 en frontend |
| Job de SonarQube comentado | AKINE-00.01 | ⏳ Pendiente — falta instancia y token |
| Job E2E del pipeline comentado | AKINE-00.01 | ⏳ Pendiente — espera imagen Docker del backend |
| Observabilidad (JSON logging, Prometheus, OTel) | AKINE-00.01 | ⏳ Pendiente |
| Sin commits ni protección de rama | AKINE-00.01 | ✅ **Resuelto** — baseline commiteado en `main` |
| Frontend sin remote de GitHub | AKINE-00.01 | ⏳ Pendiente |
| CSRF deshabilitado, `permitAll` explícito | AKINE-00.01 | ⏳ F1/M02 — por diseño |
| Refresh + cola de peticiones ante 401 | AKINE-00.01 | ⏳ F1/M02 — el endpoint no existe |
| Cuentas de prueba y seed QA | AKINE-00.01 | ⏳ F1 — los módulos no existen |
| Regla que prohíba imports entre features | AKINE-00.02 | ⏳ AKINE-00.03 |
| Pruebas de carga | AKINE-00.02 | ⏳ Harness identificado (k6), sin ejecutar — ver `pruebas-de-carga.md` |

---

## 11. Cambios de comportamiento

La etapa exige *"no se modificó comportamiento"*. Se cumple, con **una excepción declarada**:

**Reestructuración de rutas y layout del frontend.** `App` pasó de contener la pantalla a
ser un layout puro (skip link, cabecera, `main` con `router-outlet`), y el contenido se movió
a `features/platform/pages/estado/`. Se agregó una ruta comodín con página 404.

Está **explícitamente dentro del alcance** de la etapa, cuya sección Frontend pide *"fijar
estructura de rutas, layout, errores y cliente API"*. Sin ella, M01 no tendría patrón que
copiar y cada feature inventaría el suyo.

Lo observable en `/` es idéntico antes y después: los 5 E2E de AKINE-00.01 siguen pasando
sin modificación. Los 2 E2E nuevos cubren la ruta 404 y la persistencia del layout.

Ningún otro cambio de la etapa altera comportamiento: ADRs, reglas de lint, umbrales de
cobertura, verificación de versiones y tests adicionales son todos verificación.
