# QA manual del §6 — guía de ejecución (G-12)

> Escrita el **11/10/2026** sobre `main` = `5bbf065` (contrato **0.80.0**, última migración
> **`V85`**) y `appKine-web` `main` = `502a094`. **Actualizada el 12/10/2026** sobre `main` =
> `5b4ef93` (contrato **0.85.0**, sin migraciones nuevas: sigue `V85`) y `appKine-web` `main` =
> `7af0110`, con las pantallas y cambios que entraron después: egresos y pagos (web #63, circuito
> nuevo [12](12-egresos-y-pagos.md)), saldo explicable, alertas y selector de autorización (web #58),
> permisos otorgables (api #85, web #48), aviso 503 sin deslogueo (api #91, web #62), fecha de la
> sede en todas las pantallas (web #60), IP de cliente en la sesión (api #93), auditoría de cobro,
> devengo y anulación (api #86), notificaciones fallidas (web #46), importación de aranceles
> (web #28) y los E2E nuevos de web #64. **Ejecución pendiente del usuario.** Las consultas SQL de
> esta guía se validaron contra el esquema real (§8): corren, pero nadie corrió todavía los pasos
> contra la pantalla.

El QA manual del §6 de [`CLAUDE.md`](../../CLAUDE.md) no corre desde 02.02 y está declarado
**bloqueante para deploy** (F8, gate del MVP). Esta guía lo convierte en pasos que una persona puede
seguir con la pantalla abierta de un lado y un cliente MySQL del otro.

## 1. Las cuatro reglas, y qué significan acá

1. **La persistencia se verifica contra la base, después de cada escritura.** Un `200`, un toast
   verde o la pantalla recargada **no prueban nada**: el defecto #7 del 07/10 respondía `DEBITADO`
   y la base seguía `INCLUIDO`. Cada paso que escribe trae la consulta exacta que lo confirma.
2. **Aislamiento de tenant.** Un token del centro B no ve **nada** del centro A: ni por pantalla, ni
   cambiando un id en la URL. Fuera de alcance es **404**, nunca 403 (un 403 confirma que existe).
   Circuito propio: [11](11-aislamiento-y-rol-paciente.md).
3. **No se maquilla la base.** Ningún `UPDATE` a mano para destrabar un estado. Si un flujo no
   llega, **es un hallazgo** (§7). Por eso esta guía **solo trae `SELECT`**: el script de
   validación rechaza cualquier bloque que escriba.
4. **Local API Switch: no hay.** El frontend usa el proxy de desarrollo (`proxy.conf.json` →
   `localhost:8080`). No hay nada que cambiar antes ni que revertir después. Si alguien agrega un
   switch, esta regla vuelve a aplicar: se revierte **siempre**, pase o falle el QA.

## 2. Levantar el stack

Ambos repos **en la misma rama** (`main` en los dos para el QA del MVP). Verificá el remote antes
de nada: hay otro proyecto `akine-*` en `C:\Users\santo\Documents\akine` que **no es éste**.

```powershell
git -C C:\Users\santo\Desktop\AKINE\appKine-api remote get-url origin   # ...Chapadiex/appKine-api.git
git -C C:\Users\santo\Desktop\AKINE\appKine-web remote get-url origin   # ...Chapadiex/appKine-web.git
git -C C:\Users\santo\Desktop\AKINE\appKine-api branch --show-current
git -C C:\Users\santo\Desktop\AKINE\appKine-web branch --show-current
```

### 2.1 Base de datos: una base nueva para la corrida

Se recomienda **una base aparte, `akine_qa`, dentro del mismo `akine-mysql`**: el QA arranca de cero
—el bootstrap de plataforma, el catálogo global vacío y la primera alta son parte de lo que se
prueba— y la base de desarrollo de siempre (`akine_local`) queda intacta.

```powershell
cd C:\Users\santo\Desktop\AKINE\appKine-api
docker compose up -d
docker compose ps        # akine-mysql y akine-mailpit "healthy"
docker exec akine-mysql mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS akine_qa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL ON akine_qa.* TO 'akine'@'%';"
```

Para repetir la corrida desde cero: `DROP DATABASE akine_qa;` con el mismo comando y volver a
crearla. **Nunca** `docker compose down -v`: borra también `akine_local`.

### 2.2 Backend con perfil `local` contra `akine_qa`

```powershell
cd C:\Users\santo\Desktop\AKINE\appKine-api
$env:AKINE_DB_URL = 'jdbc:mysql://localhost:3306/akine_qa?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC'
$env:AKINE_BOOTSTRAP_ADMIN_EMAIL = 'qa.plataforma@ejemplo.test'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

- Flyway aplica `V1`…`V85` en el primer arranque (un par de minutos). Listo cuando
  `http://localhost:8080/actuator/health` responde `{"status":"UP"}`.
- `AKINE_BOOTSTRAP_ADMIN_EMAIL` es **DP-14**: al arrancar, si no hay ningún admin de plataforma con
  contraseña, la cuenta sembrada por `V15` pasa a esa casilla y recibe un enlace de activación en
  Mailpit. Es el paso [PLA-01](10-plataforma.md).
- Correos: **Mailpit** en <http://localhost:8025>. Ningún correo sale de la máquina.

### 2.3 Frontend

```powershell
cd C:\Users\santo\Desktop\AKINE\appKine-web
npm ci        # solo si cambió package-lock.json
npm start     # = ng serve --proxy-config proxy.conf.json, en http://localhost:4200
```

Nunca `ng serve` pelado: sin el proxy, `/api` no resuelve.

### 2.4 Al terminar

`Ctrl+C` en backend y frontend. `docker compose stop` si no vas a seguir. La base `akine_qa` queda
para revisar hallazgos; borrala cuando ya no la necesites.

## 3. Cuentas sintéticas

Todas con dominio **`@ejemplo.test`** —reservado, nunca entrega correo real— y datos inventados.
**Ninguna cuenta, nombre ni documento real.** Las contraseñas las elegís vos (política: 12+
caracteres, fuera de la denylist) y van **solo** a tu `qa-config.md`, que está gitignored.

| Alias en la guía | Email sugerido | Cómo nace | Rol |
|---|---|---|---|
| plataforma | `qa.plataforma@ejemplo.test` | Bootstrap DP-14 ([PLA-01](10-plataforma.md)) | `PLATFORM_ADMIN` |
| admin-a | `qa.admin-a@ejemplo.test` | Alta self-service ([ON-01](01-onboarding-y-cuentas.md)) | `ORG_ADMIN` fundador del **centro A** |
| profesional | `qa.profesional@ejemplo.test` | Alta self-service + vínculo a A ([ORG-04](02-organizacion-sedes-colaboradores.md)) | `PROFESIONAL` en la sede de A |
| administrativo | `qa.administrativo@ejemplo.test` | Ídem | `ADMINISTRATIVO` en la sede de A |
| paciente | `qa.paciente@ejemplo.test` | Ídem | `PACIENTE` en la sede de A (DP-22) |
| invitado | `qa.invitado@ejemplo.test` | Invitación por correo ([ORG-06](02-organizacion-sedes-colaboradores.md)) | `ADMINISTRATIVO` |
| admin-b | `qa.admin-b@ejemplo.test` | Alta self-service | `ORG_ADMIN` fundador del **centro B** |

> **Por qué cada colaborador nace con su propio centro.** El vínculo directo (`POST
> /memberships`) exige que la cuenta exista y esté activa; la única forma de crear una cuenta es el
> alta self-service, que también le crea una organización propia. Es el mismo camino que usan los
> E2E y no es un defecto del QA. Las invitaciones ([ORG-06](02-organizacion-sedes-colaboradores.md))
> sí crean la membership sin pasar por eso.

**Límites que vas a tocar:** el alta self-service admite **5 por minuto y por IP** (esperá entre
tandas); el plan `BASICO` admite **5 miembros activos** y **una sede**.

## 4. Consultar la base

Abrí **un** cliente interactivo y dejalo abierto toda la corrida: las variables de MySQL (`@org_a`,
`@persona`…) viven en la sesión y se pierden al reconectar.

```powershell
docker exec -it akine-mysql mysql -uakine -pakine akine_qa
```

### 4.1 Variables de la corrida

Cada consulta de la guía usa variables en vez de ids pegados. Las que nacen en un paso se capturan
con la consulta de ese paso (`SET @x := (SELECT ...)`); las demás se fijan a mano cuando aparecen.
Si reconectás, pegá de nuevo este bloque con los valores que anotaste en `qa-config.md`:

```sql
SET @desde                 := UTC_TIMESTAMP(6) - INTERVAL 12 HOUR;  -- inicio de la corrida (UTC)
SET @email_admin_a         := 'qa.admin-a@ejemplo.test';
SET @email_admin_b         := 'qa.admin-b@ejemplo.test';
SET @email_profesional     := 'qa.profesional@ejemplo.test';
SET @email_administrativo  := 'qa.administrativo@ejemplo.test';
SET @email_paciente        := 'qa.paciente@ejemplo.test';
SET @org_a  := (SELECT m.organization_id FROM membership m JOIN cuenta c ON c.id = m.account_id
                WHERE c.email_normalizado = @email_admin_a AND m.is_founder = 1);
SET @sede_a := (SELECT MIN(id) FROM consultorio WHERE organization_id = @org_a);
SET @org_b  := (SELECT m.organization_id FROM membership m JOIN cuenta c ON c.id = m.account_id
                WHERE c.email_normalizado = @email_admin_b AND m.is_founder = 1);
SET @sede_b := (SELECT MIN(id) FROM consultorio WHERE organization_id = @org_b);
SELECT @org_a, @sede_a, @org_b, @sede_b;
```

Las fechas de la base están en **UTC** (`occurred_at`, `*_en`, `created_at`); la sede trabaja en su
zona (`consultorio.timezone`, por defecto `America/Argentina/Cordoba`, UTC−3). Un turno de las
10:00 locales se guarda como 13:00.

### 4.2 La consulta de auditoría de siempre

Casi todos los pasos terminan mirando `audit_event`. La forma general es ésta; cada paso dice qué
`event_type` buscar:

```sql
SELECT id, event_type, entity_type, entity_id, consultorio_id, actor_account_id,
       previous_state, new_state, reason, LEFT(details, 200) AS details, occurred_at
FROM audit_event
WHERE organization_id = @org_a AND occurred_at >= @desde
ORDER BY id DESC
LIMIT 15;
```

Lo que vale para toda la tabla:

- **Append-only** (RN-M24-001): dos triggers de `V14` rechazan `UPDATE` y `DELETE`. Si ves una
  fila "corregida", es un hallazgo crítico.
- Los eventos de **identidad** (`LOGIN_*`, `CUENTA_ACTIVADA`, `SESION_ABIERTA`…) pueden tener
  `organization_id` **NULL**: las cuentas no son de ningún tenant (ADR-0019). Para verlos, filtrá
  por `actor_account_id` o `entity_id` en vez de por organización.
- En los eventos clínicos el `details` guarda `historiaClinicaId` como `[REDACTADO]`, y la
  justificación declarada va a `reason` **solo cuando el acceso fue por justificación**; con
  relación asistencial (`viaDeAcceso = RELACION_ASISTENCIAL`) `reason` queda NULL. Ver
  [06](06-atencion-clinica.md).
- **No todo deja auditoría.** Reservar o confirmar un turno no escribe `audit_event`: su rastro vive en la propia fila (`*_por_cuenta_id`,
  `motivo_*`) o en una tabla de eventos del dominio (`turno_evento`, `recepcion_evento`,
  `autorizacion_evento`, `caso_evento`, `plan_evento`). Cada paso dice dónde mirar.

## 5. Las operaciones sin pantalla

Historia clínica, entradas, casos, planes de tratamiento, soporte y roles de plataforma, bloqueo de
cuentas, cambio de plan y alta de organización por plataforma **no tienen pantalla en
`appKine-web` `main`** (egresos y alertas de consumo sí la tienen desde el 11/10) (la historia clínica está en el PR #4 de `appKine-web`, sin mergear). Se
prueban por la API con [`qa-api.ps1`](qa-api.ps1):

```powershell
. C:\Users\santo\Desktop\AKINE\appKine-api\docs\qa\qa-api.ps1
$A = Entrar 'qa.admin-a@ejemplo.test'          # pide la contraseña sin mostrarla
$r = Api GET '/api/v1/personas?size=5' $A
$r.Status; $r.Tipo; $r.Cuerpo
```

`Api` devuelve siempre `Status`, `Tipo` (el último segmento del `problemType`, p. ej.
`concurrent-modification`) y `Cuerpo`, **también en los 4xx**: no tira excepción. El token vence a
los 10 minutos; volvé a llamar a `Entrar`. Para una cuenta con contexto en dos organizaciones:
`Entrar 'qa.admin-a@ejemplo.test' <orgId>`.

Swagger UI (`http://localhost:8080/swagger-ui.html`) sirve para leer el contrato, **no para
probar**: el OpenAPI no declara `securityScheme` (hueco conocido, ver
[90](90-conocido-y-aceptado.md)) y no hay botón para cargar el token.

## 6. `qa-config.md`

El §6 lo declara bloqueante: sin `.claude/qa-config.md` no corre el QA. Es **personal y
gitignored** (`.gitignore` línea 2). Partí de [`qa-config.ejemplo.md`](qa-config.ejemplo.md),
copialo a `C:\Users\santo\Desktop\AKINE\appKine-api\.claude\qa-config.md` y completá lo marcado
`COMPLETAR`: contraseñas de las cuentas sintéticas y los ids de la corrida a medida que aparecen.
El de la máquina (del 22/08) tiene todavía los `PENDIENTE(F1)`: reemplazalo por éste.

## 7. Registrar hallazgos

Un hallazgo es **cualquier diferencia** entre lo esperado y lo que pasó, en la pantalla o en la
base, incluido un flujo que no llega a un estado. **No se corrige la base para seguir**: se anota y
se sigue por otro camino, o se marca el resto del circuito como bloqueado.

Cada corrida en un archivo `docs/qa/corridas/AAAA-MM-DD.md` (commiteable: no lleva contraseñas), con
el resultado por paso (`OK` · `HALLAZGO` · `BLOQUEADO` · `NO CORRIDO`) y un bloque por hallazgo:

```markdown
### H-<n> · <título corto en una línea>

- **Paso:** ECO-04 (07-obligaciones-cobros-caja.md)
- **Severidad:** crítica | alta | media | baja
  (crítica: datos de otro tenant, dato clínico expuesto, plata mal registrada, auditoría alterada;
   alta: el flujo no llega o la base contradice a la pantalla; media: mensaje o estado equivocado
   que no pierde datos; baja: texto, foco, orden)
- **Esperado:** <qué decía la guía>
- **Obtenido en pantalla:** <qué pasó; requestId del error si lo hay (header X-Request-Id)>
- **Obtenido en la base:** <la consulta y su resultado, pegado>
- **Reproducción:** <pasos mínimos desde un estado conocido>
- **¿Está en "conocido y aceptado"?** no (si sí, no es hallazgo: ver 90-conocido-y-aceptado.md)
```

Un hallazgo crítico o alto bloquea el gate del MVP hasta que tenga PR o decisión del dueño del
producto.

## 8. Cómo se validaron las consultas (y cómo revalidarlas)

Ninguna consulta se escribió de memoria: los nombres de tabla, columna y valores de estado salen
del esquema que dejan `V1`…`V85` y de una corrida real. `validacion/` levanta un stack **aislado**
—por defecto prefijo `g12-`, MySQL en 3329, backend en 8099, Mailpit en 8049— que no toca
`akine-mysql` ni los puertos 8080/4200, siembra un mínimo **por la API** y corre cada bloque
```` ```sql ```` de esta carpeta:

```bash
docs/qa/validacion/stack.sh build && docs/qa/validacion/stack.sh up
QA_API_URL=http://localhost:8099 QA_MAILPIT_URL=http://localhost:8049 \
  node docs/qa/validacion/sembrar.mjs /tmp/estado-g12.json          # ~3 min: 5 altas por minuto
node docs/qa/validacion/validar-consultas.mjs /tmp/estado-g12.json   # 0 errores = todas corren
docs/qa/validacion/stack.sh down --imagen                            # borra contenedores, red e imagen
```

Si esos puertos están tomados (`perf/stack.sh` usa 3329 y 8099), `stack.sh` acepta prefijo y
puertos por entorno, y `validar-consultas.mjs` recibe el contenedor como segundo argumento. Así se
revalidó el 12/10:

```bash
export QA_PREFIJO=qa2 QA_PUERTO_MYSQL=3339 QA_PUERTO_API=8109 QA_PUERTO_MAILPIT=8059 QA_PUERTO_SMTP=1059
docs/qa/validacion/stack.sh build && docs/qa/validacion/stack.sh up
QA_SIN_FERIADOS=1 QA_API_URL=http://localhost:8109 QA_MAILPIT_URL=http://localhost:8059 \
  node docs/qa/validacion/sembrar.mjs /tmp/estado-qa2.json
node docs/qa/validacion/validar-consultas.mjs /tmp/estado-qa2.json qa2-mysql
docs/qa/validacion/stack.sh down --imagen
```

El sembrado reserva turnos **de hoy**: en un feriado nacional (el 12/10 lo es) la agenda no tiene
slots, y `QA_SIN_FERIADOS=1` apaga el cierre por feriado de la sede A, como lo haría su
administrador. Después de las 22 de la sede, `QA_ZONA_SEDE=Pacific/Honolulu` la mueve a una zona
donde todavía es temprano.

**Cuándo revalidar:** cada migración nueva que toque una tabla que la guía consulta. El script
falla si un bloque tiene un error de sintaxis, de tabla o de columna, o si escribe en la base. Que
una consulta devuelva cero filas en el sembrado **no** es error.

## 9. Circuitos, por riesgo

La prioridad sale de qué rompe más caro y qué **ningún test automático cubre**. Cada paso marca lo
que ya cubre un test en CI, para no repetirlo entero a mano:

- **`[E2E <spec>]`**: un spec de `appKine-web/e2e/` lo recorre por pantalla contra el backend real,
  en el job `e2e` del CI (proyectos `chromium` y `agenda`). Casi ninguno mira la base: a mano se
  corre **solo la consulta de persistencia**, sin las variantes de error que el spec ya prueba.
- **`[IT <clase>]`**: un test de integración del backend lo prueba contra MySQL real en `verify`.
  No pasa por la pantalla: a mano se hace el camino feliz por pantalla y se confía el resto al IT.
- **Sin marca**: no lo cubre nada automático. **Ahí va el tiempo del QA.**

| # | Circuito | Prioridad | Pasos | Lo que ningún test cubre |
|---|---|---|---|---|
| [01](01-onboarding-y-cuentas.md) | Onboarding y cuentas | P2 | 12 | El correo de "ya registrado", el reenvío que invalida el enlace, las sesiones que revoca el reset, la IP de la sesión, el 503 sin deslogueo |
| [02](02-organizacion-sedes-colaboradores.md) | Organización, sedes, colaboradores, permisos | **P1** | 16 | Grants prohibidos por pantalla, último admin, invitación aceptada, tope de plan, notificación agotada por un correo caído de verdad |
| [03](03-catalogo-servicios-ofertas.md) | Catálogo, servicios, ofertas, prácticas, precios | P2 | 10 | Precio particular, horario de sede (DP-19) |
| [04](04-personas-coberturas-autorizaciones.md) | Personas, coberturas, órdenes, autorizaciones | **P1** | 15 | Historial de la autorización (DP-23), selector explicable contra el consumo real, la base detrás del saldo |
| [05](05-agenda-turnos-recepcion.md) | Agenda, turnos, series, recepción, prepago | P2 | 13 | Ausente, reprogramar una serie, anular una recepción, el día de la sede en agenda y recepción |
| [06](06-atencion-clinica.md) | Sesión, historia clínica, casos, plan, enmiendas | **P1** | 15 | **Todo**: no hay E2E de la vertical clínica |
| [07](07-obligaciones-cobros-caja.md) | Obligaciones, cobros, anulación, reintegro, caja | **P1** | 14 | Coseguro, DP-17, anulación de una deuda, lo posterior al cobro en efectivo, la auditoría de devengo y cobro |
| [08](08-presentaciones-facturacion-pagos.md) | Presentaciones, facturación, pagos | **P1** | 9 | Débito persistido, anulación del lote, cuenta corriente del financiador |
| [09](09-reportes.md) | Reportes | P2 | 6 | Recorte por rol (DP-15), export CSV |
| [10](10-plataforma.md) | Plataforma | P2 | 9 | Soporte con rastro, rol de plataforma, cambio de plan, suspensión |
| [11](11-aislamiento-y-rol-paciente.md) | Aislamiento entre organizaciones y rol PACIENTE | **P1** | 8 | La cuenta en dos organizaciones por pantalla (#70), DP-22 por pantalla |
| [12](12-egresos-y-pagos.md) | Egresos y pagos de la sede | **P1** | 8 | Honorarios a un colaborador, pago parcial por transferencia, borrador sin comprobante, anular el egreso, filtros |
| [90](90-conocido-y-aceptado.md) | Conocido y aceptado | — | — | Lo que **no** se reporta como bug |

**135 pasos** en total (120 del 11/10 y 15 del 12/10). Al armar la guía, la validación contra el
stack aislado destapó **dos defectos sin corregir y una pregunta para el dueño del producto**; la
revalidación del 12/10, **tres defectos más** (el filtro de egresos por día UTC, el cobro anulado
que desaparece del listado y el correo de una invitación cancelada que sale igual). Están en
[90](90-conocido-y-aceptado.md) §4 y §5, para que el QA los vincule en vez de reportarlos como
nuevos.

**E2E nuevos del 12/10** (web #64, contra el backend real): `flujo-autorizaciones`,
`flujo-cobros-posteriores`, `flujo-colaboradores`, `flujo-dia-de-la-sede`, `flujo-notificaciones`
y `flujo-practicas-y-aranceles`, más el tercer test de `flujo-caja` (egreso en efectivo). Ninguno
mira la base: la consulta de persistencia de cada paso sigue siendo manual.

**Orden sugerido:** 10 (PLA-01 y PLA-02, para tener el servicio global) → 01 → 02 → 03 → 04 → 05 →
06 → 07 → 12 → 08 → 09 → resto de 10 → 11 → 90 al final, para descartar hallazgos que ya son decisión
tomada. Una corrida completa son **dos jornadas**; si hay que recortar, los circuitos P1 primero.

## 10. Archivos

| Archivo | Para qué |
|---|---|
| `README.md` | Esto |
| `01-…` a `12-…` | Un checklist por circuito |
| `90-conocido-y-aceptado.md` | Criterios provisorios y decisiones abiertas: lo que no es bug |
| `qa-api.ps1` | `Entrar` y `Api` en PowerShell, para lo que no tiene pantalla |
| `qa-config.ejemplo.md` | Plantilla del `qa-config.md` personal, ya con lo de AKINE |
| `validacion/stack.sh` | Stack aislado (`g12-*` o el prefijo de `QA_PREFIJO`) para validar las consultas |
| `validacion/sembrar.mjs` | Sembrado mínimo por la API sobre ese stack |
| `validacion/validar-consultas.mjs` | Corre cada bloque SQL de la guía y falla ante un error |
