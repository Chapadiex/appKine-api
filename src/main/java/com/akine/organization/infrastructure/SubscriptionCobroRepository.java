package com.akine.organization.infrastructure;

import com.akine.organization.domain.SubscriptionCobro;
import com.akine.organization.domain.port.SubscriptionCobroRepositoryPort;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Historial de cobros. Extiende {@link Repository} y no {@code JpaRepository} por el mismo motivo
 * que {@link SubscriptionTransitionRepository}: una tabla append-only no hereda {@code delete}.
 */
public interface SubscriptionCobroRepository
		extends Repository<SubscriptionCobro, Long>, SubscriptionCobroRepositoryPort {

	@Override
	SubscriptionCobro save(SubscriptionCobro cobro);

	@Override
	List<SubscriptionCobro> findAllByOrganizationIdOrderByRegistradoEnDescIdDesc(Long organizationId);
}
