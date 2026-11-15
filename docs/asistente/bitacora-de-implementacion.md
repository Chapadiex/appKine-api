# Asistente de datos — bitácora de problemas y soluciones

Registro de lo que salió distinto de lo planeado al implementar `AKINE-asistente-de-datos-plan.md`, y qué se
hizo en cada caso. Cada entrada sigue el criterio del proyecto: se resuelve con lo que el diseño y las reglas
del repo ya establecen; si no alcanzan, se elige la opción más conservadora y se declara acá.

| # | Etapa | Problema | Solución aplicada |
|---|---|---|---|
| 1 | Medición | La prueba con `NUM_GPU=0` dejó `qwen3:4b-instruct` cargado en CPU durante su `keep_alive` de 2 h, y la biblioteca habría quedado lenta | Se descargó el modelo con `keep_alive: 0` y se volvió a cargar en GPU. Queda escrito en `evaluacion/resultados-2026-11-15.md` |
| 2 | Medición | El prompt v3 se ajustó después de ver una falla del set de control, así que su 20/20 deja de ser una medición limpia | Se declaró en los resultados. El número limpio es el de v2 (19/20), y B-5 mide con un tercer set nuevo |
| 3 | Medición | Cambiar el prompt mueve fallas de lugar: con v3 volvió `MES:PASADO` en una pregunta que con v2 salía bien | No se siguió ajustando el prompt. Se normalizan los alias en el backend (`ResolutorDePeriodo.normalizar`) |
| 4 | B-1 | En el plan aparecía un rol `RECEPCION`, que no existe. La recepción es `ADMINISTRATIVO` | Se corrigió el plan, y `asistente:use` quedó para `ORG_ADMIN`, `CONSULTORIO_ADMIN`, `PROFESIONAL` y `ADMINISTRATIVO` |
| 5 | B-1 | `Intencion` estaba planeada en `domain`, pero los otros módulos sólo pueden importar el `spi` (`ModuleArchitectureTest`) | `Intencion` vive en `assistant.spi` |
| 6 | B-1 | Los `.java` del repo no llevan tildes y los archivos nuevos sí | Se quitaron, por convención del repo. Los documentos sí llevan tildes |
| 7 | B-1 | En un record, la fábrica `sinPermiso()` chocaba con el componente `sinPermiso` y no compilaba | Se renombró la fábrica a `denegado()` |
| 8 | B-1 | ArchUnit pasaba con el `spi` sin consumidores, lo que no prueba la arista `módulo → assistant.spi` | Se puso una clase sonda en `scheduling` que implementa la herramienta, se corrió ArchUnit (5/5) y se borró la sonda junto con su `.class` de `target/` |
| 9 | B-2 | El modelo acorta los títulos de clase compuestos ("yoga para embarazadas" → "yoga") | Se resuelve en B-4: la clase se busca contra los títulos reales del período y gana el más largo que aparece en la pregunta |
| 10 | B-3 | El primer arranque real falló: `AsistenteService` tenía dos constructores (uno con `Clock` para los tests) y Spring no sabía cuál usar (`No default constructor found`). Los unitarios no lo veían porque construyen el servicio a mano | `@Autowired` en el constructor público, la convención de `activity` (`AjusteDePaseService`). Es la lección del 29/09: un error de creación de bean sólo aparece ejecutando |
| 11 | B-4 | `ClasesEnElAsistente` acotaba siempre al título más largo escrito en la pregunta. Con "yoga" a secas se quedaba con las clases "Yoga" y dejaba afuera "Yoga para embarazadas", al revés de lo que decía su propio Javadoc | Sólo se acota si el título que nombra la pregunta es más largo que lo que entendió el modelo, que es el caso en que el modelo lo acortó. Cubierto en `ClasesEnElAsistenteTest` |
| 12 | B-3 | El `Resultado` de la respuesta era un enum de `application` con un nombre genérico, que springdoc podía publicar como schema `Resultado` y chocar con otro | En el DTO viaja como `String` con `allowableValues`. `SchemaNameCollisionTest` en verde |
| 13 | B-3 | Una transacción que envolviera toda la consulta habría quedado marcada rollback-only en cuanto una herramienta recibiera `AccessDeniedException` de su servicio, y Spring habría lanzado `UnexpectedRollbackException` | `AsistenteService` no tiene transacción propia. Cada lectura corre en la de su servicio y la auditoría en una `REQUIRES_NEW` (`AuditoriaDelAsistente`) |
| 14 | B-3 | Un `personaId` elegido por el cliente podía apuntar a cualquier persona | Se usa sólo si está entre los candidatos de la búsqueda del mismo nombre. Si no está, responde "no la encontré" |
| 15 | B-3 | Prueba de punta a punta: "¿Quiénes vienen mañana?" respondía 400. `RecepcionService.delRango` toma `[desde, hasta)` y la herramienta le pasaba el último día inclusive. Además, en silencio, quedaba afuera el último día de cada período (el domingo de "esta semana") | `TurnosEnElAsistente` pasa `hasta + 1`. Los reportes toman los dos extremos inclusive y las clases instantes con fin exclusivo, así que no cambian. Cubierto en `TurnosEnElAsistenteTest` |
| 16 | B-3 | Prueba de punta a punta: "¿Se cargó la sesión de fanny?" heredó "la semana pasada" de la pregunta anterior, que era sobre otra persona | El sujeto se hereda si falta, pero el período sólo si la pregunta es un seguimiento ("¿y …?") o sigue hablando del mismo sujeto. Cubierto en `InterpretadorTest` (Fanny y el caso H04 "¿y a pilates?") |
| 17 | B-4 | Los turnos reservados que ya pasaron sin llegada registrada aparecían como "Pendientes" | La plantilla recibe el "ahora" del reloj del servicio y separa "Sin llegada registrada" (pasados) de "Próximos" (futuros). No afirma que la persona faltó: eso lo dice sólo `AUSENTE` |
| 18 | B-4 | La matriz decía que un `ADMINISTRATIVO` que pregunta por la facturación recibe "sin permiso". En la prueba real recibió los números, porque el reporte de M23 se los muestra igual en pantalla (DP-15) | El asistente hace lo correcto, que es lo mismo que la pantalla. Se corrigió el texto de la matriz (§23) |
| 19 | B-3 | Al reiniciar la pila, la instancia anterior de Java seguía escuchando en el 8097 aunque `preview_stop` había terminado su proceso padre, y la nueva arrancó en otro puerto | Antes de volver a arrancar, esperar a que el 8097 quede libre. `asistente-backend.ps1` fija `SERVER_PORT=8097` |
| 20 | B-6 | El plan reservaba la minor 0.169.0, pero `main` ya la había tomado con el PR #202 (búsqueda por correo) | El asistente publica la **0.170.0**. Al rebasear hay que confirmarla otra vez (regla 5 de `docs/fases/01-trabajo-en-paralelo.md`) |
| 21 | B-6 | `OpenApiContractIT` falló con "la versión del contrato está bifurcada": subí la versión en `pom.xml` y no en `application.yml` | Se subió también `akine.contract.version` en `application.yml` y se regeneró. Es el gate haciendo su trabajo |
| 22 | B-6 | Los IT necesitaban fijar la interpretación del modelo, pero reciben el bean ya creado | `ModeloDelAsistenteDePrueba` suma `responderJson`, `responderCon` y `reiniciar`, como el doble de la biblioteca |
| 23 | B-6 | El IT del interruptor apagado podía costar un arranque de Spring más en el CI | `AsistenteApagadoIT` no declara propiedades propias del asistente, así que reusa el contexto que ya comparten los demás IT |
| 24 | W-2 | El plan proponía un panel lateral (drawer) abierto desde la barra superior, pero el bundle inicial tiene poco margen y la consulta a la biblioteca ya es una página con su entrada en el menú | Página `/asistente` con ruta perezosa y entrada "Asistente de datos" en Atención, con el mismo lenguaje visual que la consulta a la biblioteca. El bundle inicial queda en 405,84 kB de 448 |
| 25 | W-1 | `api:check` y `check-problem-types` leen el contrato de `../appKine-api`, que en esta máquina es el checkout principal y está en otra rama, así que en local fallan aunque todo esté alineado | Se corrieron con una copia temporal que apunta a `wt-API-ASISTENTE` ("Contrato alineado: 0.170.0", sin tipos desconocidos) y se borró la copia. En el CI lo resuelve la rama espejo del backend con el mismo nombre (precedente de la marca por centro) |
| 26 | W-1 | El tipo `problemas-del-contrato.ts` es exhaustivo y no compilaba con el problem type nuevo | Se sumó `asistente-deshabilitado` a la lista. Es el gate haciendo su trabajo |
| 27 | W-1 | Se supuso que el cliente generado elegía `Accept: application/problem+json` (la trampa del 406 que el proyecto ya pagó) y `AsistenteApi` forzaba `application/json` | **Premisa equivocada**, la encontró `/simplify`: para esta operación el cliente ya pone `application/json` primero. Se quitó el forzado; sólo hace falta donde se pide CSV o binario |
| 28 | W-3 | A 375 px el aviso de la cabecera no cortaba línea y la página desbordaba (604 px de contenido en 375) | `.asistente__aviso` con `flex: 1 1 100%`, `min-width: 0` y `white-space: normal`. Medido otra vez: 375 de 375 |
| 29 | W-3 | El `textarea` usaba la tipografía monoespaciada del navegador | `font: inherit` |
| 30 | W-3 | Con el panel del navegador oculto, las teclas no llegaban a la página y la pregunta no se escribía | La prueba usa `form_input` y clics por referencia. Es una limitación de la herramienta, no del producto |
| 31 | W-3 | En el navegador, "no tiene llegadas registradas" seguido de "Sin llegada registrada: …" repetía lo mismo | Sin ninguna llegada, la plantilla lista en la misma frase los turnos que tenía (`PlantillasTest`) |

