package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Resuelve en lote los nombres que una agenda muestra. Dos consultas para los profesionales y una
 * para los boxes, sea cual sea el tamano de la lista.
 */
@Component
class NombresDeAgenda {

	private final ConsultorioMembershipDirectory memberships;
	private final AccountIdentityDirectory identidades;
	private final EspacioDirectory espacios;

	NombresDeAgenda(
			ConsultorioMembershipDirectory memberships,
			AccountIdentityDirectory identidades,
			EspacioDirectory espacios) {

		this.memberships = memberships;
		this.identidades = identidades;
		this.espacios = espacios;
	}

	/** Membership -> cuenta -> nombre. Una cuenta sin nombre o que ya no resuelve no aparece. */
	Map<Long, String> profesionales(long organizationId, Collection<Long> membershipIds) {
		if (membershipIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, ConsultorioMembershipSnapshot> vinculos = memberships.findTodas(organizationId, membershipIds);
		Map<Long, AccountIdentity> cuentas = identidades.identidadesDe(
				vinculos.values().stream().map(ConsultorioMembershipSnapshot::accountId).toList());
		Map<Long, String> nombres = new HashMap<>();
		vinculos.forEach((id, vinculo) -> {
			AccountIdentity cuenta = cuentas.get(vinculo.accountId());
			if (cuenta != null && cuenta.nombreCompleto() != null) {
				nombres.put(id, cuenta.nombreCompleto());
			}
		});
		return nombres;
	}

	Map<Long, String> espacios(long organizationId, Collection<Long> espacioIds, Instant at) {
		if (espacioIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> nombres = new HashMap<>();
		espacios.findTodos(organizationId, espacioIds, at).forEach(
				(id, espacio) -> nombres.put(id, nombreDe(espacio)));
		return nombres;
	}

	private static String nombreDe(EspacioSnapshot espacio) {
		return espacio.name();
	}
}
