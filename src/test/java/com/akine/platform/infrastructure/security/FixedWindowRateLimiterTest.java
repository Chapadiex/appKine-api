package com.akine.platform.infrastructure.security;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El contador de ventana fija.
 *
 * <p>El test que mas importa es {@link #dos_claves_distintas_no_se_estorban()}: es la
 * propiedad estructural que impide que el rate limit se convierta en un oraculo de enumeracion.
 * Como las claves son independientes y el comportamiento depende solo de cuantas veces se uso
 * ESA clave, no hay forma de que el contador responda distinto segun exista o no una cuenta,
 * porque el contador no sabe que es una cuenta.
 */
class FixedWindowRateLimiterTest {

	private static final Instant T0 = Instant.parse("2026-08-23T12:00:00Z");
	private static final Duration VENTANA = Duration.ofMinutes(1);

	@Test
	@DisplayName("permite hasta el maximo y corta a partir del siguiente")
	void permite_hasta_el_maximo() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 3);

		assertThat(limiter.permitir("k", T0)).isTrue();
		assertThat(limiter.permitir("k", T0)).isTrue();
		assertThat(limiter.permitir("k", T0)).isTrue();
		assertThat(limiter.permitir("k", T0)).isFalse();
		assertThat(limiter.permitir("k", T0)).isFalse();
	}

	@Test
	@DisplayName("la ventana se reinicia cuando vence, no antes")
	void la_ventana_se_reinicia_al_vencer() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 1);
		limiter.permitir("k", T0);

		assertThat(limiter.permitir("k", T0.plusSeconds(59))).isFalse();
		assertThat(limiter.permitir("k", T0.plusSeconds(60))).isTrue();
	}

	@Test
	@DisplayName("insistir mientras se esta limitado no adelanta el reinicio")
	void insistir_no_adelanta_el_reinicio() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 1);
		limiter.permitir("k", T0);
		for (int intento = 0; intento < 20; intento++) {
			limiter.permitir("k", T0.plusSeconds(10));
		}

		assertThat(limiter.permitir("k", T0.plusSeconds(59))).isFalse();
		assertThat(limiter.permitir("k", T0.plusSeconds(61))).isTrue();
	}

	@Test
	@DisplayName("dos claves distintas no se estorban: el contador no distingue nada mas")
	void dos_claves_distintas_no_se_estorban() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 1);

		assertThat(limiter.permitir("una", T0)).isTrue();
		assertThat(limiter.permitir("una", T0)).isFalse();
		// La segunda clave arranca de cero: el estado de la primera no la afecta en nada.
		assertThat(limiter.permitir("otra", T0)).isTrue();
		assertThat(limiter.permitir("otra", T0)).isFalse();
	}

	@Test
	@DisplayName("informa cuantos segundos faltan para volver a tener cupo")
	void informa_el_tiempo_de_espera() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 1);

		assertThat(limiter.segundosParaReintentar("k", T0)).isZero();
		limiter.permitir("k", T0);
		assertThat(limiter.segundosParaReintentar("k", T0.plusSeconds(20))).isEqualTo(40);
		// Vencida la ventana no hay espera, aunque la fila siga en el mapa.
		assertThat(limiter.segundosParaReintentar("k", T0.plusSeconds(120))).isZero();
	}

	@Test
	@DisplayName("el mapa se purga al pasar el tope: rotar la IP no agota la memoria")
	void el_mapa_se_purga_al_pasar_el_tope() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 1);
		// Mas claves que el tope interno, cada una desde una "IP" distinta.
		for (int clave = 0; clave <= 50_001; clave++) {
			limiter.permitir("ip-" + clave, T0);
		}

		// Con las ventanas ya vencidas, la purga las descarta y el limiter sigue funcionando.
		Instant despues = T0.plusSeconds(120);
		for (int clave = 0; clave <= 50_001; clave++) {
			limiter.permitir("ip-" + clave, despues);
		}

		assertThat(limiter.permitir("ip-0", despues)).isFalse();
		assertThat(limiter.permitir("ip-nueva", despues)).isTrue();
	}

	@Test
	@DisplayName("una configuracion imposible se rechaza al construir, no en el primer request")
	void una_configuracion_imposible_se_rechaza() {
		assertThatThrownBy(() -> new FixedWindowRateLimiter(Duration.ZERO, 5))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FixedWindowRateLimiter(Duration.ofMinutes(-1), 5))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FixedWindowRateLimiter(null, 5))
				.isInstanceOf(IllegalArgumentException.class);
		// Cero intentos permitidos dejaria a todo el mundo afuera del login para siempre.
		assertThatThrownBy(() -> new FixedWindowRateLimiter(VENTANA, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
