# Manual de usuario contextual — plan de implementación

> **Para agentes:** sub-skill requerida: `superpowers:subagent-driven-development` (recomendada) o
> `superpowers:executing-plans`. Los pasos usan casillas (`- [ ]`).

**Objetivo:** una entrada «Ayuda» al final del menú, con la ficha de la pantalla actual (capturas
marcadas e íconos), y el mismo contenido como base de la intención `COMO_SE_HACE` del asistente.

**Arquitectura:** las fichas Markdown viven en `appKine-api` (`src/main/resources/manual/`).
`knowledge` las carga al arrancar en un índice en memoria, filtrado como el menú, y las sirve por
cuatro `GET`. La web las muestra en un panel diferido con un render propio del subconjunto de
Markdown. Las capturas las genera un script de Playwright en la web, con las marcas quemadas.
`knowledge` implementa `assistant.spi.HerramientaDelAsistente` para `COMO_SE_HACE`.

**Stack:** Java 21 · Spring Boot 4.1 · SnakeYAML (ya en el classpath) · Angular 21 · Playwright.

**Spec:** [`AKINE-manual-de-usuario.md`](AKINE-manual-de-usuario.md). Leer las dos cosas antes de
empezar.

## Restricciones globales

- **Tests después y pocos** (`CLAUDE.md` §4 de cada repo y preferencia del usuario): reglas de
  negocio, permisos, el caso que rompe y lo que corre contra el motor. Nada de tests de framework.
- En local se corre sólo lo que toca el cambio; la suite completa la corre el CI.
- Worktrees: `wt-API-MANUAL` (rama `akine-manual-api`, ya creado) y `wt-WEB-MANUAL` (rama
  `akine-manual-web`, desde `origin/main` de `appKine-web`). Si `node_modules` es un enlace,
  quitarlo con `cmd //c "rmdir wt-WEB-MANUAL\node_modules"` antes de `worktree remove`.
- **Sin migración.** Contrato: reservar **0.181.0** para el PR 1 y **0.182.0** para el PR 3
  (0.180.0 la tiene la rama de acciones del asistente). Confirmar la versión al rebasear.
- **Bundle inicial sin margen (445/446 kB):** nada del manual en el bundle inicial. Los trazos
  nuevos de íconos van en un mapa propio, cargado con el panel.
- Formatear la web con `npx prettier@3.10.1`.
- Redacción de las fichas: voseo, oraciones cortas, nombres de botones **tal cual la pantalla** en
  negrita, cada `### tarea` autocontenida. Datos de ejemplo sintéticos únicamente.
- Commits en prosa normal, cada uno con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  Sin push ni PR hasta que el usuario lo pida.

## Foco de revisión

1. **Ruta con parámetros o query** (`/pacientes/123/resumen?tab=x`) → `/contexto` resuelve la
   ficha del patrón `/pacientes/:id/resumen` y no devuelve 204. Test en la Tarea 3.
2. **Pregunta que no está en el manual** («¿cómo trato una lumbalgia?») → el asistente se abstiene
   y no cita el pasaje más parecido. Lo cubre el set de evaluación de la Tarea 11, con al menos 8
   preguntas cuya respuesta esperada es `NINGUNO`.
3. **El modelo devuelve un id que no estaba entre las candidatas, o JSON roto** → cuenta como
   `NINGUNO`. Test en la Tarea 11.
4. **Una persona sin el permiso pregunta por esa pantalla** («¿cómo cierro la caja?» sin
   `caja:operate`) → ni el índice, ni la búsqueda, ni el asistente la mencionan. Test en las
   Tareas 4 y 11.
5. **Markdown con HTML o un enlace externo** dentro de una ficha → el render lo muestra como texto
   y no ejecuta nada. Test en la Tarea 7.

---

## PR 1 — Manual en la API (`appKine-api`, `wt-API-MANUAL`)

### Tarea 1: Formato de la ficha y carga al arrancar

**Archivos:**
- Crear: `src/main/java/com/akine/knowledge/domain/manual/FichaDelManual.java`,
  `PasajeDelManual.java`, `TipoDePasaje.java`, `ManualInvalidoException.java`
- Crear: `src/main/java/com/akine/knowledge/application/manual/CargadorDelManual.java`
- Crear: `src/main/resources/manual/caja-diaria.md` (la primera ficha, que sirve de modelo)
- Test: `src/test/java/com/akine/knowledge/application/manual/CargadorDelManualTest.java`

