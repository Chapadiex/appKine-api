package com.akine.organization.domain.port;

import com.akine.organization.domain.PedidoDeCambioDePlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/** Pedidos de cambio de plan de los centros (V50). */
public interface PedidoDeCambioDePlanRepositoryPort {

	PedidoDeCambioDePlan saveAndFlush(PedidoDeCambioDePlan pedido);

	Optional<PedidoDeCambioDePlan> findById(Long id);

	/** El pedido mas reciente del centro, en cualquier estado. */
	Optional<PedidoDeCambioDePlan> findFirstByOrganizationIdOrderByCreatedAtDescIdDesc(long organizationId);

	/** Bandeja de la plataforma, los mas nuevos primero; {@code q} busca en el nombre del centro. */
	Page<PedidoDeCambioDePlan> buscar(PedidoDeCambioDePlan.Estado estado, String q, Pageable pageable);

	long countByEstado(PedidoDeCambioDePlan.Estado estado);
}
