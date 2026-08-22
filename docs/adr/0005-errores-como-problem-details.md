# ADR-0005 — Errores como RFC 7807 Problem Details

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.01

## Contexto

La especificación funcional documenta, para cada uno de los 29 módulos, sus "errores
esperables". Son parte del contrato: el frontend debe distinguir un dato inválido de un
permiso insuficiente, de un conflicto de estado, de una falla del servidor, y reaccionar
distinto en cada caso.

El plan pide "mensajes de error de dominio estables, accionables y desacoplados de detalles
internos", y prohíbe exponer datos sensibles en logs y respuestas.

Sin una convención fijada antes del primer módulo, cada uno inventa su formato: uno devuelve
`{"error": "..."}`, otro `{"message": "..."}`, un tercero un string plano. El frontend
termina con un `if` por endpoint, y el cliente TypeScript generado no puede tipar nada útil.

Hay además un riesgo de seguridad concreto: la respuesta por defecto de Spring puede incluir
stack traces, nombres de clase y mensajes de excepción. Sobre un sistema con datos clínicos,
eso le entrega a un atacante el mapa interno de la aplicación —y a veces, en el mensaje de
una excepción de base, datos reales.

## Decisión

**Todos los errores se devuelven como `ProblemDetail` (RFC 7807).**

`GlobalExceptionHandler` en `platform.api` centraliza la traducción. Cada módulo funcional
registra allí sus excepciones de dominio a medida que existan.

```json
{
  "type": "https://akine.app/problems/validation-error",
  "title": "Error de validacion",
  "status": 400,
  "detail": "La solicitud contiene campos invalidos",
  "instance": "/api/v1/pacientes",
  "errors": { "email": "debe ser una direccion valida" }
}
```

Reglas:

- **La respuesta nunca expone detalles internos**: ni stack traces, ni nombres de clase, ni
  SQL, ni rutas de archivo. El detalle completo va al log, correlacionado por el trace id.
- `type` es un URI estable que identifica la clase de problema. El frontend puede ramificar
  sobre él sin parsear texto.
- Los errores de validación incluyen `errors` con el detalle por campo: es información que
  el cliente necesita para pintar el formulario y no revela nada interno.
- Refuerzo en configuración: `server.error.include-stacktrace: never` y
  `include-message: never`.
- Un `@ExceptionHandler(Exception.class)` actúa de red de contención: **todo lo que llega
  ahí es un bug**, se loguea completo y se responde genérico.

## Alternativas consideradas

**Formato propio de error.** Total libertad de diseño. Descartada: RFC 7807 ya resolvió el
problema, tiene soporte nativo en Spring Framework 6+ (`ProblemDetail`), y openapi-generator
lo entiende. Inventar un formato propio significa documentarlo, defenderlo y explicárselo a
cada integrante nuevo, a cambio de nada.

**Solo el `ResponseEntityExceptionHandler` de Spring.** Cubre las excepciones del framework
sin escribir código. Descartada por insuficiente: no cubre las excepciones de dominio —que
son la mayoría en AKINE— y sus mensajes por defecto no están pensados para un usuario final.
Se usa como base, no como solución completa.

**Devolver el mensaje de la excepción en `detail`.** Muy cómodo para depurar. Descartada por
seguridad: los mensajes de excepción filtran nombres de tabla, fragmentos de SQL y, en el
peor caso, valores de datos reales. La comodidad de depuración se resuelve con el log, que
es el lugar correcto.

**Códigos de error numéricos propios (`AKINE-1234`).** Fáciles de buscar en documentación.
Descartada para el baseline: agrega un catálogo que hay que mantener sincronizado, y el
`type` como URI cumple la misma función siendo además autodescriptivo. Reconsiderable si
aparece una necesidad real de soporte.

## Consecuencias

### Positivas

- El frontend tiene **un** formato de error, y puede tratarlo en un interceptor en lugar
  de en cada llamada. Ya está implementado así en `error.interceptor.ts`.
- El contrato OpenAPI puede tipar las respuestas de error.
- Ningún endpoint puede filtrar internals: la regla está en un único lugar.
- Los errores de validación llegan por campo, lo que permite formularios accionables.

### Negativas

- Cada módulo debe registrar sus excepciones en el handler central, que crecerá y necesitará
  organizarse por módulo.
- Los mensajes genéricos hacen el diagnóstico más lento en desarrollo: hay que ir al log.
- El `type` como URI obliga a mantener un espacio de nombres coherente y a no reutilizarlos
  con otro significado.

### Qué obliga a hacer

- Toda excepción de dominio nueva se registra en `GlobalExceptionHandler` con su `type`,
  `title` y status.
- **Nunca** poner `exception.getMessage()` en `detail` de una excepción no controlada.
- El log lleva el detalle completo; la respuesta, lo accionable.
- Cada `type` nuevo se documenta bajo `https://akine.app/problems/`.
- Los E2E verifican que las respuestas de error no contengan `com.akine`,
  `org.springframework` ni `stacktrace`. Ver `e2e/smoke.spec.ts` en `appKine-web`.
