package com.akine.clinical.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las reglas que la entidad hace cumplir sola, sin servicio ni base.
 */
@DisplayName("HistoriaClinica")
class HistoriaClinicaTest {

	private static final long ORG_ID = 10L;
	private static final long PERSONA_ID = 500L;
	private static final long ACCOUNT_ID = 40L;

	@Test
	@DisplayName("Borrar el resumen limpia tambien su autoria")
	void el_resumen_vacio_no_deja_autoria_huerfana() {
		// Un resumen en blanco con autor y fecha diria que alguien escribio algo que no esta, y el
		// CHECK ck_historia_clinica_resumen_trazable lo rechazaria de todos modos: mejor que el
		// dominio no llegue a intentarlo.
		HistoriaClinica historia = nueva();
		historia.actualizarResumen("dolor lumbar", Instant.now(), ACCOUNT_ID);

		historia.actualizarResumen("   ", Instant.now(), ACCOUNT_ID);

		assertThat(historia.getResumen()).isNull();
		assertThat(historia.getResumenActualizadoEn()).isNull();
		assertThat(historia.getResumenActualizadoPor()).isNull();
	}

	@Test
	@DisplayName("La baja exige motivo y deja de estar vigente")
	void la_baja_exige_motivo() {
		HistoriaClinica historia = nueva();

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> historia.deactivate(Instant.now(), "  "))
				.isInstanceOf(IllegalArgumentException.class);

		historia.deactivate(Instant.now(), "ficha fusionada");
		assertThat(historia.isVigente()).isFalse();
	}

	private static HistoriaClinica nueva() {
		return new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID);
	}
}
