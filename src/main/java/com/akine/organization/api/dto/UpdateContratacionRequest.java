package com.akine.organization.api.dto;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Contratacion nueva de un centro, completa")
public record UpdateContratacionRequest(
		@Schema(description = "Codigo de un plan ofrecido", example = "CRECIMIENTO")
		@NotBlank(message = "Elegí un plan")
		String planCode,
		@Schema(description = "Servicios, al menos uno")
		@NotEmpty(message = "Elegí al menos un servicio")
		List<Servicio> servicios,
		@Schema(description = "Adicionales; vacio = ninguno")
		@NotNull(message = "Los adicionales son obligatorios: una lista vacía si no se suma ninguno")
		List<Adicional> adicionales,
		@Schema(description = "Si la suscripcion esta en PRUEBA, pasarla a ACTIVA. Fuera de prueba no hace nada",
				example = "false")
		boolean contratar,
		@Schema(description = "Version leida de la contratacion", example = "0")
		@NotNull(message = "La versión de la contratación es obligatoria")
		Long version) {
}
