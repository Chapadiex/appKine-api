# Backup y restore

## 1. Qué se respalda y por qué juntos

AKINE guarda estado en **dos lugares**, y un backup que tenga uno solo no restaura nada útil:

| Dónde | Qué | Dato que los ata |
|---|---|---|
| MySQL (`AKINE_DB_*`) | Todo el modelo, el historial de Flyway, la auditoría (con sus triggers append-only de `V14`) y el outbox de correo | — |
| Volumen en `/app/var` | `adjuntos/` (M25, administrativos) y `adjuntos-clinicos/` (M09). Raíces separadas a propósito (`application.yml`, `akine.clinical.adjuntos.base-dir`) | `adjunto_administrativo.storage_key` y `adjunto_clinico.storage_key` → archivo `<raíz>/<k[0:2]>/<k[2:4]>/<k>`, con `checksum_sha256` |

Una base sin binarios deja cada adjunto respondiendo 409 `adjunto-no-disponible`; binarios sin
base son archivos con nombre de UUID que nadie puede vincular a nada.

**Fuera del backup, a propósito:** los secretos (`AKINE_JWT_SECRET`, credenciales de base y de
SMTP, token de scrape). Viven en el gestor de secretos del entorno, no en el volumen ni en la
base, y se respaldan con él. Restaurar una base con otro `AKINE_JWT_SECRET` funciona: sólo
invalida los access tokens vivos (duran 10 minutos).

## 2. Cómo se hace

```bash
AKINE_BACKUP_DIR=/srv/backups/akine \
AKINE_DB_HOST=db.interno AKINE_DB_PORT=3306 AKINE_DB_NAME=akine \
AKINE_DB_USER=akine_backup AKINE_DB_PASSWORD="$(cat /run/secrets/akine_backup)" \
AKINE_ADJUNTOS_VOLUME=akine-var \
AKINE_BACKEND_IMAGE="akine-api@sha256:..." \
  ops/backup.sh
```

Si MySQL es un contenedor en la misma máquina, `AKINE_DB_HOST=<nombre del contenedor>` y
`AKINE_DB_DOCKER_NETWORK=<su red>`. Si los adjuntos están en un directorio del host y no en un
volumen, `AKINE_ADJUNTOS_DIR=/ruta` en lugar de `AKINE_ADJUNTOS_VOLUME`. Sin `mysqldump` en el
host, el script lo corre desde `mysql:8.4` (`AKINE_MYSQL_CLIENT=docker`, automático).

Resultado: `akine-<UTC>/` con `db.sql.gz`, `adjuntos.tar.gz`, `MANIFEST` y `SHA256SUMS`.

### Decisiones del dump

| Opción | Por qué |
|---|---|
| `--single-transaction` | Foto consistente de InnoDB **sin bloquear** escrituras: se puede respaldar con el sistema en uso |
| `--routines --triggers --events` | `V14` crea los triggers que hacen append-only a `audit_event` (RN-M24-001). Sin ellos, un restore deja la auditoría modificable **y nada falla** |
| `--set-gtid-purged=OFF` | El dump se restaura en un servidor nuevo, no en una réplica |
| `--no-tablespaces` | Evita pedirle `PROCESS` al usuario de backup |
| sin `--databases` | El dump no fija el nombre de la base: se restaura en cualquiera (el simulacro y `verificar-backup.sh` lo hacen) |

**`--single-transaction` no tolera DDL concurrente**: un `ALTER TABLE` durante el dump lo rompe.
Las migraciones de Flyway son DDL: **no correr el backup durante un deploy** (el checklist de
`rollback.md` pide un backup *antes*, no durante).

**Orden: base primero, adjuntos después.** Un archivo subido entre los dos queda como binario sin
fila, inofensivo (`verificar-adjuntos.sh` lo cuenta como huérfano). El orden inverso dejaría
filas apuntando a archivos que no están.

### Usuario de backup

No usar el usuario de la aplicación: el de backup sólo lee.

```sql
CREATE USER 'akine_backup'@'%' IDENTIFIED BY '...';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES ON akine.* TO 'akine_backup'@'%';
-- Rutinas (hoy no hay ninguna, pero --routines las pide):
GRANT SHOW_ROUTINE ON *.* TO 'akine_backup'@'%';
```

### Verificación en cada backup

`backup.sh` falla —y deja el directorio como `.partial`, que la retención no cuenta— si:

- `flyway_schema_history` tiene alguna migración fallida (la base está a medio migrar: runbook R1);
- el gzip no es válido o el dump no termina en `-- Dump completed` (mysqldump se cortó);
- el dump no trae `flyway_schema_history`, o trae menos tablas o triggers que la base;
- el tar no tiene la misma cantidad de archivos que el almacenamiento.

