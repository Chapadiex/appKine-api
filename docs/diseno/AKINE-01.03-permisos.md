# AKINE-01.03 — Memberships, roles, permisos y auditoría base

- **Estado:** diseño con las decisiones estructurales cerradas (§12). Quedan abiertas D-3, D-5, D-6, D-7, D-8 y D-9
- **Fecha:** 2026-08-23 · **Última revisión:** 2026-08-23 (cierre de D-1, D-2 y del orden de bloqueo)
- **Etapa del plan:** `AKINE_IMPLEMENTATION_PLAN.md` líneas 928–1006
- **Dependencias:** AKINE-01.02 (cerrada parcialmente: escenario diferido 8 en rojo, 7b diferido a esta etapa)
- **Fuentes normativas:** RF-M01-002, RF-M02-004, RF-M05-001, RF-M05-002, RF-M24-001..004, §32, §33, §34, §35; `docs/seguridad/matriz-permisos-minima.md` (vinculante); ADR-0001, 0003, 0004, 0005, 0006, 0007, 0009, 0019, **0020**
- **Contrato hoy:** 0.3.0 · **Contrato al cierre previsto:** 0.4.0 (aditivo)

Este documento es el paso `design` + `DESIGN CHALLENGE` del ciclo de `CLAUDE.md` §3. No es
implementación: fija qué se construye, dónde vive cada cosa, qué se rompe si se hace distinto,
y qué decisiones necesitan al usuario antes de escribir una línea.

### Decisiones cerradas por el usuario el 23/08/2026

Se registran acá porque cambian secciones enteras del documento, no solo la lista de §12. Cada
una está desarrollada donde corresponde y anotada como cerrada en §12.

| # | Decisión | Dónde impacta |
|---|---|---|
| 1 | **D-2 cerrada, opción A.** `PLATFORM_ADMIN` sale de una tabla `platform_role` propia, y el primero entra por un **seed de migración** con un email concreto versionado en el repositorio. Descartadas la lista en `application.yml` y el comando fuera de HTTP. El usuario aceptó explícitamente el email versionado a cambio de que el alta sea auditable y reproducible | §2.3, §2.7, §3.5, §7.1, **ADR-0020** |
| 2 | **D-1 cerrada, opción B.** Entra **solo asignar y revocar rol** sobre memberships que ya existen. **No entra el flujo de invitación por mail.** Es una **desviación declarada** de RF-M05-001 y RF-M05-002, con etapa destino, no un olvido | §1.1, §1.2, §2.4, §5.2, §6.5, §7.1, §7.2, §10 |
| 3 | **Orden de bloqueo único del sistema: `subscription` → `organization`.** La §6.1 tomaba `X` sobre `organization` como primera sentencia y eso producía un deadlock contra el alta de sede de 02.01, que toma `X` sobre `subscription` dentro del `PlanGate` y después `S` sobre `organization` por la FK. Se reescribe este documento y **no** el `PlanGate`: el gate ya está implementado y probado con hilos reales, y cambiar papel es más barato que cambiar código verificado. Cierra la D-11 de `AKINE-02.01-consultorios.md` | §6.0, §6.1, §6.5, §6.6, §11.8 |

**Lo que 01.02 ya corrigió en el código y este documento no debe volver a proponer:** la
migración `V10` está aplicada en el árbol. Expandió el unique de `membership` con una columna
generada `consultorio_scope BIGINT AS (IFNULL(consultorio_id, 0)) STORED` y
`UNIQUE (organization_id, account_id, consultorio_scope)`, y retiró
`uk_membership_org_account` en la misma migración. El criterio de resolución cuando hay varias
memberships está centralizado en `organization/domain/MembershipSelection.java`: **gana la más
específica**, no la más privilegiada, porque no existe jerarquía de roles hasta esta etapa y
elegirla por privilegio sería inventarla. Ver §2.1 para lo que eso deja pendiente.

---

## 1. Alcance

### 1.1 Entra

| # | Capacidad | Traza |
|---|---|---|
| A-1 | Evaluador de permisos server-side sobre el modelo `base(rol) ∪ grants ∩ alcance` de la matriz §3 | §32; matriz §3 y §6 |
| A-2 | Memberships por consultorio: una cuenta con roles distintos en sedes distintas de la misma organización | RF-M01-002; RN-M02-002; matriz §1.1 |
| A-3 | Máquina de estados de membership (`ACTIVA → SUSPENDIDA/REVOCADA`), con vigencia y sin borrado. El estado `INVITADA` se declara en el catálogo pero **ninguna transición lo produce en 01.03** (D-1) | RF-M01-002; §33; RN-M05-003; matriz §7 |
| A-4 | Asignación y revocación de rol, y grants adicionales, **sobre memberships que ya existen** | RF-M02-004; matriz §3 ("No por defecto", "Según permiso") |
| A-5 | ~~Invitación de colaborador y aceptación/rechazo~~ **Fuera por D-1 (opción B).** Ver §1.2 | RF-M05-001, RF-M05-002 |
| A-6 | Invariantes "último admin", "self-revoke", "el fundador no lo desvincula otro admin" | matriz §7; plan 01.03 "Validaciones" |
| A-7 | Origen de dato productivo de `PLATFORM_ADMIN` y su re-validación por request | plan 01.03 "Casos borde: soporte de plataforma con acceso restringido"; matriz §1.3 |
| A-8 | Acceso de soporte: grant temporal, con motivo obligatorio, acotado y auditado | matriz §3 (valores "Soporte" y "Restringido") y §8 (hueco con etapa destino = 01.03) |
| A-9 | Consulta de auditoría por entidad, por actor y por período, paginada y autorizada | RF-M24-002, RF-M24-003, RF-M24-004; RNF-M24-004 |
| A-10 | Inmutabilidad de `audit_event` aplicada por la base | RN-M24-001; V5 lo dejó anotado como trabajo de 01.03 |
| A-11 | Índices de auditoría que soporten los tres filtros anteriores | RNF-M24-004 |
| A-12 | Cierre del escenario diferido 7b: `request_hash` en `onboarding_registro` + conflicto por payload distinto | `docs/tests-diferidos.md` #7; V8; `AccountRegistrationController` línea 147 |
| A-13 | Frontend: store de permisos efectivos, guard y directiva de UX, pantallas de colaboradores y auditoría (sin invitación, D-1) | plan 01.03 "Frontend"; §32 |
| A-14 | Reemplazo de los cuatro `TODO(AKINE-01.03)` de autorización | ver §1.3 |

### 1.2 Queda afuera, con etapa destino

| Qué | Por qué | Etapa destino |
|---|---|---|
| **Flujo de invitación de colaborador por mail** (tabla `membership_invitation`, endpoints de invitar / aceptar / rechazar / revocar, notificación) | **D-1 cerrada como opción B por el usuario el 23/08/2026.** Es una **desviación declarada de RF-M05-001 y RF-M05-002**: el plan de la etapa los nombra y esta etapa no los cubre. Ver el recuadro debajo de esta tabla | AKINE-02.01 (candidata; el usuario todavía no la fijó) |
| **Alta de una membership nueva por API** | Consecuencia directa de lo anterior, y hay que decirla aparte porque no es obvia: si la única forma prevista de crear una membership era aceptar una invitación, sacar la invitación deja a la etapa **sin ningún camino para crear memberships**. Ver el recuadro | ídem |
| Permisos clínicos (`hc:read`, `hc:write`, `caso:create`) y su relación asistencial | La matriz §5 los marca F4. No existe HC ni Caso contra los que evaluarlos | F4 |
| `sesion:register` | Matriz §5, F6 | F6 |
| `cobro:register`, `caja:operate` | Matriz §5, F7 | F7 |
| `reporte:read` y el catálogo definitivo de restricciones "Limitado" en reportes | Matriz §5 y §8 | F8 |
| `paciente:manage`, `convenio:manage` | Matriz §5, F3 | F3 |
| Habilitación profesional vigente ("según rol clínico", RF-M05-008) | Depende de `offering` (M27), que no existe | F4 / M27 |
| Flag organizacional "el paciente puede ver su HC" ("Propia autorizada") | Matriz §8 | F4 |
| Si `auditoria:read-clinica` exige además relación asistencial (DP-03) | Matriz §8 | F4 |
| `consultorio:manage` completo (alta, edición, baja, onboarding de sede) | Matriz §5 lo marca "F1 (mínimo) / F2". 01.03 define el **permiso**; los endpoints que lo consumen son de 02.01 | AKINE-02.01 |
| Rediseño de `SecureLinkVault` (persistencia o cifrado del enlace) | Ver §11 D-4: es una decisión de identidad/notificación, no de permisos | decisión abierta |
| Retención y archivado de `audit_event` | Sin RF que lo respalde. ADR-0004 ya anota que "la base solo crece" | decisión abierta / F8 |
| Exportación de auditoría (CSV/PDF) | Ningún RF de M24 la pide | — |
| RF-M24-005 (enmiendas clínicas) y RF-M24-006 (anulaciones económicas) | Requieren HC y economía | F4 / F7 |

> **Qué queda sin poder hacerse, dicho sin rodeos.** Al cierre de 01.03, la única membership que
> una organización puede tener es la del fundador, creada por el onboarding compuesto. No hay
> invitación, y tampoco hay alta de membership por API. En consecuencia:
>
> - **Un tenant no puede sumar un segundo colaborador.** `colaborador:manage` existe, se evalúa y
>   se prueba, pero sobre un conjunto de una sola membership.
> - **RN-M02-002 queda habilitada en el esquema y no alcanzable por la API.** `V10` expandió el
>   unique justamente para permitir roles distintos en sedes distintas; crear esa segunda
>   membership por sede es un alta, y el alta no entra. La regla queda soportada por la base y sin
>   camino de usuario.
> - **Las pruebas de la matriz por rol necesitan memberships que la API no puede crear.** El plan
>   de tests §10.2 recorre 6 roles: esas filas se siembran desde el repositorio en el fixture, no
>   por HTTP. Es aceptable para probar el evaluador, y hay que decirlo en el registro de cierre.
> - **El invariante "último admin" (§6.1) se vuelve más crítico, no menos.** Si el único
>   `ORG_ADMIN` de un tenant pierde su rol, no hay forma de crear otro: no queda ni siquiera el
>   rodeo de invitar a alguien. El rescate es SQL manual o un `PLATFORM_ADMIN` con acceso de
>   soporte.
>
> Nada de esto es un argumento contra la decisión: la etapa baja de 17 endpoints a 12, se cae la
> dependencia de D-4 (`SecureLinkVault`) y se cae el cambio al `spi` de `notification`. Es el
> precio, y va escrito acá y en el registro de cierre para que la etapa que reciba RF-M05-001 y
> RF-M05-002 sepa exactamente qué hereda.

### 1.3 Deuda concreta que 01.03 absorbe — verificada hoy en el árbol

| Ubicación | Qué dice | Verificado |
|---|---|---|
| `organization/application/ProvisionalAuthorizationGuard.java:19` | "reemplazar por el evaluador de la matriz de permisos. Toda la autorización del módulo pasa por esta clase" | **Sigue siendo cierto.** Los ocho puntos de autorización del módulo son ocho llamadas a este guard desde `OrganizationController` (líneas 141, 199, 258, 306) y `SubscriptionController` (108, 173, 224, 293). No hay autorización dispersa: el reemplazo es un archivo |
| `identity/application/AccountAdminService.java:50` y `:192` | "reemplazar `autorizar` por el evaluador"; "va a distinguir entre bloquear y desactivar" | Confirmado. Toda la autorización de la clase pasa por el método privado `autorizar(Actor, long)` |
| `platform/infrastructure/security/AuthenticatedJwtPrincipal.java:53` | "hoy nada emite `PLATFORM_ADMIN` … devuelve siempre `false` y las operaciones de plataforma quedan inalcanzables por HTTP" | Confirmado. `platformAdmin()` compara `claims.roleCode()` contra `"PLATFORM_ADMIN"`, y ese claim se llena con el rol de la membership del contexto, que nunca es ese valor. **Consecuencia medible:** `POST /organizations`, `POST …/subscription/transitions` y `POST …/subscription/plan-changes` están publicados en el contrato 0.3.0 y **no los puede ejecutar nadie** |
| `identity/infrastructure/SecureLinkVault.java:45` | "cerrar esto de raíz … es una decisión de diseño que excede esta etapa" | Confirmado: `ConcurrentHashMap` en memoria, sin persistencia. Ver §11 D-4 |
| `docs/tests-diferidos.md` #7 / `AccountRegistrationController.java:147` | `requestHash = null`, "la deduplicación por contenido no se implementa en 01.02" | Confirmado. `organization_onboarding` (V3) **sí** tiene `request_hash`; `onboarding_registro` (V8) **no**. La mitad del mecanismo existe: falta la columna y el cálculo del hash en `identity` |

