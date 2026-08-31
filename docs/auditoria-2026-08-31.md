# Auditoría del 31/08/2026 — promesas que el código no cumple

Dos enjambres de subagentes, diez auditores en total y un escéptico por hallazgo cuyo trabajo era
**refutarlo** antes de que llegara acá. Los crudos completos están en
`auditoria-2026-08-31-crudo.json` (backend) y `auditoria-2026-08-31-web-crudo.json` (frontend).

> **Nada de esto está arreglado.** El único hallazgo verificado a mano —leyendo el código, no
> confiando en el agente— es el primero. **El resto está sin verificar por mí**: sobrevivió a su
> escéptico, que no es lo mismo.

## 1. El correo de invitación a colaborar nunca se envía — VERIFICADO A MANO

**Gravedad alta. Rompe RF-M05-001/002 de punta a punta: 02.03 entera.**

La cadena, con los cuatro eslabones confirmados uno por uno:

1. `ColaboradorInvitacionService.encolarCorreo` (línea ~562) encola `INVITACION_COLABORADOR`
   **con su enlace real** y la clave `"invitacion:{id}:{expiraEnMillis}"`.
2. `NotificationType.INVITACION_COLABORADOR(true)` — **sí requiere enlace seguro**.
3. `IdentitySecureLinkResolver.tipoDeTokenDe` devuelve `Optional.empty()` para ese tipo, con un
   javadoc que dice *«INVITACION_COLABORADOR es de 01.03»*. Ya no lo es: llegó en 02.03.
   `resolveLink` corta en su línea 55 y **nunca llega al vault**.
4. `OutboxDispatcher.entregar`: `requiereEnlaceSeguro() && enlace == null` →
   `registrarFalloPermanente(...)`. **Permanente, sin reintento.**

El motivo que se registra —*"El token referenciado ya no es valido: fue consumido, revocado o
vencio"*— **es falso**: el token está vivo con 14 días por delante.

> **Son dos causas independientes, no una.** Aunque se mapee el enum, sigue roto por dos motivos
> más: el token de invitación **no vive en `token_verificacion`** —su hash está en
> `colaborador_invitacion`, y `TipoTokenVerificacion` lo documenta—, así que el `findById` de la
> línea 66 tampoco lo encontraría; y `OutboxNotificationBridge.referenciaDe` toma el substring
> tras el **último** `:`, o sea que de `"invitacion:{id}:{millis}"` extrae **el timestamp**, no un
> id. El arreglo es de diseño, no de una línea.

**Y el test lo consagra en verde.** `IdentitySecureLinkResolverTest:109` mete
`INVITACION_COLABORADOR` en la misma aserción que `CUENTA_YA_REGISTRADA`, bajo el `@DisplayName`
"los tipos sin enlace ... devuelven vacio". Pero el enum dice `true`. Si alguien arregla el
resolutor, **ese test se pone rojo y el arreglo parece la regresión**.

## 2. Resto del backend — sobrevivieron al escéptico, sin verificar a mano

| Módulo | Hallazgo |
|---|---|
| `organization` | El alta de sede promete evaluar un permiso que no evalúa |
| `organization` | El listado de accesos de soporte dice devolver los históricos y filtra por `active=1` |
| `resource` | El conteo de dependientes de una especialidad ignora el aislamiento por `owner_key` |
| `resource` | La sonda de desvinculación afirma que todo bloque contado rige "ahora mismo" |
| `person` | La "segunda capa de idempotencia" del alta de perfil convierte el 500 que dice evitar en otro 500 |
| `offering` | `puedePrestarse` da `false` por capacidad insuficiente en un caso que el javadoc describe distinto |

## 3. Frontend

| Zona | Hallazgo |
|---|---|
| `auth` | **"Volver a intentar" en el enlace de invitación vencido no puede tener éxito nunca.** Reenvía el mismo token muerto. La pantalla hermana, `activate-page`, toma la decisión contraria y la documenta: ahí el botón directamente no existe |
| `auth` | Su spec dice "ofrece reintentar" y **solo mira `textContent`**: nunca clickea el botón. Es el mismo patrón del defecto del motivo obligatorio |

## 4. Lo que esto dice del proyecto

Los dos defectos del 30/08 y estos salieron de **la misma grieta**: un test que verifica lo que la
pantalla *dice* o el código de respuesta que *devuelve*, sin ejercer nunca el escenario. Y en los
dos casos de `auth`, la pantalla hermana ya había resuelto bien el mismo problema — o sea que la
respuesta correcta estaba escrita en el repo, al lado.
