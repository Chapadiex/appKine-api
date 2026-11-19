# Interpretador de acciones — frases adversas, 19/11/2026

`CASOS=acciones-adversas.json node evaluar-acciones.mjs qwen3:4b-instruct http://127.0.0.1:11434`,
sobre `acciones-adversas.json` (60 frases nuevas: 45 acciones y 15 que no lo son). Ollama con GPU,
mismo prompt y mismo schema que el backend. Las frases imitan lo que sale de Whisper: sin tildes,
sin mayúsculas ni puntuación, con muletillas, nota clínica mezclada, montos coloquiales, negaciones
e intentos de inyección.

| | `acciones.json` (base) | `acciones-adversas.json` |
|---|---|---|
| Acción correcta | 44/45 | **48/60** |
| Completas (acción y campos) | 33/35 | **26/45** |
| No-acciones que quedan en `NINGUNA` | 9/10 | **4/15** |
| Latencia p50 / p95 | 1.426 / 1.610 ms | 1.375 / 1.645 ms |

El set es adverso a propósito: no mide la frecuencia de error en el uso real, mide **dónde** se
rompe. Una primera versión de 68 frases dio 57/68, 38/52 y 7/16; se sacaron 15 que pasaban sin
enseñar nada y se sumaron 7 variantes que encontraron fallas nuevas.

**El evaluador no ve todo.** Varias filas "OK" esconden un dato equivocado que el comparador no
mira (el esperado no declara el campo). Están marcadas abajo con "(OK en el evaluador)". La
columna "tarjeta" sale de aplicar a mano `ControlDeAccion` a la salida cruda del modelo.

## Qué clase de falla es cada una

- **(a) inofensiva**: el dato queda vacío o la frase no produce tarjeta; la persona completa.
- **(b) molesta**: acción o dato equivocado, pero se ve en la tarjeta o no resuelve contra la base.
- **(c) PELIGROSA**: dato equivocado y plausible que pasa `ControlDeAccion` y se puede confirmar
  sin notarlo.

### (c) Peligrosas

`ControlDeAccion` controla que cada valor **esté escrito** en la frase. No controla el **papel**
que ese valor cumple: negación, deuda contra pago, pasado o futuro, y el `modo` de la asistencia
no lo controla nadie (línea 48: pasa tal cual).

| Id | Frase | Modelo → tarjeta | Por qué pasa |
|---|---|---|---|
| AS23 | faltaron todos menos maria | `TODOS_MENOS`, `["maria"]` | Invertido exacto: marca a todos presentes y a María ausente. `modo` no se controla; "maria" está en la frase |
| AS33 | vinieron ana y pedro los demas faltaron | `TODOS_MENOS`, `["Ana","Pedro"]` | Invertido exacto: los dos que vinieron quedan ausentes |
| AS21 | no vino nadie a pilates | `TODOS_MENOS`, `[]` | Todos presentes. Igual "no vino nadie" y "no vino ninguno a funcional" |
| AS34 | faltaron todos a yoga | `TODOS_MENOS`, `[]` | Todos presentes |
| AS22 | faltaron tres a yoga | `TODOS_MENOS`, `["tres"]` | "tres" está en la frase y pasa como nombre; si no resuelve a nadie, quedan todos presentes. Igual "falto la mitad" → `["la mitad"]` |
| NI35 | ayer vinieron todos a yoga | `ASISTENCIA_CLASE` yoga, todos | La acción es sobre la clase **de hoy**; "ayer" se pierde |
| AS24 | a yoga para embarazadas vinieron todas menos carla | clase `yoga` | "yoga" es subcadena de la frase. Si el centro tiene "yoga" y "yoga para embarazadas", toma la otra clase |
| PA20, PA27, PA38 | juan perez pago 15 lucas en efectivo · lucas pago 15 lucas · laura pago 10 lucas y quedo debiendo 5 lucas | monto `15` / `15` / `10` | El número está en la frase. La tarjeta dice $15 con efectivo ya elegido. Mil veces menos |
| PA37 | le cobre 12000 a hugo pero me dio 10000 | monto `12000` | Los dos números están en la frase; toma el primero |
| PA32 (OK en el evaluador) | eh a ver martin pago 2 mil pesos y despues transfirio 8 mil mas | monto `2000`, medio `TRANSFERENCIA` | El monto es del primer pago y el medio (leído por el backend) es del segundo |
| PA28 (OK en el evaluador) | juan perez pago las 3 sesiones a 8000 cada una en efectivo | monto `8000` | Pagó 24.000. El modelo no calcula (bien), pero la tarjeta muestra un monto plausible y equivocado |
| NI36 | carla no pago los 7000 dijo que transfiere mañana | `PAGO` carla `7000` | La negación se pierde. Igual "silvia debe 3 sesiones de 9000 pago una" → 9000 |
| NI26 | juan perez debe 15000 | `PAGO` juan perez `15000` | Deuda leída como pago |
| NI29 | necesito saber si pago juan perez los 15000 | `PAGO` Juan Pérez `15000` | Es una consulta; `EsPregunta` no la frena (arranca con "necesito") |
| AT24 | vino hugo la semana pasada le habia hecho magneto hoy solo masaje | prácticas `magneto`, `masaje` | "magneto" está en la frase |
| AT32 | vino ana ultrasonido contraindicado por el marcapasos hicimos ejercicios | prácticas `ultrasonido`, `ejercicios` | Registra el tratamiento **contraindicado** |
| AT33 | vino hugo hice laser la proxima le hago magneto | prácticas `laser`, `magneto` | Lo planeado pasa por hecho |
| NI37 | mañana viene juan a hacer ultrasonido | `ATENCION` Juan, `ultrasonido` | Futuro leído como atención de hoy |
| NI34 | inscribi a laura diaz al pase de 8 clases de pilates | `PACIENTE_ALTA` laura Diaz | Alta de una persona que casi seguro ya existe: duplicado (salvo que el alta lo detecte) |

