package com.akine.identity.application;

import com.akine.identity.domain.Cuenta;
import com.akine.identity.domain.port.CuentaRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.AuthorizedContext;
import com.akine.organization.spi.MembershipSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	public MyProfileService(CuentaRepositoryPort cuentas, AccountContextDirectory contextos) {
		this.cuentas = cuentas;
		this.contextos = contextos;
	}

	public record ContextoActivo(
			long organizationId, String organizationName,
			long consultorioId, String consultorioName,
			String roleCode, String roleLabel) {
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
		Optional<MembershipSnapshot> membership = contextos.membership(accountId, organizationId);
		if (membership.isEmpty()) {
			return Optional.empty();
		}
		String rol = membership.get().roleCode();
		return contextos.authorizedContexts(accountId).stream()
				.filter(a -> a.organizationId() == organizationId && a.consultorioId() == consultorioId)
				.findFirst()
				.map((AuthorizedContext a) -> new ContextoActivo(
						a.organizationId(), a.organizationName(),
						a.consultorioId(), a.consultorioName(),
						rol, ETIQUETAS.getOrDefault(rol, rol)));
	}
}
