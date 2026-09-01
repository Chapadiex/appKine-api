package com.akine.billing.application;

import com.akine.billing.domain.Cobro;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Un cobro registrado, con su comprobante.
 *
 * <p>Los importes son {@link BigDecimal} de punta a punta: ningun numero de punto flotante toca
 * este camino.
 *
 * @param comprobanteNumero correlativo por sede. Es lo que el paciente se lleva
 */
public record CobroView(
		long id,
		long consultorioId,
		long personaId,
		BigDecimal total,
		String moneda,
		int comprobanteNumero,
		Instant cobradoEn,
		List<MedioView> medios,
		List<ImputacionView> imputaciones,
		long version) {

	public record MedioView(String medio, BigDecimal importe, String referencia) {
	}

	/**
	 * @param importe lo que ESTE cobro aplico a esa deuda. El saldo que quedo vive en la
	 *                obligacion: duplicarlo aca habilitaria que las dos versiones discrepen
	 */
	public record ImputacionView(long obligacionId, BigDecimal importe) {
	}

	public static CobroView de(Cobro cobro) {
		return new CobroView(
				cobro.getId(),
				cobro.getConsultorioId(),
				cobro.getPersonaId(),
				cobro.getTotal(),
				cobro.getMoneda(),
				cobro.getComprobanteNumero(),
				cobro.getCobradoEn(),
				cobro.getMedios().stream()
						.map(medio -> new MedioView(
								medio.getMedio().name(), medio.getImporte(), medio.getReferencia()))
						.toList(),
				cobro.getImputaciones().stream()
						.map(imputacion -> new ImputacionView(
								imputacion.getObligacionId(), imputacion.getImporte()))
						.toList(),
				cobro.getVersion());
	}
}
