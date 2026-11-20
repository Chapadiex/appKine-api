---
id: biblioteca
titulo: Biblioteca
grupo: Configuración
rutas: ["/biblioteca", "/biblioteca/:libroId"]
permisos: [biblioteca:manage]
funcionIa: BIBLIOTECA
relacionadas: [consultar-biblioteca, caso-clinico, organizacion]
palabras: [libros, pdf, subir libro, material, bibliografía, indexar, procesar, reemplazar archivo, búsqueda]
---

## Para qué sirve

Acá cargás los **libros en PDF** del centro. El sistema los lee, los divide en pedazos y los usa
para responder en **Consultar biblioteca** y en las sugerencias de evolución, siempre citando de
dónde sacó cada respuesta.

> [!consejo]
> Si la biblioteca no aparece en el menú, puede estar apagada. Se prende en **Organización →
> Inteligencia artificial**.

## Tareas

### Subir un libro

![El panel Subir libro, con el archivo, el título, la declaración y el botón Subir marcados](captura:biblioteca/subir-libro)

1. Entrá a :icono[configuracion]: **Configuración → Biblioteca** y tocá **Subir libro**.
2. En **Archivo PDF**, elegí el archivo.
3. Escribí el **Título**. Si querés, completá **Autores**, **Edición** y **Área**.
4. Marcá **Declaro que el centro tiene derecho a usar este material**.
5. Tocá **Subir**.

El libro aparece **En cola** y después **Procesando**. Cuando dice **Listo**, ya se puede consultar.

### Probar qué encuentra la búsqueda

1. Tocá **Probar búsqueda**.
2. Escribí una pregunta o un tema y mirá qué pedazos devuelve y de qué libro.

Sirve para saber si el material cargado cubre un tema antes de usarlo en la atención.

### Reemplazar el archivo de un libro

1. En la fila del libro, tocá :icono[mas]: y elegí **Reemplazar archivo**.
2. Elegí el PDF nuevo.
3. Tocá **Reemplazar**.

El libro anterior sigue disponible hasta que el nuevo termine de procesarse.

### Desactivar o eliminar un libro

1. En la fila del libro, tocá :icono[mas]:.
2. Elegí **Desactivar** para sacarlo de la búsqueda sin borrarlo. Lo volvés a usar con **Activar**.
3. O elegí **Eliminar** y confirmá con **Eliminar libro**.

> [!importante]
> Eliminar borra el archivo y sus pedazos, y no se puede deshacer. Si dudás, desactivalo.

### Corregir los datos de un libro

1. Tocá el título del libro o **Ver detalle**.
2. Tocá **Editar datos**.
3. Cambiá **Título**, **Autores**, **Edición** o **Área** y tocá **Guardar**.

En el detalle también ves cuántos pedazos se indexaron y cuáles quedaron afuera por sospechosos.

## Qué significa cada estado

- **En cola**: el libro se subió y espera su turno para procesarse.
- **Procesando**: el sistema lo está leyendo. Muestra el avance.
- **Listo**: se puede consultar.
- **Con error**: el procesamiento falló. La fila dice el motivo; probá **Reprocesar**.
- **Inactivo**: está guardado pero fuera de la búsqueda.

## Problemas comunes

- **«La biblioteca no está habilitada»**: la instalación no la ofrece. La activa quien administra
  la plataforma, no se prende desde acá.
- **Al subir dice que el archivo ya existe**: ese PDF ya está cargado. Tocá **Ver el libro**.
- **Un libro quedó Con error**: suele pasar con PDF escaneados (solo imágenes) o dañados. Probá
  **Reprocesar** o subí otra copia.
- **Algunos pedazos no aparecen en la búsqueda**: el sistema descarta los que parecen
  instrucciones dirigidas a él. Es una protección, no un error.
