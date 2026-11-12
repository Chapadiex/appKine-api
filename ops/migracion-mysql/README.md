# Migración MySQL → PostgreSQL (11/11/2026)

Herramientas **históricas** de la migración. No forman parte de la aplicación: Spring no las
carga, Maven no las empaqueta y el runtime de AKINE no tiene driver de MySQL. Se conservan para
repetir la migración en otro entorno y para auditar cómo se hizo.

El diseño, las decisiones y los resultados medidos están en
[`docs/diseno/AKINE-migracion-postgres.md`](../../docs/diseno/AKINE-migracion-postgres.md).

| Archivo | Qué es |
|---|---|
| `mysql-historico/` | Las 145 migraciones Flyway de MySQL (V1–V145, con huecos). **No se ejecutan más**: el esquema vigente es el baseline de `src/main/resources/db/migration`. |
| `generar-baseline.mjs` | Generó V2–V13 del baseline PostgreSQL desde el esquema final de MySQL y los tipos que espera Hibernate. |
| `entradas/` | Entradas del generador: `information_schema` de MySQL, DDL de Hibernate para PostgreSQL, tabla→módulo y semillas. |
| `auditar-sql.mjs` | Inventario del SQL escrito a mano en el código Java, con las marcas de lo que era MySQL. |
| `portar-sql-tests.mjs` | Portó el SQL de los tests de integración (fechas, intervalos, booleanos, `INSERT IGNORE`). |
| `TransferirDatos.java` | Copia los datos de una base MySQL a una PostgreSQL ya migrada y escribe el reporte de conciliación. |

## Procedimiento

Los pasos A a E son los del plan de migración. Las credenciales salen del entorno; las de abajo son
las del entorno local de desarrollo (`compose.yaml`), con datos sintéticos.

### A. Respaldo de MySQL (antes de tocar nada)

```bash
mkdir -p respaldos/mysql-$(date +%F)
for db in akine_local akine_rag; do
  docker exec akine-mysql sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysqldump -uroot --single-transaction \
    --routines --triggers --events --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=OFF $db" \
    > respaldos/mysql-$(date +%F)/$db.sql
done
sha256sum respaldos/mysql-$(date +%F)/*.sql > respaldos/mysql-$(date +%F)/SHA256SUMS
```

**Verificar que el respaldo restaura**: cargarlo en una base descartable y comparar filas por tabla
con la original (así se hizo el 11/11: 123 tablas y 10.512 filas en `akine_local`, 126 y 1.321 en
`akine_rag`, sin diferencias). La base MySQL original **no se modifica ni se borra**.

Restaurar un respaldo:

```bash
docker exec akine-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE akine_local"'
docker exec -i akine-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot akine_local' < respaldos/<fecha>/akine_local.sql
```

### B. Base nueva

```bash
docker compose up -d postgres          # pgvector/pgvector:0.8.1-pg18-trixie, crea el rol y la base
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # Flyway aplica V1..V13 y Hibernate valida
```

Para otra base del mismo servidor (por ejemplo `akine_rag`), crearla como superusuario con la
extensión y correr Flyway apuntando a ella:

```bash
docker exec akine-postgres psql -U postgres -c "CREATE DATABASE akine_rag OWNER akine"
docker exec akine-postgres psql -U postgres -d akine_rag -c "CREATE EXTENSION vector"
AKINE_DB_URL=jdbc:postgresql://localhost:5432/akine_rag ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### C. Transferencia y conciliación

Con la aplicación **detenida** sobre la base destino (la herramienta la vacía antes de copiar):

```bash
M2=~/.m2/repository
CP="$M2/com/mysql/mysql-connector-j/9.6.0/mysql-connector-j-9.6.0.jar:$M2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar"
MYSQL_USER=root MYSQL_PWD=... PG_USER=postgres PG_PWD=... \
  java -cp "$CP" ops/migracion-mysql/TransferirDatos.java \
       jdbc:mysql://localhost:3306/akine_local jdbc:postgresql://localhost:5432/akine_local \
       docs/migracion/conciliacion-akine_local.md
```

(En Windows el separador del classpath es `;`.) La herramienta sale con código 1 si alguna tabla
difiere en filas, en una suma de control o si queda algún huérfano. El reporte lista, por tabla,
filas en origen y destino y el resultado de las sumas de control: nulos, suma de ids e importes,
instantes en microsegundos, fechas en días, booleanos verdaderos, largos de texto y distribución de
`estado`.

Los archivos (adjuntos y PDF de la biblioteca) **no se mueven**: viven en el disco configurado por
`akine.*.base-dir` y la base guarda sólo su clave. Verificarlos con `ops/verificar-adjuntos.sh`.

### D. Aplicación

`AKINE_DB_URL`, `AKINE_DB_USER` y `AKINE_DB_PASSWORD` apuntan a PostgreSQL. Smoke test y QA
manual según `docs/qa/`.

### E. Corte definitivo (producción, cuando exista)

1. Detener las escrituras en MySQL (bajar la aplicación).
2. Respaldo final de MySQL (paso A) y verificación.
3. Transferencia final (paso C) contra la base PostgreSQL de producción ya migrada por Flyway.
4. Conciliación sin diferencias. Si hay diferencias, **no se sigue**: se explican y se corrigen.
5. Arrancar AKINE contra PostgreSQL y correr el smoke test.
6. Conservar MySQL y su respaldo como contingencia hasta aceptar la migración.

No hay escritura doble: entre el paso 1 y el 5 la aplicación está detenida.

## Rollback

Mientras la migración no esté aceptada, volver a MySQL es volver a la versión anterior de la
aplicación con su base intacta:

1. Bajar AKINE.
2. Desplegar la imagen anterior a la migración (la última de `main` antes del merge de
   `akine-postgres`), que trae el driver de MySQL y sus 145 migraciones.
3. Apuntar `AKINE_DB_URL` a la base MySQL, que no se tocó. Si se hubiera perdido, restaurarla del
   respaldo del paso A.
4. Lo escrito en PostgreSQL después del corte **no vuelve solo**: si hubo operación real sobre
   PostgreSQL, se pierde salvo que se copie a mano. Por eso el rollback tiene sentido sólo en la
   ventana inmediata después del corte.
