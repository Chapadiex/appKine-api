package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import com.akine.resource.domain.port.AgendaProfesionalRepositoryPort;
import com.akine.resource.spi.BloqueoDeAgendaDeProfesional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Implementacion de {@link BloqueoDeAgendaDeProfesional} sobre {@code agenda_profesional} (V129).
 *
 * <p>La persona se resuelve por la cuenta de la membership ({@code organization.spi}): es la cuenta
 * la que no puede estar en dos sedes a la vez, y las memberships por sede le dan un id distinto en
 * cada una.
 */
@Component
public class ResourceBloqueoDeAgendaDeProfesional implements BloqueoDeAgendaDeProfesional {

	private final AgendaProfesionalRepositoryPort filas;
	private final ConsultorioMembershipDirectory memberships;
	private final FilaLockPrevia filaAgendaProfesional;

	public ResourceBloqueoDeAgendaDeProfesional(
			AgendaProfesionalRepositoryPort filas,
			ConsultorioMembershipDirectory memberships,
			PlatformTransactionManager transactionManager) {
		this.filas = filas;
		this.memberships = memberships;
		this.filaAgendaProfesional = new FilaLockPrevia("agenda_profesional", transactionManager);
	}

	@Override
	public void asegurar(long organizationId, Collection<Long> membershipIds) {
		cuentas(organizationId, membershipIds).forEach(cuenta -> filaAgendaProfesional.asegurar(
				() -> filas.existeFila(organizationId, cuenta),
				() -> filas.crearSiFalta(organizationId, cuenta)));
	}

	@Override
	public Set<Long> bloquear(long organizationId, long membershipId) {
		Optional<Long> cuenta = cuentaDe(organizationId, membershipId);
		if (cuenta.isEmpty()) {
			return Set.of(membershipId);
		}
		tomar(organizationId, cuenta.get());
		return deLaCuenta(organizationId, cuenta.get(), membershipId);
	}

	@Override
	public void bloquearEnOrden(long organizationId, Collection<Long> membershipIds) {
		cuentas(organizationId, membershipIds).forEach(cuenta -> tomar(organizationId, cuenta));
	}

	@Override
	public Set<Long> deLaMismaPersona(long organizationId, long membershipId) {
		return cuentaDe(organizationId, membershipId)
				.map(cuenta -> deLaCuenta(organizationId, cuenta, membershipId))
				.orElse(Set.of(membershipId));
	}

	private void tomar(long organizationId, long cuenta) {
		if (!filas.bloquear(organizationId, cuenta)) {
			throw new OptimisticLockingFailureException(
					"La agenda del profesional cambio mientras se operaba: recargar y reintentar");
		}
	}

	/** Cuentas distintas, en orden ascendente: el orden unico de toma de las filas. */
	private TreeSet<Long> cuentas(long organizationId, Collection<Long> membershipIds) {
		TreeSet<Long> cuentas = new TreeSet<>();
		membershipIds.stream()
				.filter(Objects::nonNull)
				.distinct()
				.forEach(id -> cuentaDe(organizationId, id).ifPresent(cuentas::add));
		return cuentas;
	}

	private Optional<Long> cuentaDe(long organizationId, long membershipId) {
		return memberships.find(organizationId, membershipId).map(ConsultorioMembershipSnapshot::accountId);
	}

	private Set<Long> deLaCuenta(long organizationId, long cuenta, long membershipId) {
		Set<Long> ids = new LinkedHashSet<>();
		ids.add(membershipId);
		memberships.findByAccount(organizationId, cuenta).forEach(m -> ids.add(m.membershipId()));
		return Set.copyOf(ids);
	}
}