**Interfaces que produce:**
- `record FichaDelManual(String id, String titulo, String grupo, List<String> rutas, Set<String> permisos, FuncionIa funcionIa, Servicio servicio, List<String> relacionadas, List<String> palabras, String markdown, List<PasajeDelManual> pasajes)`.
  `funcionIa` y `servicio` son nullable y son los enums de `organization.spi`.
- `record PasajeDelManual(String id, String fichaId, TipoDePasaje tipo, String titulo, String markdown)`.
  `id` = `"<fichaId>#<slug del título>"`; el slug va en minúsculas, sin tildes y con guiones.
- `enum TipoDePasaje { PARA_QUE_SIRVE, TAREA, ESTADOS, PROBLEMAS }`
- `CargadorDelManual.cargar(ResourcePatternResolver resolver): List<FichaDelManual>` lee
  `classpath:manual/*.md`. El frontmatter va entre `---` y se lee con SnakeYAML. Parte la ficha en
  un pasaje por `### ` dentro de `## Tareas` y uno por cada `## Para qué sirve`, `## Qué significa
  cada estado` y `## Problemas comunes`.
- Lanza `ManualInvalidoException` (mensaje con el archivo) si encuentra: id repetido; id distinto
  del nombre del archivo; falta `titulo`, `grupo` o `rutas`; una `relacionadas` que no existe; un
  `permisos` o `funcionIa` desconocidos; una ficha sin `## Para qué sirve`.

- [ ] **Paso 1:** escribir los records y `CargadorDelManual`, y `caja-diaria.md` con el formato del
  spec §5.1 y §5.7: capturas `![alt](captura:caja-diaria/<nombre>)`, `:icono[x]:` y
  `> [!importante]`.
- [ ] **Paso 2:** escribir `CargadorDelManualTest` con estos casos:
  - `cargaLasFichasRealesSinErrores()`: carga desde el classpath real, el resultado no está vacío y
    cada ficha tiene al menos un pasaje `PARA_QUE_SIRVE`;
  - `partePasajesPorTarea()`: con un `.md` en `src/test/resources/manual-prueba/` que tiene 2
    tareas, salen 2 pasajes `TAREA` con ids `ficha#abrir-la-caja` y `ficha#cerrar-la-caja`;
  - `rechazaIdRepetido()` y `rechazaRelacionadaInexistente()`: `ManualInvalidoException`.
- [ ] **Paso 3:** correr
  `./mvnw -o test -Dtest="com.akine.knowledge.application.manual.*Test" -Djacoco.skip=true`.
  Debe dar PASS.
- [ ] **Paso 4:** commit `feat(manual): formato de ficha y carga al arrancar`.

### Tarea 2: Qué fichas ve cada persona

**Archivos:**
- Crear: `src/main/java/com/akine/organization/spi/ServiciosDelCentro.java`
- Modificar: `src/main/java/com/akine/organization/application/ContratacionService.java`, que la
  implementa delegando en `tieneServicio`
- Crear: `src/main/java/com/akine/knowledge/application/manual/VisibilidadDelManual.java`
- Test: `src/test/java/com/akine/knowledge/application/manual/VisibilidadDelManualTest.java`

**Interfaces:**
- Produce `organization.spi.ServiciosDelCentro { boolean tiene(long organizationId, Servicio servicio); }`
- Produce `VisibilidadDelManual.para(long accountId, long organizationId, long consultorioId): Predicate<FichaDelManual>`.
  Una ficha es visible si se cumplen las tres condiciones:
  - (`permisos` está vacío, **o** algún permiso está en `effectivePermissions ∪ ownScopedPermissions`);
  - (`funcionIa` es null **o** `FuncionesIaDelCentro.prendida`);
  - (`servicio` es null **o** `ServiciosDelCentro.tiene`).
  Calcula los dos conjuntos de permisos **una vez** por llamada, no por ficha.

- [ ] **Paso 1:** implementar.
- [ ] **Paso 2:** escribir `VisibilidadDelManualTest` con mocks de los tres `spi`:
  - `ocultaFichaSinPermiso()`: `caja:operate` no está en ningún conjunto, entonces es invisible;
  - `cuentaPermisoDeAlcancePropio()`: `turno:manage` sólo en `ownScopedPermissions`, entonces es
    visible;
  - `ocultaFichaDeFuncionIaApagada()` y `ocultaFichaDeServicioNoContratado()`.
