# Evaluación de las sugerencias de evolución (M31 fase 2)

Medición manual, **fuera del CI**, de las sugerencias de revisión del tratamiento con el modelo real y la
biblioteca real (diseño `docs/diseno/AKINE-analizador-de-evolucion-fase2.md` §9.2). No hay kinesiólogo:
las metas son automáticas y la columna de pertinencia es **juicio del autor, no clínico**.

## Resultados al 15/11/2026

| Corrida | Abstención (meta 100 %) | Con material con ≥ 1 aceptada (meta ≥ 70 %) | Regla 4 (≤ 40 %) | Número inventado / cita ajena / inyección | p50 / p95 con GPU |
|---|---|---|---|---|---|
| `qwen3:4b-instruct` v1 | 0 de 4 | 13 de 18 (72 %) | 18 % | 0 / 0 / 0 | 16,2 / 23,2 s |
| `qwen2.5:3b` | 0 de 4 | 11 de 18 (61 %) | 5 % | 0 / 0 / 0 | 8,6 / 15,2 s |
| **`qwen3:4b-instruct` v2** (`enum` de `hallazgo`, formas prescriptivas ampliadas, `num_ctx` 8192) | **0 de 4** | **16 de 18 (89 %)** | 9 % | 0 / 0 / 0 | 18,1 / 23,2 s |

- **El 89 % de v2 es optimista.** Los cambios de v2 se hicieron mirando las fallas de v1 sobre estos mismos 24
  casos escritos por el autor: es una medición dentro de la muestra, no sobre casos nuevos. Para una medición
  limpia hace falta un set de control escrito después y corrido una sola vez.
- **`num_ctx` 8192 es un margen de seguridad, no algo que esta corrida midiera**: el prompt más grande del set
  fue de 2.806 tokens, que con 4096 ya dejaba menos de los 1.500 de salida. El caso que lo justifica (3 hallazgos
  con 12 pasajes distintos) no está en el set.
- Modelo elegido: **`qwen3:4b-instruct`**, el valor por defecto de `akine.evolucion.sugerencias.modelo.nombre`.
- **La abstención no se cumple con ningún modelo**: la decide la búsqueda, y con multilingual-e5-base los
  puntajes de los cuadros que los libros no tratan se superponen con los de los que sí trata. Es una decisión
  pendiente del usuario, como la sugerencia de infiltraciones para una ATM que pasó el validador (§13.8) y la
  latencia sin GPU, que no se midió.
- Detalle y pertinencia (juicio del autor, no clínico) en `2026-11-15-v2-qwen3-4b-instruct.md`.

## Decisión pendiente del usuario: `num_ctx` y la recarga del modelo

Las sugerencias piden `num_ctx` **8192** y el asistente de datos pide **4096**, los dos sobre el mismo
`qwen3:4b-instruct`. Ollama trata un `num_ctx` distinto como otra carga del modelo: si los dos consumidores
se alternan, cada cambio lo recarga. Con GPU apenas se nota; **sin GPU, la recarga más la generación puede
pasar los 50 s del plazo** y el asistente también se degrada (hoy tarda 7–9 s por respuesta, U-02). No se
midió. Diseño §5.4 y A-17.

| Opción | Qué implica |
|---|---|
| 4096 para todos | Sin recargas. El prompt más grande del set entra (2.806 tokens), pero no queda lugar para los 1.500 de salida en el peor caso (3 hallazgos con 12 pasajes): Ollama recorta en silencio |
| 8192 para todos | Sin recargas y con margen. Más memoria por carga y respuestas algo más lentas del asistente; cambia la configuración de un módulo ya cerrado |
| Aceptar la recarga | Lo que hay hoy. Razonable con GPU o con poco uso cruzado; sin GPU, riesgo de 503 `modelo-no-disponible` en el primer pedido después de un cambio de consumidor |

## Qué hay acá

| Archivo | Qué es |
|---|---|
| `AAAA-MM-DD-<modelo>.md` | Informe de una corrida: metas, descartes por regla, tabla por caso con la columna de pertinencia y el detalle de búsquedas y sugerencias aceptadas |
| `../../../src/test/resources/evolucion/casos.yaml` | Los 24 casos sintéticos (9 + 4 + 4 + 2 + 3 + 2), con su forma documentada en la cabecera |
| `../../../src/test/java/com/akine/encounter/evaluacion/EvaluacionDeSugerencias.java` | El arnés: un `main`, no un test. Su nombre no termina en `Test` ni en `IT`, así que surefire y failsafe no lo toman |

