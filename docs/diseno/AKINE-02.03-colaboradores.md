# AKINE-02.03 — Ciclo de vida de colaboradores

> **Qué es este documento.** El registro de diseño de la etapa: las decisiones, las que quedaron
> abiertas y lo que la etapa deliberadamente no hizo. No repite lo que ya está escrito y es
> autoridad en otro lado:
>
> | Dónde | Qué contiene |
> |---|---|
> | `V21__m05_colaborador_invitacion.sql` (encabezado) | El modelo, los dos centinelas del `UNIQUE` y por qué esta tabla **no** es una excepción a ADR-0004 |
> | `ColaboradorInvitacionService` (javadoc) | El orden de los pasos, las dos formas de autorizar y qué se responde cuando el token no resuelve |
> | `ColaboradorDesvinculacionProbe` (javadoc) | Por qué esta sonda informa y la de consultorios bloquea |
> | `openapi/akine-api.yaml` **0.10.0** | El contrato: 8 operaciones nuevas, aditivas |

## 0. Resumen de una línea

La invitación a colaborar de M05: un administrador invita por email a alguien que **puede no
tener cuenta**, esa persona acepta o rechaza con el token del enlace como única autoridad, y al
aceptar se crean cuenta y vínculo en una transacción.

## 1. Las tres decisiones que el usuario cerró el 25/08/2026

Se preguntaron antes de escribir código porque las tres tenían lecturas que llevaban a trabajos
materialmente distintos.

| Pregunta | Decisión | Consecuencia |
|---|---|---|
| ¿Qué pasa con el alta directa, que exige cuenta existente? | **Conviven.** El alta directa es un click para quien ya está en AKINE; la invitación es el camino para quien no | 02.03 es **puramente aditiva**: ninguna operación del contrato cambia de forma |
| ¿Cómo obtiene cuenta un invitado que no la tiene? | **Registro y aceptación en un acto.** El enlace pide nombre y contraseña, crea la cuenta **ya activa** y acepta, todo junto | Un solo paso para el invitado, y ningún segundo correo de activación |
| RN-M05-004 pide turnos futuros visibles, y M12 no existe | **Puerto sin implementación**, como el de consultorios | La regla tiene lugar reservado y empieza a aplicar sin tocar la API |

## 2. Lo que entra

- **`colaborador_invitacion`** (migración `V21`), propiedad de `identity`.
- **Lado del administrador**: emitir, listar con filtro por estado, reenviar y cancelar.
- **Lado del invitado**, público: consultar sin consumir, aceptar y rechazar.
- **Correo por outbox** (RF-M26-001), con `INVITACION_COLABORADOR`, que ya estaba reservado.
- **Análisis de impacto previo a la desvinculación** (RN-M05-004), con su puerto.
- **Frontend**: la pantalla de invitaciones del administrador y la pantalla pública del invitado.

## 3. Por qué la invitación vive en `identity`

Mismo motivo que `onboarding_registro` (V8): el flujo **arranca con un email** —dato de
`identity`— y en el caso más común **crea la cuenta** al aceptarse. `organization` no puede
compilar contra `identity` (ArchUnit rechaza la flecha), así que la orquestación tiene que
ocurrir de este lado y la membership la crea `organization` por su SPI.

La consecuencia práctica: **el controller del administrador vive en `identity.api`** aunque su
ruta empiece por `/organizations/{orgId}`. Ese `orgId` es decorativo —el tenant sale del contexto
revalidado—, y está para que la URL sea legible.

## 4. La decisión de seguridad de la etapa: dos formas de autorizar

| Operaciones | Qué autoriza |
|---|---|
| Emitir, listar, reenviar, cancelar | `colaborador:manage` sobre el alcance pedido, igual que el alta directa |
| Consultar, aceptar, rechazar | **El token**, y nada más |

No es una excepción cómoda: **es la única posible**. Quien acepta no pertenece al tenant y puede
no tener cuenta, así que no tiene ni puede tener `colaborador:manage` sobre él. Exigirlo haría
que ninguna invitación pudiera aceptarse nunca.

La autorización real ocurrió antes —el administrador emitió la invitación **con** ese permiso, y
ese acto quedó auditado como `INVITACION_EMITIDA`— y lo que prueba que quien responde es la
persona invitada es el token, verificado contra su SHA-256. Por eso `createFromInvitation` es un
método aparte de `createDirect` y su javadoc dice, en el SPI y en la implementación, que es de
uso exclusivo del servicio de invitaciones: llamarlo desde otro lado es crear memberships sin
autorizar a nadie.

### 4.1 La cuenta del invitado nace ACTIVA

El registro self-service crea la cuenta `PENDIENTE_ACTIVACION` y manda un correo porque ahí nadie
probó que la dirección sea suya. Acá **eso ya está probado**: el token llegó a ese buzón y volvió.
Un segundo correo verificaría por segunda vez lo mismo y agregaría el único paso donde la mitad de
la gente abandona.

La contracara declarada: el token de invitación tiene el mismo poder que uno de activación, y por
eso vive hasheado, se consume al usarse y expira a los 14 días.

### 4.2 Aceptar NO devuelve sesión

Sería cómodo —la persona acaba de elegir su contraseña— y se descarta por dos motivos, los dos en
el javadoc de `AcceptedInvitacionResponse`:

1. Duplicaría en un segundo lugar toda la maquinaria de sesión (rotación estricta, cookie
   `httpOnly`, validación de `Origin`) que hoy vive concentrada en un solo camino auditado.
