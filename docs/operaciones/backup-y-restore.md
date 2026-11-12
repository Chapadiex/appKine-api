# Backup y restore

## 1. Qué se respalda y por qué juntos

AKINE guarda estado en **dos lugares**, y un backup que tenga uno solo no restaura nada útil:

| Dónde | Qué | Dato que los ata |
|---|---|---|
| PostgreSQL (`AKINE_DB_*`) | Todo el modelo, el historial de Flyway, la auditoría (con sus triggers append-only `trg_audit_event_no_update/no_delete`), los 24 triggers `trg_*_updated_at_al_modificar`, la collation `akine_ci`, las extensiones `vector` y `unaccent` y el outbox de correo | — |
| Volumen en `/app/var` | `adjuntos/` (M25, administrativos) y `adjuntos-clinicos/` (M09). Raíces separadas a propósito (`application.yml`, `akine.clinical.adjuntos.base-dir`) | `adjunto_administrativo.storage_key` y `adjunto_clinico.storage_key` → archivo `<raíz>/<k[0:2]>/<k[2:4]>/<k>`, con `checksum_sha256` |

Una base sin binarios deja cada adjunto respondiendo 409 `adjunto-no-disponible`; binarios sin
base son archivos con nombre de UUID que nadie puede vincular a nada.

**Fuera del backup, a propósito:** los secretos (`AKINE_JWT_SECRET`, credenciales de base y de
SMTP, token de scrape). Viven en el gestor de secretos del entorno, no en el volumen ni en la
base, y se respaldan con él. Restaurar una base con otro `AKINE_JWT_SECRET` funciona: sólo
invalida los access tokens vivos (duran 10 minutos).

> **Backups de antes del 12/11/2026.** Los `akine-backup/1` son dumps de MySQL 8.4 y
> `restore.sh` los rechaza por formato. Sus datos pasan a PostgreSQL con la herramienta de
> `ops/migracion-mysql/`, no con estos scripts.

## 2. Cómo se hace

```bash
AKINE_BACKUP_DIR=/srv/backups/akine \
AKINE_DB_HOST=db.interno AKINE_DB_PORT=5432 AKINE_DB_NAME=akine \
AKINE_DB_USER=akine_backup AKINE_DB_PASSWORD="$(cat /run/secrets/akine_backup)" \
AKINE_ADJUNTOS_VOLUME=akine-var \
AKINE_BACKEND_IMAGE="akine-api@sha256:..." \
  ops/backup.sh
```

Si PostgreSQL es un contenedor en la misma máquina, `AKINE_DB_HOST=<nombre del contenedor>` y
`AKINE_DB_DOCKER_NETWORK=<su red>`. Si los adjuntos están en un directorio del host y no en un
volumen, `AKINE_ADJUNTOS_DIR=/ruta` en lugar de `AKINE_ADJUNTOS_VOLUME`. Sin `pg_dump` y `psql` en
el host, los scripts los corren desde la misma imagen del servidor,
`pgvector/pgvector:0.8.1-pg18-trixie` (`AKINE_DB_CLIENT=docker`, automático; `AKINE_OPS_IMAGE` la
cambia). `pg_dump` tiene que ser de la misma versión mayor que el servidor o más nuevo: con un
cliente local más viejo, forzar `AKINE_DB_CLIENT=docker`. La contraseña viaja en `PGPASSWORD`,
nunca como argumento.

Resultado: `akine-<UTC>/` con `db.sql.gz`, `adjuntos.tar.gz`, `MANIFEST` y `SHA256SUMS`.

### Decisiones del dump

`pg_dump --format=plain --no-owner --no-privileges --encoding=UTF8 --lock-wait-timeout=60s`,
comprimido con gzip:

| Opción | Por qué |
|---|---|
| (siempre) una transacción, un snapshot | `pg_dump` saca una foto consistente **sin bloquear** escrituras: se puede respaldar con el sistema en uso. Es lo que en MySQL pedía `--single-transaction` |
| SQL plano | Se verifica con `awk` y se restaura con `psql` en streaming, sin `pg_restore`. Trae extensiones, collation, funciones y triggers: sin los triggers de `audit_event` un restore deja la auditoría modificable (RN-M24-001) **y nada falla** |
| `--no-owner --no-privileges` | El dump no fija dueños ni `GRANT` del origen: todo pasa a ser de quien restaura (en MySQL había que quitar los `DEFINER` a mano) |
| `--lock-wait-timeout` (`AKINE_BACKUP_LOCK_TIMEOUT`, 60 s) | El backup falla en vez de quedar colgado detrás de una migración |
| sin `--create` | El dump no fija el nombre de la base: se restaura en cualquiera (el simulacro y `verificar-backup.sh` lo hacen) |

