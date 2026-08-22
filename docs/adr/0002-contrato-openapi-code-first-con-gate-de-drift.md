# ADR-0002 — Contrato OpenAPI code-first con gate de drift

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.01

## Contexto

AKINE se construye en **dos repositorios independientes**: `appKine-api` y `appKine-web`.
El plan decide que el backend es propietario del contrato OpenAPI 3 canónico, que su
pipeline debe "validarla y publicarla como artefacto versionado", y que el frontend genera
un cliente TypeScript desde una versión explícita del contrato, con los DTO manuales
prohibidos.

También decide que **nunca se asuman commits atómicos entre repositorios**: un cambio de
API se despliega en dos pasos, con una ventana en la que ambas versiones coexisten.

Esto deja una pregunta abierta que el plan no responde: **quién es la fuente de verdad**,
el YAML o el código. La respuesta determina cómo se trabaja todos los días durante 29
módulos.

El riesgo concreto que hay que eliminar: que el contrato publicado y la implementación se
separen sin que nadie lo note. Si eso pasa, el frontend genera un cliente que no
corresponde a la API real, y el error aparece en runtime —o en producción— en lugar de en
compilación.

## Decisión

**Code-first con contrato commiteado y gate de drift automático.**

1. springdoc genera la especificación a partir de los controllers anotados.
2. El YAML resultante se versiona en `openapi/akine-api.yaml`.
3. `OpenApiContractIT` levanta la aplicación, descarga el contrato generado y lo compara
   con el commiteado. **Si difieren, el build falla.**
4. Para actualizarlo: `./mvnw verify -Dakine.contract.update=true`.
5. El pipeline publica `akine-api.yaml` como artefacto, y además verifica con `git status`
   que no haya cambios sin commitear en `openapi/`.

La versión del contrato es **SemVer independiente de la versión de la aplicación**, en la
propiedad `akine.contract.version`: minor para cambios aditivos, major para incompatibles.

El bloque `servers` se declara explícitamente como relativo (`/`). Si se deja que springdoc
infiera la URL del servidor, toma el puerto —aleatorio en los tests— y el contrato cambia en
cada corrida, con lo que el gate daría un falso positivo permanente.

## Alternativas consideradas

**Contract-first puro.** El YAML se escribe a mano y openapi-generator produce las interfaces
del servidor. El contrato es la fuente de verdad en el sentido más literal, y el frontend
puede generar su cliente **antes** de que el backend implemente —lo que permitiría paralelizar
el trabajo entre repos.

Descartada por el volumen: 29 módulos de especificación funcional se traducen en cientos de
endpoints, y escribir y mantener ese YAML a mano es un trabajo permanente que compite con
implementar. A eso se suma que las interfaces generadas suelen pelearse con los idioms de
Spring, y que el equipo es chico. La capacidad de generar el cliente antes que la
implementación es real, pero no compensa el costo sostenido durante todo el proyecto.

**Code-first sin gate.** springdoc expone el contrato en runtime y el pipeline lo publica.
Cero fricción. Descartada porque convierte el contrato en un **subproducto**: no aparece en
ningún diff, nadie lo revisa, y un breaking change se descubre cuando el frontend se rompe.
Eso contradice directamente el requisito del plan de que el pipeline **valide** el contrato,
y elimina la única oportunidad barata de detectar una incompatibilidad: la revisión del PR.

**Contrato generado y publicado, pero no commiteado.** Evita el ruido del YAML en los diffs.
Descartada por lo mismo: sin el archivo en el repositorio no hay diff que revisar, y el
frontend no tiene una referencia estable desde la cual generar.

## Consecuencias

### Positivas

- **Todo cambio de API aparece como diff revisable en el pull request.** Es el momento más
  barato para detectar una incompatibilidad.
- No hay YAML que mantener a mano: el contrato no puede desactualizarse respecto del código,
  porque el build lo impide.
- El contrato commiteado es una referencia estable para que el frontend genere su cliente.
- El versionado SemVer del contrato, separado del de la aplicación, permite razonar sobre
  compatibilidad sin mirar releases.

### Negativas

- Los ITs del contrato **requieren Docker**: `./mvnw test` no los corre, hay que usar
  `./mvnw verify`. Un desarrollador sin Docker no detecta el drift localmente.
- Regenerar el contrato agrega un paso manual tras cada cambio de API. Se olvida, y CI lo
  recuerda con un fallo.
- El diff del YAML generado a veces es más ruidoso que el cambio real.
- El frontend **no puede** generar su cliente antes de que el backend implemente. Si esto
  llegara a bloquear la coordinación entre repos, habría que reconsiderar contract-first
  para los módulos afectados, con un ADR nuevo.
- La calidad del contrato depende de la disciplina al anotar: sin `@Schema` y `@Operation`,
  el contrato es correcto pero pobre.

### Qué obliga a hacer

- Tras cualquier cambio de API: `./mvnw verify -Dakine.contract.update=true`, revisar el
  diff del contrato y commitearlo junto al código.
- Anotar los controllers con `@Operation` y `@Schema`: son la documentación que consume el
  frontend.
- Declarar `produces` en los controllers. Sin eso, el contrato dice `*/*` y el cliente
  generado pierde el tipado del content-type.
- Si el cambio es incompatible: subir la **major** de `akine.contract.version`, coordinar la
  regeneración del cliente en `appKine-web` y respetar la ventana de compatibilidad.
