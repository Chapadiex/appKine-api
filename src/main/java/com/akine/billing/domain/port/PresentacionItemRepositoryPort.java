package com.akine.billing.domain.port;

import com.akine.billing.domain.PresentacionItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Persistencia de los items de un lote. */
public interface PresentacionItemRepositoryPort {

	PresentacionItem save(PresentacionItem item);

	/** Los items de un lote, en orden estable de inclusion. */
	List<PresentacionItem> findDeLaPresentacion(long presentacionId);

	Optional<PresentacionItem> findByIdEnLaPresentacion(
			long organizationId, long presentacionId, long itemId);

	/**
	 * El item vivo que tiene tomada esa obligacion, si hay alguno (RN-M21-003).
	 *
	 * <p>Se consulta <b>antes</b> de insertar para poder responder 409 con el lote que la tiene —lo
	 * que permite que el administrativo vaya a mirarlo— en vez de dejar reventar el unique
	 * {@code (organization_id, obligacion_id, ocupa_marca)}, que ademas dejaria la transaccion
	 * marcada para rollback. El unique sigue siendo el mecanismo; esto existe para explicarlo.
	 */
	Optional<PresentacionItem> findVivoDeLaObligacion(long organizationId, long obligacionId);

	/**
	 * Suma de {@code importe_presentado} de un lote, para fijar el total mientras se arma.
	 *
	 * <p>Nunca se usa para operar sobre un lote confirmado: alli el total esta congelado y lo unico
	 * que lo mueve son los {@code UPDATE} condicionales de {@link PresentacionRepositoryPort}.
	 */
	BigDecimal sumarPresentado(long presentacionId);

	/**
	 * Borra un item del borrador.
	 *
	 * <p><b>Es el unico DELETE fisico de la etapa y solo es alcanzable desde {@code BORRADOR}</b>,
	 * donde no hay informacion historica relevante que preservar: el lote no se envio, nadie lo vio
	 * y no tiene numero. Preservar cada item que alguien agrego y saco mientras armaba la pantalla
	 * no es historia, es ruido que despues hay que filtrar en cada consulta.
	 *
	 * <p>Un item de un lote confirmado <b>no se quita por ningun camino</b>: se debita.
	 */
	void borrarDelBorrador(PresentacionItem item);
}
