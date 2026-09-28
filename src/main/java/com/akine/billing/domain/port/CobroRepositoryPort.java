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
	 * <p><b>El {@code organizationId} no es decorativo</b> (AKINE-07.07). Estas eran las dos
	 * unicas escrituras de dinero del modulo cuyo WHERE no llevaba discriminante de tenant: la
	 * firma era {@code (obligacionId, importe)} a secas. No se explotaba, porque los dos
	 * llamadores resuelven la obligacion con {@code findByIdInScope} antes de llegar aca — pero
	 * una firma asi no le da al proximo llamador ninguna forma de equivocarse a favor, y el
	 * proximo llamador con un id que venga de un body salda la deuda de otro centro del SaaS sin
	 * que nada falle. Sus tres hermanas —{@code EgresoRepositoryPort} y el
	 * {@code descontarSaldo} de autorizaciones, que ademas cita a esta como precedente— ya lo
	 * llevaban: la unica que faltaba era el origen del patron.
	 *
	 * @return filas afectadas. <b>Cero significa que otro cobro se llevo la plata primero</b>, y es
	 *         un desenlace legitimo que el llamador traduce a 409, no un error tecnico. Desde
	 *         07.07 cero tambien puede significar que la obligacion es de otro tenant, y el
	 *         desenlace es el mismo: no se descuenta nada
	 */
	int descontarSaldo(long organizationId, long obligacionId, BigDecimal importe);

	/** Deriva el estado del saldo: PAGADA cuando llega a cero, PARCIAL mientras no. */
	void actualizarEstadoPorSaldo(long organizationId, long obligacionId);
}
