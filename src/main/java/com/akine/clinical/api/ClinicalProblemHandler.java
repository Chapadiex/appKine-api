package com.akine.clinical.api;

import com.akine.clinical.domain.exception.AccesoClinicoNoJustificadoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoInactivoException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNoDisponibleException;
import com.akine.clinical.domain.exception.AdjuntoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.AntecedenteNotAccessibleException;
import com.akine.clinical.domain.exception.AutorizacionNoElegibleException;
import com.akine.clinical.domain.exception.DerivacionNotAccessibleException;
import com.akine.clinical.domain.exception.ArchivoClinicoNoAceptadoException;
import com.akine.clinical.domain.exception.CasoClinicoCerradoException;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.CasoClinicoPosibleDuplicadoException;
import com.akine.clinical.domain.exception.AutorizacionNoVinculableException;
import com.akine.clinical.domain.exception.CasoNoActivoException;
import com.akine.clinical.domain.exception.CierreDeCasoSinMotivoException;
import com.akine.clinical.domain.exception.CursorInvalidoException;
import com.akine.clinical.domain.exception.EnmiendaSinMotivoException;
import com.akine.clinical.domain.exception.EntradaClinicaInactivaException;
import com.akine.clinical.domain.exception.EntradaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.OfertaNoHabilitadaException;
import com.akine.clinical.domain.exception.OfertaNoVigenteException;
import com.akine.clinical.domain.exception.PacienteSinPerfilVigenteException;
import com.akine.clinical.domain.exception.PlanNoEditableException;
import com.akine.clinical.domain.exception.PlanTratamientoNotAccessibleException;
import com.akine.clinical.domain.exception.PlanVivoEnElCasoException;
import com.akine.clinical.domain.exception.ReferenciaDelPlanNotAccessibleException;
import com.akine.clinical.domain.exception.TransicionDePlanInvalidaException;
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
	private static final URI CASO_NO_ACCESIBLE = ProblemType.CASO_CLINICO_NO_ACCESIBLE.uri();
	private static final URI CASO_CERRADO = ProblemType.CASO_CLINICO_CERRADO.uri();
	private static final URI CASO_POSIBLE_DUPLICADO =
			ProblemType.CASO_CLINICO_POSIBLE_DUPLICADO.uri();
	private static final URI CASO_SIN_MOTIVO = ProblemType.CASO_SIN_MOTIVO_DE_CIERRE.uri();
	private static final URI OFERTA_NO_VIGENTE = ProblemType.OFERTA_NO_VIGENTE.uri();
	private static final URI PLAN_NO_ACCESIBLE = ProblemType.PLAN_NO_ACCESIBLE.uri();
	private static final URI PLAN_NO_EDITABLE = ProblemType.PLAN_NO_EDITABLE.uri();
	private static final URI PLAN_TRANSICION_INVALIDA =
			ProblemType.PLAN_TRANSICION_INVALIDA.uri();
	private static final URI PLAN_VIVO_EN_EL_CASO = ProblemType.PLAN_VIVO_EN_EL_CASO.uri();
	private static final URI CASO_NO_ACTIVO = ProblemType.CASO_NO_ACTIVO.uri();
	private static final URI AUTORIZACION_SIN_SALDO = ProblemType.AUTORIZACION_SIN_SALDO.uri();
	private static final URI AUTORIZACION_VENCIDA = ProblemType.AUTORIZACION_VENCIDA.uri();
	private static final URI OFERTA_NO_HABILITADA = ProblemType.OFERTA_NO_HABILITADA.uri();
	private static final URI AUTORIZACION_NO_ELEGIBLE = ProblemType.AUTORIZACION_NO_ELEGIBLE.uri();
	private static final URI DERIVACION_NO_ACCESIBLE = ProblemType.DERIVACION_NO_ACCESIBLE.uri();

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

	// =================================================================================
	// Caso Clinico (M10, AKINE-04.03)
	// =================================================================================

	/**
	 * <b>404.</b> Mismo criterio que la entrada clinica, y en el caso pesa igual: "no existe", "es
	 * de otro tenant" y "su historia no es accesible" son indistinguibles a proposito. Un 403
	 * confirmaria que la fila existe, y probar ids consecutivos alcanzaria para censar cuantos
	 * casos clinicos tiene otro centro del SaaS.
	 *
	 * <p>Tambien lo emite el filtro por caso del timeline cuando el caso no es de esa historia: una
	 * pagina vacia diria "este caso no tiene hechos" sobre un caso que no es del paciente, y son
	 * dos situaciones distintas que llevan a acciones distintas.
	 */
	@ExceptionHandler(CasoClinicoNotAccessibleException.class)
	public ProblemDetail handleCasoNoAccesible(CasoClinicoNotAccessibleException ex) {
		log.debug("Caso clinico no accesible: casoClinicoId={}", ex.getCasoClinicoId());
		return noEncontrado(CASO_NO_ACCESIBLE, "Caso clinico no encontrado",
				"El caso clinico no existe.");
	}

	/**
	 * <b>409, y no 404 ni 403.</b> El caso existe y se sigue leyendo entero con todo su historial
	 * —eso distingue "termino" de "no existio"— y quien opera si tiene {@code hc:write}: lo que no
	 * admite cambios es el estado.
	 *
	 * <p>La accion que la pantalla tiene que ofrecer es <b>reabrir con motivo</b>, que queda en el
	 * historial (RF-M10-006). Editar en silencio un caso terminado es historia clinica reescrita, y
	 * ADR-0011 lo prohibe.
	 */
	@ExceptionHandler(CasoClinicoCerradoException.class)
	public ProblemDetail handleCasoCerrado(CasoClinicoCerradoException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(CASO_CERRADO);
		problem.setTitle("El caso clinico esta cerrado");
		problem.setProperty("casoClinicoId", ex.getCasoClinicoId());
		return problem;
	}

	/**
	 * <b>409 con los candidatos.</b> Es la advertencia de RN-M10-002, no un invariante.
	 *
	 * <p>Lleva {@code candidatos} —los ids de los casos activos que coinciden— porque sin ellos el
	 * 409 seria un callejon: el profesional sabe que "ya hay algo parecido" y no puede verlo. Con la
	 * lista, la pantalla muestra los casos y el profesional elige entre abrir uno o reenviar el alta
	 * con {@code confirmaPosibleDuplicado}. Mismo mecanismo, y misma pantalla, que el alta de
	 * Persona de 03.01.
	 *
	 * <p>Los ids no filtran nada: quien recibe esto ya tiene {@code hc:write} sobre esa historia.
	 */
	@ExceptionHandler(CasoClinicoPosibleDuplicadoException.class)
	public ProblemDetail handleCasoPosibleDuplicado(CasoClinicoPosibleDuplicadoException ex) {
		log.debug("Alta de caso detenida por posible duplicado: candidatos={}",
				ex.getCandidatos().size());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"Ya hay un caso activo de esa oferta en esta historia. Revisalo antes de abrir "
						+ "otro; si es un problema distinto, reenvia el alta confirmando el "
						+ "posible duplicado.");
		problem.setType(CASO_POSIBLE_DUPLICADO);
		problem.setTitle("Posible caso duplicado");
		problem.setProperty("candidatos", ex.getCandidatos());
		return problem;
	}

	/**
	 * <b>400 y no 409.</b> No hay conflicto de estado: el caso esta como tiene que estar y el actor
	 * tiene permiso. Falta un dato del pedido, y un 409 mandaria al profesional a reintentar el
	 * mismo cuerpo, que va a fallar exactamente igual. Mismo reparto que la enmienda sin motivo.
	 */
	@ExceptionHandler(CierreDeCasoSinMotivoException.class)
	public ProblemDetail handleCierreSinMotivo(CierreDeCasoSinMotivoException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setType(CASO_SIN_MOTIVO);
		problem.setTitle("El cierre de un caso exige un motivo");
		return problem;
	}

	/**
	 * <b>409 y no 404.</b> La oferta existe y quien la eligio la esta viendo en una lista; un 404
	 * mandaria a la pantalla a decir "no encontrada" sobre algo que el usuario tiene delante. Lo
	 * que corresponde ofrecerle es reactivarla o elegir otra. Mismo criterio que
	 * {@code oferta-no-agendable} en M12.
	 */
	@ExceptionHandler(OfertaNoVigenteException.class)
	public ProblemDetail handleOfertaNoVigente(OfertaNoVigenteException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(OFERTA_NO_VIGENTE);
		problem.setTitle("La oferta no esta vigente");
		problem.setProperty("ofertaId", ex.getOfertaId());
		return problem;
	}

	// =================================================================================
	// Plan de Tratamiento (M11, AKINE-04.04)
	// =================================================================================

	/**
	 * <b>404.</b> Mismo criterio que el caso clinico: "no existe", "es de otro tenant" y "su caso o
	 * su historia no resuelven" son indistinguibles a proposito. Probar ids consecutivos no puede
	 * servir para censar cuantos tratamientos tiene en curso otro centro del SaaS.
	 */
	@ExceptionHandler(PlanTratamientoNotAccessibleException.class)
	public ProblemDetail handlePlanNoAccesible(PlanTratamientoNotAccessibleException ex) {
		log.debug("Plan de tratamiento no accesible: planId={}", ex.getPlanId());
		return noEncontrado(PLAN_NO_ACCESIBLE, "Plan de tratamiento no encontrado",
				"El plan de tratamiento no existe.");
	}

	/**
	 * <b>409, y no 404 ni 403.</b> El plan existe y se sigue leyendo entero con todas sus versiones
	 * —eso distingue "termino" de "no existio"— y quien opera si tiene {@code hc:write}: lo que no
	 * admite cambios es el estado.
	 *
	 * <p>La accion que la pantalla tiene que ofrecer es <b>crear un plan nuevo</b>: un plan
	 * finalizado no se reabre. Modificar en silencio un tratamiento terminado es historia clinica
	 * reescrita, y ADR-0011 lo prohibe.
	 */
	@ExceptionHandler(PlanNoEditableException.class)
	public ProblemDetail handlePlanNoEditable(PlanNoEditableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(PLAN_NO_EDITABLE);
		problem.setTitle("El plan de tratamiento no admite cambios");
		problem.setProperty("planId", ex.getPlanId());
		problem.setProperty("estado", ex.getEstado());
		return problem;
	}

	/**
	 * <b>409.</b> El caso tiene un plan suspendido que todavia ocupa el lugar del vigente.
	 *
	 * <p>No es un {@code plan-transicion-invalida}: el plan que se quiere activar esta en un
	 * estado que admite la transicion perfectamente, y lo que la frena es OTRO plan. Publicarlo
	 * como transicion invalida mandaria a la pantalla a hablar del plan equivocado.
	 */
	@ExceptionHandler(PlanVivoEnElCasoException.class)
	public ProblemDetail handlePlanVivoEnElCaso(PlanVivoEnElCasoException exception) {
		log.debug("Plan vivo en el caso: planQueOcupaId={}", exception.getPlanQueOcupaId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(PLAN_VIVO_EN_EL_CASO);
		problem.setTitle("El caso ya tiene un plan vivo");
		problem.setProperty("planQueOcupaId", exception.getPlanQueOcupaId());
		problem.setProperty("numeroDelPlanQueOcupa", exception.getNumeroDelPlanQueOcupa());
		return problem;
	}

	/**
	 * <b>409 y no 400.</b> El cuerpo del pedido esta bien formado; lo que no encaja es el estado, y
	 * la accion que corresponde es releer el plan —puede haber cambiado desde que se dibujo la
	 * pantalla— y no corregir un campo.
	 *
	 * <p>Lleva {@code estadoActual} porque sin el la pantalla no puede decidir que ofrecer: desde
	 * BORRADOR se activa, desde SUSPENDIDO se reanuda, y desde FINALIZADO no se hace nada salvo
	 * crear un plan nuevo.
	 */
	@ExceptionHandler(TransicionDePlanInvalidaException.class)
	public ProblemDetail handleTransicionInvalida(TransicionDePlanInvalidaException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(PLAN_TRANSICION_INVALIDA);
		problem.setTitle("Transicion de plan no permitida");
		problem.setProperty("planId", ex.getPlanId());
		problem.setProperty("estadoActual", ex.getEstadoActual());
		return problem;
	}

	/**
	 * <b>409.</b> El caso existe y se lee entero; lo que no admite es un plan de tratamiento nuevo o
	 * uno que pase a vigente. Un tratamiento para un problema que el centro dio por terminado es un
	 * tratamiento sin problema que tratar.
	 *
	 * <p>Es un {@code type} propio y no {@code caso-clinico-cerrado} porque lleva a otra accion: la
	 * pantalla tiene que ofrecer <b>reabrir el caso con motivo</b> antes de planificar.
	 */
	@ExceptionHandler(CasoNoActivoException.class)
	public ProblemDetail handleCasoNoActivo(CasoNoActivoException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(CASO_NO_ACTIVO);
		problem.setTitle("El caso clinico no esta activo");
		problem.setProperty("casoClinicoId", ex.getCasoClinicoId());
		return problem;
	}

	/**
	 * <b>409 y no 404</b>, mismo criterio que la oferta no vigente del alta de caso.
	 *
	 * <p>Lleva {@code ofertaId} y eso es lo que lo distingue de aquel: quien arma un plan carga
	 * varias practicas de una vez, asi que la pantalla tiene que poder señalar cual de todas es la
	 * que no entra en vez de rechazar el formulario entero sin decir por que.
	 */
	@ExceptionHandler(OfertaNoHabilitadaException.class)
	public ProblemDetail handleOfertaNoHabilitada(OfertaNoHabilitadaException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(OFERTA_NO_HABILITADA);
		problem.setTitle("La oferta no esta habilitada");
		problem.setProperty("ofertaId", ex.getOfertaId());
		return problem;
	}

	// =================================================================================
	// Vinculo con las autorizaciones de M17 (RF-M11-007, AKINE-04.05)
	// =================================================================================

	/**
	 * El item o la autorizacion que el pedido nombro no son alcanzables (404).
	 *
	 * <p>Cinco casos en una sola respuesta: el item no es de la version vigente, y la autorizacion
	 * no existe, es de otro tenant, es de <b>otro paciente</b> o esta dada de baja. Distinguirlos
	 * confirmaria que ese id existe, y el caso "es de otro paciente" es ademas el que publicaria en
	 * esta ficha un numero de autorizacion ajeno.
	 */
	@ExceptionHandler(ReferenciaDelPlanNotAccessibleException.class)
	public ProblemDetail handleReferenciaNoAccesible(ReferenciaDelPlanNotAccessibleException ex) {
		return noEncontrado(NOT_FOUND, "No encontrado", ex.getMessage() + ".");
	}

	/**
	 * La autorizacion existe y no habilita hoy (409).
	 *
	 * <p><b>Emite el mismo {@code type} que {@code person}</b> aunque la excepcion sea de este
	 * modulo: los {@code ProblemType} viven en {@code platform.spi} y se comparten, mientras que
	 * las excepciones no cruzan el borde del modulo (regla de 01.01). El cliente ve
	 * {@code autorizacion-vencida} o {@code autorizacion-sin-saldo} venga de donde venga.
	 *
	 * <p>El reparto: falta de saldo va a {@code autorizacion-sin-saldo} porque la accion correctiva
	 * es pedir una ampliacion; todo lo demas —vencida, aun no vigente, no aprobada— va a
	 * {@code autorizacion-vencida}, donde la accion es renovar o esperar la respuesta del
	 * financiador. {@code motivo} viaja con el vocabulario cerrado que ya publica el listado de
	 * elegibles, para que la pantalla diga exactamente cual de los cuatro es.
	 */
	@ExceptionHandler(AutorizacionNoVinculableException.class)
	public ProblemDetail handleAutorizacionNoVinculable(AutorizacionNoVinculableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT,
				"La autorizacion no habilita hoy y no se puede vincular al plan: "
						+ ex.getMotivo() + ". Vencida y agotada no son estados guardados: se "
						+ "calculan contra el dia en que se pregunta.");
		problem.setType(ex.esFaltaDeSaldo() ? AUTORIZACION_SIN_SALDO : AUTORIZACION_VENCIDA);
		problem.setTitle("Autorizacion no vinculable");
		problem.setProperty("autorizacionId", ex.getAutorizacionId());
		problem.setProperty("motivo", ex.getMotivo());
		return problem;
	}

	// =================================================================================
	// Derivacion de participante al circuito clinico (RF-M28-008, AKINE-08.04)
	// =================================================================================

	/**
	 * La derivacion no existe o es de otro tenant (404).
	 *
	 * <p>Los dos casos colapsan a proposito: un 403 confirmaria que existe, y con ids consecutivos
	 * se enumeran las derivaciones del sistema. Cross-tenant es 404, nunca 403.
	 */
	@ExceptionHandler(DerivacionNotAccessibleException.class)
	public ProblemDetail handleDerivacionNoAccesible(DerivacionNotAccessibleException ex) {
		return noEncontrado(DERIVACION_NO_ACCESIBLE, "Derivacion no encontrada",
				"La derivacion no existe o no es accesible.");
	}

	/**
	 * La autorizacion declarada no habilita ese dia (409).
	 *
	 * <p><b>409 y no 404</b>: existe, es de ese paciente y es de ese tenant —los cuatro casos en
	 * que no lo es ya colapsaron a vacio dentro de {@code AutorizacionDirectory}—. Lo que pasa es
	 * que no sirve, y el operador tiene dos salidas: pedir otra autorizacion, o derivar sin
	 * declararla. {@code motivo} viaja calculado por {@code person}, no recalculado aca.
	 */
	@ExceptionHandler(AutorizacionNoElegibleException.class)
	public ProblemDetail handleAutorizacionNoElegible(AutorizacionNoElegibleException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(AUTORIZACION_NO_ELEGIBLE);
		problem.setTitle("La autorizacion no habilita");
		problem.setProperty("autorizacionId", ex.getAutorizacionId());
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
