# ADR-0010 — Historia Clínica de alcance organizacional

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

Los documentos históricos hablan de **una** Historia Clínica por paciente, sin más
precisión: una HC única que sigue a la persona. El modelo multi-tenant, en cambio, exige
aislamiento y permisos contextuales, y M09 no define de forma inequívoca si la HC es
global, por Organización o por Consultorio.

Los tres alcances posibles producen sistemas distintos:

- **Global**: cualquier organización donde el paciente se atienda vería su historia
  completa. Máxima continuidad clínica, pero implica compartir datos de salud entre
  entidades legales distintas sin base de consentimiento ni marco de interoperabilidad.
- **Por Consultorio**: máximo aislamiento, pero fragmenta la historia dentro de una misma
  organización: el paciente atendido en dos sedes tendría dos historias parciales.
- **Por Organización**: la historia es longitudinal dentro del tenant y el límite de
  privacidad coincide con el límite legal y comercial del SaaS.

El impacto declarado —privacidad, duplicación e intercambio clínico— es el más sensible
del sistema: se trata de datos de salud. Había que fijarlo antes de diseñar una sola tabla
del módulo clínico (AKINE-04.01).

## Decisión

La **Historia Clínica pertenece a la Organización** y constituye el contexto longitudinal
del paciente dentro de ese tenant.

- Puede consultarse desde distintos Consultorios de la **misma** Organización, únicamente
  por actores que reúnan tres condiciones: **membership vigente**, **permiso clínico** y
  **relación asistencial** con el paciente — o, en su defecto, una **justificación
  autorizada** registrada en el momento del acceso.
- **Nunca se comparte automáticamente entre Organizaciones diferentes.** Si la misma
  persona se atiende en dos organizaciones, existen dos Historias Clínicas independientes.
- **Todo acceso clínico sensible y toda excepción quedan auditados**: quién accedió, a
  qué, cuándo y con qué justificación.

## Alternativas consideradas

**HC global única por paciente (lectura histórica).** Descartada porque convierte al SaaS
en una red de intercambio de datos de salud sin consentimiento del paciente ni marco legal
que lo habilite: un profesional de la organización B leyendo evoluciones cargadas por la
organización A es una filtración, no una feature. La continuidad inter-organizacional es
un problema real, pero se resuelve con exportación o derivación explícita y consentida —
fuera del alcance del MVP—, no con una tabla compartida.

**HC por Consultorio.** Descartada porque fragmenta sin proteger nada adicional: las sedes
de una organización comparten razón social, responsables y pacientes. Dos historias
parciales del mismo paciente en el mismo tenant duplican datos, esconden información
clínicamente relevante (alergias, medicación) y obligan a inventar un mecanismo de
consolidación que el alcance organizacional da gratis.

## Consecuencias

### Positivas

- El límite de privacidad coincide con el límite del tenant: las reglas de aislamiento de
  [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md) protegen también la HC,
  sin un régimen especial paralelo.
- Dentro de la organización, la historia es completa: cambiar de sede no la corta.
- El triple requisito (membership + permiso + relación asistencial) deja el acceso "por
  curiosidad" fuera del camino feliz y lo fuerza por la vía auditada.

### Negativas

- **Duplicación real entre organizaciones.** El mismo paciente atendido en dos tenants
  tiene dos historias que no se ven entre sí: anamnesis repetida, estudios repetidos. Es
  el costo aceptado de la privacidad por defecto.
- La verificación de relación asistencial y el flujo de justificación autorizada
  ("break-the-glass") agregan complejidad a cada lectura clínica.
- La auditoría de accesos de lectura genera un volumen de datos considerable y necesita
  su propia política de retención.

### Qué obliga a hacer

- `clinical` es propietario de la HC; toda tabla clínica lleva `organization_id NOT NULL`
  y sus únicos e índices incluyen el tenant
  ([ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md)).
- La autorización de lectura clínica consulta membership y permisos de `organization` vía
  `spi`, y además evalúa la relación asistencial — la verificación de tenant sola no
  alcanza.
- El flujo de justificación autorizada se diseña como parte de M09, no como parche
  posterior: motivo obligatorio y evento de auditoría en el mismo acto.
- Etapas: AKINE-04.01–04.02 (HC, resumen, timeline, accesos); auditoría transversal según
  M24/§42.
