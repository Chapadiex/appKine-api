# AKINE — Consentimiento del centro para usar IA

> Diseño corto, aprobado por el usuario el 26/11/2026. Migración **V49**, contrato **0.195.0**
> (V48/0.194.0 los tomó la rama `akine-pedido-de-plan`).

## Por qué

Ley 25.326: el centro es responsable de los datos de sus pacientes, que son datos de salud. Antes de
que cualquier función de IA los procese —con el modelo local o con un proveedor externo como Claude u
OpenCode— el centro tiene que dar su consentimiento, y AKINE tiene que poder probar **quién lo dio,
cuándo y sobre qué texto**.

## Objetivos

- El ORG_ADMIN acepta, en nombre del centro, el texto vigente del consentimiento de IA.
- Sin consentimiento vigente **ninguna** función de IA corre: asistente (y sus acciones), evolución,
  sugerencias, biblioteca y dictado. Da igual que el modelo sea local o externo.
- Cada aceptación queda guardada para siempre (no se pisa ni se borra) y además en la auditoría.
- Si el texto cambia, se sube su versión y todos los centros vuelven a aceptar.

## Fuera de alcance

- Consentimiento por paciente (opción B). Es la segunda etapa posible.
- Retirar el consentimiento: apagar los interruptores ya frena todo.
- Texto legal definitivo: el de este cambio es un **borrador que tiene que revisar un abogado**.

## Enfoques considerados

1. **Patrón de los términos obligatorios (elegido):** texto y versión en el backend, aceptación
   guardada en una tabla, bloqueo en el servidor. Se reutiliza algo que ya funciona y deja prueba.
2. Sólo en pantalla (guardar "aceptado" en el navegador): no deja prueba ni bloquea la API.
3. Documento firmado aparte: prueba legal, pero no bloquea nada y queda fuera del sistema.

## Cómo

**Datos (V49).** Tabla `organization_ia_consentimiento`: `id`, `organization_id`, `version` (texto),
`account_id`, `nombre` (de quien aceptó, copiado al aceptar), `aceptado_at`. Sólo inserciones; el
vigente es la última fila del centro cuya `version` coincide con la del despliegue.

**Texto y versión.** `akine.ia.consentimiento.version` (por defecto `2026-11-26`;
`AKINE_IA_CONSENTIMIENTO_VERSION` la cambia). El texto vive en
`src/main/resources/ia/consentimiento.md` y viaja junto con su versión. **Versión vacía = no se exige**:
así corren los tests y el CI, igual que los términos.

**Bloqueo.** El control ya está concentrado en `FuncionesIaService.prendida()`: todas las funciones
preguntan ahí. Se agrega la condición "consentimiento vigente" en ese método, y `FuncionesIaDelCentro`
suma `consentimientoVigente(orgId)` para el dictado, que hoy no pasa por `prendida()`.
*Decisión del implementador:* sin consentimiento cada módulo responde como cuando la función está
apagada (409 o 404, lo que ya hace), en vez de un 409 `ia-sin-consentimiento` nuevo. Así no hay un
camino de error más por módulo, y la pantalla sabe el motivo por la vista de abajo.

**API** (`/api/v1/organizations/{orgId}/ia`, mismo controlador):
- `GET` suma `consentimiento: { exigido, versionVigente, aceptado, aceptadoPor, aceptadoAt }`.
- `GET .../consentimiento` devuelve `{ version, texto }`.
- `POST .../consentimiento` con `{ version }` (requiere `tenant:update`): guarda la aceptación, audita
  `ORGANIZATION_IA_CONSENTIMIENTO_ACEPTADO`. Versión distinta de la vigente → 409
  `concurrent-modification` (el texto cambió mientras se leía). Aceptar dos veces la misma versión no
  duplica: devuelve la vigente.

**Pantalla** (Organización → Inteligencia artificial):
- Sin consentimiento: arriba, un cartel con el texto y "Acepto en nombre del centro"; los
  interruptores quedan deshabilitados.
- Con consentimiento: una línea "Consentimiento aceptado por X el día Y (versión Z)".
- Las demás pantallas de IA ya desaparecen del menú cuando la función no está activa; no cambian.

## Lo que hereda quien implemente

- Al desplegar, la IA se frena en todos los centros hasta que su admin acepte.
- `VisibilidadDelManual` usa `prendida()`: las fichas de IA del manual se esconden sin consentimiento.
  Es lo esperado.
