# ADR-0015 — Requisitos clínicos y legales configurables por financiador, con gate de aprobación

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

`plan_sesiones.txt` y `Adicional.txt` proponen campos concretos de sesión, orden médica y
autorizaciones como si fueran requisitos universales del dominio. Pero ninguna fuente
adjunta validación normativa vigente ni política por financiador: son ejemplos de una
operatoria, no reglas confirmadas.

El riesgo de tomar los ejemplos como norma es doble y simétrico:

- **Exigir de más:** un campo "obligatorio" que un financiador no exige bloquea sesiones y
  facturación por una regla inventada.
- **Exigir de menos:** una presentación sin la documentación que el financiador sí exige
  se rechaza, y el rechazo aparece meses después de la prestación, cuando ya no hay cómo
  subsanar.

Además hay una dimensión legal que ningún documento del proyecto resuelve: consentimientos,
retención de datos de salud, privacidad. El impacto declarado —datos sensibles,
obligatoriedad y facturación— exige decidir el mecanismo antes de construir M15–M17
(AKINE-03.03–03.06) y el cierre de sesiones (04.05).

## Decisión

**Los requisitos de orden médica, autorización, documentación y campos obligatorios se
configuran por Financiador, Plan, Convenio y Prestación, con vigencia y trazabilidad.**

- La obligatoriedad es un dato del sistema, no una constante del código: qué exige cada
  financiador, para qué plan, bajo qué convenio y para qué prestación, desde y hasta
  cuándo.
- **Ningún ejemplo de las fuentes históricas se convierte en obligatorio global** sin una
  regla confirmada que lo respalde.
- **Antes de liberar el MVP, un responsable clínico y un responsable legal aprueban** el
  conjunto de reglas, consentimientos, retención, privacidad y documentación aplicable.
  **La evidencia de esa aprobación es parte del gate de release**: sin ella no hay
  liberación.

## Alternativas consideradas

**Hardcodear los campos históricos como obligatorios globales.** Rápido y "completo".
Descartada porque eleva ejemplos sin validación normativa a reglas de dominio; cada
financiador real que difiera obliga a tocar código, y el error se paga en sesiones
bloqueadas o presentaciones rechazadas.

**Todo opcional hasta tener certeza.** Cero fricción inicial. Descartada porque desarma la
función del sistema: si nada es exigible, la documentación requerida se descubre en el
rechazo del financiador. El sistema debe poder exigir — lo configurable es **qué** exige.

**Aprobación legal y clínica como tarea posterior al release.** Descartada porque el MVP
maneja datos de salud desde el primer paciente: consentimientos, retención y privacidad
sin aprobación previa son un riesgo legal asumido a ciegas, y adecuar después puede
implicar migrar o purgar datos ya cargados.

## Consecuencias

### Positivas

- El sistema se adapta a financiadores reales por configuración, con vigencias: un cambio
  de exigencia es un dato nuevo, no un release.
- La validación en el momento de la prestación puede explicar exactamente qué falta y por
  qué ("el plan X exige orden médica para la práctica Y desde el 01-03").
- El gate legal y clínico convierte un riesgo difuso en un checkpoint con evidencia.

### Negativas

- Un motor de reglas por Financiador/Plan/Convenio/Prestación con vigencias es una pieza
  compleja: resolución de precedencias, snapshot del requisito vigente al momento de la
  prestación, administración de la configuración.
- El release del MVP depende de aprobaciones externas al equipo de desarrollo: es un
  riesgo de calendario real y explícito.
- Hasta que cada financiador esté configurado, el sistema exige menos de lo que la
  realidad exige; la carga inicial de reglas es un trabajo operativo serio.

### Qué obliga a hacer

- `billing` es propietario del catálogo de Financiadores, Planes, Convenios y de sus
  reglas de requisitos con vigencia (M15–M17; etapas AKINE-03.03–03.06). Vigencias y
  snapshots siguen las convenciones de
  [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md).
- `clinical` valida sesión, documentación y cierre contra las reglas efectivas, resueltas
  vía `spi` al momento de la prestación (AKINE-04.05) — nunca contra constantes propias
  ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)).
- El requisito aplicado a cada prestación queda trazado (qué regla, qué versión) para
  defender la presentación ante el financiador.
- El plan incorpora el gate de aprobación clínico-legal como bloqueante de release, con
  evidencia archivada junto a los criterios de cierre de AKINE-09.
