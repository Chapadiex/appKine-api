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
| `paciente:read` | Ver el padrón y los datos administrativos de las personas (DP-22, §15) | F3 (DU-6) |
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
| ~~Catálogo definitivo de restricciones "Limitado" en reportes~~ | F8 — **cerrado por AKINE-G-1** (DP-15), ver §14 |
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

## 11. Enmiendas — AKINE-02.05 (Especialidades, prácticas y nomencladores)

Mismo criterio que las secciones 9 y 10: nada se aplicó en silencio. Cada punto es una diferencia
real entre lo que dicen las secciones 1–8 y lo que el código hace, con su motivo y su alcance.

### 11.1 `catalogo:read` y `catalogo:manage` — **PROPUESTOS, sin aplicar**

**El hueco que los origina, en una línea.** M06 introduce cuatro tablas de catálogo clínico
—especialidad, práctica, nomenclador y sus vigencias— y el catálogo de la sección 5 **no tiene
ningún código para ellas**. Ninguno de los existentes describe la acción: `consultorio:manage` es
la administración de una sede y un catálogo clínico no es configuración de una sede —es de la
organización, o de la plataforma—; `espacio:read` es el catálogo **físico**; `convenio:manage` es
de M16 y es F3.

**Lo que 02.05 hizo mientras tanto, y por qué no inventó los códigos.** El catálogo de la sección
5 es **vinculante** y agregarle una fila es una decisión de la matriz, no de una etapa — el mismo
razonamiento con el que 01.03 se negó a inventar un código de edición de organización (§9.2) y
con el que 02.02 dejó `espacio:read` propuesto en §10.1 sin aplicarlo. La autorización interina
es:

| Operación | Autorización interina de 02.05 | Código propuesto |
|---|---|---|
| Leer el catálogo (búsqueda, detalle, vigencias) | **Pertenencia**: cualquier membership vigente en la organización del contexto | `catalogo:read` |
| Crear, editar y dar de baja un concepto **contextual** | `consultorio:manage` sobre la sede del contexto | `catalogo:manage` |
| Crear, editar y dar de baja un concepto **global** | Rol de plataforma, sin permiso de tenant de por medio | `catalogo:manage` con alcance Global |
| Solicitar el alta de un concepto global (RF-M06-005) | `consultorio:manage` sobre la sede del contexto | `catalogo:manage` |
| Resolver una solicitud | Rol de plataforma | `catalogo:manage` con alcance Global |

**La propuesta.** Dos códigos, no uno: leer el catálogo lo necesita todo el equipo clínico y
administrativo —sin eso, un profesional no puede elegir la práctica que acaba de hacer—, y
administrarlo no.

| Código | Acción | Fase |
|---|---|---|
| `catalogo:read` | Consultar el catálogo clínico —especialidades, prácticas, nomencladores y vigencias— global y propio | F2 |
| `catalogo:manage` | Administrar los conceptos del catálogo clínico y las solicitudes de alta | F2 |

| Permiso | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| `catalogo:read` | Global | Org | Consultorio | Consultorio | Consultorio | — |
| `catalogo:manage` | Global | Org | Consultorio | — | — | — |

**Por qué `Global` para `PLATFORM_ADMIN` y no `Soporte`, que es lo contrario de lo que §10.1
decidió para `espacio:read`.** No es una excepción al criterio de §9.7 —*¿la operación deja por
sí misma una fila que diga quién la hizo y por qué?*—: es que **la pregunta no aplica**. En este
módulo el administrador de plataforma **no lee ni escribe datos de ningún tenant**: lo único que
alcanza es el catálogo **común**, que no es de nadie en particular y que ya ven todos los
tenants. `support_access` protege el acceso al dato de un cliente; acá no hay dato de un cliente
al que acceder. Es el mismo tratamiento que el catálogo de planes (`plan`, `plan_limit`,
`plan_feature`), que tampoco pasa por soporte.

**Diferencia entre lo interino y lo propuesto, dicha para que la aprobación sea informada:**

- Un `PACIENTE` con membership vigente **hoy lee el catálogo por pertenencia** y con
  `catalogo:read` aprobado recibiría 403. Es el mismo efecto colateral que §10.1 declaró para
  `espacio:read`, y el motivo por el que la enmienda hace falta.
