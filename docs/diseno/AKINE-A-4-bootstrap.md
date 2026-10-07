# AKINE-A-4 — Bootstrap del `PLATFORM_ADMIN` (DP-14)

> Paquete A-4 de `docs/fases/01-trabajo-en-paralelo.md`. Implementa **DP-14**
> (`docs/producto/AKINE_IMPLEMENTATION_PLAN.md`), que resolvió DU-2 el 07/10/2026.
> Sin migración y sin cambio de contrato.

## El problema

`V15` siembra `plataforma@akine.app` con `password_hash` NULL, estado `ACTIVA` y una fila en
`platform_role`, y prometía que se tomaba posesión por "recuperar contraseña". No funciona:
`PasswordResetService.solicitar` descarta en silencio las cuentas que no pueden autenticarse
(`!cuenta.puedeAutenticarse()`), y una cuenta sin credencial no puede. El otro camino,
`PlatformRoleService.grant`, exige el rol que se quiere obtener. En un despliegue nuevo ningún
endpoint de `/api/v1/platform/**` es alcanzable.

## La decisión (DP-14)

Al arrancar, si no hay ningún administrador de plataforma con credencial y está definida
`AKINE_BOOTSTRAP_ADMIN_EMAIL`, la cuenta sembrada pasa a ese email y se le encola un enlace de
activación por el outbox, con el mismo flujo de activación del registro (ADR-0017/0018).
Descartadas: abrir el reset a cuentas sin credencial y fijar la contraseña desde el entorno.

## Diseño

**Dónde vive.** En `identity`, que es dueño de `cuenta`, de `token_verificacion` y del flujo de
activación:

| Pieza | Paquete | Qué hace |
|---|---|---|
| `PlatformAdminBootstrapRunner` | `identity.infrastructure` | `ApplicationRunner`. Lee `akine.bootstrap.admin-email` (= `AKINE_BOOTSTRAP_ADMIN_EMAIL`), llama al servicio y loguea el resultado. **Nunca tumba el arranque** |
| `PlatformAdminBootstrapService` | `identity.application` | La decisión, en una transacción. Devuelve un `ResultadoBootstrap` que dice qué hizo o por qué no |
| `Cuenta.prepararBootstrapDePlataforma` | `identity.domain` | Cambia el email y deja la cuenta `PENDIENTE_ACTIVACION`. Solo sobre una cuenta sin credencial |
| `AccountActivationService.emitirEnlaceDeActivacion` | `identity.application` | El mismo emisor que usa el reenvío: invalida los enlaces anteriores, emite token, encola el correo |
| `PlatformAdminRoster` | `organization.spi` | `Set<Long> cuentasConRolDePlataforma(Instant)`: qué cuentas tienen el rol vigente |

**Cómo se pregunta "¿hay admin con credencial?".** La pregunta tiene dos mitades de dos dueños:
quién tiene el rol (`platform_role`, de `organization`) y quién tiene credencial (`cuenta`, de
`identity`). `identity → organization.spi` ya existe (activación de memberships, onboarding,
invitaciones) y `organization` jamás importa `identity`, así que `identity` pide a
`organization.spi` los ids con rol vigente y mira la credencial de cada uno en su propia tabla.
No se agrega una arista nueva entre módulos: se agrega una interfaz a un `spi` que `identity` ya
consume. `PlatformRoleDirectory` (`platform.spi`) no sirve: responde por una cuenta dada y no
lista, y ADR-0020 prohíbe un listado de cuentas de plataforma alcanzable desde HTTP — este no lo
es: lo consume un runner de arranque, sin request.

**El algoritmo**, todo en una transacción:

1. Sin variable (o en blanco) → `SIN_VARIABLE`, no toca la base.
2. Email con forma inválida → `EMAIL_INVALIDO`.
3. Cuentas con rol de plataforma vigente (`organization.spi`), leídas en `identity`.
4. Si alguna tiene `password_hash` → `YA_HAY_ADMIN_CON_CREDENCIAL`. Es la lectura literal de
   DP-14: "con credencial", no "habilitada". Una cuenta de plataforma bloqueada sigue siendo un
   admin con credencial; desbloquearla es de un humano, no de un arranque.
5. Candidatas: las de rol vigente, sin credencial, `active` y en `ACTIVA` o
   `PENDIENTE_ACTIVACION`. Ninguna → `SIN_CUENTA_SEMBRADA`; más de una → `CANDIDATAS_AMBIGUAS`.
   En la práctica es una sola: la de `V15` (ningún otro camino da el rol a una cuenta sin
   credencial, porque `grant` exige un admin autenticado).
6. Si el email ya es de **otra** cuenta → `EMAIL_DE_OTRA_CUENTA`. No se pisa nada **y no se le
   otorga el rol a esa cuenta**: no hay RF que lo diga, y otorgar el permiso más alto del sistema
   a una cuenta que alguien registró con ese email es exactamente la toma de control que ADR-0020
   pide auditar con autor y motivo. Se anota como decisión a revisar.
