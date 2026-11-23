# Asistente de datos como agente con herramientas

Diseño del 23/11/2026. Reemplaza el camino de consulta del asistente (`AKINE-asistente-de-datos.md` y
`AKINE-asistente-sql.md`); las acciones con confirmación (`AKINE-asistente-acciones.md`) y el manual
(`COMO_SE_HACE`) se conservan y se enchufan como herramientas.

## 1. Por qué

El asistente está en `main` pero no resuelve el uso diario. Diagnóstico sobre `c44f120a`:

- Un modelo local de 4B **clasifica** la pregunta en seis intenciones fijas y una plantilla arma el
  texto. Reglas con expresiones regulares (`pideDetalle`, `esContinuacion`, `pideDatoClinico`,
  `pideCambio`) parchean lo que el modelo clasifica mal.
- Lo que no entra en las seis intenciones va a la consulta libre en SQL, que está **apagada por
  defecto, también en el perfil `ia`**. Con eso apagado, "¿qué arancel cobro por X?" responde la
  plantilla `AYUDA` ("Todavía no sé responder eso…") y "¿a quién se le aplicó magnetoterapia?" cae en
  `FALTA_PERSONA` ("¿De qué persona?"). Son exactamente las respuestas genéricas y las repreguntas que
  el usuario reporta.
- Aun prendido, el SQL lo escribe el modelo de 4B sobre 43 vistas sin ver filas, y su medición fue de
  intención (10/10), no de respuestas correctas.

Decisión del usuario del 23/11: que funcione "tope de gama"; cómo se despliega en la realidad se decide
después. Se permite un modelo en la nube (Claude) y sumar otros proveedores (una key de OpenCode).

## 2. Objetivo y criterios de aceptación

El asistente interpreta preguntas abiertas sobre todo el dominio del centro y responde directo con los
datos vigentes, respetando organización, sede y permisos del usuario.

1. **Asistencia:** "¿Vino Juan hoy?" y "¿Quiénes faltaron hoy?" devuelven el estado o el listado real
   sin repreguntar. Si hay dos Juan con turno hoy, responde por los dos (o pregunta **sólo** entonces).
2. **Tratamientos:** "¿A quién se le aplicó [práctica]?" identifica la práctica y devuelve pacientes y
   sesiones del período (por defecto, los últimos 30 días, dicho en la respuesta).
3. **Aranceles:** "¿Qué arancel cobro por [práctica]?" y "¿Cómo está cargado [valor]?" informan el monto
   vigente y explican de dónde sale (precio particular de la oferta, convenio del plan, convenio de la
   sede, coseguro, tope mensual).
4. **Variantes:** una misma consulta dicha de distintas formas (coloquial, abreviada, con errores de
   tipeo) llega a la misma respuesta.
