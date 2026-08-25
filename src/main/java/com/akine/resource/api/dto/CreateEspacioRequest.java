package com.akine.resource.api.dto;

import com.akine.resource.domain.EspacioTipo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Alta de un espacio en una sede (RF-M04-001, RF-M04-007).
 *
 * <p><b>Solo el nombre es obligatorio.</b> RNF-M04-005 pide interacciones compactas, y el caso
 * abrumadoramente mas frecuente —un box individual, en servicio desde ya, sin fin previsto— no
 * deberia exigir cargar nada mas. Los defaults son {@code tipo = BOX},
 * {@code capacidad = 1}, {@code validFrom = ahora} y {@code validUntil = null}.
 */
@Schema(description = "Datos para dar de alta un espacio en la sede")
public record CreateEspacioRequest(

		@Schema(
				description = "Nombre operativo del espacio. Unico entre los espacios VIGENTES "
						+ "de la sede: el nombre de uno dado de baja se puede reusar",
				example = "Box 1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre del espacio es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(
				description = "Clasificacion fisica del recurso. Si se omite, BOX. NO habilita "
						+ "servicios ni define roles: eso lo resuelve la habilitacion por Oferta "
						+ "de Servicio (RN-M04-007)",
				example = "BOX",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		EspacioTipo tipo,

		@Schema(
				description = "Personas simultaneas que admite. Si se omite, 1, que es el caso "
						+ "de un box individual",
				example = "1",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 1, message = "La capacidad minima es 1")
		@Max(value = 1000, message = "La capacidad maxima es 1000")
		Integer capacidad,

		@Schema(description = "Observacion operativa libre. Nunca contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La observacion no puede superar los 280 caracteres")
		String notes,

		@Schema(
				description = "Instante UTC desde el que el espacio esta en servicio. Si se "
						+ "omite, el momento del alta. Es la ventana OPERATIVA, distinta de la "
						+ "baja logica",
				example = "2026-09-01T00:00:00Z",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validFrom,

		@Schema(
				description = "Instante UTC hasta el que esta en servicio, EXCLUSIVO. Omitirlo "
						+ "significa sin fin previsto, que es lo normal",
				example = "2027-01-01T00:00:00Z",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validUntil) {
}
