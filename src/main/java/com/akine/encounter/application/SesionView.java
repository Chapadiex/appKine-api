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
 * @param casoId   Caso Clinico de la atencion, o {@code null}. RF-M14-002 admite atencion sin caso
 * @param numeroEnCaso correlativo <b>dentro del caso</b> (04.03), o {@code null}. <b>No es
 *                     {@code numeroSesion}</b>, que es el correlativo por Historia Clinica: desde
 *                     esta etapa "la sesion 8" es ambigua si no se dice de que, y ninguna pantalla
 *                     puede mostrar uno solo sin decir cual es
 * @param ultimoNumeroVersion numero de la ultima version de contenido (06.06). {@code 0} mientras
 *                     la sesion esta abierta, {@code 1} desde el cierre, y uno mas por cada
 *                     enmienda. <b>No es {@code version}</b>, que es el control optimista
 * @param fueEnmendada {@code true} si la sesion se corrigio al menos una vez. Viaja calculado y no
 *                     como comparacion que la pantalla tenga que hacer: que un registro clinico
 *                     este corregido es un hecho que quien lo lee tiene derecho a ver sin pedir
 *                     el historial
 * @param derivacionId derivacion de la que salio una atencion de origen grupal (AKINE-08.05), o
 *                     {@code null} en una atencion de turno o sin turno
 * @param claseId      clase de la que salio una atencion de origen grupal, o {@code null}
 * @param claseTitulo  titulo de esa clase, o el nombre de su oferta si no tiene (08.05, web #104).
 *                     {@code null} si la atencion no es grupal
 * @param motivoSinTurno por que se atendio sin turno (DP-43); {@code null} en las demas
 * @param enmendableHasta hasta cuando se puede enmendar la sesion cerrada (U-25); {@code null}
 *                     mientras esta abierta
 */
public record SesionView(
		long id,
		long consultorioId,
		long historiaClinicaId,
		Long casoId,
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
		Integer numeroEnCaso,
		CierreDeSesion cierre,
		Instant cerradaEn,
		long version,
		int ultimoNumeroVersion,
		boolean fueEnmendada,
		Long derivacionId,
		Long claseId,
		String claseTitulo,
		String motivoSinTurno,
		Instant enmendableHasta) {

	/**
	 * La misma vista con hasta cuando se puede enmendar (U-25). Lo calcula el servicio, que es quien
	 * conoce la politica de la organizacion; {@code null} mientras la sesion esta abierta.
	 */
	public SesionView conEnmendableHasta(Instant hasta) {
		return new SesionView(id, consultorioId, historiaClinicaId, casoId, turnoId, ofertaId,
				profesionalId, estado, iniciadaEn, borrador, borradorGuardadoEn, evaluacion,
				evaluadaEn, previa, numeroSesion, numeroEnCaso, cierre, cerradaEn, version,
				ultimoNumeroVersion, fueEnmendada, derivacionId, claseId, claseTitulo,
				motivoSinTurno, hasta);
	}

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
		return de(sesion, previa, null);
	}

	/** @param claseTitulo como se llama la clase de una atencion grupal; {@code null} en las demas */
	public static SesionView de(Sesion sesion, EvaluacionPrevia previa, String claseTitulo) {
		return new SesionView(
				sesion.getId(),
				sesion.getConsultorioId(),
				sesion.getHistoriaClinicaId(),
				sesion.getCasoId(),
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
				sesion.getNumeroEnCaso(),
				new CierreDeSesion(
						sesion.getAsistencia(),
						sesion.getNotaDeCierre(),
						sesion.getRespuestaTratamiento(),
						sesion.getTolerancia(),
						sesion.getIndicaciones(),
						sesion.getProximaConducta()),
				sesion.getCerradaEn(),
				sesion.getVersion(),
				sesion.getUltimoNumeroVersion(),
				sesion.fueEnmendada(),
				sesion.getDerivacionId(),
				sesion.getClaseId(),
				claseTitulo,
				sesion.getMotivoSinTurno(),
				null);
	}
}
