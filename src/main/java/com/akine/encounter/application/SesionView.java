package com.akine.encounter.application;

import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.EvaluacionBase;
import com.akine.encounter.domain.Sesion;

import java.time.Instant;

/**
 * Una sesion abierta.
 *
 * @param borrador contenido opaco tal como se guardo. Ver la cabecera de {@code Sesion}
 * @param version  la que el cliente tiene que devolver al guardar. <b>Es el autosave</b>: sin ella
 *                 dos pestanas del mismo profesional se pisan en silencio
 */
public record SesionView(
		long id,
		long consultorioId,
		long historiaClinicaId,
		Long turnoId,
		long ofertaId,
		long profesionalId,
		String estado,
		Instant iniciadaEn,
		String borrador,
		Instant borradorGuardadoEn,
		EvaluacionBase evaluacion,
		Instant evaluadaEn,
		EvaluacionPrevia previa,
		Integer numeroSesion,
		CierreDeSesion cierre,
		Instant cerradaEn,
		long version) {

	/**
	 * La evaluacion de la sesion ANTERIOR del mismo paciente, para poder comparar.
	 *
	 * <p>Es la mitad "cambio" del requisito de la etapa. Viaja con la sesion y no en un endpoint
	 * aparte porque la pantalla la necesita en el mismo momento: mostrar "la vez pasada tenia 7"
	 * al lado del campo de dolor es lo que hace que el profesional cargue una evolucion real en vez
	 * de la que recuerda.
	 *
	 * @param iniciadaEn cuando fue esa sesion, para que la pantalla pueda decir "hace 4 dias"
	 */
	public record EvaluacionPrevia(Instant iniciadaEn, Integer dolorEva, String evolucion) {
	}

	public static SesionView de(Sesion sesion) {
		return de(sesion, null);
	}

	public static SesionView de(Sesion sesion, EvaluacionPrevia previa) {
		return new SesionView(
				sesion.getId(),
				sesion.getConsultorioId(),
				sesion.getHistoriaClinicaId(),
				sesion.getTurnoId(),
				sesion.getOfertaId(),
				sesion.getProfesionalMembershipId(),
				sesion.getEstado().name(),
				sesion.getIniciadaEn(),
				sesion.getBorrador(),
				sesion.getBorradorGuardadoEn(),
				new EvaluacionBase(
						sesion.getModo(),
						sesion.getMotivoClinico(),
						sesion.getDolorEva(),
						sesion.getDolorZona(),
						sesion.getDolorLateralidad(),
						sesion.getEvolucion(),
						sesion.getObjetivoSesion(),
						sesion.getLimitacionFuncional()),
				sesion.getEvaluadaEn(),
				previa,
				sesion.getNumeroSesion(),
				new CierreDeSesion(
						sesion.getAsistencia(),
						sesion.getNotaDeCierre(),
						sesion.getRespuestaTratamiento(),
						sesion.getTolerancia(),
						sesion.getIndicaciones(),
						sesion.getProximaConducta()),
				sesion.getCerradaEn(),
				sesion.getVersion());
	}
}