- `consultorio:manage` como permiso interino de mutación **le da al `CONSULTORIO_ADMIN` un
  alcance que el catálogo no tiene**: el catálogo es de la organización, no de la sede, así que
  el administrador de una sede puede crear un concepto que las demás sedes del mismo tenant van a
  ver. Con `catalogo:manage` la fila `Consultorio` conserva ese comportamiento a propósito —la
  etapa lo pide de frente: *"admin consultorio para conceptos contextuales/solicitudes"*— pero
  queda dicho que es una elección y no una consecuencia.
- Nada más cambia. **Los códigos HTTP de rechazo son los mismos** antes y después de la
  aprobación: 404 fuera de alcance, 403 sin contexto o sin el permiso.

### 11.2 Lo que 02.05 NO habilita — dicho para que nadie lo asuma

- **`PLATFORM_ADMIN` no ve ni muta conceptos contextuales de ningún tenant.** No es un olvido: el
  catálogo propio de un centro es información comercial suya, no existe ninguna operación de
  rescate que exija tocarlo, y concederlo obligaría además a exigir `support_access` y a auditar
  cada lectura. Se aparta hacia el lado que no concede de más, igual que §10.2.
  Consecuencia práctica: la bandeja de solicitudes (`GET /api/v1/catalogo-solicitudes`) es la
  **única** consulta cross-tenant del módulo, y devuelve el pedido —nombre propuesto,
  justificación— y no el catálogo del centro.
- **`PLATFORM_ADMIN` no puede pedir el alta de un concepto global.** No se pide conceptos a sí
  mismo: los crea. Recibe 403.
- **Un administrador de tenant no puede promover su concepto a global**, ni al crearlo ni
  editándolo. El camino es la solicitud, y la decide la plataforma.
- ~~**Aprobar una solicitud no crea el concepto global.** La aprobación es una decisión registrada;
  la publicación del concepto pasa por el alta normal, con el rol de plataforma.~~ **Cambiado por
  AKINE-A-7 (07/10/2026):** aprobar **publica** el concepto global en la misma transacción, con el
  código, nombre y descripción que fija la plataforma en la resolución (los propuestos por el centro
  son solo el default), por el mismo alta global y con el mismo rol de plataforma. Si la publicación
  choca, la solicitud sigue pendiente. **No cambia ningún permiso**: resolver sigue siendo del rol de
  plataforma y la bandeja sigue sin pasar por soporte. Ver `docs/diseno/AKINE-A-7-plataforma.md` §2.
  **Confirmado por el dueño del producto el 08/10/2026 (DP-18):** la enmienda queda firme.

---

## 12. Enmiendas — AKINE-03.01 (Persona y PerfilPaciente)

`paciente:manage` figuraba en §5 desde el primer día con fase destino F3, y en el código con la
nota *"sin asignación base todavía: deniega"*. Esta etapa creó el módulo que lo evalúa y le dio la
asignación que §4 ya le daba. **No hay ningún código de permiso nuevo.**

### 12.1 La fila "Gestionar paciente" de §4, cableada literalmente

| Permiso | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| `paciente:manage` | **Soporte** | Org | Consultorio | — (solo por grant) | Consultorio | — |

- **`PLATFORM_ADMIN` con Soporte y no Global.** §4 lo dice literalmente, y coincide con el
  invariante de §7: el padrón de personas es exactamente el dato que esa sección protege. Es una
  mutación —y las mutaciones suelen quedar Global porque dejan su propia fila nominal—, pero acá
  manda lo que la matriz ya había decidido. `PersonaService` deja `SUPPORT_ACCESS_USED` cuando la
  decisión vuelve con `viaSupportAccess`.
- **`PROFESIONAL` solo por grant.** §4 le dice "Según permiso", que significa no por defecto y sí
  por concesión explícita. Para que esa celda tuviera alguna forma de cumplirse hubo que sumar
  `paciente:manage` a los códigos otorgables como grant: hasta esta etapa el único era
  `auditoria:read-clinica`, y cualquier otro se rechazaba con 400.
- **`PACIENTE` no recibe nada.** Su celda es "Propio", o sea alcance `OWN`, y ese alcance **no
  está implementado en ninguna parte del sistema**: no existe vínculo entre una cuenta y una
  persona, justamente porque RN-M07-002 los separa y ese vínculo es de la etapa de autoservicio.