---

## 2. Modelo de datos

Migraciones a partir de `V11`, una por módulo propietario, expandir–migrar–contraer (ADR-0007).
`V10` ya está aplicada en el árbol y es de 01.02: ver §2.0. Todas las tablas nuevas llevan
`organization_id NOT NULL` y todos sus `UNIQUE` lo incluyen (ADR-0004), **salvo `platform_role`**,
que es la excepción que formaliza **ADR-0020**.

### 2.0 Lo que `V10` ya hizo, y lo que este documento proponía distinto

01.02 corrigió el unique de `membership` antes de que 01.03 empezara. `V10` está aplicada:

```sql
ALTER TABLE membership
    ADD COLUMN consultorio_scope BIGINT AS (IFNULL(consultorio_id, 0)) STORED NOT NULL;
ALTER TABLE membership DROP INDEX uk_membership_org_account;
ALTER TABLE membership
    ADD CONSTRAINT uk_membership_org_account_scope
        UNIQUE (organization_id, account_id, consultorio_scope);
```

El motivo es el mismo que este documento razonaba: en MySQL varios `NULL` no colisionan en un
índice único, así que un unique sobre `consultorio_id` a secas habría aceptado dos memberships de
alcance organización para la misma cuenta —el caso que más hay que impedir, porque es el que
decide la administración del tenant entero—. El centinela `0` materializa el alcance.

**Y la ruptura de código que este documento anticipaba ya está resuelta.**
`MembershipSelection.java` centraliza el criterio: cuando hay varias memberships, **gana la más
específica**, no la más privilegiada. El javadoc es explícito sobre por qué: no existe jerarquía
de roles hasta 01.03, y elegir por privilegio sería inventarla. Los dos criterios son
fail-closed. 01.03 **no reabre esa decisión**; si al implementar la matriz aparece una jerarquía
real de roles, ese es el momento de discutir si "la más específica gana" tiene que convivir con
"la más privilegiada gana", y es un cambio con su propia justificación, no un ajuste al pasar.

> **Diferencia real entre lo aplicado y lo que este documento proponía, y qué hay que hacer con
> ella.** La propuesta original era `vinculo_activo = IF(active = 1, COALESCE(consultorio_id, 0),
> NULL)`, que deja **fuera del unique a las filas dadas de baja**. Lo aplicado,
> `consultorio_scope = IFNULL(consultorio_id, 0)`, **incluye a todas las filas, activas e
> históricas**. La consecuencia es concreta y aparece en cuanto 01.03 revoque su primera
> membership: **revocar a una persona y volver a vincularla en la misma sede choca contra
> `uk_membership_org_account_scope`**, porque la fila revocada sigue ocupando la clave. Es la
> misma trampa que 02.01 §2.4 resuelve para el nombre del consultorio con `deleted_key`.
>
> No es un defecto de `V10`: cuando se escribió no existía ni la revocación ni la revinculación.
> Y hay un matiz que baja la urgencia sin eliminar el problema: **con D-1 cerrada como B, 01.03 no
> expone ningún camino para crear memberships**, así que la colisión no es alcanzable por la API
> de esta etapa. Se vuelve alcanzable el día que vuelva el alta —invitación incluida—, y ese día
> aparece como un 409 incomprensible al revincular a alguien que trabajó antes en la sede. Las dos
> salidas, y ninguna es obvia:
>
> - **Expandir el discriminador en `V11`** para que las filas históricas salgan del unique, con
>   la misma forma que la propuesta original: una columna generada que valga `NULL` cuando la
>   membership no está vigente. Sale barato hoy, y hay que revisar que nada dependa del unique
>   actual.
> - **Reusar la fila en vez de crear otra**: revocar cierra vigencia y estado, y volver a
>   vincular reabre la misma fila. Conserva el unique tal cual y **pierde el historial**: una
>   fila que se revoca y se reactiva tres veces no deja rastro de las tres, contra RN-M05-003 y
>   la regla maestra 10. La auditoría lo compensaría solo en parte.
>
> **Queda registrado como D-13.** No bloquea la implementación de 01.03; **sí** bloquea a la etapa
> que devuelva el alta de memberships, y conviene decidirlo mientras la tabla todavía tiene una
> fila por tenant y cambiar el discriminador es gratis.

### 2.1 `V11` — organization: expandir `membership` (estado y trazabilidad)

```
ALTER TABLE membership
  ADD COLUMN estado                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVA',
  ADD COLUMN revoked_by_account_id   BIGINT      NULL,
  ADD COLUMN revoked_reason          VARCHAR(500) NULL,
  ADD INDEX ix_membership_org_consultorio_estado (organization_id, consultorio_id, estado, active);
```

- **`estado`** materializa §33 (toda entidad con estados declara los suyos). Valores:
  `ACTIVA`, `SUSPENDIDA`, `REVOCADA` (terminal). El backfill de las filas existentes es
  `'ACTIVA'`: hoy la única membership escrita es la del fundador (`RoleCode.ORG_ADMIN`,
  `is_founder = 1`) y está activa por construcción.
- **`INVITADA` y `RECHAZADA` no se declaran todavía.** Eran los estados del flujo de invitación,
  que D-1 dejó fuera (§1.2). Declarar valores que ninguna transición produce sería documentar una
  máquina de estados que no existe; cuando la invitación llegue, agregarlos es una migración de
  catálogo, no un rediseño.
- **`invited_by_account_id` tampoco entra**, por el mismo motivo: sin invitación no hay quién
  invitó. `revoked_by_account_id` y `revoked_reason` sí, porque la revocación sí entra y su motivo
  es obligatorio.
- **`estado` no reemplaza a `active` ni a la vigencia.** Son tres cosas y las tres tienen que
  cumplirse, igual que hoy `Membership.isValidAt` exige `active` **y** ventana temporal.
  `REVOCADA` cierra `valid_until` y pone `active = 0`; la fila **nunca** se borra (RN-M05-003,
  regla maestra 10).
- **El unique no se toca acá salvo que D-13 se resuelva por la primera salida.** En ese caso, el
  cambio de discriminador va en esta misma migración, con su expandir–contraer explícito y con la
  advertencia de irreversibilidad práctica del párrafo siguiente.

Retirar `uk_membership_org_account_scope` para reemplazarlo es un cambio de constraint, no de
datos: no hay backfill, y el rollback práctico es recrear el índice anterior, que solo es posible
mientras ninguna cuenta tenga dos memberships con el mismo alcance en la misma organización —una
vigente y una revocada, por ejemplo—. **Después de la primera revocación seguida de una
revinculación, el paso es irreversible en la práctica** y hay que decirlo en el registro de
cierre.

### 2.3 `V12` — organization: `membership_grant` y `platform_role`

```
CREATE TABLE membership_grant (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id       BIGINT      NOT NULL,
    membership_id         BIGINT      NOT NULL,
    permission_code       VARCHAR(48) NOT NULL,
    granted_by_account_id BIGINT      NOT NULL,
    reason                VARCHAR(500) NOT NULL,
    valid_from            DATETIME(6) NOT NULL,
    valid_until           DATETIME(6) NULL,
    active                TINYINT(1)  NOT NULL DEFAULT 1,
    deleted_at            DATETIME(6) NULL,
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    grant_activo          VARCHAR(48) AS (IF(active = 1, permission_code, NULL)) STORED,
    CONSTRAINT pk_membership_grant PRIMARY KEY (id),
    CONSTRAINT uk_membership_grant_activo UNIQUE (organization_id, membership_id, grant_activo),
    CONSTRAINT fk_membership_grant_org        FOREIGN KEY (organization_id) REFERENCES organization (id),
    CONSTRAINT fk_membership_grant_membership FOREIGN KEY (membership_id)   REFERENCES membership (id),
    INDEX ix_membership_grant_membership (membership_id, active)
);
```

- `reason` es `NOT NULL`: un permiso adicional sin motivo declarado no se puede revisar seis
  meses después, que es cuando se revisa.
- El unique con columna generada da "un grant activo por permiso por membership" **conservando
  el historial** de los revocados. Un `UNIQUE (organization_id, membership_id, permission_code)`
  a secas impediría re-otorgar lo que alguna vez se quitó.
- **En F1 el único código que esta tabla puede contener es `auditoria:read-clinica`** (matriz §6:
  es el único "No por defecto (grant)" de la fase). El código valida contra el catálogo y
  rechaza cualquier otro con `422`/`400`, no con una fila inválida.

```
CREATE TABLE platform_role (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    account_id            BIGINT      NOT NULL,
    role_code             VARCHAR(48) NOT NULL,
    granted_by_account_id BIGINT      NULL COMMENT 'NULL = seed de bootstrap',
    reason                VARCHAR(500) NOT NULL,
    valid_from            DATETIME(6) NOT NULL,
    valid_until           DATETIME(6) NULL,
    active                TINYINT(1)  NOT NULL DEFAULT 1,
    ...
    rol_activo            VARCHAR(48) AS (IF(active = 1, role_code, NULL)) STORED,
    CONSTRAINT uk_platform_role_activo UNIQUE (account_id, rol_activo)
);
```

- **Sin `organization_id`. La excepción a ADR-0004 está formalizada en
  [ADR-0020](../adr/0020-rol-de-plataforma-sin-organization-id.md)**, escrito para esta etapa
  porque ADR-0019 cierra su lista con "una excepción nueva exige un ADR que la agregue a esta
  tabla; un comentario en la migración no alcanza". El motivo es el mismo que el de
  `plan`/`plan_limit`: es un objeto **de la plataforma**, no de un tenant. La matriz §1.3 es
  explícita: "`PLATFORM_ADMIN` no tiene membership en ninguna organización". La migración cita
  ADR-0020 en su encabezado.
- **`account_id` es referencia lógica, sin FK física**, igual que `membership.account_id` (V3):
  `cuenta` es de `identity` y `organization` no la referencia físicamente.
- **Propietario: `organization`.** No `platform`. Razón: `organization` ya es el propietario del
  catálogo de roles (`RoleCode`), del catálogo global de planes y del evaluador (§3); poner el
  rol de plataforma en `platform` partiría el modelo de permisos en dos módulos y obligaría a un
  puerto invertido más para algo que no lo necesita. La dirección `identity → organization.spi`
  ya existe y basta.
- **`role_code` en esta tabla solo admite `PLATFORM_ADMIN`.** No es una tabla de roles genérica.

> **Contradicción de modelado que esto cierra.** `RoleCode.PLATFORM_ADMIN` existe en el enum que
> persiste `membership.role_code`, y el comentario de `V3` lo lista como valor válido de esa
> columna — pero la matriz §1.3 dice que ese rol no tiene membership. Decisión: `PLATFORM_ADMIN`
> **queda prohibido como valor de `membership.role_code`**, verificado por un `CHECK` en `V10` y
> por un test. El enum lo conserva porque es el catálogo de roles del sistema, no el de valores
> de esa columna.

### 2.4 `V13` — organization: `support_access`

> **`membership_invitation` no se crea.** D-1 quedó cerrada como opción B el 23/08/2026: el flujo
> de invitación no entra en esta etapa (§1.2). La tabla, sus dos uniques con columna generada, los
> endpoints públicos de aceptación y rechazo y el cambio al `spi` de `notification` viajan con
> RF-M05-001 y RF-M05-002 a la etapa que los reciba. Se deja acá el registro de la forma que
> tenían, porque el análisis está hecho y rehacerlo sería trabajo repetido: unique global sobre
> `token_hash` —el token se presenta antes de que haya contexto, mismo caso que
> `token_verificacion` en ADR-0019—, y unique sobre `(organization_id, consultorio_id, <email si
> está pendiente>)` para impedir dos invitaciones vivas al mismo email en el mismo alcance sin
> bloquear reinvitar después de un rechazo.

```
CREATE TABLE support_access (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id       BIGINT       NOT NULL,
    account_id            BIGINT       NOT NULL COMMENT 'El PLATFORM_ADMIN que accede',
    reason                VARCHAR(500) NOT NULL COMMENT 'Obligatorio. Sin motivo no hay acceso de soporte',
    granted_by_account_id BIGINT       NOT NULL,
    valid_from            DATETIME(6)  NOT NULL,
    valid_until           DATETIME(6)  NOT NULL COMMENT 'NOT NULL: el acceso de soporte SIEMPRE vence',
    revoked_at            DATETIME(6)  NULL,
    ...
    INDEX ix_support_access_org_account (organization_id, account_id, valid_until)
);
```

- `valid_until NOT NULL` es la diferencia entre esta tabla y un grant común: la matriz §3 define
  "Soporte" como "acotado en tiempo y auditado". Un acceso de soporte sin fin no es soporte.
