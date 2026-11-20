---
id: catalogo-clinico
titulo: Catálogo clínico
grupo: Servicios y catálogo
rutas: ["/catalogo", "/catalogo/:tipo", "/catalogo/mediciones", "/catalogo/nomencladores/:nomencladorId/vigencias"]
permisos: [catalogo:read]
relacionadas: [actividades-de-la-sede, convenios, arancel-efectivo, caso-clinico, atencion]
palabras: [especialidad, práctica, nomenclador, código, vigencia, medida, medición, examen físico, sentido de mejora, catálogo clínico]
---

## Para qué sirve

Es el vocabulario clínico del centro, en cuatro pestañas:

- **Especialidades**: la disciplina bajo la que se atiende (kinesiología, fonoaudiología,
  nutrición). De ellas cuelgan las prácticas.
- **Prácticas**: lo que efectivamente se hace en una sesión. Es lo que los financiadores
  reconocen y pagan.
- **Nomencladores**: el listado de códigos con el que se le factura a un financiador. Los códigos
  viven en sus **vigencias**.
- **Mediciones**: las medidas del examen físico, con su unidad y rango.

La columna **De quién es** separa lo **De la plataforma** (lo mantiene AKINE, no se edita) de lo
**De este centro** (lo administrás vos desde la misma tabla).

## Tareas

### Dar de alta una práctica del centro

![El panel Nueva práctica de este centro, con el código, el nombre, la especialidad y el botón Dar de alta marcados](captura:catalogo-clinico/nueva-practica)

1. Entrá a :icono[servicios]: **Servicios y catálogo → Catálogo clínico**, pestaña **Prácticas**, y
   tocá :icono[agregar]: **Dar de alta una práctica de este centro**.
2. Escribí el **Código**. Es la clave estable: elegilo pensando que no se renombra.
3. Escribí el **Nombre**.
4. Elegí la **Especialidad** de la que cuelga. Solo aparecen las activas.
5. Tocá **Dar de alta**.

Con **Vigente desde (opcional)** en una fecha futura, la práctica queda activa pero no aparece en
los selectores hasta ese día. Lo mismo sirve para especialidades y nomencladores.

### Editar o dar de baja un concepto del centro

1. En la fila, tocá **Editar** para cambiar el nombre, la descripción o la vigencia, y tocá
   **Guardar cambios**. El código no se edita.
2. Para darlo de baja, tocá **Dar de baja**, escribí el **Motivo de la baja** y confirmá.

> [!importante]
> La baja no se deshace. Lo que ya se registró con ese concepto conserva su significado; si vuelve
> a hacer falta, se da de alta uno nuevo. Su código y su nombre quedan libres.

### Cargar una vigencia de un nomenclador

1. En la pestaña **Nomencladores**, tocá **Ver vigencias** en la fila del nomenclador.
2. Tocá :icono[agregar]: **Cargar una vigencia**.
3. Completá el **Código**, la **Práctica que codifica** y el **Nombre** con el que la nombra el
   financiador.
4. Si querés, cargá el **Valor de referencia (opcional)** y las fechas.
5. Tocá **Cargar la vigencia**.

Una vigencia **no se corrige**: se da de baja la que ya no rige y se carga otra. Así lo que ya se
presentó a un financiador sigue resolviendo con su código y su valor de entonces.

### Dar de alta una medida del examen físico

1. En la pestaña **Mediciones**, tocá :icono[agregar]: **Dar de alta una medida de este centro**.
2. Completá **Código**, **Nombre** y **Tipo de valor**. El tipo no se cambia después.
3. Si es numérica o de escala, completá la **Unidad** y, si querés, **Mínimo** y **Máximo**.
4. Elegí el **Sentido de mejora**: **Mejora cuando sube** (un rango de movimiento) o **Mejora cuando
   baja** (el dolor).
5. Tocá **Guardar**.

Con el sentido de mejora cargado, la evolución del caso avisa cuando una medida va en contra. Sin
él, la serie se muestra pero no se califica.

## Qué significa cada estado

- **Activo y vigente**: aparece en los selectores.
- **Activo, sin vigencia hoy**: está cargado pero hoy queda fuera de sus fechas; no aparece en los
  selectores.
- **Dado de baja**: queda como historia, con su motivo.

En las vigencias de un nomenclador: **Rige hoy**, **Activa, fuera de ventana** y **Dada de baja**.

## Problemas comunes

- **«Ese código ya está en uso»** o **«Ese nombre ya está en uso»**: ya hay un concepto activo con
  ese dato. Buscalo con **Mostrar → Todos**.
- **Una fila dice «La mantiene AKINE.»**: es de la plataforma. Se usa igual, pero no se edita ni se
  da de baja desde tu centro.
- **«Se superpone con otra vigencia del mismo código»**: dos vigencias del mismo código no pueden
  pisarse. Dale de baja a la anterior o elegí fechas que no se crucen.
- **No veo el botón de alta**: tu cuenta solo puede consultar. Pedí permiso para administrar el
  catálogo a quien administra el centro.