- [ ] **Paso 3:** correr ese test y el ArchUnit
  (`./mvnw -o test -Dtest="*ArchitectureTest" -Djacoco.skip=true`). Debe dar PASS.
- [ ] **Paso 4:** commit `feat(manual): fichas visibles con las condiciones del menú`.

### Tarea 3: Índice en memoria y búsqueda

**Archivos:**
- Crear: `src/main/java/com/akine/knowledge/application/manual/IndiceDelManual.java`,
  `PasajeEncontrado.java`, `TokenizadorDelManual.java`
- Modificar: `src/main/java/com/akine/knowledge/application/BusquedaHibrida.java`, para extraer la
  fusión RRF a un método estático reutilizable si hoy no lo es, sin cambiar su comportamiento
- Test: `src/test/java/com/akine/knowledge/application/manual/IndiceDelManualTest.java`

**Interfaces:**
- Consume `CargadorDelManual.cargar` (Tarea 1) y, opcional, `ModeloEmbeddings` vía
  `ObjectProvider<ModeloEmbeddings>`, porque el bean sólo existe con la biblioteca prendida.
- Produce `record PasajeEncontrado(PasajeDelManual pasaje, FichaDelManual ficha, double puntaje)`.
- Produce, en `IndiceDelManual`, un `@Component` que carga en el constructor y falla el arranque
  si el manual es inválido:
  - `fichas(Predicate<FichaDelManual> visible): List<FichaDelManual>`, en orden de grupo y título;
  - `ficha(String id, Predicate<FichaDelManual> visible): Optional<FichaDelManual>`;
  - `porRuta(String ruta, Predicate<FichaDelManual> visible): Optional<FichaDelManual>`, que quita
    query y fragmento y compara segmento a segmento, donde `:x` acepta cualquier segmento;
  - `buscar(String consulta, Predicate<FichaDelManual> visible, String rutaActual, int n): List<PasajeEncontrado>`.
    Filtra **antes** de rankear. Fusiona por RRF palabras + vectores si hay vectores, o sólo
    palabras. Suma un plus a la ficha de `rutaActual`. Los `palabras` del frontmatter cuentan como
    texto del pasaje.
- `TokenizadorDelManual.tokens(String): List<String>`: minúsculas, sin tildes (`Normalizer` NFD) y
  sin palabras vacías del español.
- Vectores: tipo de pasaje `"passage: "` y de consulta `"query: "`, como la biblioteca. Se calculan
  en un hilo aparte después de `ApplicationReadyEvent`; mientras no terminan, `buscar` usa sólo
  palabras.

- [ ] **Paso 1:** implementar.
- [ ] **Paso 2:** escribir `IndiceDelManualTest` sobre `src/test/resources/manual-prueba/`, sin
  modelo:
  - `porRutaResuelveParametrosYQuery()`: `/pacientes/123/resumen?tab=x` devuelve la ficha con
    ruta `/pacientes/:id/resumen`;
  - `porRutaSinFichaEsVacio()`;
  - `buscarEncuentraPorSinonimo()`: «arqueo» encuentra `caja-diaria#cerrar-la-caja` como primero;
  - `buscarNoDevuelveFichasInvisibles()`: el predicado excluye `caja-diaria` y ningún resultado es
    de esa ficha;
  - `buscarPrefiereLaFichaDeLaRutaActual()`: con dos pasajes de puntaje igual, gana el de la ficha
    de `rutaActual`.
- [ ] **Paso 3:** correr el test y los de `BusquedaHibrida`
  (`-Dtest="com.akine.knowledge.**.*Test"`). Debe dar PASS.
- [ ] **Paso 4:** commit `feat(manual): índice en memoria con búsqueda híbrida`.

### Tarea 4: API y contrato

**Archivos:**
- Crear: `src/main/java/com/akine/knowledge/api/ManualController.java` y, en
  `src/main/java/com/akine/knowledge/api/dto/`, `IndiceDelManualResponse.java`,
  `FichaDelManualResponse.java` y `PasajeDelManualResponse.java`
- Modificar: `openapi/akine-api.yaml` (regenerado), `pom.xml` y `application.yml`
  (`akine.contract.version` / `info.version` = `0.181.0`)
- Test: `src/test/java/com/akine/knowledge/api/ManualReadIT.java`

