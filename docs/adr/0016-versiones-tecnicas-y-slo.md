# ADR-0016 — Versiones técnicas confirmadas y SLO medibles

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

El documento histórico `AKINE.pdf` proponía Angular 18, Java 17 y PostgreSQL 15. La deuda
documental lo dice sin vueltas: el stack y los SLO figuraban como **objetivos**, no como
decisiones verificadas. Para un proyecto greenfield eso significa dos agujeros:

- **Reproducibilidad:** sin versiones fijadas, cada máquina y cada pipeline resuelve
  distinto; "funciona en mi máquina" queda institucionalizado desde el día uno.
- **Criterios de aceptación no funcionales:** sin números, "que sea rápido y estable" no
  es verificable. No hay forma de dimensionar infraestructura ni de saber si el release
  está listo.

Las versiones históricas, además, ya estaban superadas al momento de crear el proyecto:
adoptarlas habría sido nacer con deuda de upgrade.

## Decisión

### Stack confirmado

Angular 21 · Java 21 con Spring Boot 4.1.x · MySQL 8.4 LTS · REST/OpenAPI 3 · JWT con
refresh · Docker · GitHub Actions · SonarQube · Playwright · Prometheus · OpenTelemetry.

### SLO y presupuesto de calidad

- **Disponibilidad** mensual ≥ 99,9 %.
- **Latencia** de API interactiva: p95 ≤ 300 ms y p99 ≤ 800 ms, excluyendo exportaciones
  y proveedores externos.
- **Carga:** 500 usuarios concurrentes con tasa de error técnico < 1 %.
- **Frontend:** LCP ≤ 2,5 s en p75.
- **Cobertura** de código nuevo ≥ 80 %, y ≥ 90 % en módulos clínicos, de seguridad y de
  economía.
- **SonarQube:** cero vulnerabilidades y cero problemas bloqueantes/críticos en código
  nuevo; duplicación de código nuevo ≤ 3 %.

Los SLO excluyen explícitamente lo que el equipo no controla (proveedores externos) y lo
que no es interactivo (exportaciones): el presupuesto mide la experiencia operativa real.

## Alternativas consideradas

**Mantener el stack histórico (Angular 18, Java 17, PostgreSQL 15).** Fidelidad a la
documentación original. Descartada porque eran propuestas de otro momento, no decisiones
verificadas, y sus versiones ya estaban superadas antes de escribir la primera línea:
arrancar un greenfield sobre versiones viejas es elegir deuda de upgrade gratis. La
decisión confirmada fija LTS y versiones vigentes con soporte por delante.

**SLO cualitativos ("rápido, estable, cubierto por tests").** Cero compromiso incómodo.
Descartada porque un objetivo sin número no puede fallar, y lo que no puede fallar no
gobierna nada: no dimensiona infraestructura, no bloquea un release lento, no detecta
regresiones de performance.

**Definir los SLO al final, con el sistema hecho.** Descartada porque invierte la
causalidad: los números de latencia y carga condicionan decisiones de diseño (índices,
paginación, N+1, colas). Conocer el presupuesto después de gastarlo garantiza pagarlo
con retrabajo.

## Consecuencias

### Positivas

- Build reproducible: AKINE-00.01 ya fijó Java 21, Spring Boot 4.1.1 y MySQL 8.4 con
  enforcer y CI; el contrato de versiones se verifica, no se recuerda.
- El release tiene criterios no funcionales objetivos: los SLO se validan con carga
  representativa antes de liberar, y el resultado es sí o no.
- El presupuesto de calidad (cobertura, Sonar, duplicación) opera como gate continuo en
  cada etapa, no como auditoría final.

### Negativas

- Spring Boot 4.1 es filo reciente: el baseline ya pagó roturas respecto de 3.x (starters
  partidos, Jackson 3, Testcontainers 2). Estar al día tiene costo de fricción.
- p95 ≤ 300 ms es exigente: prohíbe de facto diseños perezosos (N+1, payloads gigantes) y
  a veces obligará a trabajar de más en endpoints que "andaban bien".
- Cobertura ≥ 90 % en módulos clínicos, de seguridad y económicos es esfuerzo real y
  sostenido; el número invita a tests de relleno si no se revisa qué se testea.
- Los SLO requieren infraestructura de medición (Prometheus, OpenTelemetry, k6) que hay
  que montar y mantener antes de que "haga falta".

### Qué obliga a hacer

- AKINE-00.01 fija versiones reproducibles (hecho: enforcer de Java 21 exacto y Maven
  ≥ 3.9); AKINE-00.02 crea la medición base (hecho: umbral JaCoCo activo, harness k6
  identificado).
- Cada etapa protege el presupuesto de calidad: cobertura y gates de Sonar sobre código
  nuevo en el CI, sin excepciones por apuro.
- AKINE-07.08 monta la observabilidad (logging estructurado, Prometheus, OpenTelemetry)
  y AKINE-09.03 valida los SLO con carga representativa **antes** del release; AKINE-09.04
  cierra los gates de calidad.
- Las reglas `PACKAGE` de cobertura al 90 % se activan cuando existan los módulos
  clínicos, de seguridad y económicos (`clinical`, `identity`, `billing`).
