# AKINE — Consulta a la biblioteca con un modelo local (chat)

> Estado: diseño, 11/11/2026. Sigue a `AKINE-biblioteca-rag.md` (biblioteca, M30): la biblioteca ya
> guarda, indexa y busca. Esto agrega el paso que redacta. Ramas: `akine-biblioteca-rag`
> (`appKine-api`) y `akine-biblioteca-web` (`appKine-web`). Lo marcado **[decisión del agente]** lo
> propuso el agente y el usuario no lo objetó; lo demás lo dijo el usuario.

## 1. Qué es y por qué

Un **chat de consulta** sobre la bibliografía del centro. Alguien del equipo pregunta algo clínico
—"¿qué tratamiento conviene para una lumbalgia crónica?", "¿qué pasa con la rodilla después de una
lesión de cruzado?"— y AKINE responde **redactando con lo que dicen los libros cargados**, con las
fuentes a la vista. Hoy la pantalla "Probar búsqueda" devuelve pasajes sueltos; el usuario quiere una
respuesta con sentido.

La regla que manda sobre todo, en palabras del usuario: **si entrega cosas que no son, no debería**.
Mejor "no tengo material" que inventar. Y si igual fabula, el usuario repregunta en la charla para
que se ajuste.

## 2. Objetivos, no-objetivos y lo que lo haría fracasar

**Objetivos**
- Responder en castellano, breve, **sólo con pasajes de la biblioteca de la organización**.
- Charla con **repreguntas** que se apoyan en lo anterior ("¿y cuántas veces por semana?").
- Cada respuesta lleva sus **fuentes**: al pie, una marca chica "Fuentes (N)" que al tocarla despliega
  libro y páginas ("un tilde de dónde sale", en palabras del usuario).
- Modelo **local y chico, en Docker**: nada sale a internet.
- Lo usa **cualquier persona del equipo**.

**No-objetivos (por ahora)**
- **No se guarda nada**: ni preguntas, ni respuestas, ni historial. Cerrar o recargar la pantalla la
  pierde. "Nose en el futuro", dijo el usuario: queda abierto para después.
