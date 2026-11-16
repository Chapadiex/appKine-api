# Asistente de datos: consultas libres en SQL

Decisión del usuario del 15/11/2026: el asistente tiene que poder responder sobre toda la base, no sólo
sobre las seis intenciones del catálogo. Se eligió **intenciones + SQL de respaldo**: las intenciones
siguen siendo el camino principal (respuestas exactas, con los permisos de cada módulo) y, cuando la
pregunta no entra en ninguna, el modelo escribe un `SELECT`.

## 1. Flujo

1. El interpretador clasifica la pregunta como siempre.
2. Va al SQL si: la intención es `FUERA_DE_ALCANCE` y la pregunta no pide crear, borrar ni cambiar nada
   (`pideCambio`), **o** la intención es `REPORTE` pero la pregunta pide un detalle que ningún reporte da
   (ranking, "quién", listado, pacientes: `pideDetalle`).
3. Además hacen falta el interruptor `akine.asistente.sql.enabled` (apagado por defecto) y el permiso
   `asistente:sql` en la sede (por base sólo `ORG_ADMIN` y `CONSULTORIO_ADMIN`).
4. `ConsultaLibre` le manda al modelo el esquema de las vistas (`prompt-sql.txt`) y la pregunta. El
   modelo devuelve `{"sql": "..."}`.
5. `ValidadorDeSql` decide si se puede ejecutar. Si el motor la rechaza, se le devuelve el error al modelo
   **una** vez para que la corrija.
6. `JdbcLectorDeVistas` la ejecuta y la plantilla arma el texto. La respuesta trae el `SELECT` en
   `consulta` (contrato 0.173.0), y la pantalla lo muestra en "Ver la consulta".

## 2. Las tres barreras

| Barrera | Qué frena |
|---|---|
| Vistas de `asistente_sql` (`V21`) | Sólo columnas no sensibles: sin texto clínico, documentos, correos ni teléfonos. Cada vista se recorta a la organización y, si el dato es de una sede, a la sede activa, con dos parámetros que fija el ejecutor. Sin parámetros, no devuelven filas |
| `ValidadorDeSql` | Una sola sentencia, que empiece con `SELECT` o `WITH`; sin palabras que escriban, bloqueen o cambien la sesión; sólo las vistas o sus propios `WITH`; sin comillas dobles, `$` ni comentarios; sólo funciones de la lista (`set_config`, `current_setting` o `pg_read_file` no escriben, así que READ ONLY no las frena) |
| `JdbcLectorDeVistas` | Transacción propia `READ ONLY` en `READ_COMMITTED`, `statement_timeout`, `search_path` en `asistente_sql` y `set_config(..., true)`, que muere con la transacción y no queda en la conexión del pool. La consulta va envuelta con su `LIMIT` |

El modelo ve el esquema y la pregunta, **nunca filas**. El texto lo arma la plantilla.

## 3. Design challenge

1. **Ownership:** las vistas son un modelo de lectura del módulo `assistant` y leen tablas de otros
   módulos. ArchUnit no lo ve porque es SQL; se acepta porque es solo lectura, está en una sola migración
   y la lista de vistas vive junto al validador. Si una tabla cambia, la vista se rompe en la migración
   que la cambia, no en producción.
2. **Ciclos:** no hay dependencias Java nuevas entre módulos.
3. **Tenant:** cada vista filtra por `organization_id = asistente_sql.org()`, y las de sede además por
   `consultorio_id = asistente_sql.sede()`. Los dos valores los fija el ejecutor desde el actor, no desde
   la pregunta. `set_config` está fuera de la lista de funciones.
4. **Reglas maestras:** el prompt explica turno ≠ sesión y deuda ≠ cobro ≠ caja; las plantillas no suman
   conceptos económicos.
5. **Baja lógica:** las vistas excluyen lo borrado salvo en turnos y cobros, donde el estado lo dice
   (cancelado, anulado).
6. **Contrato:** aditivo, `RespuestaAsistenteResponse.consulta` opcional. 0.173.0 porque 0.172.0 está
   tomado por otra rama.
7. **Ruta crítica:** se apoya en el asistente que ya está en `main`.
8. **El caso que rompe el diseño:** el profesional. Sus permisos están recortados a su actividad y las
   vistas no lo saben. Por eso `asistente:sql` es sólo de los dos roles de administración, que ya ven
   todo de la sede.

## 4. Lo que no hace

- No reemplaza a las intenciones: una pregunta que entra en el catálogo sigue yendo por su herramienta.
- No escribe. Nada que el modelo devuelva puede modificar datos.
- No es exacto como una intención: un modelo de 4B puede escribir un `SELECT` válido que responde otra
  cosa. Por eso la respuesta muestra la consulta.

## 5. Medición (15/11)

| Set | Antes | Con el respaldo |
|---|---|---|
| `preguntas.json` (45) | 45/45 | 45/45 (prompt del interpretador sin cambios) |
| `preguntas-control.json` (20) | 20/20 | 20/20 |
| `preguntas-limpias.json` (25) | 24/25 (LF3 es la inyección, que el backend neutraliza) | igual |
| `preguntas-sql.json` (10) | — | 10/10 de intención; las tres que el modelo da como `REPORTE` las desvía `pideDetalle` |

Contra la base `akine_asistente_main` (copia de `akine_qa`): "¿cuántos pacientes tenemos?" → 22;
"listame los pacientes mayores de 60" → 7 filas; "el padrón?" → 33 personas; casos abiertos, servicios y
precios, deudas y ranking de profesionales con su `SELECT` correcto. Entre 2 y 4 s por respuesta.

## 6. Problemas y decisiones

- **JSqlParser no se puede usar.** Con la dependencia en el classpath, Spring Data JPA 4 la detecta y la
  usa para analizar **todas** las consultas nativas del proyecto, sin propiedad que lo apague
  (`QueryEnhancerFactories.jSqlParserPresent`), y no arranca por una de `PersonaRepository`. El validador
  se hizo sin parser; la seguridad no depende de él solo (ver §2).
- **El prompt del interpretador no se tocó.** Tres intentos de enseñarle que "¿quién nos debe?" o "¿qué
  profesional tiene más turnos?" no son `REPORTE` arreglaron esas y rompieron entre 2 y 5 preguntas
  medidas. La regla quedó en el backend (`pideDetalle`), que es determinista.
- **Pedidos de cambio:** "borrá todos los turnos de hoy" llegaba al SQL y el modelo contaba turnos. Ahora
  `pideCambio` lo frena antes, y el prompt de SQL devuelve `{"sql": null}` en esos casos.
