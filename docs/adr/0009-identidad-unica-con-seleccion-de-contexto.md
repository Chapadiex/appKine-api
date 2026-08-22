# ADR-0009 — Identidad única con selección de contexto posterior al login

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

`AKINE_info.txt` propone que el usuario elija "Paciente" o "Profesional" **antes** de
autenticarse: dos puertas de entrada según quién decís ser. La especificación moderna (M02)
define lo contrario: una identidad única, con roles distintos según el contexto en el que
actúa.

La discrepancia no es de UX sino de autorización:

- Con selección previa, un dato elegido por el cliente antes de autenticarse condiciona el
  flujo de permisos. Todo lo que el cliente declara es input no confiable.
- La misma persona puede ser profesional en una Organización y paciente en otra. Dos
  puertas empujan a dos cuentas para un mismo humano, y la deduplicación de personas (M07)
  nace rota.
- AKINE es multi-tenant: los permisos reales no dependen de "qué sos" sino de **dónde
  estás actuando** — qué Organización, qué Consultorio, con qué membership vigente.

El impacto declarado —seguridad, UX y autorización— toca el diseño del token, del login y
de toda verificación de permisos del backend. Había que resolverlo antes de AKINE-01.02.

## Decisión

El backend autentica **una identidad única, sin selección previa de rol**.

Después del login, el usuario selecciona una **Organización y un Consultorio** entre sus
contextos autorizados. El backend calcula los permisos efectivos a partir de las
memberships y los roles vigentes en ese contexto; la interfaz no puede elevar privilegios:
lo que el frontend muestre u oculte es cosmética, la autoridad es siempre el backend.

El **access token queda acotado al contexto seleccionado**: sus claims incluyen la
Organización y el Consultorio activos, y solo autoriza operaciones dentro de ese alcance.
El cambio de contexto autorizado no exige un nuevo login, pero **sí emite o renueva un
access token** para el contexto nuevo, y queda auditado (quién, cuándo, de qué contexto a
cuál).

Del lado del navegador, ese token acotado vive solo en memoria, según el
[ADR-0001 del frontend](../../../appKine-web/docs/adr/0001-token-en-memoria-refresh-en-cookie-httponly.md):
la combinación de token de vida corta, acotado a un contexto y nunca persistido reduce el
radio de un robo de token a un contexto y a minutos.

## Alternativas consideradas

**Selección de rol antes del login (modelo histórico).** Descartada porque convierte una
declaración del cliente en insumo de autorización, duplica cuentas para personas con dos
roles y complica la recuperación de acceso ("¿en cuál de tus cuentas?"). El rol no es un
atributo de la persona: es un atributo de su vínculo con un contexto.

**Token global con la unión de todos los permisos.** Un solo token para todos los
contextos, sin renovación al cambiar. Descartada: viola mínimo privilegio —un token robado
abriría todas las organizaciones del usuario— y obliga a cada endpoint a resolver "en qué
tenant estoy" por otros medios, multiplicando las oportunidades de error de aislamiento.

**Nuevo login por cada cambio de contexto.** Máxima simpleza conceptual. Descartada:
castiga al usuario multi-sede varias veces por día sin ganancia real de seguridad — es la
misma identidad, ya autenticada, con la misma sesión de refresh.

## Consecuencias

### Positivas

- Autorización con una sola fuente de verdad: membership y rol vigentes en el contexto del
  token. No hay "modo" del cliente que interpretar.
- El aislamiento multi-tenant se refuerza en la capa de autenticación: el token ya viene
  acotado, no hay que confiar en que cada query filtre bien.
- Una persona = una cuenta. La deduplicación de Personas (M07) y la auditoría (M24)
  referencian una identidad estable.

### Negativas

- El cambio de contexto requiere coreografía de renovación de token entre frontend y
  backend; es un flujo más a diseñar, probar y auditar.
- La revocación a mitad de sesión (membership dada de baja con un token de contexto vivo)
  exige una decisión explícita sobre la ventana de validez del access token.
- El usuario con un solo contexto necesita un atajo de UX (selección automática) para no
  ver un selector de una sola opción.

### Qué obliga a hacer

- `identity` es propietario de autenticación, sesión de refresh y emisión de tokens;
  `organization`, de memberships y roles. El cálculo de permisos efectivos consume
  `organization` vía `spi` ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)).
- Los claims del access token incluyen el contexto activo; ningún endpoint de negocio
  acepta un token sin contexto.
- Toda autorización se verifica server-side contra membership y rol vigentes — nunca
  contra claims de rol cacheados de larga vida.
- El cambio de contexto emite evento de auditoría. Etapas: AKINE-01.02 (login y tokens) y
  AKINE-01.03 (roles y permisos).