7. Si la candidata ya tiene ese email, está pendiente y tiene un enlace de activación vigente
   → `ENLACE_VIGENTE` (segundo arranque).
8. Si no: cambia el email, queda `PENDIENTE_ACTIVACION`, se invalidan los enlaces anteriores, se
   emite uno nuevo por el outbox (`organization_id` NULL) y se audita `PLATFORM_ADMIN_BOOTSTRAP`
   (sin tenant, actor sistema) → `ENLACE_EMITIDO`.

Desde ahí es el flujo del registro, sin una línea nueva: `POST /api/v1/auth/activate` con el
token y la contraseña nueva (obligatoria porque la cuenta no tiene credencial), la cuenta pasa a
`ACTIVA`, puede autenticarse, y la fila de `platform_role` —que nunca se tocó— la reconoce.

**Por qué `PENDIENTE_ACTIVACION`.** `activar` exige ese estado (cierra la fuga de estado y el
autodesbloqueo, ver su javadoc), y `V15` sembró la cuenta `ACTIVA`. Es la única excepción a "nadie
vuelve a `PENDIENTE_ACTIVACION`" y está acotada en el dominio: solo una cuenta sin credencial, o
sea que nunca pudo entrar, y solo desde `ACTIVA` o `PENDIENTE_ACTIVACION`. La alternativa —relajar
`activar` para aceptar `ACTIVA` sin credencial— abría una puerta en el flujo público para un caso
que sólo existe al arrancar. Efecto lateral bueno: mientras está pendiente, el reenvío público de
activación (`POST /api/v1/auth/activation/resend`) funciona para ese email, así que un enlace
vencido se recupera sin reiniciar.

**Cambio de email.** `email_normalizado` era `updatable = false`. Deja de serlo: lo cambia solo
`prepararBootstrapDePlataforma`, no hay setter, y `uk_cuenta_email_normalizado` sigue siendo la
garantía de "una persona, una cuenta" — si un registro concurrente toma el email entre la lectura
y el flush, el flush choca contra el unique, la transacción se revierte y el runner lo loguea.

**Variable cambiada entre arranques.** Si la cuenta sigue pendiente y la variable trae otro email
(se tipeó mal el primero), se re-apunta y se emite un enlace nuevo que invalida el anterior. Sigue
siendo idempotente para el mismo valor. Quien controla el entorno ya controla el despliegue: no es
una escalada.

## Design challenge

1. **Ownership.** No hay tabla nueva. `identity` escribe sólo `cuenta` y `token_verificacion`, que
   son suyas; el correo va por el puerto del outbox y la auditoría por `platform.spi.audit`.
   `platform_role` se **lee** por `organization.spi` y no se escribe.
2. **Ciclos.** `identity → organization.spi` ya existe. La interfaz nueva vive en
   `organization.spi` y la implementa `organization.infrastructure`; `organization` no importa
   `identity`. `ModuleArchitectureTest` verde.
3. **Tenant.** Sin tablas nuevas. `cuenta` y `platform_role` son globales por ADR-0019 y ADR-0020;
   el outbox y la auditoría van con `organization_id` NULL, la forma admitida para eventos de
   identidad y de plataforma.
4. **Reglas maestras.** No toca HC, turnos, sesiones ni economía.
5. **Baja lógica.** No borra nada. Los tokens anteriores se **invalidan**, no se borran, y el email
   anterior queda en la auditoría.
6. **Contrato.** Sin cambios: no hay endpoint nuevo ni DTO tocado. `OpenApiContractIT` sin drift.
7. **Ruta crítica.** Usa piezas cerradas: el flujo de activación de 01.02, el outbox de 01.02 y
   `platform_role` de 01.03. Desbloquea A-7.
8. **El caso que rompe el diseño.** *Dos instancias arrancan a la vez contra una base nueva, las dos
   con la variable.* Las dos leen la cuenta en la versión 0 y las dos la modifican; la segunda en
   hacer flush choca con `@Version` y se revierte entera —su token y su fila de outbox incluidos—,
   el runner lo loguea como WARN y la aplicación arranca igual. Queda un solo enlace. *Segundo
   caso:* alguien se registra con el email de la variable antes del arranque → `EMAIL_DE_OTRA_CUENTA`,
   nada cambia, y el operador lo ve en el log con el motivo; el remedio es elegir otra casilla.

## Verificación

- Unitarios: `PlatformAdminBootstrapServiceTest` (cada rama del algoritmo), el dominio de `Cuenta`
  y el runner.
- IT contra MySQL: `PlatformAdminBootstrapIT` (con la variable en el contexto): primer arranque
  rebautiza y deja la fila de activación en `notification_outbox`; segundo arranque no hace nada;
  activar con el enlace deja la cuenta autenticándose y con el rol vigente; con un admin con
  credencial no hace nada. `PlatformAdminSinBootstrapIT` (sin la variable): la cuenta sembrada
  queda intacta y sin enlace.