## Revisión de `/simplify` (15/11)

Cuatro revisores en paralelo (reuso, simplificación, eficiencia y profundidad de los arreglos). Lo aplicado:

| # | Hallazgo | Qué se hizo |
|---|---|---|
| 32 | Plegado de acentos reimplementado en `ResolutorDePeriodo`, `Interpretador` y `ClasesEnElAsistente` | Se usa `platform.spi.listado.TextoDeBusqueda.plegar`, que ArchUnit permite desde cualquier módulo |
| 33 | Los reportes admitidos estaban escritos en tres lugares (y un control inalcanzable en `ReportesEnElAsistente`) | `Intencion.REPORTES` en el `spi` es la lista única. `assistant` no puede importar `ReporteCode` porque `reporting` ya depende de su `spi` |
| 34 | "Qué campos usa cada intención" estaba repetido en `Interpretador` y en `Interpretacion` | `usaPersona`, `usaClase` y `usaReporte` viven en `Intencion` |
| 35 | `AccessDeniedException` se atrapaba en cada herramienta y otra vez en el servicio | Un solo `catch` en `AsistenteService.ejecutar`. Las herramientas lo dejan propagar y el Javadoc del `spi` lo dice |
| 36 | La regla "fin inclusive + 1" estaba en dos lugares, y el pedido llevaba dos instantes derivables | `PedidoDeHerramienta` deriva `hastaExclusivo()`, `desdeInstante()` y `hastaInstante()` |
| 37 | La resolución de persona tenía cuatro niveles de anidamiento y una variable `corte` reasignada en cinco ramas | `resolverPersona` con retornos tempranos y `idDe` |
| 38 | `ClasesEnElAsistente` pedía el detalle de 10 clases y la respuesta muestra 3 | Pide el detalle de 3 y pliega cada título una sola vez |
| 39 | `AuditoriaDelAsistente` usaba `REQUIRES_NEW`, el patrón que trabó el pool el 11/10 si alguien envuelve la consulta en una transacción | `REQUIRED`: hoy abre su propia transacción, y si la consulta se envuelve, se suma a esa |
| 40 | Web: la tabla se recalculaba en cada tecla, y las fechas salían en la zona del navegador | La tabla se arma una vez al llegar la respuesta, con `formatearInstante` y la zona de la sede (`DiaDeLaSede`) |
| 41 | Web: un 400 mostraba un texto fijo y el traductor no estaba en `validacion-en-cada-traductor.spec.ts` | Muestra `error.detalle` y el traductor quedó registrado |
| 42 | Web: el aviso iba en el lugar de la cabecera pensado para pastillas, y el CSS pisaba las reglas del componente compartido | El aviso pasó a la primera línea de la tarjeta, sin pisar `cabecera-de-pagina` |