**Interfaces:**
- Consume `IndiceDelManual` (Tarea 3), `VisibilidadDelManual` (Tarea 2) y el actor de
  `KnowledgeApiActor`. Contexto de organización y sede obligatorio, sin excepción en
  `TenantContextFilter`.
- Produce, todos `@Tag("Manual")` y `produces = application/json`:
  - `GET /api/v1/manual`, operationId `verIndiceDelManual`. Devuelve
    `{ grupos: [{ nombre, fichas: [{ id, titulo, tareas: [string] }] }] }`.
  - `GET /api/v1/manual/fichas/{id}`, operationId `verFichaDelManual`. Devuelve
    `{ id, titulo, grupo, markdown, relacionadas: [{ id, titulo }] }`; 404 `not-found` si no existe
    o no es visible.
  - `GET /api/v1/manual/contexto?ruta=`, operationId `verFichaDeLaRuta`. Devuelve la misma ficha, o
    204.
  - `GET /api/v1/manual/busqueda?q=` (`q` de 2 a 200 caracteres), operationId
    `buscarEnElManual`. Devuelve
    `[{ fichaId, fichaTitulo, pasajeId, titulo, fragmento, enlace }]`, hasta 10. `fragmento` son
    los primeros 240 caracteres del pasaje sin Markdown; `enlace` es la primera ruta de la ficha
    sin parámetros, o null si todas los tienen.

- [ ] **Paso 1:** implementar el controller y los DTO.
- [ ] **Paso 2:** escribir `ManualReadIT` con la cuenta de profesional y la de administrativo de
  los ITs existentes:
  - `profesionalSinCajaNoVeCajaDiaria()`: no aparece en el índice, la búsqueda «abrir caja» no la
    trae y `fichas/caja-diaria` da 404;
  - `administrativoConCajaLaVe()`;
  - `contextoSinFichaDa204()`;
  - `sinSedeEnContextoDa403()`.
- [ ] **Paso 3:** regenerar el contrato con
  `./mvnw verify -Dakine.contract.update=true -Dit.test=OpenApiContractIT -Djacoco.skip=true`,
  después correr `ManualReadIT` y `OpenApiContractIT`. Debe dar PASS, sin `operationId`
  desambiguado.
- [ ] **Paso 4:** commit `feat(manual): API de lectura del manual (contrato 0.181.0)`.

### Tarea 5: Las fichas, por tandas

**Archivos:** crear `src/main/resources/manual/<id>.md`, una por pantalla del menú
(`appKine-web/src/app/layout/navegacion-principal/secciones.ts`) más `primeros-pasos`, `glosario`,
`ficha-de-la-persona`, `atencion` y `caso-clinico`.

**Tandas**, un commit cada una, en este orden: 1) Atención; 2) Pacientes; 3) Economía;
4) Actividades; 5) Servicios y catálogo + Contratación; 6) Espacios y horarios; 7) Configuración +
Mi perfil/Preferencias; 8) `primeros-pasos`, `glosario` y las tres de detalle.

Para cada ficha:
- [ ] **Paso 1:** leer la plantilla, el componente y los textos de la pantalla en `appKine-web`,
  y el RF de `docs/producto/AKINE_Requerimientos_Integrados.md`. El frontmatter copia `permisos`,
  `funcionIa` y `servicio` **de la entrada de `secciones.ts`**. `rutas` incluye las rutas de
  detalle de la pantalla.
- [ ] **Paso 2:** redactar siguiendo el modelo de `caja-diaria.md`:
  - de 2 a 6 tareas, cada una con su `captura:` si la pantalla tiene algo que señalar, y los
    números de los pasos coinciden con las marcas;
  - `:icono[x]:` sólo con nombres que existan en `TRAZOS` o en el mapa del manual (Tarea 8);
  - estados con el texto exacto que muestra la interfaz.
- [ ] **Paso 3:** al cerrar la tanda, correr `CargadorDelManualTest`. Debe dar PASS.
- [ ] **Paso 4:** commit `docs(manual): fichas de <grupo>`.

Al terminar la tanda 8: cada sección del menú tiene ficha, comprobado contando las `ruta` de
`secciones.ts` contra las `rutas` de las fichas, y el resultado queda anotado en el cuerpo del PR.

---

## PR 2 — Panel de ayuda (`appKine-web`, `wt-WEB-MANUAL`)

