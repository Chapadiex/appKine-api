package com.akine.offering.api;

import com.akine.offering.domain.exception.ConsultorioNoAccesibleException;
import com.akine.offering.domain.exception.ConsultorioNoOperableException;
import com.akine.offering.domain.exception.HabilitacionNoAccesibleException;
import com.akine.offering.domain.exception.OfertaInactivaException;
import com.akine.offering.domain.exception.OfertaNombreComercialTakenException;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.exception.PoliticaDeDevengoIncoherenteException;
import com.akine.offering.domain.exception.ServicioCodigoTakenException;
import com.akine.offering.domain.exception.ServicioInactivoException;
import com.akine.offering.domain.exception.ServicioNombreTakenException;
import com.akine.offering.domain.exception.ServicioNotAccessibleException;
import com.akine.offering.domain.exception.ServicioYaInactivoException;
import com.akine.platform.spi.problem.ProblemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Traduce las excepciones de dominio de {@code offering} a Problem Details RFC 7807 (ADR-0005).
 *
 * <p><b>{@code HIGHEST_PRECEDENCE} con el mismo criterio que los otros handlers de modulo:</b>
 * sin el, {@code GlobalExceptionHandler} atrapa estas excepciones como {@code RuntimeException}
 * y devuelve un 500 generico. El orden no es una preferencia de estilo, es lo que hace que estos
 * {@code type} lleguen al cliente.
 *
 * <p><b>Lo que este handler NO mapea, y a proposito:</b> el
 * {@code OptimisticLockingFailureException} de la version desactualizada. Lo mapea el handler
 * global a {@code conflict}, y esta etapa no lo intercepta para emitir
 * {@code concurrent-modification} — que es lo que los contratos de 02.02 y 02.05 prometen y su
 * codigo no cumple. Unificar los dos {@code type} es una decision de contrato transversal que
 * excede a M27; mientras no se tome, lo honesto es documentar el que realmente sale.
 *
 * <h2>Por que un no-accesible es 404 y no 403</h2>
 *
 * <p>Un servicio inexistente y una oferta de otro tenant responden lo mismo. Distinguirlos
 * confirmaria que ese id existe, y bastaria recorrer numeros para averiguar que servicios presta
 * cada centro del SaaS: informacion comercial de un cliente, filtrada por la forma del error.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OfferingProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(OfferingProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI VALIDATION_ERROR = ProblemType.VALIDATION_ERROR.uri();
	private static final URI SERVICIO_CODIGO_TAKEN = ProblemType.SERVICIO_CODIGO_TAKEN.uri();
	private static final URI SERVICIO_NOMBRE_TAKEN = ProblemType.SERVICIO_NOMBRE_TAKEN.uri();
	private static final URI SERVICIO_INACTIVO = ProblemType.SERVICIO_INACTIVO.uri();
	private static final URI SERVICIO_ALREADY_INACTIVE =
			ProblemType.SERVICIO_ALREADY_INACTIVE.uri();
	private static final URI OFERTA_NOMBRE_COMERCIAL_TAKEN =
			ProblemType.OFERTA_NOMBRE_COMERCIAL_TAKEN.uri();
	private static final URI OFERTA_INACTIVA = ProblemType.OFERTA_INACTIVA.uri();
	private static final URI OFERTA_ALREADY_INACTIVE = ProblemType.OFERTA_ALREADY_INACTIVE.uri();
	private static final URI CONSULTORIO_NO_OPERABLE = ProblemType.CONSULTORIO_NO_OPERABLE.uri();

	// =================================================================================
	// No accesibles — 404
	// =================================================================================

	@ExceptionHandler(ServicioNotAccessibleException.class)
	public ProblemDetail handleServicioNoAccesible(ServicioNotAccessibleException exception) {
		log.debug("Servicio no accesible: servicioId={}", exception.getServicioId());
		return noEncontrado("El servicio no existe.");
	}

	@ExceptionHandler(OfertaNotAccessibleException.class)
	public ProblemDetail handleOfertaNoAccesible(OfertaNotAccessibleException exception) {
		log.debug("Oferta no accesible: ofertaId={}", exception.getOfertaId());
		return noEncontrado("La oferta no existe.");
	}

	@ExceptionHandler(ConsultorioNoAccesibleException.class)
	public ProblemDetail handleConsultorioNoAccesible(ConsultorioNoAccesibleException exception) {
		log.debug("Consultorio no accesible desde offering: consultorioId={}",
				exception.getConsultorioId());
		return noEncontrado("La sede no existe.");
	}

	/**
	 * El recurso que se quiso habilitar no existe, es de otro tenant o es de otra sede.
	 *
	 * <p>Los tres casos responden 404 y con el mismo texto. Distinguirlos confirmaria que ese id
	 * existe en alguna parte, y bastaria recorrer numeros para averiguar cuanta gente y cuantos
	 * boxes tiene cada centro del SaaS.
	 */
	@ExceptionHandler(HabilitacionNoAccesibleException.class)
	public ProblemDetail handleHabilitacionNoAccesible(
			HabilitacionNoAccesibleException exception) {

		log.debug("Recurso no accesible para habilitar: tipo={} id={}",
				exception.getTipo(), exception.getRecursoId());

		return noEncontrado("profesional".equals(exception.getTipo())
				? "Ese profesional no existe en este centro, o no atiende en esta sede."
				: "Ese espacio no existe en esta sede.");
	}

	// =================================================================================
	// Invariantes del catalogo global — 409
	// =================================================================================

	@ExceptionHandler(ServicioCodigoTakenException.class)
	public ProblemDetail handleCodigoTaken(ServicioCodigoTakenException exception) {
		log.debug("Codigo de servicio en uso: codigo={}", exception.getCodigo());
		return conflicto(
				"Ya existe un servicio vigente con ese codigo en el catalogo de plataforma. El "
						+ "codigo de uno dado de baja si se puede reusar.",
				"Codigo de servicio en uso",
				SERVICIO_CODIGO_TAKEN);
	}

	@ExceptionHandler(ServicioNombreTakenException.class)
	public ProblemDetail handleNombreTaken(ServicioNombreTakenException exception) {
		log.debug("Nombre de servicio en uso");
		return conflicto(
				"Ya existe un servicio vigente con ese nombre. La comparacion no distingue "
						+ "mayusculas ni acentos.",
				"Nombre de servicio en uso",
				SERVICIO_NOMBRE_TAKEN);
	}

	/**
	 * El servicio referenciado esta dado de baja.
	 *
	 * <p>Es el 409 de RF-M27-002 y llega desde el alta de una OFERTA, no desde el servicio. La
	 * baja de un servicio global no cascadea: las ofertas que ya lo referencian siguen operando
	 * y lo unico que se impide es crear ofertas nuevas sobre el.
	 */
	@ExceptionHandler(ServicioInactivoException.class)
	public ProblemDetail handleServicioInactivo(ServicioInactivoException exception) {
		log.debug("Servicio dado de baja referenciado: servicioId={}", exception.getServicioId());
		return conflicto(
				"El servicio esta dado de baja y no admite ofertas nuevas. Las ofertas que ya "
						+ "lo prestaban siguen operando.",
				"Servicio dado de baja",
				SERVICIO_INACTIVO);
	}

	@ExceptionHandler(ServicioYaInactivoException.class)
	public ProblemDetail handleServicioYaInactivo(ServicioYaInactivoException exception) {
		log.debug("Operacion sobre servicio ya inactivo: servicioId={} operacion={}",
				exception.getServicioId(), exception.getOperacion());

		return "dar de baja".equals(exception.getOperacion())
				? conflicto(
						"El servicio ya estaba dado de baja.",
						"Servicio ya dado de baja",
						SERVICIO_ALREADY_INACTIVE)
				: conflicto(
						"El servicio esta dado de baja: no admite ediciones. Sus datos "
								+ "historicos siguen siendo consultables.",
						"Servicio dado de baja",
						SERVICIO_INACTIVO);
	}

	// =================================================================================
	// Invariantes de la oferta — 409
	// =================================================================================

	@ExceptionHandler(OfertaNombreComercialTakenException.class)
	public ProblemDetail handleNombreComercialTaken(
			OfertaNombreComercialTakenException exception) {

		// El nombre comercial NO se loguea: es dato comercial del cliente. La sede si, que es
		// lo que permite ubicar el conflicto sin publicar como llama el centro a lo que ofrece.
		log.debug("Nombre comercial de oferta en uso: consultorioId={}",
				exception.getConsultorioId());

		return conflicto(
				"Ya existe una oferta vigente con ese nombre comercial en esta sede. La "
						+ "comparacion no distingue mayusculas ni acentos, y el nombre de una "
						+ "dada de baja si se puede reusar.",
				"Nombre comercial en uso",
				OFERTA_NOMBRE_COMERCIAL_TAKEN);
	}

	@ExceptionHandler(OfertaInactivaException.class)
	public ProblemDetail handleOfertaInactiva(OfertaInactivaException exception) {
		log.debug("Operacion sobre oferta inactiva: ofertaId={} operacion={}",
				exception.getOfertaId(), exception.getOperacion());

		return "dar de baja".equals(exception.getOperacion())
				? conflicto(
						"La oferta ya estaba dada de baja.",
						"Oferta ya dada de baja",
						OFERTA_ALREADY_INACTIVE)
				: conflicto(
						"La oferta esta dada de baja: no admite ediciones. Sus historicos "
								+ "siguen resolviendo.",
						"Oferta dada de baja",
						OFERTA_INACTIVA);
	}

	@ExceptionHandler(ConsultorioNoOperableException.class)
	public ProblemDetail handleConsultorioNoOperable(ConsultorioNoOperableException exception) {
		log.debug("Sede no operable para offering: consultorioId={}",
				exception.getConsultorioId());
		return conflicto(
				"La sede no esta en un estado que admita operar sobre sus ofertas.",
				"Sede no operable",
				CONSULTORIO_NO_OPERABLE);
	}

	/**
	 * Politica de devengo incoherente: <b>422 y no 409</b>.
	 *
	 * <p>No es un estado del mundo que impide la operacion —eso es un conflicto—, es un pedido que
	 * no tiene sentido: un momento sin esquema, un esquema que no admite ese momento, o
	 * {@code POR_CLASE} sin elegir entre sus dos momentos posibles. Un 409 le diria al mostrador
	 * "volve a intentar", y volver a intentar lo mismo va a fallar igual.
	 */
	@ExceptionHandler(PoliticaDeDevengoIncoherenteException.class)
	public ProblemDetail handlePoliticaIncoherente(
			PoliticaDeDevengoIncoherenteException exception) {

		log.debug("Politica de devengo rechazada: {}", exception.getMessage());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
		problem.setTitle("Politica de devengo incoherente");
		problem.setType(VALIDATION_ERROR);
		return problem;
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setTitle("No encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}

	private static ProblemDetail conflicto(String detalle, String titulo, URI type) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalle);
		problem.setTitle(titulo);
		problem.setType(type);
		return problem;
	}
}
