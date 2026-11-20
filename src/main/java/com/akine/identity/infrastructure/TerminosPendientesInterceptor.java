package com.akine.identity.infrastructure;

import com.akine.identity.application.TerminosService;
import com.akine.identity.domain.exception.TerminosPendientesException;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * Corta con 403 {@code terminos-pendientes} a toda cuenta que no acepto la version vigente de
 * los terminos (19/11/2026, docs/diseno/AKINE-terminos-obligatorios.md).
 *
 * <p>No importa como nacio la cuenta (registro, invitacion, alta manual, administrador de
 * plataforma): si no hay aceptacion de la vigente, no opera. Pasan sin mirar los pedidos sin
 * cuenta y las rutas que una cuenta pendiente necesita para salir del paso: la sesion
 * (ingresar, renovar, cerrar), leer los terminos y aceptarlos.
 */
@Component
public class TerminosPendientesInterceptor implements HandlerInterceptor {

	/** La misma ruta que MisTerminosController: la cuenta pendiente la necesita para salir. */
	static final String RUTA_MIS_TERMINOS = "/api/v1/me/terminos";

	private static final List<String> RUTAS_LIBRES = List.of(
			"/api/v1/auth/", "/api/v1/terms", RUTA_MIS_TERMINOS, "/api/v1/public/");

	// ObjectProvider: los slices @WebMvcTest cargan los interceptores sin los servicios.
	private final ObjectProvider<TerminosService> terminosService;

	TerminosPendientesInterceptor(ObjectProvider<TerminosService> terminosService) {
		this.terminosService = terminosService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod)) {
			return true;
		}
		String ruta = request.getRequestURI().substring(request.getContextPath().length());
		if (RUTAS_LIBRES.stream().anyMatch(ruta::startsWith)) {
			return true;
		}
		AuthenticatedPrincipal principal = principal();
		TerminosService servicio = terminosService.getIfAvailable();
		if (principal == null || servicio == null) {
			return true;
		}
		if (servicio.pendiente(principal.accountId())) {
			throw new TerminosPendientesException(servicio.versionVigente());
		}
		return true;
	}

	private static AuthenticatedPrincipal principal() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		return authentication.getPrincipal() instanceof AuthenticatedPrincipal akine ? akine : null;
	}
}
