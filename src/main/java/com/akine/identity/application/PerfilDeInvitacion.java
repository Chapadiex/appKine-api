package com.akine.identity.application;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Datos personales y profesionales que la persona invitada puede corregir al aceptar. */
public record PerfilDeInvitacion(
		@Size(max = 120) String nombre,
		@Size(max = 120) String apellido,
		@Size(max = 32) String tipoDocumento,
		@Size(max = 50) String numeroDocumento,
		@Size(max = 50) String telefono,
		@Size(max = 160) String profesion,
		@Size(max = 80) String matricula,
		@Size(max = 120) String jurisdiccion,
		@Size(max = 160) String especialidadPrincipal,
		@Size(max = 20) List<@NotBlank @Size(max = 160) String> especialidadesAdicionales) {
}
