package com.akine.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parametros operativos del worker.
 *
 * <p>Los tres invariantes que valida el record no son cosmetica: un lote de cero filas deja la
 * cola parada sin que falle nada, y un lease de cero segundos hace que el worker considere
 * huerfana toda fila que acaba de reclamar y se la robe a si mismo en el tick siguiente. Las dos
 * configuraciones arrancan la aplicacion sin un solo error y solo se notan en produccion.
 */
class OutboxWorkerSettingsTest {

	@Test
	@DisplayName("los valores por defecto son los del diseno")
	void los_valores_por_defecto_son_los_del_diseno() {
		OutboxWorkerSettings settings = OutboxWorkerSettings.porDefecto();

		assertThat(settings.tamanoLote()).isEqualTo(20);
		assertThat(settings.duracionLease()).isEqualTo(Duration.ofMinutes(5));
		assertThat(settings.backoff().maxIntentos()).isEqualTo(5);
	}

	@Test
	@DisplayName("el lease por defecto es holgadamente mayor que la primera espera de backoff")
	void el_lease_es_mayor_que_la_primera_espera() {
		OutboxWorkerSettings settings = OutboxWorkerSettings.porDefecto();

		// Si el lease fuera mas corto que lo que tarda un envio, el worker se roba a si mismo
		// filas que todavia esta procesando y la persona recibe el mismo mail dos veces.
		assertThat(settings.duracionLease())
				.isGreaterThan(settings.backoff().escalones().get(0));
	}

	@Test
	@DisplayName("un lote vacio o negativo deja la cola parada y no se acepta")
	void un_lote_vacio_no_se_acepta() {
		assertThatThrownBy(() -> new OutboxWorkerSettings(
				0, Duration.ofMinutes(5), RetryBackoffPolicy.porDefecto()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lote");

		assertThatThrownBy(() -> new OutboxWorkerSettings(
				-1, Duration.ofMinutes(5), RetryBackoffPolicy.porDefecto()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("un lease nulo, cero o negativo no se acepta")
	void un_lease_invalido_no_se_acepta() {
		assertThatThrownBy(() -> new OutboxWorkerSettings(
				20, null, RetryBackoffPolicy.porDefecto()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lease");

		assertThatThrownBy(() -> new OutboxWorkerSettings(
				20, Duration.ZERO, RetryBackoffPolicy.porDefecto()))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new OutboxWorkerSettings(
				20, Duration.ofMinutes(-1), RetryBackoffPolicy.porDefecto()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("sin politica de reintentos no hay worker")
	void sin_politica_no_hay_worker() {
		// El agotamiento se decide unicamente en la politica: sin ella no habria forma de saber
		// cuando dejar de reintentar.
		assertThatThrownBy(() -> new OutboxWorkerSettings(20, Duration.ofMinutes(5), null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("reintentos");
	}
}