**El DDL no convive con el dump**: `pg_dump` toma `ACCESS SHARE` sobre cada tabla, así que un
`ALTER TABLE` de Flyway espera detrás de él (o él detrás del `ALTER`). **No correr el backup
durante un deploy** (el checklist de `rollback.md` pide un backup *antes*, no durante).

**Orden: base primero, adjuntos después.** Un archivo subido entre los dos queda como binario sin
fila, inofensivo (`verificar-adjuntos.sh` lo cuenta como huérfano). El orden inverso dejaría
filas apuntando a archivos que no están.

### Usuario de backup

No usar el usuario de la aplicación: el de backup sólo lee. No necesita superusuario.

```sql
CREATE ROLE akine_backup LOGIN PASSWORD '...';
GRANT pg_read_all_data TO akine_backup;       -- SELECT sobre todas las tablas y secuencias
GRANT CONNECT ON DATABASE akine TO akine_backup;
```

### El manifiesto (`akine-backup/2`)

`MANIFEST` describe lo que había **antes** del dump: `formato=akine-backup/2`, `motor=postgresql`,
`postgres_version`, `flyway_version`, `tablas`, `triggers` y `funciones` del esquema `public` (sin
los objetos de extensiones: las ~100 funciones de pgvector viven en `public` y no son de AKINE),
`extensiones` (`unaccent:1.1,vector:0.8.1`, las que el restore va a necesitar), `adjuntos_*` e
`imagen_backend`.

### Verificación en cada backup

`backup.sh` falla —y deja el directorio como `.partial`, que la retención no cuenta— si:

- `flyway_schema_history` tiene alguna migración fallida (la base está a medio migrar: runbook R1);
- el gzip no es válido o el dump no trae `-- PostgreSQL database dump complete` (`pg_dump` se cortó);
- el dump no trae `flyway_schema_history`, o trae otra cantidad de tablas, triggers o funciones
  que la base;
- el tar no tiene la misma cantidad de archivos que el almacenamiento.

Eso prueba que el archivo está **entero**. Que **se restaura** lo prueba lo siguiente.

### Verificación de verdad: restaurarlo

```bash
ops/verificar-backup.sh /srv/backups/akine/akine-20261009T030000Z
```

Levanta un PostgreSQL efímero con la imagen de producción, inicializado con el mismo
`ops/postgres/init` que el `compose.yaml` (rol de aplicación **sin** superusuario dueño de la base,
y `vector` creada por el superusuario, que es el paso del DBA), y un volumen efímero; corre
`restore.sh` completo con el rol de la aplicación —checksums, versión de Flyway, tablas, triggers,
funciones, extensiones y el cruce de cada adjunto con su SHA-256— y borra todo. No toca nada real
ni usa sus credenciales. **Correrlo después de cada backup programado**, o como mínimo una vez por
semana: un backup que nunca se restauró es una hipótesis.

### Retención

`AKINE_BACKUP_RETENTION_DAYS` (14) y `AKINE_BACKUP_KEEP_MIN` (7): se borran los backups
completos más viejos que la retención, **pero nunca se baja del mínimo**. Si el job de backup
falla una semana, la retención por días sola borraría el último backup bueno.

Esto cubre la retención **operativa** (volver atrás un error de ayer). La retención **legal** de
la historia clínica es otra cosa y la define DP-08 / G-14: los backups de largo plazo (mensuales,
fuera del sitio y cifrados) se configuran en el destino de almacenamiento, no en este script.

### Programación sugerida

```cron
# 03:00 UTC: backup y verificación. Si cualquiera falla, cron manda el mail y el .partial queda.
0 3 * * * cd /opt/akine && . /etc/akine/backup.env && d=$(ops/backup.sh | tail -1) && ops/verificar-backup.sh "$d"
```

Copiar `AKINE_BACKUP_DIR` fuera de la máquina (objeto con versionado, otra región). Un backup en
el mismo disco que la base no sobrevive a perder el disco.

