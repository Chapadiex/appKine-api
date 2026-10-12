# Calidad de dependencias, análisis estático y cobertura por módulo (AKINE G-6)

Lo que vigila el backend sin que nadie tenga que acordarse de mirarlo. El frontend tiene su par en
`appKine-web/docs/calidad.md` (Dependabot, `npm audit` y el mismo job de Sonar).

## 1. Dependabot — `.github/dependabot.yml`

Una corrida por semana (lunes 06:00, hora de Córdoba) para Maven, GitHub Actions, el `Dockerfile`
y el `compose.yaml`.

- Las menores y parches de Maven llegan **en un solo PR** (`maven-menores`); las mayores, de a
  una, porque cada una es una decisión (Spring Boot, Java, MySQL).
- De MySQL sólo entran parches: saltar de 8.4 (LTS) a otra serie es una decisión de motor.
- `dependency-check-maven` no sube de mayor: la 13.0.0 sin clave del NVD falla (ver §2).
- Tope de PRs abiertos: 5 Maven, 3 Actions, 2 por cada fuente de imágenes. Las actualizaciones
  **de seguridad** no respetan ni el grupo ni el horario.
- Un bump que cambie el OpenAPI generado lo frena el gate de drift del contrato: hay que regenerar
  el contrato en la rama del PR.

> Las *alertas* de Dependabot se encienden en **Settings › Code security**. Es un ajuste del
> repositorio, no se versiona: lo activa quien administra el repo.

## 2. Vulnerabilidades de dependencias — job `vulnerabilidades`

```bash
./mvnw dependency-check:check
```

- **Falla sólo con CVSS ≥ 7** (`failBuildOnCVSS` en el `pom.xml`). Lo de abajo queda en el
  reporte, que el CI publica siempre como artifact `dependency-check-report` (HTML y JSON).
- No está enganchado a ninguna fase de Maven: no frena el `verify` local.
- Corre sobre las dependencias de compilación y runtime; las de test quedan fuera.
- OSS Index está apagado: desde 2025 exige cuenta y sin credenciales sus 401 tumban la corrida.
- La base del NVD vive en `~/.cache/dependency-check` (~250 MB) y **no** en `~/.m2`, para que la
  cache de Maven del CI no la arrastre. El job la restaura, la actualiza y la guarda **antes** del
  análisis: si el análisis falla por un CVE, la base nueva se guarda igual.

### La clave del NVD, y qué pasa sin ella

**El job usa OWASP sólo si existe el secreto `NVD_API_KEY`.** Es gratuita:
<https://nvd.nist.gov/developers/request-an-api-key>, y se carga en Settings › Secrets and
variables › Actions. Con clave, la primera descarga de la base tarda pocos minutos.

**Sin clave, el job analiza contra OSV.dev** con `ops/vulnerabilidades-osv.mjs`: lista las
dependencias de runtime con `dependency:list`, las consulta en lote, y falla con severidad
`HIGH` o `CRITICAL` —el equivalente del CVSS ≥ 7— salvo que el CVE esté vigente en
`dependency-check-suppressions.xml`, que es el mismo archivo de excepciones de OWASP. Tarda
segundos y no pide clave. Verificado por mutación: con las versiones de antes de este cambio falla
por los tres críticos de Tomcat 11.0.24.

Por qué hace falta el respaldo, medido:

- En una máquina de desarrollo, sin clave, OWASP bajó los 403.695 registros en **38 min** el
  09/10/2026, con reintentos por el límite de 5 pedidos cada 30 s.
- En el runner de GitHub, sin clave, el NVD respondió **429** al 15 % de la descarga el
  11/10/2026 y el job murió: las IP de los runners son compartidas y el límite se agota antes.

Los dos analizadores no son idénticos y conviene saberlo: OSV ve los CVE que publica GitHub
Advisory aunque el CPE del NVD no los matchee (los de Jackson de este cambio), y OWASP ve los que
sólo tiene el NVD (los de Connector/J, que OSV no tiene). Con la clave cargada corre OWASP.

