# Tests diferidos — deuda de verificación con etapa destino

Registro explícito de escenarios **decididos y no ejecutados**. Existe para que nada se dé
por cubierto sin haber corrido: un escenario que no está en esta lista y no tiene test, es
un olvido; uno que está acá, es una decisión con fecha.

Se revisa al cerrar cada etapa. Una fila solo se borra cuando el test existe y pasa.

---

## Diferidos de AKINE-01.01 → destino AKINE-01.02

Motivo común: **todos necesitan una sesión autenticada real**, y el login llega en 01.02.
Montar un backdoor de autenticación para que corran antes produciría tests verdes que no
prueban el camino real.

| # | Escenario | Qué verifica | Origen |
|---|---|---|---|
| 1 | Cross-tenant en todos los endpoints | Con contexto de la organización A, pedir organización, suscripción y consultorios de B devuelve **404** en los tres casos — nunca 403, que confirmaría la existencia | CA-\*-03; diseño §13 test 10 |
| 2 | Membership revocada entre requests | Emitido el contexto, se revoca la membership: el request siguiente falla. Ventana de revocación cero | Caso QA 6; diseño §13 test 11 |
| 3 | Suscripción SUSPENDIDA | Mutación de negocio → `409 subscription-suspended`; el GET de históricos sigue devolviendo 200 | RN-M01-002, CA-\*-06; test 12 |
| 4 | Transición de suscripción concurrente | Dos `POST transitions` con el mismo `expectedStatus`: una gana, la otra recibe 409 | RNF-M01-003, caso QA 2; test 13 |
| 5 | Onboarding con fallo parcial | Fallo inyectado a mitad de la transacción compuesta → rollback total, cero filas en las cuatro tablas | ADR-0008, CA-001-04; test 7 |
| 6 | Onboarding con reintento concurrente | Dos hilos con la misma clave de idempotencia → un solo tenant creado | CA-001-05, caso QA 4; test 8 |
| 7 | `Idempotency-Key` repetida por HTTP | Misma clave y mismo payload → replay; misma clave y payload distinto → 409 `idempotency-key-conflict` | Diseño §10; test 9 |
| 8 | Límite de plan bajo concurrencia | Dos altas simultáneas del recurso número límite: una crea, la otra recibe `409 plan-limit-exceeded`. **Nunca dos.** Es el test que prueba que el bloqueo pesimista funciona | Challenge B-3 |
| 9 | Uniques con alcance tenant | Mismo nombre de consultorio en dos organizaciones distintas: ambas válidas. En la misma: violación | ADR-0004; test 15 |
| 10 | E2E de cambio de contexto sin fuga | Crear organización, seleccionar contexto, ver solo sus datos; cambiar de organización y comprobar que **no queda ningún dato residual** de la anterior | Criterio de aceptación de la etapa; diseño §13 test 22 |
| 11 | E2E de errores sin internals | Ninguna respuesta de error contiene `com.akine`, `org.springframework` ni `stacktrace` | ADR-0005; test 23 |

**Nota sobre el #8:** de los once, es el más importante. La carrera que corrige existía en el
diseño original y se detectó en el design challenge; el test es lo único que evita que alguien
"simplifique" la firma del `PlanGate` y la reintroduzca sin que nadie se entere.

---

## Cómo se cierra esta deuda

Al ejecutar AKINE-01.02, la implementación del login habilita los once. El registro de cierre
de esa etapa debe listar cada uno como ejecutado, o justificar por qué sigue diferido.

Mientras existan filas en esta tabla, el registro de cierre de la etapa correspondiente **no
puede afirmar** que los criterios de aceptación asociados están cubiertos: están decididos y
pendientes de verificación, que no es lo mismo.
