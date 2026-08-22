# ADR-0004 — Convenciones de persistencia multi-tenant

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.01

## Contexto

AKINE es un SaaS multi-tenant: la Organización es el tenant y contiene uno o más
Consultorios. La regla maestra 11 del documento de requerimientos exige que **todos los
módulos respeten multi-tenancy**, y el plan obliga a aplicar aislamiento "en almacenamiento,
consultas, comandos, cachés, archivos, reportes, eventos y procesos en segundo plano".

Los datos en juego son historia clínica y movimientos económicos. Una fuga entre tenants no
es un bug de severidad media: es exposición de datos de salud de pacientes de otra
organización.

Las decisiones de persistencia son de las más caras de revertir. Una vez que hay datos en
producción, cambiar el tipo de una columna de importe, la zona horaria de un timestamp o
la estrategia de borrado exige una migración con backfill sobre datos reales.

Conviene fijarlas **antes** de la primera tabla funcional, no descubrirlas en el módulo siete.

## Decisión

### Aislamiento por tenant

- **Toda tabla de negocio lleva `organization_id NOT NULL`.** Sin excepción.
- Lleva `consultorio_id` cuando el hecho pertenece a una sede específica.
- **Todo índice y todo `UNIQUE` incorpora el alcance del tenant.** Un `UNIQUE` sin
  `organization_id` es un bug de aislamiento, no una optimización pendiente: impide que dos
  organizaciones distintas usen el mismo código de práctica, y filtra por colisión la
  existencia de datos ajenos.

### Tipos

- **Importes: `DECIMAL` en la base y `BigDecimal` en Java.** Nunca `FLOAT`, `DOUBLE` ni
  `float`/`double`. El punto flotante binario no representa exactamente 0.1; el error se
  acumula y termina en una caja que no cierra.
- **Instantes: `DATETIME(6)`, siempre en UTC.** La zona local se aplica en el dominio con
  una zona IANA explícita (`America/Argentina/Cordoba`), nunca con la zona por defecto de
  la JVM ni del servidor.
- Identificadores sin significado de negocio.
- Charset `utf8mb4` con collation `utf8mb4_0900_ai_ci`. Nunca `utf8`, que en MySQL son tres
  bytes y rompe emojis y buena parte de Unicode.

### Ciclo de vida

- **Baja lógica siempre**: columnas `active` y `deleted_at`. Prohibido el `DELETE` físico
  sobre información histórica relevante (regla maestra 10).
- Vigencias y snapshots históricos donde el negocio los exige: coberturas, convenios y
  aranceles cambian con el tiempo y una sesión debe conservar los valores vigentes **al
  momento en que ocurrió**, no los actuales.

### Verificación

`CodingConventionsTest` verifica automáticamente la ausencia de punto flotante en el dominio
y de `java.util.Date`/`Calendar`. El resto se verifica en la revisión de cada migración y
en el QA contra la base.

## Alternativas consideradas

**Una base de datos por tenant.** El aislamiento más fuerte posible: es imposible que una
consulta cruce tenants. Descartada porque multiplica la operación —N bases que migrar,
respaldar y monitorear—, complica los reportes agregados de la plataforma, y encarece el
onboarding de cada organización nueva, que en un SaaS debe ser instantáneo. Es reconsiderable
si aparece una organización con requisitos regulatorios de aislamiento físico.

**Un esquema por tenant en la misma instancia.** Punto intermedio. Descartada por razones
parecidas: Flyway tendría que aplicar cada migración N veces, y una migración fallida a
mitad de camino deja tenants en versiones distintas del esquema.

**Row-level security de MySQL.** MySQL 8.4 no ofrece RLS real como PostgreSQL; se emula con
vistas y `DEFINER`, lo que agrega complejidad sin la garantía. Descartada.

**Discriminador solo en la capa de aplicación, sin columna.** Descartada de plano: sin la
columna no hay forma de auditar a quién pertenece una fila, ni de detectar una fuga
consultando la base.

**Borrado físico con tabla de auditoría aparte.** Descartada: duplica el modelo, y la regla
maestra 10 exige conservar la información histórica relevante, no una copia de ella.

## Consecuencias

### Positivas

- El aislamiento es verificable **consultando la base**, que es exactamente lo que hace el
  protocolo de QA.
- Los índices con alcance tenant son además los correctos para el rendimiento: casi toda
  consulta filtra por organización.
- `BigDecimal` y UTC eliminan dos clases enteras de bug que en sistemas económicos y de
  agenda aparecen tarde y son caras.
- La baja lógica preserva la trazabilidad clínica que el dominio exige.

### Negativas

- `organization_id` en cada tabla es repetitivo y hay que recordarlo en cada migración.
- La baja lógica obliga a filtrar por `active` en **toda** consulta. Olvidarlo muestra datos
  dados de baja, y es un error fácil de cometer.
- Los datos nunca se borran: la base solo crece. Requiere una estrategia de archivado a
  futuro.
- `BigDecimal` es más verboso que `double` y exige cuidado con `equals` frente a
  `compareTo` (`2.0` y `2.00` no son `equals`).
- Trabajar en UTC obliga a convertir en cada punto de presentación.

### Qué obliga a hacer

- Revisar cada migración contra esta lista **antes** de commitearla.
- Todo `UNIQUE` e índice nuevo incluye `organization_id`.
- Todo QA que toque persistencia verifica el aislamiento con un token de una segunda
  organización. Esperado: `403` o `404`, **nunca `200`**.
- Al comparar importes, usar `compareTo`, no `equals`.
- Al validar fechas contra la base durante el QA, comparar en UTC, no en hora local.
