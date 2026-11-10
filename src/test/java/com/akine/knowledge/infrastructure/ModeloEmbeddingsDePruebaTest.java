package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.Vectores;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ModeloEmbeddingsDePruebaTest {

	private final ModeloEmbeddingsDePrueba modelo = new ModeloEmbeddingsDePrueba();

	@Test
	void es_determinista_y_acerca_textos_con_palabras_en_comun() {
		List<float[]> pasajes = modelo.pasajes(List.of(
				"Rehabilitación del ligamento cruzado anterior", "Tratamiento del hombro congelado"));
		float[] consulta = modelo.consulta("ligamento cruzado rehabilitacion");

		assertThat(pasajes.get(0)).hasSize(768);
		assertThat(Vectores.coseno(pasajes.get(0), pasajes.get(0))).isCloseTo(1f, within(1e-5f));
		assertThat(modelo.consulta("ligamento cruzado rehabilitacion")).containsExactly(consulta);
		assertThat(Vectores.coseno(consulta, pasajes.get(0)))
				.isGreaterThan(Vectores.coseno(consulta, pasajes.get(1)))
				.isGreaterThan(0f);
	}
}