- No se ata a un paciente, a una sesión ni a la historia clínica. Es consulta pura.
- **Ayuda sobre cómo usar AKINE**: el usuario la quiere más adelante ("si pregunta sobre
  funcionalidad del sistema pueda responder cómo se debe trabajar"). No se construye ahora, pero el
  diseño la deja prevista como **otra fuente** de pasajes (ver §6.6).
- No es la "sugerencia de tratamiento dentro de la sesión de evaluación" del proyecto 2 original:
  ésa sigue pendiente y podrá reusar esta pieza.

**Lo que lo haría fracasar aunque funcione**
- Que **invente** algo que no está en los libros y lo presente como si estuviera.
- Que tarde tanto que no valga la pena esperar.

## 3. Decisiones tomadas

| Tema | Decisión |
|---|---|
| Patrón | **El backend busca siempre** y después le pide al modelo que redacte (opción A). El modelo no decide si busca |
| Modelo | **Ollama en Docker con `qwen2.5:1.5b`** (986 MB), probado el 11/11: ~11 s caliente, 81 s la primera carga, en CPU (12 núcleos, sin GPU) |
| Quién | Todo el equipo del centro |
| Persistencia | Ninguna |
| Repreguntas | Sí |
| Fuentes | Marca plegable "Fuentes (N)" al pie de cada respuesta |

**Lo que mostró la prueba del 11/11 y el diseño tiene que contener:** el modelo chico **no cita
páginas aunque se le pida**, **agrega cosas de su cosecha** (un ejemplo que no estaba en el pasaje) e
**inventa una respuesta completa** cuando la pregunta no tiene nada que ver con los pasajes (lumbalgia
contra un libro de Java). Las tres se resuelven fuera del modelo: §5.

## 4. Pantalla (`appKine-web`)

- Ruta propia **`/consulta`** con entrada "Consultar biblioteca" en la barra **[decisión del agente]**:
  la usa todo el equipo, no es configuración. Grupo de la barra: "Atención" **[decisión del
  agente]**.
- Chat de una columna: burbujas de pregunta y respuesta, caja de texto abajo (Enter envía,
  Shift+Enter nueva línea), botón "Nueva charla" que vacía todo.
- La respuesta **aparece mientras se escribe** (streaming) **[decisión del agente]**: 10–15 s se
  sienten menos con texto apareciendo.
- Al pie de cada respuesta: "Fuentes (N)" plegable; desplegado, cada fuente con título del libro
  (enlace a `/biblioteca/:id` sólo para quien tiene `biblioteca:manage`), páginas y el extracto.
- Si no hubo material: la respuesta es el aviso fijo "No encontré material en la biblioteca para
  responder eso", sin fuentes, sin pasar por el modelo.
- Aviso fijo arriba: "Respuestas orientativas basadas sólo en la bibliografía cargada por el centro.
  No reemplazan el criterio profesional." **[decisión del agente]**.
- Estados: biblioteca apagada o consulta apagada (estado vacío explicativo, como la biblioteca);
  modelo no disponible ("El asistente no está disponible en este momento"); ocupado (ver §5.5).
- La charla vive en memoria del componente. **No** en `localStorage` (la regla de lint de AKINE lo
  prohíbe y además sería guardar lo que no se debe guardar).
- Norma de pantalla vigente (`DESIGN.md`, AA, "?" para lo explicativo).

## 5. Backend (`appKine-api`)

### 5.1 Dónde vive

Dentro del módulo **`knowledge`** **[decisión del agente]**: usa su búsqueda directamente y el
modelo de lenguaje es un puerto más de ese módulo (`ModeloDeLenguaje` en `domain/port`, adaptador
`OllamaModeloDeLenguaje` en `infrastructure`). Ningún otro módulo cambia salvo `organization`
(permiso nuevo) y `platform` (tipos de problema).

### 5.2 Permiso

- **`biblioteca:consultar`** nuevo, para `ORG_ADMIN`, `CONSULTORIO_ADMIN`, `PROFESIONAL` y
  `ADMINISTRATIVO`. Fuera el rol `PACIENTE` y la plataforma **[decisión del agente]**.
- `biblioteca:manage` sigue siendo sólo del `ORG_ADMIN` para administrar libros y "Probar búsqueda".
- Matriz `docs/seguridad/` y catálogo de otorgables al día.

### 5.3 El flujo de una pregunta

```
POST /api/v1/organizations/{orgId}/biblioteca/consultas
  { "pregunta": "...", "historial": [ {"rol":"usuario|asistente","texto":"..."}, ... ] }

1. Interruptores (biblioteca y consulta), tenant, permiso biblioteca:consultar.
2. Validar: pregunta 1..600 caracteres; historial ≤ 6 turnos, cada texto ≤ 2.000; si no, 400.
3. Pregunta de búsqueda:
   - sin historial: la pregunta tal cual;
   - con historial: el modelo la reescribe como pregunta completa y autosuficiente
     (llamada corta, salida ≤ 80 tokens, temperatura 0). Si falla o tarda, se usa la pregunta
     tal cual unida a la última pregunta del usuario.
4. Buscar en la biblioteca con lo existente (BibliotecaDirectory / BusquedaService), k = 5.
5. Compuerta: si `alcanza` es falso → respuesta fija "No encontré material…", sin fuentes,
   SIN llamar al modelo. Fin.
6. Si alcanza: armar el mensaje = system prompt + pasajes sobre el corte (máx. 5, numerados) +
   preguntas previas del usuario (sin las respuestas del asistente, R10) + pregunta. Llamar al modelo (temperatura 0,2, salida ≤ 400 tokens).
7. Responder en streaming (§5.4). Las fuentes las arma el backend con los pasajes que le pasó al
   modelo: libro, páginas, extracto. No dependen de que el modelo cite.
```

El historial lo manda el navegador en cada pregunta: el servidor no guarda estado entre pedidos.

### 5.4 Respuesta en streaming

`text/event-stream` (SSE) desde el `POST` **[decisión del agente]**. Eventos (más `espera`, ver §8.4):

- `fuentes` — una vez, al principio: la lista de fuentes (para mostrarlas apenas empieza).
- `texto` — muchos: fragmentos del texto a medida que el modelo los genera.
- `fin` — una vez: `{ "sinMaterial": bool, "camino": "VECTOR|PALABRAS" }`.
- `error` — si el modelo falla a mitad: `{ "type": "<problemType>" }`; el texto parcial queda.

Antes de empezar el stream, los errores son `application/problem+json` normales (400, 403, 404, 409,
503). Los esquemas de los eventos se publican en el contrato como componentes para que el cliente
generado tenga sus tipos; la lectura del stream en el frontend es manual (`fetch` + lector), porque el
cliente de openapi-generator no consume SSE.

### 5.5 Recursos y tiempos

- Un modelo en CPU atiende bien de a una: **cola de 1 consulta en curso por instancia, hasta 3 en
  espera**; con la cola llena, **503 `consulta-ocupada`** con `Retry-After` **[decisión del
  agente]**.
- Tiempo máximo por consulta 90 s; pasado, se corta con evento `error`.
- `keep_alive` del modelo en 30 min para no pagar la carga en frío (81 s) en cada pregunta.
- Ollama no disponible → 503 `asistente-no-disponible`.
- **Contexto acotado:** los pasajes van recortados a lo que entra en `num_ctx` 4096 junto con el
  historial; si no entra, se descartan primero los turnos más viejos del historial, después los
  pasajes de menor puntaje.

### 5.6 El system prompt

En un recurso versionado (`knowledge/prompt-consulta.txt`), no en el código. Contenido base:

> Sos un asistente para profesionales de kinesiología y rehabilitación. Respondés en castellano
> rioplatense, breve y claro, usando **sólo** la información de los pasajes numerados que te doy. No
> agregues datos, ejemplos ni recomendaciones que no estén en los pasajes. Si los pasajes no alcanzan
> para responder la pregunta, respondé exactamente: "No encontré material en la biblioteca para
> responder eso." No des diagnósticos ni dosis que no estén escritos en los pasajes.

Los pasajes viajan **delimitados** y con la aclaración de que son material de referencia y no
instrucciones: los pedazos marcados sospechosos ya están fuera de la búsqueda (M30), y esto es la
segunda capa contra un PDF que traiga órdenes escondidas.

### 5.7 Configuración

```yaml
akine.biblioteca.consulta:
  enabled: false            # apagado por defecto, como la biblioteca
  url: http://localhost:11434
  modelo: qwen2.5:1.5b
  timeout-segundos: 90
  keep-alive: 30m
  max-en-espera: 3
```

Docker: servicio `ollama` (imagen fijada por digest) en el `compose.yaml` del backend, con volumen
para los modelos; el modelo se baja una vez (`ollama pull qwen2.5:1.5b`). En producción, el mismo
servicio al lado del backend.

### 5.8 Privacidad y auditoría

- Lo que se escribe en el chat puede traer datos de un paciente aunque no se pida. No sale de la
  infraestructura (modelo local), **no se guarda** y **no se loguea**: ni la pregunta, ni la respuesta,
  ni los pasajes; los logs llevan sólo tiempos, cantidad de pasajes y si hubo material.
- La consulta **no se audita** con contenido; sí se cuenta en una métrica (`akine_consultas_total`
  por resultado) **[decisión del agente]**.

### 5.9 Contrato

Aditivo: una operación nueva (`consultarBiblioteca`), los esquemas de pedido y de eventos y dos
`problemType` (`consulta-ocupada`, `asistente-no-disponible`). Minor siguiente al de la rama (0.165.0).
Sin migración: el permiso vive en código como `biblioteca:manage`.

## 6. Design challenge

1. **Ownership.** No hay tablas nuevas. Todo vive en `knowledge`; `organization` sólo suma el código
   de permiso, como ya hizo M30.
2. **Ciclos.** `knowledge` sigue consumiendo `organization`/`platform` por `spi`; nadie consume la
   consulta todavía. ArchUnit no cambia de forma.
3. **Tenant.** La búsqueda ya filtra por organización; el modelo no ve nada fuera de los pasajes que
   recibe. El historial lo manda el cliente: no puede traer pasajes de otra organización porque las
   fuentes las vuelve a calcular el servidor en cada pedido.
4. **Reglas maestras.** No toca HC, caso, sesión, turno ni dinero. No escribe nada clínico.
5. **Baja lógica.** No hay datos que borrar: no se guarda nada.
6. **Contrato.** Aditivo, minor.
7. **Ruta crítica.** La biblioteca (M30) está en la misma rama y probada contra el backend real; el
   modelo está probado en Docker. Lo que se adelanta es la funcionalidad del proyecto 2 en una forma
   reducida (chat sin persistencia), a pedido del usuario.
8. **El caso que rompe el diseño.** Un profesional pregunta algo que **parece** estar en los libros
   pero no está (por ejemplo, una dosis), la búsqueda devuelve pasajes del tema sobre el corte, y el
   modelo completa con una cifra inventada. La compuerta no lo ataja porque sí hubo material. Lo
   contienen: el prompt que prohíbe cifras ausentes, temperatura baja, las fuentes siempre visibles
   para contrastar y el aviso fijo de que es orientativo. **No se elimina**: con un modelo de 1,5 B
   el riesgo residual existe, y por eso el aviso no es decorativo. La medición del §7 dice cuánto.

## 7. Cómo se sabe que funciona

- Con la biblioteca actual (un libro de Java) y un PDF de kinesiología sintético de varias páginas:
  10 preguntas con material y 5 sin material. **Éxito:** las 5 sin material responden el aviso fijo
  sin llamar al modelo; de las 10 con material, ninguna agrega datos ausentes de los pasajes (revisión
  a mano contra las fuentes), y las repreguntas se resuelven bien en al menos 8 de 10.
- Tiempo: primera palabra en menos de 5 s con el modelo caliente; respuesta completa en menos de 20 s.
- Si `qwen2.5:1.5b` no pasa la vara de "no inventar", se prueba `qwen2.5:3b` antes de tocar el diseño.

## 8. Decisiones técnicas del agente (de la revisión del diseño)

Todas **[decisión del agente]**; ninguna cambia lo que el usuario pidió.

1. **Autenticación del stream.** El `fetch` manual del frontend lleva el mismo
   `Authorization: Bearer` que pone `auth.interceptor.ts`, leído del store del token en memoria. El
   tenant viaja en el token de contexto, como en todo pedido. Un 401 **antes** de que empiece el stream
   pasa por el mismo canje de refresh del `SessionService` (cola single-flight) y se reintenta una vez;
   un 401 en el reintento manda al login como cualquier pantalla. Las validaciones (interruptores,
   tenant, permiso, cuerpo) corren **en el hilo del pedido, antes de abrir el stream**, así que se
   resuelven con el `SecurityContext` y el filtro de tenant de siempre; al emisor asíncrono sólo le
   llegan datos ya resueltos (orgId, pasajes, mensajes), nunca el contexto de seguridad.
2. **`alcanza` ya existe.** `BusquedaService` devuelve `alcanza = pasajes sobre el corte ≥ minPedazos
   (2)`, con corte propio por camino (`corteVector` 0,818 y `cortePalabras`). La compuerta usa ese
   booleano tal cual en los dos caminos. Por PALABRAS (modelo de embeddings caído) el corte es más
   grueso; se acepta: el riesgo es contestar "sin material" de más, que es el lado seguro.
3. **Reescritura de la repregunta.** Es una llamada aparte al modelo, **dentro del mismo turno de la
   cola y del mismo límite de 90 s**. El respaldo si falla o pasan 10 s: la última pregunta del usuario
   del historial + " " + la pregunta nueva, como texto concatenado.
4. **Cola.** Un semáforo por instancia (1 en curso) con hasta 3 en espera; el trabajo corre en un
   ejecutor acotado de hilos virtuales. Mientras espera, el stream ya abierto emite un evento
   `espera` con su posición, y la pantalla muestra "En cola, sos el N.º X". Si el cliente se
   desconecta, se cancela la llamada a Ollama y se libera el turno.
5. **Contrato del stream.** La operación declara el `200` como `text/event-stream` con esquema
   `string`, y los cinco eventos (`fuentes`, `texto`, `espera`, `fin`, `error`) como componentes con
   nombre propio en el contrato, referenciados desde la descripción. El cliente generado no expone la
   operación usable, pero sí los tipos; la pantalla usa esos tipos con su `fetch`. Se verifica que
   `OpenApiContractIT`, el chequeo de `operationId` y la generación del cliente pasen antes de cerrar.
6. **Docker y modelo.** `compose.yaml` del backend suma el servicio `ollama` con la imagen fijada por
   digest (`ollama/ollama@sha256:910d0d81…`, 0.40.16, la probada) y un volumen. El modelo se baja con
   un paso manual documentado en el runbook (`docker exec … ollama pull qwen2.5:1.5b`); el arranque
   del backend no depende de que esté: sin Ollama, la consulta responde 503 y nada más se rompe. CI y
   ITs usan un modelo de lenguaje falso y no necesitan Ollama.
7. **Recorte de contexto.** Estimación por caracteres, **3 caracteres por token**, con presupuesto de
   3.000 tokens para entrada (deja ~1.000 para salida dentro de `num_ctx` 4096). El system prompt
   **nunca se recorta**: se descartan primero los turnos viejos del historial y después los pasajes de
   menor puntaje.
8. **Barra y permiso.** El grupo "Atención" existe (es el primero de la barra) y la entrada va al
   final de ese grupo. `biblioteca:consultar` va por base en los cuatro roles del equipo; no se agrega
   como otorgable a otros roles.

## 9. Lo que queda para quien lo construye

- Nombres de clases, DTOs y el armado exacto del mensaje dentro de lo dicho arriba.
- Cómo leer el stream de Ollama desde Spring (cliente HTTP del proyecto) y emitir SSE (`SseEmitter`
  o `ResponseBodyEmitter`).
- La disposición visual del chat dentro de `DESIGN.md`.
- Tests: los que exigen las reglas del repo (pocos): la compuerta sin material no llama al modelo,
  la reescritura con historial, la cola llena da 503, y un IT del endpoint con un modelo falso.
