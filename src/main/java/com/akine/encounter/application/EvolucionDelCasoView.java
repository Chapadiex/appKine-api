package com.akine.encounter.application;

import com.akine.clinical.spi.PlanVigenteSnapshot;
import com.akine.encounter.domain.evolucion.AnalisisDeEvolucion;
import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo.Severidad;

import java.util.List;

/**
 * La evolucion de un caso tal como la muestra la pantalla (RF-M31-001).
 *
 * @param plan         plan ACTIVO o SUSPENDIDO; {@code null} si el caso no tiene
 * @param advertencias lo que el analisis dejo afuera: sesiones sin caso, recorte por tope
 */
public record EvolucionDelCasoView(
		long casoId,
		int numeroCaso,
		String estadoCaso,
		String diagnosticoPresuntivo,
		String objetivoTerapeutico,
		AnalisisDeEvolucion analisis,
		List<HallazgoView> hallazgos,
		PlanVigenteSnapshot plan,
		List<String> advertencias) {

	public EvolucionDelCasoView {
		hallazgos = hallazgos == null ? List.of() : List.copyOf(hallazgos);
		advertencias = advertencias == null ? List.of() : List.copyOf(advertencias);
	}

	/** Un hallazgo con su texto. */
	public record HallazgoView(
			CodigoDeHallazgo codigo,
			Severidad severidad,
			String texto,
			List<Long> sesiones,
			List<Long> turnos) {
	}
}
