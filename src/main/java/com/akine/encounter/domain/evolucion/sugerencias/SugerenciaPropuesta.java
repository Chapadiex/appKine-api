package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;

import java.util.List;

/** Una sugerencia del modelo que paso el validador. */
public record SugerenciaPropuesta(CodigoDeHallazgo hallazgo, String texto, String fundamento,
		List<PasajeEnviado> citas) {

	public SugerenciaPropuesta {
		citas = List.copyOf(citas);
	}
}
