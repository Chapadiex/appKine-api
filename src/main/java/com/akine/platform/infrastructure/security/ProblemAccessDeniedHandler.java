package com.akine.platform.infrastructure.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Respuesta a un request <b>autenticado</b> que no tiene permitido lo que pide: <b>403</b>.
 *
 * <p>La distincion con el 401 es funcional, no estetica. 401 significa "no se quien sos" y el
 * interceptor del frontend reacciona borrando el token; 403 significa "se quien sos y no
 * alcanza", y el frontend lo muestra sin desloguear a nadie. Devolver 401 donde va 403 manda al
 * usuario a un bucle de login del que no sale (regla heredada de AKINE-01.01).
 *
 * <p>El {@code type} es el mismo {@code forbidden} que ya usa {@code GlobalExceptionHandler}
 * para las {@code AccessDeniedException} que llegan desde un controller: al frontend le da lo
 * mismo si el rechazo ocurrio en la cadena o adentro del servicio.
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {

		ProblemResponses.escribir(request, response, HttpStatus.FORBIDDEN,
				ProblemResponses.FORBIDDEN,
				"Acceso denegado",
				"No tiene permisos suficientes para realizar esta operacion.");
	}
}
