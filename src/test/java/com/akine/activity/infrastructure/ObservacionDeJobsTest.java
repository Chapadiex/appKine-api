package com.akine.activity.infrastructure;

import com.akine.activity.application.PasadaDeJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ObservacionDeJobs")
class ObservacionDeJobsTest {

	private MeterRegistry metricas;
	private ObservacionDeJobs observacion;

	@BeforeEach
	void setUp() {
		metricas = new SimpleMeterRegistry();
		ObservationRegistry observaciones = ObservationRegistry.create();
		observaciones.observationConfig().observationHandler(new DefaultMeterObservationHandler(metricas));
		observacion = new ObservacionDeJobs(metricas, observaciones);
	}

	@Test
	@DisplayName("los contadores de los cuatro jobs existen en cero antes de la primera pasada")
	void contadores_en_cero_al_arrancar() {
		for (String job : ObservacionDeJobs.JOBS) {
			for (String resultado : new String[] {"procesado", "omitido", "fallido"}) {
				assertThat(items(job, resultado)).as("%s/%s", job, resultado).isZero();
			}
		}
	}

	@Test
	@DisplayName("una pasada suma sus cuentas al job que corresponde y deja un timer por job")
	void la_pasada_suma_sus_cuentas() {
		observacion.observar(ObservacionDeJobs.VENCIMIENTO_DE_PASES, () -> new PasadaDeJob(3, 2, 1));
		observacion.observar(ObservacionDeJobs.VENCIMIENTO_DE_PASES, () -> new PasadaDeJob(1, 0, 0));

		assertThat(items(ObservacionDeJobs.VENCIMIENTO_DE_PASES, "procesado")).isEqualTo(4);
		assertThat(items(ObservacionDeJobs.VENCIMIENTO_DE_PASES, "omitido")).isEqualTo(2);
		assertThat(items(ObservacionDeJobs.VENCIMIENTO_DE_PASES, "fallido")).isEqualTo(1);
		assertThat(items(ObservacionDeJobs.RENOVACION_DE_ABONOS, "procesado")).isZero();
		assertThat(metricas.get("akine.job").tag("job", ObservacionDeJobs.VENCIMIENTO_DE_PASES).timer().count())
				.isEqualTo(2);
	}

	@Test
	@DisplayName("lo que lanza la pasada se relanza, queda en el timer con su error y no suma cuentas")
	void la_excepcion_se_relanza_y_se_registra() {
		assertThatThrownBy(() -> observacion.observar(ObservacionDeJobs.RENOVACION_DE_ABONOS, () -> {
			throw new IllegalStateException("base caida");
		})).isInstanceOf(IllegalStateException.class).hasMessage("base caida");

		assertThat(metricas.get("akine.job")
				.tag("job", ObservacionDeJobs.RENOVACION_DE_ABONOS)
				.tag("error", "IllegalStateException")
				.timer().count()).isEqualTo(1);
		assertThat(items(ObservacionDeJobs.RENOVACION_DE_ABONOS, "fallido")).isZero();
	}

	@Test
	@DisplayName("un job sin metricas declaradas es un error de programacion, no una serie nueva")
	void job_desconocido() {
		assertThatThrownBy(() -> observacion.observar("otro", () -> PasadaDeJob.VACIA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(metricas.find("akine.job.items").tag("job", "otro").counter()).isNull();
	}

	private double items(String job, String resultado) {
		return metricas.get("akine.job.items").tag("job", job).tag("resultado", resultado).counter().count();
	}
}
