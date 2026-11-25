package com.akine.activity.infrastructure;

import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.DetalleOperativoView;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClasesParaElAgenteTest {

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
		assertThat(ClasesParaElAgente.elegir(semana, "yoga", "quiénes faltaron a la clase de yoga para embarazadas", AHORA))
				.extracting(ClaseView::id).containsExactly(2L);
		assertThat(ClasesParaElAgente.elegir(semana, "pilates", "presentes en pilates reformer ayer", AHORA))
				.extracting(ClaseView::id).containsExactly(3L);
	}

	@Test
	void si_la_pregunta_dice_solo_yoga_vienen_todas_las_de_yoga_las_ya_empezadas_primero() {
		assertThat(ClasesParaElAgente.elegir(semana, "yoga", "quienes vinieron a yoga", AHORA))
				.extracting(ClaseView::id).containsExactly(2L, 1L, 4L);
	}

	@Test
	void una_clase_sin_titulo_se_encuentra_por_el_nombre_de_la_oferta() {
		ClaseView sinTitulo = clase(9, null, "2026-11-16T19:00:00Z");
		when(sinTitulo.ofertaNombre()).thenReturn("Yoga");

		assertThat(ClasesParaElAgente.elegir(List.of(sinTitulo), "yoga", "quienes van a yoga manana", AHORA))
				.extracting(ClaseView::id).containsExactly(9L);
		assertThat(ClasesParaElAgente.nombre(sinTitulo)).isEqualTo("Yoga");
	}

	@Test
	void sin_coincidencias_o_sin_texto_no_hay_clases() {
		assertThat(ClasesParaElAgente.elegir(semana, "zumba", "quienes fueron a zumba", AHORA)).isEmpty();
		assertThat(ClasesParaElAgente.elegir(semana, null, "quienes fueron", AHORA)).isEmpty();
	}

	@Test
	void ejecutar_usa_la_pregunta_original_y_no_solo_el_fragmento_del_modelo() {
		ClaseService clases = mock(ClaseService.class);
		when(clases.listar(any(), eq(3L), any(), any())).thenReturn(semana);
		AsistenciaService asistencias = mock(AsistenciaService.class);
		DetalleOperativoView sinParticipantes = mock(DetalleOperativoView.class);
		when(sinParticipantes.contenido()).thenReturn(List.of());
		when(asistencias.detalleOperativo(any(), eq(3L), anyLong(), anyInt(), anyInt())).thenReturn(sinParticipantes);
		ContextoDeConsulta ctx = new ContextoDeConsulta(LocalDate.of(2026, 11, 15),
				ZoneId.of("America/Argentina/Cordoba"), null, "¿quiénes van a yoga para embarazadas?");

		ResultadoDeHerramienta r = new ClasesParaElAgente(clases, asistencias).ejecutar(
				new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO"),
				Map.of("actividad", "yoga", "desde", "2026-11-09", "hasta", "2026-11-20"), ctx);

		assertThat(r.filas()).extracting(f -> f.get("clase")).containsExactly("Yoga para embarazadas");
	}
}
