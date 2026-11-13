import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Transferencia de datos MySQL -> PostgreSQL de AKINE (migracion del 11/11/2026).
 *
 * <p>Herramienta historica, separada de la aplicacion: no se empaqueta ni la carga Spring. Se corre
 * con el launcher de un solo archivo de Java 21 y los dos drivers en el classpath (ver README.md):
 *
 * <pre>
 *   java -cp mysql-connector-j.jar:postgresql.jar TransferirDatos.java \
 *        jdbc:mysql://localhost:3306/akine_local jdbc:postgresql://localhost:5432/akine_local reporte.md
 * </pre>
 *
 * Credenciales por entorno: {@code MYSQL_USER}/{@code MYSQL_PWD} (lectura) y {@code PG_USER}/
 * {@code PG_PWD} (debe ser superusuario para {@code session_replication_role}).
 *
 * <p>Que hace, en orden:
 * <ol>
 *   <li>Exige que el destino ya tenga el esquema de Flyway (baseline V1..V13) y lo vacia, salvo
 *       {@code flyway_schema_history}: el origen es la fuente de verdad, incluidas las filas que
 *       sembro V13 (la cuenta de plataforma, por ejemplo, ya puede tener otro correo).</li>
 *   <li>Con {@code session_replication_role = replica} —las FK y los triggers no corren durante la
 *       carga, los CHECK, NOT NULL y UNIQUE si— copia tabla por tabla las columnas que existen en
 *       los dos lados y no son generadas. Los identificadores se copian tal cual.</li>
 *   <li>Convierte por el tipo del DESTINO: {@code tinyint(1)} a boolean, DATETIME (que la aplicacion
 *       escribia en UTC) a timestamptz UTC, JSON a jsonb, el BLOB float32 del vector a
 *       {@code vector(768)} con los mismos valores, DECIMAL tal cual (BigDecimal, sin floats).</li>
 *   <li>Lleva cada secuencia IDENTITY al maximo id copiado.</li>
 *   <li>Verifica cada clave foranea contando huerfanos, y escribe el reporte de conciliacion:
 *       filas por tabla en origen y destino, sumas de control de ids y de importes, y nulos.</li>
 * </ol>
 * No modifica el origen: lo lee en una transaccion de solo lectura con foto consistente.
 */
public class TransferirDatos {

	private static final int LOTE = 1000;

	public static void main(String[] args) throws Exception {
		if (args.length != 3) {
			System.err.println("uso: TransferirDatos <jdbc-mysql> <jdbc-postgres> <reporte.md>");
			System.exit(2);
		}
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		String urlMysql = args[0] + (args[0].contains("?") ? "&" : "?")
				+ "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&zeroDateTimeBehavior=EXCEPTION";
		try (Connection origen = DriverManager.getConnection(urlMysql, env("MYSQL_USER"), env("MYSQL_PWD"));
				Connection destino = DriverManager.getConnection(args[1], env("PG_USER"), env("PG_PWD"))) {
			origen.setReadOnly(true);
			origen.setAutoCommit(false);
			origen.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			try (Statement s = origen.createStatement()) {
				s.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
			}
			destino.setAutoCommit(false);
			new TransferirDatos(origen, destino, Path.of(args[2])).correr(args[0], args[1]);
		}
	}

	private static String env(String nombre) {
		String v = System.getenv(nombre);
		if (v == null) {
			throw new IllegalStateException("Falta la variable de entorno " + nombre);
		}
		return v;
	}

	private record Columna(String nombre, String tipo, boolean generada, boolean identidad) {
	}

	private record Resultado(String tabla, long origen, long destino, String control, String nota) {
	}

	private final Connection origen;
	private final Connection destino;
	private final Path reporte;
	private final List<Resultado> resultados = new ArrayList<>();
	private final List<String> avisos = new ArrayList<>();

	TransferirDatos(Connection origen, Connection destino, Path reporte) {
		this.origen = origen;
		this.destino = destino;
		this.reporte = reporte;
	}