**El backup contiene PHI** (historias clínicas, documentos de identidad): el destino tiene que
estar cifrado en reposo y con acceso restringido, igual que la base.

## 3. Restore

### Base y adjuntos completos (desastre)

1. **Detener el backend.** Restaurar debajo de una aplicación viva mezcla dos historias.
2. Crear una base **vacía**, dueña el rol con el que va a correr el backend, y un volumen de
   adjuntos **vacío** (o vaciar los existentes, a mano y con nombre propio: el script no hace
   `DROP` de nada). "Vacía" es sin tablas, funciones, tipos ni collations propias en `public`; las
   extensiones sí pueden estar.
3. **El DBA crea `vector`** en esa base: `vector` exige superusuario y el rol de la aplicación no
   puede crearla (`unaccent` es *trusted* y la crea el dueño de la base). Es el mismo reparto que
   `ops/postgres/init` en desarrollo:

   ```sql
   -- como superusuario, conectado a la base destino
   CREATE EXTENSION IF NOT EXISTS vector;
   ALTER DATABASE akine SET timezone TO 'UTC';
   ```

   La otra opción es correr el restore con un usuario que pueda crearla; entonces todo lo
   restaurado queda a su nombre. Si `vector` falta y no se puede crear, `restore.sh` corta **antes
   de cargar nada**, con un mensaje que dice esto mismo.
4. Restaurar con el rol de la aplicación:

   ```bash
   AKINE_RESTORE_CONFIRM=akine \
   AKINE_DB_HOST=... AKINE_DB_NAME=akine AKINE_DB_USER=akine AKINE_DB_PASSWORD=... \
   AKINE_ADJUNTOS_VOLUME=akine-var \
     ops/restore.sh /srv/backups/akine/akine-20261009T030000Z
   ```

   Verifica `SHA256SUMS` y el formato, exige la base vacía, crea las extensiones del `MANIFEST`,
   carga el dump **en una sola transacción** (`psql --single-transaction -v ON_ERROR_STOP=1`: si
   algo falla, la base queda vacía como estaba, no a medias), compara versión de Flyway, tablas,
   triggers, funciones y extensiones contra el `MANIFEST`, extrae los adjuntos con dueño UID 10001
   (el usuario de la imagen) y corre `verificar-adjuntos.sh`. Avisa si la zona de la sesión no es
   UTC: el dump no la trae, es de la base o del servidor.
5. Arrancar el backend con la **misma imagen** del backup (`imagen_backend` en el `MANIFEST`) o una
   posterior. Flyway valida el historial al arrancar. Una imagen **más vieja** que el esquema restaurado
   también arranca (Flyway tolera las migraciones "del futuro", `rollback.md` §1), pero sólo es
   segura si esas migraciones son compatibles con ella.
6. Correr el smoke (`smoke-post-deploy.md`).

El servidor destino tiene que ser PostgreSQL de la misma versión mayor que el origen o más nuevo
(`postgres_version` en el `MANIFEST`), con pgvector e ICU (la collation no determinista
`akine_ci`). La imagen fijada cumple las dos cosas.

El restore descarta del dump **sólo** los `COMMENT ON EXTENSION`: comentar una extensión exige ser
su dueño, y si la creó el DBA el dueño es otro. Es el equivalente de los `DEFINER` que había que
quitar en MySQL.

### Sólo la base

`ops/restore.sh <backup> --solo-db`. Caso típico: un error de datos sin pérdida de disco. Los
adjuntos subidos **después** del backup quedan como huérfanos (binario sin fila): no molestan y
`verificar-adjuntos.sh` los cuenta.

### Sólo los adjuntos

`ops/restore.sh <backup> --solo-adjuntos`, con `AKINE_RESTORE_ADJUNTOS_MODO=completar` para
reponer **sólo los archivos que faltan** sin pisar ninguno. Es el runbook R6.

### Qué se pierde

Todo lo escrito entre el backup y el desastre (RPO = intervalo entre backups; con uno diario,
hasta 24 h). Bajarlo exige archivado continuo de WAL y *point-in-time recovery* (un backup base
con `pg_basebackup` más `archive_command`/`restore_command` y `recovery_target_time`), que este
repositorio **no** automatiza: queda para cuando haya un entorno productivo con requisitos de RPO
declarados.

## 4. Probado de verdad

