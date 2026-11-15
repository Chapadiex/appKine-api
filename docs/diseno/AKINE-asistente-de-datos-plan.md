# AKINE — Asistente de datos: plan de implementación

> Plan del 15/11/2026 para `AKINE-asistente-de-datos.md`. La meta es **una primera versión usable
> rápido**: cinco intenciones, sólo lectura, sin tablas nuevas, con el modelo local que ya está
> instalado. Todo lo demás va a la fase 2.

## 0. Antes de escribir código

| # | Qué | Quién | Bloquea |
|---|---|---|---|
| 0.1 | Decidir D-1 a D-4 (§10 del diseño). Con las recomendaciones, el plan no cambia | usuario | todo |
| 0.2 | Reservar la minor del contrato (hoy sería 0.169.0) en `docs/fases/01-trabajo-en-paralelo.md` | agente | B-6 |
| 0.3 | Con D-2 = permiso nuevo: fila `asistente:use` en `docs/seguridad/matriz-permisos-minima.md` (ORG_ADMIN, PROFESIONAL, ADMINISTRATIVO, RECEPCION; **no** PACIENTE) | agente | B-1 |
| 0.4 | Escribir 40 preguntas reales con su intención esperada en `src/test/resources/asistente/preguntas.yaml` | usuario + agente | B-5 |

**Ramas:** `wt-API-ASISTENTE` (rama `akine-asistente-datos`) y `wt-WEB-ASISTENTE`. Un PR por repo,
con la regla de siempre: primero el backend con el contrato y después el cliente.

## 1. Backend — `appKine-api`

Orden pensado para tener algo que responda al final del paso B-3 y completar después.

### B-1 · Esqueleto del módulo `assistant` (medio día)

```
com.akine.assistant
├── api/            AsistenteController, AsistenteApiActor, AsistenteProblemHandler, dto/
├── application/    AsistenteService, Interpretador, ResolutorDePeriodo, Plantillas, OperatingActor
├── domain/         Intencion, Interpretacion, PermissionCodes, exception/
│   └── port/       ModeloDeLenguaje, MensajeChat, OpcionesGeneracion   (copia de knowledge, D-3)
├── infrastructure/ OllamaModeloDeLenguaje (copia), ModeloDeLenguajeDePrueba, config/AsistenteProperties
└── spi/            HerramientaDelAsistente, ActorDelAsistente, PedidoDeHerramienta, ResultadoDeHerramienta
```

- Propiedades `akine.asistente.enabled` (por defecto `false`), `akine.asistente.modelo.tipo`
  (`ollama` | `prueba`), `url`, `modelo` (`qwen3:4b-instruct`), `plazo` (`10s`).
- Con el flag apagado: no se crea el bean del modelo y la operación responde 409
  `asistente-deshabilitado`, igual que la biblioteca.
- `PermissionCodes.ASISTENTE_USE` y su alta en el catálogo de permisos.
- **Verificar:** `ModuleArchitectureTest` en verde con el módulo vacío.

### B-2 · Interpretador (1 día)

- `Interpretador.interpretar(pregunta, historial) → Interpretacion`: **una sola** llamada en modo
  JSON. El prompt de sistema lista las cinco intenciones, el vocabulario de períodos (§4.5 del
  diseño) y tres ejemplos por intención.
- `Interpretacion(intencion, textoPersona, textoClase, periodo, reporte)`, con todo validado: un
  JSON inválido, una intención desconocida o un período fuera del vocabulario terminan en
  `FUERA_DE_ALCANCE`, nunca en una excepción.
- Si el historial trae una aclaración resuelta (`personaId` elegido), se salta la búsqueda.
- `ResolutorDePeriodo`: de `ESTA_SEMANA` a `(lunes, domingo)` con la zona de la organización.
  Lunes como inicio de semana.
- **Tests:** unitarios de `ResolutorDePeriodo` (bordes de semana y mes, zona) y del parseo del JSON
  con `ModeloDeLenguajeDePrueba`. Nada más.

### B-3 · Primera herramienta de punta a punta: `ASISTENCIA_PERSONA` (1 día)

Esto da la primera respuesta real y valida el patrón entero.

- `person/infrastructure/BuscarPersonaHerramienta` → `PersonaService.buscar(actor, PersonaBusqueda(texto, ...), 0, 5)`.
- `scheduling/infrastructure/AsistenciaDePersonaHerramienta` → `RecepcionService.delRango(actor,
  sede, desde, hasta, null, null, true, new FiltroDeTurnos(Set.of(), personaId))`.
- `AsistenteService`: interpretar → buscar persona → 0 resultados: "no encontré a…"; más de uno:
  `aclaracion` → ejecutar → `Plantillas.asistencia(...)`.
- `AsistenteController`: `POST /api/v1/asistente/consultas` con `consultorioId` en el cuerpo
  (D-4: la sede activa).
- **Verificar a mano** contra la pila local con Ollama: "¿Juan vino esta semana?".

### B-4 · El resto del catálogo (1 día)

