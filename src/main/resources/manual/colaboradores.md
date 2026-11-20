---
id: colaboradores
titulo: Colaboradores
grupo: Configuración
rutas: ["/organizacion/colaboradores", "/organizacion/colaboradores/nuevo", "/organizacion/colaboradores/manual/nuevo", "/organizacion/colaboradores/invitaciones", "/organizacion/colaboradores/notificaciones"]
permisos: [colaborador:read]
relacionadas: [organizacion, sedes, horarios-de-profesionales, mi-perfil]
palabras: [equipo, personal, profesional, invitar, invitación, vincular, rol, permisos, alcance, revocar, suspender, usuario]
---

## Para qué sirve

Muestra quién trabaja en el centro, con qué **rol** y sobre qué **alcance** (toda la organización
o una sede). Desde acá sumás gente al equipo, cambiás su rol y le das o le sacás acceso.

Hay tres formas de sumar a alguien:

- **Invitar**: le llega un correo con un enlace. No hace falta que tenga cuenta en AKINE.
- **Vincular colaborador**: para alguien que **ya** tiene cuenta. Queda vinculado en el acto.
- **Alta manual**: cargás sus datos personales y profesionales, y le llega la invitación al final.

## Tareas

### Invitar a alguien al equipo

![El panel Nueva invitación, con el email, el rol, el alcance y el botón de envío marcados](captura:colaboradores/invitar)

1. Entrá a :icono[configuracion]: **Configuración → Colaboradores** y tocá la pestaña
   **Invitaciones**. Ahí tocá :icono[agregar]: **Invitar a alguien**.
2. Escribí el **Email de la persona**.
3. Elegí el **Rol con el que entraria**.
4. En **Donde trabajaria**, elegí la sede activa o **En toda la organización**.
5. Tocá **Enviar la invitacion**.

El vínculo nace cuando la persona **acepta**, no cuando invitás.

> [!importante]
> Invitar no ocupa un lugar de tu plan, pero aceptar sí. Si te queda un solo lugar y mandás cinco
> invitaciones, entra la primera que responda.

### Vincular a alguien que ya tiene cuenta

![El formulario Vincular colaborador, con el email, el rol, el alcance, el motivo y el botón Vincular marcados](captura:colaboradores/vincular)

1. En **Colaboradores**, tocá :icono[agregar]: **Vincular colaborador**.
2. Escribí el **Email de la cuenta**, exactamente el que la persona usó al registrarse.
3. Elegí el **Rol**.
4. En **Alcance**, elegí **Toda la organización** o **Una sede en particular** (y cuál).
5. Escribí el **Motivo**. Queda en la auditoría.
6. Tocá **Vincular**.

No se manda ningún correo: la persona ya puede trabajar en el centro.

### Dar de alta a un colaborador con todos sus datos

1. En **Colaboradores**, tocá **Alta manual**.
2. En **Tipo y persona**, elegí el **Tipo de colaborador** y completá nombre, apellido, documento y
   el **Email para la invitación**. Si es profesional, sumá profesión, matrícula y especialidades.
3. Tocá **Siguiente →**.
4. En **Función y sedes**, elegí las **Sedes de trabajo** y marcá si está **Previsto para recibir
   turnos** o como instructor de actividades. Tocá **Siguiente →**.
5. En **Revisión y acceso**, revisá y tocá **Guardar y enviar invitación**.

### Cambiar el rol o el alcance de alguien

1. En la tabla, buscá a la persona.
2. Tocá **Cambiar rol o alcance**.
3. Elegí el nuevo **Rol** y, si corresponde, el nuevo **Alcance**. Lo que no quieras tocar, dejalo en
   **No cambiar**.
4. Escribí el **Motivo** y tocá **Aplicar el cambio**.

### Dar un permiso adicional

1. En la fila de la persona, tocá :icono[mas]: y elegí **Permisos adicionales**.
2. En **Otorgar un permiso**, elegí el **Permiso a otorgar**. Solo aparecen los que su rol admite.
3. Escribí el **Motivo** y, si querés, la fecha en que **Vence el** permiso.
4. Tocá **Otorgar**.

Para sacarlo, tocá **Dar de baja** al lado del permiso, en **Permisos vigentes**.

### Suspender o revocar el acceso

1. En la fila de la persona, tocá :icono[mas]: y elegí **Suspender** o **Revocar**.
2. Escribí el motivo.
3. Tocá **Confirmar**.

**Suspender** se puede deshacer con **Reactivar**. **Revocar** no: es definitivo.

> [!importante]
> Antes de revocar, el panel muestra lo que queda pendiente a su nombre, como turnos futuros.
> Revocar no los cancela ni los reasigna: quedan para que alguien los resuelva.

## Qué significa cada estado

- **Activa**: la persona puede trabajar con su rol y su alcance.
- **Suspendida**: el acceso está en pausa. Se puede reactivar.
- **Revocada**: el acceso terminó y no se puede deshacer. La fila queda con quién la revocó y por qué.
- **Pendiente** (invitación): todavía no respondió. Si dice **Pendiente, con el enlace vencido**,
  tocá **Reenviar**: es la misma invitación con un enlace nuevo.
- **Aceptada**, **Rechazada** o **Cancelada** (invitación): la persona aceptó, la rechazó, o alguien
  del centro la canceló.

## Problemas comunes

- **«Esta cuenta ya tiene un vínculo con la organización»**: la persona ya está en el listado. Si
  está suspendida, reactivala desde ahí en vez de vincularla de nuevo.
- **Al vincular dice que la cuenta no existe**: la persona todavía no se registró en AKINE. Mandale
  una invitación.
- **No le llegó el correo de invitación**: mirá la pestaña **Notificaciones**. Si el envío falló,
  tocá **Reintentar**.
- **No veo las pestañas Invitaciones ni Notificaciones, ni los botones para sumar gente**: hace
  falta el permiso para administrar colaboradores.
- **Le di un permiso y no le funciona**: si dice **No vigente con el rol actual**, el rol de la
  persona no admite ese permiso. Podés darlo de baja.
