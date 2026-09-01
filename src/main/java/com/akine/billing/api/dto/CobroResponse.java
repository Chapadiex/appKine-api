package com.akine.billing.api.dto;

import com.akine.billing.application.CobroView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Un cobro registrado, con su comprobante.
 *
 * <p>Los importes viajan como decimal exacto. <b>El cliente no debe hacer aritmetica de plata en
 * coma flotante</b>: sumar totales con el operador de punto flotante produce centavos que no
 * cuadran.
 */
@Schema(name = "Cobro", description = "Dinero recibido. No es la deuda (M18) ni la caja (M20).")
public record CobroResponse(

		@Schema(example = "5001")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(example = "128")
		long personaId,

		@Schema(example = "8500.00")
		BigDecimal total,

		@Schema(example = "ARS")
		String moneda,

		@Schema(
				description = "Correlativo **por sede**. Es lo que el paciente se lleva, y un numero "
						+ "repetido seria un problema fiscal.",
				example = "142")
		int comprobanteNumero,

		@Schema(example = "2026-09-15T13:02:00Z")
		Instant cobradoEn,

		@Schema(description = "Por que via entro. Su suma da el total.")
		List<MedioResponse> medios,

		@Schema(description = "Contra que deudas se aplico. Su suma da el total.")
		List<ImputacionResponse> imputaciones,

		@Schema(example = "0")
		long version) {

	@Schema(name = "MedioDeCobroAplicado")
	public record MedioResponse(
			@Schema(allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"})
			String medio,
			BigDecimal importe,
			String referencia) {
	}

	@Schema(name = "ImputacionAplicada")
	public record ImputacionResponse(
			long obligacionId,
			@Schema(description = "Lo que ESTE cobro aplico. El saldo que quedo vive en la obligacion.")
			BigDecimal importe) {
	}

	public static CobroResponse de(CobroView vista) {
		return new CobroResponse(
				vista.id(), vista.consultorioId(), vista.personaId(), vista.total(), vista.moneda(),
				vista.comprobanteNumero(), vista.cobradoEn(),
				vista.medios().stream()
						.map(medio -> new MedioResponse(medio.medio(), medio.importe(), medio.referencia()))
						.toList(),
				vista.imputaciones().stream()
						.map(imputacion -> new ImputacionResponse(
								imputacion.obligacionId(), imputacion.importe()))
						.toList(),
				vista.version());
	}
}
