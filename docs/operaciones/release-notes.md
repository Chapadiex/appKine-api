# Release notes

AKINE no tiene tags todavía: una release es un merge de `main` que se despliega. El borrador de
las notas sale de los PR mergeados entre lo que corre hoy y lo que se va a desplegar.

```bash
node ops/release-notes.mjs <ref desplegado> origin/main --out notas.md
node ops/release-notes.mjs <ref desplegado> origin/main --repo-dir ../appKine-web --out notas-web.md
```

`<ref>` es un tag, un SHA o una rama. Cuando empiece a haber releases, **taggear el merge que se
despliega** (`git tag -a api-2026.10.09 <sha>`) y usar los tags: es lo que hace reproducible el
rango.

## Qué arma solo y qué no

| Sección | De dónde sale |
|---|---|
| PR incluidos, agrupados | Merges de primer padre del rango (`Merge pull request #N`, o `(#N)` de un squash), leídos del git local; título, autor, rama y etiquetas de la API de GitHub. Se agrupan por etiqueta (`bug`, `ops`) o por el prefijo de la rama: `akine-G-*` operación, `akine-DU-*` decisiones, `akine-fix-*` correcciones |
| Migraciones nuevas | Archivos **agregados** bajo `src/main/resources/db/migration` en el rango. Es lo primero que se mira: decide si el rollback es por imagen o por restore (`rollback.md`) |
| Contrato | `info.version` de `openapi/akine-api.yaml` en cada punta; si cambia la versión **mayor**, lo marca incompatible |
| Commits sin PR | Lo que entró a `main` sin pasar por PR (no debería haber nada: `main` está protegida) |
| Resumen, variables nuevas, plan de rollback, conocido y pendiente | **COMPLETAR**: los escribe una persona |

El script no decide si una migración es compatible ni qué cambio le importa a un centro: deja
el bloque marcado para que alguien lo responda.

## Token

Usa `GITHUB_TOKEN` si está definido; si no, el de `git credential fill` para `github.com` (el
mismo que usa `git push`). **Nunca lo imprime ni lo escribe.** Sin token funciona contra un repo
público con el límite de 60 pedidos por hora.

El repo se toma del `remote origin`, no del nombre de la carpeta: en esta máquina hay otro
`akine-api` que no es éste (`../CLAUDE.md`).

## Formato

[`plantilla-release-notes.md`](plantilla-release-notes.md). Las notas finales van como
descripción del deploy (o como GitHub Release cuando haya tags) y enlazan el backup previo y el
resultado del smoke.
