package com.akine.activity;

import com.akine.activity.ActividadItFixture.Desenlace;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Una carrera con el orden <b>elegido</b>, no sorteado (AKINE-09.03).
 *
 * <p>{@link ActividadItFixture#enParalelo} larga las transacciones a la vez y deja que InnoDB
 * decida; sirve para el invariante, pero una ventana estrecha —una lectura sin lock seguida de un
 * {@code UPDATE} unos microsegundos despues— puede no abrirse en cien rondas. Esto la abre siempre:
 *
 * <ol>
 *   <li>una conexion propia toma la fila con {@code FOR UPDATE} y la retiene ({@link Retencion});</li>
 *   <li>cada tarea se larga y se espera <b>hasta verla bloqueada</b> en esa fila, mirando su
 *       sentencia en {@code information_schema.PROCESSLIST} (el usuario de la aplicacion ve sus
 *       propias conexiones sin privilegio {@code PROCESS});</li>
 *   <li>se suelta la fila y todas corren contra lo que commitee la primera.</li>
 * </ol>
 *
 * <p>Lo que queda en manos de InnoDB es solo el orden en que despierta a las que esperan, y por eso
 * los tests afirman el invariante para los dos desenlaces posibles.
 */
final class CarreraDirigida implements AutoCloseable {

	private static final long ESPERA_MAXIMA_MS = 15_000;

	private final JdbcTemplate jdbc;
	private final ExecutorService hilos = Executors.newCachedThreadPool();

	CarreraDirigida(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Larga la tarea y vuelve recien cuando su sentencia {@code patron} esta esperando un lock. */
	<T> Future<Desenlace<T>> largarHastaBloquearse(Callable<T> tarea, String patron) {
		int antes = enEspera(patron);
		Future<Desenlace<T>> futuro = hilos.submit(() -> {
			try {
				return new Desenlace<>(tarea.call(), null);
			} catch (Exception error) {
				return new Desenlace<T>(null, error);
			}
		});
		long limite = System.currentTimeMillis() + ESPERA_MAXIMA_MS;
		while (enEspera(patron) <= antes) {
			if (futuro.isDone()) {
				throw new IllegalStateException(
						"La tarea termino sin bloquearse en '" + patron + "': " + resultado(futuro));
			}
			if (System.currentTimeMillis() > limite) {
				throw new IllegalStateException("La tarea nunca se bloqueo en '" + patron + "'");
			}
			pausa();
		}
		return futuro;
	}

	static <T> Desenlace<T> resultado(Future<Desenlace<T>> futuro) {
		try {
			return futuro.get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("La tarea no termino", e);
		}
	}

	/**
	 * Sentencias de OTRAS conexiones en curso que contienen el patron (comodines de {@code LIKE}).
	 * Mientras la fila esta retenida, toda sentencia que la pide y sigue en la lista esta esperando.
	 */
	private int enEspera(String patron) {
		Integer cuantas = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.PROCESSLIST
				 WHERE ID <> CONNECTION_ID() AND COMMAND = 'Query' AND INFO LIKE ?
				""", Integer.class, patron);
		return cuantas == null ? 0 : cuantas;
	}

	private static void pausa() {
		try {
			Thread.sleep(20);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void close() {
		hilos.shutdownNow();
	}

	/** Una fila tomada con {@code FOR UPDATE} desde una conexion que no es de ninguna tarea. */
	static final class Retencion implements AutoCloseable {

		private final Connection conexion;
		private boolean suelta;

		private Retencion(Connection conexion) {
			this.conexion = conexion;
		}

		static Retencion de(DataSource dataSource, String tabla, long id) {
			try {
				Connection conexion = dataSource.getConnection();
				conexion.setAutoCommit(false);
				conexion.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
				try (PreparedStatement sentencia = conexion.prepareStatement(
						"SELECT id FROM " + tabla + " WHERE id = ? FOR UPDATE")) {
					sentencia.setLong(1, id);
					try (ResultSet filas = sentencia.executeQuery()) {
						if (!filas.next()) {
							conexion.rollback();
							conexion.close();
							throw new IllegalArgumentException("No existe " + tabla + " " + id);
						}
					}
				}
				return new Retencion(conexion);
			} catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		}

		/** Suelta la fila sin haber escrito nada. */
		void soltar() {
			if (suelta) {
				return;
			}
			suelta = true;
			try {
				conexion.rollback();
				conexion.close();
			} catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public void close() {
			soltar();
		}
	}
}