`ops/simulacro-restore.sh` corre el ciclo entero en un stack aislado (contenedores `g13-*`,
PostgreSQL en 5445, backend en 8095): siembra por la API una organización con cuenta activada,
tres personas y un adjunto; hace backup y lo verifica en un PostgreSQL efímero; **borra los
contenedores y los dos volúmenes**; levanta un PostgreSQL nuevo, restaura con el rol de la
aplicación y arranca el backend con la misma imagen; compara los conteos exactos de la base antes
y después, entra por la API con la misma cuenta, lista las personas y descarga el adjunto
comparando su SHA-256; y corre el smoke contra backend y frontend.

```bash
G13_API_IMAGE=akine-api:local G13_WEB_IMAGE=akine-web:local ops/simulacro-restore.sh
```

### Corrida del 12/11/2026, PostgreSQL (parcial)

Sin imagen del backend construida sobre PostgreSQL, se corrió todo lo que no la necesita, contra
una copia descartable con el esquema de V1..V13 y datos sintéticos (126 tablas, 10.424 filas, 26
triggers, 34 adjuntos clínicos):

| Paso | Resultado |
|---|---|
| `backup.sh` con el rol de la aplicación | flyway 13, 126 tablas, 26 triggers, 2 funciones, `unaccent:1.1,vector:0.8.1`, 34 adjuntos; verificación interna OK |
| `verificar-backup.sh` | Restaurado en un PostgreSQL efímero con el rol sin superusuario: todo OK, adjuntos OK=34 |
| `restore.sh` con el rol sin superusuario y sin `vector` | Corta antes de cargar, con el mensaje del DBA |
| `restore.sh` con superusuario | Completo; repetirlo sobre la base restaurada corta por base no vacía |
| Comparación independiente origen/destino | Filas de las 126 tablas, triggers (nombre, tabla, estado), cuerpo de las funciones, collation, 2212 constraints y 647 índices: idénticos. Un `UPDATE` sobre `audit_event` lo rechaza el trigger |
| Backup con un byte agregado | `SHA256SUMS no coincide`: el restore no arranca |
| Simulacro sin backend (PostgreSQL del stack con volumen, backup, verificación, destrucción, PostgreSQL nuevo, restore) | Conteos idénticos antes y después |

**No se ejecutó:** el arranque del backend con Flyway sobre la base restaurada, la siembra y la
comprobación por API, el smoke y el frontend. Queda correr el simulacro completo cuando exista la
imagen.

### Corrida del 09/10/2026, MySQL (histórica)

Imagen construida de `main` `2240971`, contrato 0.80.0, esquema `V85`, terminada con
`SIMULACRO OK` y el stack borrado:

| Paso | Resultado |
|---|---|
| Backup | 110 tablas, 2 triggers, flyway 85, 1 adjunto; verificación interna OK |
| `verificar-backup.sh` | Restaurado en un MySQL efímero: flyway, tablas, triggers y SHA-256 del adjunto OK |
| Restore tras destruir contenedores y volúmenes | Base y adjuntos restaurados; `verificar-adjuntos.sh` OK=1, sin faltantes ni huérfanos |
| Arranque del backend | 40 s; Flyway *"Successfully validated 72 migrations"*, *"Schema is up to date"* |
| Conteos exactos antes/después | Idénticos: `flyway_schema_history`, `cuenta`, `organization`, `persona`, `adjunto_administrativo`, `audit_event`, `notification_outbox`, triggers |
| Lectura por API | Login con la cuenta sembrada, mismos ids de persona, adjunto `DISPONIBLE` |
| Smoke | 11/11 verde, frontend y proxy incluidos |

**Lo que el simulacro encontró y no es de backup:** descargar un adjunto administrativo
(`GET /personas/{id}/adjuntos/{id}/contenido`) respondía **500** en `main`, restaurado o no:
`AdjuntoService.contenido` auditaba la descarga dentro de una transacción `readOnly` y MySQL
rechazaba el INSERT en `audit_event` (*"Connection is read-only"*). Existía desde 03.02 y ningún IT
descargaba un adjunto contra la base real. **Corregido** en `akine-fix-descarga-y-ratelimit`
(`LecturaAuditadaIT`), junto con otras dos lecturas con el mismo defecto; desde entonces el
simulacro exige la descarga y ya no la corre con `SIM_DESCARGA=avisar`, que queda sólo como
válvula manual contra una imagen anterior. La integridad del binario restaurado la prueba además
`verificar-adjuntos.sh` contra `checksum_sha256`.
