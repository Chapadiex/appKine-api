# Plantilla de release notes

`ops/release-notes.mjs` toma lo que está debajo del marcador y reemplaza los `{{...}}`. Los
bloques **COMPLETAR** los escribe una persona: el script no sabe qué cambio le importa a un
centro ni si una migración es compatible hacia atrás. Cómo se usa: `release-notes.md`.

<!-- PLANTILLA -->
# AKINE — release {{HASTA}}

- **Repositorio:** `{{REPO}}`
- **Rango:** {{DESDE}} → {{HASTA}}
- **Fecha del borrador:** {{FECHA}}
- **PR incluidos:** {{CANTIDAD_PR}}

## Resumen

**COMPLETAR:** dos o tres líneas para quien opera un centro: qué puede hacer ahora que antes no
podía, y qué cambió de algo que ya usaba. Sin nombres de clases ni de tablas.

## Antes de desplegar

### Migraciones de base

{{MIGRACIONES}}

### Contrato de la API

{{CONTRATO}}

### Variables de entorno nuevas o cambiadas

**COMPLETAR** (o "ninguna"). Una variable nueva sin default rompe el arranque: va al checklist
pre-deploy.

### Plan de rollback de esta release

**COMPLETAR:** "imagen anterior" o "restore del backup de las HH:MM", con el porqué
(`docs/operaciones/rollback.md`).

## Cambios

{{CAMBIOS}}

### Commits en `main` sin PR

{{SIN_PR}}

## Conocido y pendiente

**COMPLETAR:** deuda declarada que viaja con esta release (tests diferidos, decisiones abiertas
que afectan a lo entregado). Si no hay, borrar la sección.

## Verificación

- [ ] Backup previo verificado (`ops/verificar-backup.sh`)
- [ ] Smoke post-deploy verde (`node ops/smoke.mjs`)
- [ ] Sin 5xx nuevos en la primera hora (`http_server_requests_seconds_count{status=~"5.."}`)
