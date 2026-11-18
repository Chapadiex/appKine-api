package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Elige los hallazgos que vale la pena respaldar con bibliografia (diseno fase 2, 5.1). */
public final class ElectorDeHallazgos {

	public static final Set<CodigoDeHallazgo> ELEGIBLES = EnumSet.of(
			CodigoDeHallazgo.DOLOR_SIN_MEJORA, CodigoDeHallazgo.DOLOR_EN_AUMENTO,
			CodigoDeHallazgo.EVOLUCION_DESFAVORABLE, CodigoDeHallazgo.MALA_TOLERANCIA,
			CodigoDeHallazgo.MEDICION_SIN_CAMBIO, CodigoDeHallazgo.MEDICION_EMPEORA,
			CodigoDeHallazgo.BAJA_ADHERENCIA, CodigoDeHallazgo.FRECUENCIA_BAJO_PLAN,
			CodigoDeHallazgo.PLAN_EXCEDIDO_EN_TIEMPO);

	static final int MAXIMO = 3;

	private ElectorDeHallazgos() {
	}

	/** ATENCION antes que INFO; a igual severidad, orden del enum; hasta 3. */
	public static List<Hallazgo> elegir(List<Hallazgo> hallazgos) {
		return hallazgos.stream()
				.filter(h -> ELEGIBLES.contains(h.codigo()))
				.sorted(Comparator.comparing((Hallazgo h) -> h.severidad() == Hallazgo.Severidad.ATENCION ? 0 : 1)
						.thenComparing(h -> h.codigo().ordinal()))
				.limit(MAXIMO)
				.toList();
	}
}
