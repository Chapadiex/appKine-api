package com.akine.organization.infrastructure.tenant;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
				.map(consultorio -> new ConsultorioSnapshot(
						consultorio.getId(),
						consultorio.getOrganizationId(),
						consultorio.getName(),
						consultorio.getTimezone(),
						consultorio.isActive()));
	}
}
