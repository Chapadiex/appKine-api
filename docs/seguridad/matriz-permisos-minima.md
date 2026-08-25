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
| `espacio:read` | Consultar el catálogo físico y la disponibilidad de una sede | F2 |
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
| `espacio:read` | Soporte | Org | Consultorio | Consultorio | Consultorio | — |
| `colaborador:manage` | Global | Org | Consultorio | — | — | — |
| `colaborador:read` | Global | Org | Consultorio | Consultorio | Consultorio | — |
| `auditoria:read` | Global | Org | Consultorio | — | — | — |
| `auditoria:read-clinica` | Restringido (grant + soporte) | No por defecto (grant) | No por defecto (grant) | — | — | — |

> `espacio:read` es de **F2** y está en esta tabla porque su módulo ya existe (M04, etapa
> 02.02). Se aprobó el **25/08/2026**; el registro de la decisión está en §10.1.

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

---

## 9. Enmiendas — AKINE-01.03

La matriz es **vinculante**: nada de lo que sigue se aplicó en silencio. Cada punto es una
diferencia real entre lo que dicen las secciones 1–8 y lo que el código hace, con su motivo y su
alcance. Si una enmienda no está acá, no existe.

### 9.1 Lectura básica de la organización y de sus consultorios — **enmienda aprobada**

**Qué dice la matriz.** La sección 6 da `tenant:read` únicamente a `ORG_ADMIN` (alcance
organización) y a `PLATFORM_ADMIN` (global). Leído a la letra, un `PROFESIONAL` no puede leer
nada de su propia organización.

**Qué hace el código.** `GET /api/v1/organizations/{orgId}` y
`GET /api/v1/organizations/{orgId}/consultorios` están habilitados para **cualquier miembro
vigente**, con cualquier rol. Lo aplica `AuthorizationGuard.requireMember`, que decide por
**pertenencia** y no por permiso.

**Por qué.** Dos motivos, y ninguno es de comodidad:

1. **Es el comportamiento vigente desde AKINE-01.01** y los dos endpoints están publicados en el
   contrato `0.2.0`. Ajustarlos a la letra de la sección 6 sería un cambio **incompatible** en
   endpoints ya publicados, no una corrección.
2. **El selector de contexto no funciona sin eso.** Después del login, la persona elige
   organización + consultorio (ADR-0009). Para elegir necesita ver el nombre de su organización
   y sus sedes. Un `PROFESIONAL` sin esa lectura no puede ni entrar a trabajar.

**Qué NO habilita la enmienda.** Los datos de la **suscripción** siguen detrás de `tenant:read`,
y por lo tanto siguen siendo de `ORG_ADMIN`. La enmienda cubre el perfil de la organización y el
listado de sedes; nada más.

**Alcance.** Fase 1. La etapa que revise el catálogo de la sección 5 debería decidir si esta
lectura merece un código propio —algo como `tenant:read-basic`— en vez de quedar como una
excepción por pertenencia. Hoy inventarlo sería agregar un código que la matriz no declara.

### 9.2 `tenant:read` cubre también la edición de la propia organización — **contradicción interna, no enmienda**

La sección 4 dice que el "Limitado" de `ORG_ADMIN` en *Gestionar tenant* **incluye editar los
datos de su propia organización** (y excluye cambiar de plan y suspender, que son de plataforma).
La sección 6, en cambio, le da a `ORG_ADMIN` solo `tenant:read`, y la sección 5 no declara ningún
código para "editar la propia organización".

**Las dos secciones no coinciden entre sí.** AKINE-01.03 se resolvió por el lado que preserva el
comportamiento vigente y el texto de la sección 4: `PATCH /organizations/{orgId}` exige
`tenant:read` con alcance organización. **No se inventó un código nuevo**, porque el catálogo es
vinculante y agregarle una fila es una decisión de la matriz, no de una etapa.

Queda registrado para la etapa que enmiende el catálogo: o la sección 4 se acota a solo lectura,
o la sección 5 gana un código de edición y la sección 6 se lo asigna a `ORG_ADMIN`.

### 9.3 Desviación declarada de RF-M05-001 y RF-M05-002 — alta directa en vez de invitación