2. Quien acepta con una cuenta que **ya tenía** probó que llega al buzón, no que la cuenta sea
   suya. Una casilla abierta en una máquina compartida alcanza para lo primero y no para lo
   segundo. Devolver sesión ahí sería un login sin contraseña.

## 5. El `UNIQUE` de invitación pendiente

Lo que hay que garantizar: **una sola invitación pendiente por (organización, alcance, email)**.
Dos enlaces válidos a la vez dejan al invitado eligiendo cuál usar.

En MySQL varios `NULL` no colisionan, y esta tabla se cruza con esa trampa **dos veces, en
sentidos opuestos**:

| Eje | Sin centinela | Con centinela |
|---|---|---|
| Alcance (`consultorio_id` NULL = organización) | Dos invitaciones de alcance organización al mismo email no chocan | `consultorio_key = IFNULL(consultorio_id, 0)` |
| Resolución (`resuelta_en` NULL = pendiente) | Protege el histórico y desprotege lo vigente | `resuelta_key = IFNULL(resuelta_en, '1970-01-01')` |

Los dos son formas que el repositorio ya usa: el centinela numérico de V10 y V20, y el centinela
de fecha de V18 y V19.

## 6. Expirar no es un estado

`EstadoInvitacion` tiene cuatro valores y **EXPIRADA no es uno**. Materializarlo obligaría a un
job que lo escriba, y entre que el enlace vence y el job corre la base diría `PENDIENTE` sobre
algo que ya no se puede aceptar. Se deriva de `expira_en` en cada lectura.

Consecuencia deliberada: **una invitación vencida sigue ocupando el `UNIQUE`**. Es lo correcto —
reinvitar a alguien cuyo enlace venció es un **reenvío**, mismo pedido con token nuevo, y no una
invitación nueva.

## 7. Lo que la etapa arregló de paso, y no estaba en su alcance

### 7.1 `MAX_MIEMBROS_ACTIVOS` estaba configurado y no se aplicaba

El límite existe en `LimitCode` desde 00.01 y `TenantUsageCounter` sabe contarlo desde 01.03,
pero **ninguna alta lo consultaba**: un plan que declaraba cinco miembros admitía quinientos. Se
cierra acá porque 02.03 es la etapa que convierte el alta de colaboradores en un flujo real, y
porque publicar la invitación sin gate sería publicar la vía por la que el límite se evade.

**Emitir no consume cupo; aceptar sí.** Si emitir lo consumiera, un administrador podría dejar
sin cupo a su propia organización invitando a diez personas que nunca respondan. La contracara
declarada: **cinco invitaciones pendientes con un solo lugar libre significan que cuatro reciben
409 al aceptar**. Entra el que llega primero, y el tope del plan se mide sobre gente que trabaja.

### 7.2 Los enlaces de correo apuntaban a rutas que el SPA no sirve

`IdentityProperties.Links` traía `/activar` y `/restablecer`, y el frontend monta esas pantallas
bajo `/auth`. Un enlace a `/activar` pelado cae en el comodín `**` y el usuario ve "página no
encontrada" con un token perfectamente válido en la URL. **Está así desde 01.02**: activación y
recuperación de contraseña no se podían completar desde el correo. Corregido a `/auth/activar`,
`/auth/restablecer` y `/auth/invitacion`.

> Los tests que verifican el armado del enlace fijan sus propias rutas, así que ninguno cubría el
> valor por defecto. Es exactamente el tipo de agujero que un test de configuración por defecto
> habría encontrado y ninguno de los existentes podía.

## 8. Permisos

No hace falta ningún código nuevo: `colaborador:manage` y `colaborador:read` ya están en la
matriz y son los que la etapa usa. Dos precisiones que sí se tomaron:

- **El listado de invitaciones exige `colaborador:manage`, no `colaborador:read`.** Lo que se ve
  ahí son direcciones de correo de personas que todavía no aceptaron nada: no es la lista de
  colaboradores, es la lista de a quién se le escribió.
- **El análisis de impacto exige `colaborador:read`, no `manage`.** Sirve para decidir, y quien
  decide suele mirar antes de tener el permiso de ejecutar.

## 9. Deuda diferida

| Deuda | Destino |
|---|---|
| `ColaboradorDesvinculacionProbe` sin implementación: el impacto siempre responde cero | Etapa de agenda (M12) |
| Si algún día hay más de una sonda, el impacto pasa a ser una lista y la respuesta un array | Cambio de contrato, anotado en `impactoDe` |
| E2E de Playwright de las dos pantallas nuevas | No corridos en esta etapa |
| Que un `ORG_ADMIN` pueda subir de plan cuando el tope de miembros lo frena | Arrastrada de 02.01, ahora también alcanzable desde acá |
| Purga de invitaciones pendientes viejas | Sin etapa asignada. Hoy quedan para siempre, ocupando su `UNIQUE` |

## 10. Contexto para la etapa siguiente

- **Hay dos vías de alta de colaborador y las dos pasan por `MembershipProvisioning`**: quien
  agregue una tercera tiene que decidir explícitamente qué la autoriza.
- **`MAX_MIEMBROS_ACTIVOS` ahora se aplica de verdad**: cualquier etapa que cree memberships
  tiene que contar con que puede recibir 409 por tope de plan.
- **02.04** (disponibilidad semanal) hereda de acá los profesionales vinculados por sede, que es
  el contexto que declaraba necesitar.
