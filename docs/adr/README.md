# Architecture Decision Records — appKine-api

Registro de decisiones arquitectónicas del backend de AKINE.

## Qué es un ADR y por qué existe

Un ADR captura **una decisión, su contexto y sus consecuencias** en el momento en que se
tomó. No documenta cómo funciona el sistema —eso está en `AGENT.md` y en el código— sino
**por qué es así y qué se descartó**.

El valor aparece meses después, cuando alguien —vos mismo incluido— se pregunta "¿por qué
está hecho de esta forma tan rara?". Sin ADR, la respuesta se reconstruye por arqueología
de commits, o directamente se revierte una decisión correcta por desconocer su motivo.

## Reglas

1. **Un ADR por decisión.** Si estás escribiendo "y además" por tercera vez, son dos ADRs.
2. **Los ADR aceptados no se editan.** Si una decisión cambia, se escribe uno nuevo que
   la supersede y se marca el anterior como `Superseded by ADR-XXXX`. El registro es
   histórico: reescribirlo destruye justamente lo que lo hace útil.
3. **Numeración correlativa**, sin reutilizar números.
4. **Las alternativas descartadas son obligatorias.** Un ADR sin alternativas no explica
   nada: toda decisión sin alternativa era, en realidad, la única opción.
5. **Las consecuencias incluyen las malas.** Un ADR que solo lista ventajas es marketing.

## Estados

| Estado | Significado |
|---|---|
| `Propuesto` | En discusión, todavía no vinculante |
| `Aceptado` | Vigente. Vinculante para todo código nuevo |
| `Superseded by ADR-XXXX` | Reemplazado. Se conserva por su valor histórico |
| `Deprecado` | Ya no aplica y no fue reemplazado |

## Índice

| ADR | Título | Estado | Etapa |
|---|---|---|---|
| [0001](0001-monolito-modular-con-paquete-spi.md) | Monolito modular con `spi` como único contrato entre módulos | Aceptado | AKINE-00.01 |
| [0002](0002-contrato-openapi-code-first-con-gate-de-drift.md) | Contrato OpenAPI code-first con gate de drift | Aceptado | AKINE-00.01 |
| [0003](0003-flyway-como-unica-autoridad-del-esquema.md) | Flyway como única autoridad del esquema | Aceptado | AKINE-00.01 |
| [0004](0004-convenciones-de-persistencia-multi-tenant.md) | Convenciones de persistencia multi-tenant | Aceptado | AKINE-00.01 |
| [0005](0005-errores-como-problem-details.md) | Errores como RFC 7807 Problem Details | Aceptado | AKINE-00.01 |
| [0006](0006-arquitectura-verificada-por-tests.md) | La arquitectura se verifica con tests, no con revisión | Aceptado | AKINE-00.01 |
| [0007](0007-expandir-migrar-contraer.md) | Expandir–migrar–contraer para todo cambio de esquema | Aceptado | AKINE-00.02 |

> Las decisiones de producto `DP-01`–`DP-09` del plan de implementación se formalizan como
> ADRs en la etapa **AKINE-00.03**, no acá. Estos ADRs cubren decisiones **técnicas** del
> baseline.

## Plantilla

```markdown
# ADR-XXXX — Título en una línea

- **Estado:** Propuesto | Aceptado | Superseded by ADR-YYYY | Deprecado
- **Fecha:** AAAA-MM-DD
- **Etapa:** AKINE-XX.YY

## Contexto

Qué problema existe y qué restricciones aplican. Sin justificar todavía.

## Decisión

Qué se decidió, en voz activa y presente.

## Alternativas consideradas

Cada una con por qué se descartó. Obligatorio.

## Consecuencias

### Positivas
### Negativas
### Qué obliga a hacer
```
