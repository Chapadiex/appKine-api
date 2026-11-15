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
import java.sql.Timestamp;
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
	public List<Map<String, Object>> leer(String sql, long organizationId, long consultorioId, int limiteFilas) {
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
				jdbc.queryForList("SELECT set_config('akine.asistente.organizacion', ?, true), "
						+ "set_config('akine.asistente.sede', ?, true)",
						String.valueOf(organizationId), String.valueOf(consultorioId));
				return jdbc.query(envuelta, (ResultSet rs, int fila) -> fila(rs));
			});
		}
		catch (DataAccessException e) {
			Throwable causa = e.getMostSpecificCause();
			throw new ConsultaInvalidaException(causa.getMessage() == null ? e.getMessage() : causa.getMessage(), e);
		}
	}

	/** Columna a valor, con fechas e instantes como tipos de java.time para que se serialicen en ISO. */
	private static Map<String, Object> fila(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		Map<String, Object> fila = new LinkedHashMap<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			Object valor = rs.getObject(i);
			if (valor instanceof Timestamp ts) {
				valor = ts.toInstant();
			}
			else if (valor instanceof java.sql.Date d) {
				valor = d.toLocalDate();
			}
			else if (valor != null && !(valor instanceof Number) && !(valor instanceof Boolean)
					&& !(valor instanceof String)) {
				valor = valor.toString();
			}
			fila.put(meta.getColumnLabel(i), valor);
		}
		return fila;
	}

}
