# AKINE G-1 — `reporte:read` según la matriz (DP-15)

> Paquete G-1 de `docs/fases/01-trabajo-en-paralelo.md`. Ficha: `docs/fases/F8-cierre-del-mvp.md`,
> defecto "nadie tiene `reporte:read`". Decisión del dueño del producto: **DP-15** (07/10/2026),
> que resuelve DU-3.

## 1. El problema

`reporte:read` está en el catálogo desde 00.03 (`PermissionCode.REPORTE_READ`) y `ReporteService`
lo exige en las tres operaciones de `reporting` —catálogo, reporte y CSV—, pero **ningún rol lo
tenía** en `RolePermissions`. Los tres endpoints respondían 403 a todo el mundo desde 07.06.

## 2. Lo que dice la matriz y cómo queda

Fila "Ver Reportes" de la matriz §2, con la semántica de §3 y las restricciones "Limitado" de §4:

| Rol | Matriz | Alcance en `RolePermissions` | Cómo se cumple |
|---|---|---|---|
| `ORG_ADMIN` | Sí | `ORGANIZACION` | Ve todo lo de su alcance que el permiso de cada sección le deja ver |
| `CONSULTORIO_ADMIN` | Sí | `CONSULTORIO` | Ídem, en su sede |
| `ADMINISTRATIVO` | Limitado: "solo operativos y de caja, sin contenido clínico" | `CONSULTORIO` | **Sin alcance especial.** No tiene `hc:read` ni `sesion:register`: `sesiones` y `casos` se omiten por el recorte por sección que ya existía y quedan declaradas en `omitidas` |
| `PROFESIONAL` | Limitado: "solo reportes de su propia actividad" | **`ACTIVIDAD_PROPIA`** (nuevo) | Turnos, sesiones y casos se recortan a lo que atendió o le fue asignado. Lo económico ya se omite: no tiene `cobro:register` |
| `PACIENTE` | No | — | 403 |
| `PLATFORM_ADMIN` | Global | **`SOPORTE`** | Ver §4 |

No es otorgable por grant: un grant hereda el alcance de la membership y se saltearía el recorte.

## 3. La actividad propia del profesional

### Por qué un alcance nuevo y no `OWN` ni `CONSULTORIO`

- `CONSULTORIO` le mostraría la agenda de todo el equipo: tiene `turno:read` de sede.
- `OWN` es el "Propio" **del paciente** (matriz §3): las entidades de la persona que es el actor.
  Depende de un vínculo cuenta↔persona que no existe y está citado como hueco en media docena de
  servicios. Reusarlo mezclaría dos semánticas y cualquier implementación futura de `OWN` para
  pacientes rompería el reporte del profesional, o al revés.
- `ACTIVIDAD_PROPIA` dice exactamente la regla de §4. El evaluador lo trata como `CONSULTORIO`
  para decidir **qué sede** alcanza y lo devuelve en `grantedByScope`; **qué filas** las recorta
  quien lee el dato. Es el mismo reparto que ya usa `AuditQueryService` con `CONSULTORIO`.

Que un rol de sede reciba `ACTIVIDAD_PROPIA` y no otra cosa lo sostiene `RolePermissionsTest`; que
el nombre del enum coincida con la constante que `reporting` compara
(`PermissionDecision.ALCANCE_ACTIVIDAD_PROPIA`) también.

### Qué es "su actividad"

| Sección | Módulo | Se cuenta si… |
|---|---|---|
| `turnos` | `scheduling` | `turno.profesional_membership_id` es suya |
| `sesiones` | `encounter` | `sesion.profesional_membership_id` es suya (quien atiende y firma) |
| `casos` | `clinical` | integra **o integró** el equipo (`caso_profesional`, cualquier vigencia) |

"Suya" = cualquiera de las memberships de su cuenta en la organización (`ConsultorioMembershipDirectory.findByAccount`),
no sólo la que gobierna la decisión: lo atendido con una membership que después se cerró sigue
siendo su actividad, y la sede ya la recorta cada consulta. Un turno sin profesional no es actividad
de nadie. En casos se usa `EXISTS` y no `JOIN`: quien entró, salió y volvió tiene dos filas.

### Cómo viaja, sin ciclos

```
reporting.application.ReporteService
   ├─ organization.spi.PermissionGuard            → decisión con grantedByScope
   ├─ organization.spi.ConsultorioMembershipDirectory → memberships de la cuenta
   └─ reporting.spi.ConsultaDeReporte(actividadPropiaDe = {ids})
          ↑ implementan reporting.spi.ReporteContributor (dependencia invertida, como en 07.06)
      scheduling / encounter / clinical
```

`reporting → organization.spi` ya existía; los contribuyentes ya dependían de `reporting.spi`. No
aparece ninguna arista nueva entre módulos (`ModuleArchitectureTest` en verde).

- `ConsultaDeReporte` gana `Set<Long> actividadPropiaDe` (null = sede entera; si no es null, nunca
  vacío) y conserva el constructor de ocho argumentos, así los contribuyentes de `billing` y sus
  tests no se tocan (F-4 trabaja ahí).
