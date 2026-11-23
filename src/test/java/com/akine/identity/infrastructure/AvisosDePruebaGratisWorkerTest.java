package com.akine.identity.infrastructure;

import com.akine.identity.application.AvisosDeCobro;
import com.akine.identity.application.AvisosDePruebaGratis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AvisosDePruebaGratisWorkerTest {

	@Test
	@DisplayName("un tick que falla no tira el scheduler, y la prueba rota no deja sin avisos de cobro")
	void un_fallo_no_tira_el_scheduler() {
		AvisosDePruebaGratis avisos = mock(AvisosDePruebaGratis.class);
		AvisosDeCobro cobros = mock(AvisosDeCobro.class);
		willThrow(new IllegalStateException("base caida")).given(avisos).avisarVencimientos(any(Instant.class));
		willThrow(new IllegalStateException("base caida")).given(cobros).avisarVencimientos(any(Instant.class));

		assertThatCode(new AvisosDePruebaGratisWorker(avisos, cobros)::tick).doesNotThrowAnyException();
		verify(avisos).avisarVencimientos(any(Instant.class));
		verify(cobros).avisarVencimientos(any(Instant.class));
	}
}
