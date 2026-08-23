# Tests diferidos — deuda de verificación con etapa destino

Registro explícito de escenarios **decididos y no ejecutados**. Existe para que nada se dé
por cubierto sin haber corrido: un escenario que no está en esta lista y no tiene test, es
un olvido; uno que está acá, es una decisión con fecha.

Se revisa al cerrar cada etapa. Una fila solo se borra de la tabla de pendientes cuando el
test existe y pasa.

---

## Ejecución de los diferidos de 01.01 — AKINE-01.02

Los once escenarios recibieron test en `src/test/java/com/akine/diferidos/`. Los datos de abajo
son la foto de `target/failsafe-reports` al **2026-08-23 15:30**, no una corrida hecha al escribir
este documento.

### Cerrados: test escrito y en verde

| # | Escenario | Test | Evidencia |
|---|---|---|---|
| 1 | Cross-tenant en todos los endpoints | `AislamientoDeTenantIT` — "Con contexto de A, los tres endpoints de B responden 404 y ninguno 403" | 3 tests, 0 fallos |
| 2 | Membership revocada entre requests | `AislamientoDeTenantIT` — "ventana de revocacion cero" | ídem |
| 3 | Suscripción SUSPENDIDA | `AislamientoDeTenantIT` — "la mutacion es 409 subscription-suspended y el historico sigue en 200" | ídem |
| 4 | Transición de suscripción concurrente | `TransicionConcurrenteDeSuscripcionIT` | 1 test, 0 fallos |
| 5 | Onboarding con fallo parcial | `RollbackDeOnboardingIT` | 1 test, 0 fallos |
| 6 | Onboarding con reintento concurrente | `IdempotenciaYUniquesIT` — casos 6 y 6-bis | 6 tests, 0 fallos, 1 saltado |
| 9 | Uniques con alcance tenant | `IdempotenciaYUniquesIT` — caso 9 | ídem |

### Abiertos

| # | Escenario | Estado | Motivo y etapa destino |
|---|---|---|---|
| 7 | `Idempotency-Key` repetida por HTTP | **parcial** — 7a (misma clave, mismo payload → replay) y 7c (el conflicto funciona en el alta compuesta de `organization`) pasan; **7b está `@Disabled`** en `IdempotenciaYUniquesIT:132` | La mitad "payload distinto" **no tiene camino HTTP**. `AccountRegistrationController:147` pasa `requestHash = null` por decisión documentada de 01.02, y la tabla `onboarding_registro` (migración V8) no tiene columna donde guardarlo. Como `identity` corta primero en el replay (`OnboardingService:137`), la comparación de hash que sí existe en `organization` nunca se ejecuta: la segunda alta responde `202` en vez de `409 idempotency-key-conflict`, o sea que le acusa recibo a un pedido que ignoró. El otro camino candidato, `POST /api/v1/organizations`, documenta explícitamente que su `Idempotency-Key` no registra idempotencia. Arreglarlo es una migración con `request_hash` más un cambio de API → **AKINE-01.03** |
| 8 | Límite de plan bajo concurrencia | **rojo** — `LimiteDePlanConcurrenteIT` falla | Las dos altas simultáneas del recurso número límite entran las dos: `[exactamente un alta entra. Desenlaces: [OK 900000004, OK 900000005]] expected: 1L but was: 2L` (`LimiteDePlanConcurrenteIT:99`). Es un bug real de concurrencia, no del test: el bloqueo pesimista no está conteniendo la carrera. **Se corrige dentro de 01.02**, antes de cerrar la etapa |
| 10 | E2E de cambio de contexto sin fuga | **sin test** | Crear organización, seleccionar contexto, ver solo sus datos; cambiar de organización y comprobar que no queda ningún dato residual. Requiere el frontend de 01.02 commiteado y un E2E de auth que hoy no existe (`e2e/smoke.spec.ts` no toca ningún flujo de sesión). Destino: **AKINE-01.03** |
| 11 | E2E de errores sin internals | **sin test** | Ninguna respuesta de error contiene `com.akine`, `org.springframework` ni `stacktrace` (ADR-0005). Mismo bloqueo que el 10. Destino: **AKINE-01.03** |

**Nota sobre el #8:** de los once es el más importante, y es el único que encontró el bug que
buscaba. La carrera existía en el diseño original y se detectó en el design challenge; el test es
lo único que evita que alguien "simplifique" la firma del `PlanGate` y la reintroduzca sin que
nadie se entere. Mientras esté en rojo, **01.02 no puede declarar cerrado el escenario**.

---

## Cómo se cierra esta deuda

Al cerrar AKINE-01.02, el registro de cierre debe dar cuenta de las cuatro filas abiertas: la 8
en verde, y la 7b, la 10 y la 11 explícitamente reasignadas a 01.03 con su motivo.

Mientras existan filas en la tabla de abiertos, el registro de cierre de la etapa correspondiente
**no puede afirmar** que los criterios de aceptación asociados están cubiertos: están decididos y
pendientes de verificación, que no es lo mismo.

Los orígenes de cada escenario (RF/RN/CA y número de test del diseño de 01.01) están en el
`@DisplayName` de cada test y en el historial de este archivo: `git log -p docs/tests-diferidos.md`.