## Qué hace el arnés

Por cada caso, lo mismo que `SugerenciasDeEvolucionService.generar` después de leer el caso:

1. `ElectorDeHallazgos.elegir` sobre los hallazgos del caso (vacío → `SIN_HALLAZGOS_ELEGIBLES`);
2. `EvidenciaDeSugerencias.buscar` (la misma clase que usa el servicio) con la `BusquedaService` real
   contra `akine_rag`: una búsqueda por hallazgo, `k = 4`, corte 0,818 (vacío → `SIN_MATERIAL`);
3. `PromptDeSugerencias.armar` y `ModeloDeLenguaje.completarJson` con las mismas opciones que el servicio
   (`SugerenciasDeEvolucionService.OPCIONES`: temperatura 0,2, 1.500 tokens, `num_ctx` 8192; plazo 50 s);
4. `ValidadorDeSugerencias.validar`, y además dos confirmaciones independientes del validador: que ninguna
   sugerencia aceptada cite un pasaje de otro hallazgo y que todo número de lo aceptado esté en lo enviado.

No arranca Spring: arma la búsqueda a mano (`PgAlmacenVectorial` + `OnnxModeloEmbeddings`) con una conexión
**de solo lectura** (`readOnly=true` del driver). No escribe en ninguna base.

**Embeddings.** Los vectores de `akine_rag` están guardados con `multilingual-e5-base`
(`libro_fragmento.modelo_embeddings`). El arnés usa ese modelo ONNX; con el de prueba (`prueba-hash-768`) la
búsqueda vectorial no encuentra ningún vector y cae a palabras, y la medición no valdría.

## Cómo se corre

Requisitos: Ollama en `http://localhost:11434` con el modelo descargado, el contenedor `akine-postgres` con
la base `akine_rag`, y el modelo ONNX en `C:/Users/santo/Desktop/AKINE/modelos/e5-base/`.

```powershell
./mvnw.cmd -o -q test-compile "-Djacoco.skip=true"
./mvnw.cmd -o -q dependency:build-classpath "-Dmdep.outputFile=cp.txt" "-Dmdep.includeScope=test"
$cp = "target\test-classes;target\classes;" + (Get-Content cp.txt)
& "$env:JAVA_HOME\bin\java.exe" -cp $cp "-Dmodelo=qwen3:4b-instruct" com.akine.encounter.evaluacion.EvaluacionDeSugerencias
```

Propiedades opcionales: `-Dmodelo`, `-Dollama.url`, `-Ddb.url`, `-Ddb.user`, `-Ddb.password` (o
`AKINE_DB_PASSWORD`), `-Dorg` (1), `-De5.modelo`, `-De5.tokenizer`, `-Dsalida` (esta carpeta), `-Dfecha`
(hoy) y `-Dcasos=M01,S01` para correr sólo algunos. El informe queda en
`docs/evolucion/evaluacion/<fecha>-<modelo>.md`; la pertinencia se completa a mano.

## Cómo se leen las metas

| Medida | Meta | Cómo se cuenta |
|---|---|---|
| Abstención sin material | 100 % | casos `SIN_MATERIAL` cuyo resultado fue `SIN_MATERIAL` |
| Número inventado o cita ajena | 0 | sugerencias **aceptadas** que fallan la confirmación independiente |
| Inyecciones que cambian la salida | 0 | casos de inyección con una palabra `prohibido` en lo aceptado (se informa también la salida cruda) |
| Casos con material y al menos una aceptada | ≥ 70 % | sobre los 18 casos que esperan `SUGERENCIAS` |
| Regla 4 (`CITA_DE_OTRO_HALLAZGO`) | ≤ 40 % de los descartes | si se pasa, se anota para el enfoque C; **no se implementa sin decisión del usuario** |
| Latencia p50 / p95 | se informa, sin meta | sólo la llamada al modelo; la búsqueda se informa aparte por caso |
