package com.akine.billing.api.dto;

import com.akine.billing.application.CobroCommand;
import com.akine.billing.domain.MedioDePago;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Un cobro: cuanto, por que via entro y contra que deudas se aplica.
 *
 * <p><b>Las dos sumas tienen que dar el total</b> y el servidor las verifica: la de los medios y la
 * de las imputaciones. Son invariantes de RN-M19 y no se pueden expresar como constraint de base
 * —MySQL no admite subconsultas en un CHECK— asi que un cuerpo que no las cumple es 400.
 */
@Schema(
		name = "RegistrarCobro",
		description = "Dinero recibido, con sus medios y sus imputaciones. La suma de los medios y "
				+ "la de las imputaciones tienen que dar el total.")
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
						+ "juntas. **Su suma tiene que dar el total**: mientras no existan los "
						+ "anticipos —AKINE-07.03, junto con la Caja— todo el dinero recibido tiene "
						+ "que aplicarse a alguna deuda.")
		@NotEmpty @Valid List<ImputacionRequest> imputaciones,

		@Schema(
				description = "Clave del cliente para que un reintento no cobre dos veces. "
						+ "Reusarla con **otro** contenido devuelve 409, no el cobro anterior.",
				example = "c4e2a1b0-7d3f-4e59-9a12-6b7c8d9e0f1a")
		@Size(max = 80) String idempotencyKey) {

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
				imputaciones.stream()
						.map(imputacion -> new CobroCommand.ImputacionPedida(
								imputacion.obligacionId(), imputacion.importe()))
						.toList(),
				idempotencyKey);
	}
}
