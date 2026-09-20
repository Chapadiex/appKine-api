package com.akine.billing.api.dto;

import com.akine.billing.application.JornadaCajaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Un turno de caja.
 *
 * <p>Los importes viajan como decimal exacto. <b>El cliente no debe hacer aritmetica de plata en
 * coma flotante</b>: sumar totales con punto flotante produce centavos que no cuadran, y en un
 * arqueo eso es precisamente lo que se esta buscando.
 */
@Schema(
		name = "JornadaCaja",
		description = "Turno de caja de una sede. No es el cobro (M19) ni la deuda (M18).")
public record JornadaCajaResponse(

		@Schema(example = "3001")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(
				description = "Dia operativo **en la zona de la sede**, no la del servidor.",
				example = "2026-09-20")
		LocalDate fechaNegocio,

		@Schema(example = "ARS")
		String moneda,

		@Schema(allowableValues = {"ABIERTA", "CERRADA"}, example = "ABIERTA")
		String estado,

		@Schema(example = "15000.00")
		BigDecimal saldoInicial,

		@Schema(
				description = "Lo que **deberia haber en el cajon ahora** (RF-M20-005). Solo "
						+ "efectivo: tarjetas y transferencias nunca estuvieron ahi. Es el numero "
						+ "que hay que mandar como `saldoTeoricoEsperado` al cerrar.",
				example = "142500.00")
		BigDecimal saldoTeorico,

		@Schema(example = "2026-09-20T11:00:00Z")
		Instant abiertaEn,

		@Schema(example = "44")
		long abiertaPorCuentaId,

		@Schema(example = "2026-09-20T22:15:00Z")
		Instant cerradaEn,

		Long cerradaPorCuentaId,

		@Schema(description = "Congelado al cerrar: un cierre historico se explica sin recalcular.")
		BigDecimal saldoTeoricoCierre,

		@Schema(description = "Lo que se conto fisicamente.")
		BigDecimal saldoDeclarado,

		@Schema(
				description = "`declarado - teorico`. Positiva sobra, negativa falta. **Se registra: "
						+ "no se rechaza el cierre y no se ajusta con un movimiento que la haga "
						+ "desaparecer.**",
				example = "-500.00")
		BigDecimal diferencia,

		String motivoDiferencia,

		@Schema(
				description = "Cuanto movio cada medio. Vacio en los listados. Sin este desglose, un "
						+ "arqueo de 60.000 sobre un dia de 120.000 parece un faltante gigante, "
						+ "cuando la mitad entro por tarjeta.")
		List<TotalPorMedioResponse> totalesPorMedio) {

	@Schema(name = "TotalPorMedioDeCaja")
	public record TotalPorMedioResponse(
			@Schema(allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"})
			String medio,
			BigDecimal total,
			@Schema(description = "Si suma al saldo que se cuenta al arquear. Solo el efectivo.")
			boolean afectaArqueo) {
	}

	public static JornadaCajaResponse de(JornadaCajaView vista) {
		return new JornadaCajaResponse(
				vista.id(), vista.consultorioId(), vista.fechaNegocio(), vista.moneda(),
				vista.estado(), vista.saldoInicial(), vista.saldoTeorico(),
				vista.abiertaEn(), vista.abiertaPorCuentaId(),
				vista.cerradaEn(), vista.cerradaPorCuentaId(),
				vista.saldoTeoricoCierre(), vista.saldoDeclarado(), vista.diferencia(),
				vista.motivoDiferencia(),
				vista.totalesPorMedio().stream()
						.map(total -> new TotalPorMedioResponse(
								total.medio(), total.total(), total.afectaArqueo()))
						.toList());
	}
}
