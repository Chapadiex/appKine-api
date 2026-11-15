package com.akine.encounter.domain.evolucion;

import java.util.List;
import java.util.Map;

/**
 * Una alerta con lo que la origina (RF-M31-002).
 *
 * <p>No trae texto: el dominio dice que se observo y con que valores, y la redaccion la pone
 * {@code application} con una plantilla por codigo.
 *
 * @param severidad {@code INFO} o {@code ATENCION}
 * @param valores   los numeros de la plantilla, ya formateados
 */
public record Hallazgo(
		CodigoDeHallazgo codigo,
		Severidad severidad,
		List<Long> sesiones,
		List<Long> turnos,
		Map<String, String> valores) {

	public Hallazgo {
		sesiones = sesiones == null ? List.of() : List.copyOf(sesiones);
		turnos = turnos == null ? List.of() : List.copyOf(turnos);
		valores = valores == null ? Map.of() : Map.copyOf(valores);
	}

	/** Severidad de un hallazgo. */
	public enum Severidad {
		INFO,
		ATENCION
	}
}
