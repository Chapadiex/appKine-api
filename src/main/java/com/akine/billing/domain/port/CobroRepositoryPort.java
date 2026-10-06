package com.akine.billing.domain.port;

import com.akine.billing.domain.Cobro;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Persistencia de cobros y el descuento atomico del saldo de la deuda. */
public interface CobroRepositoryPort {

	Cobro save(Cobro cobro);

	/**
	 * Escribe los cambios pendientes ahora. Lo usan las operaciones de F-3, que modifican un cobro
	 * ya administrado y necesitan su version y los ids de sus imputaciones nuevas para responder.
	 * Firma identica a la de {@code JpaRepository}: otra cualquiera Spring Data la tomaria por una
	 * consulta derivada y la aplicacion no arrancaria.
	 */
	void flush();

	/**
	 * El cobro, <b>con lock de fila</b> ({@code SELECT ... FOR UPDATE}), para las operaciones que lo
	 * modifican despues de confirmado: imputar o reintegrar su saldo a favor, y anularlo.
	 *
	 * <p>Las tres compiten por el mismo saldo a favor y por el mismo estado, y el lock las serializa.
	 * Tiene que ser la <b>primera</b> carga del cobro en la transaccion —si la entidad ya estuviera
	 * administrada, Hibernate devolveria la instancia vieja sin refrescarla— y la transaccion va en
	 * {@code READ_COMMITTED}, para que lo que se lee despues del lock sea lo que dejo el anterior.
	 */
	Optional<Cobro> findByIdInScopeParaEscribir(long organizationId, long consultorioId, long cobroId);

	/**
	 * El cobro al que pertenece la imputacion posterior registrada con esa clave, si la hay.
	 *
	 * <p>Devuelve solo el id y no la entidad a proposito: cargarla antes del lock haria que
	 * {@link #findByIdInScopeParaEscribir} devolviera una instancia vieja.
	 */
	Optional<Long> cobroDeLaImputacionConClave(long organizationId, String idempotencyKey);

	/**
	 * Devuelve a la deuda lo que un cobro anulado le habia imputado, y recalcula su estado.
	 *
	 * <p>Es el espejo de {@link #descontarSaldo}: un UPDATE condicional que no puede dejar el saldo
	 * por encima del importe original ni tocar una deuda anulada. Avanza {@code version} por la misma
	 * razon que aquel.
	 *
	 * @return filas afectadas. Cero es una divergencia entre el cobro y la deuda, no un desenlace de
	 *         negocio: el llamador no la traduce a 409
	 */
	int devolverSaldo(long organizationId, long obligacionId, BigDecimal importe);

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
	 *
	 * <p><b>Avanza {@code version} de la obligacion (F-3).</b> Antes no lo hacia, y una anulacion de
	 * la deuda que la habia leido antes del cobro pasaba su {@code WHERE version = N} intacto y la
	 * dejaba {@code ANULADA} con saldo cero <b>con un cobro imputado</b>: plata en la caja sin deuda
	 * que la explique. Es la cuarta regla de la integracion del 29/09 —un UPDATE nativo que no toca
	 * la version deja pasar al flush que lo pisa— y aca {@code @DynamicUpdate} no alcanza, porque
	 * la anulacion escribe el saldo legitimamente.
	 */
	int descontarSaldo(long organizationId, long obligacionId, BigDecimal importe);

	/** Deriva el estado del saldo: PAGADA cuando llega a cero, PARCIAL mientras no. */
	void actualizarEstadoPorSaldo(long organizationId, long obligacionId);
}
