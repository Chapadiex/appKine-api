# AKINE — Asistente de datos: plan de implementación

> Plan del 15/11/2026 para `AKINE-asistente-de-datos.md`. La meta es **una primera versión usable
> rápido**: cinco intenciones, sólo lectura, sin tablas nuevas, con el modelo local que ya está
> instalado. Todo lo demás va a la fase 2.

## 0. Antes de escribir código

| # | Qué | Quién | Bloquea |
|---|---|---|---|
| 0.1 | Decidir D-1 a D-4 (§10 del diseño). Con las recomendaciones, el plan no cambia | usuario | todo |
| 0.2 | Reservar la minor del contrato (hoy sería 0.169.0) en `docs/fases/01-trabajo-en-paralelo.md` | agente | B-6 |
| 0.3 | Con D-2 = permiso nuevo: fila `asistente:use` en `docs/seguridad/matriz-permisos-minima.md` (ORG_ADMIN, CONSULTORIO_ADMIN, PROFESIONAL y ADMINISTRATIVO, que es el rol de la recepción; **no** PACIENTE). Hecho en B-1 (§23 de la matriz) | agente | B-1 |
| 0.4 | Escribir 40 preguntas reales con su intención esperada en `src/test/resources/asistente/preguntas.yaml` | usuario + agente | B-5 |

**Ramas:** `wt-API-ASISTENTE` (rama `akine-asistente-datos`) y `wt-WEB-ASISTENTE`. Un PR por repo,
con la regla de siempre: primero el backend con el contrato y después el cliente.

## 1. Backend — `appKine-api`

Orden pensado para tener algo que responda al final del paso B-3 y completar después.

### B-1 · Esqueleto del módulo `assistant` (medio día) — hecho el 15/11

> **Hecho.** `spi` (`Intencion`, `HerramientaDelAsistente`, `ActorDelAsistente`, `PedidoDeHerramienta`,
> `ResultadoDeHerramienta`), puerto `ModeloDeLenguaje` con una sola operación, `completarJson`, adaptador
> `OllamaModeloDelAsistente` (sólo la llamada completa, no los 282 renglones con streaming de `knowledge`),
> `ModeloDelAsistenteDePrueba`, `AsistenteProperties`/`AsistenteConfig` con `akine.asistente.enabled` en
> `false` y `asistente:use` en `PermissionCode` y `RolePermissions`. Sin migración ni cambio de contrato: el
> permiso vive en código. Diferencias con lo planeado: `Intencion` vive en `spi` y no en `domain`, porque
> los otros módulos la necesitan y sólo pueden importar el `spi`. La excepción `asistente-deshabilitado` y el
> controller pasan a B-3, que es donde hay operación. Tampoco hay precarga del modelo: la de la biblioteca ya
> lo sube, y si está apagada la primera pregunta paga la carga.
> **Verificado:** ArchUnit 5/5 también con una clase sonda en `scheduling` que implementa la herramienta
> llamando a `RecepcionService.delRango` (borrada después); convenciones 12/12; `RolePermissionsTest` 136/136;
> `OllamaModeloDelAsistenteTest` 3/3 contra un servidor HTTP falso.

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

### B-2 · Interpretador (1 día) — hecho el 15/11

> **Hecho.** `Interpretador`, `Interpretacion`, `TurnoPrevio`, `ResolutorDePeriodo` y `RangoDeDias` en
> `application`, más el bean `interpretadorDelAsistente`, que lee el prompt del classpath. Decisiones que no
> estaban en el plan: un `REPORTE` sin reporte válido es `FUERA_DE_ALCANCE` (no se repregunta); en un
> seguimiento se heredan sólo los campos que usa la intención nueva, y nunca desde ni hacia
> `FUERA_DE_ALCANCE`; el historial se recorta a los últimos 3 turnos; una pregunta de más de 300 caracteres
> no llama al modelo; un día de la semana va hacia adelante sólo para `AGENDA_DEL_DIA`, y un mes posterior al
> actual se toma del año pasado.
> **Verificado:** 16 unitarios del interpretador y 36 del resolutor. `InterpretadorContraOllamaTest` (corre sólo
> con `AKINE_OLLAMA_URL`, así que el CI lo saltea) pasó los 65 casos por el código Java real contra Ollama:
> **intención 65/65 y completos 63/65**. Las dos fallas son los títulos de clase compuestos, que se resuelven en B-4.
> Ojo: el prompt v3 se ajustó viendo esos casos, así que la medición limpia sigue pendiente para B-5.

- `Interpretador.interpretar(pregunta, historial) → Interpretacion`: **una sola** llamada en modo
  JSON. El prompt de sistema lista las cinco intenciones, el vocabulario de períodos (§4.5 del
  diseño) y tres ejemplos por intención.
- `Interpretacion(intencion, textoPersona, textoClase, periodo, reporte)`, con todo validado: un
  JSON inválido, una intención desconocida o un período fuera del vocabulario terminan en
  `FUERA_DE_ALCANCE`, nunca en una excepción.
- Si el historial trae una aclaración resuelta (`personaId` elegido), se salta la búsqueda.
- `ResolutorDePeriodo`: de `ESTA_SEMANA` a `(lunes, domingo)` con la zona de la organización.
  Lunes como inicio de semana. Normaliza los alias (`MES:PASADO` → `MES_PASADO`) y **extrae las fechas
  escritas del texto original** (`20/11`) sin usar el año del modelo.
- Seguimientos: un campo vacío en la interpretación se hereda de la interpretación anterior.
- Arranca con el prompt ya medido (v3), que se movió a `src/main/resources/asistente/prompt-interpretador.txt`: el backend y `evaluar.mjs` leen el mismo archivo.
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
| `AsistentesDeClaseHerramienta` | activity | `ClaseService.listar` (gana el título real más largo contenido en la pregunta, sin distinguir mayúsculas ni tildes; con empate, aclaración) + `AsistenciaService.detalleOperativo` |
| `ReporteHerramienta` | reporting | `ReporteService.generar(actor, sede, ECONOMICO / TURNOS / CLASES, desde, hasta)` → indicadores |

- Una plantilla por intención en `Plantillas`. Ninguna suma conceptos económicos.
- Un 403 de un servicio (`AccesoDenegado` de cada módulo) → "no tenés permiso para ver eso", con
  200.
- Auditoría `ASISTENTE_CONSULTA` (intención, parámetros resueltos, cantidad de filas; **sin la
  pregunta textual**).

### B-5 · Medir la interpretación (medio día) — adelantada el 15/11, ver `docs/asistente/evaluacion/`

- Ya existe la prueba fuera del backend (`evaluar.mjs`, 45 + 20 preguntas). En B-5 se vuelve a correr
  contra el interpretador real, con un **tercer set nuevo** para tener una medición limpia.
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
