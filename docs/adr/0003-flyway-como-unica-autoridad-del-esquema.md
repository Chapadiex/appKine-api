# ADR-0003 — Flyway como única autoridad del esquema

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.01

## Contexto

El plan aprueba Flyway sobre MySQL 8.4 LTS y exige que las tablas funcionales se creen
únicamente en la etapa que las aprueba. También prohíbe los drops destructivos en etapas
funcionales y exige que la información histórica relevante no se elimine físicamente.

Spring Boot ofrece `spring.jpa.hibernate.ddl-auto`, que puede crear y alterar el esquema
a partir de las entities. Combinar ambos mecanismos produce un modo de falla difícil de
diagnosticar: el esquema local diverge del que producen las migraciones, los tests pasan
contra un esquema que nunca existió en producción, y el problema aparece en el primer
deploy real.

AKINE almacena historia clínica y movimientos económicos. Una migración mal aplicada no es
un inconveniente de desarrollo: es pérdida de datos clínicos o descuadre de caja.

## Decisión

**Flyway es la única autoridad sobre el esquema.** Hibernate lo valida, nunca lo modifica.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    baseline-on-migrate: false
    validate-on-migrate: true
```

Consecuencia operativa directa: **si falta una tabla o una columna, falta una migración.**
Nunca se crea nada a mano en la base.

Reglas asociadas:

- Las migraciones viven en `src/main/resources/db/migration` con nomenclatura
  `V<n>__<descripcion_en_snake_case>.sql`.
- **Una migración aplicada no se edita jamás.** Se corrige con una nueva. Editarla rompe
  el checksum de Flyway y deja la base en un estado que no se puede reproducir.
- `baseline-on-migrate: false` a propósito: si Flyway encuentra una base con tablas y sin
  historial, debe fallar en lugar de asumir que el estado previo era correcto.
- `validate-on-migrate: true`: la aplicación no arranca contra una base cuyo historial no
  coincide con las migraciones del artefacto.
- Los tests de integración corren sobre **Testcontainers con la misma versión de MySQL**
  que producción (8.4, imagen fijada), y aplican las migraciones desde una base vacía.

## Alternativas consideradas

**`ddl-auto: update`.** Es lo más cómodo en desarrollo: se toca la entity y la tabla se
ajusta sola. Descartada sin dudarlo. Genera un esquema que nadie revisó, no versiona nada,
no sabe borrar ni renombrar, y produce diferencias silenciosas entre máquinas. En un sistema
con datos clínicos es directamente inaceptable.

**`ddl-auto: none`.** Evita que Hibernate toque el esquema, igual que `validate`, pero
tampoco verifica nada. Descartada porque `validate` es gratis y detecta al arrancar una
clase entera de bugs: la entity dice una cosa y la tabla otra. Es la red de seguridad que
convierte un error de runtime en un error de arranque.

**Liquibase.** Más expresivo, con changelogs en XML/YAML y rollback declarativo. Descartada
porque el plan aprobó Flyway, porque SQL plano es más directo de revisar en un PR que un
changelog XML, y porque el rollback automático de Liquibase da una falsa sensación de
seguridad: en la práctica un rollback de datos reales casi nunca es automático.

**Hibernate genera y Flyway versiona el resultado.** Un híbrido tentador. Descartado porque
el SQL generado por Hibernate no está pensado para ser leído ni revisado, y porque la
decisión de índices —crítica en un sistema multi-tenant, donde todo índice debe incluir el
alcance del tenant— no puede delegarse a un generador.

## Consecuencias

### Positivas

- El esquema es **reproducible**: cualquier clon llega exactamente al mismo estado.
- Cada cambio de esquema pasa por revisión en un PR, como SQL legible.
- `validate` detecta al arrancar la divergencia entre entities y tablas.
- Los tests corren contra el esquema real, producido por las mismas migraciones que
  producción.

### Negativas

- Más fricción en desarrollo: agregar un campo exige escribir la migración a mano.
- Un error en una migración ya aplicada solo se corrige con otra migración: no hay
  "deshacer".
- Los ITs necesitan Docker.
- Requiere disciplina con los checksums: editar un archivo aplicado rompe entornos ajenos.

### Qué obliga a hacer

- Todo cambio de modelo trae su migración en el mismo commit.
- Toda migración respeta las convenciones fijadas en `V1__baseline_tecnico.sql`: `utf8mb4`,
  `organization_id` obligatorio en tablas de negocio, alcance tenant en índices y uniques,
  `DECIMAL` para importes, `DATETIME(6)` en UTC, baja lógica con `active`/`deleted_at`.
- Los cambios de esquema siguen expandir–migrar–contraer (ver [ADR-0007](0007-expandir-migrar-contraer.md)).
- Ante un fallo de Flyway al arrancar, **diagnosticar la causa**. Nunca "arreglarlo" con
  `baseline-on-migrate` ni tocando `flyway_schema_history` a mano.
