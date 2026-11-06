package com.akine.organization.api.dto;

import com.akine.organization.application.ConsultorioView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Los datos fiscales que RIGEN para la sede, ya resueltos: los suyos si declaro
 * {@code datosFiscalesPropios}, los de la organizacion si no. La pantalla los muestra tal cual y
 * no repite la regla.
 */
@Schema(description = "Datos fiscales efectivos de la sede: propios o heredados de la organizacion")
public record DatosFiscalesEfectivosResponse(

		@Schema(description = "Razon social efectiva", example = "Centro Kinesico SRL")
		String legalName,

		@Schema(description = "Identificacion fiscal efectiva", example = "30-71234567-1")
		String taxId,

		@Schema(description = "Domicilio fiscal efectivo", example = "Av. Colon 1234, Cordoba")
		String addressLine,

		@Schema(description = "De donde salen: SEDE u ORGANIZACION", example = "ORGANIZACION")
		ConsultorioView.OrigenFiscal origen) {

	public static DatosFiscalesEfectivosResponse from(ConsultorioView.DatosFiscalesEfectivosView view) {
		return view == null ? null : new DatosFiscalesEfectivosResponse(
				view.legalName(), view.taxId(), view.addressLine(), view.origen());
	}
}