- Habilita únicamente los valores "Soporte" y "Restringido" de la matriz. En F1 su efecto
  práctico es: permitir a un `PLATFORM_ADMIN` operar dentro del tenant y, combinado con un grant
  `auditoria:read-clinica`, leer la auditoría clínica (que en F1 todavía no tiene contenido).

### 2.5 `V14` — platform: auditoría

```
ALTER TABLE audit_event
  ADD INDEX ix_audit_event_org_time (organization_id, occurred_at),
  ADD INDEX ix_audit_event_org_loc_time (organization_id, consultorio_id, occurred_at);

CREATE TRIGGER trg_audit_event_no_update BEFORE UPDATE ON audit_event
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event es append-only';
CREATE TRIGGER trg_audit_event_no_delete BEFORE DELETE ON audit_event
  FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event es append-only';
```

- **Los índices faltan hoy y RF-M24-004 los necesita.** `AuditEventRepository` ya declara
  `findAllByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc`, pero los dos índices de V5
  son `(organization_id, entity_type, entity_id, occurred_at)` y
  `(organization_id, actor_account_id, occurred_at)`: ninguno sirve para un rango sobre
  `occurred_at` filtrando solo por organización. Hoy esa consulta es un scan del tenant entero.
- Los triggers son el "enforcement de inmutabilidad por trigger SIGNAL con su test" que el
  comentario de V5 le asignó explícitamente a 01.03. Cierran RN-M24-001 **en la base**: hasta
  hoy la garantía dependía de que `AuditEventRepository` no heredara `delete` de
  `JpaRepository`, que protege del descuido pero no del SQL directo.

### 2.6 `V15` — identity: `request_hash` (escenario diferido 7b)

```
ALTER TABLE onboarding_registro
  ADD COLUMN request_hash CHAR(64) NULL
  COMMENT 'SHA-256 del payload canonico. NULL para las filas anteriores a 01.03: no se puede reconstruir';
```

Nullable y sin backfill, a propósito: el payload de las altas ya ocurridas no está guardado en
ningún lado (y no debe estarlo: lleva la contraseña). Una fila con `request_hash NULL` se trata
como "sin huella conocida" y **no** produce conflicto — el reintento se replica igual que hoy.
El conflicto solo se puede afirmar cuando hay dos huellas y difieren.

El hash se calcula sobre la forma canónica de los campos de negocio del `RegisterAccountRequest`
—email normalizado, nombres, datos de organización, consultorio, plan— **excluyendo la
contraseña**: incluirla haría que un reintento con la misma clave y la contraseña recortada por
el gestor del navegador diera un 409 incomprensible, y metería material de credencial en el
cálculo. Esto es simétrico con lo que `organization_onboarding.request_hash` (V3, B-4) ya hace.

### 2.7 `V16` — organization: seed del primer `PLATFORM_ADMIN`

Migración separada de la que crea la tabla, con el mismo criterio que `V4` usó para el catálogo
de planes: el DDL y el dato no se mezclan.

```sql
INSERT INTO platform_role (account_id, role_code, granted_by_account_id, reason,
                           valid_from, active, created_at, updated_at)
SELECT c.id, 'PLATFORM_ADMIN', NULL, 'Bootstrap de plataforma — AKINE-01.03, ADR-0020',
       UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
  FROM cuenta c
 WHERE c.email_normalizado = '<email institucional del bootstrap>'
   AND NOT EXISTS (SELECT 1 FROM platform_role p
                    WHERE p.account_id = c.id AND p.active = 1);
```

Cuatro cosas que hay que mirar de frente, porque ninguna es cosmética:

1. **El email queda versionado en el repositorio.** Es la contrapartida que el usuario aceptó
   explícitamente a cambio de que el alta sea auditable y reproducible. Tiene que ser una casilla
   **institucional y estable**, no la dirección personal de quien esté a cargo hoy.
2. **La migración lee `cuenta`, que es de `identity`.** Es el único lugar del sistema donde una
   sentencia SQL cruza la frontera de propiedad entre módulos. ADR-0020 lo autoriza por escrito y
   explica por qué no hay alternativa: `organization → identity` está prohibido, así que este
   bootstrap no tiene llamador Java posible. ArchUnit no ve los `.sql`; la regla la sostiene la
   revisión de la migración.
3. **En una base nueva el seed inserta cero filas, y no falla.** Al aplicarse las migraciones
   todavía no existe ninguna cuenta: `cuenta` está vacía. O sea que **el despliegue inicial
   termina sin ningún `PLATFORM_ADMIN`**, en silencio. Es la consecuencia más incómoda de la
   decisión y no se puede tapar con `NOT NULL` ni con un `CHECK`.
   - Operativamente: desplegar → registrar por self-service la cuenta del email del bootstrap →
     aplicar **una migración posterior** que corra el mismo `INSERT … SELECT`. Son dos
     despliegues, y va al runbook.
   - En los tests de integración el problema es el mismo: el fixture siembra la fila
     directamente, porque las migraciones corren sobre una base vacía.
   - **Sub-decisión abierta (D-14):** si se acepta ese bootstrap en dos pasos, o si el seed
     además crea la fila de `cuenta` con credencial inutilizable y obliga a la recuperación de
     contraseña para tomar posesión. Lo segundo hace el alta de un solo paso y cruza la frontera
     de propiedad **escribiendo**, no solo leyendo.
4. **Chequeo de arranque, no silencio.** Un `ApplicationRunner` cuenta las filas activas de
   `platform_role` y loguea a nivel `WARN` si son cero, con el texto explícito de que el alta
   administrativa de tenants está inoperante. No falla el arranque: un entorno de desarrollo sin
   platform admin es legítimo.

**Sub-decisión de D-2, todavía abierta:** si un `PLATFORM_ADMIN` puede otorgarle el rol a otro por
HTTP (`POST /platform/roles`). Si sí, comprometer esa cuenta compromete la plataforma entera; si
no, cada alta posterior es otra migración o una operación manual. Ver §12 D-2.

---

## 3. El evaluador de permisos

### 3.1 Dónde vive y por qué

**Módulo propietario: `organization`.** Es el único que satisface las tres condiciones a la vez:

1. Es dueño de los datos que la evaluación necesita — `membership`, `membership_grant`,
   `platform_role`, `support_access`, `consultorio` — y la regla 1 de `AGENT.md` §4 dice que
   cada tabla tiene un módulo propietario y ningún otro la lee.
2. Es dueño de la semántica: `RoleCode` ya vive en `organization.domain`, y la matriz es el
   catálogo de roles de ese enum.
3. No cierra ciclos. Las flechas que 01.03 necesita ya existen y todas van en la dirección
   permitida.

```
identity ──────► organization.spi ──────► platform.spi
   │                                          ▲
   └──────────────────────────────────────────┘
organization.infrastructure.tenant ──implementa──► platform.spi.tenant.MembershipDirectory
                                                   platform.spi.tenant.PlatformRoleDirectory  (nuevo)
```

Alternativas descartadas:

- **Un módulo `authorization` nuevo.** Necesitaría leer `membership` ⇒ `authorization →
  organization.spi`; y `organization` necesitaría autorizar sus propios endpoints ⇒
  `organization → authorization.spi`. Ciclo, rechazado por `sin_ciclos_entre_modulos`. La única
  salida sería que el evaluador recibiera la membership por parámetro, con lo que la regla
  "revalidar contra la base" se le delegaría a cada llamador: exactamente lo que se quiere
  evitar.
- **El evaluador en `platform`.** `platform` es el módulo base y no puede depender de ninguno
  funcional; hoy resuelve tenancy con un puerto invertido. Pero `platform.spi.TenantMembership`
  documenta que `platform` "transporta el `roleCode` y no lo interpreta; quien decide qué puede
  hacer cada rol es el módulo que evalúa permisos". Poner el evaluador ahí obligaría a `platform`
  a conocer el catálogo de permisos y a ser propietario de tablas de negocio de un tenant.

### 3.2 Qué expone por `spi`

```java
package com.akine.organization.spi;

public interface PermissionEvaluator {
    PermissionDecision evaluate(AuthorizationRequest request);
    Set<String> effectivePermissions(long accountId, long organizationId, Long consultorioId);
    boolean isPlatformAdmin(long accountId, Instant at);
}

public record AuthorizationRequest(
        long accountId, String permissionCode,
        Long organizationId, Long consultorioId, Long targetAccountId, Instant at) { }

public record PermissionDecision(boolean granted, DenialKind denial, String grantedByScope) { }

public enum DenialKind { NONE, NO_CONTEXT, OUT_OF_SCOPE, NO_PERMISSION, CLINICAL_ENABLEMENT_MISSING }
```

- `permissionCode` y `grantedByScope` viajan como `String`, no como enums de `domain`, por el
  mismo motivo documentado en `MembershipSnapshot`: un consumidor que use el enum estaría
  importando `organization.domain`, que ArchUnit rechaza.
- `DenialKind` es lo que permite mapear el rechazo a **un solo** código HTTP en **un solo**
  lugar (§7): `OUT_OF_SCOPE → 404`, `NO_CONTEXT → 403 missing-tenant-context`,
  `NO_PERMISSION → 403 forbidden`. Si cada llamador decidiera, la regla "cross-tenant es 404"
  duraría hasta el tercer controller.
- `CLINICAL_ENABLEMENT_MISSING` se declara ahora y **siempre devuelve `false` en F1**: los
  permisos "según rol clínico" no existen hasta F4. Se declara para que el enum no cambie de
  forma cuando llegue, no para simular la funcionalidad.

Además, `organization.spi.PermissionGuard` con `requirePermission(AuthorizationRequest)`, que
lanza. Las excepciones son `organization.domain.exception.PermissionDeniedException` y
`MembershipNotAccessibleException`, mapeadas por `OrganizationProblemHandler`.

> **Por qué esto no viola la regla de módulos.** `identity` llama a `permissionGuard.require(...)`
> y **no nombra nunca el tipo de la excepción**: no la captura, la deja propagar. ArchUnit prohíbe
> *importar* `organization.domain`, y un `throws` no capturado no es un import. El advice es
> `@RestControllerAdvice`, es decir global: atrapa la excepción aunque el controller sea de
> `identity`. Es el mismo mecanismo que ya usa `ContextNotAuthorizedException`, lanzada por
> `AccountContextDirectory.selectContext` y consumida desde `identity/api/AuthSessionController`.

### 3.3 Cómo lo consumen los dos módulos

| Consumidor | Qué reemplaza | Cómo |
|---|---|---|
| `organization.application` | `ProvisionalAuthorizationGuard` completo | El guard **no se borra: se vacía**. Conserva el nombre `AuthorizationGuard`, las mismas cuatro firmas que hoy usan los controllers, y por dentro delega en el evaluador. Los ocho call sites no se tocan, así que el diff que importa está en un archivo y el riesgo de que se escape un endpoint es cero |
| `identity.application.AccountAdminService` | El método privado `autorizar(Actor, long)` | Pasa a `permissionGuard.requirePermission(...)` con `colaborador:manage` y `targetAccountId = cuentaId`. La verificación "la cuenta objetivo tiene membership en esta organización" **no se relaja**: la absorbe el evaluador vía `targetAccountId`, tal como ADR-0019 lo exige ("la matriz decide *qué* puede hacer el actor, la membership sigue decidiendo *sobre quién*") |
| `platform.infrastructure.security` | `AuthenticatedJwtPrincipal.platformAdmin()` | Deja de leer el claim. Ver §3.5 |

### 3.4 El algoritmo

Materializa literalmente la fórmula de la matriz §3:

```
permisos(actor, org, consultorio, t) =
      base(rol_de_la_membership_que_cubre(consultorio), t)      // tabla estática, en código
    ∪ grants_activos(membership, t)                              // membership_grant
    ∪ base_plataforma(platform_role_activo(actor, t))            // solo con support_access vigente
                                                                 //   para lo que la matriz marca
                                                                 //   "Soporte" o "Restringido"
    ∩ alcance(membership)                                        // GLOBAL | ORGANIZACION | CONSULTORIO | OWN | CATALOGO
    ∩ habilitacion_vigente(t)                                    // vacío en F1: no hay acciones clínicas
```

**La asignación base por rol vive en código, no en una tabla.** `organization.domain.RolePermissions`
es un mapa estático que reproduce la matriz §6. Motivo: la asignación base **es la
especificación** (§32); cambiarla debe ser un diff revisable contra la matriz, no un `UPDATE`.
Una tabla de asignación base invita a que alguien se otorgue `tenant:manage` con SQL, sin
auditoría y sin revisión. Los **grants** sí son datos, porque son decisiones operativas de cada
organización, y por eso tienen `granted_by`, `reason`, vigencia y auditoría.

