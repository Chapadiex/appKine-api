package com.akine.organization.infrastructure.tenant;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.infrastructure.ConsultorioRepository;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;

/**
 * Implementacion de {@link ConsultorioDirectory}.
 *
 * <p>Vive junto a {@link OrganizationMembershipDirectory} y por el mismo motivo: es el borde
 * por donde {@code organization} responde preguntas de otros modulos, y <b>nunca devuelve
 * entities</b>, solo el record del {@code spi}.
 *
 * <p>{@code readOnly}: es una consulta pura. Se une a la transaccion del llamador cuando hay
 * una —el alta de un espacio la invoca dentro de la suya— y abre una propia cuando no.
 *
 * <p><b>La direccion sale ya filtrada</b> (V140): si la sede no la publica, el snapshot la lleva
 * en {@code null} y el consumidor no tiene que conocer la regla. Las coordenadas van con la misma
 * condicion: sin direccion publica no hay "como llegar".
 */
@Component
public class OrganizationConsultorioDirectory implements ConsultorioDirectory {

	private final ConsultorioRepository consultorioRepository;

	public OrganizationConsultorioDirectory(ConsultorioRepository consultorioRepository) {
		this.consultorioRepository = consultorioRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ConsultorioSnapshot> find(long organizationId, long consultorioId) {
		return consultorioRepository.findByIdAndOrganizationId(consultorioId, organizationId)
				.map(OrganizationConsultorioDirectory::snapshotDe);
	}

	/**
	 * {@code MANDATORY}: el lock compartido tiene que quedar en la transaccion del llamador. En una
	 * propia se soltaria al volver de este metodo y no serializaria nada contra la baja.
	 */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean inactivaBajoLockCompartido(long organizationId, long consultorioId) {
		return consultorioRepository.countActivaForShare(consultorioId, organizationId) == 0;
	}

	private static ConsultorioSnapshot snapshotDe(Consultorio consultorio) {
		boolean publica = consultorio.isDireccionPublica() && consultorio.direccionFormateada() != null;
		return new ConsultorioSnapshot(
				consultorio.getId(),
				consultorio.getOrganizationId(),
				consultorio.getName(),
				consultorio.getTimezone(),
				consultorio.isActive(),
				consultorio.isAgendaHabilitada(),
				publica ? consultorio.direccionFormateada() : null,
				publica ? consultorio.getLatitud() : null,
				publica ? consultorio.getLongitud() : null);
	}
}
