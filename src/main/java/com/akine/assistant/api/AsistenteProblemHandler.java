package com.akine.assistant.api;

import com.akine.assistant.domain.exception.AccionInvalidaException;
import com.akine.assistant.domain.exception.AccionNoEncontradaException;
import com.akine.assistant.domain.exception.AccionNoVigenteException;
import com.akine.assistant.domain.exception.AccionesDeshabilitadasException;
import com.akine.assistant.domain.exception.AsistenteDeshabilitadoException;
import com.akine.assistant.domain.exception.DictadoDeshabilitadoException;
import com.akine.assistant.domain.exception.DictadoInvalidoException;
import com.akine.assistant.domain.exception.DictadoNoDisponibleException;
import com.akine.assistant.domain.exception.ModeloNoDisponibleException;
import com.akine.assistant.domain.exception.SedeNoEncontradaException;
import com.akine.assistant.domain.exception.BotDeTelegramNoConfiguradoException;
import com.akine.assistant.domain.exception.TelegramDeshabilitadoException;
import com.akine.assistant.domain.exception.TokenDeTelegramInvalidoException;
import com.akine.platform.spi.problem.ProblemType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Traduce las excepciones de {@code assistant} a Problem Details, en su propio advice. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AsistenteProblemHandler {

	@ExceptionHandler(AsistenteDeshabilitadoException.class)
	public ProblemDetail handleDeshabilitado(AsistenteDeshabilitadoException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.ASISTENTE_DESHABILITADO,
				"Asistente deshabilitado", exception.getMessage());
	}

	@ExceptionHandler(ModeloNoDisponibleException.class)
	public ProblemDetail handleModeloNoDisponible(ModeloNoDisponibleException exception) {
		return problema(HttpStatus.SERVICE_UNAVAILABLE, ProblemType.ASISTENTE_NO_DISPONIBLE,
				"Asistente no disponible", exception.getMessage());
	}

	@ExceptionHandler(DictadoDeshabilitadoException.class)
	public ProblemDetail handleDictadoDeshabilitado(DictadoDeshabilitadoException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.DICTADO_DESHABILITADO,
				"Dictado deshabilitado", exception.getMessage());
	}

	@ExceptionHandler(DictadoNoDisponibleException.class)
	public ProblemDetail handleDictadoNoDisponible(DictadoNoDisponibleException exception) {
		return problema(HttpStatus.SERVICE_UNAVAILABLE, ProblemType.DICTADO_NO_DISPONIBLE,
				"Dictado no disponible", exception.getMessage());
	}

	@ExceptionHandler(DictadoInvalidoException.class)
	public ProblemDetail handleDictadoInvalido(DictadoInvalidoException exception) {
		return problema(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION_ERROR,
				"Dictado invalido", exception.getMessage());
	}

	@ExceptionHandler(AccionesDeshabilitadasException.class)
	public ProblemDetail handleAccionesDeshabilitadas(AccionesDeshabilitadasException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.ACCIONES_DESHABILITADAS,
				"Acciones deshabilitadas", exception.getMessage());
	}

	@ExceptionHandler(AccionNoVigenteException.class)
	public ProblemDetail handleAccionNoVigente(AccionNoVigenteException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.ACCION_NO_VIGENTE,
				"Accion no vigente", exception.getMessage());
	}

	@ExceptionHandler(AccionInvalidaException.class)
	public ProblemDetail handleAccionInvalida(AccionInvalidaException exception) {
		return problema(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION_ERROR,
				"Confirmacion invalida", exception.getMessage());
	}

	/** Inexistente, o de otra cuenta, sede u organizacion: 404, nunca 403. */
	@ExceptionHandler(AccionNoEncontradaException.class)
	public ProblemDetail handleAccionNoEncontrada(AccionNoEncontradaException exception) {
		return problema(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, "Accion no encontrada", exception.getMessage());
	}

	@ExceptionHandler(TelegramDeshabilitadoException.class)
	public ProblemDetail handleTelegramDeshabilitado(TelegramDeshabilitadoException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.TELEGRAM_DESHABILITADO, "Telegram deshabilitado",
				exception.getMessage());
	}

	@ExceptionHandler(BotDeTelegramNoConfiguradoException.class)
	public ProblemDetail handleTelegramSinBot(BotDeTelegramNoConfiguradoException exception) {
		return problema(HttpStatus.CONFLICT, ProblemType.TELEGRAM_SIN_BOT, "El centro no tiene bot de Telegram",
				exception.getMessage());
	}

	@ExceptionHandler(TokenDeTelegramInvalidoException.class)
	public ProblemDetail handleTokenDeTelegramInvalido(TokenDeTelegramInvalidoException exception) {
		return problema(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION_ERROR, "Token de Telegram invalido",
				exception.getMessage());
	}

	/** Inexistente o de otra organizacion: 404, nunca 403. */
	@ExceptionHandler(SedeNoEncontradaException.class)
	public ProblemDetail handleSedeNoEncontrada(SedeNoEncontradaException exception) {
		return problema(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, "Sede no encontrada", exception.getMessage());
	}

	private static ProblemDetail problema(HttpStatus status, ProblemType type, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type.uri());
		problem.setTitle(title);
		return problem;
	}
}
