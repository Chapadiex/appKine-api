package com.akine.clinical.api;

import com.akine.clinical.domain.exception.AccesoClinicoNoJustificadoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoInactivoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNoDisponibleException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.AntecedenteNotAccessibleException;
import com.akine.clinical.domain.exception.ArchivoClinicoNoAceptadoException;
import com.akine.clinical.domain.exception.CursorInvalidoException;
import com.akine.clinical.domain.exception.EnmiendaSinMotivoException;
import com.akine.clinical.domain.exception.EntradaClinicaInactivaException;
import com.akine.clinical.domain.exception.EntradaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.PacienteSinPerfilVigenteException;
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
 * Traduce las excepciones de {@code clinical} a Problem Details (RFC 7807, ADR-0005).
 *
 * <h2>Por que vive aca y no en {@code GlobalExceptionHandler}</h2>
 *
 * <p>Cada modulo mapea las suyas en su propio advice. Hacerlo en el global obligaria a
 * {@code platform.api} a importar {@code clinical.domain}, cerrando un ciclo entre modulos que
 * ArchUnit rechaza. Es la regla que 01.01 dejo fijada y que {@code EncounterProblemHandler} y
 * {@code PersonProblemHandler} ya aplican.
 *
 * <h2>La linea que este modulo no cruza: nada de lo que se loguea es clinico</h2>
 *
 * <p>Ningun handler de aca escribe un cuerpo de entrada, un titulo de adjunto ni una
 * justificacion en el log. Los ids si: sin ellos no hay forma de investigar un incidente. El
 * texto que un profesional escribe sobre un paciente, no — el log se consulta con permisos de
 * operacion y no con permisos clinicos, y un detalle filtrado ahi es una via de lectura clinica
 * que ninguna auditoria registra.
 *
 * <h2>El reparto de status, en una tabla</h2>
 *
 * <ul>
 *   <li><b>404</b> — historia, entrada, adjunto o antecedente fuera del alcance. "No existe" y
 *       "es de otro tenant" son indistinguibles a proposito.</li>
 *   <li><b>403</b> — acceso sin justificar. Nunca 401: el interceptor del frontend borra el token
 *       ante cualquier 401 y entra en bucle de login.</li>
 *   <li><b>409</b> — estados que la operacion no admite: entrada o adjunto de baja, contenido
 *       perdido, persona sin perfil de paciente.</li>
 *   <li><b>400</b> — datos del pedido: cursor roto, enmienda sin motivo, archivo rechazado.</li>
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClinicalProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(ClinicalProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI FORBIDDEN = ProblemType.FORBIDDEN.uri();
	private static final URI ENTRADA_NO_ACCESIBLE = ProblemType.ENTRADA_CLINICA_NO_ACCESIBLE.uri();
	private static final URI ENTRADA_INACTIVA = ProblemType.ENTRADA_CLINICA_INACTIVA.uri();
	private static final URI ENMIENDA_SIN_MOTIVO = ProblemType.ENMIENDA_SIN_MOTIVO.uri();
	private static final URI ADJUNTO_NO_ACCESIBLE = ProblemType.ADJUNTO_CLINICO_NO_ACCESIBLE.uri();
	private static final URI ADJUNTO_INACTIVO = ProblemType.ADJUNTO_CLINICO_INACTIVO.uri();
	private static final URI ADJUNTO_NO_DISPONIBLE =
			ProblemType.ADJUNTO_CLINICO_NO_DISPONIBLE.uri();
	private static final URI CURSOR_INVALIDO = ProblemType.CURSOR_INVALIDO.uri();
	private static final URI ARCHIVO_NO_ACEPTADO = ProblemType.ARCHIVO_NO_ACEPTADO.uri();
	private static final URI PERSONA_SIN_PERFIL = ProblemType.PERSONA_SIN_PERFIL_PACIENTE.uri();

	// =================================================================================
	// 404 — fuera del alcance del actor
	// =================================================================================

	/**
	 * <b>404 y nunca 403</b>, aunque la causa real haya sido cross-tenant.
	 *
	 * <p>Un 403 confirma que el recurso existe, y probar ids consecutivos alcanzaria para censar
	 * las historias clinicas de otro centro del SaaS. En un modulo clinico eso deja de ser un
	 * problema de aislamiento y pasa a ser uno de privacidad.
	 */
	@ExceptionHandler(HistoriaClinicaNotAccessibleException.class)
	public ProblemDetail handleHistoriaNoAccesible(HistoriaClinicaNotAccessibleException ex) {
		log.debug("Historia clinica no accesible: referencia={}", ex.getReferencia());
		return noEncontrado(NOT_FOUND, "Recurso no encontrado",
				"La historia clinica no existe.");
	}

	/** <b>404.</b> Mismo criterio; el {@code type} propio distingue QUE falto, no por que. */
	@ExceptionHandler(EntradaClinicaNotAccessibleException.class)
	public ProblemDetail handleEntradaNoAccesible(EntradaClinicaNotAccessibleException ex) {
		log.debug("Entrada clinica no accesible: entradaClinicaId={}", ex.getEntradaClinicaId());
		return noEncontrado(ENTRADA_NO_ACCESIBLE, "Entrada clinica no encontrada",
				"La entrada clinica no existe.");
	}

	/**
	 * <b>404.</b> Tambien cubre el alta que apunta a una entrada de OTRA historia.
	 *
	 * <p>Podria ser un 400 —el dato es incoherente— y es 404 deliberadamente: un 400 distinguiria
	 * "esa entrada no existe" de "esa entrada existe pero es de otro paciente".
	 */
	@ExceptionHandler(AdjuntoClinicoNotAccessibleException.class)
	public ProblemDetail handleAdjuntoNoAccesible(AdjuntoClinicoNotAccessibleException ex) {
		log.debug("Adjunto clinico no accesible: adjuntoId={}", ex.getAdjuntoId());
		return noEncontrado(ADJUNTO_NO_ACCESIBLE, "Adjunto clinico no encontrado",
				"El adjunto clinico no existe.");
	}

	/** <b>404.</b> Los antecedentes son de 04.01 y no tenian advice porque no tenian API. */
	@ExceptionHandler(AntecedenteNotAccessibleException.class)
	public ProblemDetail handleAntecedenteNoAccesible(AntecedenteNotAccessibleException ex) {
		log.debug("Antecedente clinico no accesible: antecedenteId={}", ex.getAntecedenteId());
		return noEncontrado(NOT_FOUND, "Recurso no encontrado",
				"El antecedente clinico no existe.");
	}

	// =================================================================================
	// 403 — la tercera condicion de DP-03
	// =================================================================================

	/**
	 * <b>403, y con una propiedad que cambia lo que la pantalla puede hacer.</b>
	 *
	 * <p>Quien opera <b>si</b> tiene {@code hc:read} o {@code hc:write}: lo que no tiene es
	 * relacion asistencial con ese paciente ni un motivo declarado. Un 403 pelado mandaria al
	 * usuario a pedirle a su administrador un permiso que ya tiene; con
	 * {@code requiereJustificacion} la pantalla sabe que lo que corresponde es pedir el motivo y
	 * reintentar el mismo pedido con la cabecera puesta.
	 *
	 * <p>No se publica un {@code type} propio: el diseño de la etapa enumera los siete
	 * {@code type} nuevos y este no esta entre ellos, y agregar uno al catalogo es una decision de
	 * contrato que no le toca tomar a la capa HTTP. La propiedad extra da la misma informacion sin
	 * ampliar el enum publicado.
	 *
	 * <p><b>403 y nunca 401.</b> La sesion es valida; el interceptor del frontend borra el token
	 * ante cualquier 401 y entraria en bucle de login.
	 */
	@ExceptionHandler(AccesoClinicoNoJustificadoException.class)
	public ProblemDetail handleAccesoNoJustificado(AccesoClinicoNoJustificadoException ex) {
		log.info("Acceso clinico sin relacion asistencial ni justificacion: personaId={}",
				ex.getPersonaId());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.FORBIDDEN,
				"El acceso a esta historia clinica exige declarar un motivo.");
		problem.setType(FORBIDDEN);
		problem.setTitle("Acceso clinico sin justificar");
		problem.setProperty("requiereJustificacion", true);
		problem.setProperty("cabecera", AccesoClinicoHeaders.JUSTIFICACION);
		return problem;
	}

	// =================================================================================
	// 409 — estados que la operacion no admite
	// =================================================================================

	/**
	 * <b>409 y no 404.</b> La entrada existe y se sigue consultando por su id — eso es lo que
	 * distingue "no lo muestres" de "no existio". Lo que no admite es contenido nuevo: una
	 * enmienda sobre una entrada de baja produciria una version que nadie va a leer, porque la
	 * entrada ya salio del timeline. Para dejar constancia se registra una entrada nueva.
	 */
	@ExceptionHandler(EntradaClinicaInactivaException.class)
	public ProblemDetail handleEntradaInactiva(EntradaClinicaInactivaException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(ENTRADA_INACTIVA);
		problem.setTitle("La entrada clinica esta dada de baja");
		return problem;
	}

	/**
	 * <b>409.</b> Se sigue descargando y se sigue listando; lo que no admite un adjunto de baja es
	 * reclasificarse. Negar la descarga convertiria la baja logica en un borrado con otro nombre.
	 */
	@ExceptionHandler(AdjuntoClinicoInactivoException.class)
	public ProblemDetail handleAdjuntoInactivo(AdjuntoClinicoInactivoException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(ADJUNTO_INACTIVO);
		problem.setTitle("El adjunto clinico esta dado de baja");
		return problem;
	}

	/**
	 * <b>409 y no 404.</b> La metadata existe y quien pregunta la esta viendo en la lista. Un 404
	 * le diria al profesional que el estudio no existe y lo empujaria a pedirselo de nuevo al
	 * paciente bajo una historia que todavia afirma tenerlo.
	 */
	@ExceptionHandler(AdjuntoClinicoNoDisponibleException.class)
	public ProblemDetail handleAdjuntoNoDisponible(AdjuntoClinicoNoDisponibleException ex) {
		log.warn("Contenido de adjunto clinico ausente en el almacenamiento: adjuntoId={}",
				ex.getAdjuntoId());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(ADJUNTO_NO_DISPONIBLE);
		problem.setTitle("El contenido del adjunto no esta disponible");
		return problem;
	}

	/**
	 * <b>409.</b> RF-M07-010: una Persona no es un Paciente. La persona existe y el actor puede
	 * verla; lo que no admite la operacion es su estado, asi que la pantalla tiene que ofrecer
	 * activar el perfil como una accion propia y no decir "no encontrado".
	 */
	@ExceptionHandler(PacienteSinPerfilVigenteException.class)
	public ProblemDetail handlePacienteSinPerfil(PacienteSinPerfilVigenteException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(PERSONA_SIN_PERFIL);
		problem.setTitle("La persona no tiene perfil de paciente vigente");
		problem.setProperty("personaId", ex.getPersonaId());
		return problem;
	}

	// =================================================================================
	// 400 — datos del pedido
	// =================================================================================

	/**
	 * <b>400 y no 409.</b> No hay conflicto de estado: la entrada esta vigente y el actor tiene
	 * permiso. Falta un dato del pedido, y un 409 mandaria al profesional a reintentar el mismo
	 * cuerpo, que va a fallar exactamente igual.
	 */
	@ExceptionHandler(EnmiendaSinMotivoException.class)
	public ProblemDetail handleEnmiendaSinMotivo(EnmiendaSinMotivoException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setType(ENMIENDA_SIN_MOTIVO);
		problem.setTitle("La enmienda exige un motivo");
		return problem;
	}

	/**
	 * <b>400.</b> El cursor es opaco y la unica forma legitima de obtener uno es haber leido la
	 * pagina anterior. No se degrada a "primera pagina": contestar la primera ante un cursor roto
	 * haria que un cliente con un bug de paginacion recorriera la misma pagina para siempre sin
	 * que nadie lo note.
	 *
	 * <p>El cursor recibido no se loguea: es entrada del cliente.
	 */
	@ExceptionHandler(CursorInvalidoException.class)
	public ProblemDetail handleCursorInvalido(CursorInvalidoException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setType(CURSOR_INVALIDO);
		problem.setTitle("Cursor de paginacion invalido");
		return problem;
	}

	/**
	 * <b>400 y no 415.</b> El tipo declarado del request es correcto —es
	 * {@code multipart/form-data}— y lo que se rechaza es el CONTENIDO de una de sus partes.
	 *
	 * <p>Un solo {@code type} para las dos familias, con {@code motivo} como propiedad extra: para
	 * la pantalla el desenlace es el mismo, decir por que ese archivo no entra y pedir otro.
	 */
	@ExceptionHandler(ArchivoClinicoNoAceptadoException.class)
	public ProblemDetail handleArchivoNoAceptado(ArchivoClinicoNoAceptadoException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setType(ARCHIVO_NO_ACEPTADO);
		problem.setTitle("El archivo no se puede cargar");
		problem.setProperty("motivo", ex.getMotivo());
		return problem;
	}

	private static ProblemDetail noEncontrado(URI type, String titulo, String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(type);
		problem.setTitle(titulo);
		return problem;
	}
}
