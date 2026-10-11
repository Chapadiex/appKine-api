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
| [runbooks.md](runbooks.md) | Incidentes típicos: síntomas, diagnóstico y acciones |

## Scripts (`ops/`)

| Script | Qué hace | Necesita |
|---|---|---|
| `ops/backup.sh` | mysqldump consistente + tar de adjuntos, verificación y retención | bash, Docker (o `mysqldump` local) |
| `ops/verificar-backup.sh` | Restaura un backup en un MySQL efímero y lo comprueba | bash, Docker |
| `ops/restore.sh` | Restaura base y/o adjuntos en el destino | bash, Docker (o `mysql` local) |
| `ops/verificar-adjuntos.sh` | Cruza cada fila de adjunto contra su binario (existencia y SHA-256) | bash, Docker |
| `ops/smoke.mjs` | Smoke post-deploy: health, versión, login, lectura, frontend y proxy | Node 18+ |
| `ops/release-notes.mjs` | Borrador de release notes desde los PR mergeados entre dos refs | Node 18+, git |
| `ops/simulacro-restore.sh` | Simulacro completo en un stack aislado: sembrar, backup, destruir, restaurar, comprobar | bash, Docker, Node |

Todos se configuran **por variable de entorno**: ningún script tiene un host ni una credencial
escritos adentro. Las contraseñas viajan en `MYSQL_PWD` (nunca como argumento, que se ve en
`ps`) y ningún script las imprime.

## Supuestos del despliegue

Lo que estos documentos dan por hecho. Si el despliegue real es otro, se ajustan acá primero.

- **Un backend y un frontend por entorno**, en contenedores. El frontend proxyea `/api` al
  backend (nginx, `AKINE_API_URL`) y es la única puerta pública; el actuator del backend no se
  publica a internet.
- **MySQL 8.4** con `--log-bin-trust-function-creators=1` (los triggers de `V14`; ver
  `compose.yaml`) y binlog activo.
- **Los adjuntos viven en un volumen montado en `/app/var`** del backend: `adjuntos/`
  (administrativos) y `adjuntos-clinicos/`. Sin volumen, se pierden con el contenedor.
- **Las imágenes se identifican por digest o por un tag inmutable** (por ejemplo el SHA corto del
  merge en `main`). Un `latest` no permite volver atrás: no dice a qué volver.
- **No hay registry configurado todavía** (G-3: la imagen no se publica). El procedimiento de
  rollback de abajo asume que la imagen anterior sigue en el host o en el registry que se elija.