**Decisión del usuario del 23/08/2026 (D-1, opción B).** El flujo de invitación por correo
—invitar, aceptar, rechazar, revocar la invitación— **no entra en AKINE-01.03**. En su lugar
entra el **alta directa por un administrador** sobre una cuenta que ya existe
(`organization.spi.MembershipProvisioning`).

Es una **desviación declarada**, no un olvido: el plan de la etapa nombra esos dos RF. Sin el
alta directa la etapa quedaba sin ninguna vía para crear memberships, con `colaborador:manage`
evaluándose sobre un conjunto de una sola fila y RN-M02-002 soportada por el esquema y sin camino
de usuario.

**Lo que la desviación no cubre**, y viaja con RF-M05-001/002 a la etapa que los reciba: no hay
consentimiento de la persona vinculada, no hay alta de cuentas nuevas por esta vía, y el estado
`INVITADA` no existe.

**Consecuencia de ownership que hay que respetar:** el endpoint del alta directa vive en
`identity.api`, no en `organization.api`. Un administrador tipea un **email**, y `cuenta` es de
`identity`; `organization` no compila contra ese módulo, así que la traducción email → `accountId`
ocurre del lado de `identity`, que después entra por el `spi`.

### 9.4 Acceso de soporte — hueco de la sección 8, cerrado (D-3)

La sección 8 declaraba el modelo de acceso de soporte como hueco con etapa destino 01.03. Queda
cerrado así, por decisión del usuario del 23/08/2026:

| Pregunta | Decisión | Qué se aceptó a cambio |
|---|---|---|
| Quién lo otorga | El propio `PLATFORM_ADMIN`, con **motivo obligatorio** | Es el modelo más débil de los tres evaluados. Los otros dos —cuatro ojos, o aprobación del `ORG_ADMIN`— no sirven para el caso que motiva casi todo el soporte, que es "el tenant no puede entrar" |
| Duración | **4 horas** | Una hora genera fricción operativa real; veinticuatro deja abierta una ventana de un día sobre datos de salud ajenos |
| Si el tenant se entera | **Sí**: `SUPPORT_ACCESS_GRANTED` se escribe con el `organization_id` del tenant, así que aparece en su propia consulta de auditoría | El aviso **por correo** no se implementó: resolverlo exige el email de una cuenta, y `organization` no puede leer `identity`. Queda como pendiente declarado |

Además, **cada operación** realizada al amparo de un acceso vigente deja `SUPPORT_ACCESS_USED`
(sección 7), no solo el otorgamiento.

**Lo que el rol de plataforma NO habilita por sí solo.** La sección 6 le da "Global" a las
acciones administrativas del *contrato* del tenant (alta de organización, plan, suscripción,
colaboradores) y "Soporte"/"Restringido" a las que tocan **datos de personas**. El evaluador
respeta esa diferencia literalmente: los alcances `SOPORTE` y `RESTRINGIDO` **exigen**
`support_access` vigente; `GLOBAL` no. `auditoria:read-clinica` con alcance `RESTRINGIDO`
**deniega siempre en Fase 1**, porque el permiso clínico que la sección 3 le exige además no
existe hasta F4.

### 9.5 Alcance de `auditoria:read` para `CONSULTORIO_ADMIN` — **sigue abierto** (D-7)

Implementado tal como lo dice la sección 6: alcance "Consultorio", filtrando por
`consultorio_id`. **Consecuencia incómoda que no se tapó:** casi todos los eventos que el sistema
escribe hoy llevan `consultorio_id` nulo, porque las operaciones de M01/M02 son de alcance
organización. Con el filtro estricto, un `CONSULTORIO_ADMIN` abre la pantalla de auditoría y **no
ve nada**.

Se implementó la versión estricta porque es la única que no concede de más: mostrarle además los
eventos de alcance organización le expondría transiciones de suscripción y datos del tenant que
no le competen. La alternativa siempre se puede agregar; al revés, no.

### 9.6 Límites de la consulta de auditoría — defaults provisorios (D-8)

Sin RF que los respalde. Fijados en `AuditQueryService`, en un solo lugar y con nombre:
rango máximo **90 días**, tamaño máximo de página **100**, orden siempre `occurred_at DESC`.
Sin tope, un `from=1970` sobre un tenant grande es un scan y un problema de disponibilidad.

