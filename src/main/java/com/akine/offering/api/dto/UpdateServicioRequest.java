package com.akine.offering.api.dto;

import com.akine.offering.domain.Modalidad;
import com.akine.offering.domain.Naturaleza;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Edicion de un servicio del catalogo global.
 *
 * <p><b>El codigo no esta y no puede estar.</b> Es la clave con la que las ofertas de todos los
 * centros lo referencian: cambiarlo seria reescribir el significado de filas que ya existen en
 * tenants que no participan de esta llamada. Renombrar es cambiar {@code nombre}.
 *
 * <p>Los campos que llegan en {@code null} <b>no se tocan</b>. Es una edicion parcial declarada:
 * mandar el recurso entero obligaria a la pantalla a releerlo antes de cada guardado para no
 * pisar lo que otro cambio en el medio, y el control optimista de {@code expectedVersion} ya
 * resuelve ese problema mejor.
 */
@Schema(description = "Campos a modificar de un servicio del catalogo global")
public record UpdateServicioRequest(

		@Schema(description = "Nombre visible nuevo. Null deja el actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(description = "Descripcion nueva. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(description = "Naturaleza nueva. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Naturaleza naturaleza,

		@Schema(description = "Modalidad por defecto nueva. Null deja la actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Modalidad modalidadDefault,

		@Schema(description = "Default de caso clinico nuevo. Null deja el actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereCasoClinicoDefault,

		@Schema(description = "Default de registro clinico nuevo. Null deja el actual",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean generaRegistroClinicoDefault,

		@Schema(
				description = "Version que el cliente cree estar editando. Si la fila avanzo "
						+ "desde entonces, responde 409 concurrent-modification y no pisa nada",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(value = 0, message = "expectedVersion no puede ser negativo")
		Long expectedVersion) {
}
