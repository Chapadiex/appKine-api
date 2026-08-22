# ADR-0013 — El prepago es una política configurable, no una condición del dominio clínico

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.03

## Contexto

El flujo histórico cobra al paciente particular **antes** de mandarlo a la sala de espera:
sin pago no hay atención. La especificación moderna dice otra cosa: RN-M14-005 establece
que el cierre clínico no depende del cobro, y M18 genera la obligación de pago **al
concretarse la prestación** — es decir, después de atender, no antes.

Las dos fuentes describen realidades que existen a la vez:

- Muchos consultorios reales cobran al particular al llegar; negarlo es negar la
  operatoria.
- Pero convertir el cobro en precondición del dominio clínico rompe el resto del sistema:
  el paciente con cobertura no paga en el mostrador (el financiador paga meses después),
  y una sesión que no puede cerrarse "porque falta plata" bloquea el registro clínico por
  un motivo administrativo.
- Además, el dinero cobrado antes de atender es un problema contable genuino: todavía no
  existe la obligación que ese dinero salda. ¿Qué es ese cobro?

El impacto declarado —prepago, devoluciones, no-show y caja— define el corazón del módulo
económico. Había que resolverlo antes de AKINE-07.01.

## Decisión

**El prepago es una política configurable por Consultorio y por Oferta, nunca una
condición global del dominio clínico.**

- Una **Sesión puede iniciarse, documentarse y cerrarse con independencia del pago**. El
  registro clínico jamás queda rehén de la caja.
- El dinero recibido **antes de que exista una obligación** se registra como
  **anticipo/saldo a favor**, mediante un ledger trazable y un movimiento real de Caja.
  No es todavía el pago "de" nada: es dinero del paciente en custodia contable.
- Cuando la prestación se concreta y nace la obligación (M18), el anticipo **se imputa** a
  una obligación compatible.
- Cancelaciones, no-show y devoluciones aplican **reglas explícitas** que operan mediante
  movimientos nuevos: **el movimiento original nunca se borra**.

El consultorio que exige prepago lo sigue exigiendo — como política operativa suya, que el
sistema soporta y registra — pero el modelo de dominio no se la impone a todos.

## Alternativas consideradas

**Cobro obligatorio antes de la atención (flujo histórico), como regla del dominio.**
Descartada porque solo describe al paciente particular de un consultorio con esa
costumbre: no cubre coberturas, copagos diferidos ni cortesías, y ata el estado clínico al
estado de caja — exactamente lo que RN-M14-005 prohíbe. Como regla global obligaría a
"trampear" el sistema en cada excepción, y un sistema que se trampea a diario deja de ser
registro confiable.

**Ignorar el prepago: siempre facturar después de atender.** Simetría perfecta con M18.
Descartada porque el dinero anticipado existe: si el sistema no lo modela, recepción lo
anota "en un cuaderno" y la caja del sistema deja de cerrar contra la caja real. La
operatoria histórica es evidencia de una necesidad, aunque su modelado histórico fuera
incorrecto.

**Modelar el prepago como una obligación creada anticipadamente.** Reutiliza el flujo de
pago normal. Descartada porque inventa una deuda que no existe: si el paciente falta, no
debía nada — y habría que "anular la obligación y el pago" en lugar de simplemente tener
un saldo a favor sin imputar. El anticipo como figura propia mantiene la contabilidad
honesta.

## Consecuencias

### Positivas

- El circuito clínico y el económico quedan desacoplados: cada consultorio elige su
  política sin que el dominio cambie.
- Todo peso que entra queda trazado desde el minuto cero — anticipo, imputación,
  devolución — con movimiento de Caja real; la caja cierra contra la realidad.
- No-show y cancelaciones tienen una base contable limpia: reglas sobre saldos, no
  borrados retroactivos.

### Negativas

- Un ledger con anticipos, imputaciones y devoluciones es sustancialmente más complejo que
  "pago = atención cobrada": más entidades, más invariantes, más conciliación.
- El saldo a favor introduce obligaciones operativas nuevas: devolver dinero, arrastrar
  saldos, explicárselo al paciente.
- La política configurable por Consultorio y Oferta es superficie de configuración que
  alguien debe administrar y que el frontend debe reflejar correctamente.

### Qué obliga a hacer

- `billing` es propietario del ledger: anticipos, obligaciones, imputaciones, movimientos
  de Caja y devoluciones. Importes en `DECIMAL`/`BigDecimal` y sin borrado físico, según
  [ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md).
- `clinical` cierra sesiones sin consultar estado de pago; la obligación nace por evento
  de prestación concretada, consumido por `billing` vía `spi` o eventos
  ([ADR-0001](0001-monolito-modular-con-paquete-spi.md)).
- La política de prepago se define en la configuración de Consultorio (`organization`) y
  de Oferta (M27) y se evalúa en recepción (`scheduling`), no en el dominio clínico.
- Etapas: AKINE-07.01 (obligaciones), 07.02 (cobros y anticipos), 07.03 (caja); reglas
  extendidas de no-show y abonos en 08.03 y 08.06–08.08.
