package com.akine.contracting.api;

import com.akine.contracting.application.OperatingActor;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Traduce el contexto de seguridad y el de tenant al {@link OperatingActor} de
 * {@code contracting}.
 *
 * <p><b>Por que hay uno por modulo y no uno compartido.</b> Los {@code ApiActor} de
 * {@code person}, {@code offering} y {@code resource} hacen exactamente esto mismo y no se reusan
 * a proposito: cada uno devuelve el {@code OperatingActor} de SU modulo, que es un tipo de la
 * capa {@code application} vecina, y ArchUnit lo rechaza con razon. Lo que se comparte es el SPI
 * de plataforma, que es donde vive el contrato de verdad.
 *
 * <p><b>La clase se llama distinto en cada modulo y eso no es cosmetico:</b> dos clases
 * {@code ApiActor} colisionan por nombre de bean y Spring no arranca. Es la leccion que dejo
 * escrita 02.06.
 *
 * <p>El contexto puede venir en {@code null} y eso <b>no</b> es un error de esta clase: un usuario
 * autenticado que todavia no eligio con que centro trabaja es un estado legitimo. Quien decide que
 * hacer con eso es cada operacion.
 */
@Component
class ContractingApiActor {

	private final TenantContextHolder tenantContextHolder;

	ContractingApiActor(TenantContextHolder tenantContextHolder) {
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
	 * ni el id de cuenta ni el rol de plataforma.
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
