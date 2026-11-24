# Evaluación del agente — resultados

> **Sin corridas todavía.** Este archivo explica cómo se corre la evaluación y qué mide cada número.
> La corrida contra Claude cuesta plata y espera el OK del dueño del proyecto con el costo estimado
> (abajo). Los números se agregan acá, una sección por corrida, con fecha, proveedor y commit.

## Qué hay

| Archivo | Qué es |
|---|---|
| `agente-preguntas.json` | 179 preguntas en 50 intenciones (grupos) de 2 a 5 variantes: los cuatro criterios de §2 (asistencia, tratamientos, aranceles, variantes), sin datos, sin permiso, continuaciones de charla, dos "Juan", adversarias y frases de acción. Se usa para afinar |
| `agente-preguntas-control.json` | 44 preguntas de 34 de esas intenciones, con otra redacción. **No se usa para afinar** el prompt ni las descripciones de herramientas: la meta de §2 se mide acá |
| `sembrar-agente.mjs` | Siembra fija por la API real sobre una organización nueva (cabecera del script: qué crea) |
| `evaluar-agente.mjs` | Corre un set contra el backend y puntúa |
| `acciones.json`, `acciones-adversas.json` | Los sets de #226; `evaluar-agente.mjs --set acciones.json` los convierte al vuelo |

Cada pregunta: `{id, grupo, pregunta, actor, historial?, personaId?, requiere?, esperado}` con
`esperado = {resultado, debeContener, noDebeContener, herramientasEsperadas?, debePreguntar, admitePregunta?, accion?}`.
`resultado` puede ser una lista de admitidos; en `debeContener` un arreglo es "alguno de";
`{{juanPerez}}` en `personaIds`/`personaId` es el id que dejó la siembra. Los actores son
`ADMIN` (ORG_ADMIN), `PROFESIONAL_PROPIO` (PROFESIONAL, alcance propio) y `ADMINISTRATIVO`.

## Cómo se corre

1. Backend levantado con el asistente prendido en el despliegue y el proveedor del agente
   configurado (la key va por variable de entorno), Mailpit arriba y la cuenta de plataforma
   (`AKINE_BOOTSTRAP_ADMIN_EMAIL`, DP-14). Mejor sobre una base de prueba: la siembra crea cuentas y
   las propuestas quedan en `asistente_accion`.
2. Ver el plan sin llamar a nada:
   ```bash
   node sembrar-agente.mjs --dry-run
   node evaluar-agente.mjs --dry-run                       # plan y costo estimado
   node evaluar-agente.mjs --dry-run --set agente-preguntas-control.json
   ```
3. Variables de entorno obligatorias (no hay valores por defecto; sin ellas los scripts cortan con
   un mensaje, salvo en `--dry-run`):
   - `AKINE_EVAL_PASSWORD`: contraseña de las cuentas sintéticas que crea la siembra (la misma para
     sembrar y evaluar).
   - `AKINE_PLATAFORMA_EMAIL` y `AKINE_PLATAFORMA_PASSWORD`: la cuenta de plataforma de la base local
     (sólo la siembra).
   - Opcionales: `AKINE_API`, `AKINE_MAILPIT`, `AKINE_TERMINOS_VERSION`, `AKINE_EVAL_SUFIJO`.

   Los dos scripts **sólo aceptan un backend local** (`localhost`, `127.0.0.1`, `[::1]`,
   `*.localhost`, `*.test`); otro host sale con error salvo `--forzar-remoto`.
4. Sembrar (tarda ~15 min: los turnos de hoy duran un minuto y hay que esperar a que empiecen para
   marcar ausentes):
   ```bash
   node sembrar-agente.mjs
   ```
   Deja `.estado/agente-siembra.json` (gitignored) y avisa si alguna deuda leída de vuelta no es la
   que espera el set.
5. Evaluar **el mismo día** de la siembra (las preguntas dicen "hoy"):
   ```bash
   node evaluar-agente.mjs                                  # set de afinado
   node evaluar-agente.mjs --set agente-preguntas-control.json
   node evaluar-agente.mjs --solo tratamientos-tens,arancel-kinesiologia   # sólo esos grupos
   node evaluar-agente.mjs --set acciones-adversas.json
   ```
   Otras opciones: `--base-url`, `--actor ADMIN`, `--limite N`, `--precio 4,20,0.20`, `--vueltas 2.5`.
   El detalle por pregunta queda en `.estado/resultados-*.json`.

## Qué mide cada número

| Métrica | Definición | Meta (§2, con Claude) |
|---|---|---|
| **Correctas** | `resultado` admitido **y** todo `debeContener` en el texto o las opciones **y** nada de `noDebeContener` en texto, filas u opciones (sin mayúsculas, acentos ni separadores de miles). Con `accion`, una tarjeta tiene que ser de ese tipo | ≥ 90 % en **control** |
| **Consistencia por grupo** | Intenciones cuyas variantes son **todas** correctas, sobre el total de intenciones | ≥ 95 % |
| **Repreguntas innecesarias** | `ACLARACION` en una pregunta con `debePreguntar: false` y sin `admitePregunta` | lo más bajo posible |
| **Herramienta esperada** | Al menos una de `herramientasEsperadas` aparece en `herramientas` de la respuesta. Informativa: no cuenta para "correcta" | — |
| **Latencia p50 / p90** | Tiempo de la consulta completa medido por el cliente (incluye todas las vueltas del modelo) | — |
| **Costo y caché** | Si la auditoría `ASISTENTE_CONSULTA` trae `tokensEntrada`, `tokensSalida` y `tokensCacheLeida` (los escribe `AgenteDelAsistente`), se suma con `--precio`. Si no, se **estima** y el resumen lo dice. La escritura de caché no se audita | — |

El proveedor compatible-OpenAI y Ollama se miden con el mismo set y se informan sin meta (§9).

## Costo estimado de una corrida completa con `claude-opus-5-5`

Precio: US$ 4 por millón de entrada, 20 de salida, 0,20 de caché leída. Supuesto por pregunta:
2–3 vueltas del modelo; por vuelta ~6.000 tokens de entrada (≈5.000 de prefijo cacheado) y ~400 de salida.

| Corrida | Preguntas | Llamadas al modelo | Con caché | Sin caché |
|---|---|---|---|---|
| `agente-preguntas.json` | 179 | ~450 | ≈ US$ 5,8 | ≈ US$ 14,3 |
| `agente-preguntas-control.json` | 44 | ~110 | ≈ US$ 1,4 | ≈ US$ 3,5 |
| `acciones.json` + `acciones-adversas.json` | 105 | ~260 | ≈ US$ 3,4 | ≈ US$ 8,4 |
| **Las cuatro** | **328** | **~820** | **≈ US$ 10,6** | **≈ US$ 26** |

Con 3 vueltas por pregunta, multiplicar por 1,2. Cada iteración de afinado que repita sólo el set de
control cuesta ≈ US$ 1,5–3,5.

## Corridas

_Ninguna todavía._
