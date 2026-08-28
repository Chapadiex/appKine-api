package com.akine.person.api;

import com.akine.person.domain.exception.PersonaDocumentoTakenException;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.exception.PersonaPosibleDuplicadoException;
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
 * Traduce las excepciones de dominio de {@code person} a Problem Details RFC 7807 (ADR-0005).
 *
 * <p><b>{@code HIGHEST_PRECEDENCE} con el mismo criterio que los otros handlers de modulo:</b>
 * sin el, {@code GlobalExceptionHandler} atrapa estas excepciones como {@code RuntimeException} y
 * devuelve un 500 generico. El orden no es estilo, es lo que hace que estos {@code type} lleguen
 * al cliente.
 *
 * <p><b>Lo que este handler NO mapea, y a proposito:</b> el
 * {@code OptimisticLockingFailureException} de la version desactualizada. Lo mapea el handler
 * global a {@code conflict}, y esta etapa no lo intercepta para emitir
 * {@code concurrent-modification} — que es lo que los contratos de 02.02 y 02.05 prometen y su
 * codigo no cumple. Unificarlos es una decision de contrato transversal que excede a M07;
 * mientras no se tome, lo honesto es documentar el que realmente sale.
 *
 * <h2>Que se loguea y que no</h2>
 *
 * <p><b>Ningun dato personal entra al log.</b> Ni documento, ni apellido, ni telefono, ni correo.
 * Los logs se agregan, se envian a un colector y los lee gente que no tiene
 * {@code paciente:manage}: un {@code log.info} con el DNI de quien se acaba de registrar es una
 * fuga que ninguna matriz de permisos alcanza a tapar. Se loguean ids y cantidades, que ubican el
 * problema sin publicar a la persona.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PersonProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(PersonProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI PERSONA_DOCUMENTO_TAKEN = ProblemType.PERSONA_DOCUMENTO_TAKEN.uri();
	private static final URI PERSONA_POSIBLE_DUPLICADO =
			ProblemType.PERSONA_POSIBLE_DUPLICADO.uri();
	private static final URI PERSONA_INACTIVA = ProblemType.PERSONA_INACTIVA.uri();

	@ExceptionHandler(PersonaNotAccessibleException.class)
	public ProblemDetail handlePersonaNoAccesible(PersonaNotAccessibleException exception) {
		log.debug("Persona no accesible: personaId={}", exception.getPersonaId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "La persona no existe.");
		problem.setTitle("No encontrada");
		problem.setType(NOT_FOUND);
		return problem;
	}

	/**
	 * Documento repetido: el invariante duro (409).
	 *
	 * <p>Lleva {@code personaExistenteId} cuando se lo pudo determinar, para que la pantalla
	 * ofrezca abrir la ficha que ya existe en vez de dejar al operador buscando a mano. Hoy viene
	 * casi siempre en {@code null}, y la razon esta en {@code PersonaService.persistir}: despues
	 * de un flush fallido la sesion JPA no se puede volver a consultar, y averiguar el id ahi
	 * mismo produciria un 500 en vez de este 409. La propiedad se publica igual porque el dia que
	 * se resuelva —en otra transaccion— el contrato no cambia.
	 */
	@ExceptionHandler(PersonaDocumentoTakenException.class)
	public ProblemDetail handleDocumentoTaken(PersonaDocumentoTakenException exception) {
		log.debug("Documento de persona en uso");

		ProblemDetail problem = conflicto(
				"Ya existe una persona vigente con ese documento en la organizacion. El documento "
						+ "de una persona dada de baja si se puede reusar.",
				"Documento en uso",
				PERSONA_DOCUMENTO_TAKEN);

		if (exception.getPersonaExistenteId() != null) {
			problem.setProperty("personaExistenteId", exception.getPersonaExistenteId());
		}
		return problem;
	}

	/**
	 * Posible duplicado: la advertencia que implementa RN-M07-001 (409).
	 *
	 * <p>Lleva {@code candidatos} —los ids que coinciden— porque sin ellos el 409 seria un
	 * callejon: el operador sabe que "hay alguien parecido" y no puede verlo. Con la lista, la
	 * pantalla muestra las fichas y el operador elige entre abrir una o reenviar el alta con
	 * {@code confirmaPosibleDuplicado}.
	 *
	 * <p>Los ids no filtran nada: quien recibe esto tiene {@code paciente:manage} en esa
	 * organizacion y puede leer cualquiera de esas fichas con un GET.
	 */
	@ExceptionHandler(PersonaPosibleDuplicadoException.class)
	public ProblemDetail handlePosibleDuplicado(PersonaPosibleDuplicadoException exception) {
		log.debug("Alta detenida por posibles duplicados: candidatos={}",
				exception.getCandidatos().size());

		ProblemDetail problem = conflicto(
				"El alta coincide con personas ya registradas. Revisalas antes de crear una "
						+ "ficha nueva; si es otra persona, reenvia el alta confirmando el "
						+ "posible duplicado.",
				"Posible duplicado",
				PERSONA_POSIBLE_DUPLICADO);

		problem.setProperty("candidatos", exception.getCandidatos());
		return problem;
	}

	/**
	 * La persona esta dada de baja (409, nunca 404).
	 *
	 * <p>La persona existe y se sigue leyendo con 200: RN-M07-004 exige que el historico resuelva.
	 * Lo que no admite la operacion es su estado.
	 */
	@ExceptionHandler(PersonaInactivaException.class)
	public ProblemDetail handlePersonaInactiva(PersonaInactivaException exception) {
		log.debug("Operacion sobre persona inactiva: personaId={} operacion={}",
				exception.getPersonaId(), exception.getOperacion());

		return conflicto(
				"La persona esta dada de baja y no admite " + exception.getOperacion()
						+ ". Su ficha sigue siendo consultable.",
				"Persona dada de baja",
				PERSONA_INACTIVA);
	}

	private static ProblemDetail conflicto(String detalle, String titulo, URI type) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalle);
		problem.setTitle(titulo);
		problem.setType(type);
		return problem;
	}
}