### Tarea 6: Cliente y servicio del manual

**Archivos:**
- Modificar: `src/app/api/generated/**`, con `npm run api:generate` contra el contrato 0.181.0 de
  `../wt-API-MANUAL`
- Crear: `src/app/features/manual/services/manual.store.ts`

**Interfaces:**
- Produce `ManualStore` (`providedIn: 'root'`, sin importarse desde el shell para no entrar al
  bundle inicial), con estas señales y métodos:
  - `abierto: Signal<boolean>`, `abrir()`, `cerrar()`, `alternar()`;
  - `fichaActual: Signal<FichaDelManualResponse | null>`, recalculada al cambiar
    `Router.url` mientras `abierto()`, vía `verFichaDeLaRuta`. Un 204 da null;
  - `indice()`, `buscar(q)` y `verFicha(id)`, que delegan en el cliente generado.
  - Sin sede elegida, no llama a la API y expone `sinSede = true`.

- [ ] **Paso 1:** generar el cliente e implementar el store.
- [ ] **Paso 2:** correr `npm run api:check` y
  `npx ng test --watch=false --include=src/app/features/manual`. Debe dar PASS.
- [ ] **Paso 3:** commit `feat(manual): cliente 0.181.0 y store del manual`.

### Tarea 7: Render del Markdown del manual

**Archivos:**
- Crear: `src/app/features/manual/markdown/parsear-markdown.ts`
- Crear: `src/app/features/manual/markdown/markdown-del-manual.ts` (componente
  `app-markdown-del-manual`)
- Test: `src/app/features/manual/markdown/parsear-markdown.spec.ts`

**Interfaces:**
- `parsearMarkdown(md: string): Bloque[]`, función pura. Tipos:
  - `type Bloque = Titulo(nivel 2|3) | Parrafo | ListaOrdenada | ListaSimple | Aviso('importante'|'consejo') | Captura(ficha, nombre, alt)`;
  - `type Inline = Texto | Negrita | Icono(nombre) | EnlaceInterno(ruta, texto) | NumeroDeMarca(n)`.
  `②` y `(2)` al final de un paso son `NumeroDeMarca`.
- El componente recibe `markdown` y renderiza con plantilla Angular (`@for` / `@switch`),
  **nunca `innerHTML`**. Un enlace con esquema (`http:`, `javascript:`) se muestra como texto. Una
  `Captura` es `<img src="/manual/<ficha>/<nombre>.webp" alt loading="lazy">` dentro de un botón
  que emite `ampliar`.

- [ ] **Paso 1:** implementar.
- [ ] **Paso 2:** escribir `parsear-markdown.spec.ts` con estos casos:
  - `separaTituloListaYAviso`;
  - `reconoceIconoYCaptura`: `:icono[filtro]:` da `Icono('filtro')`, y
    `![x](captura:caja-diaria/abrir-caja)` da `Captura('caja-diaria','abrir-caja','x')`;
  - `htmlQuedaComoTexto`: `<img onerror=…>` sale como `Texto` literal;
  - `enlaceExternoQuedaComoTexto`.
- [ ] **Paso 3:** correr `npx ng test --watch=false --include=src/app/features/manual`. Debe dar
  PASS.
- [ ] **Paso 4:** commit `feat(manual): render seguro del Markdown del manual`.

### Tarea 8: Panel, entrada «Ayuda», F1 e íconos

**Archivos:**
- Crear: `src/app/features/manual/panel/panel-de-ayuda.ts`, `.html` y `.css`
- Crear: `src/app/features/manual/iconos-del-manual.ts`, con los trazos extra y los íconos de
  sección: `para-que-sirve`, `tareas`, `estados`, `problemas`, `relacionadas`, `importante` y
  `consejo`
- Crear: `src/app/features/manual/panel/sin-sede.ts`, con el texto estático de primeros pasos y
  glosario mínimo para antes de elegir sede
- Modificar: `src/app/layout/navegacion-principal/navegacion-principal.html` y `.ts` (entrada
  «Ayuda» fija al pie, fuera de los grupos), y el shell (`src/app/app.html` o el layout que
  contiene la barra), con `@defer (when manualAbierto)` para el panel y `(window:keydown.F1)`
- Modificar: `src/app/shared/components/icono/icono.ts`, para aceptar un `trazo` explícito como
  alternativa a `nombre`, sin agregar trazos a `TRAZOS`