### 12.2 El permiso se evalúa CON la sede del contexto, aunque la Persona sea de la organización

Es la decisión menos obvia de la etapa. Una `persona` no tiene `consultorio_id`: pertenece a la
organización entera. El reflejo es evaluar con `consultorioId = null`, y **eso rompe la matriz**:
`PermissionEvaluatorService.alcanceCubre` concede un alcance de sede solo cuando la consulta
nombra una sede, así que con la consulta sin sede pasarían `ORG_ADMIN` y plataforma, y quedarían
afuera `CONSULTORIO_ADMIN` y `ADMINISTRATIVO` — a quienes §4 les dice "Sí". El recepcionista no
podría dar de alta a nadie.

Por eso la consulta lleva la sede del contexto activo. Consecuencia: **sin contexto de sede no se
muta el padrón** (403), igual que ofertas y disponibilidad. La persona no queda atada a esa sede.

### 12.3 Las lecturas se autorizan por pertenencia, y eso concede de más

> **Superada el 08/10/2026 por DP-22 (§15).** Existe `paciente:read`, solo para el personal, y el
> rol `PACIENTE` recibe 403 en todas las lecturas de `person`. Lo que sigue queda como historia.

**No existe `paciente:read`** y esta etapa no lo crea: la matriz no lo declara y una etapa no
amplía la matriz (mismo criterio que 02.04, 02.05 y 02.06). Leer el padrón exige solo tener
contexto de organización activo.

**El hueco, dicho de frente:** con pertenencia sola, una membership con rol `PACIENTE` lee el
padrón entero de su organización. §4 le asigna "Propio" a esa celda. Aprobar un `paciente:read`
**no lo resolvería**: el problema no es el código de permiso sino el alcance `OWN`, que no existe.
Queda como hueco conocido con etapa destino en el autoservicio, y no se tapa con un permiso que no
cambiaría ningún comportamiento.

## 13. Enmiendas — AKINE-05.04 reducida (Recepción y check-in)

### 13.1 La recepción no estrena ningún permiso, y eso es la decisión

El check-in y la agenda del día se autorizan con los permisos que **ya existían** para turnos:
`turno:read` para leer la agenda del día y un turno suelto, `turno:manage` para registrar la
llegada y deshacerla.

**No se creó `recepcion:*` a propósito.** Un permiso nuevo por pantalla es la forma más rápida de
volver la matriz inauditable: quien administra un centro tendría que entender qué es "recepción"
como concepto separado de "turnos" para decidir a quién dárselo, cuando en los hechos es el mismo
trabajo sobre las mismas filas. El check-in **es una transición del Turno**, y quien puede
cancelar un turno con más razón puede decir que el paciente llegó.

Consecuencia práctica, que conviene tener presente: **el `PROFESIONAL` no tiene `turno:manage`
por asignación base**, así que no puede marcar llegadas salvo que se le otorgue por grant. Es
coherente con M13 —recepción es trabajo administrativo— pero deja sin resolver el centro de un
solo kinesiólogo que atiende y recibe. Se resuelve con un grant, que es exactamente para lo que
existe.

### 13.2 Lo que 05.04 NO habilita — dicho para que nadie lo asuma

- **No valida cobertura, órdenes ni autorizaciones.** Es recableo de DP-10 y no un olvido: 03.06 y
  04.05 quedaron fuera de alcance y con cobertura PARTICULAR única no hay nada que validar. El día
  que existan, la validación es de esta misma transacción y **puede exigir permisos nuevos** —leer
  una autorización no es leer un turno—.
- **No expone ningún dato clínico.** La respuesta de recepción lleva nombre, documento y nombre
  comercial de la oferta. Quien atiende el mostrador no necesita saber por qué viene el paciente,
  y `TurnoDelDiaResponse` es un tipo aparte justamente para que nadie le agregue un campo clínico
  "de paso".
- **No hay estado de atendido.** Un turno que pasó por recepción queda `EN_ESPERA` hasta que se
  cancele o se deshaga el check-in; que la prestación ocurrió lo dice la Sesión y sólo ella.
---

## 13. Enmiendas — AKINE-03.03 (Financiadores y planes de cobertura, M15)

