package com.akine.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Politica de reintentos del worker (diseno 01.02 seccion 7).
 *
 * <p>Lo que estos tests blindan: que el agotamiento tenga UNA sola fuente de verdad. El
 * JavaDoc promete que {@code proximaEspera(...).isEmpty()} es esa fuente y que
 * {@code agotada(...)} es solo un atajo. Si las dos condiciones se desincronizaran, el worker
 * podria reprogramar para siempre una notificacion que la politica ya considera muerta —o
 * cerrar como agotada una a la que todavia le quedaban intentos.
 */
class RetryBackoffPolicyTest {

	private final RetryBackoffPolicy politica = RetryBackoffPolicy.porDefecto();

	/** Jitter neutro: 0,5 cae justo en el centro de la amplitud y no desvia nada. */
	private static final double SIN_DESVIO = 0.5;

	@Test
	@DisplayName("La politica por defecto es 1/5/15/60/180 minutos con 5 intentos")
	void la_politica_por_defecto_es_la_documentada() {
		// Los escalones estan escritos a mano desde el diseno y no leidos de la clase: si
		// alguien los cambia, la decision de cuanto tarda en agotarse una notificacion —y si
		// entra dentro del TTL del token— vuelve a discutirse en vez de cambiar sola.
		assertThat(politica.escalones()).containsExactly(
				Duration.ofMinutes(1),
				Duration.ofMinutes(5),
				Duration.ofMinutes(15),
				Duration.ofMinutes(60),
				Duration.ofMinutes(180));
		assertThat(politica.maxIntentos()).isEqualTo(5);
	}

	@Test
	@DisplayName("El total acumulado de esperas entra holgado en el TTL de un token de activacion")
	void el_total_entra_en_el_ttl_del_token() {
		Duration total = politica.escalones().stream().reduce(Duration.ZERO, Duration::plus);

		// ~4,3 h: la promesa del JavaDoc. Un backoff que doblara sin techo dejaria vivas filas
		// que ya no le importan a nadie y reintentaria despues de vencido el enlace.
		assertThat(total).isEqualTo(Duration.ofMinutes(261));
		assertThat(total).isLessThan(Duration.ofDays(7));
	}

	@ParameterizedTest(name = "tras {0} intentos la espera base es de {1} minutos")
	@CsvSource({"1, 1", "2, 5", "3, 15", "4, 60"})
	@DisplayName("Cada escalon devuelve la espera esperada con jitter neutro")
	void cada_escalon_devuelve_su_espera(int intentosRealizados, int minutosEsperados) {
		Optional<Duration> espera = politica.proximaEspera(intentosRealizados, SIN_DESVIO);

		assertThat(espera).contains(Duration.ofMinutes(minutosEsperados));
	}

	@Test
	@DisplayName("Cero intentos usa el primer escalon, no una espera nula")
	void cero_intentos_usa_el_primer_escalon() {
		// Un indice mal calculado que devolviera Duration.ZERO haria que el primer reintento
		// saliera en el mismo tick que el fallo, y el backoff no serviria para nada.
		assertThat(politica.proximaEspera(0, SIN_DESVIO)).contains(Duration.ofMinutes(1));
	}

	@ParameterizedTest(name = "con {0} intentos realizados no queda espera")
	@ValueSource(ints = {5, 6, 50})
	@DisplayName("Agotados los intentos, la espera es vacia")
	void agotados_los_intentos_no_hay_espera(int intentosRealizados) {
		assertThat(politica.proximaEspera(intentosRealizados, SIN_DESVIO)).isEmpty();
	}