**Interfaces:**
- Consume `ManualStore` (Tarea 6) y `app-markdown-del-manual` (Tarea 7).
- El panel es un `dialog` **no modal**, a la derecha y de 420 px; Escape cierra y el foco vuelve a
  «Ayuda». Contenido, en orden:
  1. buscador, que llama a `buscar` después de 300 ms y con 2 caracteres o más;
  2. ficha actual, con sus tareas en `<details>`, o «Esta pantalla todavía no tiene ficha»;
  3. relacionadas;
  4. «Ver todo el manual»;
  5. «Preguntale al asistente», visible con las mismas condiciones que la entrada «Asistente de
     datos» de `secciones.ts`. Abre `asistente-flotante` con la pregunta del buscador.
- Ampliar una captura abre un `dialog` modal a pantalla completa con la imagen; Escape cierra.

- [ ] **Paso 1:** implementar.
- [ ] **Paso 2:** correr `npm run build` y comprobar que el bundle inicial no crece respecto de
  `main` (445,60 kB). Correr `npm run lint` y los specs de `features/manual` y
  `navegacion-principal`.
- [ ] **Paso 3:** con la pila local (`akine-api` 8080 desde `wt-API-MANUAL`, `akine-web-dev` 4200
  desde `wt-WEB-MANUAL`) comprobar en el navegador:
  - F1 abre el panel;
  - al navegar de Caja a Egresos, cambia la ficha;
  - una cuenta de profesional no ve Caja en el índice.
  Sacar una captura de prueba para el usuario.
- [ ] **Paso 4:** commit `feat(manual): panel de ayuda con F1 y entrada en el menú`.

### Tarea 9: Script de capturas marcadas

**Archivos:**
- Crear: `e2e/manual/definicion.ts`, con el tipo
  `CapturaDelManual { ficha, nombre, ruta, preparar?(page), recorte: string, marcas: Marca[] }`,
  donde `Marca = { selector: string; numero?: number; forma?: 'recuadro'|'flecha'|'zona' }`
- Crear: `e2e/manual/marcas.ts`: `marcar(page, marcas)` inyecta una capa `position:fixed` con el
  recuadro y el círculo numerado sobre el `getBoundingClientRect()` de cada selector. Colores
  `--color-primario` y fondo blanco para el número, 2 px de borde. Falla si un selector no
  coincide con exactamente un elemento.
- Crear: `e2e/manual/capturar.spec.ts`: recorre `e2e/manual/capturas/*.capturas.ts`, filtra por
  `MANUAL_FICHA`, inicia sesión con la cuenta de la siembra, prepara, marca y saca
  `locator(recorte).screenshot()` con `scale: 'device'`. Convierte a WebP con `sharp`, ya presente
  en el árbol de dependencias; si no está, PNG y anotarlo. Lo guarda en
  `public/manual/<ficha>/<nombre>.webp`.
- Crear: `scripts/verificar-manual.mjs`: cruza las `captura:` de
  `../appKine-api/src/main/resources/manual/*.md` con las definiciones (falta de cada lado, y
  error si falta alguna) y los `:icono[x]:` con `TRAZOS` + `iconos-del-manual.ts`.
- Modificar: `playwright.config.ts` (proyecto `manual`, viewport 1280×800,
  `deviceScaleFactor: 2`, tema claro, excluido del proyecto por defecto) y `package.json`
  (`manual:capturas` = `playwright test --project=manual`, `manual:verificar` = el script más
  `--project=manual` con `MANUAL_SOLO_VERIFICAR=1`, que marca sin escribir).
- Crear: `e2e/manual/capturas/caja-diaria.capturas.ts`, con las capturas de la ficha modelo.

- [ ] **Paso 1:** implementar.
- [ ] **Paso 2:** sobre la pila aislada con siembra (puertos 8090/4210), correr
  `MANUAL_FICHA=caja-diaria npm run manual:capturas`. Debe dejar los `.webp` con marcas legibles.
  Mirarlos y mostrárselos al usuario.
- [ ] **Paso 3:** romper a propósito un selector y correr `npm run manual:verificar`. Debe fallar
  y nombrar el selector. Restaurarlo.
- [ ] **Paso 4:** commit `feat(manual): capturas marcadas generadas por script`.

### Tarea 10: Capturas por tanda

