# AKINE — Manual de usuario contextual y base de conocimiento del asistente

> Diseño del 19/11/2026. Enfoque elegido por el usuario: **A — fichas por pantalla que también lee
> la IA**. Estado: **aprobado por el usuario el 19/11**, con el agregado de capturas marcadas e
> íconos (§5.7).

## 1. Intención

Una persona que usa AKINE tiene que poder saber, sin salir de la pantalla en la que está, **para
qué sirve, cómo se hace cada tarea y qué significa cada estado**. Hoy existe la ayuda "?" campo por
campo, pero no hay ninguna explicación de la pantalla entera ni del circuito que la conecta con las
demás.

El mismo texto tiene que servirle al **asistente**: que a "¿cómo abro la caja?" responda con los
pasos del manual y un enlace a la pantalla, en vez de "todavía no sé responder eso".

**Una sola fuente**: lo que la persona lee en el panel y lo que responde la IA es el mismo párrafo.
Si el manual está mal, está mal en los dos lados y se corrige en un solo lugar.

## 2. Objetivos, no-objetivos, anti-objetivos

**Objetivos**

- Entrada **«Ayuda»** al final del menú lateral. Abre un panel con la ficha de la pantalla actual,
  un buscador y el índice del resto.
- **Una ficha por pantalla del menú** (~60, `layout/navegacion-principal/secciones.ts`) y las
  pantallas de detalle que importan (ficha de la persona, atención/sesión, caso clínico).
- Lenguaje **claro y simple**, para recepción, profesionales y administración, no para técnicos.
- El índice y la búsqueda **muestran sólo lo que la persona puede usar** según sus permisos.
- Intención nueva del asistente, **`COMO_SE_HACE`**, que responde citando el manual textual.

**No-objetivos (v1)**

- Recorridos guiados que resaltan botones **en vivo** (enfoque C): etapa posterior, si se pide.
  Las capturas marcadas (§5.7) cubren la necesidad sin depender del DOM en tiempo real.
- Capturas en modo oscuro y en móvil: v1 captura en claro y en escritorio.
- Editar el manual desde la aplicación (enfoque B, descartado).
- Manual de la consola de plataforma: es de un solo usuario y queda para después.
- Traducciones.

**Anti-objetivos** — sería un fracaso aunque funcione:

- Que el asistente **redacte** una respuesta sobre cómo se usa el sistema y se equivoque. Responde
  con el texto del manual o dice que no lo encontró.
- Que una persona vea en el manual cómo se hace algo que su rol no puede hacer, y lo tome por un
  error de la aplicación.
- Que el manual empuje el bundle inicial (445 de 446 kB): nada del manual entra al bundle inicial.
- Que el panel dependa de la IA: con la IA apagada o sin GPU, el manual funciona igual.

## 3. Restricciones

- **Bundle inicial sin margen** → el panel se carga diferido (`@defer` o ruta lazy) y el contenido
  viaja por la API, no como asset del frontend.
- **Modelo local chico** (`qwen3:4b-instruct`, contexto de 4096) → el modelo **no puede leer las
  ~300 tareas del manual**. Interpreta como hoy y, en un paso nuevo, elige entre pocas candidatas.
- **Contrato propiedad del backend** (regla de coordinación §1–§4) → endpoints nuevos en
  `openapi/akine-api.yaml`, cliente regenerado en la web, sin DTO a mano.
- **Sin migración**: el manual no es dato del centro, es parte de la versión desplegada (ver §5.3).
  Además, `V29` ya la tiene reservada otra rama (acciones del asistente).
- **Choque posible con la rama de acciones del asistente** (`wt-API-ACCIONES`, sin push), que toca
  `AsistenteService` e `Intencion`. Quien integre segundo resuelve el conflicto y renumera el
  contrato.

## 4. Enfoques considerados

