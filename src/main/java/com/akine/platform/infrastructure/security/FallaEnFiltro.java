package com.akine.platform.infrastructure.security;

import com.akine.platform.spi.problem.ProblemType;
import java.io.IOException;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionException;

import jakarta.persistence.PersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Responde un error de los filtros de seguridad que consultan la base, sin dejarlo escapar.
 *
 * <h2>Por que no se deja escapar (docs/carga.md §6.3)</h2>
 *
 * <p>{@code JwtAuthenticationFilter} revalida el rol de plataforma y {@code TenantContextFilter}
 * la membership contra la base en cada pedido. Si la base no responde —el pool agotado, el motor
 * caido—, la excepcion salia del filtro, el contenedor despachaba a {@code /error}, ese despacho
 * corre sin el filtro de autenticacion y el pedido terminaba como <b>401</b>. Para el frontend un
 * 401 es "tu sesion no vale": borra el token y desloguea. Una caida de la base deslogueaba a todos
 * los usuarios a la vez, y la prueba de carga de G-10 lo midio: 427 respuestas 401 con la base
 * trabada.
 *
 * <p>Una falla de infraestructura responde <b>503 {@code service-unavailable}</b>: transitoria, la
 * sesion sigue valida. Cualquier otra excepcion es un bug y responde 500 {@code internal-error}.
 * Nunca 401.
 */
public final class FallaEnFiltro {

	private static final Logger log = LoggerFactory.getLogger(FallaEnFiltro.class);

	private FallaEnFiltro() {
		// Utilidad.
	}

	/** Escribe el problema que corresponde a la excepcion y la loguea completa. */
	public static void responder(
			HttpServletRequest request, HttpServletResponse response, RuntimeException falla)
			throws IOException {

		if (esDeInfraestructura(falla)) {
			log.error("La base no respondio en un filtro de seguridad: {} responde 503",
					ProblemResponses.rutaDe(request), falla);
			ProblemResponses.escribir(request, response, HttpStatus.SERVICE_UNAVAILABLE,
					ProblemType.SERVICE_UNAVAILABLE.uri(),
					"Servicio no disponible",
					"El servicio no pudo atender el pedido en este momento. Su sesion sigue valida:"
							+ " intente de nuevo en unos segundos.");
			return;
		}
		log.error("Excepcion no controlada en un filtro de seguridad: {} responde 500",
				ProblemResponses.rutaDe(request), falla);
		ProblemResponses.escribir(request, response, HttpStatus.INTERNAL_SERVER_ERROR,
				ProblemType.INTERNAL_ERROR.uri(),
				"Error interno",
				"Ocurrio un error inesperado");
	}

	/**
	 * Si la causa es la base o el acceso a ella: acceso a datos de Spring, transaccion que no pudo
	 * abrirse (es lo que lanza Hikari cuando el pool no entrega una conexion a tiempo), JPA o JDBC.
	 */
	public static boolean esDeInfraestructura(Throwable falla) {
		for (Throwable causa = falla; causa != null; causa = causa.getCause()) {
			if (causa instanceof DataAccessException
					|| causa instanceof TransactionException
					|| causa instanceof PersistenceException
					|| causa instanceof SQLException) {
				return true;
			}
			if (causa.getCause() == causa) {
				break;
			}
		}
		return false;
	}
}
