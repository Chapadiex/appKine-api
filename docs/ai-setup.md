# AI Development Environment — Setup Guide (appKine-api)

Backend de AKINE: SaaS multi-tenant para centros de kinesiología, fisioterapia y rehabilitación.

Este documento configura el entorno de trabajo de tu IA para este repositorio. Pasáselo completo
al agente al inicio de la sesión si es la primera vez, o si necesita reconfigurar todo.

Al final de este setup tendrás:
- Plugins y skills de productividad instaladas
- Workflow SDD activo para cualquier cambio no trivial
- Contexto completo del proyecto cargado en memoria

---

## Quick path

1. Instalá los plugins requeridos
2. Instalá las skills globales
3. Verificá que el repo tiene `CLAUDE.md` y `AGENT.md`
4. Cargá el contexto del proyecto desde Engram
5. Verificá el estado inicial del repo
6. Creá tu `.claude/qa-config.md` local (requerido para QA manual y deploy)

---

## 1. Plugins requeridos

> **Agente: instalalos vos.** No le pidas al usuario que abra `/plugin` ni que los instale a mano.
> Corré los comandos, verificá el resultado, y solo escalá al usuario si un comando falla.

Verificar primero qué hay instalado:

```bash
claude plugin list
```

### superpowers — workflow skills (brainstorming, TDD, planes, reviews)

Marketplace oficial de Anthropic, disponible por defecto:

```bash
claude plugin install superpowers@claude-plugins-official
```

### engram — memoria persistente entre sesiones

Requiere agregar su marketplace primero:

```bash
claude plugin marketplace add Gentleman-Programming/engram
```

```bash
claude plugin install engram@engram
```

### playwright — browser automation para QA y E2E

```bash
claude plugin install playwright@claude-plugins-official
```

### caveman — ahorro de tokens (~75 %)

```bash
claude plugin marketplace add JuliusBrussee/caveman
```

```bash
claude plugin install caveman@caveman
```

Después de instalar, los plugins se activan al reiniciar la sesión de Claude Code.
Avisale al usuario que reinicie antes de continuar.

---

## 2. Skills globales a instalar

Van en `~/.claude/skills/` (globales, disponibles en cualquier proyecto).

### engram-sdd-flow — ciclo Spec Driven Development

No viene incluida en el plugin `engram` (v0.1.1 solo trae la skill `memory`). Se copia
desde el marketplace ya clonado:

```bash
mkdir -p ~/.claude/skills/engram-sdd-flow && cp ~/.claude/plugins/marketplaces/engram/skills/sdd-flow/SKILL.md ~/.claude/skills/engram-sdd-flow/SKILL.md
```

Trigger: cambios no triviales, planificación multi-fase.

### debugging-code — debugger interactivo

```
Instalá la skill debugging-code de AlmogBaku/debug-skill en ~/.claude/skills/debugging-code/SKILL.md

Contenido del SKILL.md: fetchealo de
https://raw.githubusercontent.com/AlmogBaku/debug-skill/main/skills/debugging-code/SKILL.md
```

Trigger: programa crashea, output inesperado, excepciones, necesitás ver estado en runtime.

### playwright-skill — tests E2E y de API

```
Instalá la skill playwright-skill de testdino-hq en ~/.claude/skills/playwright-skill/SKILL.md

Contenido del SKILL.md: fetchealo de
https://raw.githubusercontent.com/testdino-hq/playwright-skill/main/SKILL.md
```

Trigger: tests E2E, Playwright, testing de flujos de API o UI.

### Templates del entorno de IA

```bash
mkdir -p ~/.claude/templates && cp ../templates/*.md ~/.claude/templates/
```

---

## 3. Verificación de archivos del repo

