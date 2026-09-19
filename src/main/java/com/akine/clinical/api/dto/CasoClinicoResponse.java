package com.akine.clinical.api.dto;

import com.akine.clinical.application.CasoClinicoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Un Caso Clinico con su equipo vigente, tal como sale por la API.
 *
 * <p>Trae el equipo <b>vigente</b> y no el historico: el historico se lee con el historial del
 * caso, que es otra operacion. Mostrar los dos juntos en la ficha pondria a un profesional que
 * dejo el equipo hace un año al lado del que atiende hoy.
 *
 * <p>{@code numeroCaso} es el correlativo <b>dentro de la historia</b> y no el id: "el caso 2 de
 * este paciente". Tampoco es el numero de sesion — desde 04.03 hay tres numeros distintos dando
 * vueltas y ninguno sustituye a otro.
 */
@Schema(name = "CasoClinico",
		description = "Problema clinico concreto de un paciente, con su equipo tratante vigente")
public record CasoClinicoResponse(

		@Schema(example = "17")
		long id,

		@Schema(description = "Historia Clinica de la que cuelga. La HC es de la ORGANIZACION "
				+ "(DP-03) y existe sin casos", example = "88")
		long historiaClinicaId,

		@Schema(description = "Correlativo DENTRO de la historia: \"el caso 2 de este paciente\". "
				+ "No es el id y no es el numero de sesion", example = "2")
		int numeroCaso,

		@Schema(description = "Oferta que motiva el caso (RN-M10-006). No se edita: cambiarla "
				+ "convertiria el caso en otro caso con el mismo numero y el mismo historial",
				example = "42")
		long ofertaId,

		@Schema(description = "Sede de la OFERTA, no del caso. Las ofertas son por sede, asi que "
				+ "sin esto ofertaId no se puede volver a resolver; el caso sigue siendo de la "
				+ "organizacion", example = "7")
		long ofertaConsultorioId,

		@Schema(description = "Lo que se esta tratando. Es contenido clinico",
				example = "Gonalgia derecha post-artroscopia")
		String diagnosticoPresuntivo,

		@Schema(description = "A donde se quiere llegar. Ausente si todavia no se definio")
		String objetivoTerapeutico,

		@Schema(description = "ACTIVO o CERRADO. No hay un tercer valor y no hay baja logica: "
				+ "cerrar no es borrar", allowableValues = {"ACTIVO", "CERRADO"}, example = "ACTIVO")
		String estado,

		@Schema(example = "2026-09-19T11:02:44Z")
		Instant abiertoEn,

		@Schema(description = "Cuenta que abrio el caso", example = "8")
		long abiertoPor,

		@Schema(description = "Instante del cierre. Ausente si el caso esta activo. LA REAPERTURA "
				+ "LO LIMPIA: el cierre anterior, con su motivo y su actor, queda en el historial")
		Instant cerradoEn,

		@Schema(description = "Cuenta que cerro el caso. Ausente si esta activo")
		Long cerradoPor,

		@Schema(description = "Por que se cerro. Ausente si esta activo. Es obligatorio al cerrar: "
				+ "sin motivo, un cierre es indistinguible de un abandono")
		String motivoCierre,

		@Schema(description = "Equipo tratante VIGENTE. El historico completo se lee con el "
				+ "historial del caso")
		List<CasoProfesionalResponse> equipo,

		@Schema(description = "Devolvela al editar, cerrar, reabrir o cambiar el equipo. Dos "
				+ "profesionales del equipo sobre el mismo caso son el caso normal", example = "3")
		long version) {

	public static CasoClinicoResponse from(CasoClinicoView view) {
		return new CasoClinicoResponse(
				view.id(),
				view.historiaClinicaId(),
				view.numeroCaso(),
				view.ofertaId(),
				view.ofertaConsultorioId(),
				view.diagnosticoPresuntivo(),
				view.objetivoTerapeutico(),
				view.estado(),
				view.abiertoEn(),
				view.abiertoPor(),
				view.cerradoEn(),
				view.cerradoPor(),
				view.motivoCierre(),
				view.equipo().stream().map(CasoProfesionalResponse::from).toList(),
				view.version());
	}
}