```bash
# Local, con la base ya bajada o con tiempo:
./mvnw dependency-check:check -DnvdApiKey=<clave>
# Local, en segundos:
./mvnw -q dependency:list -DincludeScope=runtime -DoutputFile=target/dependencias.txt
node ops/vulnerabilidades-osv.mjs target/dependencias.txt
```

> **Por qué 12.2.x y no 13.0.0:** la 13.0.0 sin clave muere con `Invalid API Key, length of 0 too
> short` en el primer reintento, en vez de esperar el límite del NVD. Verificado el 09/10/2026.

### Excepciones — `dependency-check-suppressions.xml`

Dos clases, y cada entrada dice en `<notes>` por qué y desde cuándo:

1. **Falso positivo**, sin vencimiento: el CPE del NVD matchea el jar, pero el CVE no aplica.
2. **Excepción temporal**, con `until`: el CVE aplica y no hay versión que lo corrija. Cuando
   vence, el gate vuelve a fallar y obliga a decidir. Bajar el umbral nunca es la salida.

### Estado al 09/10/2026

| Dependencia | CVE (CVSS) | Qué se hizo |
|---|---|---|
| `tomcat-embed-core` 11.0.24 | 9 altas o críticas: CVE-2026-65637 (9.8), -65905 (9.8), -65182 (9.1), -68525 (9.1), -65183 (8.1), -66422 (8.1), -68569 (8.1), -65927 (7.5), -68763 (7.5) | **Bump**: `tomcat.version` 11.0.26 |
| `jackson-databind` / `jackson-core` 3.1.5 y 2.21.5 | GHSA altas: CVE-2026-68497, -91776, -91777, -89425, -89407 (el NVD no las matchea; las da OSV/GitHub Advisory) | **Bump**: `jackson-bom.version` 3.1.7, `jackson-2-bom.version` 2.21.7 |
| `kotlin-stdlib` 2.3.21 (de okhttp, vía el exportador OTLP de G-4) | CVE-2026-53914 (9.8) | **Falso positivo**: es la caché de build del compilador de Kotlin |
| `opentelemetry-api` 1.62.0 | CVE-2026-54285 (5.3) | **Falso positivo**: es de opentelemetry-js |
| `mysql-connector-j` 9.7.0 | CVE-2026-60586 (7.7), -60623 (7.1), -60624 (6.5), -61082 (6.5) | **Excepción temporal hasta el 09/11/2026.** Oracle declara afectadas 9.7.0–9.7.1 y no hay versión corregida en Maven Central. **Decisión pendiente:** esperar la corrección o bajar a 9.6.0, que no figura como afectada |

Las tres propiedades de versión (`tomcat.version`, `jackson-bom.version`,
`jackson-2-bom.version`) pisan lo que gestiona Spring Boot 4.1.1. **Hay que borrarlas** cuando el
parent traiga versiones iguales o mayores, o quedan fijando una versión vieja.

## 3. SonarQube — job `sonar`

Listo y **apagado**: el job corre siempre, comprueba si existen los secretos y, si no, termina en
verde con un aviso. Los PR desde forks no reciben secretos y también se saltean.

Para activarlo, en **Settings › Secrets and variables › Actions**:

| Secreto | Valor |
|---|---|
| `SONAR_TOKEN` | Token de análisis (SonarQube: *My Account › Security*) |
| `SONAR_HOST_URL` | URL de la instancia, p. ej. `https://sonarcloud.io` |

El proyecto se llama `akine-api`. El job **no vuelve a correr los tests**: baja el `jacoco.xml`
que publica `build` en el artifact `jacoco-report`, compila (`test-compile`) para que Sonar tenga
los `.class` y analiza. Con SonarCloud hace falta además `-Dsonar.organization=<org>`. El plugin
va fijado por versión en `SONAR_MAVEN_VERSION`, al principio del `ci.yml`.

## 4. JaCoCo por módulo — reglas en el `pom.xml`

