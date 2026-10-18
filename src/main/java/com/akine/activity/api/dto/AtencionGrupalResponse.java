package com.akine.activity.api.dto;

import com.akine.activity.application.PanelDeAtencionGrupalView;
import com.akine.activity.application.ParticipanteEnAtencionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * El panel de atencion grupal de una clase (AKINE-08.05): cada participante con su asistencia y
 * sus atenciones. Sin contenido clinico; cada ficha se abre por su Sesion.
 */
@Schema(
		name = "AtencionGrupal",
		description = "Quien estuvo y que atencion clinica tiene cada participante. Sin contenido "
				+ "clinico: cada ficha se abre por su Sesion.")
public record AtencionGrupalResponse(
		@Schema(example = "640") long claseId,
		@Schema(description = "`false` si la oferta de la clase no genera registro clinico: no "
				+ "puede haber atenciones (RN-M14-008)")
		boolean clinica,
		List<Participante> participantes) {

	@Schema(name = "ParticipanteEnAtencionGrupal", description = "Un participante con asistencia")
	public record Participante(
			@Schema(example = "412") long inscripcionId,
			@Schema(example = "77") long personaId,
			@Schema(
					description = "Del padron, resuelto al leer. Ausente si la ficha no se "
							+ "resolvio. Junto con `apellido` es lo unico de la persona que viaja: "
							+ "ni documento ni contacto",
					example = "Ana")
			String nombre,
			@Schema(description = "Del padron, resuelto al leer. Ausente si la ficha no se resolvio",
					example = "Perez")
			String apellido,
			@Schema(example = "933") long asistenciaId,
			@Schema(allowableValues = {"PRESENTE", "PRESENTE_TARDE", "AUSENTE"})
			String resultadoAsistencia,
			@Schema(description = "`true` si estuvo: solo entonces se lo puede atender")
			boolean estuvo,
			@Schema(description = "Una por derivacion atendida. Vacia si todavia no tiene")
			List<AtencionDeParticipanteResponse> atenciones) {

		static Participante de(ParticipanteEnAtencionView p) {
			return new Participante(p.inscripcionId(), p.personaId(), p.nombre(), p.apellido(),
					p.asistenciaId(),
					p.resultadoAsistencia(), p.estuvo(),
					p.atenciones().stream()
							.map(a -> AtencionDeParticipanteResponse.de(p.inscripcionId(), a))
							.toList());
		}
	}

	public static AtencionGrupalResponse de(PanelDeAtencionGrupalView panel) {
		return new AtencionGrupalResponse(panel.claseId(), panel.clinica(),
				panel.participantes().stream().map(Participante::de).toList());
	}
}
