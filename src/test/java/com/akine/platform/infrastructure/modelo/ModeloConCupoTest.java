package com.akine.platform.infrastructure.modelo;

import com.akine.platform.spi.modelo.ModeloNoDisponibleException;
import com.akine.platform.spi.modelo.ModeloSoloJson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeloConCupoTest {

	@Test
	void sin_lugar_dentro_del_plazo_responde_no_disponible_y_libera_el_cupo_al_terminar() throws Exception {
		CountDownLatch adentro = new CountDownLatch(1);
		CountDownLatch soltar = new CountDownLatch(1);
		ModeloSoloJson lento = (mensajes, esquema, plazo) -> {
			adentro.countDown();
			try {
				soltar.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "{}";
		};
		ModeloConCupo conCupo = new ModeloConCupo(lento, 1);

		Thread primera = Thread.ofVirtual().start(() -> conCupo.completarJson(List.of(), null, Duration.ofSeconds(5)));
		assertThat(adentro.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> conCupo.completarJson(List.of(), null, Duration.ofMillis(100)))
				.isInstanceOf(ModeloNoDisponibleException.class);

		soltar.countDown();
		primera.join(5_000);
		assertThat(conCupo.completarJson(List.of(), null, Duration.ofSeconds(1))).isEqualTo("{}");
	}

	@Test
	void la_concurrencia_tiene_que_ser_positiva() {
		assertThatThrownBy(() -> new ModeloConCupo((ModeloSoloJson) (m, e, p) -> "{}", 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
