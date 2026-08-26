package com.akine.organization.infrastructure;

import com.akine.organization.domain.Membership;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.MembershipDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implementacion de {@link MembershipDirectory}: el borde por donde {@code organization}
 * responde "donde y cuando vale esta membership" a otros modulos.
 *
 * <p><b>No confundir con {@code infrastructure.tenant.OrganizationMembershipDirectory}.</b> Ese
 * adaptador implementa {@code platform.spi.tenant.MembershipDirectory} y resuelve el CONTEXTO
 * operativo completo (rol + estado de la suscripcion) para la autenticacion, en caliente y sin
 * cache. Este resuelve una pregunta mas chica y mas fria —a que sede y a que ventana de tiempo
 * corresponde una membership ya identificada por id—, la usan 02.04-07 y 02.04-08 para
 * proyectar disponibilidad, y no tiene nada que ver con autorizar un request.
 *
 * <p>Delega el filtro de tenant en {@link MembershipRepository#findByIdAndOrganizationId}, la
 * misma consulta por {@code (id, organizationId)} que ya usa el resto del modulo: una
 * membership de otro tenant no resuelve, nunca se filtra en Java despues de traerla.
 *
 * <p>Nunca devuelve la {@link Membership}: solo el record del {@code spi}. Devolver la entity
 * dejaria que el consumidor la modificara dentro de una transaccion ajena, y ademas ArchUnit lo
 * rechaza porque la entity vive en {@code domain}.
 */
@Component
public class OrganizationMembershipDirectory implements MembershipDirectory {

	private final MembershipRepository membershipRepository;

	public OrganizationMembershipDirectory(MembershipRepository membershipRepository) {
		this.membershipRepository = membershipRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ConsultorioMembershipSnapshot> find(long organizationId, long membershipId) {
		return membershipRepository.findByIdAndOrganizationId(membershipId, organizationId)
				.map(OrganizationMembershipDirectory::snapshot);
	}

	private static ConsultorioMembershipSnapshot snapshot(Membership membership) {
		return new ConsultorioMembershipSnapshot(
				membership.getId(),
				membership.getAccountId(),
				membership.getOrganizationId(),
				membership.getConsultorioId(),
				membership.getRoleCode().name(),
				membership.getValidFrom(),
				membership.getValidUntil(),
				membership.isActive());
	}
}
