package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja LOGICA de un adjunto clinico (RF-M25-004).
 *
 * <p><b>El binario no se borra del disco.</b> Es lo que hace reversible en los hechos una baja
 * por error sobre un estudio clinico, y lo que permite que el historico siga resolviendo: el
 * contenido se sigue descargando. Un job de limpieza sobre contenidos realmente huerfanos es otra
 * cosa y necesita una politica de retencion que nadie escribio.
 *
 * <p>No lleva {@code expectedVersion}, a diferencia de la baja de una entrada: lo unico mutable
 * de un adjunto es su clasificacion, y el unico conflicto real —"ya estaba dado de baja"— no es
 * un conflicto: se responde con el adjunto tal como quedo, con su motivo original intacto.
 */
@Schema(description = "Baja logica de un adjunto clinico. El contenido se sigue descargando")
public record BajaDeAdjuntoClinicoRequest(

		@Schema(description = "Motivo declarado de la baja. Obligatorio",
				example = "Estudio cargado en la historia equivocada",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo) {
}
