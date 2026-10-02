# Retomar AKINE fase por fase

Esta carpeta existe para que cualquier desarrollador —o cualquier agente, en cualquier chat— pueda
retomar **una fase** del plan sin depender de la memoria de una sesión anterior.

Sale de la **auditoría plan-vs-código del 01/10/2026**: ocho revisiones independientes que
contrastaron cada etapa de `docs/producto/AKINE_IMPLEMENTATION_PLAN.md` contra el código real de
los dos repos (`appKine-api` en `37ff201`, `appKine-web` en `4c6ae8b`). Los defectos marcados
**verificado** se comprobaron a mano; el resto son hallazgos de la auditoría y **se verifican
contra el código antes de actuar**.

## Archivos

| Archivo | Para qué |
|---|---|
| [`00-orden-recomendado.md`](00-orden-recomendado.md) | En qué orden se encaran las fases y por qué. **Empezar acá.** |
| [`01-trabajo-en-paralelo.md`](01-trabajo-en-paralelo.md) | Cómo repartir lo que falta entre varias personas: carriles, paquetes, olas, migraciones reservadas. Diagrama: [`.excalidraw`](01-trabajo-en-paralelo.excalidraw) · [`.png`](01-trabajo-en-paralelo.png) |
| [`F0-F1-fundacion-y-plataforma.md`](F0-F1-fundacion-y-plataforma.md) | Arquitectura, CI, identidad, tenancy, permisos |
| [`F2-operacion-del-consultorio.md`](F2-operacion-del-consultorio.md) | Consultorios, espacios, colaboradores, disponibilidad, catálogos, ofertas |
| [`F3-personas-y-cobertura.md`](F3-personas-y-cobertura.md) | Persona, paciente 360, financiadores, coberturas, convenios, autorizaciones |
| [`F4-dominio-clinico.md`](F4-dominio-clinico.md) | Historia clínica, timeline, caso, plan, consumo de autorizaciones |
| [`F5-agenda-y-recepcion.md`](F5-agenda-y-recepcion.md) | Slots, reserva, ciclo de turno, series, check-in |
| [`F6-atencion-clinica.md`](F6-atencion-clinica.md) | Sesión, evaluación, examen, tratamientos, cierre, enmiendas |
| [`F7-economia-del-mvp.md`](F7-economia-del-mvp.md) | Obligaciones, cobros, caja, presentaciones, egresos |
| [`F8-cierre-del-mvp.md`](F8-cierre-del-mvp.md) | Reportes, hardening, observabilidad, release del MVP |
| [`F9-segunda-entrega.md`](F9-segunda-entrega.md) | Clases, inscripciones, asistencia, derivación, pases y abonos — **congelada** |
| [`F10-consolidacion-final.md`](F10-consolidacion-final.md) | Reporting ampliado, hardening y release final |

## Cómo se usa una ficha de fase

1. Leer `00-orden-recomendado.md` y confirmar que las fases anteriores están cerradas.
2. Leer la ficha de la fase completa.
3. Leer en el plan las etapas de esa fase (§13) y sus registros de cierre (al final del plan).
4. **Re-verificar** cada faltante contra el código: la ficha es una foto del 01/10/2026.
5. Trabajar con el flujo del `CLAUDE.md` del repo (brainstorming → diseño → design challenge → TDD → verify).
6. Al terminar: tachar en la ficha lo resuelto, escribir el registro de cierre en el plan con los
   diez puntos del §10.5, y actualizar la tabla de trazabilidad del plan.

## Prompt sugerido para abrir un chat nuevo

```text
Vamos a trabajar la fase FX de AKINE. Leé AGENT.md, CLAUDE.md, docs/fases/README.md,
docs/fases/00-orden-recomendado.md y docs/fases/FX-*.md. Después leé en
docs/producto/AKINE_IMPLEMENTATION_PLAN.md las etapas de esa fase y sus registros de cierre.
Antes de proponer nada, verificá contra el código que cada faltante de la ficha siga vigente
y decime cuáles ya no lo están.
```

## Regla para mantener esto vivo

Una ficha que dice algo que el código ya no dice es peor que no tener ficha: este proyecto ya
perdió sesiones enteras por un `CLAUDE.md` que daba por hecho lo que no estaba. **Quien cierra un
ítem lo tacha en la ficha en el mismo commit.**
