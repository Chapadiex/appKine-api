package com.akine.organization.domain.port;

import com.akine.organization.domain.SubscriptionCobro;

import java.util.List;

/** Historial de cobros de la suscripcion. <b>Solo alta y lectura</b>, como el de transiciones. */
public interface SubscriptionCobroRepositoryPort {

	/** Unica escritura permitida: agregar una fila. */
	SubscriptionCobro save(SubscriptionCobro cobro);

	/** Cobros de un centro, el mas nuevo primero. */
	List<SubscriptionCobro> findAllByOrganizationIdOrderByRegistradoEnDescIdDesc(Long organizationId);
}
