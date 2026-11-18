package com.akine.organization.api.dto;

import com.akine.organization.domain.SolicitudDePrueba;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Pedido de prueba gratis de 30 dias, desde la landing publica")
public record SolicitudDePruebaRequest(
		@Schema(description = "Nombre de quien pide", example = "Ana")
		@NotBlank @Size(max = 80) String nombre,
		@Schema(description = "Apellido", example = "Gomez")
		@NotBlank @Size(max = 80) String apellido,
		@Schema(description = "Correo de contacto; sera el del duenio si se aprueba", example = "ana@centro.com")
		@NotBlank @Email @Size(max = 254) String email,
		@Schema(description = "Telefono, opcional", example = "+54 351 555 1234")
		@Size(max = 40) String telefono,
		@Schema(description = "Nombre del centro", example = "Centro Movimiento")
		@NotBlank @Size(max = 120) String nombreCentro,
		@Schema(description = "Ciudad, opcional", example = "Cordoba")
		@Size(max = 120) String ciudad,
		@Schema(description = "Le interesa el servicio de kinesiologia")
		boolean kinesiologia,
		@Schema(description = "Le interesa el servicio de actividades")
		boolean actividades,
		@Schema(description = "Cuantas sedes tiene")
		@NotNull SolicitudDePrueba.Sedes sedes,
		@Schema(description = "Le interesa el adicional de IA")
		boolean interesaIa,
		@Schema(description = "Comentario libre, opcional")
		@Size(max = 1000) String comentario,
		@Schema(description = "Campo que una persona deja vacio. Si llega con texto, el pedido se ignora "
				+ "y la respuesta es la misma")
		@Size(max = 200) String sitioWeb) {
}
