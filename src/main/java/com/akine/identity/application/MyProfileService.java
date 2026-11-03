package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.AuthorizedContext;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.MembershipSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Perfil de la propia cuenta (DP-45 punto 3). Sin permiso: es la cuenta leyendose a si misma, y
 * el id sale del principal, nunca de un parametro.
 */
@Service
public class MyProfileService {

	private static final Map<String, String> ETIQUETAS = Map.of(
			"PLATFORM_ADMIN", "Administrador de plataforma",
			"ORG_ADMIN", "Administrador de la organizacion",
			"CONSULTORIO_ADMIN", "Administrador de sede",
			"PROFESIONAL", "Profesional",
			"ADMINISTRATIVO", "Administrativo",
			"PACIENTE", "Paciente");

	private final CuentaRepositoryPort cuentas;
	private final AccountContextDirectory contextos;
	private final ConsultorioMembershipDirectory memberships;

	public MyProfileService(CuentaRepositoryPort cuentas, AccountContextDirectory contextos,
			ConsultorioMembershipDirectory memberships) {
		this.cuentas = cuentas;
		this.contextos = contextos;
		this.memberships = memberships;
	}

	/**
	 * @param membershipId la membership que gobierna este contexto (la de la sede si la hay, si no la
	 *                     de la organizacion): es el id con el que la agenda nombra al profesional, y
	 *                     el que el frontend usa para abrir "mi agenda". {@code null} si no se resuelve
	 */
	public record ContextoActivo(
			long organizationId, String organizationName,
			long consultorioId, String consultorioName,
			String roleCode, String roleLabel, Long membershipId) {
	}

	public record Perfil(long accountId, String email, String nombre, String apellido,
			ContextoActivo contexto) {
	}

	@Transactional(readOnly = true)
	public Optional<Perfil> perfil(long accountId, Long organizationId, Long consultorioId) {
		Optional<Cuenta> cuenta = cuentas.findById(accountId);
		return cuenta.map(c -> new Perfil(
				accountId, c.getEmail(), c.getNombre(), c.getApellido(),
				resolver(accountId, organizationId, consultorioId).orElse(null)));
	}

	private Optional<ContextoActivo> resolver(long accountId, Long organizationId, Long consultorioId) {
		if (organizationId == null || consultorioId == null) {
			return Optional.empty();
		}
		// La que gobierna la sede, con el mismo criterio que el evaluador de permisos: la mas
		// especifica gana. La de organizacion sigue siendo la que da el rol cuando existe, como antes.
		Optional<ConsultorioMembershipSnapshot> gobernante = gobernante(accountId, organizationId, consultorioId);
		Optional<String> rolDeOrganizacion = contextos.membership(accountId, organizationId)
				.map(MembershipSnapshot::roleCode);
		Optional<String> rol = rolDeOrganizacion.or(() -> gobernante.map(ConsultorioMembershipSnapshot::roleCode));
		if (rol.isEmpty()) {
			return Optional.empty();
		}
		Long membershipId = gobernante.map(ConsultorioMembershipSnapshot::membershipId).orElse(null);
		return contextos.authorizedContexts(accountId).stream()
				.filter(a -> a.organizationId() == organizationId && a.consultorioId() == consultorioId)
				.findFirst()
				.map((AuthorizedContext a) -> new ContextoActivo(
						a.organizationId(), a.organizationName(),
						a.consultorioId(), a.consultorioName(),
						rol.get(), ETIQUETAS.getOrDefault(rol.get(), rol.get()), membershipId));
	}

	private Optional<ConsultorioMembershipSnapshot> gobernante(
			long accountId, long organizationId, long consultorioId) {
		Instant ahora = Instant.now();
		return memberships.findByAccount(organizationId, accountId).stream()
				.filter(m -> m.validAt(ahora))
				.filter(m -> m.cubreConsultorio(consultorioId))
				.min(Comparator.comparing((ConsultorioMembershipSnapshot m) -> m.consultorioId() == null));
	}
}