	void correr(String urlOrigen, String urlDestino) throws Exception {
		Map<String, Map<String, Columna>> tablasDestino = tablasDestino();
		Map<String, Map<String, String>> tablasOrigen = tablasOrigen();
		long inicio = System.nanoTime();

		try (Statement s = destino.createStatement()) {
			s.execute("SET session_replication_role = replica");
			s.execute("SET timezone = 'UTC'");
			s.execute("TRUNCATE " + String.join(", ", tablasDestino.keySet()) + " RESTART IDENTITY");
		}
		for (String tabla : tablasDestino.keySet()) {
			Map<String, String> colsOrigen = tablasOrigen.get(tabla);
			if (colsOrigen == null) {
				resultados.add(new Resultado(tabla, 0, 0, "-", "no existe en el origen (tabla nueva): queda vacia"));
				continue;
			}
			copiar(tabla, colsOrigen, tablasDestino.get(tabla));
		}
		for (String tabla : tablasOrigen.keySet()) {
			if (!tablasDestino.containsKey(tabla) && !tabla.equals("flyway_schema_history")) {
				avisos.add("La tabla `" + tabla + "` del origen no existe en el destino: no se copio.");
			}
		}
		try (Statement s = destino.createStatement()) {
			s.execute("SET session_replication_role = origin");
		}
		reiniciarSecuencias(tablasDestino);
		List<String> huerfanos = verificarClavesForaneas();
		destino.commit();
		origen.rollback();
		escribirReporte(urlOrigen, urlDestino, huerfanos, (System.nanoTime() - inicio) / 1_000_000);
		System.out.println("Transferencia terminada: " + resultados.size() + " tablas, reporte en " + reporte);
		if (!huerfanos.isEmpty() || resultados.stream().anyMatch(r -> r.origen != r.destino || r.control.startsWith("DIFIERE"))) {
			System.out.println("HAY DIFERENCIAS: ver el reporte.");
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------ metadatos

	private Map<String, Map<String, Columna>> tablasDestino() throws SQLException {
		Map<String, Map<String, Columna>> r = new TreeMap<>();
		String sql = """
				SELECT c.table_name, c.column_name, c.udt_name, c.is_generated, c.is_identity
				  FROM information_schema.columns c
				  JOIN information_schema.tables t
				    ON t.table_schema = c.table_schema AND t.table_name = c.table_name
				 WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
				   AND c.table_name <> 'flyway_schema_history'
				 ORDER BY c.table_name, c.ordinal_position""";
		try (Statement s = destino.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				r.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>()).put(rs.getString(2),
						new Columna(rs.getString(2), rs.getString(3), "ALWAYS".equals(rs.getString(4)),
								"YES".equals(rs.getString(5))));
			}
		}
		if (r.isEmpty()) {
			throw new IllegalStateException("El destino no tiene tablas: corre Flyway primero");
		}
		return r;
	}