Las reglas nacieron en G-6, medidas el **11/10/2026** con `./mvnw verify` completo sobre `main`
`955855f` (después de #77, #78, #80 y #81) más esa rama: **3.439 unitarias + 785 de integración, 0 fallos**, 1 salteada (la 7b).
BUNDLE: **línea 89,80 % · instrucción 88,56 % · rama 77,31 %**. El CI del PR midió lo mismo con
diferencias de centésimas (`clinical` instrucción 82,24 % en el CI, 82,19 % local: los ITs
concurrentes no recorren siempre las mismas ramas). El gate global sigue en **0,80** de línea e
instrucción y `BRANCH` sigue sin gate (decisión pendiente del usuario). Los números vigentes son
los de la tabla de abajo, remedidos después de `akine-cobertura-capa-rest`.

### Las reglas

Una ejecución de `jacoco:check` por módulo crítico (`check-coverage-<módulo>` en el `pom.xml`),
cada una con `includes` = `com/akine/<módulo>/**` y una regla `BUNDLE`: mide **el agregado del
módulo**. El mínimo es lo medido **redondeado hacia abajo al punto entero**, así que es un
trinquete: un cambio que baje la cobertura de un módulo crítico rompe el build.

> **Por qué no el elemento `PACKAGE` de JaCoCo.** `PACKAGE` exige el mínimo a **cada paquete
> Java** por separado, no al módulo. Con los números de hoy, la regla de `billing` quedaría en el
> 0 % de `billing.api.dto` y la de `scheduling` en el 20 % de `scheduling.api.dto`: no protegería
> nada. La regla por módulo sí. Verificado por mutación antes del merge de `main`, con `billing` en
> 86,33 % y la regla subida a 0,87: *lines covered ratio is 0.86, but expected minimum is 0.87*.

| Módulo | Línea | Instrucción | Rama | Regla (línea / instr.) | ¿Llega al 0,90? |
|---|---|---|---|---|---|
| `organization` | 98,14 % | 97,70 % | 88,24 % | 0,97 / 0,97 | sí |
| `identity` | 97,78 % | 98,19 % | 86,53 % | 0,97 / 0,97 | sí |
| `billing` | 96,48 % | 96,37 % | 81,79 % | 0,96 / 0,96 | sí |
| `encounter` | 94,56 % | 93,81 % | 84,18 % | 0,94 / 0,93 | sí |
| `scheduling` | 93,76 % | 93,05 % | 77,11 % | 0,93 / 0,93 | sí |
| `person` | 92,86 % | 92,86 % | 74,68 % | 0,92 / 0,92 | sí |
| `clinical` | 91,69 % | 91,40 % | 71,61 % | 0,91 / 0,91 | sí |

Remedido el **11/10/2026** con `./mvnw -o verify` completo sobre `main` `81344c5` más la rama
`akine-cobertura-capa-rest`: **3.740 unitarias + 794 de integración, 0 fallos**, 1 salteada (la
7b), y las ocho ejecuciones de `jacoco:check` en verde. BUNDLE: **línea 93,22 % · instrucción
92,77 % · rama 77,91 %**.

> **Única excepción al redondeo hacia abajo:** la instrucción de `identity` queda en 0,97 y no en
> 0,98. 98,19 % redondeado dejaría 0,19 puntos de margen, y la G-6 ya midió diferencias de 0,05
> entre corridas por los ITs concurrentes. Antes de esta rama `identity` estaba a 0,09 puntos de su
> mínimo de línea; ahora está a 0,78.

### Cómo se llegó al 0,90: la capa REST

Antes de `akine-cobertura-capa-rest` cuatro módulos críticos no llegaban, y el hueco tenía el mismo
nombre en los cuatro: **la capa REST**. Los ITs entran por el servicio, no por el controller, y casi
ningún controller tenía slice de `@WebMvcTest`.

| Módulo | Antes (línea / instr.) | Después | Lo que lo hundía |
|---|---|---|---|
| `billing` | 87,75 % / 83,55 % | 96,48 % / 96,37 % | `billing.api` 72,5 %, `billing.api.dto` **0 %** |
| `person` | 86,54 % / 86,28 % | 92,86 % / 92,86 % | `PersonProblemHandler`, DTOs de autorización y consumo, dos adaptadores de `person.spi` |
| `scheduling` | 86,47 % / 82,74 % | 93,76 % / 93,05 % | `scheduling.api` 61,4 %, `scheduling.api.dto` 20,2 % |
| `clinical` | 82,59 % / 82,24 % | 91,69 % / 91,40 % | `clinical.api` 56,3 %, `clinical.api.dto` 47,6 %, `ClinicalProblemHandler` |
| `identity` | 96,09 % / 97,12 % | 97,78 % / 98,19 % | margen de 0,09 puntos; `JwtEmitter` sin test de rechazo |

Los "antes" son la corrida completa sobre `main` `81344c5` (3.466 unitarias + 794 de integración).

Lo que se agregó, y por qué rinde tanto por test:

- **Un slice por módulo** —`BillingControllersTest`, `PersonControllersTest`,
  `SchedulingControllersTest`, `ClinicalControllersTest`— sobre el esqueleto común
  `testsupport/SliceRestDeModulo`. Cada uno recorre **todos** los endpoints del módulo con una
  tabla (método, ruta, cuerpo, status esperado) y afirma el borde HTTP: 201 con `Location` contra
  200 contra 204, que el cuerpo deserialice y pase la validación, el binding de filtros y la
  cabecera `X-Justificacion-Acceso` de `clinical` hasta el servicio.
- **Los servicios son mocks que devuelven vistas completas** (`testsupport/Fixture`): cada record de
  vista se arma por reflexión con todos sus campos no nulos y cada lista con un elemento, así que
  el mapeo vista → DTO se ejercita campo por campo sin escribir constructores de quince argumentos
  que se rompen con cada campo nuevo.
- **Cada advice del módulo se recorre entero**: las excepciones se leen de sus `@ExceptionHandler`
  por reflexión, se instancian y se lanzan desde un endpoint; cada una tiene que salir como Problem
  Detail 4xx con `type` de AKINE. Un handler nuevo entra al test sin tocarlo. Lo mismo para
  `IdentityProblemHandler`, en `AccountAdminControllerTest`.
- Unitarios chicos donde el hueco no era REST: `JwtEmitterTest` (lo que el verificador **rechaza**:
  token tocado, header `alg:none`, base64 inválido, claims incoherentes con firma válida) y
  `PersonSpiAdaptersTest` (el veredicto y el motivo que `AutorizacionDirectory` le da a
  `clinical`, y de dónde sale la organización del perfil que aprovisiona la reserva).

> **Lo que el recorrido de advices confirmó y no es defecto:** `OcurrenciaSinLugarException`
> delega en el handler de su causa y responde 500 si la causa no es una de las cuatro previstas.
> Es deliberado —`IllegalStateException` "causa no prevista"— y tiene su propio test con una causa
> real. Registrar un antecedente responde 201 **sin** `Location`: no hay `GET` individual de
> antecedente y un `Location` que da 404 es peor que no mandarlo. No apareció ningún defecto real
> en los controllers: ninguna excepción de los cuatro módulos terminaba en 500.

Lo que queda abajo en cada módulo ya no es la capa REST sino servicios: `PlanTratamientoService`
(60 líneas), `CoberturaPacienteService` (43), `AutorizacionService` (41) y `OrdenMedicaService`
(33), y la **rama**, que sigue sin gate: `clinical` 71,61 %, `person` 74,68 %.

### Fuera de las reglas por módulo

`offering` (89,93 % de línea), `resource` (82,84 %), `contracting` (91,88 %), `reporting`
(95,91 %), `activity` (96,39 %), `notification` (94,82 %) y `platform` (96,84 %) sólo los cubre el
gate global. `resource` es el
más bajo del backend y no es de los siete críticos que fija el plan.

> **Las reglas son estrechas a propósito**: el mínimo es lo medido redondeado hacia abajo. Si un IT
> concurrente hace que una corrida cubra un par de líneas menos que otra, el build lo va a decir;
> la salida es mirar qué rama no se ejercitó, no bajar el número.
