package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Descarta el borrador con motivo.
 *
 * <p><b>Solo sobre un borrador.</b> Una presentacion enviada no se anula: ya existe del otro lado
 * del mostrador, y si el financiador la rechaza entera eso son debitos sobre todos sus items.
 */
@Schema(
		name = "AnularPresentacion",
		description = "Descarta un borrador. No borra la fila: la marca anulada y libera sus "
				+ "prestaciones para otro lote.")
public record AnularPresentacionRequest(

		@Schema(
				description = "Por que se descarta. **Obligatorio.**",
				example = "Periodo mal elegido: se rehace de agosto")
		@NotBlank @Size(max = 280) String motivo) {
}