- `ReporteContributor` gana `default boolean filtraPorActividadPropia()` en **`false`**. Con la
  consulta recortada, una sección que no sabe recortar **se omite y se declara** con
  `permisoRequerido = reporte:read`; nunca se calcula entera. Ante la duda, cerrado: una sección
  nueva que se olvide de declararlo no le muestra al profesional la actividad del equipo.
- Las consultas no se duplican: cada una gana `AND (:recortar = false OR x IN :memberships)`. Un
  `IN ()` vacío no es SQL válido en MySQL, así que sin recorte viaja un centinela (`0`) que la
  condición nunca evalúa.
- El reporte limitado lleva la advertencia `alcance-actividad-propia` para que la pantalla no lea
  "12 turnos" como los de la sede, y la auditoría de lectura clínica registra `alcance`.

## 4. `PLATFORM_ADMIN`: "Global" leído como `SOPORTE`

La columna de plataforma de la matriz ya se leyó con un criterio fijo en §9.7: *¿la operación deja
por sí misma una fila que diga quién la hizo y por qué?* Las mutaciones sí y quedan `GLOBAL`; las
lecturas de datos de un tenant no y bajaron a `SOPORTE` (`tenant:read`, `auditoria:read`,
`colaborador:read`, `espacio:read`, `turno:read`, `clase:read`, `inscripcion:read`). Un reporte es
una lectura de la agenda, la caja y la actividad de un tenant: `SOPORTE`.

Sin acceso de soporte vigente da 403; con él, cada `generar` y cada `catalogo` dejan
`SUPPORT_ACCESS_USED` en una transacción propia (`ReportingSupportAccessAuditor`, mismo patrón que
`PersonSupportAccessAuditor`). Lo que ve lo sigue recortando el permiso de cada sección: turnos sí
(`turno:read` es `SOPORTE`), lo clínico (`RESTRINGIDO`) y lo económico (no tiene `cobro:register`)
no.

## 5. Sin migración ni cambio de contrato

- Los permisos base viven en código (`RolePermissions`), no en la base: **sin migración**. `V77`–`V79`
  siguen libres.
- La respuesta no cambia de forma: la advertencia usa la lista que ya existía. **El contrato no
  cambia** y no se sube de versión; `OpenApiContractIT` lo verifica sin drift.

## 6. Design challenge (CLAUDE.md §3)

1. **Ownership.** No hay tablas nuevas. Cada consulta sigue en el módulo dueño de su tabla:
   `turno` en `scheduling`, `sesion` en `encounter`, `caso_clinico` y `caso_profesional` en
   `clinical`. `reporting` sigue sin repositorio.
2. **Ciclos.** Ninguna arista nueva: `reporting → organization.spi` ya existía y los tres
   contribuyentes ya implementaban `reporting.spi`. `ModuleArchitectureTest` 5/5.
3. **Tenant.** Las consultas conservan `organization_id` y la sede; el recorte por membership es
   adicional, nunca reemplaza al filtro de tenant. Las memberships salen de
   `findByAccount(organizationId, accountId)`, acotado al tenant del contexto.
4. **Reglas maestras.** Turno (reserva), Sesión (atención) y Caso siguen en secciones separadas;
   el recorte se aplica a cada una con su propia definición de "lo suyo", sin fundirlas.
5. **Baja lógica.** No hay escritura salvo auditoría. El equipo del caso se lee con su historia
   (incluye a quien dejó el equipo) precisamente porque `caso_profesional` no borra.
6. **Contrato.** Sin cambio. La advertencia nueva viaja en `advertencias`, que ya existía.
7. **Ruta crítica.** `reporting` (07.06) y los tres contribuyentes existen; la matriz y el
   evaluador también. Nada se adelanta.
8. **El caso que rompe el diseño.**
   - *Una sección nueva (p. ej. `activity`) que se suma al reporte operativo y no sabe recortar.*
     Con un default permisivo, el profesional vería la actividad de todo el centro sin que nada
     falle. Por eso el default es `false` y la sección se omite y se declara.
   - *Un recorte vacío* —la membership se revoca entre la decisión y la lectura—. Se corta con 403
     en vez de devolver ceros, y `ConsultaDeReporte` rechaza un recorte vacío por construcción.
   - *Una cuenta `ORG_ADMIN` de organización y `PROFESIONAL` en la sede.* En esa sede gobierna la
     membership más específica (`MembershipSelection`), así que ve su actividad y no la sede. Es el
     criterio ya fijado en 01.03 —la fila de sede existe para decir otra cosa—, no una excepción.

## 7. Decisiones para revisar

1. **Casos: "le fue asignado" incluye a quien dejó el equipo.** Si el producto prefiere sólo el
   equipo vigente, es agregar `cp.hasta IS NULL` en las tres consultas.
2. **Sesiones: sólo las que atendió como profesional de la sesión.** Un co-tratante de un
   tratamiento (`tratamiento_realizado.profesional_membership_id`) no la cuenta como suya.
3. **La advertencia en lugar de un campo de contrato.** Si el tablero (G-8) necesita distinguir el
   alcance de forma estructurada, es un campo aditivo en `ReporteResponse` (minor).
4. **`PLATFORM_ADMIN` en `SOPORTE` y no `GLOBAL`.** La matriz dice "Global"; se aplicó el criterio
   de §9.7, que ya bajó todas las lecturas de esa columna.
