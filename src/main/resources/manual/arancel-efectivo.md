---
id: arancel-efectivo
titulo: Arancel efectivo
grupo: Contratación
rutas: ["/contratacion/arancel-efectivo"]
permisos: [convenio:read]
servicio: KINESIOLOGIA
relacionadas: [convenios, financiadores, catalogo-clinico, cuenta-corriente]
palabras: [cuánto sale, precio de una práctica, arancel, coseguro, consultar arancel, cobertura, convenio vigente, particular]
---

## Para qué sirve

Responde **cuánto sale una práctica** para un financiador y un plan, en una fecha, en **esta
sede**: cuánto pone el financiador y, si elegís la actividad, cuánto paga el paciente (coseguro:
precio de la actividad menos lo que pone el financiador). Además dice
**de dónde sale** el precio: qué convenio y qué arancel lo produjeron.

> [!consejo]
> Que no haya arancel es una respuesta normal, no una falla: la mayoría de las atenciones son
> particulares.

## Tareas

### Consultar cuánto sale una práctica

![El formulario Datos de la consulta, con el financiador, el plan, la práctica, la fecha y el botón Consultar marcados](captura:arancel-efectivo/consultar)

1. Entrá a :icono[contratacion]: **Contratación → Arancel efectivo**.
2. Elegí el **Financiador**.
3. Elegí el **Plan**. Se llena al elegir el financiador, sólo con sus planes activos.
4. Elegí la **Práctica**. Si la atención fue dentro de una actividad con precio propio, elegila en
   **Actividad (opcional)**.
5. Poné la **Fecha de la prestación**: la del día atendido, no la de hoy.
6. Tocá **Consultar**.

El resultado muestra **Pone el financiador** y, si elegiste la actividad, el total y **Paga el
paciente**. Abajo, **De donde sale este precio**: el convenio y la vigencia del arancel.

### Averiguar qué precio regía en una fecha pasada

1. Cargá el financiador, el plan y la práctica como en una consulta normal.
2. En **Fecha de la prestación**, poné la fecha vieja.
3. Tocá **Consultar**.

Una fecha pasada devuelve el arancel que regía entonces, aunque hoy sea otro.

## Problemas comunes

- **«No hay convenio vigente para ese financiador y ese plan en esa fecha»**: la prestación se
  cobra como particular. Si el convenio debería existir, cargalo en **Convenios de la sede**; si
  venció, editalo y estirale el fin.
- **«Hay convenio, pero esa práctica no tiene precio cargado para esa fecha»**: falta el arancel
  de esa práctica. Cargalo en la grilla del convenio; hasta entonces no se puede liquidar contra el
  financiador.
- **El resultado lista requisitos (orden, autorización, credencial)**: el sistema todavía no los
  hace cumplir. Los tiene que verificar quien atiende el mostrador.
- **El plan no aparece**: sólo salen los planes activos del financiador elegido.
- **«Todavía no elegiste un consultorio»**: la consulta es por sede. Tocá **Elegir un consultorio**.
