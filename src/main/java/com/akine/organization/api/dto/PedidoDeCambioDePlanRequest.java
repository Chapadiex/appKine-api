package com.akine.organization.api.dto;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Contratacion que el centro pide: plan, servicios y adicionales, completa")
public record PedidoDeCambioDePlanRequest(
		@Schema(description = "Codigo de un plan ofrecido", example = "CRECIMIENTO")
		@NotBlank(message = "Elegí un plan")
		String planCode,
		@Schema(description = "Servicios, al menos uno")
		@NotEmpty(message = "Elegí al menos un servicio")
		List<Servicio> servicios,
		@Schema(description = "Adicionales; vacio = ninguno")
		@NotNull(message = "Los adicionales son obligatorios: una lista vacía si no se suma ninguno")
		List<Adicional> adicionales,
		@Schema(description = "Comentario para la plataforma", example = "Empezamos con clases de yoga")
		@Size(max = 500, message = "El comentario admite hasta 500 caracteres")
		String comentario) {
}
