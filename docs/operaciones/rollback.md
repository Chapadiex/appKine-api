# Rollback y checklist pre-deploy

## 1. La regla que decide todo

Flyway corre **sin `outOfOrder`** y las migraciones son **sólo hacia adelante**: no hay
`undo`, ni scripts de reversión, ni Liquibase (ADR-0003). Volver atrás una migración aplicada no
es una operación que exista.

Por eso el rollback de AKINE tiene **dos formas**, y cuál corresponde se decide **antes** de
desplegar, no durante el incidente:

| Lo que trae la release | Rollback | Pérdida de datos |
|---|---|---|
| Ninguna migración, o sólo migraciones **compatibles con la imagen anterior** (ADR-0007: expandir) | **Imagen anterior** (§2). Minutos | Ninguna |
| Alguna migración **incompatible** con la imagen anterior (contraer, `NOT NULL` nuevo, `CHECK` que reemplaza valores, rename) | **Restore** del backup previo al deploy (§3) | Todo lo escrito desde el deploy |

ADR-0007 es lo que hace posible la primera fila: **toda migración debe dejar funcionando a la
versión anterior de la aplicación durante la ventana de despliegue**. Si una release la rompe, su
rollback deja de ser "cambiar la imagen" y pasa a ser "restaurar y perder lo de hoy". Eso se
declara en las release notes (*Plan de rollback de esta release*) y lo aprueba quien despliega.

> **Lo que ya pasó y hay que conocer:** un `ALTER` que reemplaza un `CHECK` **no es aditivo**
> aunque lo parezca. `V57` reescribió un `CHECK` y borró un valor que `V56` había agregado
> —ningún gate lo vio—. Una imagen anterior que escribe ese valor choca contra el `CHECK` nuevo.
> Otro caso declarado: E-4 (#52) deja `EN_ESPERA` fuera de `ck_turno_estado`, así que una instancia
> vieja que lo escriba falla. **Revisar cada `CHECK`, `NOT NULL` y `DROP` de la release.**

### Por qué la imagen anterior arranca contra un esquema más nuevo

Flyway valida al arrancar (`validate-on-migrate: true`), y la imagen vieja **no conoce** las
migraciones nuevas. No falla porque el default de Flyway (10+; AKINE usa 12.4) es
`ignoreMigrationPatterns=*:future`: una migración aplicada que el código no tiene se toma como "del
futuro" y se tolera. Lo que **sí** sigue deteniendo el arranque es una migración fallida, una
modificada (checksum) o una faltante que el código sí declara.

> **Probado el 09/10/2026 en el stack aislado de G-13:** la imagen `akine-api:local` del 06/10
> (migraciones hasta `V65`) arrancó con readiness `UP` contra la base de `main` restaurada
> (`V85`), sin ninguna variable extra, y Hibernate (`ddl-auto: validate`) tampoco objetó: veinte
> migraciones de distancia, todas aditivas.
>
> **No sobreescribir `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS`** en ningún entorno: con un valor
> que no incluya `*:future`, el rollback por imagen muere con *"Detected applied migration not
> resolved locally"* (runbook R1).

## 2. Rollback por imagen

Precondición: la release **no** trae migraciones incompatibles (release notes) y la imagen
anterior está identificada por digest o tag inmutable.

1. Anotar la hora y el `X-Request-Id` / `traceId` de algún error que motive el rollback (para el
   post-mortem: runbook R5).
2. Volver el **backend** a la imagen anterior:

   ```bash
   docker stop akine-api && docker rm akine-api
   docker run -d --name akine-api ... akine-api@sha256:<digest anterior>
   ```

   Mismo volumen `/app/var`, mismas variables de entorno que el deploy anterior. Si la release
   agregó variables, la imagen vieja las ignora.
3. Volver el **frontend** a su imagen anterior si se desplegó junto. El frontend no tiene estado:
   es sólo cambiar la imagen. **Ojo con el contrato:** un frontend nuevo contra un backend viejo
   llama operaciones que no existen (404/400); se vuelven juntos.
4. `node ops/smoke.mjs` con `AKINE_SMOKE_CONTRACT` = el contrato **anterior**.
5. Mirar 15 minutos `http_server_requests_seconds_count{status=~"5.."}` y el log de errores.

Si el smoke falla en el paso 4 por algo del esquema (un `CHECK`, una columna `NOT NULL` que la
imagen vieja no completa), la release **no era** compatible: pasar a §3.

## 3. Rollback por restore

1. Detener backend y frontend (página de mantenimiento si existe).
2. **Backup del estado actual** (`ops/backup.sh`), aunque esté mal: es la única copia de lo
   escrito desde el deploy, y alguien va a querer recuperar algo de ahí.
3. Restaurar el backup **previo al deploy** sobre una base y un volumen vacíos
   (`backup-y-restore.md` §3).
4. Arrancar la imagen **anterior**: el esquema restaurado es el suyo.
5. Smoke con el contrato anterior.
6. Avisar a los centros afectados: lo cargado entre el deploy y el restore se perdió (turnos,
   sesiones, cobros). El backup del paso 2 es la fuente para reconstruirlo a mano.

## 4. Checklist pre-deploy

Se completa en las release notes y lo firma quien despliega.

**Migraciones**
- [ ] Lista de migraciones nuevas (`node ops/release-notes.mjs <tag anterior> <tag nuevo>`).
- [ ] Para cada una: ¿la imagen anterior sigue funcionando con ella aplicada? Revisado `DROP`,
      `RENAME`, `NOT NULL` sin default, `CHECK` reemplazado, unique nuevo sobre datos existentes.
- [ ] Números de versión mayores que la última aplicada en el entorno (sin `outOfOrder`, una
      versión menor no corre nunca).
- [ ] Probadas contra una copia del esquema del entorno: `ops/verificar-backup.sh` deja la base
      restaurada en un MySQL efímero; correr la imagen nueva contra eso es la prueba más honesta
      de que las migraciones ejecutan sobre datos reales.
- [ ] Plan de rollback declarado: **imagen anterior** o **restore**.

**Imagen y configuración**
- [ ] Imágenes nuevas identificadas por digest; **digest de las anteriores anotado**.
- [ ] Variables de entorno nuevas presentes en el entorno (una variable obligatoria que falta
      tumba el arranque: `AKINE_JWT_SECRET`, `AKINE_MAIL_*`, `AKINE_METRICS_SCRAPE_TOKEN` ≥ 32).
- [ ] `AKINE_BOOTSTRAP_ADMIN_EMAIL` sólo en el primer deploy; quitarla cuando el admin activó.
- [ ] Contrato: si cambia la versión mayor, frontend y backend se despliegan juntos.

**Datos**
- [ ] Backup completo **inmediatamente antes** (no durante) y verificado con
      `ops/verificar-backup.sh`. Anotar su directorio en las release notes.
- [ ] Outbox sin filas `PROCESANDO` viejas (runbook R2): un deploy en medio de un tick deja
      filas que vuelven solas a los 5 minutos, pero conviene no sumar ruido.

**Después**
- [ ] `node ops/smoke.mjs` verde con `AKINE_SMOKE_CONTRACT` = el contrato nuevo.
- [ ] Primera hora sin 5xx nuevos ni salto de p95 (`docs/observabilidad.md` §3).
- [ ] Ventana de compatibilidad: no desplegar una migración de *contraer* hasta que ninguna
      instancia de la imagen anterior corra en ningún entorno (ADR-0007).
