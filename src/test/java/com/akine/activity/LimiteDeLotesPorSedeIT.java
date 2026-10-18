package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.CarreraDirigida.Retencion;
import com.akine.activity.application.ResultadoDeLote;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.exception.LotesDeAsistenciaSaturadosException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U-49 opcion c (DP-30) encendida con N=1 y 2 s de espera, contra MySQL real. El gemelo con la
 * propiedad sin setear es {@link LimiteDeLotesApagadoIT}.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"akine.activity.asistencia.lotes-concurrentes-por-sede=1",
				"akine.activity.asistencia.lotes-espera-maxima=2s"})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class LimiteDeLotesPorSedeIT extends EscenarioDeLotesPorSede {

	@Test
	@DisplayName("U-49 c · N=1: el segundo lote de la sede espera la espera maxima y se rechaza sin escribir nada; el primero termina bien")
	void el_segundo_se_rechaza() {
		double rechazadosAntes = contador("rechazado");

		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion retencion = Retencion.de(dataSource, "clase_programada", claseA)) {
			Future<Desenlace<ResultadoDeLote>> loteA =
					carrera.largarHastaBloquearse(() -> lote(claseA), LOCK_DE_LA_CLASE);

			long inicio = System.nanoTime();
			assertThatThrownBy(() -> lote(claseB))
					.isInstanceOfSatisfying(LotesDeAsistenciaSaturadosException.class,
							rechazo -> assertThat(rechazo.getSegundosParaReintentar()).isEqualTo(2));
			long esperoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);

			assertThat(esperoMs).as("espero la espera maxima antes de rechazar")
					.isGreaterThanOrEqualTo(1_900);
			assertThat(loteA.isDone()).as("el lote A sigue en curso").isFalse();
			retencion.soltar();

			Desenlace<ResultadoDeLote> a = CarreraDirigida.resultado(loteA);
			assertThat(a.fallo()).as("Desenlace: %s", a).isFalse();
			assertThat(a.valor().exitosos()).isEqualTo(2);
		}

		assertThat(asistenciasDe(claseB)).as("el lote rechazado no escribio nada").isZero();
		assertThat(asistenciasDe(claseA)).isEqualTo(2);
		assertThat(contador("rechazado") - rechazadosAntes).isEqualTo(1);
	}

	@Test
	@DisplayName("U-49 c · N=1: si el primero termina dentro de la espera maxima, el segundo espera, entra y registra todo")
	void el_segundo_espera_y_entra() throws Exception {
		double trasEsperaAntes = contador("tras-espera");
		ExecutorService hilo = Executors.newSingleThreadExecutor();

		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion retencion = Retencion.de(dataSource, "clase_programada", claseA)) {
			Future<Desenlace<ResultadoDeLote>> loteA =
					carrera.largarHastaBloquearse(() -> lote(claseA), LOCK_DE_LA_CLASE);
			Future<ResultadoDeLote> loteB = hilo.submit(() -> lote(claseB));

			esperarHasta(() -> enEspera() == 1);
			assertThat(loteB.isDone()).isFalse();
			assertThat(asistenciasDe(claseB)).as("mientras espera no escribio nada").isZero();
			retencion.soltar();

			assertThat(CarreraDirigida.resultado(loteA).valor().exitosos()).isEqualTo(2);
			assertThat(loteB.get(30, TimeUnit.SECONDS).exitosos()).isEqualTo(2);
		} finally {
			hilo.shutdownNow();
		}

		assertThat(asistenciasDe(claseB)).isEqualTo(2);
		assertThat(contador("tras-espera") - trasEsperaAntes).isEqualTo(1);
		assertThat(enEspera()).isZero();
	}

	@Test
	@DisplayName("U-49 c · N=1: el lote de un item y el registro individual no pasan por el limite")
	void un_item_no_pasa_por_el_limite() {
		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion retencion = Retencion.de(dataSource, "clase_programada", claseA)) {
			Future<Desenlace<ResultadoDeLote>> loteA =
					carrera.largarHastaBloquearse(() -> lote(claseA), LOCK_DE_LA_CLASE);

			long inicio = System.nanoTime();
			ResultadoDeLote deUno = asistenciaService.registrarLote(tenant.actor(),
					tenant.consultorioId(), claseB, List.of(marca(claseB, 0, OrigenAsistencia.LOTE)));
			asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), claseB,
					marca(claseB, 1, OrigenAsistencia.MOSTRADOR));
			long tardoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);

			assertThat(deUno.exitosos()).isEqualTo(1);
			assertThat(tardoMs).as("no esperaron el lugar de la sede").isLessThan(1_500);
			assertThat(loteA.isDone()).isFalse();
			retencion.soltar();
			assertThat(CarreraDirigida.resultado(loteA).valor().exitosos()).isEqualTo(2);
		}
		assertThat(asistenciasDe(claseB)).isEqualTo(2);
	}
}
