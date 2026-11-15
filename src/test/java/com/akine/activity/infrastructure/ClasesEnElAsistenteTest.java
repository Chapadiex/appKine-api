package com.akine.activity.infrastructure;

import com.akine.activity.application.ClaseView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClasesEnElAsistenteTest {

	private static final Instant AHORA = Instant.parse("2026-11-15T15:00:00Z");

	private static ClaseView clase(long id, String titulo, String inicio) {
		ClaseView c = mock(ClaseView.class);
		when(c.id()).thenReturn(id);
		when(c.titulo()).thenReturn(titulo);
		when(c.inicio()).thenReturn(Instant.parse(inicio));
		return c;
	}

	private final List<ClaseView> semana = List.of(
			clase(1, "Yoga", "2026-11-11T21:00:00Z"),
			clase(2, "Yoga para embarazadas", "2026-11-12T21:00:00Z"),
			clase(3, "Pilates Reformer", "2026-11-13T21:00:00Z"),
			clase(4, "Yoga", "2026-11-17T21:00:00Z"));

	@Test
	void el_titulo_mas_largo_escrito_en_la_pregunta_gana_aunque_el_modelo_lo_acorte() {
		// Los casos XC2 y XC3 de la medicion del 15/11.
		assertThat(ClasesEnElAsistente.elegir(semana, "yoga", "quiénes faltaron a la clase de yoga para embarazadas", AHORA))
				.extracting(ClaseView::id).containsExactly(2L);
		assertThat(ClasesEnElAsistente.elegir(semana, "pilates", "presentes en pilates reformer ayer", AHORA))
				.extracting(ClaseView::id).containsExactly(3L);
	}

	@Test
	void si_la_pregunta_dice_solo_yoga_vienen_todas_las_de_yoga_las_ya_empezadas_primero() {
		assertThat(ClasesEnElAsistente.elegir(semana, "yoga", "quienes vinieron a yoga", AHORA))
				.extracting(ClaseView::id).containsExactly(2L, 1L, 4L);
	}

	@Test
	void sin_coincidencias_o_sin_texto_no_hay_clases() {
		assertThat(ClasesEnElAsistente.elegir(semana, "zumba", "quienes fueron a zumba", AHORA)).isEmpty();
		assertThat(ClasesEnElAsistente.elegir(semana, null, "quienes fueron", AHORA)).isEmpty();
	}
}
