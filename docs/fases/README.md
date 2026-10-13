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
| [`decisiones-pendientes.md`](decisiones-pendientes.md) | **Lista única** de las decisiones del usuario que dejaron abiertas los cierres de fase, ordenadas por lo que cuesta postergarlas |

## Estado de las fases al 13/10/2026

Sale de los registros de cierre del 12–13/10 que cada ficha tiene al final, consolidados en el
*Registro de cierre técnico de fases — 13/10/2026* de `docs/producto/AKINE_IMPLEMENTATION_PLAN.md`.
Medido sobre `main` `c791c7c`: contrato **0.98.0**, última migración **`V93`**.

| Fase | Estado al 13/10 | % técnico | PR del cierre | Lo que queda |
|---|---|---|---|---|
| [F0–F1](F0-F1-fundacion-y-plataforma.md) | Cerrada en lo técnico | ~98 | api #99 | Del usuario: secretos de Sonar, QA manual. El 7b que la ficha da abierto lo cerró api #104 |
| [F2](F2-operacion-del-consultorio.md) | Cerrada en lo técnico | ~95 | api #102 · web #72 | Del usuario: `nomenclador_item`, `catalogo:*`, disciplina, QA manual |
| [F3](F3-personas-y-cobertura.md) | Cerrada en código | ~96 | api #103 | Del usuario: DU-13, requisitos, clonación, `convenio:read`, QA manual |
| [F4](F4-dominio-clinico.md) | Backend cerrado; pantallas pendientes | backend ~94 | api #109 | Pantallas D-a/b/c (web #4, abierto) y D-e; del usuario: C-9, plan por práctica, `caso:create` |
| [F5](F5-agenda-y-recepcion.md) | Cerrada salvo decisiones | ~92 | api #107 | Selector de cobertura en la reserva (espera el cliente); del usuario: autoservicio, ventana, `RETIRADO`, series, QA manual |
| [F6](F6-atencion-clinica.md) | Backend cerrado; pantallas sin PR | backend ~88 | api #109 | Pantallas D-d y D-g; del usuario: C-8, DU-11, subbloques, parámetros, permiso de enmienda |
| [F7](F7-economia-del-mvp.md) | Cerrada salvo decisiones | ~94 | api #108 | Del usuario: RF-M21-003, débito parcial, permisos, adjunto binario, arancel 0, QA manual |
| [F8](F8-cierre-del-mvp.md) | **El gate del MVP no pasa** | ~80 | api #106 | QA manual, aprobación y deploy, cuatro decisiones de seguridad; SLO de escrituras en curso; E2E clínicos |
| [F9](F9-segunda-entrega.md) | Congelada (DP-07) | ~23 (01/10) | — | Espera el gate de F8 |
| [F10](F10-consolidacion-final.md) | Sin empezar | 0 | — | Depende de F9 |

El % de F4 y F6 es el promedio simple de los % por etapa de su ficha. Las decisiones del usuario
de todas las fases están en [`decisiones-pendientes.md`](decisiones-pendientes.md).

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
