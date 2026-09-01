package com.akine.billing.domain.port;

import com.akine.billing.domain.Cobro;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Persistencia de cobros y el descuento atomico del saldo de la deuda. */
public interface CobroRepositoryPort {

	Cobro save(Cobro cobro);

	Optional<Cobro> findByIdInScope(long organizationId, long consultorioId, long cobroId);

	Optional<Cobro> findByIdempotencyKey(long organizationId, String idempotencyKey);

	/** Los cobros de un paciente, del mas reciente al mas viejo. */
	List<Cobro> findDeLaPersona(long organizationId, long personaId);

	/**
	 * Descuenta el importe del saldo de la obligacion, si alcanza.
	 *
	 * <p><b>Es un UPDATE condicional y no un lock, y esa es toda la idea:</b>
	 * {@code SET saldo = saldo - :importe WHERE saldo >= :importe}. Es atomico, no necesita leer
	 * antes —que es donde se cuela la ventana entre lectura y escritura— y no puede dejar el saldo
	 * en negativo aunque dos cobros lleguen juntos.
	 *
	 * @return filas afectadas. <b>Cero significa que otro cobro se llevo la plata primero</b>, y es
	 *         un desenlace legitimo que el llamador traduce a 409, no un error tecnico
	 */
	int descontarSaldo(long obligacionId, BigDecimal importe);

	/** Deriva el estado del saldo: PAGADA cuando llega a cero, PARCIAL mientras no. */
	void actualizarEstadoPorSaldo(long obligacionId);
}