### 13.1 La fila "Administrar Convenios" de §2, cableada parcialmente

`convenio:manage` estaba en el catálogo de §5 desde AKINE-01.03, declarado para **F3**, y **no lo
tenía ningún rol**: denegaba siempre porque no existía módulo que lo evaluara. Con `contracting`
(M15) ya existe, y la asignación base pasa a ser:

| Permiso | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| `convenio:manage` | — (ver 13.2) | Org | Consultorio | — | — | — |

Es la fila "Administrar Convenios" de §2 leída literalmente: "Sí" al `ORG_ADMIN` y al
`CONSULTORIO_ADMIN`, "No" a los otros tres. **No se agrega ningún código nuevo**: una etapa no
amplía el catálogo de §5, mismo criterio que 02.04, 02.05, 02.06 y 03.01.

### 13.2 El `PLATFORM_ADMIN` NO lo recibe, y eso es la decisión más importante de esta enmienda

Su celda dice **"Catálogo global"**, que §3 define como *"solo sobre el catálogo de plataforma
(financiadores/planes globales), nunca sobre convenios de un tenant"*.

**Ese catálogo global no existe.** AKINE-03.03 modela el financiador como dato de la
**organización** (`financiador.organization_id NOT NULL`, V41). Darle `convenio:manage` hoy no
cumpliría su celda: la **violaría**, porque lo dejaría administrar los financiadores de un tenant
—exactamente lo que su celda excluye—.

Queda **sin cumplirse**, declarado, hasta que exista la población global. El camino de migración
está escrito en la cabecera de V41: `organization_id` pasa a nullable y aparece el centinela
`owner_key`, que es el patrón de `especialidad` y `practica` (ADR-0021). El bloqueo práctico para
construirlo es el mismo que arrastra RF-M06-005 desde 02.05: **ningún endpoint le dice al frontend
si quien mira tiene rol de plataforma**, así que la consola de plataforma no se puede construir.

### 13.3 El permiso se evalúa CON la sede del contexto, aunque el financiador sea de la organización

Idéntico a 12.2 y por el mismo motivo mecánico: `PermissionEvaluatorService.alcanceCubre` concede
un alcance de sede **solo cuando la consulta nombra una sede**. Evaluando con `consultorioId =
null` pasarían `ORG_ADMIN` y plataforma y quedaría afuera el `CONSULTORIO_ADMIN`, a quien §2 le
dice "Sí".

Consecuencia: **sin contexto de sede no se muta el catálogo** (403). El financiador no queda atado
a esa sede — sigue siendo de la organización.

### 13.4 Las lecturas se autorizan por pertenencia, y eso concede de más

**No existe `convenio:read`** y esta etapa no lo crea. Leer el catálogo de financiadores y planes
exige solo tener contexto de organización activo.

**El hueco, dicho de frente:** con pertenencia sola, una membership con rol `PACIENTE` lee el
catálogo entero de financiadores de su organización. Es el mismo hueco que 12.3 dejó abierto en el
padrón y tiene la misma causa de fondo — el alcance `OWN` no existe— así que se declara igual y no
se tapa con un permiso que no cambiaría ningún comportamiento.

---

## 14. Enmiendas — AKINE-G-1 (`reporte:read`, DP-15)

`reporte:read` estaba en §5 desde 00.03 con fase F8 y **no lo tenía ningún rol**: los tres
endpoints de `reporting` (catálogo, reporte, CSV) respondían 403 a todo el mundo desde 07.06. La
decisión del dueño del producto del **07/10/2026 (DP-15)** fue otorgarlo **completo según la fila
"Ver Reportes" de §2**. No hay ningún código de permiso nuevo; sí un **alcance** nuevo.

### 14.1 La fila "Ver Reportes", cableada

| Permiso | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| `reporte:read` | **Soporte** | Org | Consultorio | **Actividad propia** | Consultorio | — |

No es otorgable por grant: el grant hereda el alcance de la membership y se saltearía el recorte.

Dentro de cada reporte, **cada sección sigue pidiendo el permiso de su fuente** (07.06): `turnos`
pide `turno:read`, `sesiones` `sesion:register`, `casos` `hc:read`, `economia` y `financiadores`
`cobro:register`. Una sección sin su permiso se omite y se declara en `omitidas`; no hay 403 sobre el
tablero entero.