Sigue las tandas de la Tarea 5. Por tanda: escribir los `*.capturas.ts` de sus fichas, correr
`MANUAL_FICHA=<id> npm run manual:capturas` por ficha, **mirar cada imagen** (la marca cae sobre el
elemento y el número coincide con el paso), y correr `npm run manual:verificar` en verde. Commit
`docs(manual): capturas de <grupo>`.

---

## PR 3 — `COMO_SE_HACE` (`appKine-api` + `appKine-web`, mismas ramas, PR aparte después de 1 y 2)

### Tarea 11: Intención y herramienta del manual (api)

**Archivos:**
- Modificar: `src/main/java/com/akine/assistant/spi/Intencion.java`, agregando `COMO_SE_HACE` y
  `usaManual()`
- Modificar: `src/main/java/com/akine/assistant/spi/PedidoDeHerramienta.java`, con el campo
  `String rutaActual` al final, y su único constructor en `AsistenteService.pedido(...)`
- Modificar: `src/main/java/com/akine/assistant/spi/ClavesDeFila.java`, con `MANUAL_TITULO`,
  `MANUAL_TEXTO`, `MANUAL_FICHA` y `MANUAL_ENLACE`
- Modificar: `src/main/java/com/akine/assistant/application/Interpretador.java`, con
  `COMO_SE_HACE` en `ELEGIBLES` y de 4 a 6 ejemplos en el prompt
- Modificar: `src/main/java/com/akine/assistant/application/AsistenteService.java`. Si la
  intención es `COMO_SE_HACE`, ejecuta la herramienta **antes** de cualquier desvío a
  `consultaLibre` y sin resolver persona ni período. Un resultado con filas vacías da
  `Resultado.FUERA_DE_ALCANCE` con el texto de abstención.
- Modificar: `src/main/java/com/akine/assistant/application/Plantillas.java`, con la rama
  `COMO_SE_HACE`: «En **{ficha} → {tarea}**:\n\n{texto}», y la abstención «No encontré eso en el
  manual. Quizás te sirva:» seguida de hasta 3 títulos como enlaces
- Modificar: `src/main/java/com/akine/assistant/api/dto/ConsultaAsistenteRequest.java`, con
  `rutaActual` opcional y `@Size(max = 300)`
- Crear: `src/main/java/com/akine/knowledge/infrastructure/manual/ComoSeHaceHerramienta.java`,
  que implementa `HerramientaDelAsistente`
- Crear: `src/main/java/com/akine/knowledge/infrastructure/manual/ElectorDePasaje.java`
- Modificar: `src/main/java/com/akine/knowledge/infrastructure/config/KnowledgeConfig.java`, con el
  `ModeloDeLenguaje` del elector armado por `FabricaDeModelos` desde las mismas propiedades que el
  asistente (`akine.asistente.*`)
- Modificar: `openapi/akine-api.yaml` (regenerado, 0.182.0)
- Test: `src/test/java/com/akine/knowledge/infrastructure/manual/ElectorDePasajeTest.java`
- Crear: `docs/asistente/evaluacion/manual-preguntas.md`, con 30 preguntas y su pasaje esperado o
  `NINGUNO`, al menos 8 `NINGUNO`

**Interfaces:**
- `ElectorDePasaje.elegir(String pregunta, List<PasajeEncontrado> candidatos): Optional<PasajeEncontrado>`.
  Una llamada a `completarJson` con el esquema
  `{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}`. Los mensajes llevan
  la pregunta y la lista `id: ficha → título`. Devuelve vacío si el id es `NINGUNO`, si no está
  entre los candidatos, si el JSON es inválido o si falla el modelo con
  `ModeloNoDisponibleException`.
- `ComoSeHaceHerramienta.ejecutar(actor, pedido)`:
  1. `VisibilidadDelManual.para(actor.accountId(), actor.organizationId(), actor.consultorioId())`;
  2. `IndiceDelManual.buscar(pedido.pregunta(), visible, pedido.rutaActual(), 8)`;
  3. `ElectorDePasaje.elegir`.
  Con un elegido, devuelve una fila con las cuatro claves `MANUAL_*`. Sin elegido, devuelve filas
  vacías y, en `advertencias`, los 3 primeros candidatos como `"titulo|enlace"`.
- **ArchUnit:** antes de escribir `ComoSeHaceHerramienta`, poner una clase sonda en
  `knowledge.infrastructure` que importe `assistant.spi.Intencion` y correr
  `*ArchitectureTest`. Si falla, frenar y avisar al usuario.