Un test de arquitectura compara `RolePermissions` fila por fila contra la tabla §6 de
`matriz-permisos-minima.md` como fixture: si alguien toca el mapa sin tocar la matriz —o al
revés— el build falla.

### 3.5 `PLATFORM_ADMIN`: de dónde sale y cómo se revalida

Hoy `platformAdmin()` sale del claim `rol`. Eso contradice frontalmente lo que documenta
`AccessTokenClaims`: *"si alguien empieza a autorizar por este campo, la ventana de revocación
de permisos deja de ser cero y pasa a ser el TTL del token… No lo hagas."* Es una contradicción
interna real, hoy inofensiva solo porque el claim nunca vale `PLATFORM_ADMIN`.

01.03 la cierra así:

1. Nuevo puerto invertido `platform.spi.tenant.PlatformRoleDirectory`, con
   `boolean isPlatformAdmin(long accountId, Instant at)`. Lo declara `platform`, lo implementa
   `organization.infrastructure.tenant` leyendo `platform_role`. Es el mismo patrón, probado,
   de `MembershipDirectory`.
2. `TenantContextFilter` lo consulta **una vez por request** —igual que la membership, sin
   caché— y publica el resultado en el contexto del request.
3. `AuthenticatedJwtPrincipal.platformAdmin()` pasa a devolver el valor revalidado, no el
   claim. El claim `rol` sigue existiendo como pista para el frontend, y nadie autoriza con él.
4. El costo es un seek indexado más por request. Es el mismo precio que la decisión T-7 ya pagó
   por la membership, por la misma razón: ventana de revocación cero.

**Cómo nace el primer `PLATFORM_ADMIN`: D-2 cerrada (opción A, 23/08/2026).** Tabla
`platform_role` propia más un seed de migración que resuelve un email institucional versionado en
el repositorio. La excepción a ADR-0004 que la tabla introduce está formalizada en **ADR-0020**, y
sin ese ADR la migración no se commitea. El detalle del seed, sus tres trampas y la sub-decisión
que sigue abierta están en §2.7.

---

## 4. Vigencias de membership

### 4.1 Lo que no se rompe

`TenantContextFilter` revalida la membership contra la base en **cada** request, sin caché
(decisión T-7, documentada en `MembershipDirectory`). La ventana de revocación es cero y 01.03
la conserva tal cual. Concretamente: **prohibido** cachear `resolveMembership`, prohibido
autorizar por el claim `rol`, y prohibido resolver permisos una vez por sesión.

### 4.2 Membership que vence a mitad de una sesión

Tres ventanas distintas, y conviene no confundirlas:

| Ventana | Duración | Qué pasa | Decisión |
|---|---|---|---|
| Entre dos requests | 0 | El request siguiente no resuelve contexto y recibe **404** (camino ya existente de `TenantContextFilter`) | Sin cambios |
| Entre el filtro y el commit de la transacción de negocio | Duración de la transacción | El filtro resolvió en su propia transacción `readOnly`; la de negocio es otra | El evaluador corre **dentro de la transacción de negocio**, no en el filtro. Ver 4.3 |
| Entre la emisión del access token y su vencimiento | ≤ 10 min | Irrelevante: el token no autoriza nada, solo dice qué contexto se pide | Sin cambios |

La segunda ventana no se puede llevar a cero sin serializar el sistema entero, y pretender lo
contrario sería mentir. Lo que sí se acota: **el evaluador se invoca al principio de la
transacción de negocio y lee la membership ahí**, de modo que la ventana es la duración de una
transacción y no la de un request completo. Para las mutaciones que además tocan memberships
(§6), la resolución ocurre **después** de tomar el lock del tenant —la fila de `subscription`,
§6.0.1—, con lo que en ese subconjunto la ventana efectiva es cero.

### 4.3 Qué ve el usuario

Que el 404 sea correcto del lado del servidor no lo hace comprensible del lado de la pantalla.
El frontend, al recibir un `404 not-found` sobre una ruta de negocio **teniendo un contexto
seleccionado**, no muestra "no encontrado": limpia el contexto y lleva al selector con el
mensaje "tu acceso a este consultorio cambió". Es UX, no seguridad: el backend ya rechazó.

### 4.4 Vigencias futuras

`valid_from`/`valid_until` admiten valores futuros y `isValidAt` ya los respeta. **No hace falta
ningún job de vencimiento**, y no se agrega uno: un job que "desactiva memberships vencidas"
introduce una ventana entre el vencimiento real y la corrida del job, que es peor que no
tenerlo. La vigencia se evalúa al leer, siempre.

---

## 5. Auditoría

### 5.1 Regla heredada, sin excepciones

`AuditTrail.record` se invoca **dentro de la transacción del negocio**, con
`Propagation.MANDATORY`. Si la auditoría falla, la operación no se confirma. 01.03 no agrega
ningún listener post-commit ni ningún camino asíncrono para auditar. El mecanismo ya está
construido y probado en `AuditTrailImpl`; 01.03 lo consume, no lo reemplaza.

### 5.2 Qué se registra

Eventos nuevos, en `organization.application.AuditEvents` (constantes, no enum, por el contrato
`String` de `AuditEntry.eventType`):

| `eventType` | `entityType` | Cuándo | Traza |
|---|---|---|---|
| `MEMBERSHIP_CREATED` | `Membership` | *(ya existe)* alta de membership | RF-M01-002 |
| `MEMBERSHIP_ROLE_CHANGED` | `Membership` | cambio de rol; `previousState`/`newState` = `RoleCode` | RF-M02-004 |
| `MEMBERSHIP_SUSPENDED` / `MEMBERSHIP_REACTIVATED` | `Membership` | cambio de `estado` | §33 |
| `MEMBERSHIP_REVOKED` | `Membership` | revocación; `reason` obligatorio | RN-M05-003 |
| `MEMBERSHIP_SCOPE_CHANGED` | `Membership` | cambio de `consultorio_id` | RF-M01-002 |
| `GRANT_ASSIGNED` / `GRANT_REVOKED` | `MembershipGrant` | permiso adicional; `details.permissionCode` | matriz §3 |
| ~~`INVITATION_*`~~ | — | **Fuera por D-1.** No se declaran constantes de eventos que nada emite | RF-M05-001, RF-M05-002 |
| `PLATFORM_ROLE_GRANTED` / `PLATFORM_ROLE_REVOKED` | `PlatformRole` | `organizationId = NULL` (evento de plataforma) | matriz §1.3 |
| `SUPPORT_ACCESS_GRANTED` / `SUPPORT_ACCESS_REVOKED` | `SupportAccess` | `reason` obligatorio, `details.validUntil` | matriz §3 "Soporte" y §7 |
| `SUPPORT_ACCESS_USED` | `SupportAccess` | **cada** operación que un `PLATFORM_ADMIN` realiza dentro de un tenant amparado por soporte | matriz §7 |
| `PERMISSION_DENIED` | la entidad pedida | rechazo por permiso sobre un recurso **dentro** del alcance del actor | `AGENT.md` §10 "auditoría de toda excepción de permiso" |

**`PERMISSION_DENIED` no se registra en los rechazos por alcance (los 404).** Registrarlos
construiría dentro de `audit_event` el mismo padrón de existencia de tenants ajenos que el 404
uniforme existe para no entregar — el mismo razonamiento por el que
`IdentityAuditEvents.ACTIVACION_REENVIADA` no audita el pedido sobre un email inexistente. Los
rechazos por alcance van al log estructurado, correlacionados por `traceId`, no a la tabla.

Campos: los de `AuditEntry`, que ya existen. `previousState`/`newState` se usan para las
transiciones de rol y estado; `details` para el resto; `reason` siempre que la operación exija
motivo. Rige la lista de claves sensibles de `AuditTrailImpl` — sigue prohibido cualquier
secreto, token o contenido clínico (RN-M24-002).

### 5.3 Cómo se consulta

Tres consultas, una por RF, todas paginadas (RNF-M24-004) y todas con `organizationId` tomado
del **contexto del request**, jamás de un parámetro:

| RF | Endpoint | Índice |
|---|---|---|
| RF-M24-002 — historial de entidad | `GET …/audit-events?entityType&entityId` | `ix_audit_event_entity` |
| RF-M24-003 — por usuario | `GET …/audit-events?actorAccountId` | `ix_audit_event_actor` |
| RF-M24-004 — por período | `GET …/audit-events?from&to` | `ix_audit_event_org_time` (nuevo) |

Autorización: `auditoria:read`, con el alcance de la membership. `ORG_ADMIN` ve toda la
organización; `CONSULTORIO_ADMIN` ve **su consultorio**, filtrando por `consultorio_id`
(`ix_audit_event_org_loc_time`). El límite superior del rango se acota en el servidor
(decisión abierta D-8: cuánto).

> **Problema real que esto abre y que el usuario tiene que decidir (D-7).** Casi todos los
> eventos que hoy escribe el sistema llevan `consultorio_id NULL`, porque las operaciones de
> M01/M02 son de alcance organización. Con el filtro estricto por `consultorio_id`, un
> `CONSULTORIO_ADMIN` abriría la pantalla de auditoría y **no vería nada**. Es correcto en
> términos de mínimo privilegio y desconcertante en términos de producto.

### 5.4 Inmutabilidad

`RN-M24-001` ("auditoría no editable por usuarios operativos") pasa a estar garantizada por la
base, no por la forma del repositorio: triggers `BEFORE UPDATE` y `BEFORE DELETE` que emiten
`SIGNAL SQLSTATE '45000'` (V14). Test de integración dedicado que intenta un `UPDATE` por SQL
nativo y espera el fallo — la única forma de probar un trigger es dispararlo.

---

## 6. Concurrencia — dónde 01.03 tiene carreras

Sección escrita a partir de los tres bugs que aparecieron en 01.01/01.02 con dos hilos reales
contra MySQL real, no de razonamiento a priori.

### 6.0 Las tres lecciones, aplicadas

1. **Un lock pesimista serializa el acceso, no la visibilidad.** En `REPEATABLE READ`, una
   lectura consistente previa fija el snapshot de la transacción; un `SELECT COUNT(*)` posterior
   —aunque el lock ya se haya adquirido— puede seguir viendo datos de antes del commit del
   competidor. **Corolario que 01.03 aplica: la lectura que decide un invariante nunca es una
   lectura consistente.** O es una lectura con lock (`FOR SHARE` / `FOR UPDATE`), que siempre lee
   la última versión confirmada, o la transacción declara `READ COMMITTED`. En las transacciones
   de invariante de esta etapa se hacen **las dos cosas**, porque son baratas y protegen contra
   que alguien agregue una lectura JPA temprana sin darse cuenta.
2. **Un `INSERT` hijo toma lock compartido sobre el padre y al commit escala a exclusivo.** Dos
   transacciones simétricas se matan entre sí. **Corolario: hay un solo punto de bloqueo y está
   ordenado.** Toda mutación de memberships y grants de una organización toma primero un lock
   exclusivo sobre **la fila de `subscription` del tenant** y nada más. Una vez serializadas, dos
   escritoras nunca compiten por locks de granularidad fina en la misma organización. Por qué la
   suscripción y no la organización, que era la propuesta original: §6.0.1.

#### 6.0.1 El orden de bloqueo del sistema — `subscription` → `organization`

**Decisión del usuario, 23/08/2026.** El orden único, para todo el sistema, es
**`subscription` primero, `organization` después**. Cierra la D-11 de
`AKINE-02.01-consultorios.md`.

El problema que resuelve. Este documento proponía `SELECT id FROM organization … FOR UPDATE` como
primera sentencia de toda mutación de membership. El alta de sede de 02.01 entra por
`PlanGate.createWithinLimit`, que toma `X` sobre la fila de `subscription` y **después** inserta
el consultorio, que toma `S` sobre la fila de `organization` por
`fk_consultorio_organization`. Los dos órdenes son inversos: un alta de sede y un alta de
membership simultáneas en la misma organización se bloquean cruzado, InnoDB mata a una y devuelve
`CannotAcquireLockException`, que el handler genérico mapea hoy a **500**. Es el bug 2 de
`src/test/java/com/akine/diferidos/` reencarnado entre dos etapas distintas, que es donde nadie
lo busca.

**Se reescribe 01.03 y no el `PlanGate`.** El gate ya está implementado, probado con hilos reales
contra MySQL real (`LimiteDePlanConcurrenteIT`) y su contrato incluye la negativa deliberada a
unirse a una transacción existente —al unirse heredaría la isolation del llamador y el límite se
violaría en silencio—. Cambiar papel sale más barato que cambiar código verificado, y este
documento todavía no se implementó.

