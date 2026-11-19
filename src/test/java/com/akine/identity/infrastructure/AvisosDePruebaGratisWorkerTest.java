package com.akine.identity.infrastructure;

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
	@DisplayName("un tick que falla no tira el scheduler: se reintenta en el proximo ciclo")
	void un_fallo_no_tira_el_scheduler() {
		AvisosDePruebaGratis avisos = mock(AvisosDePruebaGratis.class);
		willThrow(new IllegalStateException("base caida")).given(avisos).avisarVencimientos(any(Instant.class));

		assertThatCode(new AvisosDePruebaGratisWorker(avisos)::tick).doesNotThrowAnyException();
		verify(avisos).avisarVencimientos(any(Instant.class));
	}
}
