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

	/**
	 * El {@code reintentarEnSegundos=0} que vieron los E2E de G-9 el 08/10/2026.
	 *
	 * <p>Rechazar implica que la ventana sigue viva, o sea que falta un tiempo estrictamente
	 * positivo. Truncarlo a segundos enteros convertia cualquier resto menor a un segundo en
	 * {@code Retry-After: 0}: "reintente ya", y el reintento inmediato caia en la misma ventana
	 * y volvia a ser 429. Se redondea hacia arriba: nunca se le dice al cliente que espere menos
	 * de lo que realmente falta.
	 */
	@Test
	@DisplayName("con menos de un segundo de ventana, Retry-After es 1 y no 0")
	void el_resto_fraccionario_se_redondea_hacia_arriba() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 1);
		limiter.permitir("k", T0);
		Instant casiAlBorde = T0.plusSeconds(59).plusMillis(400);

		assertThat(limiter.permitir("k", casiAlBorde)).isFalse();
		assertThat(limiter.segundosParaReintentar("k", casiAlBorde)).isEqualTo(1);
		// Fraccion por encima de un entero: 20,5 s restantes son 21, no 20.
		assertThat(limiter.segundosParaReintentar("k", T0.plusSeconds(39).plusMillis(500)))
				.isEqualTo(21);
		// Esperar exactamente lo que se informo alcanza: el borde es exclusivo.
		assertThat(limiter.permitir("k", casiAlBorde.plusSeconds(1))).isTrue();
	}

	/**
	 * Por que hubo un 429 aunque el cliente espaciaba 4 altas cada 60 s, con cupo de 5.
	 *
	 * <p>No es un error de conteo: el cliente anota el instante ANTES de mandar el request (y en
	 * los flujos por pantalla, antes de llenar el formulario), y el servidor cuenta el instante
	 * de LLEGADA. La ventana fija arranca con la llegada del primer request; si ese request
	 * tardo mas en llegar que los del lote siguiente, el lote siguiente cae adentro de la misma
	 * ventana del servidor aunque en el reloj del cliente hayan pasado mas de 60 s. Un intervalo
	 * de 60 s mas el desfasaje puede contener hasta ocho anotaciones de una ventana deslizante
	 * de cuatro: tener un cupo de margen no absorbe un desfasaje de tiempo.
	 */
	@Test
	@DisplayName("un lote de 4 + 4 espaciado en el cliente puede caer en una sola ventana del servidor")
	void el_desfasaje_entre_anotar_y_llegar_explica_el_429() {
		FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(VENTANA, 5);
		// Cliente: anota t = 0; 0,1; 0,2; 0,3 y espera min + 60 s + 0,5 s para el segundo lote.
		// El primer request llega 2 s despues de anotado (formulario por pantalla); el resto, al
		// instante.
		Instant servidorAbre = T0.plusSeconds(2);
		assertThat(limiter.permitir("k", servidorAbre)).isTrue();
		assertThat(limiter.permitir("k", servidorAbre.plusMillis(1))).isTrue();
		assertThat(limiter.permitir("k", servidorAbre.plusMillis(2))).isTrue();
		assertThat(limiter.permitir("k", servidorAbre.plusMillis(3))).isTrue();
		// Segundo lote: el cliente lo anota en 60,5 s, 60,6 s... y llegan enseguida, todavia
		// dentro de [2 s, 62 s), la ventana que abrio la primera llegada.
		Instant segundoLote = T0.plusSeconds(60).plusMillis(500);
		assertThat(limiter.permitir("k", segundoLote)).isTrue();
		assertThat(limiter.permitir("k", segundoLote.plusMillis(100))).isFalse();

		// El 429 llega a 1,4 s del cierre (62 s): se informan 2, no el 1 truncado; y un rechazo
		// a 0,7 s del cierre se informa 1, no el 0 que vio G-9.
		assertThat(limiter.segundosParaReintentar("k", segundoLote.plusMillis(100))).isEqualTo(2);
		assertThat(limiter.segundosParaReintentar("k", T0.plusSeconds(61).plusMillis(300)))
				.isEqualTo(1);
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
