# Matriz mínima de permisos — AKINE

- **Estado:** Aprobada (etapa AKINE-00.03)
- **Fuente normativa:** `AKINE_Requerimientos_Integrados.md`, sección **32** "Requerimientos Transversales Complementarios", subsección "27.1 Matriz mínima de permisos" (la numeración interna del documento es inconsistente; citar siempre "§32").
- **Alcance:** piso funcional. El propio §32 lo dice: *"la matriz es una base funcional; el backend debe implementar permisos más granulares"*. Este documento fija **cómo** se modela esa granularidad, porque la spec no lo define (§44: hueco funcional → decisión documentada, nunca inventada en silencio).

---

## 1. Roles de seguridad

La spec no define un enum formal de roles; surgen de las columnas de la matriz y de los actores de M01/M02/M05.

| Código | Rol | Alcance | Evidencia |
|---|---|---|---|
| `PLATFORM_ADMIN` | Administrador de plataforma | Global, cross-tenant, con accesos **restringidos** a datos clínicos | §32; actores M01/M02 |
| `ORG_ADMIN` | Administrador de organización | Organización (tenant) completa | §32; actores M01/M02 |
| `CONSULTORIO_ADMIN` | Administrador de consultorio | Un consultorio | §32; actores M02/M05 |
| `PROFESIONAL` | Profesional | Un consultorio — rol contextual | §32; RN-M02-002; CA-M05-007-06 |
| `ADMINISTRATIVO` | Administrativo / recepción | Un consultorio | §32; actor M05 |
| `PACIENTE` | Paciente | Lo propio | §32 |

Reglas estructurales (vinculantes):

1. **El rol vive en la membership, no en la cuenta** (RN-M02-002). Un mismo usuario puede ser `PROFESIONAL` en un consultorio y `CONSULTORIO_ADMIN` en otro.
2. **Rol de seguridad ≠ disciplina ≠ especialidad ≠ habilitación para prestar un servicio** (RN-M05-005). Son cuatro dimensiones. **Prohibido** crear roles por profesión (`Instructor`, `Kinesiólogo`…) — RN-M05-006 y §30.4.
3. `PLATFORM_ADMIN` no tiene membership en ninguna organización: su alcance es global y **sus accesos a datos de tenant son los más restringidos de la matriz**, no los más amplios.
4. "Auditor" y "Administrador autorizado" (actores de M24) **no son roles de seguridad**: son permisos adicionales (`auditoria:read`, `auditoria:read-clinica`) otorgables a una membership. La spec no los define como roles; decidido acá.

## 2. Matriz literal de §32

Transcripción exacta. Los valores **no son binarios**; su semántica operativa está en la sección 3.

| Acción | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| Gestionar tenant | Sí | Limitado | No | No | No | No |
| Gestionar consultorio | Global | Sí | Sí | No | No | No |
| Gestionar colaboradores | Global | Sí | Sí | No | No | No |
| Gestionar paciente | Soporte | Sí | Sí | Según permiso | Sí | Propio |
| Ver Historia Clínica | Restringido | No por defecto | Según rol clínico | Sí | Limitado | Propia autorizada |
| Editar Historia Clínica | No por defecto | No | No por defecto | Sí | No | No |
| Crear Caso Clínico | No | No | Según rol clínico | Sí | No | No |
| Registrar Sesión | No | No | Según rol clínico | Sí | No | No |
| Administrar Convenios | Catálogo global | Sí | Sí | No | No | No |
| Registrar Cobro | No | Sí | Sí | No por defecto | Sí | No |
| Operar Caja | No | Sí | Sí | No | Sí | No |
| Ver Reportes | Global | Sí | Sí | Limitado | Limitado | No |

## 3. Semántica de los valores — decisión de modelado

Cada valor de la matriz se traduce a **una regla ejecutable**. Esta tabla es la decisión que §32 deja abierta.

| Valor | Regla ejecutable | Cómo se otorga |
|---|---|---|
| **Sí** | Permiso base del rol, en el alcance de la membership | Implícito en el rol |
| **No** | Denegado. No otorgable por membership | — |
| **Global** | Permiso base de `PLATFORM_ADMIN`, sin restricción de tenant | Implícito en el rol |
| **Limitado** | Permiso base del rol **con restricción definida por acción** (ver sección 4) | Implícito, restringido |
| **Soporte** | Solo mediante **acceso de soporte**: justificación obligatoria, acotado en tiempo y **auditado** (DP-03 aplica el mismo principio a la HC) | Grant temporal con motivo |
| **Restringido** | Denegado por defecto; solo con acceso de soporte justificado **y** permiso adicional clínico. Nunca implícito | Grant temporal + `hc:read` explícito |
| **No por defecto** | No forma parte del rol. **Otorgable a una membership concreta** como permiso adicional | Grant explícito en la membership |
| **Según permiso** | Igual que "No por defecto": grant explícito en la membership | Grant explícito en la membership |
| **Según rol clínico** | Requiere, además del rol, **habilitación profesional vigente a la fecha del evento** (RN-M05-005, RF-M05-008) | Rol + habilitación |
| **Propio** | Alcance `OWN`: solo sobre las entidades del propio paciente | Implícito, alcance propio |
| **Propia autorizada** | Alcance `OWN` **y** la organización habilitó el acceso del paciente a su HC | Implícito + flag de organización |
| **Catálogo global** | Solo sobre el catálogo de plataforma (financiadores/planes globales), nunca sobre convenios de un tenant | Implícito, alcance catálogo |

### Modelo resultante

