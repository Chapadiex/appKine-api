package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * El equipo tratante que el caso tiene que tener (RF-M10-005).
 *
 * <p>Es un <b>PUT y un reemplazo completo</b>, no un alta ni una baja por integrante: quien mira
 * la pantalla ve una lista y guarda la lista, y expresar eso con dos operaciones obligaria al
 * cliente a calcular el diff — y a equivocarse la primera vez que dos personas editan a la vez.
 *
 * <p>Lo que sale de la lista <b>no se borra</b>: se le marca la fecha de salida. Un profesional
 * desvinculado sigue figurando en el caso que trato, porque lo trato (regla maestra 10). Una lista
 * vacia deja el caso sin equipo vigente y con su historial intacto.
 */
@Schema(description = "Reemplazo completo del equipo tratante de un caso")
public record EquipoDelCasoRequest(

		@Schema(description = "Equipo completo despues del cambio. Vacia deja el caso sin equipo "
				+ "vigente; nadie desaparece del historial",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El equipo es obligatorio: una lista vacia es explicita, ausente no")
		@Valid
		List<IntegranteDelEquipoRequest> integrantes,

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista. EL CAMBIO DE "
				+ "EQUIPO NO TOCA NINGUNA COLUMNA DEL CASO —escribe en otra tabla— asi que el "
				+ "backend fuerza el avance de esta version al guardar: sin eso, dos cambios "
				+ "concurrentes commitean los dos y el resultado no es ninguno de los dos.",
				example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
