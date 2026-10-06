package com.akine.billing.domain.port;

import com.akine.billing.domain.CobroReintegro;

import java.util.Optional;

/**
 * Persistencia de los reintegros de saldo a favor (F-3).
 *
 * <p><b>No declara {@code update} ni {@code delete}</b>: un reintegro es un hecho monetario y el
 * ledger se compensa, no se edita. Mismo contrato que {@link MovimientoCajaRepositoryPort}.
 */
public interface CobroReintegroRepositoryPort {

	CobroReintegro save(CobroReintegro reintegro);

	Optional<CobroReintegro> findByIdempotencyKey(long organizationId, String idempotencyKey);

	/** Si el cobro ya devolvio parte de su saldo a favor. Un cobro asi no se anula. */
	boolean existenDelCobro(long organizationId, long cobroId);
}