**Qué cambia en la práctica.** El punto de serialización de las mutaciones de membership pasa a
ser la fila de `subscription` del tenant. Serializa igual de bien: hay exactamente una suscripción
por organización, así que "tomar la suscripción" y "tomar la organización" tienen el mismo grano
—el tenant entero— y la misma contención. Lo único que cambia es **cuál** es la fila, y la elección
la fija el consumidor que no puede cambiar.

Tres precisiones para el implementador:

- **La fila de `subscription` se toma aunque la operación no consuma cupo de plan.** Revocar un
  rol no toca ningún límite, y aun así bloquea la suscripción: el orden tiene que ser único para
  *todas* las transacciones, no solo para las que miran el plan. Un orden que se respeta a veces
  no es un orden.
- **No se toma también `organization`.** Si una mutación necesitara además esa fila —hoy ninguna
  de 01.03 la necesita, porque `membership` ya existe y no se insertan filas hijas nuevas de
  `organization`—, se toma **después**, nunca antes.
- **La lectura del lock es `SELECT id FROM subscription WHERE organization_id = ? FOR UPDATE`**, y
  sigue siendo **la primera sentencia** de la transacción, por el mismo motivo de la lección 1: si
  antes hubiera un `findById` de JPA, el snapshot quedaría fijado ahí.

**Lo que hay que vigilar.** El día que 01.03 —o la etapa que traiga el alta de memberships—
necesite pasar por `PlanGate` para `MAX_MIEMBROS_ACTIVOS`, el gate abre su propia transacción y
toma la suscripción él mismo. El servicio no debe tomarla antes ni envolver al gate en una
transacción propia: `createWithinLimit` lanza `IllegalStateException` si hay una activa, y esa
excepción es la red, no un obstáculo a rodear.
3. **Una sesión JPA reusada después de un flush fallido tira `AssertionFailure` y devuelve 500.**
   **Corolario: el camino de clave duplicada no vuelve a tocar JPA.** El servicio hace
   `saveAndFlush`, traduce la `DataIntegrityViolationException` a una excepción de dominio y la
   deja propagar; la transacción se marca para rollback y el advice arma el 409. Ninguna lectura,
   ningún `save`, ningún `merge` después del flush fallido.

### 6.1 Último admin

**La carrera.** Dos `ORG_ADMIN`, A y B. En el mismo instante, A revoca a B y B revoca a A. Cada
transacción cuenta "¿queda otro admin vigente además del que estoy revocando?", las dos ven al
otro, las dos pasan, y la organización queda sin ningún administrador. No hay forma de repararlo
desde la aplicación: no queda nadie con permiso para asignar un admin.

**Cómo se cierra.**

```
@Transactional(isolation = Isolation.READ_COMMITTED)
1. SELECT id FROM subscription WHERE organization_id = :orgId FOR UPDATE
                                                               ← PRIMERA sentencia (§6.0.1)
2. resolver permisos del actor (§4.2: acá la ventana es cero)
3. SELECT COUNT(*) FROM membership
     WHERE organization_id = :orgId AND role_code = 'ORG_ADMIN'
       AND estado = 'ACTIVA' AND active = 1
       AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)
       AND id <> :membershipObjetivo
   FOR SHARE                                                   ← lectura con lock: ve lo confirmado
4. si el conteo es 0 → LastAdminException → 409 last-admin-required
5. UPDATE de la membership objetivo + auditoría
```

- El paso 1 es lo que serializa. Es **la primera sentencia**: si antes hubiera un
  `findById` de JPA, el snapshot quedaría fijado ahí y el paso 3 volvería a ser vulnerable —
  ese es exactamente el bug #1.
- **La fila que se bloquea es la de `subscription`, no la de `organization`** (§6.0.1). No es una
  preferencia: es lo que evita el deadlock contra el alta de sede de 02.01, que toma la
  suscripción primero y no puede hacer otra cosa. Quien "simplifique" esto volviendo a
  `organization` reintroduce un deadlock entre etapas que no se reproduce con un solo usuario.
  Un comentario en el método tiene que decirlo, y el test K-3 de 02.01 es el que lo prueba.
- El paso 3 excluye la fila objetivo, así que el `FOR SHARE` nunca cae sobre la fila que el paso
  5 va a actualizar: no hay escalada S→X sobre la misma fila y, además, las escritoras ya están
  serializadas por el paso 1, así que tampoco hay dos transacciones tomando locks finos a la vez.
  Los dos ingredientes del bug #2 quedan eliminados por separado.
- `READ_COMMITTED` es cinturón sobre tirantes: aunque el `FOR SHARE` ya garantiza frescura,
  declararlo evita que una lectura agregada en el futuro reintroduzca el problema en silencio.
  Es una anotación, cuesta nada.

**Alcance del invariante.** "Al menos un `ORG_ADMIN` vigente por organización" es sobre la
organización, no sobre el consultorio. Un consultorio puede quedar sin `CONSULTORIO_ADMIN`: lo
administra el `ORG_ADMIN`. Que 01.03 no invente un segundo invariante que la matriz no pide.

### 6.2 Self-revoke

Caso propio: el actor se quita a sí mismo el rol administrativo. Es una variante del anterior y
se resuelve en la misma transacción, con una diferencia: se rechaza **aunque queden otros
admins**, si el rol que se quita es su último rol administrativo en la organización. Motivo:
después de la operación, el actor no puede deshacerla, y una operación irreversible por
distracción no debería ser un `PATCH` cualquiera. Código: `409 self-revoke-not-allowed`.

Si un `ORG_ADMIN` quiere irse, el camino es que **otro** admin lo revoque. Si es el único, tiene
que promover a alguien antes. Es exactamente la secuencia que el invariante quiere forzar.

**El fundador.** `is_founder` ya existe en `membership` (V3) y su comentario le asigna a 01.03 el
invariante "el fundador no puede ser desvinculado por otro admin". Se implementa: un
`ORG_ADMIN` no fundador no puede revocar la membership del fundador. El fundador sí puede
revocarse a sí mismo, sujeto a 6.1 y 6.2. Un `PLATFORM_ADMIN` con acceso de soporte sí puede,
y queda auditado con `SUPPORT_ACCESS_USED`.

### 6.3 Resolución de permisos concurrente con revocación

**La carrera.** El actor pasa la evaluación de permiso; antes del commit de su operación, otro
admin le revoca la membership. La operación se confirma con un permiso que ya no existe.

**Qué se puede y qué no.** No se puede llevar a cero sin serializar todo el sistema. Lo que se
hace:

1. La evaluación ocurre **dentro** de la transacción de negocio, no en el filtro. La ventana pasa
   de "un request" a "una transacción".
2. Para las mutaciones que tocan memberships, la evaluación ocurre **después** del lock de 6.1:
   ahí la ventana es cero, porque la revocación del actor también necesita ese lock.
3. Para el resto de las mutaciones sensibles, la membership del actor se lee con `FOR SHARE` al
   evaluar. La revocación hace `UPDATE` sobre esa fila: si el evaluador ya la tiene en compartido,
   la revocación espera al commit del actor. Es una espera de milisegundos y elimina el
   entrelazado.
4. Las lecturas no toman locks. Una lectura autorizada con una membership revocada un
   milisegundo antes es un no-problema: el request siguiente ya falla.

**Lo que no se hace: cachear.** Ni el evaluador ni `MembershipDirectory` llevan caché. Si algún
día el SLO lo exige, es un ADR nuevo, no una optimización silenciosa — igual que dice hoy el
javadoc de `MembershipDirectory`.

### 6.4 Grant duplicado y revocación simultánea

Dos admins otorgan `auditoria:read-clinica` a la misma membership a la vez. El unique sobre
`grant_activo` deja pasar una y rechaza la otra con clave duplicada. La segunda **no** es un
500: `saveAndFlush` → `DataIntegrityViolationException` → excepción de dominio → **409
`grant-already-active`**, sin volver a tocar la sesión JPA (lección 3).

Grant y revoke simultáneos del mismo permiso: los dos hacen `UPDATE` de la misma fila y el
optimistic lock de `@Version` decide; el perdedor recibe
`ObjectOptimisticLockingFailureException` → **409 `concurrent-modification`**.

### 6.5 Aceptación doble de una invitación — fuera de alcance (D-1)

No hay invitaciones en 01.03, así que esta carrera no existe en esta etapa. Se deja escrita la
forma resuelta, porque el análisis ya está hecho y la etapa que reciba RF-M05-002 lo va a
necesitar: se cierra **sin lock**, con compare-and-set —
`UPDATE membership_invitation SET estado='ACEPTADA', … WHERE id=:id AND estado='PENDIENTE'`—; si
afecta 0 filas alguien llegó antes; si ya estaba `ACEPTADA` por la misma cuenta se replica el
mismo resultado (idempotencia de §34) y si estaba resuelta de otra forma es **409**. La creación
de la membership va en la misma transacción, después del `UPDATE` exitoso y **bajo el lock de la
suscripción** (§6.0.1) — el alta de membership además consume `MAX_MIEMBROS_ACTIVOS`, así que esa
transacción tiene que entrar por `PlanGate` y por lo tanto ya toma esa fila.

**Lo que no se hace, ni entonces ni ahora:** leer el estado, decidir en Java y después escribir.
Ese patrón es la carrera, no la solución.

### 6.5-bis Cambio de rol concurrente sobre la misma membership

Es la carrera que **sí** queda en 01.03 donde antes estaba la de invitaciones. Dos admins cambian
el rol de la misma persona al mismo tiempo, o uno cambia el rol mientras el otro revoca. Las dos
transacciones toman la suscripción (§6.0.1), así que están serializadas; la segunda entra con el
estado ya cambiado y decide contra él: si la membership quedó `REVOCADA`, el cambio de rol es
**409 `membership-not-active`**; si sigue activa, el cambio se aplica sobre el rol nuevo y la
auditoría registra la transición real, no la que el actor creía. El `@Version` de `Membership`
queda como red: si alguien mueve el lock de lugar, el perdedor recibe **409
`concurrent-modification`** en vez de pisar el cambio ajeno.

### 6.6 Isolation asumida

- **Por defecto:** el de MySQL 8.4, `REPEATABLE READ`. No se cambia globalmente: cambiarlo
  afectaría a todo el sistema por un puñado de transacciones.
- **`READ_COMMITTED` declarado por transacción** en las que deciden un invariante de conteo:
  revocación y cambio de rol de admin. (Aceptación de invitación y alta de membership quedaron
  fuera por D-1; cuando vuelvan, entran por `PlanGate`, que ya declara `READ COMMITTED` él mismo
  y verifica en runtime que nadie lo eluda.)
- **El orden de bloqueo es `subscription` → `organization`** en todo el sistema (§6.0.1). No es
  una propiedad de esta etapa: es la que evita el deadlock contra 02.01.
- **En ninguna se asume que un lock resuelve la visibilidad.** Cada conteo que decide es una
  lectura con lock.
- **Nunca `SERIALIZABLE`.** Convierte cada lectura en un lock compartido y reintroduce
  exactamente el escenario del bug #2 a escala del sistema entero.

---

## 7. Contrato API

**Todos los cambios son aditivos ⇒ `0.3.0` → `0.4.0` (minor).** No se modifica la firma, el
esquema de respuesta ni el significado de ningún endpoint existente. Dos matices que hay que
mirar de frente antes de afirmarlo:

1. `POST /api/v1/auth/register` gana un **409** que antes no podía devolver. Agregar un código de
   error a un endpoint es aditivo en el esquema, pero **cambia lo que un cliente tiene que
   manejar**. La regla del repo (aditivo → minor) se sostiene, con la condición de que el
   frontend maneje el 409 en el mismo ciclo de coordinación. Se anota como cambio coordinado, no
   como incompatible.
2. `POST /organizations`, `POST …/subscription/transitions` y `POST …/subscription/plan-changes`
   pasan de "publicados e inalcanzables" a "ejecutables por un `PLATFORM_ADMIN`". El contrato no
   cambia; **el comportamiento observable sí**. Es la corrección de un defecto, y como tal va
   dicha en el registro de cierre y en el changelog del contrato.

### 7.1 Endpoints nuevos

