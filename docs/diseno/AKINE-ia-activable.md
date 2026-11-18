# IA activable — prendida al levantar y un interruptor por función en cada centro

18/11/2026. Pedido del usuario: que la inteligencia artificial arranque prendida, con la GPU si la
hay, cuando se levanta el proyecto, y que en Configuración haya un interruptor que prenda o apague
cada función y muestre u oculte sus pantallas.

Hasta ahora el asistente de datos, la evolución del caso, sus sugerencias y la biblioteca vivían
detrás de propiedades del despliegue **apagadas por defecto**. Prenderlas era editar variables de
entorno, levantar Ollama a mano y esperar que la primera pregunta cargara el modelo. Y un centro no
tenía forma de decir "esto no lo quiero usar": lo que el servidor ofrecía, lo veían todos.

## Qué se decidió

- **Dos capas, y activa es las dos.** El **despliegue** decide si cada función *existe*, con las
  propiedades de siempre (`akine.asistente.enabled`, `akine.evolucion.enabled`,
  `akine.evolucion.sugerencias.enabled`, `akine.biblioteca.enabled`). El **centro** decide si la
  *quiere usar*, en la tabla `organization_ia`. Una función está activa sólo si las dos capas la
  tienen prendida.
- **Sin fila, todo prendido.** Un centro que nunca tocó la pantalla usa lo que el despliegue
  ofrece. La fila se crea en la primera edición.
- **Las sugerencias dependen de la evolución y de la biblioteca**, en las dos capas: parten del
  análisis de evolución y buscan en la biblioteca. Si el centro apaga cualquiera de las dos, las
  sugerencias quedan apagadas aunque su interruptor diga que no.
- **Prender lo que el despliegue no ofrece se guarda igual.** La preferencia queda lista para cuando
  el servidor tenga la función; mientras tanto la respuesta la informa como no activa.
- **Las cuatro funciones son `FuncionIa`**: `ASISTENTE`, `EVOLUCION`, `SUGERENCIAS` y `BIBLIOTECA`.

## Dónde se chequea

`organization.spi.FuncionesIaDelCentro` responde sólo por el centro; cada módulo sigue mirando su
propia propiedad del despliegue antes de preguntarle. Los cuatro puntos de entrada son:

- `AsistenteService` (asistente de datos).
- `EvolucionDelCasoService` (alertas e indicadores de la ficha del caso).
- `SugerenciasDeEvolucionService`, en generar, listar y responder.
- `BibliotecaAcceso`, la puerta única de la biblioteca, después del chequeo de tenant.

**Apagada por el centro responde igual que apagada por el despliegue**: 409 `asistente-deshabilitado`
y `biblioteca-deshabilitada`, 404 en la evolución y las sugerencias. Quien llama no puede distinguir
una cosa de la otra, y no le hace falta: en los dos casos la pantalla no debería estar a la vista.

## Modelo y contrato (0.176.0, aditivo; migración V27)

- `organization_ia`: una fila por organización (`organization_id` con unique y FK), cuatro booleanos
  que nacen en `true` y `version` propia. Dueño `organization`. Es configuración, no historia: el
  `PUT` reemplaza los cuatro valores y el hecho queda en `audit_event` como
  `ORGANIZATION_IA_UPDATED`.
- `GET /api/v1/organizations/{orgId}/ia`: alcanza con ser miembro vigente, porque la aplicación de
  todos los roles lo necesita para mostrar u ocultar el asistente, la biblioteca y la evolución.
  Devuelve cada función con tres datos: `despliegue`, `centro` y `activa`.
- `PUT .../ia`: reemplazo completo de los cuatro interruptores, con `version` (409
  `concurrent-modification` si quedó vieja). Exige `tenant:update`, o sea el `ORG_ADMIN`.
- `GET .../ia/motor`, `POST .../ia/motor/precarga` y `POST .../ia/motor/prueba`: estado del servidor
  de modelos (si responde, qué modelos tiene descargados y cargados, cuánta memoria y si corren en
  GPU o CPU), cargarlos en memoria y una pregunta de prueba con su tiempo. También con
  `tenant:update`. Los resuelve `platform.spi.modelo.MotorDeModelos`, con su implementación sobre
  Ollama.

## Perfil `ia` y el arranque

Las propiedades del despliegue siguen apagadas en `application.yml`. Las prende un perfil aparte,
`ia` (`application-ia.yml`), que se suma a `local`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,ia
```

**No va en `application-local.yml` a propósito**: unos 150 tests de integración corren con el perfil
`local` y esperan la IA apagada, y en el CI no hay Ollama ni el modelo ONNX de embeddings. Prenderla
ahí los rompía a todos. Dentro del perfil, cada función se puede apagar en una máquina con su
variable (`AKINE_ASISTENTE_ENABLED=false`, etcétera) sin tocar el archivo.

`ops/levantar.ps1` hace el arranque completo:

1. **Contenedores.** Levanta `postgres`, `mailpit` y `ollama`. Si `nvidia-smi` responde, suma
   `compose.gpu.yaml` y Ollama corre en la placa: cada respuesta tarda ~1,5 s en vez de ~24 s en CPU
   (medido el 11/11/2026 en una RTX 4050). Los contenedores tienen nombre fijo y pueden existir
   desde otro checkout o worktree, donde para compose son de otro proyecto y `up` choca por el
   nombre: **si ya existe se arranca tal cual** y sólo se crea con compose el que falta. Si
   `akine-ollama` existe sin GPU, avisa cómo recrearlo.
2. **Ollama responde.** Lo espera en `http://127.0.0.1:11434`, **no en `localhost`**: PowerShell 5.1
   prueba `::1` primero y Ollama escucha sólo en IPv4.
3. **Modelo descargado.** Si falta, lo baja una sola vez.
4. **Precarga** con `num_ctx` 8192, el mismo de las llamadas reales. Sin él Ollama carga el modelo con
   4096 y la primera pregunta lo vuelve a cargar, que es justo la espera que la precarga quiere
   evitar. Por la misma razón `OllamaModeloDeLenguaje.precargar` ahora manda el `num_ctx`.
5. **Embeddings de la biblioteca.** Si no está el modelo ONNX, avisa: la biblioteca queda apagada en
   ese arranque.
6. **Backend** con `local,ia`.

`-SinBackend` deja sólo la infraestructura y `-SinIa` arranca como antes, con `local` y sin Ollama.

## La pantalla

Organización → **Inteligencia artificial**, sólo para quien tiene `tenant:update`: los cuatro
interruptores, con la dependencia de las sugerencias a la vista, y el estado del motor con los
botones de precarga y prueba. El resto de la aplicación lee el `GET` y oculta del menú y de la ficha
del caso lo que no está activo. Se trabaja en `appKine-web`, rama `akine-ia-activable-web`.

## Lo que no hace

- **Los parámetros del modelo siguen en variables de entorno**: cuál modelo, la URL de Ollama, el
  `keep_alive`, las rutas del ONNX. Es decisión del usuario; el centro elige qué usar, no cómo
  corre.
- **La abstención de la evolución sigue pendiente**: las sugerencias todavía no se abstienen en temas
  sin material (0 de 4 en la medición de la fase 2). Este cambio no la toca.
