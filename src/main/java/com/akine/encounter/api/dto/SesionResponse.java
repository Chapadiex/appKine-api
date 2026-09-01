package com.akine.encounter.api.dto;

import com.akine.encounter.application.SesionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Una atencion abierta. <b>No es un turno</b>: DP-05 los separa en maquinas de estado distintas. */
@Schema(name = "Sesion", description = "Atencion real, con su borrador y su version de autosave")
public record SesionResponse(

		@Schema(example = "501")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(
				description = "Historia Clinica del paciente, de la ORGANIZACION y no de la sede "
						+ "(DP-03). Se crea al iniciar la primera atencion si no existia.",
				example = "88")
		long historiaClinicaId,

		@Schema(description = "Turno que origino la atencion. Ausente en una atencion sin turno.", example = "301")
		Long turnoId,

		@Schema(example = "42")
		long ofertaId,

		@Schema(description = "Membership del profesional que atiende. Decide quien puede guardar.", example = "31")
		long profesionalId,

		@Schema(description = "Estado de la ATENCION, no del turno ni del cobro", allowableValues = "BORRADOR", example = "BORRADOR")
		String estado,

		@Schema(example = "2026-09-15T12:02:44Z")
		Instant iniciadaEn,

		@Schema(description = "Contenido opaco tal como se guardo. Ausente si todavia no se guardo nada.")
		String borrador,

		@Schema(example = "2026-09-15T12:18:03Z")
		Instant borradorGuardadoEn,

		@Schema(description = "**Devolvela al guardar.** Es lo que impide que dos pestanas se pisen.", example = "3")
		long version,

		@Schema(description = "Evaluacion base cargada. Todos sus campos pueden estar ausentes.")
		EvaluacionResponse evaluacion,

		@Schema(example = "2026-09-15T12:21:00Z")
		Instant evaluadaEn,

		@Schema(
				description = "Evaluacion de la sesion ANTERIOR del mismo paciente. Ausente si es "
						+ "la primera o si ninguna anterior llego a evaluarse. Viaja con la sesion "
						+ "y no en un endpoint aparte porque la pantalla la necesita en el mismo "
						+ "momento: mostrar \"la vez pasada tenia 7\" al lado del campo de dolor es "
						+ "lo que hace que se cargue una evolucion real y no la que se recuerda.")
		PreviaResponse previa,

		@Schema(
				description = "Correlativo por historia clinica: \"la sesion numero 8 de este paciente\". "
						+ "**Ausente mientras la sesion este abierta**, y es lo que la marca como cerrada.",
				example = "8")
		Integer numeroSesion,

		@Schema(description = "Cierre clinico. Ausente si la sesion sigue abierta.")
		CierreResponse cierre,

		@Schema(example = "2026-09-15T12:48:00Z")
		Instant cerradaEn) {

	@Schema(name = "CierreDeSesion", description = "Resultado y proxima conducta")
	public record CierreResponse(
			@Schema(allowableValues = {"PRESENTE", "AUSENTE"}) String asistencia,
			@Schema(description = "Lo unico que registra que se hizo mientras 06.04 no exista") String notaDeCierre,
			String respuestaTratamiento,
			@Schema(allowableValues = {"BUENA", "REGULAR", "MALA"}) String tolerancia,
			String indicaciones,
			@Schema(allowableValues = {"CONTINUA", "ALTA", "DERIVA", "REEVALUA"}) String proximaConducta) {
	}

	@Schema(name = "EvaluacionBase", description = "Dolor, funcion y objetivo de la atencion")
	public record EvaluacionResponse(
			@Schema(allowableValues = {"RAPIDA", "COMPLETA"}) String modo,
			@Schema(description = "No es un diagnostico") String motivoClinico,
			@Schema(description = "Escala 0 a 10", example = "6") Integer dolorEva,
			String dolorZona,
			@Schema(allowableValues = {"IZQUIERDA", "DERECHA", "BILATERAL", "NO_APLICA"}) String dolorLateralidad,
			@Schema(allowableValues = {"MEJOR", "IGUAL", "PEOR", "SIN_REFERENCIA"}) String evolucion,
			String objetivoSesion,
			String limitacionFuncional) {
	}

	@Schema(name = "EvaluacionPrevia", description = "Lo minimo para poder comparar")
	public record PreviaResponse(Instant iniciadaEn, Integer dolorEva, String evolucion) {
	}

	public static SesionResponse de(SesionView vista) {
		var evaluacion = vista.evaluacion();
		return new SesionResponse(
				vista.id(), vista.consultorioId(), vista.historiaClinicaId(), vista.turnoId(),
				vista.ofertaId(), vista.profesionalId(), vista.estado(), vista.iniciadaEn(),
				vista.borrador(), vista.borradorGuardadoEn(), vista.version(),
				new EvaluacionResponse(
						nombre(evaluacion.modo()),
						evaluacion.motivoClinico(),
						evaluacion.dolorEva(),
						evaluacion.dolorZona(),
						nombre(evaluacion.dolorLateralidad()),
						nombre(evaluacion.evolucion()),
						evaluacion.objetivoSesion(),
						evaluacion.limitacionFuncional()),
				vista.evaluadaEn(),
				vista.previa() == null ? null : new PreviaResponse(
						vista.previa().iniciadaEn(),
						vista.previa().dolorEva(),
						vista.previa().evolucion()),
				vista.numeroSesion(),
				vista.numeroSesion() == null ? null : new CierreResponse(
						nombre(vista.cierre().asistencia()),
						vista.cierre().notaDeCierre(),
						vista.cierre().respuestaTratamiento(),
						nombre(vista.cierre().tolerancia()),
						vista.cierre().indicaciones(),
						nombre(vista.cierre().proximaConducta())),
				vista.cerradaEn());
	}

	/** Los enums viajan como texto para que el cliente no dependa del enum de este modulo. */
	private static String nombre(Enum<?> valor) {
		return valor == null ? null : valor.name();
	}
}
