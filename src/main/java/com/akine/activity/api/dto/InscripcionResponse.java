package com.akine.activity.api.dto;

import com.akine.activity.application.InscripcionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Una inscripcion, tal como la ve el cliente. */
@Schema(name = "Inscripcion", description = "Un participante en una clase (M28, RN-M28-003).")
public record InscripcionResponse(

		@Schema(example = "301") long id,
		@Schema(example = "77") long claseId,
		@Schema(example = "512") long personaId,

		@Schema(
				description = "RESERVADA, CONFIRMADA, ASISTIO, AUSENTE, CANCELADA o LISTA_ESPERA "
						+ "(RN-M28-004). ASISTIO y AUSENTE los escribe AKINE-08.03.",
				example = "RESERVADA")
		String estado,

		@Schema(
				description = "Posicion de ingreso a la cola, o null si entro con lugar. **Se "
						+ "conserva despues de promover**: es la prueba de en que orden llego.",
				example = "3")
		Integer posicionEspera,

		Instant inscriptoEn,
		Instant confirmadaEn,

		@Schema(description = "Instante en que salio de la lista de espera hacia un lugar real")
		Instant promovidaEn,

		@Schema(example = "La paciente aviso que no viene") String motivoCancelacion,
		Instant canceladaEn,

		@Schema(example = "0") long version) {

	public static InscripcionResponse de(InscripcionView view) {
		return new InscripcionResponse(
				view.id(),
				view.claseId(),
				view.personaId(),
				view.estado(),
				view.posicionEspera(),
				view.inscriptoEn(),
				view.confirmadaEn(),
				view.promovidaEn(),
				view.motivoCancelacion(),
				view.canceladaEn(),
				view.version());
	}
}
