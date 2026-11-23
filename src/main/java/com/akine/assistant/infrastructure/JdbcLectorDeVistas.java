package com.akine.assistant.infrastructure;

import com.akine.assistant.application.ValidadorDeSql;
import com.akine.assistant.domain.port.LectorDeVistas;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corre una consulta libre ya validada contra las vistas de {@code asistente_sql}.
 *
 * <p>Todo en una transaccion propia, de solo lectura, que fija: {@code READ ONLY} (ninguna escritura
 * pasa, aunque la llame una funcion), {@code statement_timeout}, el {@code search_path} en el esquema
 * de las vistas, y la organizacion y la sede con {@code set_config(..., true)}, que mueren con la
 * transaccion y no quedan en la conexion del pool. La consulta va envuelta con su {@code LIMIT}.
 */
public class JdbcLectorDeVistas implements LectorDeVistas {

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaccion;
	private final int tiempoMaximoMs;

	public JdbcLectorDeVistas(JdbcTemplate jdbc, PlatformTransactionManager transacciones, int tiempoMaximoMs) {
		this.jdbc = jdbc;
		this.tiempoMaximoMs = tiempoMaximoMs;
		this.transaccion = new TransactionTemplate(transacciones);
		this.transaccion.setReadOnly(true);
		this.transaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaccion.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public List<Map<String, Object>> leer(String sql, long organizationId, long consultorioId, ZoneId zona,
			int limiteFilas) {
		String consulta = sql.strip();
		if (consulta.endsWith(";")) {
			consulta = consulta.substring(0, consulta.length() - 1);
		}
		String envuelta = "SELECT * FROM (" + consulta + ") AS consulta LIMIT " + Math.max(1, limiteFilas);
		try {
			return transaccion.execute(estado -> {
				jdbc.execute("SET TRANSACTION READ ONLY");
				jdbc.execute("SET LOCAL statement_timeout = " + tiempoMaximoMs);
				jdbc.execute("SET LOCAL search_path = " + ValidadorDeSql.ESQUEMA);
				// La sesion es UTC: sin esto CURRENT_DATE seria el dia de Greenwich. La zona sale de la sede.
				jdbc.queryForList("SELECT set_config('TimeZone', ?, true)", zona.getId());
				jdbc.queryForList("SELECT set_config('akine.asistente.organizacion', ?, true), "
						+ "set_config('akine.asistente.sede', ?, true)",
						String.valueOf(organizationId), String.valueOf(consultorioId));
				return jdbc.query(envuelta, (ResultSet rs, int fila) -> fila(rs));
			});
		}
		catch (DataAccessException e) {
			Throwable causa = e.getMostSpecificCause();
			String estado = causa instanceof SQLException motor && motor.getSQLState() != null ? " [SQLState " + motor.getSQLState() + "]" : "";
			throw new ConsultaInvalidaException(sinValores(causa.getMessage() == null ? e.getMessage() : causa.getMessage()) + estado, e);
		}
	}

	/**
	 * Quita lo que el motor cita entre comillas: un mensaje como {@code invalid input syntax for type integer: "x"}
	 * puede traer un valor de otro centro. Queda la forma del error, sin los valores.
	 */
	public static String sinValores(String mensaje) {
		return mensaje == null ? "" : mensaje.replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("'[^']*'", "'…'");
	}

	/** Columna a valor, con fechas e instantes como tipos de java.time para que se serialicen en ISO. */
	private static Map<String, Object> fila(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> fila = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			// Por el nombre del tipo de PostgreSQL: el driver informa timestamptz como Types.TIMESTAMP.
			Object valor = switch (meta.getColumnTypeName(i)) {
				case "date" -> rs.getObject(i, LocalDate.class);
				case "timestamptz" -> instante(rs.getObject(i, OffsetDateTime.class));
				case "timestamp" -> {
					LocalDateTime local = rs.getObject(i, LocalDateTime.class);
					yield local == null ? null : local.toInstant(ZoneOffset.UTC);
				}
				default -> rs.getObject(i);
			};
			if (valor != null && !(valor instanceof Number) && !(valor instanceof Boolean)
					&& !(valor instanceof String) && !(valor instanceof LocalDate) && !(valor instanceof Instant)) {
				valor = valor.toString();
			}
			fila.put(meta.getColumnLabel(i), valor);
		}
		return fila;
	}

	private static Instant instante(OffsetDateTime valor) {
		return valor == null ? null : valor.toInstant();
	}

}
