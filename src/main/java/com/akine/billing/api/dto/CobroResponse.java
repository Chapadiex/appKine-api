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

		@Schema(description = "Contra que deudas se aplico, al cobrar o despues. Su suma, mas el "
				+ "saldo a favor y lo reintegrado, da el total.")
		List<ImputacionResponse> imputaciones,

		@Schema(example = "0")
		long version,

		@Schema(
				description = "Lo recibido que todavia no se imputo ni se reintegro (anticipo). Se "
						+ "aplica a una deuda con `imputarSaldoAFavor` o se devuelve con "
						+ "`reintegrarSaldoAFavor`. Cero en un cobro anulado.",
				example = "0.00")
		BigDecimal saldoAFavor,

		@Schema(
				description = "Un cobro anulado **conserva su comprobante**: el numero queda usado.",
				allowableValues = {"VIGENTE", "ANULADO"},
				example = "VIGENTE")
		String estado,

		@Schema(description = "Cuando se anulo. Nulo si esta vigente.")
		Instant anuladoEn,

		@Schema(description = "Por que se anulo. Nulo si esta vigente.")
		String motivoAnulacion,

		@Schema(description = "Turno en cuya recepcion se tomo este cobro como prepago (E-6). Nulo "
				+ "en cualquier otro cobro.", example = "301")
		Long turnoId) {

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
			BigDecimal importe,
			@Schema(description = "Cuando. Igual a `cobradoEn` si nacio con el cobro; posterior si "
					+ "aplico un anticipo despues.")
			Instant imputadaEn) {
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
								imputacion.obligacionId(), imputacion.importe(), imputacion.imputadaEn()))
						.toList(),
				vista.version(),
				vista.saldoAFavor(),
				vista.estado(),
				vista.anuladoEn(),
				vista.motivoAnulacion(),
				vista.turnoId());
	}
}
