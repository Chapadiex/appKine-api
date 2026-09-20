package com.akine.billing.domain.port;

import com.akine.billing.domain.FinanciadorPago;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de los pagos de financiadores.
 *
 * <p><b>No declara {@code update} ni {@code delete}, y esa ausencia es el diseno.</b> Un historial
 * de pagos que se puede editar no es un historial (regla maestra 10). Un pago mal registrado se
 * compensa revirtiendo su movimiento de caja y registrando el correcto. Mismo contrato que los
 * puertos de {@code movimiento_caja}, {@code turno_evento} y {@code autorizacion_movimiento}.
 */
public interface FinanciadorPagoRepositoryPort {

	FinanciadorPago save(FinanciadorPago pago);

	Optional<FinanciadorPago> findByIdempotencyKey(long organizationId, String idempotencyKey);

	/** Los pagos de un lote, para su detalle. */
	List<FinanciadorPago> findDeLaPresentacion(long presentacionId);

	/**
	 * Lo que un financiador pago en un rango, para la cuenta corriente.
	 *
	 * <p>Cruza sedes a proposito: la relacion comercial es de la <b>organizacion</b> aunque cada
	 * lote se arme en una sede.
	 */
	BigDecimal totalPagadoPorFinanciador(long organizationId, long financiadorId);
}