**No aplicado, y por qué:**
- **Mover la resolución de títulos de clase al `assistant`.** Sacar `pregunta` del `spi` obliga a traer todas las clases del período antes de acotar, o a una segunda vuelta. Hoy el tope de 3 se aplica después de acotar, en un solo paso.
- **Extraer el puerto y el adaptador de Ollama a `platform.spi`.** Es la condición de salida de D-3, que se cumple con el tercer consumidor.
- **Un componente de charla compartido con la consulta a la biblioteca.** Toca la pantalla de otra funcionalidad, que está fuera del alcance de esta tanda.
- **Que el modelo devuelva `seguimiento: true|false`.** Cambia el prompt y obliga a volver a medir. La heurística actual pasó 25/25 en el set limpio.

## Prueba de clases de punta a punta (15/11)

La base de QA no tenía clases. Se armó una por la API, como lo haría el administrador, en la copia aislada `akine_asistente`: el box "GYM" pasó de capacidad 1 a 10 (`PATCH` de espacio), se programó Yoga para el lunes 16/11 a las 16:00 con el profesional de QA, y se inscribió a dos personas. La base no se tocó a mano.

| # | Problema | Solución aplicada |
|---|---|---|
| 43 | No se podía programar ninguna clase: una clase grupal pide capacidad mínima 2 y el único box de la sede admitía 1 | Se subió la capacidad del box por la API, en la copia aislada. Es un dato de prueba, no un defecto del producto |
| 44 | **Defecto real:** "No encontré una clase de yoga", con la clase programada. El título de la clase es opcional, y una clase sin título se muestra con el nombre de su oferta, pero la herramienta buscaba sólo en `titulo` | `ClasesEnElAsistente.nombre()`: el título, o el nombre de la oferta si no tiene uno propio. Cubierto en `ClasesEnElAsistenteTest` |
| 45 | "¿Quiénes **van** a yoga mañana?" salía como agenda del día: el prompt describía las clases sólo en pasado | El prompt suma "van o están inscriptos" y "si nombra una actividad grupal, es esta intención". Un primer intento con un ejemplo ("el jueves") contagió `DIA:JUEVES` a preguntas que decían "ayer", y se sacó el ejemplo. Una aclaración en texto para la agenda ("si nombra a una persona, no es agenda") arregló la regresión que apareció en "qué días vino Pedro". Resultado: 90/90 en intención y 88/90 completos en los tres sets anteriores, igual que antes, y 5/5 en un set nuevo de preguntas en futuro (`preguntas-futuro.json`) |
| 46 | Para una clase que todavía no empezó, la respuesta decía "2 sin registrar" y no quiénes van | Una clase `PROGRAMADA` lista sus inscriptos. En una clase empezada se nombra también a los que no tienen asistencia registrada (`PlantillasTest`) |

