package com.akine.activity.api.dto;

import com.akine.activity.application.ElegibilidadClinicaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ElegibilidadClinicaDeParticipante",
		description = "Veredicto GRUPAL, sin efectos y sin leer nada clinico: si la oferta es "
				+ "clinica, si la persona estuvo y si ya es paciente. Caso, Plan, autorizacion y "
				+ "permiso clinico se deciden al derivar.")
public record ElegibilidadClinicaResponse(
		@Schema(example = "301") long inscripcionId,
		@Schema(example = "512") long personaId,
		@Schema(description = "null si no hay asistencia registrada") Long asistenciaId,
		boolean ofertaClinica,
		boolean requiereCasoClinico,
		@Schema(description = "PRESENTE, PRESENTE_TARDE, AUSENTE o null") String resultadoAsistencia,
		boolean esPacienteVigente,
		@Schema(description = "Derivar exige activarPerfilPaciente = true. No bloquea por si solo")
		boolean requiereActivarPerfil,
		boolean derivable,
		@Schema(description = "Bloqueos: OFERTA_NO_CLINICA, SIN_ASISTENCIA, AUSENTE")
		List<String> motivos) {

	public static ElegibilidadClinicaResponse de(ElegibilidadClinicaView v) {
		return new ElegibilidadClinicaResponse(
				v.inscripcionId(), v.personaId(), v.asistenciaId(), v.ofertaClinica(),
				v.requiereCasoClinico(), v.resultadoAsistencia(), v.esPacienteVigente(),
				v.requiereActivarPerfil(), v.derivable(), v.motivos());
	}
}
