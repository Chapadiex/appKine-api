# Ficha de ingreso del paciente

Estado: **propuesta, esperando aprobación** (15/11/2026). Extiende DP-47 (datos declarados).

## Por qué

Al ingresar un paciente el centro hace siempre las mismas preguntas: a qué se dedica, cuánto pesa y
mide, si tuvo cirugías (dónde, cuándo, qué zona) y si tiene alguna enfermedad de las que importan para
kinesiología. Hoy eso entra como texto libre en antecedentes: no se puede buscar ("operados de
rodilla"), no hay IMC y cambiar el peso obliga a dar de baja y volver a cargar.

El usuario lo quiere **simple y compacto**, en la misma pantalla de Datos declarados, sin pasos ni
formularios largos.

## Objetivos

- Ficha corta con datos estructurados: ocupación, peso, altura (IMC calculado), actividad física.
- Lista corta de enfermedades para tildar.
- Cirugías con zona, año y lugar, una por línea.
- Ocupación elegida de una lista; si no está, se agrega en el momento y queda para la próxima.
- Todo lo cargado por recepción o administración queda **sin revisar** hasta que lo revisa un
  profesional (mismo circuito de DP-47).

## No objetivos

- Cuestionario configurable por centro.
- Historial de peso o talla en el tiempo (el valor vigente alcanza; el cambio queda auditado). La
  evolución medida sigue siendo una medición de la sesión.
- Reportes por ocupación (los datos quedan listos para eso, la pantalla no).

## Anti-objetivos

- Una pantalla larga con scroll, o un asistente de varios pasos.
- Cuarenta casillas "por si acaso".

## Enfoque elegido: B, backend liviano

Descartadas: **A**, sólo frontend con antecedentes de texto, porque no deja datos consultables; y
**C**, un cuestionario configurable, porque sobra.

### Datos

1. **`historia_clinica_ficha`**, una fila por HC (`organization_id`, `historia_clinica_id` unique):
   - `ocupacion_id` (FK a `ocupacion`, nullable).
   - `peso_kg` `NUMERIC(5,1)` (1–400) y `altura_cm` `NUMERIC(5,1)` (30–250), los dos nullable.
   - `actividad_fisica`: `SEDENTARIO | OCASIONAL | REGULAR | DEPORTISTA`, nullable, más
     `actividad_detalle` de hasta 120 caracteres.
   - `origen`, `cargado_por`, `revisado_por`, `revisado_en`, `version` y auditoría, como en V16.
   - **El IMC no se guarda**: se calcula al leer.
2. **`ocupacion`**, un catálogo con el patrón `owner_key` (ADR-0021):
   - Filas globales sembradas por la migración (unos 40 oficios típicos) y filas propias del centro.
   - Unique `(owner_key, nombre_normalizado)`, sin tildes ni mayúsculas, así "Docente" y "docente"
     no se duplican.
   - Baja lógica.
3. **`historia_clinica_antecedente`** suma tres columnas opcionales:
   - `zona` (120), `anio` (`SMALLINT`, 1900 al año actual) y `lugar` (160), pensadas para cirugías.
   - `codigo` (40), para las enfermedades de la lista. Unique parcial
     `(historia_clinica_id, codigo) WHERE active`.

### Lista de enfermedades (del producto, no del centro)

`DIABETES`, `HIPERTENSION`, `CARDIOPATIA`, `MARCAPASOS`, `OSTEOPOROSIS`, `ANTICOAGULADO`, `EMBARAZO`,
`CANCER`, `EPILEPSIA` y `RESPIRATORIA` (asma o EPOC).

- **Tildar** crea un antecedente `MEDICO` con ese `codigo`.
- **Destildar** lo da de baja, sólo si sigue sin revisar. Si ya se revisó, la casilla queda fija y el
  profesional lo maneja desde la HC.

### Reglas

- **Quién edita la ficha:** con `hc:declarar`, el dato queda con origen DECLARADO y sin revisar. Con
  `hc:write`, origen PROFESIONAL y revisado.
- **Si quien declara edita una ficha ya revisada,** vuelve a quedar sin revisar. La ficha cambia con
  el tiempo (el peso, por ejemplo), así que no se bloquea como un antecedente.
- **Agregar una ocupación** requiere `hc:declarar` o `hc:write`, y crea la fila en el catálogo del
  centro.
- Control optimista con `version`; una versión vieja responde 409 `concurrent-modification`.

### API (aditiva, contrato 0.173.0)

- `GET /personas/{id}/datos-declarados` suma `ficha`. La respuesta trae ocupación con su nombre, IMC
  y estado de revisión.
- `PUT /personas/{id}/datos-declarados/ficha` guarda la ficha (DECLARADO).
- `PUT /historias-clinicas/{id}/ficha` (`hc:write`) la guarda como PROFESIONAL.
- `POST /historias-clinicas/{id}/ficha/revision` la marca revisada.
- `GET /ocupaciones` devuelve todas las visibles (globales + del centro: es una lista chica y el
  buscador filtra en el navegador); `POST /ocupaciones` agrega una, o devuelve la existente si el
  nombre normalizado ya está.
- Antecedentes: `zona`, `anio`, `lugar` y `codigo` opcionales en el alta y en la respuesta.

### Pantalla

Va en Datos declarados, arriba de los dos paneles actuales, en una sola tarjeta de tres líneas:

```
Ocupación [Docente ▾]   Peso [72] kg   Altura [168] cm   IMC 25,5   Actividad [Regular ▾] [camina]
¿Tiene…? ☑ Diabetes ☐ Hipertensión ☐ Cardiopatía ☐ Marcapasos ☐ Osteoporosis
         ☐ Anticoagulado ☐ Embarazo ☐ Cáncer ☐ Epilepsia ☐ Asma/EPOC
```

- **Ocupación:** se escribe y aparecen sugerencias. Si no hay coincidencia, aparece la opción
  `Agregar «Kinesiólogo»`.
- **Guardado:** se guarda al salir del campo o al tildar, sin un botón "Guardar todo". Una marca
  chica indica "Guardado".
- **Antecedentes:** el panel actual suma la cirugía con `[Zona] [Año] [Dónde]` en la misma línea
  cuando el tipo es Quirúrgico.
- **En la HC (profesional):** la ficha aparece arriba, editable y con el botón "Revisado".
- **Diseño:** mismos tokens, claro y oscuro, a 375 px sin desborde y sin scroll de página en
  1440×900.

## Migración y versión

- **Choque detectado:** `main` ya tiene `V17` (analizador de evolución), y esta rama trae `V15`
  (servicio personalizado) y `V16` (datos declarados). Una base en `V17` rechazaría `V15` y `V16`.
- **Al preparar el PR:**
  - Se renumeran `V15` → `V18` y `V16` → `V19`.
  - La ficha toma `V20`.
  - El contrato pasa de `0.172.0` a `0.173.0`; se rebasea sobre el `0.170.0` de `main` y se confirma
    el número al rebasear.

## Lo que decide quien construye

- Usar el componente de autocompletado si existe en `shared`; si no, uno chico, accesible y con
  `role="combobox"`.
- La lista exacta de los ~40 oficios del seed.
- Tests: pocos. Un IT de la ficha (declarar, revisar, volver a sin revisar al editar) y uno del unique
  de ocupación normalizada.
