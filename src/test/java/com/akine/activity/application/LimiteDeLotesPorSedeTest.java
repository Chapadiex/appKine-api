package com.akine.activity.application;

import com.akine.activity.domain.exception.LotesDeAsistenciaSaturadosException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimiteDeLotesPorSedeTest {

	private static final long SEDE = 7L;
	private static final long OTRA_SEDE = 8L;

	private final MeterRegistry metricas = new SimpleMeterRegistry();

	@Test
	void apagadoNoLimitaNiCuenta() {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(0, Duration.ofSeconds(2), metricas);

		LimiteDeLotesPorSede.Permiso uno = limite.tomar(SEDE);
		LimiteDeLotesPorSede.Permiso dos = limite.tomar(SEDE);

		assertThat(limite.activo()).isFalse();
		assertThat(uno).isSameAs(LimiteDeLotesPorSede.Permiso.NINGUNO);
		assertThat(dos).isSameAs(LimiteDeLotesPorSede.Permiso.NINGUNO);
		assertThat(contador("inmediato")).isZero();
		// Las series existen igual, en cero: una alerta tiene serie antes del primer rechazo.
		assertThat(contador("rechazado")).isZero();
		assertThat(enEspera()).isZero();
	}

	@Test
	void conLugarPasaSinEsperarYSoltarloLoDevuelve() {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(1, Duration.ZERO, metricas);

		try (LimiteDeLotesPorSede.Permiso permiso = limite.tomar(SEDE)) {
			assertThat(permiso).isNotSameAs(LimiteDeLotesPorSede.Permiso.NINGUNO);
		}
		limite.tomar(SEDE).close();

		assertThat(contador("inmediato")).isEqualTo(2);
		assertThat(contador("rechazado")).isZero();
	}

	@Test
	void sinLugarRechazaConRetryAfterRedondeadoHaciaArriba() {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(1, Duration.ofMillis(1_200), metricas);
		limite.tomar(SEDE);

		assertThatThrownBy(() -> limite.tomar(SEDE))
				.isInstanceOfSatisfying(LotesDeAsistenciaSaturadosException.class, rechazo -> {
					assertThat(rechazo.getConsultorioId()).isEqualTo(SEDE);
					assertThat(rechazo.getSegundosParaReintentar()).isEqualTo(2);
				});
		assertThat(contador("rechazado")).isEqualTo(1);
		assertThat(enEspera()).isZero();
	}

	@Test
	void retryAfterNuncaEsCero() {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(1, Duration.ZERO, metricas);
		limite.tomar(SEDE);

		assertThatThrownBy(() -> limite.tomar(SEDE))
				.isInstanceOfSatisfying(LotesDeAsistenciaSaturadosException.class,
						rechazo -> assertThat(rechazo.getSegundosParaReintentar()).isEqualTo(1));
	}

	@Test
	void cadaSedeTieneSuPropioCupo() {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(1, Duration.ZERO, metricas);
		limite.tomar(SEDE);

		limite.tomar(OTRA_SEDE);

		assertThat(contador("inmediato")).isEqualTo(2);
	}

	@Test
	void nLugaresDejanPasarNLotes() {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(3, Duration.ZERO, metricas);
		limite.tomar(SEDE);
		limite.tomar(SEDE);
		limite.tomar(SEDE);

		assertThatThrownBy(() -> limite.tomar(SEDE))
				.isInstanceOf(LotesDeAsistenciaSaturadosException.class);
	}

	@Test
	void soltarDosVecesNoRegalaUnLugar() {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(1, Duration.ZERO, metricas);
		LimiteDeLotesPorSede.Permiso permiso = limite.tomar(SEDE);
		permiso.close();
		permiso.close();

		limite.tomar(SEDE);
		assertThatThrownBy(() -> limite.tomar(SEDE))
				.isInstanceOf(LotesDeAsistenciaSaturadosException.class);
	}

	@Test
	void elQueEsperaEntraCuandoElOtroSuelta() throws Exception {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(1, Duration.ofSeconds(10), metricas);
		LimiteDeLotesPorSede.Permiso primero = limite.tomar(SEDE);
		ExecutorService hilo = Executors.newSingleThreadExecutor();
		try {
			CountDownLatch largado = new CountDownLatch(1);
			Future<?> segundo = hilo.submit(() -> {
				largado.countDown();
				limite.tomar(SEDE).close();
			});
			largado.await();
			esperarHasta(() -> enEspera() == 1);

			primero.close();
			segundo.get(5, TimeUnit.SECONDS);

			assertThat(contador("tras-espera")).isEqualTo(1);
			assertThat(contador("rechazado")).isZero();
			assertThat(enEspera()).isZero();
		} finally {
			hilo.shutdownNow();
		}
	}

	@Test
	void interrumpidoMientrasEsperaSeRechazaYConservaLaInterrupcion() throws Exception {
		LimiteDeLotesPorSede limite = new LimiteDeLotesPorSede(1, Duration.ofSeconds(30), metricas);
		limite.tomar(SEDE);
		ExecutorService hilo = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> segundo = hilo.submit(() -> {
				try {
					limite.tomar(SEDE);
					return false;
				} catch (LotesDeAsistenciaSaturadosException rechazo) {
					return Thread.currentThread().isInterrupted();
				}
			});
			esperarHasta(() -> enEspera() == 1);
			hilo.shutdownNow();

			assertThat(segundo.get(5, TimeUnit.SECONDS)).isTrue();
			assertThat(contador("rechazado")).isEqualTo(1);
		} finally {
			hilo.shutdownNow();
		}
	}

	@Test
	void rechazaConfiguracionNegativa() {
		assertThatThrownBy(() -> new LimiteDeLotesPorSede(-1, Duration.ZERO, metricas))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LimiteDeLotesPorSede(1, Duration.ofSeconds(-1), metricas))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private double contador(String resultado) {
		return metricas.get("akine.asistencia.lotes").tag("resultado", resultado).counter().count();
	}

	private double enEspera() {
		return metricas.get("akine.asistencia.lotes.en.espera").gauge().value();
	}

	private static void esperarHasta(java.util.function.BooleanSupplier condicion)
			throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condicion.getAsBoolean()) {
			if (System.nanoTime() > limite) {
				throw new AssertionError("La condicion no se cumplio en 5 s");
			}
			Thread.sleep(10);
		}
	}
}