| Método y ruta | RF | Permiso | Notas |
|---|---|---|---|
| `GET /api/v1/organizations/{orgId}/memberships` | RF-M01-002 | `colaborador:read` | Paginado. Filtros `estado`, `roleCode`, `consultorioId` |
| `GET /api/v1/organizations/{orgId}/memberships/{id}` | RF-M01-002 | `colaborador:read` | |
| `PATCH /api/v1/organizations/{orgId}/memberships/{id}` | RF-M02-004 | `colaborador:manage` | Cambia `roleCode` y/o `consultorioId`. Motivo obligatorio |
| `POST /api/v1/organizations/{orgId}/memberships/{id}/revoke` | RF-M01-002 | `colaborador:manage` | Cierra vigencia, `estado = REVOCADA`. Motivo obligatorio. **No borra** |
| `POST /api/v1/organizations/{orgId}/memberships/{id}/grants` | RF-M02-004 | `colaborador:manage` | Cuerpo: `permissionCode`, `reason`, `validUntil` opcional |
| `DELETE /api/v1/organizations/{orgId}/memberships/{id}/grants/{permissionCode}` | RF-M02-004 | `colaborador:manage` | Baja lógica del grant |
| `POST /api/v1/organizations/{orgId}/memberships/{id}/suspend` · `/reactivate` | RF-M01-002 | `colaborador:manage` | Transiciones de `estado`. Motivo obligatorio en la suspensión |
| `GET /api/v1/organizations/{orgId}/audit-events` | RF-M24-002/003/004 | `auditoria:read` | Paginado; filtros `entityType`+`entityId`, `actorAccountId`, `from`+`to` |
| `GET /api/v1/me/permissions` | — (ver D-5) | contexto válido | Permisos efectivos del contexto activo. **Insumo de UX** |
| `GET /api/v1/platform/organizations/{orgId}/support-access` | — (matriz §8) | `PLATFORM_ADMIN` | |
| `POST /api/v1/platform/organizations/{orgId}/support-access` | — (matriz §8) | `PLATFORM_ADMIN` | Motivo y duración obligatorios |
| `DELETE /api/v1/platform/support-access/{id}` | — (matriz §8) | `PLATFORM_ADMIN` | |
| `GET /api/v1/platform/roles` · `POST` · `DELETE /{id}` | — (D-2) | `PLATFORM_ADMIN` | **Solo si la sub-decisión de D-2 lo habilita.** El origen del primero ya no depende de estos endpoints: sale del seed (§2.7) |

`GET /me/permissions`, `support-access` y `platform/roles` **no tienen RF que los respalde**.
Los tres siguen como decisión pendiente (D-5, D-3, y la sub-decisión de D-2) y ninguno se
implementa antes de que el usuario decida. El plan §10.4 es explícito: no se inventan endpoints.

**Los endpoints de invitación no están en esta tabla** porque D-1 los dejó fuera (§1.2). Con eso,
la etapa pasa de 17 endpoints nuevos a 13 —tres de ellos, los de `/platform/roles`, solo si la
sub-decisión de D-2 los habilita—, y **ninguna ruta pública nueva**: todo lo que entra
está detrás de contexto de tenant o de `/platform/**`.

### 7.2 El flujo de invitación — diferido, y qué queda dicho para cuando vuelva

**D-1 cerrada como B el 23/08/2026: nada de esta sección se implementa en 01.03.** Se conserva
porque el reparto entre módulos ya está fijado por un contrato existente y la etapa que reciba
RF-M05-001 y RF-M05-002 tiene que respetarlo igual. Dos consecuencias inmediatas del
diferimiento, que son parte de lo que abarata la etapa: **no hace falta tocar
`notification.spi.SecureLinkResolver`** —sigue con una sola implementación— y **D-4
(`SecureLinkVault`) deja de bloquear a 01.03**, porque el único puente en memoria que queda es el
de activación y recuperación, que ya existía y cuya limitación ya está declarada.

`identity.spi.AccountDirectory` ya fijó el reparto y hay que respetarlo: *"invitar es de
`organization` y no toca cuentas; aceptar es de `identity`, que crea o vincula la cuenta y llama
a `organization.spi` para activar la membership"*. La flecha `organization → identity` está
prohibida sin excepciones.

```
POST /organizations/{id}/invitations        → organization
    escribe membership_invitation (PENDIENTE) + token_hash
    encola por notification.spi (tipo INVITACION_COLABORADOR, referenciaTokenId opaca)
    audita INVITATION_SENT                                  ← todo en una transacción

POST /auth/invitations/accept               → identity
    resuelve o crea la cuenta (AccountDirectory.existeCuentaCon)
    llama organization.spi.MembershipInvitations.accept(token, accountId)
        → CAS sobre estado + creación de la membership (§6.5)
    audita CUENTA_CREADA (identity) + INVITATION_ACCEPTED (organization)
```

**Consecuencia que habrá que resolver antes de implementarlo, en la etapa que lo reciba.**
`notification.spi.SecureLinkResolver`
tiene hoy **una sola implementación** (`IdentitySecureLinkResolver`) y su firma recibe el `tipo`.
Con `organization` como dueña del token de invitación harían falta dos beans de la misma
interfaz. Se agrega `boolean supports(NotificationType)` al puerto y un compositor en
`notification.infrastructure` que despacha por tipo. Es un cambio aditivo a un `spi` **interno**;
no toca el contrato HTTP. Y arrastra el problema de `SecureLinkVault` (D-4): el enlace en claro
tiene que llegar del hilo que encola al worker que envía, y hoy ese puente es un mapa en memoria.

---

## 8. Códigos de respuesta

Reglas heredadas, aplicadas sin excepción. La clave es que el mapeo lo hace **un solo lugar**
(`OrganizationProblemHandler`, a partir de `DenialKind`), no cada controller.

| Situación | Código | `type` | Por qué |
|---|---|---|---|
| Referencia a otra organización (org, membership, grant, evento de auditoría, cuenta) | **404** | `…/not-found` | Un 403 confirma que existe: bastaría probar ids consecutivos para enumerar los clientes del SaaS |
| Membership de otra organización, aun sabiendo el actor que ese id existe en la suya | **404** | `…/not-found` | El id no está en su alcance. Misma regla |
| Request sin contexto de tenant sobre una ruta que lo exige | **403** | `…/missing-tenant-context` | Nunca 401: el interceptor del frontend borra el token ante cualquier 401 y arranca un bucle de login |
| Actor con contexto válido, sin permiso, sobre un recurso **de su propia organización** cuya existencia ya conoce | **403** | `…/forbidden` | El 404 acá no protege nada y le miente al usuario |
| Actor sin `PLATFORM_ADMIN` sobre una ruta `/platform/**` | **403** | `…/forbidden` | Las rutas de plataforma son públicas en el contrato: su existencia no es secreta |
| Último admin | **409** | `…/last-admin-required` | Estado incompatible, no permiso |
| Self-revoke del último rol administrativo | **409** | `…/self-revoke-not-allowed` | Ídem |
| Revocar al fundador siendo otro admin | **403** | `…/forbidden` | Es una restricción de quién, no de estado |
| Grant ya activo | **409** | `…/grant-already-active` | Clave duplicada traducida |
| Cambio de rol sobre una membership ya revocada | **409** | `…/membership-not-active` | Estado incompatible (§6.5-bis) |
| Modificación concurrente (optimistic lock) | **409** | `…/concurrent-modification` | |
| Suscripción `SUSPENDIDA` + mutación | **409** | `…/subscription-suspended` | Camino existente de `TenantContextFilter`, sin cambios |
| `Idempotency-Key` repetida con payload distinto (registro) | **409** | `…/idempotency-key-conflict` | Mismo `type` que ya usa `OrganizationProblemHandler` |
| `permissionCode` fuera del catálogo | **400** | `…/validation-error` | |
| Rango de auditoría demasiado amplio | **400** | `…/validation-error` | Ver D-8 |

Ninguna respuesta de error incluye `com.akine`, `org.springframework` ni stacktrace
(ADR-0005; escenario diferido 11).

---

## 9. Frontend — `appKine-web`

**Los guards de permiso son UX, no seguridad.** Cada pantalla que oculten está igualmente
protegida en el backend, y el test que lo prueba es de integración del backend, no de Angular.

| Qué | Dónde | Notas |
|---|---|---|
| Cliente generado desde el contrato `0.4.0` | `src/app/api/generated/` | Regenerar y actualizar `environment.ts`; `npm run api:check` es el gate (`scripts/check-contract-version.mjs`) |
| `permissions.store.ts` | `src/app/core/services/` | Signal con el `Set<string>` de `GET /me/permissions`. **Se recarga en cada cambio de contexto y se limpia al salir**, junto a `tenant-context.store.ts`. Nunca se persiste en `localStorage`: un permiso persistido es un permiso obsoleto |
| `permission.guard.ts` | `src/app/core/guards/` | Junto a `auth.guard.ts` y `context.guard.ts`. Redirige a una pantalla de "no tenés permiso", no a login |
| Directiva `*akinePermiso` | `src/app/shared/directives/` | Oculta o deshabilita acciones. Preferir **deshabilitar con explicación** a ocultar: una acción que desaparece deja al usuario sin saber a quién pedirle acceso |
| `features/organization/pages/colaboradores/` | listado, cambio de rol, suspensión y revocación con motivo. **Sin pantalla de invitación** (D-1) | Estados: carga, vacío, error, permiso insuficiente, conflicto (409 de último admin / self-revoke con mensaje accionable), éxito |
| `features/organization/pages/auditoria/` | tabla paginada con los tres filtros de M24 | Sin exponer ids técnicos como dato principal (RNF-M24-005) |
| `error.interceptor.ts` | manejar `404` sobre ruta de negocio **con contexto activo** → limpiar contexto y llevar al selector con explicación (§4.3). `403 forbidden` → mensaje, **nunca** logout | Hoy solo el 401 dispara limpieza de sesión, y eso no cambia |
| `features/auth/pages/register/` | manejar el nuevo `409 idempotency-key-conflict` | El caso real: el usuario edita el formulario y reenvía con la misma clave. El mensaje correcto es "recargá el formulario", y la clave se regenera |
| `features/platform/` | pantallas de acceso de soporte y roles de plataforma | Solo si D-2 y D-3 se aprueban |

---

## 10. Plan de tests

Base actual, según `CLAUDE.md` §7 (2026-08-23 15:32): **905 unitarios en verde y 21 de
integración con 1 fallo abierto** (escenario diferido 8). El brief de esta tarea menciona
1010 + 23; la diferencia se resuelve corriendo la suite antes de arrancar la implementación, no
asumiendo un número. **El escenario 8 tiene que estar en verde antes de empezar 01.03**: es un
fallo de concurrencia sobre el `PlanGate`, y arrancar una etapa de permisos sobre una carrera
abierta es acumular dos investigaciones en una.

### 10.1 Unitarios de policy

- **Matriz completa dirigida por tabla.** Un `@ParameterizedTest` que recorre las 12 acciones ×
  6 roles de la matriz §2 y verifica que `RolePermissions` devuelve exactamente el valor de la
  tabla. La tabla es el fixture; si la matriz cambia y el código no, falla.
- Semántica de cada valor de la matriz §3: `Sí`, `No`, `Global`, `Limitado`, `Soporte`,
  `Restringido`, `No por defecto`, `Según permiso`, `Según rol clínico`, `Propio`,
  `Propia autorizada`, `Catálogo global`. Uno por valor, incluidos los que en F1 siempre
  deniegan — probar que "Según rol clínico" deniega en F1 es tan importante como que "Sí"
  permita, porque documenta que no está implementado por descuido.
- Alcance: membership de consultorio no habilita otro consultorio; membership de organización
  habilita todos; desempate "la específica gana a la de alcance organización".
- Vigencia: `validFrom` futuro, `validUntil` pasado, `active = 0`, `estado != ACTIVA`. Las
  cuatro deniegan, y las cuatro por separado.
- Invariantes: último admin, self-revoke, fundador. Sin base de datos, con puertos falsos.
- `PLATFORM_ADMIN` **sin** `support_access` no accede a datos del tenant; **con** `support_access`
  vigente sí, y queda `SUPPORT_ACCESS_USED`.
- ArchUnit: `organization` no importa `identity`; `platform` no importa `organization`;
  `identity` no importa `organization.domain`; ningún módulo escribe `audit_event` salvo
  `platform`.

### 10.2 Integración por matriz

Testcontainers/MySQL. Una tabla `(rol, endpoint, método) → status esperado` que cubre los 13
endpoints nuevos × los 6 roles. **Las memberships de los seis roles se siembran desde el
repositorio**, no por HTTP: D-1 dejó fuera el alta de membership y ningún endpoint las crea
(§1.2). Es tediosa y es el corazón de la etapa: es lo que prueba que la
matriz se cumple *server-side*, que es literalmente el criterio de aceptación de 01.03.

Además:

- Cross-tenant sobre cada endpoint nuevo → **404** en todos. Nunca 403.
- Cuenta objetivo de otra organización en `PATCH /memberships/{id}` → **404**.
- Sin contexto → **403 missing-tenant-context**, nunca 401.
- Sin permiso sobre recurso propio → **403 forbidden**.
- Uniques con alcance tenant: la misma cuenta con membership en dos organizaciones distintas →
  ambas válidas y ninguna ve a la otra; dos grants del mismo permiso sobre la misma membership →
  409.
