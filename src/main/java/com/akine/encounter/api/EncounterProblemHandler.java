package com.akine.encounter.api;

import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.CierreIncompletoException;
import com.akine.encounter.domain.exception.EnmiendaSinMotivoException;
import com.akine.encounter.domain.exception.EspacioNoAccesibleException;
import com.akine.encounter.domain.exception.EspacioNoOperableException;
import com.akine.encounter.domain.exception.ParametroInvalidoException;
import com.akine.encounter.domain.exception.PracticaNoUtilizableException;
import com.akine.encounter.domain.exception.ProfesionalNoAsignableException;
import com.akine.encounter.domain.exception.TratamientoNoAccesibleException;
import com.akine.encounter.domain.exception.EvaluacionIncoherenteException;
import com.akine.encounter.domain.exception.MedicionDefinicionInactivaException;
import com.akine.encounter.domain.exception.MedicionDefinicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionFueraDeRangoException;
import com.akine.encounter.domain.exception.MedicionNoAccesibleException;
import com.akine.encounter.domain.exception.MedicionTipoIncompatibleException;
import com.akine.encounter.domain.exception.EnmiendaCambiaPracticasException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNoCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.domain.exception.TurnoNoAtendibleException;
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
 * Traduce las excepciones de {@code encounter} a Problem Details (RFC 7807, ADR-0005).
 *
 * <p>Cada modulo mapea las suyas en su propio advice y nunca en {@code GlobalExceptionHandler}:
 * hacerlo alli obligaria a {@code platform.api} a importar {@code encounter.domain}, cerrando un
 * ciclo entre modulos. Es la regla que 01.01 dejo fijada.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EncounterProblemHandler {

	private static final Logger log = LoggerFactory.getLogger(EncounterProblemHandler.class);

	private static final URI NOT_FOUND = ProblemType.NOT_FOUND.uri();
	private static final URI TURNO_NO_ATENDIBLE = ProblemType.TURNO_NO_ATENDIBLE.uri();
	private static final URI SESION_AJENA = ProblemType.SESION_AJENA.uri();
	private static final URI VALIDATION_ERROR = ProblemType.VALIDATION_ERROR.uri();
	private static final URI SESION_CERRADA = ProblemType.SESION_CERRADA.uri();
	private static final URI SESION_NO_CERRADA = ProblemType.SESION_NO_CERRADA.uri();
	private static final URI ENMIENDA_SIN_MOTIVO = ProblemType.ENMIENDA_SIN_MOTIVO.uri();
	private static final URI ENMIENDA_CAMBIA_PRACTICAS =
			ProblemType.ENMIENDA_CAMBIA_PRACTICAS.uri();
	private static final URI CASO_NO_ACCESIBLE = ProblemType.CASO_CLINICO_NO_ACCESIBLE.uri();
	private static final URI CASO_CERRADO = ProblemType.CASO_CLINICO_CERRADO.uri();
	private static final URI MEDICION_DEFINICION_NO_ACCESIBLE =
			ProblemType.MEDICION_DEFINICION_NO_ACCESIBLE.uri();
	private static final URI MEDICION_DEFINICION_INACTIVA =
			ProblemType.MEDICION_DEFINICION_INACTIVA.uri();
	private static final URI MEDICION_FUERA_DE_RANGO = ProblemType.MEDICION_FUERA_DE_RANGO.uri();
	private static final URI MEDICION_TIPO_INCOMPATIBLE =
			ProblemType.MEDICION_TIPO_INCOMPATIBLE.uri();
	private static final URI TRATAMIENTO_NO_ACCESIBLE = ProblemType.TRATAMIENTO_NO_ACCESIBLE.uri();
	private static final URI PRACTICA_NO_UTILIZABLE = ProblemType.PRACTICA_NO_UTILIZABLE.uri();
	private static final URI ESPACIO_NO_OPERABLE = ProblemType.ESPACIO_NO_OPERABLE.uri();
	private static final URI PROFESIONAL_NO_ASIGNABLE = ProblemType.PROFESIONAL_NO_ASIGNABLE.uri();
	private static final URI PARAMETRO_INVALIDO = ProblemType.PARAMETRO_INVALIDO.uri();

	/**
	 * El caso no habilita esta atencion (04.03). <b>404 o 409 segun el motivo.</b>
	 *
	 * <p>"No existe" y "es de otra historia" son <b>404 e indistinguibles a proposito</b>:
	 * distinguirlas permitiria censar por ids los casos de otro paciente o de otro centro, que en
	 * un modulo clinico deja de ser aislamiento y pasa a ser privacidad.
	 *
	 * <p>"Esta cerrado" es <b>409</b> y tiene que ser otra cosa porque lleva a otra accion: el caso
	 * existe, es del paciente, y lo que corresponde es reabrirlo con motivo — no buscar otro.
	 *
	 * <p>El {@code type} sale del catalogo de {@code platform.spi.problem}, que es unico para toda
	 * la API: el cliente recibe el mismo {@code caso-clinico-cerrado} lo emita este modulo o
	 * {@code clinical}, y maneja una sola respuesta por situacion. La <b>excepcion</b>, en cambio,
	 * es de {@code encounter}, porque quien rechaza es este modulo: el spi de {@code clinical}
	 * responde y no autoriza.
	 */
	@ExceptionHandler(CasoNoAsignableException.class)
	public ProblemDetail handleCasoNoAsignable(CasoNoAsignableException exception) {
		log.debug("Caso clinico no asignable a la atencion: motivo={}", exception.getMotivo());

		if (exception.getMotivo() == CasoNoAsignableException.Motivo.CERRADO) {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
					"El caso clinico esta cerrado y no admite sesiones nuevas. Reabrilo con un "
							+ "motivo si la atencion corresponde a ese caso.");
			problem.setType(CASO_CERRADO);
			problem.setTitle("El caso clinico esta cerrado");
			problem.setProperty("casoId", exception.getCasoId());
			return problem;
		}

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
				"El caso clinico no existe.");
		problem.setType(CASO_NO_ACCESIBLE);
		problem.setTitle("Caso clinico no encontrado");
		return problem;
	}

	@ExceptionHandler(ConsultorioNoAccesibleException.class)
	public ProblemDetail handleConsultorioNoAccesible(ConsultorioNoAccesibleException exception) {
		log.debug("Consultorio no accesible desde la atencion: consultorioId={}",
				exception.getConsultorioId());
		return noEncontrado("El consultorio no existe.");
	}

	@ExceptionHandler(SesionNotAccessibleException.class)
	public ProblemDetail handleSesionNoAccesible(SesionNotAccessibleException exception) {
		log.debug("Sesion no accesible: sesionId={}", exception.getSesionId());
		return noEncontrado("La sesion no existe.");
	}

	@ExceptionHandler(TurnoNoAtendibleException.class)
	public ProblemDetail handleTurnoNoAtendible(TurnoNoAtendibleException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(TURNO_NO_ATENDIBLE);
		problem.setTitle("El turno no habilita una atencion");
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/**
	 * <b>409 y no 403, y la diferencia importa.</b> Quien opera SI tiene {@code sesion:register} en
	 * esa sede; lo que no tiene es la propiedad de esta atencion. Un 403 mandaria a la pantalla a
	 * decir "no tenes permiso", que es falso, y mandaria al usuario a pedir un permiso que ya tiene.
	 */
	@ExceptionHandler(SesionAjenaException.class)
	public ProblemDetail handleSesionAjena(SesionAjenaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(SESION_AJENA);
		problem.setTitle("La atencion la registra otro profesional");
		return problem;
	}

	/**
	 * <b>400 y no 409.</b> Un dolor de 12 en una escala de 0 a 10 es un problema del CUERPO
	 * enviado, no del estado del servidor: no depende de nada que pueda cambiar entre dos
	 * peticiones, asi que un 409 —que sugiere reintentar— mandaria al cliente a repetir algo que
	 * va a fallar igual.
	 */
	@ExceptionHandler(EvaluacionIncoherenteException.class)
	public ProblemDetail handleEvaluacionIncoherente(EvaluacionIncoherenteException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(VALIDATION_ERROR);
		problem.setTitle("La evaluacion tiene un dato invalido");
		return problem;
	}

	/**
	 * <b>409.</b> Corregir una sesion cerrada es una enmienda, no un segundo guardado.
	 *
	 * <p>Desde 06.06 la enmienda existe, asi que este error dejo de significar "no se puede
	 * corregir" y pasa a significar <b>"por ahi no"</b>: lo que corresponde ofrecer es
	 * {@code POST .../enmiendas}, que exige motivo y deja una version en el historial.
	 */
	@ExceptionHandler(SesionCerradaException.class)
	public ProblemDetail handleSesionCerrada(SesionCerradaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(SESION_CERRADA);
		problem.setTitle("La atencion ya esta cerrada");
		return problem;
	}

	/**
	 * <b>409, y con tipo propio.</b> Se intento enmendar una sesion que sigue abierta.
	 *
	 * <p>Es el espejo de {@link #handleSesionCerrada} y tiene su propio {@code type} porque lleva
	 * a <b>otra accion</b>: aca lo que corresponde ofrecer es guardar normalmente —evaluacion o
	 * borrador—, que ni exige motivo ni deja una version en el historial. Un unico
	 * {@code conflict} para las dos situaciones obligaria a la pantalla a adivinar cual de los dos
	 * botones mostrar.
	 */
	@ExceptionHandler(SesionNoCerradaException.class)
	public ProblemDetail handleSesionNoCerrada(SesionNoCerradaException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(SESION_NO_CERRADA);
		problem.setTitle("La atencion todavia esta abierta");
		return problem;
	}

	/**
	 * <b>400 y no 409.</b> Se pidio enmendar sin declarar por que.
	 *
	 * <p>No hay conflicto de estado: la sesion esta cerrada, quien opera es su dueño y la version
	 * que mando es la vigente. Falta un dato del pedido. Un 409 mandaria a la pantalla a ofrecer
	 * "reintentar" donde lo que corresponde es "completa el motivo", y reintentar sin motivo
	 * vuelve a fallar exactamente igual.
	 */
	/**
	 * <b>409.</b> La enmienda cambiaria que practicas se realizaron (C-6).
	 *
	 * <p>Conflicto de estado y no dato faltante: el pedido es valido en si, lo que lo impide es que
	 * el cierre ya consumio una autorizacion eligiendola por esas practicas.
	 */
	@ExceptionHandler(EnmiendaCambiaPracticasException.class)
	public ProblemDetail handleEnmiendaCambiaPracticas(EnmiendaCambiaPracticasException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.CONFLICT, exception.getMessage());
		problem.setType(ENMIENDA_CAMBIA_PRACTICAS);
		problem.setTitle("La enmienda no puede cambiar las practicas realizadas");
		return problem;
	}

	@ExceptionHandler(EnmiendaSinMotivoException.class)
	public ProblemDetail handleEnmiendaSinMotivo(EnmiendaSinMotivoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(ENMIENDA_SIN_MOTIVO);
		problem.setTitle("La enmienda exige un motivo");
		return problem;
	}

	/** <b>400.</b> Falta un minimo del cierre; reintentar el mismo cuerpo falla igual. */
	@ExceptionHandler(CierreIncompletoException.class)
	public ProblemDetail handleCierreIncompleto(CierreIncompletoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(VALIDATION_ERROR);
		problem.setTitle("Falta un dato para poder cerrar la atencion");
		return problem;
	}

	/**
	 * La definicion de medicion no existe o es de otro tenant (404).
	 *
	 * <p>El {@code type} es el mismo que emite {@code resource} desde la administracion del
	 * catalogo, porque para el cliente la situacion es la misma. La <b>excepcion</b>, en cambio,
	 * es de este modulo: {@code resource.spi.MedicionDirectory} responde y no autoriza, igual que
	 * {@code CasoDirectory}.
	 */
	@ExceptionHandler(MedicionDefinicionNoAccesibleException.class)
	public ProblemDetail handleMedicionDefinicionNoAccesible(
			MedicionDefinicionNoAccesibleException exception) {

		log.debug("Definicion de medicion no accesible desde la atencion: definicionId={}",
				exception.getDefinicionId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
				"La definicion de medicion no existe.");
		problem.setType(MEDICION_DEFINICION_NO_ACCESIBLE);
		problem.setTitle("Definicion de medicion no encontrada");
		return problem;
	}

	// =================================================================================
	// AKINE-06.04 — Tratamientos realizados y espacios usados
	// =================================================================================

	/**
	 * <b>404.</b> El tratamiento no existe, o no es de esa sesion, de ese tenant o de esa sede.
	 *
	 * <p>Las cuatro situaciones son indistinguibles a proposito: un 403 o un 409 confirmarian que
	 * el id existe, y bastaria probar ids consecutivos para censar las intervenciones de otro
	 * centro. En un modulo clinico eso deja de ser aislamiento de tenant y pasa a ser privacidad.
	 */
	@ExceptionHandler(TratamientoNoAccesibleException.class)
	public ProblemDetail handleTratamientoNoAccesible(TratamientoNoAccesibleException exception) {
		log.debug("Tratamiento no accesible: {}", exception.getTratamientoId());

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
				"La intervencion no existe en esta atencion.");
		problem.setType(TRATAMIENTO_NO_ACCESIBLE);
		problem.setTitle("Intervencion no encontrada");
		return problem;
	}

	/**
	 * <b>409.</b> La baja de una definicion <b>no cascadea</b>: las mediciones que ya la usaban
	 * siguen legibles y siguen entrando en la comparacion, y lo unico que se impide es registrar
	 * nuevas. La accion que corresponde ofrecer es elegir otro test.
	 */
	@ExceptionHandler(MedicionDefinicionInactivaException.class)
	public ProblemDetail handleMedicionDefinicionInactiva(
			MedicionDefinicionInactivaException exception) {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"Esa medida esta dada de baja y no admite mediciones nuevas. Las ya registradas "
						+ "siguen siendo legibles y comparables.");
		problem.setType(MEDICION_DEFINICION_INACTIVA);
		problem.setTitle("La medida esta dada de baja");
		problem.setProperty("definicionId", exception.getDefinicionId());
		return problem;
	}

	/**
	 * <b>400 y no 409.</b> Un EVA de 12 en una escala de 0 a 10 es un problema del cuerpo enviado:
	 * reintentarlo falla igual. Mismo reparto que {@link #handleEvaluacionIncoherente}.
	 *
	 * <p>El rango viaja en el cuerpo porque un "fuera de rango" sin numeros es inaccionable: la
	 * pantalla tiene que poder decir entre que y que.
	 *
	 * <p><b>Solo lo emite el registro.</b> El rango nunca se revalida al leer: una medicion vieja
	 * no se vuelve invalida porque el catalogo se estreche despues.
	 */
	@ExceptionHandler(MedicionFueraDeRangoException.class)
	public ProblemDetail handleMedicionFueraDeRango(MedicionFueraDeRangoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(MEDICION_FUERA_DE_RANGO);
		problem.setTitle("El valor esta fuera del rango de la medida");
		problem.setProperty("codigo", exception.getCodigo());
		problem.setProperty("valor", exception.getValor());
		if (exception.getMinimo() != null) {
			problem.setProperty("minimo", exception.getMinimo());
		}
		if (exception.getMaximo() != null) {
			problem.setProperty("maximo", exception.getMaximo());
		}
		return problem;
	}

	/**
	 * La practica no sirve. <b>404 o 409 segun el motivo</b>, y la diferencia importa.
	 *
	 * <p>"No existe" y "es de otro tenant" son <b>404</b> e indistinguibles: distinguirlas
	 * permitiria censar por ids el catalogo propio de otro centro.
	 *
	 * <p>"No es vigente" es <b>409</b> y tiene que ser otra cosa porque lleva a otra accion: la
	 * practica existe y es visible, fue dada de baja, y lo que corresponde es elegir otra — no
	 * buscar el id.
	 */
	@ExceptionHandler(PracticaNoUtilizableException.class)
	public ProblemDetail handlePracticaNoUtilizable(PracticaNoUtilizableException exception) {
		log.debug("Practica no utilizable: practicaId={} motivo={}",
				exception.getPracticaId(), exception.getMotivo());

		if (exception.getMotivo() == PracticaNoUtilizableException.Motivo.NO_VIGENTE) {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
					"La practica fue dada de baja o esta fuera de su ventana de vigencia, asi que "
							+ "no se puede elegir. Las intervenciones ya registradas con ella "
							+ "siguen siendo legibles.");
			problem.setType(PRACTICA_NO_UTILIZABLE);
			problem.setTitle("La practica ya no se puede elegir");
			problem.setProperty("practicaId", exception.getPracticaId());
			return problem;
		}

		return noEncontrado("La practica no existe en el catalogo accesible.");
	}

	/** <b>404.</b> El espacio no existe, o es de otro tenant o de otra sede. */
	@ExceptionHandler(EspacioNoAccesibleException.class)
	public ProblemDetail handleEspacioNoAccesible(EspacioNoAccesibleException exception) {
		log.debug("Espacio no accesible: {}", exception.getEspacioId());
		return noEncontrado("El espacio no existe en esta sede.");
	}

	/**
	 * <b>409.</b> El espacio existe y es del tenant, pero no estaba operable cuando ocurrio la
	 * atencion.
	 *
	 * <p>La vigencia se evalua en el instante de la <b>atencion</b> y no en el de la carga:
	 * evaluarla "ahora" haria que registrar el viernes una atencion del lunes fallara porque el
	 * box se dio de baja el miercoles, y eso es negar un hecho que ocurrio.
	 *
	 * <p><b>Esto no es ocupacion.</b> La ocupacion no se valida: un tratamiento realizado es un
	 * hecho consumado y rechazarlo por ocupacion no impide la sobreocupacion —ya paso— sino que
	 * impide documentarla.
	 */
	@ExceptionHandler(EspacioNoOperableException.class)
	public ProblemDetail handleEspacioNoOperable(EspacioNoOperableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"El espacio no estaba operable en el momento de la atencion.");
		problem.setType(ESPACIO_NO_OPERABLE);
		problem.setTitle("El espacio no estaba operable");
		problem.setProperty("espacioId", exception.getEspacioId());
		return problem;
	}

	/**
	 * <b>400.</b> Cubre las dos mitades de la misma invariante —falta el valor que corresponde, o
	 * sobra otro— con {@code motivo} para distinguirlas. Para la pantalla el desenlace es el mismo:
	 * decir que valor se espera.
	 */
	@ExceptionHandler(MedicionTipoIncompatibleException.class)
	public ProblemDetail handleMedicionTipoIncompatible(
			MedicionTipoIncompatibleException exception) {

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMessage());
		problem.setType(MEDICION_TIPO_INCOMPATIBLE);
		problem.setTitle("El valor no corresponde al tipo de la medida");
		problem.setProperty("codigo", exception.getCodigo());
		problem.setProperty("tipoEsperado", exception.getTipoEsperado());
		problem.setProperty("motivo", exception.getMotivo());
		return problem;
	}

	/**
	 * <b>409 y no 403.</b> El co-atendiente declarado no tiene membership vigente en esa sede.
	 *
	 * <p>Quien opera <b>si</b> tiene permiso: es el dueño de la atencion y ya paso el control. Lo
	 * que no sirve es el dato que declaro. Un 403 mandaria a la pantalla a decir "no tenes
	 * permiso", que es falso y ademas manda al usuario a pedir un permiso que ya tiene. Es el
	 * mismo reparto que {@code SesionAjenaException}.
	 */
	@ExceptionHandler(ProfesionalNoAsignableException.class)
	public ProblemDetail handleProfesionalNoAsignable(ProfesionalNoAsignableException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"El profesional declarado no tiene un vinculo vigente con esta sede.");
		problem.setType(PROFESIONAL_NO_ASIGNABLE);
		problem.setTitle("El profesional no se puede asignar a la intervencion");
		problem.setProperty("membershipId", exception.getMembershipId());
		return problem;
	}

	/**
	 * <b>404.</b> No hay una medicion de esa medida y ese lado en esa sesion.
	 *
	 * <p>Un {@code DELETE} que respondiera 204 sobre una fila inexistente le ocultaria a la
	 * pantalla que estaba mirando datos viejos, y el profesional se quedaria creyendo que borro
	 * una medicion que en realidad sigue ahi bajo el otro lado.
	 */
	@ExceptionHandler(MedicionNoAccesibleException.class)
	public ProblemDetail handleMedicionNoAccesible(MedicionNoAccesibleException exception) {
		log.debug("Medicion no accesible: definicionId={} lateralidad={}",
				exception.getDefinicionId(), exception.getLateralidad());
		return noEncontrado("No hay una medicion de esa medida y ese lado en esta sesion.");
	}

	/**
	 * <b>400.</b> Un parametro esta mal tipado.
	 *
	 * <p>Es el caso borde que la etapa nombra: un parametro sin tipo <b>se rechaza</b>, no se
	 * normaliza adivinando — adivinar es como se cuela un valor de dosificacion clinica
	 * interpretado al reves.
	 *
	 * <p>400 y no 409: no hay ningun estado del sistema que impida la operacion, falta o sobra un
	 * dato del pedido. El {@code clave} viaja en el cuerpo porque un 400 que no dice cual de los
	 * seis parametros esta mal obliga al usuario a probar de a uno.
	 */
	@ExceptionHandler(ParametroInvalidoException.class)
	public ProblemDetail handleParametroInvalido(ParametroInvalidoException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, exception.getMotivo());
		problem.setType(PARAMETRO_INVALIDO);
		problem.setTitle("Parametro de tratamiento invalido");
		problem.setProperty("clave", exception.getClave());
		return problem;
	}

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
