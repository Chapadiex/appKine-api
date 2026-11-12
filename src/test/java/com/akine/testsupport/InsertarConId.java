package com.akine.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * INSERT que devuelve el id generado en la MISMA sentencia.
 *
 * <p>{@code SELECT LAST_INSERT_ID()} es por conexion: con un {@link JdbcTemplate} sobre el pool,
 * el INSERT y el SELECT pueden caer en conexiones distintas (otro hilo toma la conexion entre
 * las dos sentencias) y devolver el id de otra conexion, o 0. Esta utilidad usa una sola conexion
 * y pide la columna {@code id} como clave generada: sin nombrarla, el driver de PostgreSQL agrega
 * {@code RETURNING *} y la primera columna del resultado depende del orden de la tabla.
 */
public final class InsertarConId {

	private InsertarConId() {
	}

	public static long insertar(JdbcTemplate jdbc, String sql, Object... args) {
		Long id = jdbc.execute((Connection conexion) -> {
			try (PreparedStatement sentencia =
					conexion.prepareStatement(sql, new String[] {"id"})) {
				for (int i = 0; i < args.length; i++) {
					sentencia.setObject(i + 1, args[i]);
				}
				sentencia.executeUpdate();
				try (ResultSet claves = sentencia.getGeneratedKeys()) {
					if (!claves.next()) {
						throw new IllegalStateException("El INSERT no devolvio clave generada");
					}
					return claves.getLong(1);
				}
			}
		});
		return id;
	}
}
