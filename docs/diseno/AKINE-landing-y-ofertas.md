# AKINE — Landing, ofertas y prueba gratis de 30 días

- **Estado:** plan, sin implementar
- **Fecha:** 18/11/2026
- **Rama:** tanda (`akine-api-tanda` y `akine-web-tanda`): se sube todo junto cuando el usuario lo pida
- **Base:** `main` en contrato **0.177.0**, última migración **V27**

## 1. Qué se quiere

Hoy cualquiera se registra en `/auth/registro` y sale con un centro en el plan `BASICO`. No hay
página que explique qué es AKINE ni qué se contrata, y la plataforma no puede dar de alta un
centro.

El usuario decidió el 18/11/2026:

1. **Los centros los crea sólo la plataforma.** Se corta el autoregistro.
2. **Hay una landing pública dentro de `appKine-web`**, en la raíz.
3. **La landing ofrece una prueba gratis de 30 días.** El interesado la pide y la plataforma la
   habilita.
4. **Lo que se contrata se arma a medida:** cantidad de sedes, uno o los dos servicios y la IA como
   adicional.
5. **La landing muestra los beneficios** de cada servicio y de manejar varias sedes en una sola
   organización.

## 2. La oferta

Un centro contrata tres cosas, que se combinan libremente.

### 2.1 Sedes: el plan

| Plan | Sedes | Para quién |
|---|---|---|
| **Base** | 1 | Un consultorio o un estudio |
| **Crecimiento** | de 1 a 3 | Un centro que abre su segunda o tercera sede |
| **Red** | más de 3, a convenir | Cadenas y franquicias. Se habla con la plataforma |

Los planes de hoy (`BASICO`, `PROFESIONAL`) se reemplazan por estos tres. `MAX_CONSULTORIOS` ya
existe en `plan_limit` y es lo que hace cumplir el tope de sedes. Hay que decidir qué pasa con
`MAX_MIEMBROS_ACTIVOS` (hoy 5 en `BASICO`): ver §9.

### 2.2 Servicios: uno o los dos

| Servicio | Qué trae | Módulos del backend |
|---|---|---|
| **Kinesiología y rehabilitación** | Historia clínica, casos y plan de tratamiento, sesiones de evaluación, coberturas, convenios, órdenes y autorizaciones, presentaciones a financiadores | `clinical`, `encounter`, `contracting`, presentaciones de `billing` |
| **Actividades** (gimnasio, yoga, pilates, danza) | Clases, inscripciones, asistencia, pases, abonos y productos, honorarios por clase | `activity` |

**Lo que tienen los dos siempre:** padrón de personas, agenda y turnos, recepción, caja, cobros,
egresos, sedes, colaboradores, marca y reportes de lo que se tenga contratado.

### 2.3 Adicional de IA

Asistente de datos, analizador de evolución, sugerencias, dictado por voz y biblioteca del centro.
Se suma a cualquier plan y a cualquier combinación de servicios. **La evolución, las sugerencias y
la biblioteca sólo tienen sentido con Kinesiología**: con sólo Actividades, la IA ofrece el
asistente y el dictado.

Lo que hoy existe es el interruptor del centro (`organization_ia`, V27, Organización → Inteligencia
artificial). Queda así: **una función de IA anda si el despliegue la tiene prendida, el centro la
contrató y el centro la prendió.** Contratar abre la puerta; el interruptor del centro sigue
mandando.

### 2.4 Prueba gratis de 30 días

- **Arranca con todo:** los dos servicios, la IA y hasta 3 sedes, para que el centro pruebe lo que
  más le sirva. Ver §9 si se prefiere probar sólo lo que pidió.
- **Al vencer**, el centro elige qué contratar. Si no eligió, pasa a **sólo lectura**: entra, ve
  sus datos y los puede exportar, pero no carga nada nuevo. Nunca se borra nada.
- **Avisos al dueño:** a los 7 días y al día del vencimiento, por correo y con una franja en la app.

## 3. El recorrido completo

```
Landing ──► "Probalo gratis 30 días" ──► formulario
                                              │
                                              ▼
                              Solicitud en la bandeja de la plataforma
                                              │  (la plataforma aprueba)
                                              ▼
              Centro creado en PRUEBA + invitación al dueño (correo)
                                              │
                                              ▼
           El dueño fija su contraseña ──► primer ingreso ──► stepper de su sede
                                              │                 (ya hecho: commit 9a16a8ba)
                                              ▼
                 Usa AKINE 30 días ──► avisos ──► elige plan y servicios
```

La plataforma también puede dar de alta un centro **sin** solicitud, desde su consola: es el mismo
formulario de aprobación, cargado a mano.