### 9.7 `tenant:read` y `auditoria:read` de `PLATFORM_ADMIN` pasan a alcance `SOPORTE` — **enmienda aprobada**

**La sección 6 y la sección 7 se contradicen, y la contradicción no se puede dejar abierta.**
La sección 6 le da `Global` a toda la columna de `PLATFORM_ADMIN` salvo la auditoría clínica.
La sección 7 declara el invariante contrario: *"`PLATFORM_ADMIN` accede a datos de un tenant
**solo** por acceso de soporte justificado y auditado"* (§32, DP-03). Las dos son vinculantes.

**Qué pasaba mientras la contradicción estuvo abierta.** Ganaba la sección 6 en todos lados, y
por lo tanto **ninguna celda de la tabla era `SOPORTE`**: la rama `SOPORTE` del evaluador era
código muerto y nada del sistema exigía nunca un `support_access` vigente. El párrafo final de
§9.4 —"los alcances `SOPORTE` y `RESTRINGIDO` exigen `support_access` vigente"— era cierto sobre
el evaluador y vacío sobre la tabla, porque no había ninguna celda así. El acceso de soporte
quedaba reducido a un flag opcional en la decisión, que además cuatro servicios ignoraban.

**Cómo se resolvió: por el lado que falla cerrado, y solo en las lecturas.** La matriz no alcanza
para decidirlo —los dos párrafos son igual de vinculantes y dicen lo contrario—, así que se eligió
la opción que no concede de más:

| Permiso | Antes | Ahora | Por qué |
|---|---|---|---|
| `tenant:read` | Global | **Soporte** | Es el perfil del tenant, sus sedes y su suscripción: datos del tenant, que es exactamente lo que §7 protege |
| `auditoria:read` | Global | **Soporte** | Es el mapa de lo que hace un cliente. Sin esto, se podía leer el rastro de un tenant sin dejar rastro de haberlo leído |
| `tenant:manage` | Global | **Global** (sin cambio) | Es el **contrato** del tenant —alta, cambio de plan, suspensión—, no sus datos. Es la capacidad de intervenir en un incidente, y ya deja su propia fila de auditoría nominal. Exigirle soporte dejaría a la plataforma sin poder suspender un tenant abusivo |

| `colaborador:read` | Global | **Soporte** | Decisión del usuario, 24/08/2026. Es el **padrón de personas** de un centro ajeno, que es literalmente lo que §7 protege, y una lectura no deja ninguna otra fila: sin `SUPPORT_ACCESS_USED` no queda evidencia de que ocurrió |

**El criterio que separa `Global` de `Soporte` en esta columna es una sola pregunta:** ¿la
operación deja por sí misma una fila que diga quién la hizo y por qué?

Las **mutaciones** la dejan —`SUBSCRIPTION_TRANSITIONED`, `MEMBERSHIP_*`, `CONSULTORIO_*`, todas
con actor y motivo obligatorio—, así que el hecho queda trazado aunque no se registre además como
uso de soporte. Las **lecturas** no dejan nada. Por eso las tres lecturas de datos de un tenant
—`tenant:read`, `auditoria:read` y `colaborador:read`— quedaron en `Soporte`, y las mutaciones
en `Global`.

**Lo que esta enmienda NO cierra, y queda reportado sin tapar.** `consultorio:manage` y
`colaborador:manage` siguen en `Global`, por el criterio de arriba: son mutaciones y dejan su
propia fila nominal. Exigirles soporte sumaría un paso a operaciones de rescate sin agregar
trazabilidad que no exista ya. **Es una decisión tomada, no un descuido**, y se revisa si alguna
vez una de esas mutaciones deja de auditar con actor y motivo.

**Consecuencia operativa que hay que saber.** Un `PLATFORM_ADMIN` que crea una organización por
`POST /organizations` no puede leerla de vuelta sin autoconcederse un acceso de soporte. Es el
costo aceptado del fail-closed, y es de un solo paso: el acceso se autoconcede con motivo y dura
cuatro horas (§9.4).

---

