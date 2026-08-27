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
| [0008](0008-onboarding-compuesto-transaccional.md) | Onboarding compuesto y transaccional para el registro del primer propietario | Aceptado | AKINE-00.03 |
| [0009](0009-identidad-unica-con-seleccion-de-contexto.md) | Identidad única con selección de contexto posterior al login | Aceptado | AKINE-00.03 |
| [0010](0010-historia-clinica-de-alcance-organizacional.md) | Historia Clínica de alcance organizacional | Aceptado | AKINE-00.03 |
| [0011](0011-series-de-turnos-sin-borrado.md) | Series de turnos sin borrado: la primera ausencia no elimina la serie | Aceptado | AKINE-00.03 |
| [0012](0012-maquinas-de-estado-separadas-turno-checkin-sesion.md) | Máquinas de estado separadas para Turno, Check-in y Sesión | Aceptado | AKINE-00.03 |
| [0013](0013-prepago-como-politica-configurable.md) | El prepago es una política configurable, no una condición del dominio clínico | Aceptado | AKINE-00.03 |
| [0014](0014-alcance-mvp-y-segunda-entrega-m28-m29.md) | Alcance del MVP (M01–M27) y segunda entrega obligatoria (M28–M29) | Aceptado | AKINE-00.03 |
| [0015](0015-requisitos-clinicos-y-legales.md) | Requisitos clínicos y legales configurables por financiador, con gate de aprobación | Aceptado | AKINE-00.03 |
| [0016](0016-versiones-tecnicas-y-slo.md) | Versiones técnicas confirmadas y SLO medibles | Aceptado | AKINE-00.03 |
| [0017](0017-custodia-y-ciclo-de-vida-de-los-tokens.md) | Custodia y ciclo de vida de los tokens de sesión | Aceptado | AKINE-01.02 |
| [0018](0018-anti-enumeracion-uniforme.md) | Respuestas uniformes: la autenticación no revela si una cuenta existe | Aceptado | AKINE-01.02 |
| [0019](0019-identidad-global-sin-organization-id.md) | Las tablas de identidad no llevan `organization_id` | Superseded by ADR-0023 | AKINE-01.02 |
| [0020](0020-rol-de-plataforma-sin-organization-id.md) | `platform_role` no lleva `organization_id` | Superseded by ADR-0023 | AKINE-01.03 |
| [0021](0021-catalogos-clinicos-globales-sin-organization-id.md) | Los catálogos clínicos llevan `organization_id` nullable | Superseded by ADR-0023 | AKINE-02.05 |
| [0022](0022-feriados-globales-sin-organization-id.md) | `feriado` es global, sin `organization_id` | Superseded by ADR-0023 | AKINE-02.04 |
| [0023](0023-tablas-globales-sin-organization-id.md) | Qué tabla puede no llevar `organization_id`: criterio único y lista consolidada | Aceptado | AKINE-02.06 |

> **La lista de excepciones a [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md) vive
> en [ADR-0023](0023-tablas-globales-sin-organization-id.md), y sólo ahí.** Ese ADR consolida las
> cuatro excepciones incrementales —0019, 0020, 0021 y 0022, que quedan superseded— y agrega lo
> que faltaba: **el criterio escrito** de qué hace que una tabla sea legítimamente global, y qué
> **no** califica. Una excepción nueva agrega una fila a la tabla de ADR-0023; ya no se escribe
> un ADR incremental por caso.
>
> Los cuatro superseded se conservan por su valor histórico y siguen siendo la referencia del
> argumento completo de cada caso: el aislamiento por cuenta en 0019, el rol de plataforma en
> 0020, la trampa de los `NULL` en los `UNIQUE` de MySQL y el centinela `owner_key` en 0021.
> Las migraciones anteriores a 02.06 citan en su cabecera el ADR vigente al momento de
> escribirse, que es lo correcto: quien las lea sigue el puntero hasta ADR-0023.

> Los ADRs 0001–0007 cubren decisiones **técnicas** del baseline. Las decisiones de
> producto `DP-01`–`DP-09` están formalizadas en los ADRs
> [0008](0008-onboarding-compuesto-transaccional.md)–[0016](0016-versiones-tecnicas-y-slo.md)
> (etapa **AKINE-00.03**). La fuente original, con el análisis completo de contradicciones
> entre fuentes históricas y especificación, es la sección 7 del plan de implementación
> (`../../../docs/AKINE_IMPLEMENTATION_PLAN.md`).

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
