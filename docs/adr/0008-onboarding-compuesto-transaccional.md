# ADR-0008 — Onboarding compuesto y transaccional para el registro del primer propietario

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

Las fuentes históricas (`AKINE_info.txt`, `AkinePN.docx`) describen el registro de un
profesional como un acto único: registrarse crea automáticamente su primer consultorio.
La especificación moderna (M01/M03) separa cuatro conceptos con identidad y ciclo de vida
propios: **Cuenta** (identidad global), **Organización** (tenant con suscripción SaaS),
**Consultorio** (sede operativa) y **Membership** (vínculo con rol y vigencia).

La contradicción bloqueaba lo primero que se construye (AKINE-01.01–01.03, 02.01):

- Si el registro crea el consultorio implícitamente, desaparece el nivel Organización,
  que es donde viven la suscripción, sus límites y el aislamiento multi-tenant. El
  ownership queda ambiguo: ¿de quién es el consultorio cuando el profesional se va?
- Si se separan las cuatro altas sin más, el usuario nuevo enfrenta una cadena de
  formularios, y un fallo a mitad de camino deja un tenant a medio construir —una Cuenta
  sin Organización, una Organización sin Consultorio— que ningún flujo posterior sabe
  manejar.

El impacto declarado de la DP-01 es exactamente ese: transacción de alta, rollback, plan
SaaS inicial y ownership. Sin resolverlo no se puede escribir la primera migración de
`identity` ni de `organization`.

## Decisión

El registro del primer propietario ejecuta un **onboarding compuesto, transaccional e
idempotente** que crea en una sola operación de negocio:

1. la **Cuenta** del usuario — módulo `identity`;
2. la **Organización** con su suscripción inicial — módulo `organization`;
3. el **primer Consultorio** de esa Organización — módulo `organization`;
4. la **Membership de propietario** que vincula la Cuenta con ese contexto.

Las cuatro entidades conservan identidad, responsabilidades y ciclo de vida separados: el
onboarding las crea juntas, no las fusiona. La Organización puede tener después más
consultorios, más miembros y otro propietario sin tocar el modelo.

Dos garantías son parte de la decisión, no detalles de implementación:

- **Idempotencia.** Un reintento del mismo registro devuelve el resultado previamente
  creado. No duplica entidades ni falla por colisión de únicos.
- **Atomicidad verificable.** Un fallo parcial revierte la operación completa o queda
  compensado de forma comprobable. No existen estados intermedios observables.

## Alternativas consideradas

**Registro con consultorio implícito (modelo histórico).** Un solo formulario, cero
fricción. Descartada porque colapsa cuatro entidades en una: sin nivel Organización no hay
dónde colgar suscripción ni límites SaaS; el profesional invitado a un consultorio ajeno
no tiene representación; y separar las entidades después, con datos en producción, es una
migración estructural del núcleo del sistema.

**Altas encadenadas manuales, sin transacción.** Cuatro pantallas honestas con el modelo.
Descartada porque cada corte de red produce tenants a medio construir, y porque traslada
al usuario una distinción interna —Cuenta vs Organización vs Consultorio— que en su primer
minuto de uso no le aporta nada.

**Onboarding como saga asíncrona.** Eventos y compensaciones entre módulos. Descartada
para este flujo: el usuario espera una respuesta síncrona, el volumen de registros no
justifica la maquinaria, y una saga introduce justamente los estados intermedios
observables que la decisión prohíbe. Si el onboarding creciera (aprovisionar recursos
externos), se reevalúa con un ADR nuevo.

## Consecuencias

### Positivas

- El modelo SaaS queda íntegro desde el primer usuario: Organización, suscripción y
  límites existen siempre, aunque el usuario nunca los haya mirado.
- El "consultorio del profesional" del modelo histórico sigue siendo posible como caso
  particular: una Organización con un Consultorio y un miembro.
- El reintento seguro elimina la clase entera de bugs de doble alta.

### Negativas

- La operación cruza dos módulos (`identity` y `organization`): exige un contrato `spi`
  entre ellos y una coordinación transaccional que un alta simple no tendría.
- La idempotencia requiere una clave de idempotencia persistida y su limpieza; no es
  gratis ni conceptual ni operativamente.
- Probar los fallos parciales (crash entre pasos, reintento concurrente) exige tests de
  integración deliberados, no unitarios.

### Qué obliga a hacer

- `identity` es propietario de Cuenta; `organization`, de Organización, Consultorio y
  Membership. Ninguno escribe tablas del otro: el onboarding orquesta vía `spi`
  ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)).
- Toda tabla creada respeta las convenciones multi-tenant de
  [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md).
- El endpoint de registro acepta reintentos: mismo request, misma respuesta.
- Los tests de las etapas AKINE-01.01–01.03 cubren el fallo parcial y el reintento
  concurrente como escenarios de primera clase.
