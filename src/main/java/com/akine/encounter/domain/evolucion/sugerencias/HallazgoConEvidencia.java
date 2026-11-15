package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.Hallazgo;

import java.util.List;

/** Un hallazgo elegido con los pasajes que lo respaldan. */
public record HallazgoConEvidencia(Hallazgo hallazgo, List<PasajeEnviado> pasajes) {

	public HallazgoConEvidencia {
		pasajes = List.copyOf(pasajes);
	}
}
