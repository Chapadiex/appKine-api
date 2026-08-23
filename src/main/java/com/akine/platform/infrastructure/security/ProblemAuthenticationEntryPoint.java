package com.akine.platform.infrastructure.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Respuesta a un request anonimo sobre una ruta que exige autenticacion: <b>401</b>.
 *
 * <p>Reemplaza el punto de entrada por defecto de Spring Security, que responde un
 * {@code WWW-Authenticate} sin cuerpo o una redireccion a un formulario de login que en una API
 * no existe. Todo error del backend es un Problem Details (ADR-0005) y el frontend ramifica por
 * el campo {@code type}.
 *
 * <p><b>No se emite {@code WWW-Authenticate: Bearer}</b> a proposito: en un navegador esa
 * cabecera dispara el dialogo nativo de credenciales, que no tiene nada que ver con el login de
 * la aplicacion y confunde al usuario.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

	@Override
	public void commence(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		ProblemResponses.escribir(request, response, HttpStatus.UNAUTHORIZED,
				ProblemResponses.UNAUTHORIZED,
				"No autenticado",
				"Este recurso requiere una sesion iniciada.");
	}
}
