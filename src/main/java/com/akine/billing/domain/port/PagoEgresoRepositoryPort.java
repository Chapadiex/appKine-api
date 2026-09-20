package com.akine.billing.domain.port;

import com.akine.billing.domain.PagoEgreso;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia del acto de saldar.
 *
 * <p><b>No declara {@code delete}</b>: un pago anulado cambia de estado y conserva su fila
 * (RN-M22-002). El historial de "se pago el 10 y se anulo el 12" es exactamente lo que una
 * auditoria busca.
 */
public interface PagoEgresoRepositoryPort {

	PagoEgreso save(PagoEgreso pago);

	/** Acotado al egreso ademas del tenant: un pago de otro egreso no resuelve, y da 404. */
	Optional<PagoEgreso> findByIdInScope(long organizationId, long egresoId, long pagoId);

	Optional<PagoEgreso> findByIdempotencyKey(long organizationId, String idempotencyKey);

	/** Los pagos del egreso, del mas reciente al mas viejo. Incluye los anulados: no se ocultan. */
	List<PagoEgreso> findDelEgreso(long organizationId, long egresoId);

	/**
	 * Suma de los pagos vigentes del egreso.
	 *
	 * <p><b>No se usa para operar:</b> el saldo que gobierna es la columna materializada. Existe
	 * para poder confrontar las dos cifras — si divergen, la que miente es la columna.
	 */
	BigDecimal totalPagadoVigente(long organizationId, long egresoId);
}
