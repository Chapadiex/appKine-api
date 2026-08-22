# ADR-0014 — Alcance del MVP (M01–M27) y segunda entrega obligatoria (M28–M29)

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

`AkinePN.docx` recorta el MVP: excluye consultorios externos y limita el alcance a un
núcleo chico. La especificación integrada, en cambio, incorpora Servicio/Oferta (M27),
clases grupales (M28) y pases/abonos (M29) como evolución del producto.

Quedaban dos preguntas sin respuesta, y las dos condicionan la secuencia completa:

- **¿M27 entra en el MVP?** Servicio/Oferta es la pieza que le pone nombre y precio a lo
  que se agenda y se cobra. Sin ella, el circuito agenda–atención–cobro no tiene sobre qué
  operar, y agregarla después obliga a retrabajar agenda y facturación.
- **¿Qué son M28–M29: compromiso o backlog?** Si son opcionales, la experiencia dice que
  no ocurren nunca: cada MVP entregado genera presión para declarar el proyecto terminado.

El impacto declarado es secuencia y tiempo de entrega: es la decisión que ordena todas las
etapas del plan, de AKINE-01 a AKINE-09.

## Decisión

**El MVP inicial incluye M01–M27** y debe entregar el circuito clínico, administrativo y
económico **completo**, incluyendo la base de Servicio/Oferta necesaria para operar:
organización y accesos, pacientes y coberturas, agenda, recepción, sesiones, obligaciones,
cobros, caja, presentaciones, reportes y auditoría.

**M28–M29 (clases grupales y pases/abonos) conforman una segunda entrega obligatoria.**
Comienza cuando el MVP:

1. cumple su Definition of Done;
2. está desplegado;
3. completa una ventana de estabilización con los bloqueantes resueltos.

**El equipo no puede cerrar el roadmap en el MVP ni convertir la segunda entrega en
opcional** sin una nueva decisión formal de producto. Si esa decisión llegara, se registra
en un ADR que supersede a este.

## Alternativas consideradas

**MVP mínimo histórico, sin M27.** Entrega antes. Descartada porque entrega un circuito
que no opera: sin Oferta no hay qué agendar con precio ni qué facturar, y el módulo
económico nacería contra entidades provisorias que habría que migrar. El ahorro de tiempo
es ilusorio: se paga con retrabajo en los módulos más delicados.

**Todo en una entrega única (M01–M29).** Coherencia máxima. Descartada porque difiere
meses el primer despliegue y el primer feedback real; clases y abonos, además, se apoyan
sobre el circuito base (agenda, cupos, caja) y se diseñan mejor cuando ese circuito ya
está estabilizado.

**M28–M29 como backlog opcional.** La opción por defecto si no se decide nada. Descartada
explícitamente: son alcance de producto comprometido. Dejarlos "para ver" es la manera
silenciosa de cancelarlos, y esta decisión existe para impedir exactamente eso.

## Consecuencias

### Positivas

- El plan tiene un final verificable por entrega: DoD del MVP, despliegue, estabilización,
  segunda entrega. No hay zona gris para declarar "terminado".
- El circuito económico se construye una sola vez, ya con Oferta como ciudadana de primera
  clase.
- La segunda entrega arranca sobre un sistema estabilizado, con los incidentes del MVP
  resueltos en lugar de arrastrados.

### Negativas

- El MVP es grande: M01–M27 es la mayor parte del sistema, y el tiempo hasta la primera
  entrega es largo. Es el costo de exigir el circuito completo.
- Clases y abonos llegan más tarde de lo que las fuentes históricas sugerían; si hay
  usuarios esperándolos, esperan una entrega más.
- "Ventana de estabilización con bloqueantes resueltos" exige definirse de forma medible
  (duración, criterios de bloqueante) antes del cierre del MVP, o la frontera entre
  entregas se vuelve discutible.

### Qué obliga a hacer

- Diseñar Servicio/Oferta (M27) como base transversal en las etapas AKINE-02.06–02.07:
  `scheduling` agenda ofertas y `billing` las valora — desde el MVP, no como parche.
- No adelantar M28–M29: los puntos de extensión (cupos grupales, créditos de abono) se
  documentan cuando aparecen, pero su implementación vive en AKINE-08.01–08.09.
- Tratar la Definition of Done del MVP y los criterios de estabilización como entregables
  del plan, con evidencia — no como sobreentendidos.
- Cualquier cambio de este alcance requiere decisión formal de producto y un ADR que
  supersede a este.