## 4. La landing

Ruta pública `/` de `appKine-web`, sin guards, con su propio layout (sin barra lateral). Quien ya
tiene sesión y entra a `/` va directo a su inicio. Sigue `PRODUCT.md` y `DESIGN.md` (los dos temas,
375 y 1440 px, AA).

Secciones, en orden:

1. **Cabecera:** logo, "Servicios", "Planes", "Preguntas", botón **Ingresar** y botón primario
   **Probalo gratis**.
2. **Portada:** qué es AKINE en una frase, para quién es, botón **Probalo gratis 30 días** y una
   captura de la agenda.
3. **Dos servicios, dos tarjetas:** Kinesiología y Actividades, con tres o cuatro beneficios
   concretos cada una, y la idea de que se pueden tener los dos.
4. **Lo que tienen todos:** agenda, recepción, caja y cobros, reportes, marca propia, acceso desde
   la tablet.
5. **Varias sedes, una organización:** pacientes y alumnos compartidos, cada sede con su agenda y su
   horario, reportes por sede y consolidados, equipo que trabaja en más de una sede.
6. **El adicional de IA:** qué hace cada función, que los datos no salen del centro (el modelo corre
   local) y que el centro decide qué prende.
7. **Planes:** Base, Crecimiento y Red, con un armador simple: elegís sedes, servicios y si querés
   IA, y ves qué incluye. **Sin precios** hasta que el usuario los defina (§9).
8. **Preguntas frecuentes:** qué pasa al terminar la prueba, si se pueden cambiar los servicios
   después, dónde quedan los datos.
9. **Pie:** contacto, términos y privacidad.

Todo el texto vive en el frontend. No hay administrador de contenidos.

## 5. El pedido de prueba

### Formulario

Nombre y apellido, correo, teléfono (opcional), nombre del centro, ciudad, qué servicios le
interesan (Kinesiología, Actividades o los dos), cuántas sedes tiene, si le interesa la IA, un
comentario libre y la aceptación de términos.

### Backend

- `POST /api/v1/public/solicitudes-de-prueba`, **sin autenticación**, idempotente por
  `Idempotency-Key`.
- **Defensas de un endpoint público:** límite por IP (el que ya tiene el login), un campo trampa
  oculto para bots, tope de largo en cada campo y respuesta siempre igual (`202`), exista o no ese
  correo, como el registro de hoy. Nada de CAPTCHA en esta etapa.
- Tabla nueva `solicitud_de_prueba`, global (sin `organization_id`, ADR-0023): datos del formulario,
  `estado` (`PENDIENTE`, `APROBADA`, `DESCARTADA`), quién la resolvió, cuándo y el centro que generó.
- **Aviso a la plataforma** por el outbox de correo cuando entra una solicitud.

### Plataforma

- **Bandeja** `/plataforma/solicitudes-de-prueba`: tabla con buscador, filtro por estado y orden por
  fecha, como el resto de las tablas.
- **Aprobar** abre el formulario de alta de centro prellenado. **Descartar** pide motivo.

## 6. El alta de un centro desde la plataforma

`POST /api/v1/platform/organizations`, sólo `PLATFORM_ADMIN`, idempotente. Reusa el onboarding
compuesto de ADR-0008 (organización, suscripción y primera sede en una transacción) con dos
diferencias:

1. **La cuenta del dueño no trae contraseña.** Se crea o se reusa por correo, y se le manda una
   **invitación** con el flujo que ya existe (`/auth/invitacion`, ADR-0018). Si el correo ya tiene
   cuenta, sólo se le agrega la membresía de dueño.
2. **Lleva la contratación:** prueba o plan, servicios e IA.

Formulario en la consola: nombre del centro, nombre de la primera sede, zona horaria, correo,
nombre y apellido del dueño, y la contratación (prueba de 30 días, o plan, servicios e IA).

**Se corta el autoregistro.** `POST /auth/register` queda detrás de
`akine.registro.autoservicio.enabled`, **apagado por defecto**, y responde `404` apagado. El
frontend saca "Crear una cuenta" del ingreso. Se deja la bandera y no se borra el código: los E2E y
los ITs que hoy se registran solos lo siguen usando con la bandera prendida.

## 7. Modelo de datos y contrato

### Migración (reservar el número al empezar, hoy sería V28)

- `subscription`: `status` suma **`PRUEBA`**; columnas nuevas `prueba_hasta timestamptz` (obligatoria
  en `PRUEBA`, `CHECK`) y `solo_lectura boolean`.
