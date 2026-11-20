---
id: ficha-de-la-persona
titulo: Ficha de la persona
grupo: Pacientes
rutas: ["/pacientes/:personaId", "/pacientes/:personaId/documentos", "/pacientes/:personaId/coberturas", "/pacientes/:personaId/autorizaciones", "/pacientes/:personaId/datos-declarados"]
permisos: [paciente:read]
relacionadas: [padron, historias-clinicas, agenda, convenios, glosario]
palabras: [ficha del paciente, 360, datos del paciente, coberturas, obra social, documentos, próximos turnos, dar de baja paciente, órdenes, autorizaciones]
---

## Para qué sirve

Es la ficha **administrativa** de una persona: sus datos, sus coberturas, sus documentos y sus
próximos turnos, de un vistazo. Desde acá se pasa a sus otras pantallas por las pestañas de arriba.

> [!importante]
> Esta ficha **no muestra nada clínico**, a propósito. La historia clínica se abre aparte porque
> entrar a ella exige justificarlo y queda registrado. Una persona sin perfil de paciente no tiene
> historia clínica.

## Tareas

### Moverte entre las pestañas de la persona

![La cabecera de la ficha con sus pestañas marcadas](captura:ficha-de-la-persona/pestanas)

1. Desde :icono[pacientes]: **Pacientes → Padrón**, buscá a la persona y tocá **Ver ficha**.
2. Arriba, debajo del nombre, elegí la pestaña que necesitás.

- **Ficha**: el resumen de esta pantalla.
- **Historia clínica**: sólo para pacientes y para quien puede leer historias.
- **Datos declarados**: lo que cuenta o trae el paciente, cargado por recepción y sin revisar hasta
  que lo vea un profesional.
- **Documentos**: los archivos administrativos (DNI escaneado, credenciales, órdenes).
- **Coberturas**: las obras sociales o prepagas, con el número de afiliado completo.
- **Órdenes y autorizaciones**: las órdenes médicas, sus autorizaciones y qué le falta al paciente
  para atenderse por una práctica.
- **Cuenta corriente** y **Pases**: aparecen si tenés permiso para cobrar o para ver pases.

### Darle un turno nuevo

1. En la cabecera de la ficha, tocá :icono[agregar]: **Nuevo turno**.
2. Seguí los pasos del panel de reserva, que ya trae a la persona elegida.

En la tarjeta **Turnos** ves los **Próximos turnos**. Tocá **Ver** al lado de uno para abrir su
detalle.

### Revisar las coberturas vigentes

1. Mirá la tarjeta **Coberturas**: muestra las que están vigentes hoy, con la **Principal**
   marcada.
2. Para ver el historial, el número de afiliado completo o si una cobertura aplica a una actividad
   concreta, tocá **Ver coberturas del paciente**.

Sin coberturas vigentes, la ficha avisa que se atiende como particular. Una **credencial vencida**
no invalida la cobertura: pedí la renovada antes de presentar la prestación.

### Abrir la historia clínica desde la ficha

1. En la cabecera, tocá **Abrir la historia clínica**.
2. Si no figura que atiendas a esta persona en tu sede, te va a pedir un motivo antes de mostrarla.

El botón aparece sólo si la persona es paciente y tenés permiso para leer historias. La tarjeta
**Historia clínica: datos administrativos** te dice, sin contenido clínico, si tiene historia, desde
cuándo y qué casos tiene.

### Dar de baja el perfil de paciente o la ficha

![El menú de acciones de la cabecera con las dos bajas marcadas](captura:ficha-de-la-persona/dar-de-baja)

1. En la cabecera, abrí el menú :icono[mas]:.
2. Elegí **Dar de baja el perfil de paciente** o **Dar de baja la ficha**.
3. Escribí el **Motivo**.
4. Tocá **Confirmar la baja del perfil** o **Confirmar la baja de la ficha**.

- Dar de baja **el perfil**: la persona sigue en el padrón y puede seguir viniendo a actividades no
  clínicas. Su historia clínica no se borra.
- Dar de baja **la ficha**: deja de admitir operaciones nuevas y, si era paciente, también se le da
  de baja el perfil. Su documento queda libre para un alta nueva.

> [!importante]
> Ninguna baja borra nada: turnos, deudas, documentos e historia clínica quedan intactos y se siguen
> consultando.

## Qué significa cada estado

- **Activa**: la ficha está vigente.
- **Dada de baja**: no admite operaciones nuevas; arriba se ve desde cuándo y con qué motivo.
- **Paciente**: tiene perfil clínico activo. **Persona**: está registrada, sin perfil clínico.
- **— sin permiso**: una tarjeta con este agregado no está vacía; es que tu rol no puede ver esos
  datos.

## Problemas comunes

- **No veo el botón Abrir la historia clínica**: la persona no es paciente, o tu rol no puede leer
  historias clínicas.
- **No veo Nuevo turno ni el menú de bajas**: la ficha está dada de baja, o te falta el permiso para
  manejar turnos o pacientes.
- **«No pudimos leer la ficha»**: tocá **Reintentar**. Si aparece **Elegir contexto de trabajo**,
  elegí centro y sede primero.
- **Subí la foto de la credencial y la cobertura no aparece**: un documento adjunto no reemplaza un
  dato cargado. La cobertura se carga en **Coberturas**.