| | Enfoque | Trade-off | Decisión |
|---|---|---|---|
| **A** | Fichas Markdown versionadas, servidas por la API e indexadas para el asistente | Una sola fuente en git, reusa embeddings y el patrón "interpretar y nada más". Cada cambio del manual pide deploy | **Elegido por el usuario** |
| B | Manual en tablas, editable desde la consola de plataforma | Sin deploy, pero fuera de git, con pantallas, migración y contrato propios, y se desincroniza de la interfaz | Descartado |
| C | A + recorridos guiados | Caro y frágil frente a cambios visuales | Posterior a A |

**Ajuste sobre lo que se propuso**: la propuesta decía "fichas en el repo web". Van en
**`appKine-api`**, porque el asistente las necesita en tiempo de ejecución, el backend no puede leer
el repo web y la especificación funcional ya vive en `appKine-api/docs/producto/`. La web las pide
por la API. *Decisión del diseñador, a confirmar.*

## 5. Diseño

### 5.1 La ficha

Un archivo por pantalla en `appKine-api/src/main/resources/manual/<id>.md`:

```markdown
---
id: caja-diaria
titulo: Caja diaria
grupo: Economía
rutas: ["/caja", "/caja/:id"]
permisos: [caja:operate]          # alcanza con uno; vacío = cualquiera con sesión
relacionadas: [egresos, reportes]
palabras: [abrir caja, cerrar caja, arqueo, efectivo]   # sinónimos para la búsqueda
---

## Para qué sirve
Dos o tres oraciones.

## Tareas

### Abrir la caja
1. ...
2. ...

### Cerrar la caja y hacer el arqueo
1. ...

## Qué significa cada estado
- **Abierta**: ...

## Problemas comunes
- **"La caja ya está abierta"**: ...
```

Reglas de redacción, que son parte del diseño:

- Segunda persona, voseo, oraciones cortas, como el resto de la interfaz.
- Los nombres de botones y pestañas, **tal cual aparecen en la pantalla**, en negrita.
- Cada `### tarea` se entiende sola, porque el asistente la va a citar suelta.
- Se explican los **conceptos del circuito** donde aparecen, por ejemplo deuda ≠ cobro ≠ caja o
  turno ≠ sesión, sin jerga técnica.
- Una ficha `primeros-pasos` y una `glosario` completan el índice.

Las fichas las redacta el agente a partir de cada pantalla (plantilla, textos y acciones) y de
`AKINE_Requerimientos_Integrados.md`. **El usuario las revisa** antes de mergear.

### 5.2 Backend — dentro del módulo `knowledge`

Va en `knowledge` porque reusa su puerto `ModeloEmbeddings` (multilingual-e5-base, ONNX local) y
la fusión RRF de `BusquedaHibrida`. La búsqueda por palabras de la biblioteca corre en PostgreSQL
(`to_tsvector`), así que el índice en memoria trae su propio tokenizado: minúsculas, sin tildes y
con los `palabras` del frontmatter. *Decisión del diseñador.*

**Embeddings y el interruptor de la biblioteca.** El bean `ModeloEmbeddings` existe sólo con
`akine.biblioteca.enabled=true`, que viene apagado por defecto (`KnowledgeConfig`). El manual
**no prende la biblioteca ni carga el modelo por su cuenta**: con el bean presente usa búsqueda
híbrida; sin él, sólo palabras. La pérdida de recall la compensan los sinónimos del frontmatter y,
en el asistente, el paso de elección (§5.4). *Decisión del diseñador*: cargar un modelo de ~1 GB en
cada despliegue sólo para el manual no se justifica en v1.

**Qué fichas ve cada persona**: las mismas condiciones que el menú (`secciones.ts`), para que el
manual no describa pantallas que la persona no alcanza. El frontmatter gana dos campos opcionales,
espejo de `Seccion`: `funcionIa` (la función de IA del centro que la pantalla necesita, vía
`FuncionesIaDelCentro`) y `servicio` (el servicio contratado). Los permisos se resuelven con
`PermissionEvaluator` para la organización y la sede activas, **contando los de alcance propio**
(`ownScopedPermissions`): un profesional que maneja sólo sus turnos usa igual la pantalla de
agenda. *Decisión del diseñador.*

