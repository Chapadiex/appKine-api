package com.akine.clinical.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.CasoClinicoView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CasosEnElAsistenteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "PROFESIONAL");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	private final CasoClinicoService casos = mock(CasoClinicoService.class);
	private final CasosEnElAsistente herramienta = new CasosEnElAsistente(casos);

	private static CasoClinicoView caso(long id) {
		return new CasoClinicoView(id, 70L, (int) id, 42L, 3L, "Kinesiologia", "dx", "obj", "ABIERTO",
				Instant.parse("2026-11-01T12:00:00Z"), 11L, null, null, null, List.of(), 0L);
	}

	@Test
	void describe_la_herramienta() {
		assertThat(herramienta.nombre()).isEqualTo("casos_persona");
		assertThat(herramienta.descripcion()).isNotBlank();
		assertThat(herramienta.esquemaEntrada()).contains("persona_id");
	}

	@Test
	void lista_los_casos_sin_diagnostico() {
		when(casos.listarDePersona(any(), eq(20L), eq(true), anyString())).thenReturn(Optional.of(List.of(caso(1))));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("persona_id", 20), CTX);

		assertThat(r.filas()).singleElement().satisfies(f -> {
			assertThat(f).containsEntry("caso_id", 1L).containsEntry("oferta", "Kinesiologia")
					.containsEntry("estado", "ABIERTO").containsEntry("abierto_en", "2026-11-01T12:00:00Z");
			assertThat(f).doesNotContainKeys("diagnostico", "diagnosticoPresuntivo");
		});
		assertThat(r.advertencias()).isEmpty();
	}

	@Test
	void sin_historia_no_encuentra_la_persona() {
		when(casos.listarDePersona(any(), anyLong(), anyBoolean(), anyString())).thenReturn(Optional.empty());

		assertThat(herramienta.ejecutar(ACTOR, Map.of("persona_id", 20), CTX).advertencias())
				.containsExactly("No encontré esa persona");
	}

	@Test
	void corta_en_el_tope_y_avisa() {
		when(casos.listarDePersona(any(), anyLong(), anyBoolean(), anyString())).thenReturn(Optional.of(
				IntStream.rangeClosed(1, 55).mapToObj(CasosEnElAsistenteTest::caso).toList()));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("persona_id", 20), CTX);

		assertThat(r.filas()).hasSize(50);
		assertThat(r.advertencias()).singleElement().asString().contains("55");
	}

	@Test
	void sin_organizacion_es_denegado_y_sin_persona_es_error() {
		ActorDelAsistente sinOrg = new ActorDelAsistente(11L, false, null, null, "PROFESIONAL");
		assertThat(herramienta.ejecutar(sinOrg, Map.of("persona_id", 20), CTX).sinPermiso()).isTrue();
		assertThatThrownBy(() -> herramienta.ejecutar(ACTOR, Map.of(), CTX))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(casos);
	}
}
