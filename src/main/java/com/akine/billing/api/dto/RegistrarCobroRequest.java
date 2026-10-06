package com.akine.billing.api.dto;

import com.akine.billing.application.CobroCommand;
import com.akine.billing.domain.MedioDePago;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Un cobro: cuanto, por que via entro y contra que deudas se aplica.
 *
 * <p><b>Las dos sumas tienen que dar el total</b> y el servidor las verifica: la de los medios, y la
 * de las imputaciones mas el anticipo declarado (F-3). Son invariantes de RN-M19 y no se pueden
 * expresar como constraint de base —MySQL no admite subconsultas en un CHECK— asi que un cuerpo que
 * no las cumple es 400.
 */
@Schema(
		name = "RegistrarCobro",
		description = "Dinero recibido, con sus medios, sus imputaciones y su anticipo. La suma de "
				+ "los medios tiene que dar el total, y la de las imputaciones mas el anticipo "
				+ "tambien.")
public record RegistrarCobroRequest(

		@Schema(description = "Quien paga", example = "128")
		@NotNull @Positive Long personaId,

		@Schema(description = "Total recibido. Decimal exacto, nunca float.", example = "8500.00")
		@NotNull @DecimalMin(value = "0.01") BigDecimal total,

		@Schema(
				description = "Por que via entro. Puede ser mas de uno: mitad efectivo y mitad "
						+ "tarjeta es normal. **Su suma tiene que dar el total.**")
		@NotEmpty @Valid List<MedioRequest> medios,

		@Schema(
				description = "Contra que deudas se aplica. Un cobro puede saldar varias sesiones "
						+ "juntas. **Su suma mas el `anticipo` tiene que dar el total.** Puede "
						+ "venir vacia si todo el cobro es anticipo.")
		@Valid List<ImputacionRequest> imputaciones,

		@Schema(
				description = "Clave del cliente para que un reintento no cobre dos veces. "
						+ "Reusarla con **otro** contenido devuelve 409, no el cobro anterior.",
				example = "c4e2a1b0-7d3f-4e59-9a12-6b7c8d9e0f1a")
		@Size(max = 80) String idempotencyKey,

		@Schema(
				description = "Lo que queda **a favor** del paciente (anticipo, DP-06). Se declara: "
						+ "lo que no se imputa y no se declara aca es un 400 `cobro-no-cuadra`, no un "
						+ "saldo a favor silencioso. Entra a la caja ahora y se imputa despues con "
						+ "`imputarSaldoAFavor`. Por defecto cero.",
				example = "0.00")
		@DecimalMin(value = "0.00") BigDecimal anticipo,

		@Schema(
				description = "Moneda del cobro. **Obligatoria si no hay imputaciones**: sin deudas no "
						+ "hay de donde tomarla. Con imputaciones sale de ellas y, si viene, tiene "
						+ "que coincidir.",
				example = "ARS")
		@Pattern(regexp = "[A-Za-z]{3}") String moneda) {

	@Schema(name = "MedioDeCobro")
	public record MedioRequest(

			@Schema(
					description = "`OTRO` existe para billeteras virtuales, cheques o descuentos de "
							+ "convenio. Sin ese valor el operador elegiria el medio equivocado "
							+ "—normalmente efectivo— y el arqueo no cerraria por una razon que "
							+ "nadie podria rastrear.",
					allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"},
					example = "EFECTIVO")
			@NotNull MedioDePago medio,

			@Schema(example = "8500.00")
			@NotNull @DecimalMin(value = "0.01") BigDecimal importe,

			@Schema(description = "Numero de operacion, ultimos digitos, lo que corresponda", example = "op-99182")
			@Size(max = 120) String referencia) {
	}

	@Schema(name = "ImputacionDeCobro")
	public record ImputacionRequest(

			@Schema(example = "9001")
			@NotNull @Positive Long obligacionId,

			@Schema(description = "Cuanto de este cobro paga esa deuda", example = "8500.00")
			@NotNull @DecimalMin(value = "0.01") BigDecimal importe) {
	}

	public CobroCommand aDominio() {
		return new CobroCommand(
				personaId,
				total,
				medios.stream()
						.map(medio -> new CobroCommand.MedioPedido(
								medio.medio(), medio.importe(), medio.referencia()))
						.toList(),
				imputaciones == null
						? List.of()
						: imputaciones.stream()
								.map(imputacion -> new CobroCommand.ImputacionPedida(
										imputacion.obligacionId(), imputacion.importe()))
								.toList(),
				idempotencyKey,
				anticipo,
				moneda);
	}
}
