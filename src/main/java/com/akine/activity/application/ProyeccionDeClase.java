package com.akine.activity.application;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.platform.spi.identity.AccountIdentity;
import com.akine.platform.spi.identity.AccountIdentityDirectory;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Arma la {@link ClaseView} de una o de muchas clases: capacidad efectiva y los nombres de la
 * oferta, del profesional y del box (pedido de la pantalla de clases de F9, web #81).
 *
 * <h2>Cuatro consultas por lista, no cuatro por fila</h2>
 *
 * <p>Los nombres viven en otros modulos y se piden por su {@code spi} <b>agrupados</b>: todas las
 * ofertas de la pagina en un {@code IN}, todos los boxes en otro, todas las memberships en otro y
 * todas las cuentas en otro. Pedirlos fila por fila era el N+1 que ya tenia {@code listar} con la
 * capacidad efectiva (una oferta y un espacio por clase), y que esta clase tambien cierra.
 *
 * <h2>Nombres de HOY, no del dia de la clase</h2>
 *
 * <p>Se leen al proyectar y no se copian a la fila. Para una grilla de clases futuras es lo que se
 * quiere —si el box cambia de nombre, la grilla tiene que decir el nombre nuevo—. Una clase pasada
 * muestra el nombre actual, igual que el resto de la agenda; si algun dia hace falta el historico,
 * es una columna en {@code clase_programada}, no un cambio aca.
 *
 * <p><b>Nunca falla por un nombre que no resuelve</b>: una oferta, un box o una membership dados de
 * baja no pueden hacer que una clase deje de poder leerse. El nombre sale {@code null} y la
 * capacidad efectiva cae a la propia, que es el criterio de {@link CapacidadDeClase}.
 *
 * <p><b>No lleva participantes ni datos de contacto</b>: del profesional sale solo el nombre, nunca
 * el correo que {@link AccountIdentity} tambien trae.
 */
@Component
public class ProyeccionDeClase {

	private final OfertaDirectory ofertas;
	private final EspacioDirectory espacios;
	private final ConsultorioMembershipDirectory memberships;
	private final AccountIdentityDirectory identidades;

	public ProyeccionDeClase(
			OfertaDirectory ofertas,
			EspacioDirectory espacios,
			ConsultorioMembershipDirectory memberships,
			AccountIdentityDirectory identidades) {

		this.ofertas = ofertas;
		this.espacios = espacios;
		this.memberships = memberships;
		this.identidades = identidades;
	}

	/** Una sola clase. Mismo camino que la lista, para que las dos no diverjan. */
	public ClaseView de(long organizationId, long consultorioId, ClaseProgramada clase) {
		return de(organizationId, consultorioId, List.of(clase)).getFirst();
	}

	/** Muchas clases, en el orden recibido, con un numero constante de consultas. */
	public List<ClaseView> de(
			long organizationId, long consultorioId, List<ClaseProgramada> clases) {

		if (clases.isEmpty()) {
			return List.of();
		}

		Map<Long, OfertaSnapshot> ofertasPorId = ofertas.findTodas(
				organizationId, consultorioId, ids(clases, ClaseProgramada::getOfertaId));

		Set<Long> espacioIds = ids(clases, ClaseProgramada::getEspacioId);
		// El instante solo decide enServicio, que aca no se usa: la capacidad y el nombre no
		// dependen de el.
		Map<Long, EspacioSnapshot> espaciosPorId = espacioIds.isEmpty()
				? Map.of()
				: espacios.findTodos(organizationId, espacioIds, Instant.now());

		Map<Long, String> profesionales = nombresDeProfesionales(
				organizationId, ids(clases, ClaseProgramada::getProfesionalMembershipId));

		return clases.stream()
				.map(clase -> {
					OfertaSnapshot oferta = ofertasPorId.get(clase.getOfertaId());
					EspacioSnapshot espacio = clase.getEspacioId() == null
							? null
							: espaciosPorId.get(clase.getEspacioId());
					return ClaseView.de(
							clase,
							capacidadEfectiva(clase, oferta, espacio),
							clase.getCupoOcupado(),
							oferta == null ? null : oferta.nombreComercial(),
							clase.getProfesionalMembershipId() == null
									? null
									: profesionales.get(clase.getProfesionalMembershipId()),
							espacio == null ? null : espacio.name());
				})
				.toList();
	}

	/**
	 * RN-M28-002, con la misma regla que {@link CapacidadDeClase}: sin oferta resoluble, la
	 * capacidad propia; sin box resoluble, el minimo entre la propia y la de la oferta.
	 */
	private static int capacidadEfectiva(
			ClaseProgramada clase, OfertaSnapshot oferta, EspacioSnapshot espacio) {

		if (oferta == null) {
			return clase.getCapacidad();
		}
		int efectiva = Math.min(clase.getCapacidad(), oferta.capacidad());
		return espacio == null ? efectiva : Math.min(efectiva, espacio.capacidad());
	}

	/** Membership -> cuenta -> nombre, en dos consultas para toda la lista. */
	private Map<Long, String> nombresDeProfesionales(
			long organizationId, Collection<Long> membershipIds) {

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

		return vinculos.entrySet().stream()
				.filter(entrada -> cuentas.containsKey(entrada.getValue().accountId()))
				.filter(entrada -> cuentas.get(entrada.getValue().accountId()).nombreCompleto() != null)
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						entrada -> cuentas.get(entrada.getValue().accountId()).nombreCompleto()));
	}

	private static Set<Long> ids(
			List<ClaseProgramada> clases, java.util.function.Function<ClaseProgramada, Long> campo) {

		return clases.stream().map(campo).filter(Objects::nonNull).collect(Collectors.toSet());
	}
}