### 14.2 Catálogo definitivo de restricciones "Limitado" en reportes (hueco de §8, cerrado)

| Celda | Restricción de §4 | Cómo se hace cumplir |
|---|---|---|
| Ver Reportes — `ADMINISTRATIVO` | Solo operativos y de caja, sin contenido clínico | **Por el permiso de sección, sin alcance especial.** No tiene `hc:read` ni `sesion:register`: `sesiones` y `casos` se omiten y quedan declaradas. Ve turnos y economía de su sede |
| Ver Reportes — `PROFESIONAL` | Solo reportes de su propia actividad | **Alcance `ACTIVIDAD_PROPIA`.** Turnos y sesiones cuyo `profesional_membership_id` es una de sus memberships; casos de cuyo equipo tratante forma o formó parte. Lo económico no le llega: no tiene `cobro:register`. Una sección que no sabe recortarse a la actividad propia **se omite y se declara** con `permisoRequerido = reporte:read` |

### 14.3 `ACTIVIDAD_PROPIA` no es `OWN`

La §3 traduce "Propio" a alcance `OWN`: las entidades **del propio paciente**, que dependen de un
vínculo cuenta↔persona que no existe. La actividad **profesional** del actor es otra cosa y se
resuelve por sus memberships, que sí existen. Reusar `OWN` habría atado el reporte del profesional
a un hueco ajeno. El evaluador trata `ACTIVIDAD_PROPIA` como `CONSULTORIO` para decidir qué sede
cubre y lo informa en `grantedByScope`; **las filas las recorta quien lee el dato**. Hoy lo usa
únicamente `reporte:read`.

### 14.4 `PLATFORM_ADMIN`: "Global" leído como Soporte

Mismo criterio de §9.7, sin excepción: un reporte es una **lectura** de datos de un tenant —agenda,
caja, actividad— y una lectura no deja por sí misma ninguna fila que diga quién la hizo. Sin
`support_access` vigente da 403; con él, cada consulta del catálogo o de un reporte deja
`SUPPORT_ACCESS_USED`. Lo que ve lo sigue recortando cada sección: turnos sí (`turno:read` es
Soporte), lo clínico no (`hc:read` es Restringido) y lo económico tampoco (no tiene `cobro:register`).

## 15. Enmiendas — AKINE-DU-6 (`paciente:read`, DP-22)

La decisión del dueño del producto del **08/10/2026 (DP-22)** cierra el hueco de §12.3: se crea
`paciente:read` **solo para el personal**, y el rol `PACIENTE` pierde todo acceso al padrón y a
los datos de otras personas. **Hay un código de permiso nuevo**; el alcance `OWN` —el "Propio" de
la columna `PACIENTE`, el paciente sobre sus propios datos— **no** se implementa y queda para
después del MVP.

### 15.1 La fila, cableada

