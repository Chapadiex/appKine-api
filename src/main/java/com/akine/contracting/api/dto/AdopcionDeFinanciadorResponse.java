package com.akine.contracting.api.dto;

import com.akine.contracting.application.AdopcionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Resultado de adoptar un financiador del catalogo global (DP-43)")
public record AdopcionDeFinanciadorResponse(

		@Schema(description = "La copia del centro. Es un financiador de la organizacion como "
				+ "cualquier otro, con catalogoId apuntando al origen")
		FinanciadorResponse financiador,

		@Schema(description = "Los planes de la copia. Al crearla, los planes ACTIVOS del catalogo; "
				+ "si ya existia, los que tenga hoy, incluidos los que el centro agrego")
		List<PlanCoberturaResponse> planes,

		@Schema(description = "true si esta llamada creo la copia (201); false si ya existia y se "
				+ "devuelve sin tocar nada (200)")
		boolean creado) {

	public static AdopcionDeFinanciadorResponse de(AdopcionView view) {
		return new AdopcionDeFinanciadorResponse(
				FinanciadorResponse.de(view.financiador()),
				view.planes().stream().map(PlanCoberturaResponse::de).toList(),
				view.creado());
	}
}