Eso prueba que el archivo está **entero**. Que **se restaura** lo prueba lo siguiente.

### Verificación de verdad: restaurarlo

```bash
ops/verificar-backup.sh /srv/backups/akine/akine-20261009T030000Z
```

Levanta un MySQL 8.4 efímero (con la misma flag de triggers que producción) y un volumen
efímero, corre `restore.sh` completo —checksums, versión de Flyway, tablas, triggers y el cruce
de cada adjunto con su SHA-256— y borra todo. No toca nada real ni usa sus credenciales.
**Correrlo después de cada backup programado**, o como mínimo una vez por semana: un backup que
nunca se restauró es una hipótesis.

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
2. Crear una base **vacía** y un volumen de adjuntos **vacío** (o vaciar los existentes, a mano y
   con nombre propio: el script no hace `DROP` de nada).
3. Confirmar que el MySQL destino tiene `log_bin_trust_function_creators=1` (el script lo
   chequea y se niega si no).
4. Restaurar:

   ```bash
   AKINE_RESTORE_CONFIRM=akine \
   AKINE_DB_HOST=... AKINE_DB_NAME=akine AKINE_DB_USER=... AKINE_DB_PASSWORD=... \
   AKINE_ADJUNTOS_VOLUME=akine-var \
     ops/restore.sh /srv/backups/akine/akine-20261009T030000Z
   ```

   Verifica `SHA256SUMS`, restaura, compara versión de Flyway, tablas y triggers contra el
   `MANIFEST`, extrae los adjuntos con dueño UID 10001 (el usuario de la imagen) y corre
   `verificar-adjuntos.sh`.
5. Arrancar el backend con la **misma imagen** del backup (`imagen_backend` en el `MANIFEST`) o una
   posterior. Flyway valida el historial al arrancar. Una imagen **más vieja** que el esquema restaurado
   también arranca (Flyway tolera las migraciones "del futuro", `rollback.md` §1), pero sólo es
   segura si esas migraciones son compatibles con ella.
6. Correr el smoke (`smoke-post-deploy.md`).

El restore borra los `DEFINER` del dump: los triggers quedan a nombre del usuario que restaura.
Con el mismo usuario de origen no cambia nada; con otro, evita exigirle `SET_USER_ID`.

### Sólo la base

`ops/restore.sh <backup> --solo-db`. Caso típico: un error de datos sin pérdida de disco. Los
adjuntos subidos **después** del backup quedan como huérfanos (binario sin fila): no molestan y
`verificar-adjuntos.sh` los cuenta.

### Sólo los adjuntos

`ops/restore.sh <backup> --solo-adjuntos`, con `AKINE_RESTORE_ADJUNTOS_MODO=completar` para
reponer **sólo los archivos que faltan** sin pisar ninguno. Es el runbook R6.

### Qué se pierde

Todo lo escrito entre el backup y el desastre (RPO = intervalo entre backups; con uno diario,
hasta 24 h). Bajarlo exige binlog con retención y *point-in-time recovery* (`mysqlbinlog` desde
la posición del dump), que este repositorio **no** automatiza: queda para cuando haya un entorno
productivo con requisitos de RPO declarados.

## 4. Probado de verdad

`ops/simulacro-restore.sh` corre el ciclo entero en un stack aislado (contenedores `g13-*`,
MySQL en 3325, backend en 8095): siembra por la API una organización con cuenta activada, tres
personas y un adjunto; hace backup y lo verifica en un MySQL efímero; **borra los contenedores y
los dos volúmenes**; levanta un MySQL nuevo, restaura y arranca el backend con la misma imagen;
compara los conteos exactos de la base antes y después, entra por la API con la misma cuenta,
lista las personas y descarga el adjunto comparando su SHA-256; y corre el smoke contra backend y
frontend.

```bash
G13_API_IMAGE=akine-api:local G13_WEB_IMAGE=akine-web:local ops/simulacro-restore.sh
```

**Corrida del 09/10/2026** (imagen construida de `main` `2240971`, contrato 0.80.0, esquema
`V85`), terminada con `SIMULACRO OK` y el stack borrado:

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
(`GET /personas/{id}/adjuntos/{id}/contenido`) responde **500** en `main`, restaurado o no:
`AdjuntoService.contenido` audita la descarga dentro de una transacción `readOnly` y MySQL
rechaza el INSERT en `audit_event` (*"Connection is read-only"*). Existe desde 03.02 y ningún IT
descargaba un adjunto contra MySQL. Mientras no se corrija, el simulacro la registra como aviso
(`SIM_DESCARGA=avisar`); la integridad del binario restaurado la prueba igual
`verificar-adjuntos.sh` contra `checksum_sha256`.
