package com.akine.knowledge.infrastructure.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.akine.knowledge.application.manual.PasajeEncontrado;
import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.knowledge.domain.manual.PasajeDelManual;
import com.akine.knowledge.domain.manual.TipoDePasaje;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElectorDePasajeTest {

	private final ModeloDeLenguaje modelo = mock(ModeloDeLenguaje.class);
	private final ElectorDePasaje elector = new ElectorDePasaje(() -> modelo, Duration.ofSeconds(1));
	private final List<PasajeEncontrado> candidatos = List.of(candidato("caja#abrir-la-caja"),
			candidato("caja#cerrar-la-caja"));

	@Test
	void idValidoDevuelveElPasaje() {
		responde("{\"id\":\"caja#cerrar-la-caja\"}");

		assertThat(elector.elegir("¿cómo cierro?", candidatos)).map(p -> p.pasaje().id())
				.contains("caja#cerrar-la-caja");
	}

	@Test
	void idFueraDeLosCandidatosEsNinguno() {
		responde("{\"id\":\"egresos#registrar\"}");

		assertThat(elector.elegir("¿cómo cierro?", candidatos)).isEmpty();
	}

	@Test
	void jsonRotoEsNinguno() {
		responde("esto no es json");

		assertThat(elector.elegir("¿cómo cierro?", candidatos)).isEmpty();
	}

	@Test
	void ningunoEsVacio() {
		responde("{\"id\":\"NINGUNO\"}");

		assertThat(elector.elegir("¿cómo trato una lumbalgia?", candidatos)).isEmpty();
	}

	private void responde(String json) {
		when(modelo.completarJson(anyList(), anyString(), any(Duration.class))).thenReturn(json);
	}

	private static PasajeEncontrado candidato(String id) {
		PasajeDelManual pasaje = new PasajeDelManual(id, "caja", TipoDePasaje.TAREA, id.substring(5), "1. Tocá algo.");
		FichaDelManual ficha = new FichaDelManual("caja", "Caja diaria", "Economía", List.of("/caja"), null, null,
				null, null, null, "", List.of(pasaje));
		return new PasajeEncontrado(pasaje, ficha, 1d);
	}
}