## Revisión de `/code-review` (15/11)

| # | Hallazgo | Solución aplicada |
|---|---|---|
| 47 | El historial lo manda el cliente y se heredaba sin validar: un período o un reporte inventados llegaban al resolutor o a `ReporteCode.valueOf` y volvían como 400 con un mensaje interno | `Interpretador.sanear` valida cada turno previo igual que la salida del modelo (`InterpretadorTest`) |
| 48 | Un elemento `null` en `historial` terminaba en `NullPointerException` (500), el mismo patrón que `{"items":[null]}` del #146 | `List<@NotNull @Valid TurnoPrevioRequest>`: 400. Cubierto en `AsistenteIT` |
| 49 | Una `FECHA` del modelo se validaba con su año y se resolvía con el actual: un 29/02 lanzaba una excepción | Si ese día no existe este año, queda la fecha completa del modelo (`ResolutorDePeriodo`) |
| 50 | "¿Quiénes van a pilates el jueves?" resolvía al jueves pasado: sólo la agenda miraba hacia adelante | `AsistenteService.enFuturo` detecta los verbos en futuro y las clases preguntadas así miran hacia adelante |
| 51 | "Turnos de enero" preguntado en noviembre resolvía a enero del año pasado | Hacia adelante, un mes anterior al actual es del año próximo |
| 52 | Una consulta que fallaba después de pasar el permiso no quedaba auditada, aunque ya hubiera leído el padrón | Se audita con `resultado` ERROR y se relanza la excepción |