**Contexto de sede**: el manual **no** se exceptúa de `TenantContextFilter`. Pide organización y
sede como el resto de la aplicación, porque sin ellas no hay permisos para filtrar. Antes de elegir
sede, durante el onboarding, el panel muestra sólo `primeros-pasos` y `glosario`, que vienen
empaquetadas en la web como excepción mínima. La consola de plataforma está fuera de v1.
*Decisión del diseñador.*

- **`CargadorDelManual`**: al arrancar lee `classpath:manual/*.md`, parsea el frontmatter y parte
  cada ficha en **pasajes**: uno por `### tarea`, más «Para qué sirve», «Estados» y «Problemas».
  Valida el formato y **falla el arranque** si hay ids repetidos, frontmatter inválido o una
  relacionada que no existe.
- **`IndiceDelManual`**: en memoria. Con ~60 fichas son ~300–400 pasajes, menos de 1,5 MB de
  vectores. Calcula los embeddings **en segundo plano** después del arranque; mientras tanto, y si
  el modelo no está disponible, la búsqueda es sólo por palabras. Búsqueda híbrida, palabras más
  vector, con el mismo criterio de fusión que la biblioteca. **Filtra por permisos antes de
  rankear.**
- **API** (`ManualController`, lectura con cualquier sesión válida, sin permiso nuevo):
  - `GET /api/v1/manual` → índice filtrado por permisos: grupos, fichas, títulos de tareas.
  - `GET /api/v1/manual/fichas/{id}` → ficha completa, o 404 si no existe o no le corresponde.
  - `GET /api/v1/manual/contexto?ruta=/caja/12` → la ficha de esa ruta. Resuelve los patrones
    `:param` y devuelve 204 si la pantalla no tiene ficha.
  - `GET /api/v1/manual/busqueda?q=...` → pasajes, cada uno con ficha, tarea, fragmento y enlace.
- El texto viaja como **Markdown** y la web lo renderiza con un subconjunto acotado: títulos,
  listas, negrita, enlaces internos y nada de HTML crudo.
- **Para el asistente**: `knowledge` implementa `assistant.spi.HerramientaDelAsistente`, igual que
  `reporting`, `scheduling`, `person` y `activity`. `assistant` sigue sin importar a nadie, y la
  arista nueva es `knowledge → assistant.spi`.

### 5.3 Por qué en memoria y no en `libro_fragmento`

`libro` y `libro_fragmento` son **por organización** (`organization_id NOT NULL`, tenant). El
manual es **igual para todos** y cambia sólo con un deploy. Meterlo en la biblioteca obliga a un
tenant ficticio o a abrir la regla de tenencia, además de una migración. En memoria no hay nada que
sincronizar: la versión desplegada **es** la versión del manual. Recalcular ~400 embeddings al
arrancar cuesta pocos segundos de CPU, en segundo plano.

### 5.4 Asistente — intención `COMO_SE_HACE`

Sigue la regla del módulo: **el modelo interpreta y elige, nunca redacta**.

1. **Interpretar**: `COMO_SE_HACE` se agrega a `assistant.spi.Intencion` **y** a
   `Interpretador.ELEGIBLES`; si falta en `ELEGIBLES`, se convierte sin aviso en
   `FUERA_DE_ALCANCE`. El prompt suma ejemplos ("¿cómo...?", "¿dónde...?", "¿qué significa...?",
   "no puedo..."). La consulta viaja con la **ruta actual** y la pregunta textual, dos campos nuevos
   y opcionales que `PedidoDeHerramienta` tiene que transportar.
2. **Recuperar**: la herramienta de `knowledge` (`ComoSeHaceHerramienta`) consulta su
   `IndiceDelManual` y obtiene hasta 8 pasajes candidatos, ya filtrados (§5.2), con un plus a la
   ficha de la pantalla actual.