```
permiso_efectivo(actor, acción, contexto) =
      base(rol_de_la_membership_vigente_en_el_contexto, acción)
    ∪ grants_adicionales(membership)
    ∩ alcance(membership)                 ← organización / consultorio / propio
    ∩ habilitación_vigente(fecha_evento)  ← solo acciones "según rol clínico"
```

- **La evaluación es siempre server-side** (RN-M02-001, RN-M01-003). El frontend oculta botones como ayuda de UX; nunca es el control.
- **La membership debe estar vigente en el momento de la evaluación.** Un token acotado al contexto puede portar un rol ya revocado (caso QA "rol revocado entre lectura y confirmación"): toda operación sensible **re-verifica la membership contra la base**, no contra el token.
- **Referencia fuera del alcance → `NOT_FOUND`**, nunca `FORBIDDEN` (todos los RF: "tratar como no accesible"). `FORBIDDEN` se reserva para *actor sin permiso sobre una entidad que sí está en su alcance*.

## 4. Restricciones de los valores "Limitado"

La spec no las detalla. Decisión para cada celda, revisable en la etapa que implemente la acción:

| Celda | Restricción |
|---|---|
| Gestionar tenant — `ORG_ADMIN` | Puede editar datos de su propia organización y ver su suscripción; **no** puede cambiar de plan ni suspender/reactivar (eso es `PLATFORM_ADMIN`) |
| Ver HC — `ADMINISTRATIVO` | Solo metadatos administrativos (existencia, fechas, profesional tratante, cobertura). **Nunca** contenido clínico |
| Ver Reportes — `PROFESIONAL` | Solo reportes de su propia actividad |
| Ver Reportes — `ADMINISTRATIVO` | Solo reportes operativos y de caja del consultorio, sin contenido clínico |

## 5. Catálogo de permisos granulares

Códigos `<dominio>:<acción>`. Estable, en minúsculas, sin significado de UI. Las acciones clínicas y económicas se listan para fijar el catálogo; **solo las marcadas F1 se implementan en la Fase 1**.

| Código | Acción de la matriz | Fase |
|---|---|---|
| `tenant:manage` | Gestionar tenant | **F1** |
| `tenant:read` | Ver datos y suscripción de la propia organización (el "Limitado" de `ORG_ADMIN`) | **F1** |
| `consultorio:manage` | Gestionar consultorio | **F1** (mínimo) / F2 |
| `colaborador:manage` | Gestionar colaboradores: invitar, asignar rol, revocar, desvincular | **F1** |
| `colaborador:read` | Listar colaboradores del alcance | **F1** |
| `auditoria:read` | Consultar auditoría operativa del alcance | **F1** |
| `auditoria:read-clinica` | Consultar auditoría de acceso clínico (RN-M24-003) | **F1** (modelo) / F4 |
| `paciente:manage` | Gestionar paciente | F3 |
| `hc:read` / `hc:write` | Ver / Editar Historia Clínica | F4 |
| `caso:create` | Crear Caso Clínico | F4 |
| `sesion:register` | Registrar Sesión | F6 |
| `convenio:manage` | Administrar Convenios | F3 |
| `cobro:register` | Registrar Cobro | F7 |
| `caja:operate` | Operar Caja | F7 |
| `reporte:read` | Ver Reportes | F8 |

## 6. Asignación base por rol — Fase 1

Lo que cada rol tiene **implícito** para las acciones de F1. Todo lo que no figura está denegado salvo grant explícito.

| Permiso | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| `tenant:manage` | Global | — | — | — | — | — |
| `tenant:read` | Global | Org | — | — | — | — |
| `consultorio:manage` | Global | Org | Consultorio | — | — | — |
| `colaborador:manage` | Global | Org | Consultorio | — | — | — |
| `colaborador:read` | Global | Org | Consultorio | Consultorio | Consultorio | — |
| `auditoria:read` | Global | Org | Consultorio | — | — | — |
| `auditoria:read-clinica` | Restringido (grant + soporte) | No por defecto (grant) | No por defecto (grant) | — | — | — |

## 7. Invariantes que F1 debe hacer cumplir

| Invariante | Fuente |
|---|---|
| Toda evaluación de permiso ocurre en backend y revalida tenant **y** membership vigente | RN-M01-003, RN-M02-001 |
| Una organización no puede quedar sin al menos un `ORG_ADMIN` vigente ("último admin") | Validaciones de AKINE-01.03 |
| Un actor no puede revocarse a sí mismo su último rol administrativo ("self-revoke") | Validaciones de AKINE-01.03 |
| Revocar un rol o desvincular **no borra** autoría ni historia; la membership pasa a un estado final con vigencia cerrada | RN-M02-004, RN-M05-003, §38 |
| Toda asignación, revocación, invitación, aceptación, bloqueo y acceso de soporte queda **auditado** con actor, fecha, tenant, consultorio, entidad, estado anterior/nuevo y motivo | M24; RNF-*-006 |
| `PLATFORM_ADMIN` accede a datos de un tenant solo por acceso de soporte justificado y auditado | §32 ("Soporte", "Restringido"); DP-03 |
| Las acciones "según rol clínico" exigen habilitación vigente **a la fecha del evento**, no a la fecha actual | RF-M05-008 |

## 8. Huecos que esta matriz NO cierra (decisiones pendientes con etapa destino)

| Hueco | Etapa |
|---|---|
| Modelo de "acceso de soporte": duración, quién lo otorga, cómo se revoca | AKINE-01.03 (diseño) — implementación mínima en F1, completa en F8 hardening |
| Flag organizacional "paciente puede ver su HC" (Propia autorizada) | F4 |
| Catálogo definitivo de restricciones "Limitado" en reportes | F8 |
| Si `auditoria:read-clinica` requiere además relación asistencial (DP-03) | F4 |
