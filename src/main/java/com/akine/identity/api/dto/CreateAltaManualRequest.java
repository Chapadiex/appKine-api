package com.akine.identity.api.dto;

import com.akine.identity.application.DatosAltaManual;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/** Alta manual que prepara los datos del colaborador y le envia una invitacion. */
@Schema(description = "Datos del colaborador y la invitacion para completar su cuenta")
public record CreateAltaManualRequest(
		@NotBlank(message = "El nombre es obligatorio") String nombre,
		@NotBlank(message = "El apellido es obligatorio") String apellido,
		String tipoDocumento,
		String numeroDocumento,
		String telefono,
		@NotBlank(message = "El email para la invitacion es obligatorio")
		@Email(message = "El email no tiene un formato valido") String email,
		@NotBlank(message = "El rol es obligatorio") String roleCode,
		String profesion,
		String matricula,
		String jurisdiccion,
		String especialidadPrincipal,
		List<@NotBlank String> especialidadesAdicionales,
		@NotNull(message = "Indicá las sedes de trabajo") List<@NotNull Long> sedeIds,
		List<@Valid ServicioElegidoRequest> servicios,
		LocalDate fechaIncorporacion,
		boolean recibeTurnos,
		boolean instructorDeActividades,
		boolean activo) {

	public record ServicioElegidoRequest(
			@NotNull(message = "La sede del servicio es obligatoria") Long sedeId,
			@NotNull(message = "La oferta del servicio es obligatoria") Long ofertaId) {
	}

	public DatosAltaManual datos() {
		return new DatosAltaManual(nombre.strip(), apellido.strip(), tipoDocumento,
				numeroDocumento, telefono, profesion, matricula, jurisdiccion,
				especialidadPrincipal,
				especialidadesAdicionales == null ? List.of() : List.copyOf(especialidadesAdicionales),
				List.copyOf(sedeIds),
				servicios == null ? List.of() : servicios.stream()
						.map(item -> new DatosAltaManual.ServicioElegido(item.sedeId(), item.ofertaId()))
						.toList(),
				fechaIncorporacion == null ? null : fechaIncorporacion.toString(),
				recibeTurnos, instructorDeActividades, activo);
	}
}
