package com.akine.knowledge.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VectoresTest {

	@Test
	void los_bytes_van_y_vuelven_en_float32_little_endian() {
		float[] v = {1.5f, -0.25f, 0f, 3.0e-7f};
		byte[] bytes = Vectores.aBytes(v);
		assertThat(bytes).hasSize(16);
		// 1.5f = 0x3FC00000: little-endian deja el byte menos significativo primero
		assertThat(bytes[0]).isZero();
		assertThat(bytes[3]).isEqualTo((byte) 0x3F);
		assertThat(Vectores.deBytes(bytes)).containsExactly(v);
	}

	@Test
	void el_coseno_de_un_vector_normalizado_consigo_mismo_es_uno() {
		float[] v = Vectores.normalizar(new float[] {3f, 4f, 0f});
		assertThat(v[0]).isCloseTo(0.6f, within(1e-6f));
		assertThat(Vectores.coseno(v, v)).isCloseTo(1f, within(1e-6f));
		assertThat(Vectores.normalizar(new float[3])).containsExactly(0f, 0f, 0f);
	}
}
