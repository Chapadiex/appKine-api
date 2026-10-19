package com.akine.encounter.api.dto;

import com.akine.encounter.application.InicioSinTurno;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Una atencion sin turno (DP-43, U-24): a quien, que, en que caso y por que.
 */
@Schema(
		name = "IniciarSesionSinTurno",
		description = "Atencion sin turno. El motivo es obligatorio; el caso, solo si la oferta lo "
				+ "exige.")
public record IniciarSesionSinTurnoRequest(

		@Schema(description = "Persona que se atiende. Tiene que tener perfil de paciente vigente.",
				example = "88")
		@NotNull @Positive Long personaId,

		@Schema(description = "Oferta de la sede que se presta. Vigente hoy y con quien atiende "
				+ "habilitado.", example = "42")
		@NotNull @Positive Long ofertaId,

		@Schema(description = "Caso activo de la historia del paciente. **Obligatorio** si la oferta "
				+ "tiene `requiereCasoClinico` (409 `caso-clinico-requerido`); opcional en las demas.",
				example = "12", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive Long casoId,

		@Schema(description = "Por que se atiende sin turno. Obligatorio, hasta 500 caracteres. No "
				+ "va a la auditoria.", example = "Paciente con dolor agudo que espero sin turno")
		@NotBlank @Size(max = 500) String motivo,

		@Schema(description = "Clave del cliente para que un reintento —el doble click— no abra una "
				+ "segunda atencion. Reusarla con **otro** paciente, oferta o caso devuelve 409 "
				+ "`idempotency-key-conflict`. Omitirla desactiva la proteccion.",
				example = "b3f1c2a4-5d6e-4f70-8a91-2c3d4e5f6a7b",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 80) String idempotencyKey) {

	public InicioSinTurno aDominio() {
		return new InicioSinTurno(personaId, ofertaId, casoId, motivo);
	}
}
