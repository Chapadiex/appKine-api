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
		@NotBlank(message = "Indicá tu nombre") @Size(max = 80, message = "El nombre admite hasta 80 caracteres") String nombre,
		@Schema(description = "Apellido", example = "Gomez")
		@NotBlank(message = "Indicá tu apellido") @Size(max = 80, message = "El apellido admite hasta 80 caracteres") String apellido,
		@Schema(description = "Correo de contacto; sera el del duenio si se aprueba", example = "ana@centro.com")
		@NotBlank(message = "Indicá tu correo") @Email(message = "El correo no tiene un formato válido")
		@Size(max = 254, message = "El correo admite hasta 254 caracteres") String email,
		@Schema(description = "Telefono, opcional", example = "+54 351 555 1234")
		@Size(max = 40, message = "El teléfono admite hasta 40 caracteres") String telefono,
		@Schema(description = "Nombre del centro", example = "Centro Movimiento")
		@NotBlank(message = "Indicá el nombre del centro")
		@Size(max = 120, message = "El nombre del centro admite hasta 120 caracteres") String nombreCentro,
		@Schema(description = "Ciudad, opcional", example = "Cordoba")
		@Size(max = 120, message = "La ciudad admite hasta 120 caracteres") String ciudad,
		@Schema(description = "Le interesa el servicio de kinesiologia")
		boolean kinesiologia,
		@Schema(description = "Le interesa el servicio de actividades")
		boolean actividades,
		@Schema(description = "Cuantas sedes tiene")
		@NotNull(message = "Indicá cuántas sedes tiene el centro") SolicitudDePrueba.Sedes sedes,
		@Schema(description = "Le interesa el adicional de IA")
		boolean interesaIa,
		@Schema(description = "Comentario libre, opcional")
		@Size(max = 1000, message = "El comentario admite hasta 1000 caracteres") String comentario,
		@Schema(description = "Campo que una persona deja vacio. Si llega con texto, el pedido se ignora "
				+ "y la respuesta es la misma")
		@Size(max = 200, message = "El sitio web admite hasta 200 caracteres") String sitioWeb,
		@Schema(description = "Acepta los terminos y condiciones. Obligatorio en true: GET /api/v1/terms "
				+ "da la version vigente")
		Boolean aceptaTerminos,
		@Schema(description = "Version de los terminos que se acepto, la versionVigente de GET "
				+ "/api/v1/terms. Tiene que coincidir exacta", example = "2026-11-19")
		@Size(max = 40, message = "La version de los terminos admite hasta 40 caracteres") String versionTerminos) {
}