3. **Elegir**, un paso **nuevo** que hoy el asistente no tiene: la misma herramienta, dentro de
   `knowledge`, hace una segunda llamada a `platform.spi.modelo.ModeloDeLenguaje` con la pregunta y
   **sólo los títulos** de los
   candidatos (`caja-diaria#abrir-la-caja: Caja diaria → Abrir la caja`). Devuelve un id o
   `NINGUNO`. Un id que no esté entre los candidatos cuenta como `NINGUNO`. Esto le da al
   asistente la **abstención** que el coseno solo no separa, que es la lección de la biblioteca.
4. **Responder** con plantilla: «En **Caja diaria → Abrir la caja**:» + **el texto del pasaje,
   textual** + enlace a la pantalla + botón «Ver ficha completa».
5. **Abstención**: «No encontré eso en el manual.» + los 3 mejores candidatos como enlaces.
6. **Sin modelo**, o con la IA del centro apagada: el asistente no está, pero el **buscador del
   panel** cubre la misma necesidad sin IA.

Permisos: el asistente nunca cita un pasaje que la persona no podría ver en el panel, porque el
filtro está en `IndiceDelManual` y no en el prompt. Auditoría: la misma del asistente; la pregunta
y el id elegido, sin datos del centro.

### 5.5 Web

- **Entrada «Ayuda»** fija al final de la barra lateral, debajo de los grupos y fuera de ellos,
  visible para cualquier persona con sesión. Atajo **F1**. En móvil, en el mismo lugar del menú.
- **Panel lateral derecho**, diferido. Desde arriba:
  1. buscador;
  2. la ficha de la pantalla actual (vía `/contexto`), con sus tareas como acordeón. Si la
     pantalla no tiene ficha: «Esta pantalla todavía no tiene ficha» + índice;
  3. «Relacionadas»;
  4. «Ver todo el manual»: índice por grupos;
  5. «Preguntale al asistente», que abre el asistente con la pregunta escrita. Aparece con las
     mismas condiciones que la entrada del asistente en el menú: función de IA del centro
     prendida y `asistente:use` en la sede activa (`AsistenteService.consultar`).
- El panel **sigue la navegación**: al cambiar de pantalla con el panel abierto, cambia de ficha.
- Los enlaces de una ficha a otra pantalla navegan y dejan el panel abierto.
- La ayuda "?" existente no cambia. Puede sumar más adelante «Más en el manual» (fuera de v1).
- Accesibilidad: el panel es un `dialog` no modal con foco gestionado, Escape lo cierra y cumple
  WCAG AA con los tokens de la norma de diseño.

### 5.7 Capturas marcadas e íconos

Pedido del usuario (19/11), al aprobar: **capturas de pantalla, íconos que ayuden a entender y
marcas sobre la imagen que señalen de qué se habla.**

**Las capturas se generan, no se sacan a mano.** Una captura hecha a mano envejece sin que nadie
lo note. Una generada por script se rehace con un comando y, cuando un elemento marcado deja de
existir, **el script falla**: avisa justo el caso que rompe el diseño (§9.8).

- **Definición**, en la web, porque los selectores son conocimiento de la web:
  `appKine-web/e2e/manual/capturas/<ficha>.capturas.ts`. Cada captura declara:
  - `ficha` y `nombre`, por ejemplo `caja-diaria` / `abrir-caja`;
  - `ruta` y una función `preparar(page)` para llegar al estado: abrir un diálogo, elegir una fila;
  - `recorte`: el selector de la zona a capturar. **No va la pantalla entera**, porque en un panel
    de ~420 px una captura de 1280 px queda ilegible;
  - `marcas`: lista de `{ selector, numero }` u otras formas (§ abajo).
- **Las marcas se queman en la imagen.** Antes de capturar, el script inyecta una capa: un recuadro
  de resalte alrededor del elemento y un **círculo numerado** al lado. **Los números coinciden con
  los pasos de la tarea**: el paso 2 dice «Tocá **Abrir caja**» y en la imagen hay un ② sobre ese
  botón. Formas posibles: `recuadro` (por defecto), `flecha` y `zona` (para un área, sin número).
  Colores con los tokens de la norma de diseño, contraste AA contra la captura.