- `plan`: se siembran `BASE`, `CRECIMIENTO` y `RED`, con su `MAX_CONSULTORIOS`. `BASICO` y
  `PROFESIONAL` se dan de baja lógica, y sus suscripciones se pasan a `BASE` y `CRECIMIENTO`.
- **`subscription_servicio`** (`organization_id`, `servicio` en `KINESIOLOGIA` o `ACTIVIDADES`):
  una fila por servicio contratado. Las organizaciones que ya existen reciben **los dos**, para no
  quitarle nada a nadie.
- **`subscription_adicional`** (`organization_id`, `adicional` en `IA`): el mismo patrón, pensado
  para que entren otros adicionales sin otra migración.
- `solicitud_de_prueba` (§5).

### Qué hace cumplir cada cosa

| Regla | Dónde | Respuesta |
|---|---|---|
| Tope de sedes del plan | Ya existe (`MAX_CONSULTORIOS`) | `409` como hoy |
| Servicio no contratado | Un `ServicioContratadoProbe` en `organization.spi` que consultan `clinical`, `encounter`, `contracting` y `activity` en sus escrituras y lecturas | `403` con `problemType` nuevo `servicio-no-contratado` |
| IA no contratada | La misma consulta, sumada a la de `organization_ia` | `404`, igual que hoy con la función apagada |
| Prueba vencida | Tarea diaria que pasa `PRUEBA` vencida a sólo lectura; un filtro rechaza las escrituras | `403` `suscripcion-solo-lectura`; las lecturas siguen |

### Contrato (minor nuevo sobre 0.177.0)

- `GET /me` o el contexto del centro suma **`contratacion`**: plan, servicios, adicionales, estado
  y `pruebaHasta`. El frontend arma el menú con esto.
- Operaciones nuevas: la solicitud pública, la bandeja y su resolución, el alta de centro desde la
  plataforma y `PUT /platform/organizations/{id}/contratacion`.
- Dos `problemType` nuevos: `servicio-no-contratado` y `suscripcion-solo-lectura`.

## 8. Etapas

Cada etapa es un commit, o pocos, en la rama de la tanda. El frontend de cada etapa va después de
su backend y del cliente regenerado.

| # | Etapa | Lado | Depende de |
|---|---|---|---|
| 1 | **Landing** con el texto, las secciones y el armador de planes, sin formulario | web | — |
| 2 | **Modelo de contratación:** migración, planes nuevos, servicios, adicionales y `contratacion` en `/me` | api | — |
| 3 | **Alta de centro desde la plataforma** + invitación al dueño + corte del autoregistro | api, web | 2 |
| 4 | **Pedido de prueba:** endpoint público, bandeja y aprobación | api, web | 3 |
| 5 | **Menú según lo contratado** y bloqueo de servicios en el backend | api, web | 2 |
| 6 | **IA como adicional:** contratada ∧ prendida | api, web | 2 |
| 7 | **Vencimiento de la prueba:** tarea diaria, sólo lectura, avisos y franja en la app | api, web | 2 |
| 8 | **Cambiar la contratación** desde la plataforma (plan, servicios, IA, pasar de prueba a plan) | api, web | 2 |

**La 1 se puede hacer ya**: no espera al backend, y el botón "Probalo gratis" puede ir a un
`mailto:` hasta que exista la 4. Las 2 y 3 son el cimiento; desde ahí, la 4 a la 8 son
independientes entre sí.

**Verificación de cada etapa:** se prueba en la app real contra `akine_main`, con lo que la etapa
toca. Los ITs de concurrencia y de seguridad de lo nuevo (endpoint público y bloqueo por servicio)
no se recortan; el resto, pocos tests, como dice `CLAUDE.md`.

## 9. Decisiones abiertas del usuario

1. **Precios** de cada plan, de cada servicio y del adicional de IA. Mientras no estén, la landing
   no muestra precios.
2. **Qué trae la prueba:** todo prendido (propuesta) o sólo lo que pidió en el formulario.
3. **Al vencer sin elegir:** sólo lectura (propuesta), suspensión o cancelación.
4. **Miembros por plan:** se mantiene un tope (hoy 5 en `BASICO`) o se saca.
5. **Plan Red:** tope fijo de sedes o "a convenir".
6. **Cobro de la suscripción:** fuera de este plan. Por ahora la plataforma pasa el centro de prueba
   a plan a mano; el cobro online es otra etapa.
7. **Términos y privacidad:** la landing los enlaza, y el texto legal es del usuario.

## 10. Lo que no entra

- Cobro online y facturación de la suscripción.
- Un administrador de contenidos para la landing.
- Blog, posicionamiento en buscadores y dominio propio, que vienen con el despliegue (U-02).
- Que el propio centro cambie su plan: por ahora lo hace la plataforma.
