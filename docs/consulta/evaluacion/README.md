# Evaluación de la consulta a la biblioteca

Set de consultas cortas, al estilo de un profesional, sobre los módulos de rehabilitación de la OMS
cargados en la base local `akine_rag` (libros 3 a 6 = M2 a M5), y un script que lo corre contra un
backend vivo y escribe una tabla Markdown. Sirve para medir v1 (línea base, `resultados-v1.md`) y
cada cambio de la consulta v2 (`docs/diseno/AKINE-biblioteca-consulta-v2.md`) con la misma vara.

## Cómo correrlo

Con la pila de evaluación arriba (`akine-api-rag-onnx` en 8098 y Ollama `akine-ollama`):

```bash
node docs/consulta/evaluacion/medir.mjs --base http://localhost:8098 --salida docs/consulta/evaluacion/resultados-vN.md
```

- Node 24, sin dependencias.
- La cuenta se lee de la fila `admin.rag@` de `AKINE/.claude/rag-cuentas.md` (tabla `| rol | correo | contraseña |`);
  otra ruta con `--cuentas <archivo>`. La contraseña no se imprime ni se escribe en la salida.
- `--solo C01,A02` corre sólo esos casos; `--consultas <archivo>` usa otro set.
- Son ~40 consultas en serie: unos minutos.

## Qué mide

Por caso: el tipo de salida (`texto`, `aclaracion`, `sin_material` o `error`), las fuentes con sus páginas,
los ms hasta el primer evento, hasta la primera palabra (el primer `texto` no vacío o la `aclaracion`) y
el total. Agregados:

- **Recall@5 de páginas**, sobre los casos `responder`: alguna página esperada, ±1, cae dentro de alguna de
  las cinco primeras fuentes del libro esperado, y la salida fue `texto`. Un "sin material" cuenta como
  fallo de recall aunque haya mandado fuentes.
- **Acierto de "repreguntar"**: casos `repreguntar` que terminaron en `aclaracion`. Sin v2 (sin eventos
  `aclaracion`/`etapa`) toda respuesta cuenta como "no repreguntó". También se informa cuántas claras **no**
  repreguntaron.
- **Acierto de "sin material"**: temas ausentes que respondieron sin material, y los "sin material" falsos
  en casos `responder`.
- Percentiles 50 y 95 de primer evento, primera palabra (de las respuestas con texto), repregunta y total.

## El set (`consultas.json`)

Cada caso es `{ id, consulta, historial: [{rol, texto}], esperado, libro, paginas, nota }`:

| Prefijo | Categoría | Esperado |
|---|---|---|
| `C` | Clara y corta, con tema e intención | `responder` |
| `A` | Ambigua: un tema suelto, o una intención sin afección ("ejercicios") | `repreguntar` |
| `H` | Repregunta con historial: hereda el tema de la charla, o lo cambia (y entonces repregunta) | según el caso |
| `P` | Profundizar sobre la respuesta anterior | `responder` |
| `X` | Tema que no está en ningún libro | `sin_material` |

- `paginas` son **páginas del PDF** (`libro_fragmento.pagina_desde/hasta`), no las impresas: en estos
  módulos la impresa N es la del PDF N+5 o N+6. Van vacías en `repreguntar` y `sin_material`.
- `libro` es el código del título (`M2`…`M5`); el script lo compara contra el `titulo` de cada fuente.
- El historial del asistente es texto sintético corto escrito a partir del contenido real.

### Agregar un caso

1. Buscar el texto en la base, por ejemplo:
   ```bash
   docker exec akine-mysql mysql -uakine -pakine akine_rag --default-character-set=utf8mb4 -N -e \
     "SELECT pagina_desde, pagina_hasta, LEFT(REPLACE(texto,'\n',' '),300) FROM libro_fragmento
      WHERE libro_id=3 AND texto LIKE '%fortalecimiento%' ORDER BY orden"
   ```
2. Poner como `paginas` las de la sección **de contenido** que responde (resumen y filas de la tabla de
   recursos), nunca derechos de autor, índice, miembros de los grupos ni referencias.
3. Para un tema ausente, confirmar con la misma consulta que la palabra no aparece en ningún libro (3 a 6) y
   dejarlo escrito en `nota`.
4. Usar un `id` nuevo con el prefijo de su categoría.
