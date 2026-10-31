package com.akine.scheduling.application;

import com.akine.scheduling.application.DisponibilidadView.HorarioDisponibleView;

import java.time.Instant;
import java.util.List;

/**
 * Lo que se reservaria (TURNO - Tecnico §20). <b>No crea nada</b>: es la propuesta que el operador
 * revisa, y lo que confirma es lo que queda de ella.
 *
 * @param pendientesDelPlan sesiones que le quedan al plan para esa prestacion, si hay plan
 */
public record PrevisualizacionDeSerieView(
		long ofertaId,
		String ofertaNombre,
		int duracionMinutos,
		String timezone,
		Integer pendientesDelPlan,
		List<OcurrenciaPrevistaView> ocurrencias) {

	/**
	 * @param estado     DISPONIBLE, NO_DISPONIBLE o FERIADO
	 * @param motivo     por que no, para mostrarlo
	 * @param sugerencia el horario mas cercano si este no se puede: ese dia o los siguientes
	 */
	public record OcurrenciaPrevistaView(
			int numero,
			Instant inicio,
			Instant fin,
			Long profesionalId,
			String profesionalNombre,
			String espacioNombre,
			String estado,
			String motivo,
			HorarioDisponibleView sugerencia) {
	}
}
