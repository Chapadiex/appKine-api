package com.akine.clinical.application;

import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Membership de un profesional -&gt; nombre de su cuenta, para mostrar el equipo de un Caso sin
 * que la pantalla haga una llamada por integrante (pedido de appKine-web #83, contrato 0.107.0).
 *
 * <p><b>Se resuelve al leer y no se guarda.</b> El nombre vive en la cuenta, y copiarlo al caso
 * produciria dos verdades que divergen en cuanto alguien corrija un apellido. Es el mismo camino
 * que {@code activity.application.ProyeccionDeClase}: dos consultas para todo el equipo, nunca una
 * por fila.
 *
 * <p>La membership se busca en la ORGANIZACION, no en la sede actual: un caso abierto en otra sede,
 * o un profesional que ya perdio la habilitacion o salio del centro, sigue mostrando su nombre.
 * Quien trato al paciente no desaparece del caso, y tampoco de la pantalla.
 */
@Component
public class NombresDeProfesionales {

	private final ConsultorioMembershipDirectory memberships;
	private final AccountIdentityDirectory identidades;

	public NombresDeProfesionales(
			ConsultorioMembershipDirectory memberships, AccountIdentityDirectory identidades) {
		this.memberships = memberships;
		this.identidades = identidades;
	}

	/**
	 * @return mapa membershipId -&gt; nombre completo. Las memberships que no resuelven, o cuya
	 *         cuenta no tiene nombre, <b>no aparecen</b>: el llamador muestra el id
	 */
	public Map<Long, String> de(long organizationId, Collection<Long> membershipIds) {
		if (membershipIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, ConsultorioMembershipSnapshot> vinculos =
				memberships.findTodas(organizationId, membershipIds);
		if (vinculos.isEmpty()) {
			return Map.of();
		}
		Map<Long, AccountIdentity> cuentas = identidades.identidadesDe(
				vinculos.values().stream().map(ConsultorioMembershipSnapshot::accountId).toList());

		Map<Long, String> nombres = new HashMap<>();
		vinculos.forEach((membershipId, vinculo) -> {
			AccountIdentity cuenta = cuentas.get(vinculo.accountId());
			if (cuenta != null && cuenta.nombreCompleto() != null) {
				nombres.put(membershipId, cuenta.nombreCompleto());
			}
		});
		return nombres;
	}
}