| Permiso | `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|---|
| `paciente:read` | — | Org | Consultorio | **Consultorio (base)** | Consultorio | **—** |

- **`PROFESIONAL` por base y no por grant**, a diferencia de `paciente:manage`. "Según permiso" es
  la celda de *gestionar*; para atender hay que poder ver al paciente, su cobertura, su orden y su
  autorización. Darle el permiso solo por grant habría dejado sin ficha a todo profesional.
- **`PACIENTE` sin nada.** Su celda es "Propio" y el alcance `OWN` necesita el vínculo
  cuenta↔persona (RN-M07-002), que es de la etapa de autoservicio. Hasta entonces, 403.
- **`PLATFORM_ADMIN` sin nada, y no cambia ningún comportamiento**: `TenantContextFilter` no le
  publica contexto de tenant, así que ninguna lectura de `person` le era alcanzable. Si ese camino
  se abre, entra con `SOPORTE` y deja `SUPPORT_ACCESS_USED`, como `paciente:manage` (§12.1).
- **No es otorgable por grant**: todo el personal ya lo tiene por base.

### 15.2 Qué gobierna

Todas las lecturas de `person`, sin excepción: búsqueda del padrón (también por número de
afiliado), ficha, Paciente 360, coberturas y cobertura para la atención, cobertura aplicable por
oferta, órdenes, autorizaciones (lista, detalle, elegibles, saldo, ledger y alertas), elegibilidad
administrativa y adjuntos administrativos (lista y descarga). Se evalúa **con la sede del
contexto**, por el mismo motivo de §12.2. Pertenecer sin el permiso es **403**; una persona de otra
organización sigue siendo **404**, porque la organización sale del contexto.

La sección `coberturas` del Paciente 360 declara `paciente:read` como su permiso requerido —el
mismo que `GET .../coberturas`—. Como el 360 entero ya lo exige, hoy no se omite nunca.

Las lecturas de **otros módulos** que muestran datos de personas ya pedían su propio permiso, que
`PACIENTE` no tiene: agenda y recepción `turno:read`, deuda y cobros `cobro:register`,
presentaciones `cobro:register`, historia clínica `hc:read`, inscripciones
`inscripcion:read`, reportes `reporte:read`. Ninguna se autorizaba solo por pertenencia.


## 16. Enmiendas — AKINE-G-5 (grants por rol y matriz ejecutable)

No hay ningún código de permiso nuevo ni cambia ninguna asignación base. Lo que cambia es **a quién
se le puede otorgar** un permiso por grant.

### 16.1 Una celda "No" no es otorgable, aunque el código lo sea para otro rol

**El hueco.** `otorgablesComoGrant()` era una lista de **códigos**: cualquier código de la lista se le
podía otorgar a **cualquier** rol. La §3 dice lo contrario —*"No: denegado. No otorgable por
membership"*— y la consecuencia era concreta: un administrador podía darle `hc:write` al
`ORG_ADMIN` (Editar HC: "No"), `hc:read` completo al `ADMINISTRATIVO` (Ver HC: "Limitado", que §4
define como solo metadatos y nunca contenido clínico), `paciente:manage` o `hc:read` al `PACIENTE`
("Propio"/"Propia autorizada", alcance `OWN` sin implementar), o `auditoria:read-clinica` al
`PROFESIONAL` (§6: "—"). `MatrizDePermisosIT` lo reprodujo por HTTP: los siete grants respondían
201.

**La regla, celda por celda** (`RolePermissions.otorgableComoGrant`):

| Permiso | Otorgable por grant a | Fuente |
|---|---|---|
| `auditoria:read-clinica` | `ORG_ADMIN`, `CONSULTORIO_ADMIN` | §6 "No por defecto (grant)" |
| `paciente:manage` | `PROFESIONAL` | §2 "Según permiso" |
| `hc:read` | `ORG_ADMIN`, `CONSULTORIO_ADMIN` | §2 "No por defecto", "Según rol clínico" |
| `hc:write` | `CONSULTORIO_ADMIN` | §2 "No por defecto" |

Un permiso que el rol ya tiene por base también se acepta (redundante e inocuo). Cualquier otra
combinación responde **400** con el rol nombrado en el detalle.

**El evaluador aplica la misma regla al leer.** Un grant que el rol **actual** de la membership no
admite no concede nada: cubre las filas otorgadas antes de G-5 y la membership que cambia de rol
después del grant (un `CONSULTORIO_ADMIN` con `hc:write` que pasa a `ADMINISTRATIVO`). La fila no se
borra —baja lógica, y además es historia— y sigue apareciendo en `GET .../grants`.

**Efecto colateral sobre `caso:create`.** Abrir un caso se autoriza con `hc:write`. Con esta regla,
quien puede abrir un caso es exactamente quien la fila "Crear Caso Clínico" de §2 habilita: el
`PROFESIONAL` por base y el `CONSULTORIO_ADMIN` por grant ("Según rol clínico"). Si `caso:create`
se cablea como código propio o se enmienda la matriz para absorberlo en `hc:write` sigue siendo una
decisión del dueño del producto.

### 16.2 La matriz, ejecutable

`MatrizDePermisosIT` recorre por HTTP real, con los cinco roles de membership, las familias
críticas: personas, historia clínica, sesiones, cobros, caja, egresos, presentaciones, reportes,
colaboradores y auditoría. Para cada celda espera lo que dicen §2, §6 y las enmiendas: el código de
éxito si el rol tiene el permiso y **403** si no. Un endpoint que autorice por pertenencia o con el
permiso de otra familia rompe ese test, aunque `RolePermissionsTest` siga verde.
