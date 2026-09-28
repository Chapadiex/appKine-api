package com.akine.activity.api.dto;

import com.akine.activity.application.AsistenciaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * El hecho de que una persona estuvo —o no— en una clase.
 *
 * <p><b>No es una Sesion clinica</b> (RN-M28-007) y <b>no devenga nada</b>: el devengo por clase
 * es de 08.06/08.07 y su politica todavia no existe en ninguna tabla.
 *
 * <p>No lleva nombre ni documento: esos viajan solo en el detalle operativo, detras de
 * {@code inscripcion:read}.
 */
@Schema(
		name = "Asistencia",
		description = "Registro operativo de participacion (RF-M28-007). No crea Sesion clinica ni "
				+ "obligacion economica.")
public record AsistenciaResponse(

		@Schema(example = "981") long id,
		@Schema(example = "77") long claseId,
		@Schema(example = "301") long personaId,
		@Schema(example = "412") long inscripcionId,

		@Schema(description = "PRESENTE, PRESENTE_TARDE o AUSENTE", example = "PRESENTE")
		String resultado,

		@Schema(
				description = "MOSTRADOR, LOTE, CIERRE o INGRESO_SIN_INSCRIPCION. Distingue una "
						+ "ausencia declarada de un no-show puesto por el cierre.",
				example = "MOSTRADOR")
		String origen,

		@Schema(description = "Instructor congelado al momento del hecho", example = "55")
		Long profesionalMembershipId,

		@Schema(description = "Nota operativa, **nunca clinica**") String observaciones,

		Instant registradaEn,

		@Schema(description = "Instante de la ultima correccion, o null") Instant corregidaEn,

		@Schema(description = "Cuantas veces se corrigio. El detalle esta en el historial.", example = "0")
		int correcciones,

		@Schema(example = "0") long version) {

	public static AsistenciaResponse de(AsistenciaView view) {
		return new AsistenciaResponse(
				view.id(), view.claseId(), view.personaId(), view.inscripcionId(),
				view.resultado(), view.origen(), view.profesionalMembershipId(),
				view.observaciones(), view.registradaEn(), view.corregidaEn(),
				view.correcciones(), view.version());
	}
}