- Auditoría: cada mutación de §5.2 deja **exactamente una** fila con actor, tenant, entidad,
  estado anterior y nuevo. Y la contraparte: una mutación que falla no deja ninguna.
- Inmutabilidad: `UPDATE` y `DELETE` nativos sobre `audit_event` fallan con el `SIGNAL`.
- `auditoria:read` no devuelve eventos de otra organización aunque se pase el `entityId` correcto.

### 10.3 Concurrencia — con hilos reales contra MySQL real

No se aceptan como cubiertos con mocks. Cada uno arranca dos (o N) hilos contra el mismo
Testcontainer y verifica el estado **consultando la base**, no el código de respuesta.

| # | Escenario | Invariante que prueba |
|---|---|---|
| C-1 | Dos revocaciones simétricas de los dos últimos `ORG_ADMIN` | Queda **exactamente uno**. Una transacción recibe 409. Es el test que prueba que el `FOR SHARE` posterior al lock resuelve la visibilidad, y el que impide que alguien lo "simplifique" a un `SELECT` común |
| C-2 | Revocación del actor concurrente con una operación que el actor autorizó | O la operación se confirma con permiso válido, o falla. Nunca se confirma sin permiso |
| C-3 | Dos grants del mismo permiso a la misma membership | Una fila activa. La segunda es **409**, no 500 (regresión de la lección 3) |
| C-4 | Cambio de rol y revocación simultáneos sobre la misma membership | Un solo desenlace, un solo evento de auditoría, y la segunda transacción decide contra el estado ya cambiado (§6.5-bis). No 500 |
| C-5 | **Alta de sede de 02.01 concurrente con una mutación de membership en la misma organización** | **Ninguna muere por `CannotAcquireLockException`.** Es el test del orden de bloqueo único (§6.0.1): con el orden invertido falla, con el orden correcto pasa. Es el mismo escenario que 02.01 llama K-3. **En 01.03 no se puede escribir todavía**: el alta de sede no existe hasta 02.01. Lo que sí se hace en 01.03 es dejar el lock con un comentario que cite §6.0.1 y una prueba que afirme, contra la base, que la transacción de membership toma la fila de `subscription` — para que el cambio de orden no se pierda en el camino entre etapas |
| C-6 | N=10 revocaciones simultáneas cuando quedan 3 admins | Sobreviven exactamente 3−(éxitos) y nunca 0 |
| C-7 | Grant y revoke simultáneos del mismo grant | Optimistic lock resuelve; el perdedor recibe 409 |
| C-8 | Dos `POST /auth/register` con la misma `Idempotency-Key` y payloads distintos | Uno crea, el otro **409**. Cierra el escenario diferido 7b |

Además, **cada test de concurrencia verifica que ninguna respuesta sea 500**. Los tres bugs de
01.01/01.02 se manifestaron como 500 sobre un contrato que prometía otra cosa; el 500 es la
señal, y hay que buscarla explícitamente.

### 10.4 Frontend

Vitest: `permissions.store` (carga, limpieza al cambiar contexto, no persistencia),
`permission.guard`, la directiva, y el manejo de 404-con-contexto y de 403 en el interceptor.

### 10.5 E2E (Playwright)

- Acceso denegado: un `PROFESIONAL` no ve la acción de invitar, y si llega a la URL directo
  recibe la pantalla de permiso insuficiente **porque el backend respondió 403**, no porque el
  guard lo frenó. Se verifica interceptando la llamada.
- Auditoría reconstruible: invitar → aceptar → cambiar rol → revocar, y después leer la
  auditoría y encontrar los cuatro eventos con actor y estados. Es el criterio de aceptación
  "toda mutación sensible es reconstruible".
- Los escenarios diferidos 10 y 11 de `tests-diferidos.md`, que siguen sin escribirse.

---

## 11. Design challenge — respondido

### 1. Ownership

| Tabla | Propietario | Quién más la toca |
|---|---|---|
| `membership` (expandida) | `organization` | Nadie. `platform` la ve solo por `MembershipDirectory`, que devuelve un record del `spi` |
| `membership_grant` | `organization` | Nadie |
| `platform_role` | `organization` | Nadie. `platform` la ve por el puerto invertido nuevo |
| `support_access` | `organization` | Nadie |
| `audit_event` (índices y triggers) | `platform` | Nadie escribe fuera de `AuditTrail` |
| `onboarding_registro` (columna) | `identity` | Nadie |

### 2. Ciclos

Sí, unidireccional, y pasa ArchUnit. Las flechas nuevas son: `organization → platform.spi`
(ya existía), `identity → organization.spi` (ya existía), `organization → notification.spi`
(nueva, y `notification` no depende de `organization`, así que no cierra nada). El puerto
`PlatformRoleDirectory` se declara en `platform.spi` y lo implementa
`organization.infrastructure.tenant`, exactamente como `MembershipDirectory`: `platform` nunca
compila contra `organization`.

El punto que hay que vigilar es el de las excepciones (§3.2): `identity` **lanza** excepciones de
`organization.domain` sin importarlas. Si alguien las captura, aparece el import y ArchUnit
falla — y eso está bien, es la red funcionando.

### 3. Tenant

`membership_grant` y `support_access` llevan `organization_id NOT NULL` y todos sus uniques lo
incluyen. `platform_role` **no lo lleva**, y esa excepción a ADR-0004 está formalizada en
**ADR-0020**, escrito para esta etapa porque ADR-0019 cierra su lista diciendo que un comentario
en la migración no alcanza. Sin ese ADR, la migración no se commitea; con él, la lista de
excepciones pasó a vivir en dos archivos y hay que leer los dos.

Los uniques con columna generada (`consultorio_scope` —ya aplicado en V10— y `grant_activo`) son la
parte del diseño con más chance de estar mal: se validan con tests de integración que insertan
la colisión esperada y la no-colisión esperada, no leyendo el DDL.

### 4. Reglas maestras

No se confunde nada de la lista de `AGENT.md` §8 porque esta etapa no toca clínica ni economía.
Sí toca dos distinciones propias y hay que sostenerlas:

- **Rol de seguridad ≠ disciplina ≠ especialidad ≠ habilitación** (RN-M05-005). El catálogo de
  roles sigue cerrado en seis valores; `is_founder` sigue siendo un atributo; los grants son
  permisos, no roles. Prohibido cualquier `RoleCode` nuevo (RN-M05-006).
- **Membership ≠ cuenta ≠ persona.** Revocar una membership no toca la cuenta; bloquear una
  cuenta no revoca sus memberships. Son dos máquinas de estado separadas, en dos módulos, y su
  única coordinación es la revocación de sesiones que `AccountAdminService` ya hace.

### 5. Baja lógica

Ningún borrado físico. Revocar una membership cierra vigencia y marca estado; el grant se da de
baja lógica; la invitación pasa a un estado terminal; `audit_event` gana triggers que hacen el
borrado imposible incluso por SQL directo. `V11` borra un **índice**, no datos.

El único punto donde la baja lógica cuesta es el unique: por eso las columnas generadas, en vez
de recurrir a un unique parcial que MySQL no tiene.

### 6. Contrato

Aditivo ⇒ **minor, 0.4.0**. Con dos matices dichos de frente en §7: el nuevo 409 en `/register`
obliga a un cambio coordinado en el frontend, y tres endpoints ya publicados cambian de
"inalcanzables" a "ejecutables". Ninguno de los dos es incompatible en el sentido de SemVer,
y los dos van al registro de cierre.

### 7. Ruta crítica

01.03 es el tercer eslabón de la ruta de `AGENT.md` §9: `Tenant → Consultorio →
Membership/Permisos → …`. Sus dos cimientos existen: `organization` (01.01) y `identity` (01.02).
**No adelanta nada**: los permisos clínicos y económicos se declaran en el catálogo pero se
implementan en la fase de su módulo, y el evaluador está diseñado para que agregarlos sea sumar
filas a `RolePermissions`, no rehacer nada.