## 10. Enmiendas — AKINE-02.02 (Espacios, boxes y capacidad física)

Mismo criterio que la sección 9: nada se aplicó en silencio. Cada punto es una diferencia real
entre lo que dicen las secciones 1–8 y lo que el código hace, con su motivo y su alcance.

### 10.1 `espacio:read` — **APROBADO el 25/08/2026, en el catálogo**

**El hueco que lo originó, en una línea.** La etapa 02.02 exige que "profesionales/administrativos"
puedan **consultar** los espacios de la sede. El catálogo de la sección 5 **no tenía ningún
código de lectura de espacios**: el único que aplicaba al recurso físico era
`consultorio:manage`, y la sección 6 se lo niega justamente a esos dos roles.

**Lo que 02.02 hizo mientras tanto, y por qué no inventó el código.** Las mutaciones —alta,
edición, baja— exigen `consultorio:manage` con alcance CONSULTORIO, que es lo que la matriz
dice. Las **lecturas** —detalle, listado y disponibilidad— autorizaban por **pertenencia**:
membership vigente en la organización, con cualquier rol. No agregó `espacio:read` al catálogo
porque el catálogo es **vinculante** y agregarle una fila es una decisión de la matriz, no de
una etapa — el mismo razonamiento con el que 01.03 se negó a inventar un código de edición de
organización (§9.2). La propuesta quedó escrita acá, sin aplicar.

**La decisión.** Aprobada por el usuario (Exequiel Santoro, dueño del producto) el
**25/08/2026**, sobre la propuesta que 02.02 dejó escrita en esta sección. La fila vive desde
entonces en el catálogo §5 y en la asignación base §6, que es donde vive lo aprobado; acá queda
solo el registro de la decisión.

| Código | Acción | Fase |
|---|---|---|
| `espacio:read` | Consultar el catálogo físico y la disponibilidad de una sede | F2 |

| Permiso | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| `espacio:read` | Soporte | Org | Consultorio | Consultorio | Consultorio | — |

`Soporte` para `PLATFORM_ADMIN` por el criterio ya fijado en §9.7 —*¿la operación deja por sí
misma una fila que diga quién la hizo y por qué?*—: es una lectura de datos de un tenant y una
lectura no deja ninguna. No es decorativo: **sin `support_access` vigente da 403, y con él
escribe `SUPPORT_ACCESS_USED`** en la auditoría del tenant leído.

**Lo aplicado.** Las tres lecturas de `EspacioController` —detalle, listado y disponibilidad—
exigen `espacio:read` con la sede como alcance. La comprobación de **pertenencia sigue primero**
y el permiso va después: un tenant ajeno tiene que salir por 404 y el evaluador de permisos
responde 403, así que invertir el orden convertiría la lectura en un oráculo de existencia de
organizaciones. El `PLATFORM_ADMIN` pasa por el mismo código —antes pasaba por `tenant:read`—.
**El contrato no cambia**: los códigos HTTP de rechazo (404 fuera de alcance, 403 sin contexto o
sin permiso) son los mismos.

**Efecto colateral que hay que decir:** una membership con rol `PACIENTE` antes leía el catálogo
físico por pertenencia y ahora recibe 403, porque su celda es `—`. Es el comportamiento que la
matriz pide y el motivo por el que la enmienda hacía falta.

### 10.2 Lo que 02.02 NO habilita — dicho para que nadie lo asuma

- **`PLATFORM_ADMIN` no puede mutar espacios.** No tiene contexto de tenant y la mutación lo
  exige, así que recibe 403. Es deliberado: el catálogo físico de un centro es del centro, y no
  existe ninguna operación de rescate que requiera crear un box ajeno. Se aparta de la columna
  `Global` que la sección 6 le da a *Gestionar consultorio*, y se aparta **hacia el lado que no
  concede de más**, igual que §9.7.
- **La mutación exige que la sede de la ruta sea la del contexto validado**, incluso para un
  `ORG_ADMIN`, que tiene alcance organización. Es más estricto que la matriz: la sede del
  contexto es la única que el sistema revalidó contra la base en ese request. Un `ORG_ADMIN` que
  quiera administrar otra sede cambia de contexto primero.
