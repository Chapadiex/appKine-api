# ADR-0007 — Expandir–migrar–contraer para todo cambio de esquema

- **Estado:** Aceptado
- **Fecha:** 2026-08-22
- **Etapa:** AKINE-00.02

## Contexto

El plan exige aplicar el patrón expandir–migrar–contraer para los cambios de datos y prohíbe
los drops destructivos en etapas funcionales. Toda migración debe contemplar "compatibilidad
temporal, datos históricos, backfill, restricciones, índices, reversibilidad práctica y
despliegue seguro".

[ADR-0003](0003-flyway-como-unica-autoridad-del-esquema.md) fijó que Flyway es la única
autoridad del esquema, pero no cómo se estructura un cambio incompatible.

El problema que esto resuelve: durante un despliegue existe una ventana —minutos, o más si
hay rollback— en la que **la versión vieja y la nueva de la aplicación corren simultáneamente
contra la misma base**. Una migración que renombra o borra una columna rompe la versión vieja
en el instante en que se aplica, y deja el rollback sin salida: volver al artefacto anterior
no arregla nada, porque la columna ya no existe.

Sobre datos clínicos y económicos, eso no es una caída: es corrupción o pérdida.

## Decisión

**Todo cambio de esquema que no sea puramente aditivo se descompone en tres migraciones
separadas, desplegadas en releases distintos.**

### 1. Expandir — aditivo, compatible hacia atrás

Agregar lo nuevo sin tocar lo viejo. La columna nueva es **nullable o con default**; el
código nuevo escribe en ambas y lee de la vieja.

```sql
-- V12__expandir_paciente_documento.sql
ALTER TABLE paciente ADD COLUMN documento_tipo VARCHAR(16) NULL;
ALTER TABLE paciente ADD COLUMN documento_numero VARCHAR(32) NULL;
```

Ambas versiones de la aplicación funcionan. El rollback es trivial.

### 2. Migrar — backfill y cambio de lectura

Backfill de los datos históricos y, una vez completo, el código pasa a leer de la columna
nueva y sigue escribiendo en ambas.

```sql
-- V13__backfill_paciente_documento.sql
UPDATE paciente
   SET documento_tipo = 'DNI',
       documento_numero = dni
 WHERE documento_numero IS NULL
   AND dni IS NOT NULL;
```

Sobre tablas grandes, el backfill va **por lotes**, no en una única sentencia: un `UPDATE`
de millones de filas bloquea la tabla y agota el undo log.

### 3. Contraer — destructivo, en un release posterior

Solo cuando se verificó que ninguna versión desplegada usa lo viejo:

```sql
-- V20__contraer_paciente_dni.sql
ALTER TABLE paciente MODIFY COLUMN documento_numero VARCHAR(32) NOT NULL;
ALTER TABLE paciente DROP COLUMN dni;
```

### Reglas asociadas

- **Nunca `RENAME COLUMN`.** Es expandir + migrar + contraer disfrazado de operación única,
  y rompe la versión vieja al instante.
- **Nunca agregar `NOT NULL` sin default en una tabla con datos.** Va en la fase de contraer,
  después del backfill.
- **Nunca `DROP` en la misma release que introduce el reemplazo.**
- La fase de contraer referencia en un comentario la migración que la habilitó, y la release
  desde la cual el código dejó de usar la columna vieja.
- La baja lógica ([ADR-0004](0004-convenciones-de-persistencia-multi-tenant.md)) es
  independiente de esto: `DELETE` físico de información histórica está prohibido siempre,
  incluso en la fase de contraer.
- **Índices sobre tablas grandes:** MySQL 8.4 los crea online, pero conviene medir. Todo
  índice incluye el alcance del tenant.

## Alternativas consideradas

**Migración directa con ventana de mantenimiento.** Una sola migración, la aplicación
detenida mientras corre. Mucho más simple de escribir. Descartada: AKINE es una plataforma
"disponible 24 horas" según su propia definición funcional, y una ventana de mantenimiento
sobre un sistema de turnos médicos significa consultorios que no pueden operar. Además no
resuelve el rollback: si la release nueva falla después de la ventana, volver atrás sigue
sin ser posible.

**Migraciones con rollback declarativo (Liquibase).** Descartada junto con Liquibase en
[ADR-0003](0003-flyway-como-unica-autoridad-del-esquema.md). Un rollback declarativo da una
falsa sensación de seguridad: puede deshacer el DDL, no los datos que se perdieron.

**Versionar la API en lugar del esquema.** Mantener endpoints v1 y v2 sobre el mismo modelo.
Descartada porque no ataca el problema: el conflicto no está en el contrato HTTP sino en que
dos versiones del código comparten una base física.

**Aplicar solo a tablas grandes.** Tentador: el patrón parece exagerado para una tabla de
catálogo con 20 filas. Descartada porque el riesgo real no es el volumen sino la
**compatibilidad durante el despliegue**, que es idéntica con 20 filas o con 20 millones.
Y una excepción "para tablas chicas" obliga a discutir en cada PR si esta tabla califica.

## Consecuencias

### Positivas

- El despliegue nunca rompe la versión que todavía está corriendo.
- El rollback es siempre posible: ninguna release deja la base en un estado que la anterior
  no entienda.
- El backfill es observable y reintentable, en lugar de un paso atómico que falla entero.
- Los `DROP` ocurren sobre columnas que se verificó que nadie usa.

### Negativas

- Un cambio conceptualmente simple se convierte en **tres migraciones y dos releases**.
- Durante la transición hay columnas duplicadas: el modelo es temporalmente más confuso y
  el código escribe dos veces.
- La fase de contraer se pospone y se olvida. Queda deuda de columnas muertas si nadie la
  sigue.
- Exige disciplina de release que un equipo chico puede sentir como burocracia.

### Qué obliga a hacer

- Antes de escribir una migración, preguntarse: **¿la versión anterior de la aplicación
  sigue funcionando después de aplicar esto?** Si la respuesta es no, hay que descomponerla.
- Toda migración de contraer registra en un comentario qué release la habilitó.
- El backfill sobre tablas grandes va por lotes.
- Las fases de contraer pendientes se anotan como deuda técnica con etapa destino en el plan
  de implementación. **Una contracción olvidada es deuda, no una decisión.**
