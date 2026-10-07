package com.akine.scheduling.application;

import com.akine.scheduling.domain.AlcanceDeSerie;
import com.akine.scheduling.domain.Turno;

import java.util.List;

/**
 * Los turnos que una operacion con alcance toca y los que deja como estan, con su motivo
 * (AKINE E-3). Lo devuelven la previsualizacion —antes de confirmar— y el comando —despues—.
 */
public record AlcanceDeSerieView(
		long serieId,
		String alcance,
		Long turnoId,
		List<TurnoView> afectados,
		List<Omitido> omitidos) {

	public record Omitido(TurnoView turno, String motivo) {
	}

	static AlcanceDeSerieView de(
			long serieId, AlcanceDeSerie alcance, Long turnoId,
			List<Turno> afectados, List<SeleccionDeAlcance.Omitido> omitidos) {

		return new AlcanceDeSerieView(
				serieId,
				alcance.name(),
				turnoId,
				afectados.stream().map(TurnoView::de).toList(),
				omitidos.stream()
						.map(omitido -> new Omitido(TurnoView.de(omitido.turno()), omitido.motivo().name()))
						.toList());
	}
}
