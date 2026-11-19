package com.akine.identity.api.dto;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Alta de un centro desde la plataforma, con su duenio y su contratacion")
public record AltaDeCentroRequest(
		@Schema(description = "Nombre del centro", example = "Centro Movimiento")
		@NotBlank(message = "Indicá el nombre del centro")
		@Size(max = 120, message = "El nombre del centro admite hasta 120 caracteres") String nombreCentro,
		@Schema(description = "Nombre de la primera sede; sin el, el del centro", example = "Sede Centro")
		@Size(max = 120, message = "El nombre de la primera sede admite hasta 120 caracteres") String nombrePrimeraSede,
		@Schema(description = "Zona horaria IANA; sin ella, America/Argentina/Cordoba",
				example = "America/Argentina/Cordoba")
		@Size(max = 64, message = "La zona horaria admite hasta 64 caracteres") String zonaHoraria,
		@Schema(description = "Correo del duenio: recibe la invitacion como administrador del centro",
				example = "ana@centro.com")
		@NotBlank(message = "Indicá el correo del dueño")
		@Email(message = "El correo del dueño no tiene un formato válido")
		@Size(max = 254, message = "El correo del dueño admite hasta 254 caracteres") String emailDuenio,
		@Schema(description = "Codigo de un plan ofrecido", example = "CRECIMIENTO")
		@NotBlank(message = "Elegí un plan") String planCode,
		@Schema(description = "Servicios contratados, al menos uno")
		@NotEmpty(message = "Elegí al menos un servicio") List<Servicio> servicios,
		@Schema(description = "Adicionales; vacio = ninguno")
		@NotNull(message = "Los adicionales son obligatorios: una lista vacía si no se suma ninguno") List<Adicional> adicionales,
		@Schema(description = "Con true la suscripcion nace en prueba gratis por 30 dias", example = "true")
		boolean prueba,
		@Schema(description = "Pedido de la landing que este alta aprueba, si vino de uno", example = "3")
		Long solicitudId) {
}