| Archivo | Propósito |
|---|---|
| `AGENT.md` | Lineamientos técnicos y de negocio. Leerlo completo antes de cualquier tarea. |
| `CLAUDE.md` | Workflow, skills activas, protocolo de cambios, estado actual. |
| `docs/ai-setup.md` | Esta guía. |
| `.claude/qa-config.md` | Config local de QA. **Gitignored.** Sin él no hay QA manual ni deploy. |
| `../CLAUDE.md` | Índice del workspace y regla de coordinación entre repos. |
| `../docs/` | Especificación funcional canónica y plan de implementación. |

Verificación rápida:

```bash
ls AGENT.md CLAUDE.md docs/ai-setup.md .claude/qa-config.md
```

---

## 4. Contexto del proyecto (Engram)

Antes de responder cualquier cosa, buscar contexto:

```
mem_search("AKINE backend arquitectura monolito modular")
mem_search("AKINE decisiones DP")
mem_search("appKine-api CLAUDE.md workflow")
```

Si no hay memorias: leer `AGENT.md` completo, luego `CLAUDE.md`, luego
`../docs/AKINE_IMPLEMENTATION_PLAN.md`. Esos son la fuente de verdad.

---

## 5. Estado del repo — verificación inicial

**Estado actual: greenfield.** Rama `main`, cero commits, sin `pom.xml` ni código.
No hay tests que correr todavía.

Una vez creado el proyecto en la etapa AKINE-00.01:

```bash
./mvnw test
```

Si los tests fallan antes de tocar código, reportarlo inmediatamente.

<!-- PENDIENTE: reemplazar por el comando exacto una vez que exista el pom.xml (AKINE-00.01) -->

---

## 6. QA config — validación funcional

El QA manual y el gate de deploy **se bloquean** si no existe `qa-config.md`. Sin él no se puede
levantar el stack, exercitar features contra la app real ni validar persistencia en la DB — y por
lo tanto no se puede deployar ni pushear.

Orden de búsqueda:
1. `.claude/qa-config.md` (recomendado — local, **gitignored**)
2. `docs/qa-config.md`
3. `qa-config.md` (raíz)

Es **personal de cada máquina**. Cada integrante crea el suyo desde
`~/.claude/templates/qa-config-template.md`.

Reglas de uso durante el QA:
- La validación de persistencia es innegociable: después de cada write/update/delete, consultar
  la DB directamente y confirmar el estado esperado. No alcanza con el status code.
- Verificar aislamiento de tenant en cada escenario multi-organización.
- Si el frontend tiene Local API Switch, cambiarlo a localhost antes de probar y revertirlo
  **siempre** al terminar.
- No maquillar la DB para forzar estados: si un flujo no llega, se registra como hallazgo.

---

## Resumen del proyecto

**Qué es:** backend de AKINE, SaaS multi-tenant para centros de kinesiología, fisioterapia,
rehabilitación y actividades de salud/bienestar. Gestiona el circuito clínico, administrativo y
económico completo: turnos, check-in, sesiones, historia clínica, coberturas, convenios,
obligaciones, cobros, caja, presentaciones a financiadores y reportes.

**Regla central:** cada módulo es propietario de sus tablas. Ningún módulo lee ni escribe tablas
ajenas — solo servicios/puertos internos explícitos o eventos post-commit. Toda tabla de negocio
lleva `organization_id`. El backend es la autoridad de permisos, tenant, estados y reglas.

**Stack:** Java 21 · Spring Boot 4.1.x · Maven Wrapper · MySQL 8.4 LTS · Flyway ·
OpenAPI 3 · JWT + refresh · JUnit / Testcontainers / ArchUnit · Docker ·
GitHub Actions + SonarQube · Prometheus + OpenTelemetry.

**Arquitectura:** monolito modular. Un único desplegable Spring Boot dividido por capacidad
de negocio.

```
api → application → domain
infrastructure → application / domain
domain → nada externo

módulo A ⇄ módulo B: solo por servicio/puerto interno o evento post-commit
                     nunca por repositorio ni tabla ajena
                     dependencias unidireccionales, ciclos prohibidos (ArchUnit)
```

