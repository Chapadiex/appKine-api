package com.akine.person.api;

import com.akine.person.application.OperatingActor;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Traduce el contexto de seguridad y el de tenant al {@link OperatingActor} de {@code person}.
 *
 * <p><b>Por que hay uno por modulo y no uno compartido.</b> {@code resource.api.ApiActor} hace
 * exactamente esto mismo, y no se reusa a proposito: devuelve el {@code OperatingActor} de
 * {@code resource}, que es un tipo del modulo vecino. Compartirlo ataria {@code person} a la
 * capa de aplicacion de {@code resource} por un detalle de conveniencia, y ArchUnit lo rechaza
 * con razon. Lo que se comparte es el SPI de plataforma —{@link TenantContextHolder} y
 * {@link AuthenticatedPrincipal}—, que es donde vive el contrato de verdad.
 *
 * <p>El contexto puede venir en {@code null} y eso <b>no</b> es un error de esta clase: un
 * usuario autenticado que todavia no eligio con que centro trabaja es un estado legitimo. Quien
 * decide que hacer con eso es cada operacion — y en {@code person} <b>todas</b> lo exigen, porque
 * no existe la persona global: las lecturas exigen contexto de organizacion y las mutaciones
 * ademas de sede. Ver {@code AutorizacionDePadron}.
 */
@Component
class PersonApiActor {

	private final TenantContextHolder tenantContextHolder;

	PersonApiActor(TenantContextHolder tenantContextHolder) {
		this.tenantContextHolder = tenantContextHolder;
	}

	/**
	 * @throws AccessDeniedException si no hay principal de AKINE en el contexto de seguridad
	 */
	OperatingActor current() {
		AuthenticatedPrincipal principal = principalAutenticado();
		if (principal == null) {
			throw new AccessDeniedException("La operacion requiere una sesion autenticada");
		}
		RequestTenantContext contexto = tenantContextHolder.current().orElse(null);
		return new OperatingActor(
				principal.accountId(),
				principal.platformAdmin(),
				contexto == null ? null : contexto.organizationId(),
				contexto == null ? null : contexto.consultorioId());
	}

	/**
	 * Principal de AKINE del contexto de seguridad, o {@code null}.
	 *
	 * <p>Se comprueba por TIPO y no por {@code isAuthenticated()}: el token anonimo de Spring
	 * Security se declara autenticado y su principal es un {@code String}, que no sabe responder
	 * ni el id de cuenta ni el rol de plataforma. Preguntarle a ese token si esta autenticado
	 * devuelve que si, y es la respuesta equivocada.
	 */
	private static AuthenticatedPrincipal principalAutenticado() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		Object principal = authentication.getPrincipal();
		return principal instanceof AuthenticatedPrincipal akine ? akine : null;
	}
}