Lo que sí hay que decir: 01.02 **no está cerrada** — el escenario 8 falla y hay 50 rutas sin
commitear. Arrancar 01.03 con eso abierto viola el plan §10.2.5 ("si aún figuran como
pendientes, completar primero la compuerta de evidencia o detener la etapa con un bloqueo
explícito"). El orden correcto es: cerrar 01.02, publicar 0.3.0, regenerar el cliente, y recién
después esta etapa.

### 8. El caso que rompe el diseño

**El escenario:** una organización tiene dos `ORG_ADMIN`, A y B. A abre la pantalla de
colaboradores y hace clic en "revocar" sobre B. En el mismo instante —y sin que ninguno de los
dos lo sepa— B hace clic en "revocar" sobre A. Las dos peticiones llegan al backend con
milisegundos de diferencia.

**Por qué rompe un diseño razonable.** La implementación obvia es: dentro de la transacción,
cargar la membership objetivo con JPA, contar cuántos admins vigentes quedan sin contarla, y si
el conteo es mayor que cero, revocar. Con `SELECT … FOR UPDATE` sobre las filas de membership
parece resuelto: el lock serializa. **No lo está.** El `findById` de JPA es una lectura
consistente y fija el snapshot de la transacción; el conteo posterior, aunque el lock ya se haya
adquirido y el competidor haya commiteado, se sirve del snapshot y **ve a un admin que ya no
existe**. Las dos transacciones cuentan uno, las dos pasan, y la organización queda con cero
administradores. No hay nadie que pueda promover a otro: el tenant queda administrativamente
muerto y solo se recupera con SQL manual en producción. Es exactamente el bug que apareció en
01.01/01.02 cuando alguien corrió dos hilos reales contra MySQL real, y aparece acá otra vez
porque la forma del problema es la misma.

Con D-1 cerrada como B, además, el daño es peor de lo que era cuando este documento se escribió:
sin alta de memberships y sin invitación, un tenant que se queda sin `ORG_ADMIN` **no tiene
ninguna vía de rescate dentro del producto**. Ni siquiera queda el rodeo de invitar a alguien
nuevo. Se repara con SQL manual o con un `PLATFORM_ADMIN` amparado por acceso de soporte, y las
dos cosas son operación, no producto.

Y hay una segunda capa: si el conteo toma `FOR SHARE` sobre las filas de los otros admins, A
bloquea en compartido la fila de B mientras la actualiza, y B bloquea en compartido la de A
mientras la actualiza. Al commit, cada una necesita escalar a exclusivo sobre una fila que la
otra tiene en compartido: **deadlock**, que es el segundo bug de la lista, con las mismas
transacciones simétricas.

**Cómo lo resuelve el diseño**, con tres cosas que hacen falta las tres:

1. **Un único punto de bloqueo, ordenado y de grano grueso.** La primera sentencia de la
   transacción es `SELECT id FROM subscription WHERE organization_id = ? FOR UPDATE`. Todas las
   mutaciones de membership de esa organización pasan por ahí, así que dos escritoras nunca
   compiten por locks finos y el deadlock simétrico no puede formarse: solo hay un recurso que
   disputar y se toma siempre en el mismo orden. **Que sea la fila de `subscription` y no la de
   `organization` no es indiferente**: es lo que evita el segundo deadlock, el que se forma
   contra el alta de sede de 02.01, que toma la suscripción primero y no puede hacer otra cosa
   porque el `PlanGate` abre su propia transacción (§6.0.1).
2. **El conteo que decide es una lectura con lock, no una lectura consistente.** El
   `SELECT COUNT(*) … FOR SHARE`, y encima excluyendo la fila objetivo para no escalar sobre la
   fila que después se actualiza. Un `SELECT` común ahí reintroduce el bug aunque el lock del
   paso 1 esté tomado, y ese es el punto que el diseño tiene que dejar escrito: **el lock
   serializa el acceso, no la visibilidad.**
3. **`READ_COMMITTED` declarado en la transacción**, para que ninguna lectura agregada más
   adelante —un `findById` que alguien mueva de lugar, un `@EntityGraph` que traiga la
   colección— pueda volver a fijar un snapshot viejo sin que nadie se entere.

**Cómo se prueba que no volvió.** El test C-1 de §10.3: dos hilos reales contra MySQL real, y la
verificación **contra la base**, contando admins vigentes al final. Un test con mocks pasaría con
el diseño roto, que es precisamente por qué los tres bugs de esta semana no aparecieron antes.

---

## 12. Riesgos y decisiones

Nada de lo que sigue abierto se implementa hasta que el usuario decida. Cada una tiene opciones y
consecuencias, no una recomendación disfrazada de hecho.

| Estado | Decisiones |
|---|---|
| **Cerradas el 23/08/2026 por el usuario** | D-1 (opción B), D-2 (opción A), y el orden de bloqueo `subscription` → `organization` de §6.0.1, que además cierra la D-11 de 02.01 |
| **Abiertas, bloqueantes para implementar** | D-3 (acceso de soporte), D-5 (`GET /me/permissions`), D-7 (alcance de `auditoria:read`), D-8 (límites de la consulta), D-9 (`GET /organizations/{orgId}`), y la sub-decisión de D-2 sobre `POST /platform/roles` |
| **Abiertas, no bloqueantes para 01.03** | D-4 (`SecureLinkVault`), D-6 (parámetros), D-13 (discriminador del unique), D-14 (bootstrap en base nueva) |

### D-1 — ¿El flujo de invitación completo entra en 01.03? — **CERRADA (opción B)**

**Decidida por el usuario el 23/08/2026.** Entra **solo asignar y revocar rol** sobre memberships
que ya existen. **No entra el flujo de invitación por mail.**

Es una **desviación declarada** de RF-M05-001 y RF-M05-002 —el plan de la etapa los nombra en sus
requerimientos— y tiene que figurar como tal en el registro de cierre, no quedar tácita. Etapa
destino candidata: AKINE-02.01, que ya toca onboarding de sede; el usuario todavía no la fijó.

Qué gana la etapa: se cae la tabla `membership_invitation` y sus dos uniques con columna generada,
se caen cinco endpoints —dos de ellos públicos—, se cae el cambio al `spi` de `notification`, y
**D-4 deja de bloquear a 01.03**. Qué pierde: está en el recuadro de §1.2, y lo más grueso es que
al cierre de la etapa un tenant no puede sumar ningún colaborador.

Descartadas: **A** (entra completo), por tamaño y por la dependencia de D-4; **C** (invitación
solo para cuentas existentes), porque parte el alta de un colaborador nuevo en dos pasos donde el
negocio espera uno y aun así arrastra la tabla y el puente de enlaces.

### D-2 — Origen de dato de `PLATFORM_ADMIN` y bootstrap del primero — **CERRADA (opción A)**

**Decidida por el usuario el 23/08/2026.** Tabla `platform_role` propia, y el primer
`PLATFORM_ADMIN` entra por un **seed de migración** que resuelve un **email concreto versionado
en el repositorio**. El usuario aceptó explícitamente el email versionado a cambio de que el alta
sea auditable y reproducible.

Consecuencias formalizadas: **ADR-0020** (la tabla no lleva `organization_id`; sin ese ADR la
migración no se commitea) y §2.7 de este documento (el seed, y las tres trampas que trae: el
email en el repo, el cruce de propiedad entre módulos en SQL, y el hecho de que en una base nueva
inserte cero filas sin fallar).

Descartadas: **B** (comando fuera de HTTP), porque agrega un paso operativo no versionado y el
alta deja de ser reproducible; **C** (lista en `application.yml`), porque deja el permiso más alto
del sistema sin auditoría, sin vigencia y sin revocación en caliente, contra la matriz §7; **D**
(no implementarlo), porque deja `POST /organizations` sin ningún actor posible.

**Sub-decisiones que siguen abiertas:**

- **¿Un `PLATFORM_ADMIN` puede otorgarle el rol a otro por HTTP?** Si sí, comprometer esa cuenta
  compromete la plataforma entera. Si no, cada alta posterior es otra migración o una operación
  manual. De esto depende si `POST /platform/roles` existe (§7.1).
- **D-14 (§2.7):** si el bootstrap es de dos pasos —desplegar, registrar la cuenta, aplicar una
  migración posterior— o si el seed además crea la fila de `cuenta` con credencial inutilizable y
  fuerza la recuperación de contraseña para tomar posesión. Lo segundo hace el alta de un solo
  paso y cruza la frontera de propiedad **escribiendo**, no solo leyendo.

### D-3 — Modelo de acceso de soporte

La matriz §8 lo declara hueco con etapa destino 01.03 (diseño), "implementación mínima en F1,
completa en F8". Falta decidir tres cosas y ninguna está en la spec:

| Pregunta | Opciones | Consecuencia |
|---|---|---|
| Duración por defecto | 1 h · 4 h · 24 h | Corto = fricción operativa real en soporte; largo = ventana de acceso a datos de salud ajenos |
| Quién lo otorga | El propio `PLATFORM_ADMIN` con motivo · otro `PLATFORM_ADMIN` (cuatro ojos) · un `ORG_ADMIN` del tenant | Autoconcedido es operable pero es el modelo más débil. Cuatro ojos exige dos personas de plataforma disponibles. Aprobado por el tenant es el más fuerte y no sirve para el caso "el tenant no puede entrar" |
| Si el tenant se entera | Notificación al `ORG_ADMIN` al otorgarse · solo visible en su auditoría · nada | Notificar es lo correcto para datos de salud y es una notificación más en el outbox |

### D-4 — `SecureLinkVault` — **ya no bloquea a 01.03**

Hoy es un `ConcurrentHashMap` en memoria: no sobrevive a un reinicio ni a multi-instancia. Con
D-1 cerrada como B, **01.03 no necesita ese puente**: el único consumidor sigue siendo el correo
de activación y recuperación de 01.02, con la limitación ya declarada. La decisión se mantiene
abierta como deuda de `identity`, y vuelve a ser bloqueante para la etapa que reciba la
invitación de colaborador.

| Opción | Consecuencia |
|---|---|
| **A. Se difiere; 01.03 reutiliza el vault tal cual** | Cero deuda **nueva**: el mismo mecanismo, la misma limitación conocida. Obliga a declarar por escrito que el backend corre en **una sola instancia** hasta que se resuelva, y que un reinicio entre el commit y el envío pierde la invitación (la persona pide otra). Etapa destino a definir |
| **B. Se arregla en 01.03: el token se cifra con una clave del entorno en vez de hashearse** | El enlace se reconstruye desde la base, sin memoria compartida. Cambia la custodia de tokens y **supersede parte de ADR-0017**: es una decisión de identidad, no de permisos, y agranda la etapa |
| **C. Se arregla en 01.03: el correo se redacta en la misma transacción** | Elimina el puente. Pero mete el cuerpo del mail —con el enlace— en `notification_outbox`, que es exactamente lo que T-11 prohíbe |

Dejó de ser la más urgente: D-1 se resolvió sin ella.

### D-5 — `GET /me/permissions`

No lo respalda ningún RF. Lo respalda el apartado "Frontend" de la etapa ("ocultar/deshabilitar
acciones como ayuda UX") y §32.

| Opción | Consecuencia |
|---|---|
| **A. Existe como endpoint propio** | El frontend tiene una fuente única y explícita. Un endpoint sin RF, que hay que justificar en el registro de cierre |
| **B. Los permisos viajan en la respuesta de `POST /auth/context`** | Sin endpoint nuevo. Quedan pegados al momento de seleccionar contexto y hay que recargarlos a mano cuando cambian; el frontend termina inventando cuándo |
| **C. No existe: el frontend deduce del `roleCode` del token** | Nada nuevo. El frontend reimplementa la matriz, y esa copia va a divergir del backend. Es el escenario que §32 quiere evitar |

### D-6 — Parámetros que la spec no fija

| Parámetro | Sin decidir | Sugerencia de rango, para que el usuario elija |
|---|---|---|
| ~~Vigencia de la invitación~~ · ~~reenvío~~ | **cae con D-1** | Viajan con RF-M05-001 a la etapa que lo reciba |
| ¿Un `CONSULTORIO_ADMIN` puede cambiarle el rol a otro `CONSULTORIO_ADMIN` de su sede? | sí | La matriz §6 le da `colaborador:manage` con alcance consultorio, lo que literalmente lo permite. ¿Es lo que se quiere? Era la misma pregunta que antes se hacía sobre invitar |
| ¿Una cuenta puede tener a la vez una membership de alcance organización **y** una de consultorio? | sí | `uk_membership_org_account_scope` (V10, ya aplicado) lo permite. Si se quiere prohibir, es una validación más y otra carrera. Hoy es teórico: 01.03 no crea memberships |

### D-7 — Alcance de `auditoria:read` para `CONSULTORIO_ADMIN`

Ver §5.3. Hoy casi todos los eventos tienen `consultorio_id NULL`.

| Opción | Consecuencia |
|---|---|
| **A. Filtro estricto por `consultorio_id`** | Mínimo privilegio impecable. Un `CONSULTORIO_ADMIN` abre la pantalla y ve una tabla vacía |
| **B. Ve los de su consultorio + los de alcance organización (`NULL`)** | La pantalla tiene sentido. Le muestra transiciones de suscripción y altas de organización, que no le competen |
| **C. `auditoria:read` se restringe a `ORG_ADMIN` en F1** | Contradice la matriz §6, que se lo da a `CONSULTORIO_ADMIN` con alcance consultorio. Requiere revisar la matriz, que es vinculante |

### D-8 — Límites de la consulta de auditoría

Sin RF. Hay que fijar: rango máximo por consulta (¿90 días?), tamaño máximo de página (¿100?),
y si el orden es siempre `occurred_at DESC`. Sin límite, un `from=1970` sobre un tenant grande es
un scan y un problema de disponibilidad, no de permisos.

### D-9 — `GET /organizations/{orgId}` y el "Limitado" de `ORG_ADMIN`

**Contradicción detectada.** Hoy `OrganizationController` línea 199 usa `requireMember`: cualquier
miembro vigente —incluido un `PROFESIONAL`— puede leer los datos de la organización. La matriz §6
le da `tenant:read` solo a `ORG_ADMIN` (alcance organización) y a `PLATFORM_ADMIN` (global).

| Opción | Consecuencia |
|---|---|
| **A. Se ajusta a la matriz: `tenant:read`** | Cumple la matriz. **Rompe al frontend**, que necesita el nombre de la organización para el encabezado en todas las pantallas. Sería un cambio de comportamiento incompatible en un endpoint publicado |
| **B. Se parte la respuesta: perfil básico para cualquier miembro, datos de suscripción solo con `tenant:read`** | Correcto y compatible. Dos proyecciones del mismo recurso, o un campo que aparece según permiso — lo segundo hace la respuesta dependiente del actor, que complica el contrato |
| **C. Se enmienda la matriz** para reconocer una lectura básica del tenant por cualquier miembro | Honesto y explícito. La matriz es vinculante: enmendarla exige aprobación, no un commit |

### D-13 — Discriminador del unique de `membership` frente a la revocación

**Abierta. Nace de `V10`, que ya está aplicada.** `uk_membership_org_account_scope` incluye las
filas históricas, así que una membership revocada sigue ocupando la clave
`(organization_id, account_id, consultorio_scope)` y revincular a la misma persona en la misma
sede choca. Desarrollado en §2.0, con las dos salidas: expandir el discriminador para que las
filas no vigentes salgan del unique, o reusar la fila y perder el historial.

No bloquea a 01.03 —esta etapa no crea memberships—, **sí** bloquea a la que devuelva el alta.
Conviene decidirla mientras la tabla tiene una fila por tenant y cambiar el discriminador es
gratis.

### D-14 — Bootstrap del `PLATFORM_ADMIN` en una base nueva

**Abierta. Sub-decisión de D-2**, detallada en §2.7 punto 3: el seed no puede resolver un email
que todavía no tiene cuenta, y en una base nueva no la tiene. O el bootstrap es de dos pasos
—desplegar, registrar, aplicar una migración posterior—, o el seed crea también la fila de
`cuenta` con credencial inutilizable y fuerza la recuperación de contraseña. Lo segundo cruza la
frontera de propiedad entre módulos **escribiendo**.

### D-10 — Riesgos operativos, sin decisión pero con vigilancia

- **El cambio de discriminador del unique de `membership`, si D-13 se resuelve por la primera
  salida, es irreversible en la práctica** una vez que coexistan una membership vigente y una
  revocada del mismo alcance.
- **Dos seeks indexados más por request** (membership + `platform_role`) sobre el camino más
  caliente. Es coherente con T-7, pero hay que medirlo antes de agregar el tercero — y 02.01 §4.2
  ya reclama ese tercero para revalidar la sede del contexto.
- **La superficie de la etapa bajó con D-1:** 5 migraciones —`V11` a `V16`, sin la de
  invitaciones—, 13 endpoints, un puerto invertido nuevo en `platform.spi`, y frontend sin
  pantalla de invitación. Ya no hace falta partirla en 01.03a y 01.03b.
- **Un tenant sin `ORG_ADMIN` no se recupera desde el producto** (§11.8). Con la invitación
  fuera, el único rescate es SQL manual o `PLATFORM_ADMIN` con acceso de soporte — que depende de
  D-3, todavía abierta. Si D-3 se sigue postergando, el único rescate real es SQL manual.