**Módulos (package `com.akine`):**

| Módulo | Responsabilidad | Cubre |
|---|---|---|
| `platform` | config, errores, tenancy técnico, auditoría base, observabilidad | transversal |
| `identity` | cuentas, autenticación, tokens, sesiones | M02 |
| `organization` | organización, suscripción, consultorio, memberships | M01, M03, M05 |
| `resource` | espacios, disponibilidad, catálogos operativos | M04–M06 |
| `person` | Persona, Paciente, coberturas, adjuntos administrativos | M07–M08, M15, M25 |
| `contracting` | financiadores, planes, convenios, aranceles, autorizaciones | M15–M17 |
| `clinical` | HC, timeline, Caso, Plan, Sesión, evolución | M09–M14 |
| `scheduling` | slots, Turno, series, check-in, agenda | M12–M13 |
| `billing` | obligaciones, anticipos, cobros, caja, claims, egresos | M18–M22 |
| `offering` | Servicio, Oferta, habilitaciones | M27 |
| `activity` | clases, inscripciones, participación, pases, abonos | M28–M29 |
| `notification` | outbox, entrega, resultados, reintentos, templates | M26 |
| `reporting` | consultas, proyecciones, exports | M23 |

MVP inicial: M01–M27. Segunda entrega obligatoria: M28–M29.

---

## Workflow — Spec Driven Development

Todo cambio no trivial sigue este ciclo. No escribir código sin exploración y especificación.

```
brainstorming → explore → propose → spec → design → DESIGN CHALLENGE
              → tasks → apply (TDD) → /simplify → verify
              → verification-before-completion → archive
```

Pasos obligatorios en todo flujo no trivial:
- `superpowers:brainstorming` antes de empezar cualquier trabajo creativo — siempre
- **Design challenge** al cerrar la fase design — las 8 preguntas de `CLAUDE.md` §3
- `superpowers:test-driven-development` durante `apply`
- `/simplify` después de `apply`, antes de `verify`
- `superpowers:verification-before-completion` antes de cerrar

```
# Feature nueva
/caveman → brainstorming → engram-sdd-flow → DESIGN CHALLENGE → writing-plans
→ TDD → /simplify → tests + /code-review → verification-before-completion → mem_save

# Fix o cambio pequeño
/caveman → systematic-debugging → test que reproduce → fix → /simplify → tests → mem_save

# Refactor o cambio complejo
/caveman → brainstorming → using-git-worktrees → writing-plans → DESIGN CHALLENGE
→ executing-plans → /simplify → tests + /code-review → verification-before-completion
→ finishing-a-development-branch → mem_save
```

**Cambio que toca la API:** actualizar implementación → actualizar `openapi/akine-api.yaml`
→ publicar contrato versionado → avisar al repo frontend para regenerar cliente →
contract tests + E2E. Nunca asumir commit atómico entre repos.

> `/sdd-new`, `/sdd-apply`, `/sdd-ff`, `/sdd-archive` y la skill `judgment-day` **no existen**
> en esta instalación. El ciclo SDD corre por la skill `engram-sdd-flow`; el paso adversarial
> de diseño está definido explícitamente en `CLAUDE.md` §3 "Design challenge".

---

## Checklist de sesión inicial

- [ ] Plugins instalados: superpowers, engram, playwright, caveman
- [ ] Skills instaladas: engram-sdd-flow, debugging-code, playwright-skill
- [ ] `AGENT.md` leído
- [ ] `CLAUDE.md` leído
- [ ] `../docs/AKINE_IMPLEMENTATION_PLAN.md` consultado para la etapa en curso
- [ ] Contexto Engram cargado (o archivos leídos como fallback)
- [ ] Tests corriendo en verde — *N/A hasta AKINE-00.01*
- [ ] `.claude/qa-config.md` creado (local, gitignored)
- [ ] Próximo paso identificado (ver "Estado actual" en `CLAUDE.md`)
