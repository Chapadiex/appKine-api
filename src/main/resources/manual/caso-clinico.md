---
id: caso-clinico
titulo: Caso clínico
grupo: Pacientes
rutas: ["/historia-clinica/personas/:personaId/casos/:casoId", "/historia-clinica/personas/:personaId/casos/:casoId/plan"]
permisos: [hc:read]
servicio: KINESIOLOGIA
relacionadas: [historias-clinicas, atencion, ficha-de-la-persona, glosario]
palabras: [caso, caso clínico, diagnóstico presuntivo, equipo tratante, cerrar caso, reabrir caso, plan de tratamiento, evolución, objetivo terapéutico, abrir caso]
---

## Para qué sirve

Un **caso clínico** es un problema a tratar dentro de la historia clínica de un paciente: una
rodilla y un hombro son dos casos. En su ficha ves el diagnóstico presuntivo, el equipo que lo
trata, su historial y, si está activo, lo editás, lo cerrás o lo reabrís.

> [!importante]
> Historia clínica, caso y sesión son tres cosas. La **historia** es una por persona; el **caso** es
> un problema dentro de ella; la **sesión** es cada atención, que puede pertenecer a un caso. Un caso
> cerrado no se borra: se sigue leyendo entero.

## Tareas

### Abrir un caso nuevo

![El panel Abrir un caso clínico, con la actividad, el diagnóstico presuntivo y el botón Abrir el caso marcados](captura:caso-clinico/abrir-caso)

1. En la historia clínica del paciente, buscá la tarjeta **Casos clínicos**.
2. Tocá :icono[agregar]: **Abrir un caso**.
3. Elegí la **Actividad que motiva el caso** y escribí el **Diagnostico presuntivo**, que es
   obligatorio.
4. Si querés, completá el **Objetivo terapeutico** y el **Profesional responsable**.
5. Tocá **Abrir el caso**.

Sólo se ofrecen como responsables los profesionales habilitados para esa actividad en tu sede.

### Entrar a un caso

1. En la tarjeta **Casos clínicos** de la historia, buscá el caso. Podés filtrar con **Mostrar**
   (activos, cerrados o los dos).
2. Tocá el enlace **Caso N · actividad**.

Si no atendés a esta persona en tu sede, primero te pide el motivo del acceso y tocás **Ver el
caso**. El motivo queda registrado con cada lectura.

### Armar el equipo tratante

![La tarjeta Equipo tratante con el profesional, el rol y el botón Guardar el equipo marcados](captura:caso-clinico/equipo-tratante)

1. En **Equipo tratante**, en **Sumar un profesional**, elegí a alguien habilitado.
2. Elegí el **Rol**: **Responsable** o **Tratante**.
3. Tocá **Sumar**. Para sacar a alguien, tocá **Quitar** en su fila.
4. Tocá **Guardar el equipo**.

El rol no da ni quita permisos: dice quién responde por el caso. Quien sale del equipo sigue
figurando en el historial.

### Cerrar o reabrir el caso

1. En la tarjeta **Cerrar el caso**, escribí el **Motivo del cierre**.
2. Tocá **Cerrar el caso**.
3. Para volver a activarlo, en **Reabrir el caso** escribí el **Motivo de la reapertura** y tocá
   **Reabrir el caso**.

Cerrar no borra nada y el motivo queda en el **Historial del caso**. Al reabrir, la numeración de
sesiones sigue donde quedó.

### Ver el plan de tratamiento

1. En la cabecera del caso, tocá **Ver el plan de tratamiento**.
2. Si el caso no tiene plan, tocá **Crear un plan**.
3. Cuando el borrador esté listo, tocá **Activar el plan**.

El plan define objetivos, frecuencia y cantidades estimadas. No reserva turnos solo: con el plan
activo, los turnos se programan desde la atención con **Programar próxima sesión**. Modificar un plan
activo deja una versión nueva y la anterior sigue consultable.

### Revisar la evolución

1. Mirá la tarjeta **Evolución**: **Alertas** e **Indicadores** calculados sobre las sesiones
   cerradas del caso.
2. Si el centro tiene las sugerencias activas, tocá **Pedir sugerencias**. Puede tardar hasta un
   minuto.
3. En cada sugerencia, elegí **Usar en nueva versión del plan** (o **Marcar como útil**) o
   **Descartar**.

Es un cálculo automático: no reemplaza el criterio profesional. Las sugerencias citan el libro de la
biblioteca que las respalda y aceptarlas no cambia el plan solo.

## Qué significa cada estado

- **Activo**: el caso está en curso; se puede editar, cambiar el equipo y asignarle sesiones.
- **Cerrado**: terminó, con su motivo. Se lee entero y se puede reabrir.
- Plan **Borrador**: todavía no rige. **Activo**: en curso. **Suspendido**: frenado, se puede
  reanudar. **Finalizado**: terminó.

## Problemas comunes

- **«Ya hay un caso parecido para esta persona»**: hay otro caso activo similar. Abrilo desde **Ver
  el caso N**, o tocá **Abrir igual un caso nuevo** si de verdad es otro problema.
- **«El diagnostico presuntivo es obligatorio»**: el caso no se abre ni se guarda sin él.
- **Aviso de que el caso cambió mientras lo editabas**: otra persona lo guardó antes. Tocá **Releer
  el caso** y volvé a hacer tu cambio.
- **No veo la tarjeta Evolución**: el centro la tiene apagada en Organización → Inteligencia
  artificial.
