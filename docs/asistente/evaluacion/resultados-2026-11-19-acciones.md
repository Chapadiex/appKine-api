# Interpretador de acciones — medición del 19/11/2026

`node evaluar-acciones.mjs qwen3:4b-instruct http://127.0.0.1:11434`, sobre `acciones.json` (45
frases: 35 acciones y 10 que no lo son). Ollama en el contenedor `akine-ollama` con la RTX 4050.
Mide **sólo el modelo**, con el prompt `prompt-acciones.txt` y el JSON schema de
`InterpretadorDeAcciones`, antes de `ControlDeAccion`.

| | Schema con sólo `accion` obligatoria | Schema con todos los campos obligatorios |
|---|---|---|
| Acción correcta | 44/45 | **44/45** |
| Completas (acción y campos) | 12/35 | **33/35** |
| No-acciones que quedan en `NINGUNA` | 9/10 | 9/10 |
| Latencia p50 / p95 | 648 / 897 ms | 1.426 / 1.610 ms |

**El schema decide más que el prompt.** Con sólo `accion` en `required`, el modelo de 4B devolvía la
acción correcta y omitía el monto, el nombre y el apellido en casi todos los pagos y altas. Con los
doce campos obligatorios (y anulables) los llena. Cuesta ~0,8 s más por frase, porque escribe el
objeto entero.

Las tres que fallan con el schema completo:

- **AS9** "tomá asistencia vinieron pedro sofia y martin": trae los tres nombres y `modo` en `null`.
  La tarjeta sale con todos sin marcar; no se graba nada equivocado.
- **AL5** "cargame a mariana ortiz celular 3516667788 documento 28999111": pierde el documento. La
  tarjeta lo muestra vacío.
- **NI10** "quienes faltaron a zumba esta semana": el modelo la toma por asistencia. No llega al
  modelo en producción: `EsPregunta` la manda antes al interpretador de consultas.

Ninguna falla inventa un dato: las tres pierden uno. PA8 ("quince mil") devuelve el monto en `null`,
que es lo esperado.

**No se volvieron a correr los sets de consultas** (`preguntas*.json`): el prompt de consultas y el
`Interpretador` no cambiaron, y con las acciones apagadas el recorrido es el de antes. Con las
acciones prendidas, una consulta escrita sin forma de pregunta ("turnos del viernes") pasa primero
por este interpretador, que la devuelve `NINGUNA` (NI1, NI9), y paga ~1,4 s más.

Sin medir: CPU sola (el interpretador de consultas tarda 7–9 s sin GPU; éste escribe más campos).