	@ParameterizedTest(name = "intentos={0}")
	@ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 20})
	@DisplayName("agotada() coincide siempre con proximaEspera().isEmpty(), para cualquier jitter")
	void agotada_coincide_con_espera_vacia(int intentosRealizados) {
		boolean agotada = politica.agotada(intentosRealizados);

		// Esta es LA invariante del JavaDoc: el vacio es la unica fuente de verdad del
		// agotamiento. Se comprueba en los dos bordes del jitter y en el centro, porque el
		// agotamiento no puede depender del azar: dos notificaciones con los mismos intentos
		// no pueden terminar una AGOTADA y la otra REINTENTABLE segun que numero salio.
		for (double jitter : new double[] {0d, SIN_DESVIO, 1d}) {
			assertThat(politica.proximaEspera(intentosRealizados, jitter).isEmpty())
					.as("intentos=%s jitter=%s", intentosRealizados, jitter)
					.isEqualTo(agotada);
		}
	}

	@ParameterizedTest(name = "escalon de {0} minutos")
	@ValueSource(ints = {1, 2, 3, 4})
	@DisplayName("El jitter se mantiene dentro de la amplitud declarada del +-20 %")
	void el_jitter_no_sale_de_la_amplitud(int intentosRealizados) {
		Duration base = politica.escalones().get(intentosRealizados - 1);
		long minimo = Math.round(base.toMillis() * 0.8);
		long maximo = Math.round(base.toMillis() * 1.2);

		// Los dos bordes exactos: jitter 0 es el piso y jitter 1 el techo. Si la amplitud se
		// fuera de mano, mil notificaciones encoladas durante una caida del SMTP volverian
		// mucho antes -o mucho despues- de lo que dice el diseno.
		assertThat(politica.proximaEspera(intentosRealizados, 0d))
				.contains(Duration.ofMillis(minimo));
		assertThat(politica.proximaEspera(intentosRealizados, 1d))
				.contains(Duration.ofMillis(maximo));

		for (double jitter = 0d; jitter <= 1d; jitter += 0.05) {
			Duration espera = politica.proximaEspera(intentosRealizados, jitter).orElseThrow();
			assertThat(espera.toMillis())
					.as("jitter=%s", jitter)
					.isBetween(minimo, maximo);
		}
	}

	@Test
	@DisplayName("El jitter desvia de verdad: los dos bordes no dan la misma espera")
	void el_jitter_desvia_de_verdad() {
		// Sin esta comprobacion, una amplitud puesta en cero pasaria todos los tests de rango
		// y el efecto de manada que el jitter existe para evitar volveria en silencio.
		Duration piso = politica.proximaEspera(1, 0d).orElseThrow();
		Duration techo = politica.proximaEspera(1, 1d).orElseThrow();

		assertThat(piso).isLessThan(techo);
		assertThat(techo.toMillis() - piso.toMillis())
				.isEqualTo(Math.round(Duration.ofMinutes(1).toMillis() * 0.4));
	}

	@ParameterizedTest(name = "jitter fuera de rango: {0}")
	@ValueSource(doubles = {-1d, -0.0001d, 1.5d, 42d})
	@DisplayName("Un jitter fuera de [0,1] se acota en vez de disparar esperas absurdas")
	void el_jitter_fuera_de_rango_se_acota(double jitter) {
		// Un generador mal usado que devuelva 42 no puede convertir una espera de un minuto en
		// una de horas: la fila quedaria fuera de la cola mucho mas de lo que nadie decidio.
		Duration espera = politica.proximaEspera(1, jitter).orElseThrow();

		assertThat(espera).isBetween(
				Duration.ofMillis(Math.round(Duration.ofMinutes(1).toMillis() * 0.8)),
				Duration.ofMillis(Math.round(Duration.ofMinutes(1).toMillis() * 1.2)));
	}

	@Test
	@DisplayName("Con mas intentos que escalones se repite el ultimo escalon, sin salirse de la lista")
	void mas_intentos_que_escalones_usa_el_ultimo() {
		RetryBackoffPolicy larga = new RetryBackoffPolicy(
				List.of(Duration.ofMinutes(1), Duration.ofMinutes(5)), 4);

		assertThat(larga.proximaEspera(3, SIN_DESVIO)).contains(Duration.ofMinutes(5));
		assertThat(larga.proximaEspera(4, SIN_DESVIO)).isEmpty();
	}

	@Test
	@DisplayName("La espera nunca es cero, aunque el escalon sea diminuto")
	void la_espera_nunca_es_cero() {
		// Una espera de cero milisegundos devolveria la fila en el mismo tick y convertiria el
		// reintento en un bucle cerrado contra el SMTP caido.
		RetryBackoffPolicy minima = new RetryBackoffPolicy(List.of(Duration.ofMillis(1)), 3);

		assertThat(minima.proximaEspera(1, 0d).orElseThrow()).isGreaterThanOrEqualTo(
				Duration.ofMillis(1));
	}

	@Test
	@DisplayName("Una politica sin escalones o sin intentos no se puede construir")
	void la_politica_exige_escalones_e_intentos() {
		assertThatThrownBy(() -> new RetryBackoffPolicy(List.of(), 3))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("escalon");
		assertThatThrownBy(() -> new RetryBackoffPolicy(null, 3))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RetryBackoffPolicy(List.of(Duration.ofMinutes(1)), 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxIntentos");
	}

	@Test
	@DisplayName("Los escalones son una copia inmutable: nadie los cambia despues de construida")
	void los_escalones_son_inmutables() {
		// La politica es un bean de aplicacion compartido: si la lista fuera la del llamador,
		// modificarla cambiaria el backoff de todo el proceso en caliente.
		assertThatThrownBy(() -> politica.escalones().add(Duration.ofDays(1)))
				.isInstanceOf(UnsupportedOperationException.class);
	}
}
