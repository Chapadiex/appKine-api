package com.akine.encounter.api;

import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.CierreIncompletoException;
import com.akine.encounter.domain.exception.EnmiendaSinMotivoException;
import com.akine.encounter.domain.exception.EvaluacionIncoherenteException;
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
	private static final URI CASO_NO_ACCESIBLE = ProblemType.CASO_CLINICO_NO_ACCESIBLE.uri();
	private static final URI CASO_CERRADO = ProblemType.CASO_CLINICO_CERRADO.uri();

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

	private static ProblemDetail noEncontrado(String detalle) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detalle);
		problem.setType(NOT_FOUND);
		problem.setTitle("Recurso no encontrado");
		return problem;
	}
}
