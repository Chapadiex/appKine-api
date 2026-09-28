package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda el mostrador cuando alguien se presenta sin estar inscripto (RF-M13-007).
 *
 * <p><b>No hay asistencia sin inscripcion</b>: esta operacion crea la inscripcion tomando el lugar
 * de verdad, con la misma sentencia condicional que impide la sobreventa. Si no queda lugar, la
 * respuesta es `clase-completa` — y **no hay lista de espera**: una clase en curso no tiene cola.
 */
@Schema(
		name = "IngresoSinInscripcion",
		description = "Inscribe y marca en un solo acto. Es la unica operacion de esta etapa que "
				+ "toma cupo, y respeta el mismo techo que las inscripciones normales.")
public record IngresoSinInscripcionRequest(

		@Schema(description = "Persona del padron. **No se exige perfil de paciente.**", example = "301")
		@NotNull @Positive Long personaId,

		@Schema(description = "Normalmente PRESENTE o PRESENTE_TARDE", example = "PRESENTE_TARDE")
		@NotNull ResultadoAsistenciaApi resultado,

		@Schema(description = "Nota operativa, no clinica", example = "Se sumo sin anotarse")
		@Size(max = 500) String observaciones) {
}
