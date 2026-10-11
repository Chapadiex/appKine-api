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

## 2. OWASP dependency-check — job `vulnerabilidades`

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

### La clave del NVD

Sin clave, la **primera** descarga tarda **~40 min** (medido el 09/10/2026: 403.695 registros,
38 min, con reintentos por el límite de 5 pedidos cada 30 s). Las siguientes bajan sólo lo nuevo.
Con clave baja en pocos minutos. Es gratuita:
<https://nvd.nist.gov/developers/request-an-api-key>. Se carga como secreto **`NVD_API_KEY`** en
Settings › Secrets and variables › Actions, y el CI la pasa con `-DnvdApiKey` sólo si existe.

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

Medido el **11/10/2026** con `./mvnw verify` completo sobre esta rama (`main` `2240971` más los
bumps de G-6): **3.447 unitarias + 777 de integración, 0 fallos**, 1 salteada (la 7b). BUNDLE:
**línea 89,50 % · instrucción 88,29 % · rama 77,04 %**. El gate global sigue en **0,80** de línea e
instrucción y `BRANCH` sigue sin gate (decisión pendiente del usuario).

### Las reglas

Una ejecución de `jacoco:check` por módulo crítico (`check-coverage-<módulo>` en el `pom.xml`),
cada una con `includes` = `com/akine/<módulo>/**` y una regla `BUNDLE`: mide **el agregado del
módulo**. El mínimo es lo medido **redondeado hacia abajo al punto entero**, así que es un
trinquete: un cambio que baje la cobertura de un módulo crítico rompe el build.

> **Por qué no el elemento `PACKAGE` de JaCoCo.** `PACKAGE` exige el mínimo a **cada paquete
> Java** por separado, no al módulo. Con los números de hoy, la regla de `billing` quedaría en el
> 0 % de `billing.api.dto` y la de `scheduling` en el 20 % de `scheduling.api.dto`: no protegería
> nada. La regla por módulo sí. Verificado por mutación: con `billing` en 0,87 el build falla con
> *lines covered ratio is 0.86, but expected minimum is 0.87*.

| Módulo | Línea | Instrucción | Rama | Regla (línea / instr.) | ¿Llega al 0,90? |
|---|---|---|---|---|---|
| `organization` | 97,95 % | 97,50 % | 88,38 % | 0,97 / 0,97 | sí |
| `identity` | 96,09 % | 97,07 % | 84,00 % | 0,96 / 0,97 | sí |
| `encounter` | 94,46 % | 93,65 % | 83,76 % | 0,94 / 0,93 | sí |
| `person` | 86,54 % | 86,26 % | 73,08 % | 0,86 / 0,86 | **no** |
| `scheduling` | 86,47 % | 82,74 % | 76,38 % | 0,86 / 0,82 | **no** |
| `billing` | 86,33 % | 82,37 % | 79,85 % | 0,86 / 0,82 | **no** |
| `clinical` | 82,09 % | 81,71 % | 70,35 % | 0,82 / 0,81 | **no** |

### Los cuatro que no llegan al 0,90, y por qué

El hueco tiene el mismo nombre en los cuatro: **la capa REST**. Los ITs de estos módulos entran por
el servicio, no por el controller, y casi ningún controller tiene slice de `@WebMvcTest`; los DTO de
respuesta y los mapeos de error quedan sin ejercitar.

| Módulo | Paquetes que lo hunden (línea) |
|---|---|
| `clinical` | `clinical.api` 55,1 % (421 líneas), `clinical.api.dto` 47,6 % (206), `clinical.domain.exception` 73,8 % |
| `billing` | `billing.api` 64,2 % (531 líneas), `billing.api.dto` **0 %** (171) |
| `scheduling` | `scheduling.api` 61,4 % (277 líneas), `scheduling.api.dto` 20,2 % (99) |
| `person` | `person.infrastructure` 66,0 % (150 líneas), `person.api.dto` 73,2 % (347) |

Llevarlos al 0,90 es escribir los tests de controller de esos cuatro módulos —una etapa propia, no
parte de G-6— y subir la regla en el mismo PR.

### Fuera de las reglas por módulo

`offering` (89,93 % de línea), `resource` (82,84 %), `contracting` (91,88 %), `reporting`
(93,86 %), `activity`, `notification` y `platform` sólo los cubre el gate global. `resource` es el
más bajo del backend y no es de los siete críticos que fija el plan.

> **Las reglas son estrechas a propósito**: `identity` queda a 0,09 puntos de su mínimo. Si un IT
> concurrente hace que una corrida cubra un par de líneas menos que otra, el build lo va a decir;
> la salida es mirar qué rama no se ejercitó, no bajar el número.
