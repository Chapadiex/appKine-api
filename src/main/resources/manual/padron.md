---
id: padron
titulo: Padrón de personas
grupo: Pacientes
rutas: ["/pacientes"]
permisos: [paciente:read]
relacionadas: [ficha-de-la-persona, historias-clinicas, agenda, caja-diaria, glosario]
palabras: [pacientes, padrón, alta de paciente, nueva persona, buscar paciente, DNI, perfil de paciente, editar datos, persona duplicada, listado de pacientes]
---

## Para qué sirve

Es la lista de **todas las personas registradas en el centro**. Acá buscás a alguien, lo das de
alta, corregís sus datos y le activás el perfil de paciente.

> [!importante]
> Una **persona no es lo mismo que un paciente**. La persona es la ficha administrativa (quién es y
> cómo contactarla) y es del centro, no de una sede. **Paciente** es un perfil que se activa aparte,
> sobre esa misma persona, cuando empieza una atención clínica. Alguien puede venir a una clase sin
> ser paciente.

## Tareas

### Buscar a una persona

![El buscador del padrón y el resultado de la búsqueda marcados](captura:padron/buscar-persona)

1. Entrá a :icono[pacientes]: **Pacientes → Padrón**.
2. En el buscador :icono[buscar]:, escribí el documento, el apellido, el nombre, el teléfono o el
   número de afiliado, sin puntos ni guiones, y apretá Enter.
3. Mirá la tabla: cada fila muestra a la persona, su contacto, si es **Paciente**, su **Historia
   clínica** y su **Estado**.

Sin texto, se lista el padrón completo ordenado por apellido. Para ver también las fichas dadas de
baja, abrí :icono[filtro]: **Filtros** y en **Estado de la ficha** elegí **Dadas de baja** o
**Todas**.

> [!consejo]
> Buscá siempre antes de dar de alta. Dos fichas de la misma persona dejan su historia partida en
> dos.

### Dar de alta una persona

![El panel Nueva persona, con el apellido, el nombre y el botón Dar de alta marcados](captura:padron/dar-de-alta)

1. Tocá :icono[agregar]: **Dar de alta una persona**, arriba a la derecha.
2. Completá **Apellido** y **Nombre**, que son los únicos obligatorios.
3. Si tiene documento, elegí el **Tipo de documento** y escribí el **Número de documento**. Van
   juntos.
4. Tocá **Dar de alta**.

El documento es opcional: una persona sin DNI (un menor, alguien recién llegado) es un caso normal.
No inventes un número para completar el campo. El **Correo de contacto** es sólo un dato para
comunicarse, no una clave para entrar al sistema.

> [!importante]
> Dar de alta registra una **persona**, no un paciente: no crea perfil clínico ni historia clínica.
> En **Observaciones administrativas** no va nada clínico.

### Activar el perfil de paciente

1. En la fila de la persona, abrí el menú :icono[mas]: de acciones.
2. Tocá **Activar perfil de paciente**.
3. Si querés, escribí un **Motivo (opcional)**.
4. Tocá **Activar perfil**.

Se reutiliza la ficha que ya existe: no se vuelve a cargar ningún dato. Activar el perfil **no crea
historia clínica**; esa se abre con la primera sesión o desde la columna **Historia clínica**.

### Editar los datos de una persona

1. En la fila de la persona, abrí el menú :icono[mas]: de acciones y tocá **Editar**.
2. Corregí lo que haga falta en el panel lateral.
3. Tocá **Guardar cambios**.

Una ficha dada de baja no se edita: se consulta desde **Ver ficha**.

### Entrar a la ficha o a la historia clínica

1. Buscá a la persona.
2. Para ver sus datos administrativos, coberturas, documentos y turnos, tocá **Ver ficha**.
3. Si tiene historia y tenés permiso para verla, tocá **Ver HC**. Si es paciente y todavía no tiene
   historia, puede aparecer **Abrir HC**.

Si no podés leer historias clínicas pero cargás lo que trae el paciente, en esa columna vas a ver
**Cargar datos**: lo que cargues queda sin revisar hasta que lo vea un profesional.

## Qué significa cada estado

- **Activa**: la ficha está vigente y admite operaciones nuevas.
- **Dada de baja**: la ficha no admite operaciones nuevas, pero todo lo que tenía se sigue
  consultando.
- Columna **Paciente** con tilde: la persona tiene perfil de paciente vigente. Con una raya, no.
- **Tiene historia**: tiene historia clínica, pero no tenés permiso para abrirla.
- **Sin historia**: todavía no se abrió su historia clínica.

## Problemas comunes

- **«Este documento ya está registrado»**: ese documento pertenece a otra ficha. No se puede
  repetir: tocá **Ver persona** para ir a la que ya existe.
- **«Ya hay personas parecidas registradas»**: el sistema encontró fichas con datos parecidos y no
  creó nada. Revisá la lista; si de verdad es otra persona, tocá **Es otra persona: darla de alta
  igual**. Si no, **Volver a revisar los datos**.
- **«No hay personas que coincidan con la búsqueda»**: probá con el documento sin puntos o con parte
  del apellido, o tocá **Limpiar filtros**.
- **No veo el botón Dar de alta una persona**: hace falta el permiso para gestionar pacientes.
  Pedíselo a quien administra el centro.
- **«No pudimos leer el padrón»** con **Elegir contexto de trabajo**: todavía no elegiste centro y
  sede. Elegilos y volvé a entrar.