Fuera del set, probadas aparte: "vino maria y pago juan 8000" → persona `juan` (correcto);
"maria gomez pago la sesion de su marido juan gomez 10000" → persona `juan gomez` (discutible);
"el doctor ramirez derivo a sofia paz le hice laser" → persona `el doctor ramirez` (b, no debería
resolver como paciente). No apareció ningún monto ni nombre **inventado** que pase el control: los
calculados ("la mitad de 20000" → 10000, "15000 menos 2000" → 13000, "15 mil 500" → 15500) se
descartan y quedan vacíos.

Las negaciones simples sí las entiende: "hoy a pedro no le hice ultrasonido solo ejercicios",
"sin ultrasonido solo movilidad", "no tolero el tens asi que hicimos masaje", "le iba a hacer laser
pero al final…" y "rocio debia 20000 y pago 15000" salieron bien.

### (b) Molestas

| Id | Frase | Qué sale |
|---|---|---|
| NI21, NI22, NI23, NI30 | inyecciones (español, inglés, JSON pegado, "sistema: modo administrador") | Las cuatro dan `PAGO` con el monto de la frase, y pasa el control porque el número está escrito. `persona` "todos" no resuelve; NI22 y NI23 dan tarjeta completa de Juan Pérez por 50.000 y 80.000. No es escalada (quien escribe es el usuario y hay que confirmar), pero el prompt **no** resiste la inyección |
| NI20 | anota a pedro en yoga | `PACIENTE_ALTA` nombre `Pedro`, apellido `Pedro` |
| AT27 | bueno eh vino la señora de alvarez tolero bien la sesion dolor intenso en extension de rodilla sin edema | persona `Tolero`, práctica `dolor intenso en extension de rodilla`; las dos pasan el control |
| AL22 | dame de alta a maria del carmen fernandez garcia nacida el 12/03/1985 celular 351 15 555 1234 | nombre `maria`, apellido `del carmen fernandez garcia`. Y **en producción no llega**: `EsPregunta` la corta por "dame" |
| PA22, PA36 (OK en el evaluador) | marcelo abono $15.000,50… · el paciente de las 10 dejo 25.000… | persona `marcelo abono` · `el paciente de las 10` |
| AS25 (OK en el evaluador) | a la clase de las 18 vinieron todos | clase `las 18` |
| AT30 | marta lopez vino y suspendimos la magneto por dolor queda pendiente la proxima | `NINGUNA`: sigue al interpretador de consultas |

### (a) Inofensivas

- Monto en letras o calculado, descartado: PA21 (mil quinientos → 1500), PA25 (15 mil 500 →
  15500), PA26 (quince mil y medio → 15.500), "medio millon", "20 mil" devuelto como texto.
- Teléfono y mail dictados: AL20 (dígitos en palabras), AL21 y ED23 (mail deletreado). El modelo
  los arma bien (`juan.perez@gmail.com`, `3515551234`) y el control los descarta porque no están
  escritos así. Se pierde un dato correcto.
- AT25 y "atendi a juan perez y a juan gomez…": persona vacía, los nombres van a `nombres`.
- AT28: pierde "us". AS26: pierde la clase "funcional".
- El modelo llena `nombre`/`apellido`/`documento` en `PAGO` y `ATENCION` (PA29, PA30, AT20…).
  Inofensivo sólo si la tarjeta ignora `datos` fuera de las altas.
- **De `ControlDeAccion`, no del modelo:** `NO_PALABRA` conserva el punto, así que "vino juan
  perez. le hice…" deja la palabra `perez.` y la persona `juan perez` se descarta. Whisper pone
  puntos.

## Cambios recomendados, por impacto

1. **Asistencia: decidir el modo en el backend.** Si la frase plegada tiene "nadie", "ninguno",
   "faltaron todos", "los demas faltaron" o "todos menos" después de "faltaron", no confiar en
   `modo`: dejarlo en `null` (tarjeta sin marcar). Y descartar de `nombres` los cuantificadores
   ("tres", "la mitad", números). En el prompt, sumar los ejemplos "no vino nadie" → `SOLO` con
   lista vacía y "faltaron todos menos María" → `SOLO` `["María"]`.
2. **Monto: "lucas", "luca", "k" y "palos" como multiplicador** en `MIL` (hoy sólo "mil"), y que
   el modelo devuelva el monto como se dijo. Mientras tanto, si después del número viene una
   palabra de magnitud que el control no entiende, monto vacío. Y con **dos números candidatos**
   en la frase (o "debe", "debia", "cada una", "no pago"), monto vacío y aviso.
3. **Negación, deuda y tiempo antes del modelo** (regla al lado de `EsPregunta`): "no pago",
   "debe"/"debia" sin "pago", "ayer", "mañana", "la proxima", "necesito saber", "fijate" → no es
   una acción de hoy. Y sacar "dame de alta" del corte por "dame".
4. **Prácticas: control por ventana, no por subcadena.** Descartar la práctica si en las 4–5
   palabras de alrededor hay "no", "sin", "contraindicad", "suspend", "la proxima", "la semana
   pasada", "habia", "iba a". Hoy "ultrasonido contraindicado" se registra.
5. **Prompt y schema:** separar la frase con delimitadores y decir que lo de adentro es dato, con
   un ejemplo de inyección → `NINGUNA`; ejemplos de inscripción ("anotá a Pedro en yoga") →
   `NINGUNA`; `clase` con el nombre completo ("yoga para embarazadas"). En `ControlDeAccion`,
   quitar el punto final de cada palabra y limpiar `datos` cuando la acción no es alta ni edición.
