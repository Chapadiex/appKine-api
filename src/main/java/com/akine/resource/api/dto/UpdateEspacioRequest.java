package com.akine.resource.api.dto;

import com.akine.resource.domain.EspacioTipo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Edicion de un espacio (RF-M04-002, RF-M04-007).
 *
 * <p>Semantica de PATCH: los campos omitidos no se tocan. Para borrar {@code notes} se manda
 * cadena vacia.
 *
 * <p><b>{@code version} es obligatoria</b> y se compara antes de mutar. Sin ella dos ediciones
 * simultaneas se pisan y el segundo en guardar borra el cambio del primero sin que nadie se
 * entere.
 *
 * <h2>Por que ningun componente de este record es un primitivo</h2>
 *
 * <p><b>Jackson 3 pasa {@code null} por cada componente AUSENTE de un record</b>, y con
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} activo —el default— eso revienta con
 * {@code HttpMessageNotReadableException} antes de llegar al controller. El sintoma es un
 * <b>400 "cuerpo invalido"</b> sobre un JSON perfectamente valido, y el mensaje no nombra el
 * campo culpable en la respuesta.
 *
 * <p>En un DTO de PATCH la mitad de los campos siempre viene ausente, asi que un solo primitivo
 * hace ilegible cualquier request parcial. {@code UpdateConsultorioRequest} tiene un
 * {@code long version} y nunca lo sufrio porque ese campo es obligatorio y el cliente siempre lo
 * manda: la trampa solo aparece cuando el primitivo es opcional. Aca
 * {@code clearValidUntil} es {@code Boolean} y {@code null} se lee como {@code false}.
 */
@Schema(description = "Cambios a aplicar sobre un espacio. Los campos omitidos no se modifican")
public record UpdateEspacioRequest(

		@Schema(description = "Nuevo nombre. Unico entre los espacios vigentes de la sede",
				example = "Box 1 - Traumatologia",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(description = "Nueva clasificacion fisica",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		EspacioTipo tipo,

		@Schema(
				description = "Nueva capacidad. Reducirla por debajo de lo ya comprometido "
						+ "responde 409 espacio-capacity-below-occupancy",
				example = "2",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 1, message = "La capacidad minima es 1")
		@Max(value = 1000, message = "La capacidad maxima es 1000")
		Integer capacidad,

		@Schema(description = "Nueva observacion. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La observacion no puede superar los 280 caracteres")
		String notes,

		@Schema(description = "Nuevo inicio de la ventana operativa, en UTC",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validFrom,

		@Schema(
				description = "Nuevo fin de la ventana operativa, EXCLUSIVO, en UTC. Se ignora "
						+ "si clearValidUntil es true",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validUntil,

		@Schema(
				description = "Deja el espacio SIN fin de vigencia. Hace explicito lo que un "
						+ "campo omitido no puede expresar: 'no toques el fin' y 'sacale el fin' "
						+ "son dos intenciones distintas y con un solo campo nulable la segunda "
						+ "no se puede pedir",
				example = "false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean clearValidUntil,

		@Schema(
				description = "Version que el cliente leyo. Si quedo vieja, 409 "
						+ "concurrent-modification y hay que recargar",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version es obligatoria para editar un espacio")
		Long version) {
}