- **Datos**: la pila aislada de E2E con datos **sintéticos** (centro y pacientes de ejemplo,
  nunca reales), siempre la misma siembra, para que las capturas no cambien entre corridas.
  Tema claro, 1280×800 y escala 2× para que se lea nítido. Formato WebP.
- **Dónde quedan**: `appKine-web/public/manual/<ficha>/<nombre>.webp`, como archivos estáticos.
  **No entran al bundle**: se descargan cuando se abre la ficha. Estimado: ~60 fichas × ~3 capturas
  × ~40 KB ≈ 7 MB en el repo.
- **Comandos**: `npm run manual:capturas` (o `-- caja-diaria` para una sola) las regenera, y
  `npm run manual:verificar` comprueba sin escribir que todo selector marcado sigue existiendo y
  que **cada referencia de las fichas tiene definición y viceversa**. Lee las fichas de
  `../appKine-api/src/main/resources/manual/`. Se corren a mano, como hoy los E2E.

**En la ficha**, una captura se referencia por nombre, y la web la resuelve a la URL del archivo:

```markdown
### Abrir la caja
![La caja cerrada, con el botón Abrir caja marcado](captura:caja-diaria/abrir-caja)
1. Entrá a :icono[caja]: **Caja diaria**.
2. Tocá **Abrir caja** ②.
```

- El texto alternativo es obligatorio (accesibilidad) y describe qué se ve y qué está marcado.
- **Tocar la imagen la amplía** a pantalla completa, con Escape para cerrar.
- El asistente, al citar una tarea, devuelve el pasaje **con su captura**: la plantilla incluye la
  referencia y la web la muestra igual que en el panel.

**Íconos**, con el mismo `app-icono` de la aplicación:

- **Íconos de la interfaz dentro del texto**, `:icono[nombre]:`: el texto muestra **el mismo
  dibujo** que la persona ve en el botón. «Tocá :icono[filtro]: para filtrar» se entiende sin
  buscar la palabra.
- **Ícono por sección de la ficha**, siempre el mismo: «Para qué sirve», «Tareas», «Estados»,
  «Problemas comunes» y «Relacionadas». Se aprende una vez y sirve para escanear la ficha.
- **Ícono por ficha** en el índice, el mismo de su grupo del menú.
- **Avisos dentro de la ficha**: `> [!importante]` y `> [!consejo]`, con ícono y color propio,
  para lo que no hay que pasar por alto («cerrar la caja no se puede deshacer»).
- Los íconos que falten se dibujan como trazos nuevos **en un mapa propio del manual**, que se
  carga diferido con el panel: `TRAZOS` de `app-icono` está en el bundle inicial y no tiene margen.
- Un `:icono[x]:` que no existe hace fallar `manual:verificar`.

### 5.8 Contrato y versiones

Cambio **aditivo**: cuatro operaciones `GET` de manual, `COMO_SE_HACE` en el enum de intención, y
`rutaActual` opcional en el pedido de consulta. Minor nuevo del contrato, que se confirma al
rebasear por la rama de acciones. Sin migración.

## 6. Entregas

| # | PR | Repo | Contenido |
|---|---|---|---|
| 1 | Manual: carga, índice y API | api | `CargadorDelManual`, `IndiceDelManual`, `ManualController`, contrato y **las fichas** |
| 2 | Panel de ayuda | web | Cliente regenerado, entrada «Ayuda», panel, render de Markdown con íconos, avisos y capturas ampliables, F1 |
| 2b | Capturas | web | Script de capturas marcadas, `manual:verificar` y las imágenes, por tandas como las fichas |
| 3 | `COMO_SE_HACE` | api + web | `ManualDirectory`, intención, elección con abstención, plantilla, `rutaActual` desde la web |

Las fichas pueden entrar en tandas dentro del PR 1, por grupo de menú; el panel funciona con las
que haya. **El contenido es la parte más larga del trabajo.**

## 7. Cómo se sabe que funciona

- **Arranque**: un test que carga todas las fichas reales. Ids únicos, relacionadas existentes y
  cada `rutas` es una ruta real de la web: se coteja contra una lista exportada de `secciones.ts`
  y de los `*.routes.ts`, copiada en el test y revisada al agregar pantallas.
