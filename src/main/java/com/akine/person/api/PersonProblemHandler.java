package com.akine.person.api;

import com.akine.person.domain.exception.AdjuntoInactivoException;
import com.akine.person.domain.exception.AdjuntoNoDisponibleException;
import com.akine.person.domain.exception.AdjuntoNotAccessibleException;
import com.akine.person.domain.exception.ArchivoNoAceptadoException;
import com.akine.person.domain.exception.AutorizacionInactivaException;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionSuperpuestaException;
import com.akine.person.domain.exception.AutorizacionTransicionNoPermitidaException;
import com.akine.person.domain.exception.CoberturaInactivaException;
import com.akine.person.domain.exception.CoberturaNotAccessibleException;
import com.akine.person.domain.exception.CoberturaPrincipalSuperpuestaException;
import com.akine.person.domain.exception.CoberturaSuperpuestaException;
import com.akine.person.domain.exception.NumeroDeDocumentoTakenException;
import com.akine.person.domain.exception.OrdenInactivaException;
import com.akine.person.domain.exception.OrdenNotAccessibleException;
import com.akine.person.domain.exception.PersonaDocumentoTakenException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.person.domain.exception.PlanNoSeleccionableException;
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
	private static final URI ARCHIVO_NO_ACEPTADO = ProblemType.ARCHIVO_NO_ACEPTADO.uri();
	private static final URI ADJUNTO_NO_DISPONIBLE = ProblemType.ADJUNTO_NO_DISPONIBLE.uri();
	private static final URI ADJUNTO_INACTIVO = ProblemType.ADJUNTO_INACTIVO.uri();
	private static final URI PERSONA_SIN_PERFIL_PACIENTE =
			ProblemType.PERSONA_SIN_PERFIL_PACIENTE.uri();
	private static final URI PLAN_NO_SELECCIONABLE = ProblemType.PLAN_NO_SELECCIONABLE.uri();
	private static final URI COBERTURA_SUPERPUESTA = ProblemType.COBERTURA_SUPERPUESTA.uri();
	private static final URI COBERTURA_PRINCIPAL_SUPERPUESTA =
			ProblemType.COBERTURA_PRINCIPAL_SUPERPUESTA.uri();
	private static final URI COBERTURA_INACTIVA = ProblemType.COBERTURA_INACTIVA.uri();
	private static final URI COBERTURA_ALREADY_INACTIVE =
			ProblemType.COBERTURA_ALREADY_INACTIVE.uri();
	private static final URI ORDEN_INACTIVA = ProblemType.ORDEN_INACTIVA.uri();
	private static final URI ORDEN_ALREADY_INACTIVE = ProblemType.ORDEN_ALREADY_INACTIVE.uri();
	private static final URI AUTORIZACION_INACTIVA = ProblemType.AUTORIZACION_INACTIVA.uri();
	private static final URI AUTORIZACION_ALREADY_INACTIVE =
			ProblemType.AUTORIZACION_ALREADY_INACTIVE.uri();
	private static final URI AUTORIZACION_SUPERPUESTA = ProblemType.AUTORIZACION_SUPERPUESTA.uri();
	private static final URI AUTORIZACION_TRANSICION_NO_PERMITIDA =
			ProblemType.AUTORIZACION_TRANSICION_NO_PERMITIDA.uri();
	private static final URI DOCUMENTO_NUMERO_TAKEN = ProblemType.DOCUMENTO_NUMERO_TAKEN.uri();

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

	/**
	 * El adjunto no existe, es de otra organizacion o es de otra persona (404).
	 *
	 * <p>Los tres casos colapsan en el mismo error, igual que con la persona: distinguirlos
	 * confirmaria que ese id existe en algun lado.
	 */
	@ExceptionHandler(AdjuntoNotAccessibleException.class)
	public ProblemDetail handleAdjuntoNoAccesible(AdjuntoNotAccessibleException exception) {
		log.debug("Adjunto no accesible: adjuntoId={}", exception.getAdjuntoId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "El adjunto no existe.");
		problem.setTitle("No encontrado");
		problem.setType(NOT_FOUND);
		return problem;
	}

	// =================================================================================
	// Coberturas del paciente (M08, AKINE-03.04)
	// =================================================================================

	@ExceptionHandler(CoberturaNotAccessibleException.class)
	public ProblemDetail handleCoberturaNoAccesible(CoberturaNotAccessibleException exception) {
		// Los tres casos —no existe, es de otro tenant, es de otro paciente— responden lo MISMO.
		// Distinguirlos confirmaria que ese id existe.
		log.debug("Cobertura no accesible: coberturaId={}", exception.getCoberturaId());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "La cobertura no existe.");
		problem.setTitle("No encontrada");
		problem.setType(NOT_FOUND);
		return problem;
	}

	/**
	 * El archivo no pasa la validacion de tipo o de tamano (400).
	 *
	 * <p>Lleva {@code motivo} para que la pantalla distinga "no es un tipo permitido" de "pesa
	 * demasiado" sin leer prosa en castellano: las dos llevan al mismo desenlace —elegir otro
	 * archivo— pero a mensajes distintos.
	 */
	@ExceptionHandler(ArchivoNoAceptadoException.class)
	public ProblemDetail handleArchivoNoAceptado(ArchivoNoAceptadoException exception) {
		log.debug("Archivo rechazado: motivo={}", exception.getMotivo());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setTitle("Archivo no aceptado");
		problem.setType(ARCHIVO_NO_ACEPTADO);
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/** El almacenamiento no tiene el contenido del adjunto (409, no 404). */
	@ExceptionHandler(AdjuntoNoDisponibleException.class)
	public ProblemDetail handleAdjuntoNoDisponible(AdjuntoNoDisponibleException exception) {
		log.warn("Contenido de adjunto no disponible: adjuntoId={}", exception.getAdjuntoId());

		return conflicto(
				"El contenido de este adjunto no esta disponible. Su ficha sigue siendo "
						+ "consultable.",
				"Contenido no disponible",
				ADJUNTO_NO_DISPONIBLE);
	}

	/** El adjunto ya estaba dado de baja (409). Se sigue descargando; no se reclasifica. */
	@ExceptionHandler(AdjuntoInactivoException.class)
	public ProblemDetail handleAdjuntoInactivo(AdjuntoInactivoException exception) {
		log.debug("Operacion sobre adjunto de baja: adjuntoId={}", exception.getAdjuntoId());

		return conflicto(
				"El adjunto ya estaba dado de baja. Su contenido se sigue pudiendo descargar.",
				"Adjunto dado de baja",
				ADJUNTO_INACTIVO);
	}

	@ExceptionHandler(PersonaSinPerfilPacienteException.class)
	public ProblemDetail handleSinPerfilPaciente(PersonaSinPerfilPacienteException exception) {
		log.debug("Cobertura rechazada: persona sin perfil de paciente. personaId={}",
				exception.getPersonaId());
		return conflicto(
				"La persona no es paciente todavia. Una cobertura es del paciente: activa su "
						+ "perfil antes de cargarla.",
				"No es paciente",
				PERSONA_SIN_PERFIL_PACIENTE);
	}

	@ExceptionHandler(PlanNoSeleccionableException.class)
	public ProblemDetail handlePlanNoSeleccionable(PlanNoSeleccionableException exception) {
		log.debug("Cobertura rechazada: plan no seleccionable. planId={}", exception.getPlanId());
		return conflicto(
				"Ese plan no se puede elegir para la fecha de inicio de la cobertura. Puede estar "
						+ "dado de baja, tener el financiador dado de baja, o estar fuera de "
						+ "vigencia.",
				"Plan no seleccionable",
				PLAN_NO_SELECCIONABLE);
	}

	@ExceptionHandler(CoberturaSuperpuestaException.class)
	public ProblemDetail handleCoberturaSuperpuesta(CoberturaSuperpuestaException exception) {
		log.debug("Cobertura rechazada por solapamiento");
		ProblemDetail problem = conflicto(
				"El paciente ya tiene una cobertura de ese plan vigente en ese periodo. Dos "
						+ "coberturas del mismo plan que se pisan son un duplicado; dos de "
						+ "financiadores distintos si pueden convivir.",
				"Cobertura superpuesta",
				COBERTURA_SUPERPUESTA);
		problem.setProperty("coberturaExistenteId", exception.getCoberturaExistenteId());
		return problem;
	}

	@ExceptionHandler(CoberturaPrincipalSuperpuestaException.class)
	public ProblemDetail handlePrincipalSuperpuesta(
			CoberturaPrincipalSuperpuestaException exception) {

		log.debug("Principal rechazada por solapamiento");
		ProblemDetail problem = conflicto(
				"El paciente ya tiene una cobertura principal vigente en ese periodo. Finalizala "
						+ "o desmarcala antes de elegir otra: no se cambia en silencio.",
				"Ya hay una cobertura principal",
				COBERTURA_PRINCIPAL_SUPERPUESTA);
		problem.setProperty("coberturaPrincipalId", exception.getCoberturaPrincipalId());
		return problem;
	}

	@ExceptionHandler(CoberturaInactivaException.class)
	public ProblemDetail handleCoberturaInactiva(CoberturaInactivaException exception) {
		// Dos type distintos sobre la misma excepcion: "esto ya estaba dado de baja" y "esto no se
		// puede editar porque esta de baja" son dos acciones distintas para quien las recibe.
		log.debug("Operacion sobre cobertura inactiva: coberturaId={} operacion={}",
				exception.getCoberturaId(), exception.getOperacion());

		boolean esUnaSegundaBaja = "dar de baja".equals(exception.getOperacion());
		return conflicto(
				esUnaSegundaBaja
						? "La cobertura ya estaba dada de baja."
						: "La cobertura esta dada de baja y no admite " + exception.getOperacion()
								+ ". Sigue siendo consultable.",
				"Cobertura dada de baja",
				esUnaSegundaBaja ? COBERTURA_ALREADY_INACTIVE : COBERTURA_INACTIVA);
	}


	// =================================================================================
	// Ordenes, autorizaciones y documentacion administrativa (M17, AKINE-03.06)
	// =================================================================================

	@ExceptionHandler(OrdenNotAccessibleException.class)
	public ProblemDetail handleOrdenNoAccesible(OrdenNotAccessibleException exception) {
		// Los tres casos —no existe, es de otro tenant, es de otro paciente— responden lo MISMO.
		// Distinguirlos confirmaria que ese id existe.
		log.debug("Orden medica no accesible: ordenId={}", exception.getOrdenId());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "La orden medica no existe.");
		problem.setTitle("No encontrada");
		problem.setType(NOT_FOUND);
		return problem;
	}

	@ExceptionHandler(AutorizacionNotAccessibleException.class)
	public ProblemDetail handleAutorizacionNoAccesible(
			AutorizacionNotAccessibleException exception) {

		log.debug("Autorizacion no accesible: autorizacionId={}", exception.getAutorizacionId());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.NOT_FOUND, "La autorizacion no existe.");
		problem.setTitle("No encontrada");
		problem.setType(NOT_FOUND);
		return problem;
	}

	@ExceptionHandler(OrdenInactivaException.class)
	public ProblemDetail handleOrdenInactiva(OrdenInactivaException exception) {
		// Dos type distintos sobre la misma excepcion, igual que en cobertura: "ya estaba dada de
		// baja" y "no se puede editar porque esta de baja" son dos acciones distintas para quien
		// las recibe.
		log.debug("Operacion sobre orden inactiva: ordenId={} operacion={}",
				exception.getOrdenId(), exception.getOperacion());
		boolean esUnaSegundaBaja = "dar de baja".equals(exception.getOperacion());
		return conflicto(
				esUnaSegundaBaja
						? "La orden medica ya estaba dada de baja."
						: "La orden medica esta dada de baja y no admite "
								+ exception.getOperacion() + ". Sigue siendo consultable.",
				"Orden dada de baja",
				esUnaSegundaBaja ? ORDEN_ALREADY_INACTIVE : ORDEN_INACTIVA);
	}

	@ExceptionHandler(AutorizacionInactivaException.class)
	public ProblemDetail handleAutorizacionInactiva(AutorizacionInactivaException exception) {
		log.debug("Operacion sobre autorizacion inactiva: autorizacionId={} operacion={}",
				exception.getAutorizacionId(), exception.getOperacion());
		boolean esUnaSegundaBaja = "dar de baja".equals(exception.getOperacion());
		return conflicto(
				esUnaSegundaBaja
						? "La autorizacion ya estaba dada de baja."
						: "La autorizacion esta dada de baja y no admite "
								+ exception.getOperacion() + ". Sigue siendo consultable.",
				"Autorizacion dada de baja",
				esUnaSegundaBaja ? AUTORIZACION_ALREADY_INACTIVE : AUTORIZACION_INACTIVA);
	}

	@ExceptionHandler(AutorizacionSuperpuestaException.class)
	public ProblemDetail handleAutorizacionSuperpuesta(
			AutorizacionSuperpuestaException exception) {

		log.debug("Autorizacion rechazada por solapamiento");
		ProblemDetail problem = conflicto(
				"El paciente ya tiene una autorizacion aprobada de esa practica vigente en ese "
						+ "periodo. Dos que se pisan contarian el saldo dos veces; dos "
						+ "consecutivas —renovar— si conviven.",
				"Autorizacion superpuesta",
				AUTORIZACION_SUPERPUESTA);
		problem.setProperty("autorizacionExistenteId", exception.getAutorizacionExistenteId());
		return problem;
	}

	@ExceptionHandler(AutorizacionTransicionNoPermitidaException.class)
	public ProblemDetail handleTransicionNoPermitida(
			AutorizacionTransicionNoPermitidaException exception) {

		log.debug("Transicion de autorizacion rechazada: autorizacionId={} estado={} accion={}",
				exception.getAutorizacionId(), exception.getEstadoActual(), exception.getAccion());
		ProblemDetail problem = conflicto(
				"Una autorizacion " + exception.getEstadoActual() + " no admite la accion "
						+ exception.getAccion() + ". APROBADA y RECHAZADA son terminales: "
						+ "corregir una decision tomada es dar de baja la autorizacion y cargar "
						+ "otra.",
				"Transicion no permitida",
				AUTORIZACION_TRANSICION_NO_PERMITIDA);
		problem.setProperty("estadoActual", exception.getEstadoActual());
		problem.setProperty("accion", exception.getAccion());
		return problem;
	}

	@ExceptionHandler(NumeroDeDocumentoTakenException.class)
	public ProblemDetail handleNumeroTaken(NumeroDeDocumentoTakenException exception) {
		log.debug("Numero de {} en uso", exception.getDocumento());
		ProblemDetail problem = conflicto(
				"Ya hay una " + exception.getDocumento() + " vigente con ese numero. El numero de "
						+ "una dada de baja si se puede reusar.",
				"Numero en uso",
				DOCUMENTO_NUMERO_TAKEN);
		problem.setProperty("documento", exception.getDocumento());
		if (exception.getNumero() != null) {
			problem.setProperty("numero", exception.getNumero());
		}
		return problem;
	}

	private static ProblemDetail conflicto(String detalle, String titulo, URI type) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalle);
		problem.setTitle(titulo);
		problem.setType(type);
		return problem;
	}
}
