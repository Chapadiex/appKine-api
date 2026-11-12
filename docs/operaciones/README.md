# Operación de AKINE (G-13)

Cómo se respalda, se restaura, se despliega, se vuelve atrás y se diagnostica AKINE en un
entorno real. Se apoya en la imagen del backend (G-3, `AGENT.md` §12), la del frontend
(`appKine-web/docs/imagen-docker.md`) y las señales de observabilidad (G-4,
`docs/observabilidad.md`).

| Documento | Para qué |
|---|---|
| [backup-y-restore.md](backup-y-restore.md) | Qué se respalda, cómo, con qué retención, cómo se verifica y cómo se restaura |
| [rollback.md](rollback.md) | Volver a la imagen anterior, cuándo eso alcanza y cuándo hay que restaurar; checklist pre-deploy |
| [smoke-post-deploy.md](smoke-post-deploy.md) | El chequeo que se corre después de cada despliegue, y la cuenta de humo |
| [release-notes.md](release-notes.md) | Cómo se arma el borrador de notas de una release ([plantilla](plantilla-release-notes.md)) |
| [runbooks.md](runbooks.md) | Incidentes típicos: síntomas, diagnóstico y acciones. R7 cubre los tres jobs programados de pases y abonos (F9) |
| [../release/RC-1.md](../release/RC-1.md) | Notas del primer release candidate (M01–M29, 16/10/2026): qué hace, qué no, qué lo condiciona |

## Scripts (`ops/`)

| Script | Qué hace | Necesita |
|---|---|---|
| `ops/backup.sh` | `pg_dump` consistente + tar de adjuntos, manifiesto `akine-backup/2`, verificación y retención | bash, Docker (o `pg_dump` y `psql` locales) |
| `ops/verificar-backup.sh` | Restaura un backup en un PostgreSQL efímero (rol sin superusuario, `vector` creada por el DBA) y lo comprueba | bash, Docker |
| `ops/restore.sh` | Restaura base (en una sola transacción, sobre una base vacía) y/o adjuntos en el destino | bash, Docker (o `psql` local) |
| `ops/verificar-adjuntos.sh` | Cruza cada fila de adjunto contra su binario (existencia y SHA-256) | bash, Docker |
| `ops/smoke.mjs` | Smoke post-deploy: health, versión, login, lectura, frontend y proxy | Node 18+ |
| `ops/release-notes.mjs` | Borrador de release notes desde los PR mergeados entre dos refs | Node 18+, git |
| `ops/simulacro-restore.sh` | Simulacro completo en un stack aislado: sembrar, backup, destruir, restaurar, comprobar | bash, Docker, Node |

Todos se configuran **por variable de entorno**: ningún script tiene un host ni una credencial
escritos adentro. El cliente de base es local si `pg_dump` y `psql` están en el PATH y, si no, la
imagen `pgvector/pgvector:0.8.1-pg18-trixie` por `docker run` (`AKINE_DB_CLIENT=local|docker`,
`AKINE_OPS_IMAGE`). Las contraseñas viajan en `PGPASSWORD` (nunca como argumento, que se ve en
`ps`) y ningún script las imprime.

## Supuestos del despliegue

Lo que estos documentos dan por hecho. Si el despliegue real es otro, se ajustan acá primero.

- **Un backend y un frontend por entorno**, en contenedores. El frontend proxyea `/api` al
  backend (nginx, `AKINE_API_URL`) y es la única puerta pública; el actuator del backend no se
  publica a internet.
- **PostgreSQL 18 con pgvector 0.8** (la imagen `pgvector/pgvector:0.8.1-pg18-trixie`, la del
  `compose.yaml` y los tests), con ICU para la collation `akine_ci`. La extensión `vector` exige
  superusuario: la crea el DBA en la base antes del primer arranque y antes de un restore (en
  desarrollo lo hace `ops/postgres/init`); el backend corre con un rol sin superusuario dueño de la
  base. Zona de la base en UTC (`ALTER DATABASE ... SET timezone TO 'UTC'`).
- **Los adjuntos viven en un volumen montado en `/app/var`** del backend: `adjuntos/`
  (administrativos) y `adjuntos-clinicos/`. Sin volumen, se pierden con el contenedor.
- **Las imágenes se identifican por digest o por un tag inmutable** (por ejemplo el SHA corto del
  merge en `main`). Un `latest` no permite volver atrás: no dice a qué volver.
- **No hay registry configurado todavía** (G-3: la imagen no se publica). El procedimiento de
  rollback de abajo asume que la imagen anterior sigue en el host o en el registry que se elija.