## Mejoras de `/simplify` aplicadas después del cierre (15/11)

| # | Mejora | Qué se hizo |
|---|---|---|
| 53 | Sin un tope, cada consulta de más retenía un hilo del servidor hasta vencer su plazo, porque Ollama atiende de a pocas | `ModeloConCupo` decora el adaptador con un semáforo justo (`akine.asistente.modelo.concurrencia`, 2 por defecto). La consulta que no consigue lugar dentro de su plazo responde 503 `asistente-no-disponible`, y lo que esperó en la fila se descuenta del plazo de la llamada (`ModeloConCupoTest`) |
| 54 | La tabla de datos mostraba los valores crudos (`EN_ATENCION`, `PRESENTE_TARDE`, `DINERO`) | Mapa local de etiquetas en la pantalla del asistente y números con formato es-AR. Es local porque ADR-0004 no deja importar los formateadores de otras funcionalidades |

**Siguen sin aplicar, con su motivo** (decisiones fuera del plan, no defectos):
- **Mover la resolución de títulos de clase al `assistant`.** Obliga a traer todas las clases del período antes de acotar, o a una segunda vuelta.
- **Extraer el puerto y el adaptador de Ollama a `platform.spi`.** La decisión D-3, aceptada por el usuario, es copiar y extraer con el tercer consumidor.
- **Un componente de charla compartido con la biblioteca.** Toca la pantalla de otra funcionalidad.
- **Que el modelo devuelva `seguimiento: true|false`.** Cambia el prompt y obliga a volver a medir, y la heurística actual pasa los sets.

**La latencia sin GPU** (7 a 9 s por pregunta) no se resuelve en código: es una decisión de despliegue (U-02). Las opciones son un servidor con GPU, aceptar la espera, o cambiar el adaptador por un modelo en la nube (D-1), y en ese caso al modelo sólo le llega la pregunta.

## CI del PR #204 (15/11)

| # | Problema | Solución aplicada |
|---|---|---|
| 55 | `MensajesDeValidacionTest` exige que toda restricción de la capa `api` tenga un `message` que nombre su campo; los DTO del asistente usaban los mensajes por defecto | `message` propio en cada restricción de `ConsultaAsistenteRequest`, `TurnoPrevioRequest` e `InterpretacionDto` |
| 56 | `PermissionEvaluatorServiceTest` y `PermisosEsquemaIT` esperan la lista exacta de permisos efectivos de cada rol, y `asistente:use` es nuevo | Se sumó a las listas esperadas, igual que `biblioteca:consultar` |
| 57 | Por la regla de correr sólo lo que toca el cambio, esos tests no se habían corrido en local, y el CI cortó en la etapa de unitarios | Antes de volver a subir se corrió la suite unitaria completa en local (4741 tests, 0 fallas) y los IT de permisos (`PermisosEsquemaIT` 11/11, `MatrizDePermisosIT` 14/14) |
