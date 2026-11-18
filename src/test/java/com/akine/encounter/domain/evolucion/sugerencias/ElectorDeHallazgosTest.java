package com.akine.encounter.domain.evolucion.sugerencias;

import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo;
import com.akine.encounter.domain.evolucion.Hallazgo.Severidad;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElectorDeHallazgosTest {

	private static Hallazgo h(CodigoDeHallazgo codigo, Severidad severidad) {
		return new Hallazgo(codigo, severidad, List.of(), List.of(), null);
	}

	@Test
	void sinElegiblesDevuelveVacio() {
		assertThat(ElectorDeHallazgos.elegir(List.of(h(CodigoDeHallazgo.DATOS_INSUFICIENTES, Severidad.INFO),
				h(CodigoDeHallazgo.SIN_PLAN_ACTIVO, Severidad.ATENCION)))).isEmpty();
	}

	@Test
	void atencionAntesQueInfoYLuegoOrdenDelEnumConTope3() {
		List<Hallazgo> elegidos = ElectorDeHallazgos.elegir(List.of(
				h(CodigoDeHallazgo.DOLOR_SIN_MEJORA, Severidad.INFO),
				h(CodigoDeHallazgo.MALA_TOLERANCIA, Severidad.ATENCION),
				h(CodigoDeHallazgo.BAJA_ADHERENCIA, Severidad.INFO),
				h(CodigoDeHallazgo.EVOLUCION_DESFAVORABLE, Severidad.ATENCION),
				h(CodigoDeHallazgo.SIN_PLAN_ACTIVO, Severidad.ATENCION)));
		assertThat(elegidos).extracting(Hallazgo::codigo).containsExactly(CodigoDeHallazgo.EVOLUCION_DESFAVORABLE,
				CodigoDeHallazgo.MALA_TOLERANCIA, CodigoDeHallazgo.DOLOR_SIN_MEJORA);
	}
}
