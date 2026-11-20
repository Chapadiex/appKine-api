---
id: sedes
titulo: Sedes
grupo: Configuración
rutas: ["/organizacion/sedes", "/organizacion/sedes/nueva", "/organizacion/sedes/:sedeId/editar"]
permisos: []
relacionadas: [organizacion, espacios-y-boxes, calendario-de-la-sede, horarios-de-profesionales, colaboradores]
palabras: [consultorio, sucursal, abrir sede, nueva sede, alta de sede, dar de baja sede, zona horaria, dirección, mapa, primer box]
---

## Para qué sirve

Lista los consultorios del centro. Cada **sede** tiene su propia zona horaria, su agenda y sus
turnos. Acá abrís una sede nueva, la editás o la das de baja.

> [!consejo]
> Para trabajar en otra sede no hace falta entrar acá: cambiala con el selector de sede de la barra
> superior.

## Tareas

### Abrir una sede nueva

![El primer paso del alta de sede, con el nombre y el botón Continuar marcados](captura:sedes/alta-datos)

1. Entrá a :icono[configuracion]: **Configuración → Sedes** y tocá :icono[agregar]: **Abrir una sede**.
2. En **Datos de la sede**, escribí el **Nombre de la sede**. Es lo único obligatorio. Si querés,
   ajustá **Duración del turno (min)**, el teléfono y el email.
3. Revisá los interruptores: **Habilitar la sede para agenda y turnos** y **Mostrar la dirección a
   los pacientes**.
4. Tocá **Continuar →**.

Después siguen tres pasos más, todos opcionales:

- **Ubicación**: completá la dirección y tocá **Ubicar en el mapa**. Si no la encuentra, arrastrá
  el marcador o elegí **Ninguna: la ubico a mano**.
- **Configuración inicial**: el **Horario de atención** (ningún turno se ofrece fuera de él), el
  **Primer box** y los **Datos fiscales**.
- **Confirmación**: revisá el resumen y tocá **Crear sede**.

### Revisar y confirmar el alta

![El paso Confirmación, con el resumen de la sede y el botón Crear sede marcados](captura:sedes/alta-confirmacion)

1. En el último paso, leé el resumen: sede, dirección, horario, box y datos fiscales.
2. Si algo está mal, tocá **Cambiar** al lado de ese dato. Vas a ese paso sin perder lo demás.
3. Tocá **Crear sede**. Si querés volver, tocá **← Anterior**.

Todo se puede cambiar después desde la edición de la sede.

### Editar una sede

1. En el listado, buscá la fila de la sede.
2. Tocá **Editar**.
3. Recorré los pasos y cambiá lo que necesites.
4. En el último paso, tocá **Guardar cambios**.

El horario se cambia en el calendario de la sede, y los boxes, en **Espacios y boxes**.

### Dar de baja una sede

1. En la fila de la sede, tocá :icono[mas]: y elegí **Dar de baja**.
2. Escribí el **Motivo de la baja**.
3. Tocá **Confirmar la baja**.

La sede no se borra: deja de admitir turnos y sesiones nuevas, y sigue en el listado con su motivo
y su fecha.

> [!importante]
> Si das de baja la sede en la que estás trabajando, vas a tener que elegir otra para seguir.

### Ver las sedes dadas de baja

1. Tocá :icono[filtro]: **Filtros**.
2. En **Mostrar**, elegí **Solo las dadas de baja** o **Todas**.

## Qué significa cada estado

- **Activa**: la sede opera y recibe turnos.
- **Dada de baja**: no admite operaciones nuevas. Lo ya registrado se conserva.

## Problemas comunes

- **No veo Abrir una sede ni Editar**: hace falta el permiso para administrar sedes. Pedíselo a
  quien administra el centro.
- **Al crear la sede aparece que llegaste al tope del plan**: tu plan no admite más sedes. Tocá
  **Ver mi plan y cambiarlo**.
- **El nombre ya existe**: tiene que ser distinto del de las otras sedes vigentes.
- **«La sede en la que estás trabajando ya no está activa»**: alguien la dio de baja. Tocá **Elegir
  otra sede**.
- **La agenda no ofrece turnos en la sede nueva**: revisá que esté prendido **Habilitar la sede
  para agenda y turnos** y que tenga horario de atención cargado.
