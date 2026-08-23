package com.akine.organization.api.dto;

import com.akine.organization.application.PlanLimitView;
import com.akine.organization.spi.LimitCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un limite cuantitativo incluido en un plan del catalogo.
 *
 * <p>{@code value} nulo significa <b>ilimitado</b>, no "cero" ni "sin definir". Se expone
 * ademas {@code unlimited} como booleano explicito porque un cliente generado que reciba
 * {@code null} tiene que ramificar de todas formas, y una bandera nombrada evita que alguien
 * interprete la ausencia del campo como un limite de 0 —que seria exactamente al reves.
 */
@Schema(description = "Limite cuantitativo incluido en un plan")
public record PlanLimitResponse(

		@Schema(description = "Codigo del limite", example = "MAX_CONSULTORIOS")
		LimitCode code,

		@Schema(
				description = "Valor maximo permitido. Ausente cuando el limite es ilimitado",
				example = "3")
		Integer value,

		@Schema(description = "true cuando el plan no impone tope para este limite", example = "false")
		boolean unlimited) {

	public static PlanLimitResponse from(PlanLimitView view) {
		return new PlanLimitResponse(view.code(), view.value(), view.unlimited());
	}
}
