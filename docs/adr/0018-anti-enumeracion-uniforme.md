# ADR-0018 — Respuestas uniformes: la autenticación no revela si una cuenta existe

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-01.02

## Contexto

Los tres endpoints públicos de identidad —login, registro y solicitud de recuperación—
reciben un email de un desconocido. Lo que respondan le enseña algo al que pregunta. Si la
respuesta cambia según el email exista o no, el endpoint es un **oráculo de existencia de
cuentas**: con una lista de correos y un script se obtiene el padrón de quién usa AKINE. Sobre
un sistema de historia clínica, ese padrón ya es dato sensible: dice quién es paciente o
profesional de un centro de kinesiología.

**La especificación empuja en la dirección contraria.** Su catálogo de errores es una
plantilla idéntica en los cinco RF de M02, y ahí `NOT_FOUND` ("entidad inexistente o no
accesible") figura como error esperable **también** de RF-M02-002 (login) y RF-M02-003
(recuperación). Implementado al pie de la letra —"email inexistente → 404"— eso es exactamente
el oráculo descrito. Es la trampa T2 del digest de M02.

La especificación **no tiene ninguna regla explícita anti-enumeración**. Lo que apunta en la
dirección correcta es genérico: RN-M02-003, `INTERNAL_ERROR` "sin exponer información
sensible", RNF-M02-005, y el plan, que exige "contratos de identidad sin enumeración".

Hay un segundo canal, más sutil que el código de estado: el **tiempo**. Verificar una
contraseña con Argon2id cuesta decenas de milisegundos; responder "no existe" sin verificar
nada cuesta casi cero. La diferencia se mide con un `curl` en un bucle y enumera igual.

## Decisión

**Login, registro y recuperación responden sin revelar si la cuenta existe.**

**Login** — `401` con `type` `invalid-credentials`, mismo body y mismo tiempo en tres casos:

1. el email no corresponde a ninguna cuenta: se verifica contra un **hash dummy precomputado
   de costo equivalente**, para que el tiempo de respuesta no delate;
2. la cuenta existe y la contraseña es incorrecta;
3. **la contraseña es correcta pero la cuenta no está `ACTIVA`** —bloqueada, desactivada o
   pendiente de activación—. Internamente se emite `LOGIN_RECHAZADO_ESTADO`, que queda en la
   auditoría; hacia afuera la respuesta es idéntica.

**Registro** — `202 Accepted` uniforme, exista o no una cuenta con ese email. Si ya está
registrado no se crea nada y se encola en el outbox un mail "ya tenés cuenta: iniciá sesión o
recuperá tu contraseña". El usuario legítimo que se olvidó de que ya se había registrado llega
a destino por el canal que prueba que el email es suyo; el atacante no aprende nada.

**Recuperación** — siempre `202`, mismo body ("si el email existe, enviaremos
instrucciones"). Solo si la cuenta existe y está `ACTIVA` se emite el token y la fila de outbox.

**Confirmación de token** — activación y confirmación de reset responden `400 invalid-token`
uniforme para token inexistente, usado, invalidado o expirado.

Esto es una **desviación deliberada del texto literal de la especificación**: `NOT_FOUND` no se
implementa como error de login ni de recuperación pese a figurar en el catálogo de RF-M02-002 y
RF-M02-003. Se funda en el espíritu de la propia especificación —RN-M02-003, `INTERNAL_ERROR`
"sin exponer información sensible", RNF-M02-005— y en el plan. Queda escrito acá para que nadie
"corrija" el código creyendo que le falta un 404.

## Alternativas consideradas

**Implementar el catálogo literal: `404 NOT_FOUND` en login y recuperación.** La opción más
trazable, la que hace calzar la implementación uno a uno con el texto del RF. Descartada:
entrega el padrón de cuentas a cualquiera con un script. La trazabilidad a la letra no vale una
fuga que las reglas generales de la misma especificación prohíben.

**`403 account-disabled` tras contraseña válida.** Es lo cómodo: el usuario bloqueado ve por qué
no entra y no llama a soporte. Descartada porque convierte el login en un **oráculo de
credenciales válidas**. Un atacante con credenciales filtradas de otro sitio —la reutilización
de contraseñas es la norma— distingue cuáles funcionan acá aunque no pueda entrar: el `403`
confirma "usuario y contraseña correctos". Esa lista se guarda y se usa el día que la cuenta se
desbloquee, o contra el canal de soporte. Sobre historia clínica, eso no se regala por comodidad.

**`409 email-already-registered` en el registro.** Era la decisión original del diseño (D7-bis),
justificada en que es el comportamiento estándar y en que el rate limiting mitiga el abuso.
Descartada por incoherente: cerrar la puerta del login y dejar abierta la del registro —más
fácil de automatizar, porque no necesita contraseña— deja el oráculo intacto. El rate limiting
encarece la enumeración masiva; no impide la consulta puntual sobre un email concreto.

**Uniformar el estado pero no el tiempo.** Descartada: el canal temporal enumera igual. Sin hash
dummy, la diferencia entre "no existe" y "contraseña incorrecta" es de un orden de magnitud.

**Un captcha en vez de respuestas uniformes.** Descartada: encarece la automatización pero sigue
respondiendo la pregunta a quien la formule una vez, degrada la accesibilidad y agrega una
dependencia externa en el camino crítico del login.

## Consecuencias

### Positivas

- Ninguno de los tres endpoints públicos permite construir un padrón de cuentas, ni por código
  de estado ni por tiempo de respuesta.
- Una filtración de credenciales de otro sitio no se puede validar contra AKINE.
- Registro y recuperación quedan con la misma forma —`202` uniforme más outbox—, lo que reduce
  la superficie de casos, y es coherente con la regla de 01.01 "cross-tenant devuelve 404".

### Negativas

- **El usuario legítimo cuya cuenta fue bloqueada no se entera por la aplicación.** Escribe su
  contraseña correcta y recibe "credenciales inválidas"; se entera por el canal administrativo,
  que es donde corresponde. Mientras tanto va a reintentar, va a usar "olvidé mi contraseña"
  —que también responde `202` sin hacer nada, porque la cuenta no está `ACTIVA`— y va a llamar a
  soporte convencido de que el sistema está roto. Es incómodo y es deliberado.
- El registro ya no dice "ese email está en uso" en el momento: el aviso llega por email, con la
  latencia del outbox. Va a generar reportes de "no pasó nada al registrarme".
- La implementación **no** coincide con la letra de la especificación. Toda revisión de
  trazabilidad RF↔código va a marcar el `NOT_FOUND` faltante, y la respuesta es este ADR.
- El hash dummy gasta CPU deliberadamente en el caso que más se repite bajo fuerza bruta. Es un
  costo aceptado, acotado por el rate limiting por IP y por email.
- La uniformidad no es perfecta: la rama "la cuenta existe" hace trabajo extra —emitir token,
  insertar outbox—. Se considera no medible frente al jitter de red, pero no es una garantía.

### Qué obliga a hacer

- El caso de uso de login en `identity.application` **no tiene rama de salida por estado de
  cuenta**: toda falla desemboca en la misma excepción, mapeada a `invalid-credentials` en el
  advice de `identity.api` ([ADR-0005](0005-errores-como-problem-details.md)).
- El hash dummy se precomputa al arrancar con los mismos parámetros de Argon2id que las
  contraseñas reales; si se suben los parámetros, se sube también el dummy o el tiempo delata.
- El registro que detecta un email existente inserta la fila de outbox con su clave idempotente
  y **no** toca la cuenta existente. El outbox nunca guarda el token en claro.
- El plan de tests de 01.02 incluye como casos explícitos: mismo `401` para email inexistente,
  contraseña incorrecta y cuenta bloqueada con contraseña correcta; mismo `202` de registro con
  email libre y con email tomado; mismo `202` de recuperación con y sin cuenta.
- La distinción real entre credenciales inválidas y cuenta bloqueada vive en la auditoría
  (`LOGIN_FALLIDO`, `LOGIN_RECHAZADO_ESTADO`), consultable desde M24 en AKINE-01.03.
- El frontend muestra el mensaje uniforme y un enlace a recuperación: ninguna heurística de
  cliente para adivinar el estado real.
- La documentación de soporte y de QA registra el síntoma "contraseña correcta, 401": es cuenta
  bloqueada, y se verifica en la auditoría o en la base, jamás cambiando la respuesta.
