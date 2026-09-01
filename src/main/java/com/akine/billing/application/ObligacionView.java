package com.akine.billing.application;

import com.akine.billing.domain.Obligacion;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una deuda.
 *
 * <p>Los importes son {@link BigDecimal} de punta a punta. <b>Ningun numero de punto flotante toca
 * este camino</b>: un {@code double} de 0.1 + 0.2 no da 0.3, y sobre una cuenta corriente eso son
 * centavos que no cuadran y que nadie puede explicar seis meses despues.
 *
 * @param importeOriginal lo que se devengo. No cambia nunca
 * @param saldo           lo que falta pagar
 * @param snapshotPrecio  el precio de la oferta AL MOMENTO de devengar. Editar la oferta manana no
 *                        cambia esto: seria reescribir una cuenta corriente
 */
public record ObligacionView(
		long id,
		long consultorioId,
		long sesionId,
		long personaId,
		String responsable,
		BigDecimal importeOriginal,
		BigDecimal saldo,
		String moneda,
		String estado,
		long ofertaId,
		String snapshotNombre,
		BigDecimal snapshotPrecio,
		Instant devengadaEn,
		Instant anuladaEn,
		String motivoAnulacion,
		long version) {

	public static ObligacionView de(Obligacion obligacion) {
		return new ObligacionView(
				obligacion.getId(),
				obligacion.getConsultorioId(),
				obligacion.getSesionId(),
				obligacion.getPersonaId(),
				obligacion.getResponsable().name(),
				obligacion.getImporteOriginal(),
				obligacion.getSaldo(),
				obligacion.getMoneda(),
				obligacion.getEstado().name(),
				obligacion.getOfertaId(),
				obligacion.getSnapshotNombre(),
				obligacion.getSnapshotPrecio(),
				obligacion.getDevengadaEn(),
				obligacion.getAnuladaEn(),
				obligacion.getMotivoAnulacion(),
				obligacion.getVersion());
	}
}