| Herramienta | Módulo | Llama a |
|---|---|---|
| `SesionDePersonaHerramienta` | scheduling | el mismo `delRango`; lee `estadoOperativo` y `sesionId` |
| `AgendaDelDiaHerramienta` | scheduling | `RecepcionService.delDia` |
| `AsistentesDeClaseHerramienta` | activity | `ClaseService.listar` (filtra por `titulo`, sin distinguir mayúsculas ni tildes) + `AsistenciaService.detalleOperativo` |
| `ReporteHerramienta` | reporting | `ReporteService.generar(actor, sede, ECONOMICO / TURNOS / CLASES, desde, hasta)` → indicadores |

- Una plantilla por intención en `Plantillas`. Ninguna suma conceptos económicos.
- Un 403 de un servicio (`AccesoDenegado` de cada módulo) → "no tenés permiso para ver eso", con
  200.
- Auditoría `ASISTENTE_CONSULTA` (intención, parámetros resueltos, cantidad de filas; **sin la
  pregunta textual**).

### B-5 · Medir la interpretación (medio día)

- `InterpretadorEvaluacionIT`, **excluido del CI** como el set de la biblioteca: corre las 40
  preguntas contra Ollama real y reporta aciertos de intención y de parámetros.
- Meta: ≥ 90 % y 10/10 en fuera de alcance. Si no se llega, se ajustan los ejemplos del prompt
  antes de tocar el modelo.

### B-6 · Contrato y tests mínimos (medio día)

- `./mvnw verify -Dakine.contract.update=true` → contrato con la minor reservada.
- **Un IT** `AsistenteIT` con `ModeloDeLenguajeDePrueba` (respuesta fija): un `RECEPCION` pregunta
  facturación y recibe "sin permiso"; un `ORG_ADMIN` de la organización A no ve personas de B; con el
  flag apagado responde 409. Son los tres que prueban las reglas, y ninguno necesita Ollama.
- `/simplify`, `/code-review` y PR.

## 2. Frontend — `appKine-web`

Arranca cuando el contrato del backend esté publicado (después de B-6). Se puede maquetar antes
contra un mock.

### W-1 · Cliente y servicio (1 hora)

- Regenerar el cliente desde la minor nueva.
- `features/asistente/services/asistente.service.ts`: `consultar(pregunta, historial)`, con la
  sede activa de la cabecera.

### W-2 · Panel de chat (1 día)

- Botón "Preguntar" en la barra superior (visible sólo con `asistente:use`) que abre un drawer
  lateral. Mismo lenguaje visual que la consulta de la biblioteca (`features/biblioteca/pages`).
- Burbujas: pregunta y respuesta en `texto`; debajo, una tabla chica con `filas` y el enlace "Ver en
  la ficha".
- `aclaracion` → botones de opción. Al elegir uno, se reenvía la pregunta con el `personaId` en el
  historial.
- Tres sugerencias de ejemplo al abrir, que son las preguntas que sabe responder.
- El historial vive en memoria del componente (signal). No se guarda.
- Respeta `DESIGN.md` (tokens claro y oscuro, contraste AA) y el presupuesto del bundle: **cargar el
  panel con `@defer` o con una ruta perezosa**, porque el bundle inicial no tiene margen.

### W-3 · Verificación (medio día)

- Probarlo contra la pila local con capturas, en claro, oscuro y 375 px.
- Un spec unitario del servicio y del flujo de aclaración. Sin E2E en esta etapa (los E2E están en
  pausa desde el 07/11).

## 3. Calendario orientativo

| Día | Backend | Frontend |
|---|---|---|
| 1 | B-1, B-2 | — |
| 2 | B-3 (primera respuesta real) | maqueta de W-2 contra mock |
| 3 | B-4 | W-2 |
| 4 | B-5, B-6, PR | W-1 con el contrato, W-3, PR |

Unos **cuatro días de trabajo** si D-1 a D-4 salen como se recomienda. El punto de control es el
día 2: si "¿Juan vino esta semana?" no responde bien con el modelo local, se revisa D-1 antes de
seguir.

## 4. Fase 2 (después de usarlo)

En orden de valor estimado:

1. **Asistencia de una persona a clases**: lectura nueva en `activity` por persona y rango. Así
   "¿Juan vino?" cubre turnos **y** clases.
2. **Sesiones sin turno**: lectura por persona en `encounter`.
3. **Más reportes como intención**: `FINANCIADORES`, `PASES`, `SERVICIOS`. Cada uno es una
   plantilla, porque la herramienta ya existe.
4. **Biblioteca como herramienta**: `knowledge` implementa `HerramientaDelAsistente` y el chat
   único responde también sobre documentos.
5. **Varias sedes** en una consulta (D-4).
6. **Redacción por el modelo** o un agente con herramientas encadenadas, si se decide un modelo más
   grande o en la nube (D-1). El `spi` no cambia: las herramientas pasan a ser las *tools* del
   agente.
7. **Extraer el puerto del modelo** a `platform.spi` cuando aparezca un tercer consumidor (D-3).
