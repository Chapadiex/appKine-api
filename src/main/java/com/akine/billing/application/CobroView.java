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
 * @param saldoAFavor       lo recibido que todavia no se imputo ni se reintegro (F-3). Cero en un
 *                          cobro anulado
 * @param estado            {@code VIGENTE} o {@code ANULADO}. Un cobro anulado conserva su
 *                          comprobante: el numero queda usado
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
		long version,
		BigDecimal saldoAFavor,
		String estado,
		Instant anuladoEn,
		String motivoAnulacion,
		Long turnoId) {

	public static final String VIGENTE = "VIGENTE";
	public static final String ANULADO = "ANULADO";

	public record MedioView(String medio, BigDecimal importe, String referencia) {
	}

	/**
	 * @param importe    lo que ESTE cobro aplico a esa deuda. El saldo que quedo vive en la
	 *                   obligacion: duplicarlo aca habilitaria que las dos versiones discrepen
	 * @param imputadaEn cuando. Igual a {@code cobradoEn} para las que nacieron con el cobro; posterior
	 *                   para las que aplicaron un anticipo despues
	 */
	public record ImputacionView(long obligacionId, BigDecimal importe, Instant imputadaEn) {
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
								imputacion.getObligacionId(), imputacion.getImporte(),
								imputacion.getImputadaEn()))
						.toList(),
				cobro.getVersion(),
				cobro.getSaldoAFavor(),
				cobro.estaAnulado() ? ANULADO : VIGENTE,
				cobro.getAnuladoEn(),
				cobro.getMotivoAnulacion(),
				cobro.getTurnoId());
	}
}
