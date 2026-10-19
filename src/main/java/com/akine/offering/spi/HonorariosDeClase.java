package com.akine.offering.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Las tarifas de honorario por clase de las ofertas (AKINE-08.09b, DP-38, RF-M22-006).
 *
 * <p>La consume {@code activity} para calcular el borrador de una liquidacion: la tarifa que
 * aplica a una clase es la vigente <b>el dia local de la clase</b> en la sede. Es una lectura
 * viva; quien liquida copia el importe al confirmar y no la vuelve a leer (CA-M22-007-06).
 *
 * <p>Separada de {@link OfertaDirectory} por lo mismo que {@link PrecioDeOferta}: el dato
 * remunerativo se pide por su nombre, no viaja de arrastre con la oferta.
 */
public interface HonorariosDeClase {

	/**
	 * Las tarifas ACTIVAS de esas ofertas, todas sus vigencias. No autoriza nada. Una oferta sin
	 * tarifa no aparece.
	 */
	List<HonorarioDeClase> activosDe(long organizationId, Collection<Long> ofertaIds);

	/** Una tarifa: importe por participante presente, con su vigencia inclusiva. */
	record HonorarioDeClase(
			long id,
			long ofertaId,
			BigDecimal importePorParticipante,
			String moneda,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		/** La fecha cae entre desde y hasta, ambos INCLUSIVE. */
		public boolean aplicaEl(LocalDate fecha) {
			return !fecha.isBefore(vigenciaDesde)
					&& (vigenciaHasta == null || !fecha.isAfter(vigenciaHasta));
		}
	}
}