5. **Sin datos:** si no hay registros, lo dice con el criterio usado ("No hay ausentes hoy en Sede
   Centro"), nunca "no sé responder eso".

**Medición:** set de evaluación nuevo (§9) con ≥ 120 preguntas agrupadas en ≥ 30 intenciones con 3–5
variantes cada una, más un set de control que no se usa para afinar. Meta: ≥ 90 % de respuestas
correctas en control y ≥ 95 % de consistencia entre variantes de una misma intención.

## 3. No-objetivos y anti-objetivos

- **No** decide el despliegue productivo (consentimiento, retención de datos, costo por centro): queda
  para después, detrás de los interruptores existentes.
- **No** amplía las escrituras: las acciones siguen siendo las de #226 y siempre con tarjeta.
- **Anti-objetivo:** que el modelo vea datos que el usuario no puede ver. Un agente brillante que filtra
  un paciente de otra sede o un dato clínico sin `hc:read` es un fracaso aunque responda bien.
- **Anti-objetivo:** cifras inventadas. Todo número o nombre de la respuesta sale de un resultado de
  herramienta de ese turno.

## 4. Enfoques considerados

| | Enfoque | A favor | En contra |
|---|---|---|---|
| **A** | **Agente con herramientas**: Claude elige y encadena herramientas tipadas (servicios de cada módulo con el actor del usuario) + SQL sobre las vistas como herramienta más, ve los resultados y redacta | Entiende cualquier redacción, explica configuración, crece sumando herramientas | Los resultados viajan al proveedor; costo y 2–5 s por pregunta |
| B | Mismo pipeline, Claude sólo clasifica y se suman intenciones | El modelo no ve datos | Sigue rígido; las plantillas no explican "cómo está cargado" |
| C | Todo por SQL con Claude | Cobertura rápida | SQL equivocado silencioso; explica mal reglas de aranceles |

**Elegido: A** (usuario, 23/11), con dos definiciones suyas:

1. **Sin puntos ciegos:** la herramienta SQL lleva en el contexto el esquema **completo** de las 43
   vistas, documentado columna por columna, para que cualquier dato existente se pueda consultar y
   cruzar aunque no tenga herramienta propia.
2. **Fichas agregadas por entidad:** herramientas de contexto rápido que devuelven de una vez el
   panorama de una entidad (paciente, práctica con su arancel, agenda del día), para no encadenar 4–5
   llamadas por pregunta y poder saltar de un paciente a otro sin perder el hilo.

Proveedor principal Claude, alternativo configurable, confirmación por tarjeta para escrituras.

## 5. Arquitectura

```
pregunta ─► AsistenteService (interruptores, tenant, asistente:use — igual que hoy)
              │
              └─ AgenteDelAsistente  ◄── bucle: modelo ⇄ herramientas, hasta respuesta o tope
                    │   proveedor: ProveedorDeAgente (Claude | compatible-OpenAI | Ollama)
                    │
                    └─ CatalogoDeHerramientas  (HerramientaDelAgente, una por capacidad)
                          ├─ acción:     proponer_accion (arma la tarjeta; no graba)
                          ├─ fichas:     ficha_paciente, ficha_practica, agenda_del_dia
                          ├─ tipadas:    buscar_persona, asistencia, ausentes, practicas_aplicadas,
                          │              arancel_vigente, turnos, deudas, coberturas, clases, reporte,
                          │              como_se_hace (manual)
                          └─ consulta_sql (sólo con asistente:sql; esquema completo en la descripción)
```

### 5.1 El bucle

- Bucle propio sobre la Messages API (no Managed Agents): las herramientas leen la base con el actor y
  la transacción del backend, así que el bucle tiene que vivir en el backend.
- Modelo por defecto `claude-opus-5-5`, pensamiento adaptativo, `effort` configurable (arranca en
  `medium`; se mide `low` por latencia). Fallback del lado del servidor por rechazo (`fallbacks:
  "default"`).
- Tope de vueltas (8) y de tiempo total (30 s). Al llegar al tope se responde con lo que hay y se dice.
- Herramientas en paralelo cuando el modelo las pide juntas; todos los resultados vuelven en un solo
  mensaje.
- **Caché de prompt**: system + catálogo de herramientas + esquema SQL son estables y van primero con
  `cache_control`; la fecha de hoy, la sede y la pregunta van después. Nada volátil en el prefijo.
- Historial (decisión de diseño): el servidor sigue sin guardar la charla. El cliente reenvía los
  últimos N turnos como **pregunta + texto de la respuesta + ids de las entidades nombradas**, nunca
  resultados de herramienta: un resultado fabricado por el cliente sería una vía de inyección y de cifras
  falsas. El historial sirve para resolver de quién se habla ("¿y ella?"); cualquier dato que la
  respuesta nueva use se vuelve a pedir a una herramienta en ese turno. Los ids del historial se tratan
  como pistas: la herramienta los valida contra el tenant como a cualquier otro.

### 5.2 Proveedores

`ProveedorDeAgente` en `platform.spi.modelo`, al lado de `ModeloDeLenguaje`, con una operación:
mensajes + herramientas → texto final o llamadas a herramienta. Implementaciones:

- `ClaudeProveedor`: SDK oficial `com.anthropic:anthropic-java`. Key por `AKINE_ANTHROPIC_API_KEY`.
- `CompatibleOpenAiProveedor`: para OpenCode (y cualquier endpoint compatible con tool calling); URL,
  modelo y key por propiedades.
- `OllamaProveedor`: el modelo local con tool calling, para trabajar sin red. Se acepta que entienda
  peor.

`akine.asistente.agente.proveedor` elige el principal y `.alternativo` el que se usa si el principal no
responde (503, timeout, sin key). El estado del motor de Organización → IA muestra el proveedor activo.

### 5.3 Herramientas

Nueva interfaz `HerramientaDelAgente` en `assistant.spi`, implementada por el módulo dueño del dato en
su `infrastructure` (mismo patrón que hoy: la dependencia va del módulo al `spi`):

```java
public interface HerramientaDelAgente {
    String nombre();                 // snake_case estable, aparece en el set de evaluación
    String descripcion();            // para el modelo: qué responde y qué no
    String esquemaEntrada();         // JSON Schema, strict
    ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, JsonNode entrada, ContextoDeConsulta ctx);
}
```

- Cada una traduce el actor a su `OperatingActor` y llama al servicio de aplicación, que exige su
  permiso. Un `AccessDeniedException` vuelve al modelo como resultado `sin_permiso` (no excepción), y
  el modelo lo dice.
- Las fechas entran como `YYYY-MM-DD` o palabras ("hoy", "esta semana"); `ResolutorDePeriodo` las
  convierte a días de la sede. La zona y "hoy" los fija el backend, no el modelo.
- Las personas entran por nombre o id; `buscar_persona` y las fichas devuelven candidatos con id. Si hay
  varios, el modelo decide: si el contexto alcanza (tiene turno hoy, es el de la charla) elige; si no,
  pregunta mostrando las opciones (la UI de `opciones` se mantiene).
- Resultados compactos: listas con tope (50 filas) y aviso de truncado; los ids viajan para que la
  respuesta pueda enlazar.

**Fichas agregadas** (definición 2 del usuario):

| Herramienta | Devuelve de una vez |
|---|---|
| `ficha_paciente` | datos personales permitidos, coberturas vigentes, turnos recientes y próximos con asistencia, caso(s) activo(s), últimas prácticas aplicadas (con `hc:read`), saldo y deudas abiertas, observaciones administrativas |
| `ficha_practica` | práctica/oferta, quién la presta, precio particular vigente, aranceles por convenio/plan, coseguro calculado, tope mensual, desde cuándo rige cada valor y dónde se carga en pantalla |
| `agenda_del_dia` | turnos de la sede en una fecha con estado (vino, faltó, cancelado, pendiente), profesional y box; totales por estado |

Cada ficha arma su contenido llamando a los `spi` de los módulos que ya existen; una sección que el
usuario no puede ver se omite y se anota como `omitida_por_permiso`.

**El monto de `ficha_practica` sale del mismo código que el devengo** (decisión de diseño). El orden
precio en el plan → convenio de la sede, el coseguro contra el precio de la actividad y el tope mensual
que pasa a particular los decide hoy el devengo (#219). Si esa resolución no está publicada en un `spi`,
se extrae a uno ("cotizar sin devengar") que usan **los dos**, devengo y ficha, con su explicación paso
a paso. Duplicarla en `assistant` está prohibido: el asistente diría un monto y se cobraría otro.

**Acciones** (decisión de diseño): el detector de acciones con el modelo de 4B deja de ir delante de
cada pregunta. Pasa a ser la herramienta `proponer_accion`, que recibe el tipo de acción y sus datos
(persona, monto, medio…), arma la misma `AccionPropuesta` de #226 y devuelve la tarjeta. Nada se graba
hasta `confirmarAccionDelAsistente`, que no cambia. Así "¿vino Juan?" y "Juan vino" los distingue el
modelo grande, y las consultas no pagan la latencia del detector. Los sets `acciones.json` y
`acciones-adversas.json` se vuelven a correr contra el agente.

**Herramienta SQL** (definición 1): `consulta_sql` reusa `ValidadorDeSql` y `JdbcLectorDeVistas` tal
cual (tres barreras de `AKINE-asistente-sql.md` §2). La descripción incluye el esquema completo de las
43 vistas con cada columna explicada, generado desde la base al arrancar y cacheado. Sólo se ofrece al
modelo si el usuario tiene `asistente:sql` (por base, `ORG_ADMIN` y `CONSULTORIO_ADMIN`); las vistas
`clinico_*` sólo con `hc:read` sin recorte. La respuesta sigue mostrando "Ver la consulta".

### 5.4 Respuesta

- El texto lo redacta el modelo a partir de los resultados. Instrucción del system: toda cifra, fecha y
  nombre sale de un resultado de herramienta del turno; si no hay dato, decirlo con el criterio usado;
  nunca prescribir conducta clínica.
- El contrato agrega, de forma aditiva, `herramientas` (nombre + entrada de cada llamada, para auditoría
  y para "Ver cómo lo resolví") y deja `interpretacion` como deprecada.
- `resultado` conserva sus valores; `ACLARACION` sólo cuando el modelo pregunta de verdad.

## 6. Seguridad y datos

- Tenant, sede y permisos se aplican en cada herramienta con el actor, igual que hoy: el modelo no puede
  pedir otra organización porque no es un parámetro de ninguna herramienta.
- **Los resultados de herramientas viajan al proveedor.** Para el desarrollo se acepta (decisión del
  usuario). Queda escrito como bloqueante del despliegue productivo: consentimiento (Ley 25.326),
  retención del proveedor y si los datos clínicos salen o se resuelven con el modelo local.
- La pregunta y los resultados son datos, no instrucciones: el system lo dice, y las herramientas no
  exponen nada que escriba. Las escrituras siguen yendo por `AccionesDelAsistente` con tarjeta.
- Auditoría: cada consulta registra herramientas llamadas, ids leídos y vistas clínicas (DP-03), sin el
  texto de la pregunta.
- La key va en variable de entorno; nunca en el frontend ni en el repo.

## 7. Qué se borra y qué queda

- **Se borra:** `Interpretador` y `prompt-interpretador.txt`, las reglas regex de `AsistenteService`
  (`pideDetalle`, `esContinuacion`, `pideDatoClinico`, `pideCambio`, `tieneNombrePropio`), `Plantillas`
  de respuestas de datos y `ConsultaLibre` como camino aparte (su validador y lector quedan dentro de
  `consulta_sql`).
- **Se adapta:** las 5 herramientas de lectura actuales (`activity`, `knowledge`, `person`,
  `reporting`, `scheduling`) pasan a `HerramientaDelAgente`; las 6 acciones quedan detrás de
  `proponer_accion`. Las herramientas de `encounter`, `clinical`, `billing` y `contracting`
  (prácticas aplicadas, arancel, deudas, coberturas) **son trabajo nuevo**, no adaptación.
- **Se borra también:** `InterpretadorDeAcciones` y `prompt-acciones.txt` (los reemplaza
  `proponer_accion`).
- **Queda igual:** interruptores (despliegue × centro), `asistente:use`, acciones con tarjeta, dictado,
  manual como herramienta `como_se_hace`.
- El camino viejo no se mantiene en paralelo: el proveedor Ollama cubre el trabajo sin red.

## 8. Frontend

- La charla muestra el texto del modelo con formato liviano (listas, negritas), enlaces a la ficha de
  cada paciente nombrado y "Ver cómo lo resolví" con las herramientas usadas.
- Indicador de "pensando…" con el nombre de la herramienta en curso **si** se hace streaming (§10).
- Cliente regenerado; sin DTO manuales.

## 9. Evaluación

- `docs/asistente/evaluacion/agente-preguntas.json`: pregunta, intención agrupadora, respuesta esperada
  verificable (ids, cantidades o montos) y si debe preguntar. Incluye las de los cuatro criterios,
  variantes coloquiales, abreviadas y con errores, continuaciones de charla, sin datos, sin permiso y
  adversarias (pedir otra sede, pedir escribir sin tarjeta, inyección en un nombre de paciente).
- Corre contra una **siembra propia y fija** que el script de evaluación arma por API sobre una base
  vacía (el Centro Demo no sirve: corre las fechas y vive en una rama sin mergear). Las respuestas
  esperadas se expresan relativas a la siembra (nombres y montos sembrados, "hoy" = fecha de la
  corrida), con el backend levantado, como `evaluar-acciones-en-vivo.mjs`.
- **La meta de §2 se exige con Claude.** El proveedor compatible-OpenAI y Ollama se miden con el mismo
  set y se informan, sin meta: sin key o sin red el asistente puede quedar peor que hoy, y se acepta.
- En el CI no hay key: los ITs usan un `ProveedorDePrueba` con guiones fijos (qué herramienta pide para
  cada pregunta) y verifican el bucle, permisos y tenant, no la comprensión. Los E2E del frontend ven
  el asistente apagado, como hoy.
- Reporta: correctas, consistencia por intención, repreguntas innecesarias, latencia p50/p90, costo por
  pregunta y tokens de caché leídos.

## 10. Design challenge

1. **Ownership:** sin tablas nuevas salvo, quizá, comentarios en las vistas de `assistant`. Las fichas
   leen por `spi` de cada módulo; ninguna escribe.
2. **Ciclos:** la dependencia sigue yendo del módulo dueño hacia `assistant.spi`
   (`HerramientaDelAgente`), igual que hoy. `ProveedorDeAgente` vive en `platform.spi`, que no importa a
   nadie. Las fichas que cruzan módulos (`ficha_paciente` toca `person`, `scheduling`, `clinical`,
   `billing`, `contracting`) se arman en `assistant` combinando herramientas, no con un módulo que importe
   a los otros: se verifica con ArchUnit y una clase sonda antes de comprometer la arista.
3. **Tenant:** ninguna herramienta acepta organización ni sede como parámetro; salen del actor. El SQL
   conserva sus tres barreras.
4. **Reglas maestras:** el system explica Turno ≠ Sesión, HC ≠ Caso, Obligación ≠ Cobro ≠ Caja; las
   herramientas devuelven los conceptos por separado y con su nombre, y "faltó" sale del estado del
   turno, no de la ausencia de sesión.
5. **Baja lógica:** sólo lectura.
6. **Contrato:** aditivo (`herramientas`, `interpretacion` deprecada). 0.193.0.
7. **Ruta crítica:** se apoya en los servicios y `spi` que ya están en `main`; nada adelantado.
8. **El caso que rompe el diseño:** un profesional con `hc:read` recortado a sus pacientes pregunta
   "¿a quién se le aplicó TENS este mes?". Las vistas no saben de relación asistencial, así que **no**
   tiene `consulta_sql`; `practicas_aplicadas` llama al servicio de `encounter` con su actor, que ya
   recorta a la actividad propia, y la respuesta dice que son sólo sus pacientes.

## 11. Decisiones del constructor

Las deja abiertas este diseño; se toman en el plan con un dato:

- Streaming de la respuesta (SSE) en esta etapa o en la siguiente.
- `effort` por defecto (`low` vs `medium`) según latencia y precisión medidas.
- Cuántos turnos de historial y cuánto de cada resultado vuelve al modelo.
- El formato exacto de "dónde se carga" en `ficha_practica` (ruta del frontend).
- Migración: sólo si hace falta una vista nueva para las fichas o para documentar columnas
  (`COMMENT ON`). Reservar **V47** y contrato **0.193.0** al empezar.
