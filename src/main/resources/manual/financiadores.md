---
id: financiadores
titulo: Financiadores
grupo: Contratación
rutas: ["/contratacion/financiadores", "/contratacion/financiadores/catalogo", "/contratacion/financiadores/:financiadorId/planes"]
permisos: [convenio:read]
servicio: KINESIOLOGIA
relacionadas: [convenios, arancel-efectivo, ficha-de-la-persona, presentaciones]
palabras: [financiador, obra social, prepaga, ART, mutual, plan, plan de cobertura, CUIT, catálogo de financiadores, copago]
---

## Para qué sirve

Es la lista de **con quién trabaja tu organización**: obras sociales, prepagas, ART, mutuales y
organismos. Cada financiador tiene **planes**, y contra un plan cada sede firma su **convenio**,
que es el que define los precios.

> [!importante]
> El orden es siempre el mismo: **financiador → plan → convenio de la sede → aranceles**. Sin un
> plan cargado no se puede firmar ningún convenio.

## Tareas

### Agregar un financiador desde el catálogo

![El catálogo de financiadores, con el buscador y el botón Agregar al centro de la primera fila marcados](captura:financiadores/agregar-desde-catalogo)

1. Entrá a :icono[contratacion]: **Contratación → Financiadores** y tocá **Agregar desde el
   catálogo**.
2. Buscá por **Nombre, código o CUIT** y tocá **Buscar**.
3. En la fila que corresponde, tocá **Agregar al centro**.

Se crea una **copia propia** con sus planes activos. Desde ahí es del centro y se edita como
cualquier otro; los cambios posteriores del catálogo no la modifican.

### Dar de alta un financiador a mano

![El panel Nuevo financiador, con el código, el nombre, el tipo y el botón Dar de alta marcados](captura:financiadores/nuevo-financiador)

1. Tocá :icono[agregar]: **Dar de alta un financiador**.
2. Completá el **Código**. No se puede cambiar después.
3. Completá el **Nombre**.
4. Elegí el **Tipo**: **Obra social**, **Prepaga**, **ART**, **Mutual**, **Organismo público** u
   **Otro**. Sólo sirve para clasificar y filtrar.
5. Tocá **Dar de alta**.

En **Más datos del financiador** podés cargar CUIT, email, teléfono y observaciones.

### Cargar un plan

1. En la fila del financiador, tocá **Planes**.
2. Tocá :icono[agregar]: **Dar de alta un plan**.
3. Completá **Código**, **Nombre** y **Vigente desde**. **Vigente hasta (opcional)** es el último
   día en que el plan se ofrece, inclusive.
4. Si corresponde, cargá el **Copago (opcional)** y los requisitos.
5. Tocá **Dar de alta**.

Un plan es **lo que se elige**: la cobertura de un paciente apunta a un plan.

### Dejar de ofrecer un plan

1. En **Planes**, tocá **Editar** en la fila del plan.
2. Poné la fecha de fin en **Vigente hasta** y guardá.

Así el plan queda activo y consultable, y se deshace volviendo a editar. **Dar de baja** es otra
cosa: exige motivo y no tiene vuelta atrás.

### Dar de baja un financiador

1. En la fila, tocá **Dar de baja**.
2. Escribí el **Motivo de la baja** y confirmá.

La baja **no cascadea**: sus planes, coberturas y convenios ya firmados siguen funcionando. Sólo
impide crear planes nuevos y elegirlo de nuevo. No hay reactivación.

## Qué significa cada estado

- **Activo**: se puede usar en coberturas y convenios nuevos.
- **Dado de baja**: queda a la vista con su motivo, pero no admite planes ni selecciones nuevas.
- **Ya agregado** (en el catálogo): el centro ya tiene su copia. Tocá **Ver la copia del centro**
  para abrirla.

Los planes muestran además su situación de vigencia: **Activo y vigente**, **Activo, todavía sin
vigencia**, **Activo, vigencia terminada** o **Dado de baja**.

## Problemas comunes

- **El código ya existe**: el código es único entre los financiadores vigentes. Buscalo en la
  lista; si estaba dado de baja, podés reusarlo en un alta nueva.
- **El catálogo dice que cambió después de agregarlo**: la copia del centro no se actualiza sola.
  Revisala y editá lo que haga falta.
- **No veo Agregar al centro**: podés mirar el catálogo, pero agregar necesita permiso para
  administrar convenios.
- **La búsqueda no filtra mientras escribo**: en esta pantalla se busca al presionar Enter.
