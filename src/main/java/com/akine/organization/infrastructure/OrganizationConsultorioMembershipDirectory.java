package com.akine.organization.infrastructure;

import com.akine.organization.domain.Membership;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Implementacion de {@link ConsultorioMembershipDirectory}: el borde por donde
 * {@code organization} responde "donde y cuando vale esta membership" a otros modulos.
 *
 * <p><b>No confundir con {@code infrastructure.tenant.OrganizationMembershipDirectory}.</b> Ese
 * adaptador implementa {@code platform.spi.tenant.MembershipDirectory} y resuelve el CONTEXTO
 * operativo completo (rol + estado de la suscripcion) para la autenticacion, en caliente y sin
 * cache, buscando por CUENTA. Este resuelve una pregunta mas chica y mas fria —a que sede y a
 * que ventana de tiempo corresponde una membership ya identificada por SU ID—, la usan
 * 02.04-07 y 02.04-08 para proyectar disponibilidad, y no tiene nada que ver con autorizar un
 * request.
 *
 * <p>Delega el filtro de tenant en {@link MembershipRepository#findByIdAndOrganizationId}, la
 * misma consulta por {@code (id, organizationId)} que ya usa el resto del modulo: una
 * membership de otro tenant no resuelve, nunca se filtra en Java despues de traerla.
 *
 * <p>Nunca devuelve la {@link Membership}: solo el record del {@code spi}. Devolver la entity
 * dejaria que el consumidor la modificara dentro de una transaccion ajena, y ademas ArchUnit lo
 * rechaza porque la entity vive en {@code domain}.
 *
 * <p><b>{@code habilitada} se calcula aca, nunca en el record.</b> Que estados habilitan algo es
 * una decision de dominio que ya vive en {@code MembershipEstado.habilita()}; reimplementarla
 * en el {@code spi} —por ejemplo comparando el nombre del estado— dejaria dos copias de la
 * misma regla libres para divergir en cuanto una de las dos cambiara.
 */
@Component
public class OrganizationConsultorioMembershipDirectory implements ConsultorioMembershipDirectory {

	private final MembershipRepository membershipRepository;

	public OrganizationConsultorioMembershipDirectory(MembershipRepository membershipRepository) {
		this.membershipRepository = membershipRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ConsultorioMembershipSnapshot> find(long organizationId, long membershipId) {
		return membershipRepository.findByIdAndOrganizationId(membershipId, organizationId)
				.map(OrganizationConsultorioMembershipDirectory::snapshot);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ConsultorioMembershipSnapshot> findByAccount(long organizationId, long accountId) {
		return membershipRepository
				.findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(organizationId, accountId)
				.stream()
				.map(OrganizationConsultorioMembershipDirectory::snapshot)
				.toList();
	}

	private static ConsultorioMembershipSnapshot snapshot(Membership membership) {
		return new ConsultorioMembershipSnapshot(
				membership.getId(),
				membership.getAccountId(),
				membership.getOrganizationId(),
				membership.getConsultorioId(),
				membership.getRoleCode().name(),
				membership.getEstado().name(),
				membership.getValidFrom(),
				membership.getValidUntil(),
				membership.isActive(),
				membership.getEstado().habilita());
	}
}