	private Map<String, Map<String, String>> tablasOrigen() throws SQLException {
		Map<String, Map<String, String>> r = new TreeMap<>();
		String sql = """
				SELECT table_name, column_name, column_type
				  FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND extra NOT LIKE '%GENERATED%'
				   AND table_name IN (SELECT table_name FROM information_schema.tables
				                       WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE')
				 ORDER BY table_name, ordinal_position""";
		try (Statement s = origen.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				r.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getString(3));
			}
		}
		return r;
	}

	// ------------------------------------------------------------------ copia

	private void copiar(String tabla, Map<String, String> colsOrigen, Map<String, Columna> colsDestino)
			throws SQLException {
		List<Columna> comunes = new ArrayList<>();
		for (Columna c : colsDestino.values()) {
			if (!c.generada() && colsOrigen.containsKey(c.nombre())) {
				comunes.add(c);
			}
		}
		List<String> soloOrigen = colsOrigen.keySet().stream().filter(c -> !colsDestino.containsKey(c)).toList();
		List<String> soloDestino = colsDestino.values().stream()
				.filter(c -> !c.generada() && !colsOrigen.containsKey(c.nombre())).map(Columna::nombre).toList();
		if (!soloOrigen.isEmpty()) {
			avisos.add("`" + tabla + "`: columnas del origen que el destino no tiene, no copiadas: " + soloOrigen);
		}
		String nota = soloDestino.isEmpty() ? "" : "columnas nuevas con su default: " + soloDestino;

		String lista = String.join(", ", comunes.stream().map(Columna::nombre).toList());
		String marcas = String.join(", ", comunes.stream().map(TransferirDatos::marca).toList());
		long leidas = 0;
		try (Statement lectura = origen.createStatement();
				ResultSet rs = lectura.executeQuery("SELECT " + comunes.stream().map(c -> "`" + c.nombre() + "`")
						.reduce((a, b) -> a + ", " + b).orElseThrow() + " FROM `" + tabla + "` ORDER BY 1");
				PreparedStatement insert = destino.prepareStatement(
						"INSERT INTO " + tabla + " (" + lista + ") OVERRIDING SYSTEM VALUE VALUES (" + marcas + ")")) {
			while (rs.next()) {
				for (int i = 0; i < comunes.size(); i++) {
					asignar(insert, i + 1, comunes.get(i), rs, i + 1, colsOrigen.get(comunes.get(i).nombre()));
				}
				insert.addBatch();
				if (++leidas % LOTE == 0) {
					insert.executeBatch();
				}
			}
			insert.executeBatch();
		}
		long enDestino = contar(destino, tabla, false);
		long enOrigen = contar(origen, tabla, true);
		resultados.add(new Resultado(tabla, enOrigen, enDestino, control(tabla, comunes), nota));
	}

	private static String marca(Columna c) {
		return switch (c.tipo()) {
			case "jsonb" -> "CAST(? AS jsonb)";
			case "vector" -> "CAST(? AS vector)";
			// MySQL admite 24:00:00 (los CHECK de franjas lo usan) y LocalTime no: viaja como texto.
			case "time" -> "CAST(? AS time)";
			default -> "?";
		};
	}

	private void asignar(PreparedStatement ps, int i, Columna destinoCol, ResultSet rs, int j, String tipoOrigen)
			throws SQLException {
		switch (destinoCol.tipo()) {
			case "bool" -> {
				int v = rs.getInt(j);
				ps.setObject(i, rs.wasNull() ? null : v != 0, Types.BOOLEAN);
			}
			case "timestamptz" -> {
				LocalDateTime v = rs.getObject(j, LocalDateTime.class);
				ps.setObject(i, v == null ? null : v.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
			}
			case "timestamp" -> ps.setObject(i, rs.getObject(j, LocalDateTime.class), Types.TIMESTAMP);
			case "date" -> ps.setObject(i, rs.getObject(j, java.time.LocalDate.class), Types.DATE);
			case "time" -> ps.setString(i, rs.getString(j));
			case "numeric" -> ps.setBigDecimal(i, rs.getBigDecimal(j));
			case "int2", "int4" -> {
				int v = rs.getInt(j);
				ps.setObject(i, rs.wasNull() ? null : v, Types.INTEGER);
			}
			case "int8" -> {
				long v = rs.getLong(j);
				ps.setObject(i, rs.wasNull() ? null : v, Types.BIGINT);
			}
			case "bytea" -> ps.setBytes(i, rs.getBytes(j));
			case "vector" -> ps.setString(i, vector(rs.getBytes(j)));
			case "jsonb", "varchar", "text", "bpchar" -> ps.setString(i, rs.getString(j));
			default -> throw new IllegalStateException("Tipo destino sin conversion: " + destinoCol);
		}
	}

	/** El BLOB float32 little-endian del origen, como texto de pgvector. Mismos float32. */
	static String vector(byte[] bytes) {
		if (bytes == null) {
			return null;
		}
		var floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
		StringBuilder t = new StringBuilder("[");
		for (int k = 0; floats.hasRemaining(); k++) {
			if (k > 0) {
				t.append(',');
			}
			t.append(Float.toString(floats.get()));
		}
		return t.append(']').toString();
	}

	// ------------------------------------------------------------------ conciliacion

	private static long contar(Connection c, String tabla, boolean mysql) throws SQLException {
		String q = mysql ? "`" + tabla + "`" : tabla;
		try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + q)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	/**
	 * Sumas de control comparadas en los dos motores, por columna copiada: nulos; suma de {@code id}
	 * y de cada numeric (importes, saldos); suma de los instantes en microsegundos;
	 * suma de las fechas en dias; cantidad de verdaderos de cada booleano; suma de largos de cada
	 * texto (no de los JSON, que los dos motores normalizan distinto); y la distribucion de cada
	 * columna {@code estado}. Devuelve "ok" o la lista de diferencias.
	 */
	private String control(String tabla, List<Columna> comunes) throws SQLException {
		List<String> exprPg = new ArrayList<>();
		List<String> exprMy = new ArrayList<>();
		List<String> nombres = new ArrayList<>();
		for (Columna c : comunes) {
			String n = c.nombre();
			String[] par = switch (c.tipo()) {
				case "numeric" -> new String[] {"SUM(" + n + ")", "SUM(`" + n + "`)"};
				case "timestamptz", "timestamp" -> new String[] {"SUM(EXTRACT(EPOCH FROM " + n + ") * 1000000)",
						"SUM(TIMESTAMPDIFF(MICROSECOND, '1970-01-01', `" + n + "`))"};
				case "date" -> new String[] {"SUM(" + n + " - DATE '1970-01-01')", "SUM(DATEDIFF(`" + n + "`, '1970-01-01'))"};
				case "bool" -> new String[] {"SUM(CASE WHEN " + n + " THEN 1 ELSE 0 END)", "SUM(`" + n + "` <> 0)"};
				case "varchar", "text" -> new String[] {"SUM(char_length(" + n + "))", "SUM(CHAR_LENGTH(`" + n + "`))"};
				default -> n.equals("id") ? new String[] {"SUM(" + n + ")", "SUM(`" + n + "`)"} : null;
			};
			if (par != null) {
				exprPg.add("COALESCE(" + par[0] + ", 0)");
				exprMy.add("COALESCE(" + par[1] + ", 0)");
				nombres.add("suma(" + n + ")");
			}
			exprPg.add("COUNT(*) - COUNT(" + c.nombre() + ")");
			exprMy.add("COUNT(*) - COUNT(`" + c.nombre() + "`)");
			nombres.add("nulos(" + c.nombre() + ")");
		}
		List<BigDecimal> pg = fila(destino, "SELECT " + String.join(", ", exprPg) + " FROM " + tabla);
		List<BigDecimal> my = fila(origen, "SELECT " + String.join(", ", exprMy) + " FROM `" + tabla + "`");
		List<String> difs = new ArrayList<>();
		for (int i = 0; i < nombres.size(); i++) {
			if (nombres.get(i).equals("estados")) {
				continue;
			}
			if (pg.get(i).compareTo(my.get(i)) != 0) {
				difs.add(nombres.get(i) + " " + my.get(i) + "→" + pg.get(i));
			}
		}
		if (comunes.stream().anyMatch(c -> c.nombre().equals("estado"))) {
			String pgEst = texto(destino, "SELECT string_agg(estado || '=' || n, ',' ORDER BY estado COLLATE \"C\") FROM"
					+ " (SELECT estado, COUNT(*) n FROM " + tabla + " GROUP BY estado) e");
			String myEst = texto(origen, "SELECT GROUP_CONCAT(CONCAT(estado, '=', n) ORDER BY estado COLLATE utf8mb4_bin)"
					+ " FROM (SELECT estado, COUNT(*) n FROM `" + tabla + "` GROUP BY estado) e");
			if (!java.util.Objects.equals(pgEst, myEst)) {
				difs.add("estados " + myEst + "→" + pgEst);
			}
			nombres.add("estados");
		}
		return difs.isEmpty() ? "ok (" + nombres.size() + " controles)" : "DIFIERE: " + difs;
	}

	private static String texto(Connection c, String sql) throws SQLException {
		try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			return rs.getString(1);
		}
	}

	private static List<BigDecimal> fila(Connection c, String sql) throws SQLException {
		try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			List<BigDecimal> r = new ArrayList<>();
			for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
				r.add(rs.getBigDecimal(i));
			}
			return r;
		}
	}

	private void reiniciarSecuencias(Map<String, Map<String, Columna>> tablas) throws SQLException {
		try (Statement s = destino.createStatement()) {
			for (var t : tablas.entrySet()) {
				for (Columna c : t.getValue().values()) {
					if (c.identidad()) {
						s.execute("SELECT setval(pg_get_serial_sequence('" + t.getKey() + "', '" + c.nombre()
								+ "'), COALESCE((SELECT MAX(" + c.nombre() + ") FROM " + t.getKey() + "), 0) + 1, false)");
					}
				}
			}
		}
	}

	/** Cuenta, por cada FK, las filas hijas cuyo padre no existe. La carga corrio sin FK. */
	private List<String> verificarClavesForaneas() throws SQLException {
		String sql = """
				SELECT con.conname, rel.relname, frel.relname,
				       array_to_string(ARRAY(SELECT a.attname FROM unnest(con.conkey) WITH ORDINALITY k(n, o)
				                              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.n ORDER BY k.o), ','),
				       array_to_string(ARRAY(SELECT a.attname FROM unnest(con.confkey) WITH ORDINALITY k(n, o)
				                              JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.n ORDER BY k.o), ',')
				  FROM pg_constraint con
				  JOIN pg_class rel ON rel.oid = con.conrelid
				  JOIN pg_class frel ON frel.oid = con.confrelid
				 WHERE con.contype = 'f'
				 ORDER BY 1""";
		List<String[]> fks = new ArrayList<>();
		try (Statement s = destino.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				fks.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
			}
		}
		List<String> huerfanos = new ArrayList<>();
		for (String[] fk : fks) {
			String[] hijas = fk[3].split(",");
			String[] padres = fk[4].split(",");
			StringBuilder on = new StringBuilder();
			StringBuilder noNulo = new StringBuilder();
			for (int i = 0; i < hijas.length; i++) {
				on.append(i > 0 ? " AND " : "").append("p.").append(padres[i]).append(" = h.").append(hijas[i]);
				noNulo.append(" AND h.").append(hijas[i]).append(" IS NOT NULL");
			}
			String q = "SELECT COUNT(*) FROM " + fk[1] + " h WHERE NOT EXISTS (SELECT 1 FROM " + fk[2] + " p WHERE "
					+ on + ")" + noNulo;
			try (Statement s = destino.createStatement(); ResultSet rs = s.executeQuery(q)) {
				rs.next();
				if (rs.getLong(1) > 0) {
					huerfanos.add(fk[0] + " (" + fk[1] + " → " + fk[2] + "): " + rs.getLong(1) + " filas");
				}
			}
		}
		avisos.add("Claves foraneas verificadas: " + fks.size() + ".");
		return huerfanos;
	}

	// ------------------------------------------------------------------ reporte

	private void escribirReporte(String urlOrigen, String urlDestino, List<String> huerfanos, long ms)
			throws Exception {
		StringBuilder md = new StringBuilder();
		md.append("# Conciliacion MySQL → PostgreSQL\n\n");
		md.append("- Origen: `").append(urlOrigen).append("`\n- Destino: `").append(urlDestino).append("`\n");
		md.append("- Fecha: ").append(OffsetDateTime.now(ZoneOffset.UTC)).append("\n- Duracion: ").append(ms).append(" ms\n");
		long totO = resultados.stream().mapToLong(Resultado::origen).sum();
		long totD = resultados.stream().mapToLong(Resultado::destino).sum();
		md.append("- Filas: ").append(totO).append(" en origen, ").append(totD).append(" en destino\n");
		md.append("- Huerfanos: ").append(huerfanos.isEmpty() ? "ninguno" : String.join("; ", huerfanos)).append("\n\n");
		md.append("| Tabla | Origen | Destino | Dif | Sumas de control | Nota |\n|---|---:|---:|---:|---|---|\n");
		for (Resultado r : resultados) {
			md.append("| ").append(r.tabla).append(" | ").append(r.origen).append(" | ").append(r.destino)
					.append(" | ").append(r.destino - r.origen).append(" | ").append(r.control).append(" | ")
					.append(r.nota).append(" |\n");
		}
		md.append("\n## Avisos\n\n");
		for (String a : avisos) {
			md.append("- ").append(a).append('\n');
		}
		Files.writeString(reporte, md.toString());
	}
}
