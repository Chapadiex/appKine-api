# Pedido de cambio de plan

25/11/2026. Migración **`V48`**, contrato **0.194.0** (`V47`/0.193.0 los tomó la rama del
asistente-agente). Ramas `akine-pedido-de-plan` en `appKine-api` y `appKine-web`.

## Por qué

Hoy un centro no puede pedir otro plan desde el sistema: el cambio de contratación lo hace sólo la
administración de plataforma (`updateContratacionDeCentro`) y el centro no tiene cómo avisar. El
caso que lo disparó: un centro con sólo Kinesiología publica Yoga y no encuentra dónde programar
clases, porque Actividades no está contratado.

El usuario lo decidió así: **desde Suscripción el centro arma el pedido (subir o bajar), y la
plataforma lo aprueba —se aplica solo— o lo rechaza con motivo.**

## Objetivos y no objetivos

- El administrador del centro (`tenant:update`) pide plan + servicios + adicionales, con un
  comentario opcional. Ve el estado de su último pedido y lo puede cancelar mientras espera.
- La plataforma ve la bandeja con el contador de pendientes, compara la contratación actual con la
  pedida y aprueba o rechaza (motivo obligatorio).
- **No:** precio ni cobro (el cobro mensual sigue como está), correos de aviso, cambio sin
  aprobación, varios pedidos a la vez.
- **Anti-objetivo:** que aprobar aplique algo distinto de lo que el centro pidió, o que un pedido
  aprobado sobre una contratación que cambió en el medio pise ese cambio sin que nadie lo vea.

## Enfoques

1. **Pedido guardado + aprobación que reusa `ContratacionService.cambiar`** (elegido). Una tabla del
   módulo `organization`, una bandeja como la de pedidos de prueba y el mismo camino de escritura
   que ya usa la plataforma.
2. Correo a soporte desde la pantalla. Sin backend, pero el pedido no queda en el sistema ni hay
   contador ni historial. Descartado por el usuario.
3. Que el `ORG_ADMIN` cambie su plan sin aprobación. Descartado: el usuario quiere la aprobación de
   la plataforma, y el cobro no está resuelto.

## Modelo

Tabla `pedido_de_cambio_de_plan` (propietario: `organization`):

| Columna | Qué |
|---|---|
| `organization_id` | FK, NOT NULL |
| `plan_code`, `servicios`, `adicionales` | lo pedido; servicios y adicionales como lista separada por comas de los enum (`KINESIOLOGIA,ACTIVIDADES`, `IA`) |
| `plan_code_anterior`, `servicios_anteriores`, `adicionales_anteriores` | foto de la contratación al pedir, para mostrar el "de → a" y para el historial |
| `comentario` (500) | opcional |
| `estado` | `PENDIENTE`, `APROBADO`, `RECHAZADO`, `CANCELADO` |
| `motivo_rechazo` (500) | obligatorio al rechazar |
| `pedido_por`, `resuelto_por`, `resuelto_en` | cuentas e instante |
| `version`, `created_at`, `updated_at` | |

- **Un pendiente por centro:** índice único parcial `(organization_id) WHERE estado = 'PENDIENTE'`.
  El segundo pedido simultáneo choca contra el índice y responde 409 `pedido-de-plan-pendiente`
  (relectura en `READ_COMMITTED`, la regla de siempre).
- **Nunca se borra:** termina aprobado, rechazado o cancelado.
- CHECK de estado, de resolución (`PENDIENTE` ⇔ `resuelto_en IS NULL`) y de motivo al rechazar.

## API (todo aditivo)

Centro (`tenant:update`), bajo `/subscription` para que funcione con la suscripción suspendida:

- `GET /api/v1/organizations/{orgId}/subscription/pedido-de-cambio` → el último pedido, o 204.
- `POST /api/v1/organizations/{orgId}/subscription/pedido-de-cambio` → 201. 400 sin servicios, plan
  no ofrecido o pedido idéntico a lo actual (`pedido-sin-cambios`); 409 `pedido-de-plan-pendiente`.
- `POST .../pedido-de-cambio/{id}/cancelar` con `version` → 200; 409 si ya se resolvió.

Plataforma (`requirePlatformAdmin`):

- `GET /api/v1/platform/pedidos-de-cambio-de-plan?estado&q&page&size` → página + `pendientes`.
- `POST /api/v1/platform/pedidos-de-cambio-de-plan/{id}/aprobar` con `version` del pedido.
- `POST /api/v1/platform/pedidos-de-cambio-de-plan/{id}/rechazar` con `motivo` y `version`.
- `PanelDePlataforma.cambiosDePlanPendientes` para el contador de la barra.

**Aprobar** llama a `ContratacionService.cambiar(org, plan, servicios, adicionales, contratar=false,
versionActualDeLaSuscripcion, actor)` en la misma transacción que marca el pedido. No pasa la prueba
a ACTIVA: eso sigue siendo un acto explícito de la plataforma. Si la contratación cambió desde que se
pidió, se aplica igual lo pedido (la plataforma ve las dos cosas antes de aprobar).

Auditoría: `PEDIDO_PLAN_RECIBIDO` y `PEDIDO_PLAN_RESUELTO`, entidad `PedidoDeCambioDePlan`.

## Pantallas

- **Suscripción:** botón «Pedir un cambio de plan» → panel lateral con los planes ofrecidos en
  tarjetas, servicios y la IA como casillas, comentario y un resumen «Pasás de Plan Base a Plan
  Intermedio · suma Actividades». Con un pedido pendiente, la tarjeta dice «Pedido enviado el
  dd/mm · esperando aprobación» con «Cancelar el pedido»; rechazado, el motivo.
- **Consola de plataforma:** «Cambios de plan» en el grupo Centros, con contador; tabla de pedidos
  (centro, actual → pedido, fecha, estado) y panel con Aprobar / Rechazar (motivo).

## Desafío de diseño

1. **Ownership:** la tabla es de `organization`, igual que la suscripción y la contratación.
2. **Ciclos:** no hay dependencias nuevas entre módulos.
3. **Tenant:** lleva `organization_id`; el índice único lo incluye. La bandeja de plataforma es
   global a propósito, como la de pedidos de prueba.
4. **Reglas maestras:** no toca HC, turnos ni dinero. El cobro mensual no cambia.
5. **Baja lógica:** no hay borrado; los pedidos terminan en un estado.
6. **Contrato:** aditivo, minor 0.194.0.
7. **Ruta crítica:** se apoya en contratación (V28/V44) y cobro (V45), ya en `main`.
8. **El caso que rompe:** dos administradores piden a la vez → el índice parcial deja uno y el otro
   recibe 409. La plataforma aprueba mientras el centro cancela → versión del pedido, el segundo
   recibe 409 `concurrent-modification`. Se baja de plan con más sedes que el tope → nada se borra,
   sólo se rechazan altas nuevas (regla vigente de `cambiar`).