- **Permisos**: una cuenta de recepción no ve `caja-diaria` si no tiene `caja:operate`, ni en el
  índice, ni en la búsqueda, ni en el asistente.
- **Asistente**: un set de unas 30 preguntas reales, con su pasaje esperado o `NINGUNO`, medido
  como el asistente actual (aciertos y abstenciones correctas). Se mide en la RTX 4050 y sin GPU.
- **Web**: el panel cambia de ficha al navegar, F1 lo abre, y el bundle inicial no crece.

## 8. Lo que queda a criterio del constructor

- La librería de Markdown en el frontend: una chica y con sanitizado, o render propio del
  subconjunto. **No** `innerHTML` sin sanitizar.
- El tokenizado y los pesos de la búsqueda híbrida, reusando lo de `BusquedaHibrida` donde se pueda.
- El texto exacto de las plantillas de respuesta.
- El orden de redacción de las fichas. Sugerido: el circuito diario primero (agenda, recepción,
  atención, caja), después el resto.

## 9. Design challenge

1. **Ownership**: no hay tablas nuevas. `CargadorDelManual` e `IndiceDelManual` son de
   `knowledge`; nadie más los toca, salvo por `knowledge.spi.ManualDirectory`.
2. **Ciclos**: hay una arista nueva, `knowledge → assistant.spi`, el mismo patrón que ya siguen
   cuatro módulos. Hoy no existe ninguna importación entre los dos módulos. Se verifica con una
   clase sonda y ArchUnit antes de comprometerla.
3. **Tenant**: el manual es global a propósito y no lleva `organization_id`. Lo que sí depende de
   la persona y del centro (permisos, funciones de IA, servicio) se aplica en `IndiceDelManual`
   antes de rankear. No hay ningún dato de un centro en el índice. El endpoint pasa por
   `TenantContextFilter` como cualquier otro (§5.2).
4. **Reglas maestras**: no toca HC, Caso, Sesión ni dinero. Las fichas **explican** esas
   distinciones y no las redefinen: si el texto contradice el integrado, gana el integrado y se
   corrige la ficha.
5. **Baja lógica**: no hay datos persistidos.
6. **Contrato**: aditivo, minor.
7. **Ruta crítica**: los cimientos existen (asistente, embeddings ONNX, intención de interpretar).
   No adelanta nada.
8. **El caso que rompe el diseño**: **una pantalla cambia y su ficha no**. El asistente cita, con
   aplomo, un botón que ya no existe. Mitigaciones: el test de arranque coteja rutas; cada PR web
   que cambie una pantalla con ficha la menciona en su checklist (regla nueva en el `CLAUDE.md` de
   `appKine-web` §4); **`manual:verificar` falla si desaparece un elemento marcado en una captura**
   (§5.7); y cada pasaje citado lleva el enlace a la ficha para reportar el error. No se elimina del
   todo: un texto puede quedar desactualizado sin que cambie ningún selector.

## 10. Decisiones del diseñador, a confirmar por el usuario

1. Fichas en `appKine-api`, no en `appKine-web` (§4).
2. ~~Sin capturas de pantalla en v1.~~ **Cambiado por el usuario el 19/11: con capturas marcadas
   e íconos** (§5.7). Mías dentro de eso: generadas por script, recortadas a la zona, marcas
   quemadas con números que coinciden con los pasos, sólo tema claro y escritorio.
3. El manual de la consola de plataforma, fuera de v1.
4. Una persona sin permiso no ve las fichas de lo que no puede hacer, ni siquiera como lectura.
5. Índice en memoria y sin migración (§5.3).
6. Sin la biblioteca prendida, el manual busca sólo por palabras y no carga el modelo de embeddings
   por su cuenta (§5.2).
7. Las fichas se filtran como el menú: permisos (incluidos los de alcance propio), función de IA y
   servicio contratado (§5.2).
8. El manual pide sede elegida; antes de elegirla, sólo `primeros-pasos` y `glosario` (§5.2).
