# AKINE DU-6 — `paciente:read` solo para el personal (DP-22)

Rama `akine-DU-6-paciente-read` · contrato **0.80.0** · sin migración (`V86` reservada y vacía).
Decisión del dueño del producto del 08/10/2026, registrada como DP-22 en
`docs/producto/AKINE_IMPLEMENTATION_PLAN.md`. Enmienda §15 de `docs/seguridad/matriz-permisos-minima.md`.

## 1. El problema

Las lecturas de `person` se autorizaban por **pertenencia**: `AutorizacionDePadron.exigirContexto`
solo pedía contexto de organización. Cualquier membership vigente —incluida una con rol
`PACIENTE`— buscaba en el padrón entero, abría fichas y 360 ajenos, veía coberturas con el número
de afiliado, órdenes, autorizaciones y descargaba documentos. Era el hueco funcional del
`CLAUDE.md` y el hallazgo alto de 07.07.

## 2. La decisión y cómo queda

`paciente:read` nuevo en `organization.domain.PermissionCode`, con asignación base:

| `PLATFORM_ADMIN` | `ORG_ADMIN` | `CONSULTORIO_ADMIN` | `PROFESIONAL` | `ADMINISTRATIVO` | `PACIENTE` |
|---|---|---|---|---|---|
| — | Organización | Consultorio | Consultorio | Consultorio | — |

`AutorizacionDePadron.exigirContexto` pasa a ser privado y su único uso es
`exigirLecturaDelPadron(permissionGuard, actor, operacion)`, que exige el contexto **y** evalúa
`paciente:read` con la sede del contexto. Los **dieciséis** GET de `person` pasan por ahí:

| Controller | Operaciones |
|---|---|
| `PersonaController` | `buscarPersonas` (incluye la búsqueda por afiliado, `q`), `verPersona`, `verResumenDePersona` |
| `CoberturaPacienteController` | `listCoberturasDePaciente`, `resolverCoberturaParaAtencion` |
| `CoberturaParaOfertaController` | `resolverCoberturaAplicablePorOferta` |
| `OrdenMedicaController` | `listOrdenesDePaciente` |
| `AutorizacionController` | `listAutorizacionesDePaciente`, `getAutorizacion`, `listAutorizacionesElegibles` |
| `ConsumoDeAutorizacionController` | `consultarSaldoDeAutorizacion`, `listMovimientosDeAutorizacion`, `listAlertasDeAutorizacion` |
| `ElegibilidadController` | `consultarElegibilidadAdministrativa` |
| `AdjuntoController` | `listarAdjuntosDePersona`, `descargarAdjuntoDePersona` |

La sección `coberturas` del 360 (`CoberturasEnElResumenDePersona`) declara `paciente:read`: el
mismo permiso que `GET .../coberturas`, ni más estricto ni más laxo.

Los `spi` de `person` que consumen otros módulos (`PacienteDirectory`, `ElegibilidadAdministrativaDirectory`,
`CoberturasAplicablesDirectory`, contribuyentes del 360, etc.) **no cambian**: no autorizan nada,
quien los llama ya resolvió su permiso. Por eso recepción (`turno:read`), cobros
(`cobro:register`), presentaciones (`cobro:register`) y la agenda que muestra el nombre del
paciente siguen igual.

## 3. Por qué el `PROFESIONAL` lo tiene por base

La celda "Según permiso" de §32 es la de **gestionar** paciente, y por eso `paciente:manage` le
llega solo por grant. Leer es otra cosa: el profesional que atiende necesita la ficha, la
cobertura, la orden y el saldo de la autorización. Dárselo por grant habría dejado sin padrón a
todo profesional de un despliegue nuevo.

## 4. Sin migración

El catálogo de permisos y su asignación por rol viven en código (`PermissionCode`,
`RolePermissions`). La única columna de la base que guarda códigos es
`membership_grant.permission_code`, un `VARCHAR(48)` sin CHECK, y `paciente:read` no es otorgable
por grant. `V86` queda vacía y no se reusa (§6 del plan de trabajo en paralelo).

## 5. Contrato 0.80.0

Solo descripciones: el 403 de los dieciséis GET pasa de "Sin contexto de trabajo activo" a "…, o
sin `paciente:read`". Los 403 ya estaban declarados, no cambia ningún schema ni ningún
`operationId`. Aditivo.

## 6. Design challenge (CLAUDE.md §3)

1. **Ownership.** Ninguna tabla nueva. El código de permiso es de `organization`; `person` lo usa
   como literal (`person.domain.PermissionCodes.PACIENTE_READ`), igual que `paciente:manage`.
2. **Ciclos.** Ninguna arista nueva: `person → organization.spi.PermissionGuard` ya existía (las
   mutaciones lo usan). Tres servicios (`ResumenDePersonaService`, `CoberturaParaOfertaService`,
   `ElegibilidadAdministrativaService`) reciben el `PermissionGuard` por constructor.
3. **Tenant.** Sin tablas nuevas. La organización sigue saliendo del contexto; el permiso se evalúa
   sobre esa organización y la sede del contexto.
4. **Reglas maestras.** No toca HC/Caso/Sesión ni Turno/Sesión ni Obligación/Cobro/Caja. El 360
   sigue sin mostrar casos.
5. **Baja lógica.** No hay borrados.
6. **Contrato.** Aditivo (descripciones). Para un cliente es incompatible en comportamiento solo
   para el rol `PACIENTE`, que es justo lo que se quiere cortar.
7. **Ruta crítica.** El evaluador de permisos, el guard y los alcances ya existían.
8. **El caso que rompe el diseño.** Un `ADMINISTRATIVO` sin sede en el contexto recibiría 403 al
   buscar —su alcance es de consultorio—. No ocurre: `TenantContextFilter` responde
   `missing-tenant-context` antes de publicar un contexto sin sede, y los ITs lo prueban con las
   tres cuentas de sede. El segundo caso adverso era romper el profesional que atiende: por eso
   lo tiene por base, y `PacienteReadIT` lo verifica con una cuenta `PROFESIONAL` real.

## 7. Lo que queda abierto

- **El autoservicio del paciente (`OWN`)**, con el vínculo cuenta↔persona: después del MVP.
- **Frontend.** `appKine-web` no necesita ocultar nada para el personal (todos los roles de staff
  tienen el permiso). Si algún día un `PACIENTE` entra al panel, las pantallas del padrón van a
  recibir 403: conviene ocultar el menú de Personas cuando `/me/permissions` no trae
  `paciente:read`.