- [ ] **Paso 1:** sonda de ArchUnit, como arriba.
- [ ] **Paso 2:** implementar.
- [ ] **Paso 3:** escribir `ElectorDePasajeTest` con un `ModeloDeLenguaje` falso:
  - `idFueraDeLosCandidatosEsNinguno`;
  - `jsonRotoEsNinguno`;
  - `ningunoEsVacio`;
  - `idValidoDevuelveElPasaje`.
  Agregar en el test existente de `AsistenteService` el caso
  `comoSeHaceNoVaAConsultaLibre` y, en el de `Interpretador`, el caso `comoSeHaceEsElegible`.
- [ ] **Paso 4:** regenerar el contrato (como en la Tarea 4) y correr
  `-Dtest="com.akine.assistant.**.*Test,com.akine.knowledge.**.*Test,*ArchitectureTest"`. Debe
  dar PASS.
- [ ] **Paso 5:** medir con las 30 preguntas contra Ollama en la RTX 4050
  (`appKine-api/ops/levantar.ps1`). Anotar aciertos, abstenciones correctas y latencia en
  `manual-preguntas.md`. **Aceptación:** al menos 24/30 correctas y ningún pasaje citado de una
  ficha invisible para la cuenta.
- [ ] **Paso 6:** commit `feat(asistente): intención COMO_SE_HACE con citas del manual (0.182.0)`.

### Tarea 12: El asistente en la web cita el manual

**Archivos:**
- Modificar: `src/app/api/generated/**` (0.182.0)
- Modificar: `src/app/features/asistente/services/asistente-api.ts`, que manda `rutaActual` =
  `Router.url`
- Modificar: `src/app/features/asistente/components/charla-del-asistente/*`. Si
  `interpretacion.intencion === 'COMO_SE_HACE'`, renderiza `texto` con `app-markdown-del-manual`,
  importado con `@defer` para no sumar al bundle del asistente si no hace falta, más el botón «Ver
  ficha completa», que abre el panel en esa ficha (`ManualStore.verFicha`). Las advertencias
  `titulo|enlace` de la abstención se muestran como enlaces.

- [ ] **Paso 1:** implementar.
- [ ] **Paso 2:** correr `npm run api:check`, `npm run build` (bundle inicial igual a `main`) y
  los specs de `features/asistente`.
- [ ] **Paso 3:** con la pila local e IA prendida, preguntar «¿cómo abro la caja?» desde la
  pantalla de Caja. Debe responder con los pasos y la captura marcada. Preguntar algo fuera del
  manual: debe abstenerse. Sacar una captura para el usuario.
- [ ] **Paso 4:** commit `feat(asistente): respuestas del manual con captura y ficha`.

### Tarea 13: Regla para que el manual no envejezca

**Archivos:**
- Modificar: `appKine-web/CLAUDE.md` §4, con la regla «Si cambiás una pantalla con ficha, revisá
  su ficha en `appKine-api/src/main/resources/manual/` y corré `npm run manual:verificar`; si
  cambió lo marcado, regenerá sus capturas»
- Modificar: `appKine-api/CLAUDE.md` §4, con la misma regla vista desde las fichas

- [ ] **Paso 1:** escribir las dos reglas.
- [ ] **Paso 2:** commit en cada repo, `docs: regla de mantenimiento del manual`.

---

## Autorrevisión (hecha)

- **Cobertura del spec:**
  - §5.1 → Tareas 1 y 5; §5.2 → Tareas 2, 3 y 4; §5.3 → Tarea 3; §5.4 → Tarea 11;
  - §5.5 → Tareas 6 a 8; §5.7 → Tareas 7 a 10; §5.8 → Tareas 4 y 11; §7 → Tareas 4, 8, 9 y 11;
  - §9.8 → Tareas 9 y 13.
- **Consistencia de nombres:** `FichaDelManual`, `PasajeDelManual`, `PasajeEncontrado`,
  `VisibilidadDelManual.para`, `IndiceDelManual.buscar/porRuta/ficha/fichas` y `ManualStore` se
  usan igual en todas las tareas.
- **Orden:** los PR 1 → 2 → 3 dependen en ese orden. Las tandas de fichas (Tarea 5) y de capturas
  (Tarea 10) pueden avanzar en paralelo una vez que existen las Tareas 1 y 9.
