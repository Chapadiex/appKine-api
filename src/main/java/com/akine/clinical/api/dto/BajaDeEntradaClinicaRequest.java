package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Baja LOGICA de una entrada clinica (regla maestra 10).
 *
 * <p>Dar de baja una entrada <b>no borra ninguna de sus versiones</b>: la entrada sale del
 * timeline y sigue siendo consultable por su id, que es lo que distingue "no lo muestres" de "no
 * existio".
 *
 * <p>Lleva {@code expectedVersion}, a diferencia de la baja de un adjunto administrativo: una
 * entrada <b>si</b> se edita en paralelo —las enmiendas— y dar de baja lo que otro acaba de
 * enmendar sin haberlo leido es exactamente el caso que el bloqueo optimista existe para evitar.
 */
@Schema(description = "Baja logica de una entrada clinica. Sus versiones no se tocan")
public record BajaDeEntradaClinicaRequest(

		@Schema(description = "Motivo declarado de la baja. Obligatorio",
				example = "Cargada en la historia equivocada",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String motivo,

		@Schema(description = "Version de la cabecera que el operador leyo",
				example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
