package com.akine.encounter.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.domain.port.TratamientoRepositoryPorts.TratamientoAplicadoEnSede;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PracticasAplicadasEnElAsistenteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "PROFESIONAL");
	private static final LocalDate HOY = LocalDate.of(2026, 11, 23);
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(HOY,
			ZoneId.of("America/Argentina/Cordoba"), null);

	private final TratamientoService tratamientos = mock(TratamientoService.class);
	private final CatalogoDirectory catalogo = mock(CatalogoDirectory.class);
	private final PacienteDirectory personas = mock(PacienteDirectory.class);
	private final PracticasAplicadasEnElAsistente herramienta =
			new PracticasAplicadasEnElAsistente(tratamientos, catalogo, personas);

	private static CatalogoSnapshot practica(long id, String codigo, String nombre) {
		return new CatalogoSnapshot(id, 7L, codigo, nombre, Instant.EPOCH, null, true, true, null, null, 0L);
	}

	private static TratamientoAplicadoEnSede aplicado(long sesionId) {
		return new TratamientoAplicadoEnSede(sesionId, 20L, "Quiroga, Pedro", HOY, 900L, "TENS", null, "DERECHA",
				31L);
	}

	@Test
	void describe_la_herramienta() {
		assertThat(herramienta.nombre()).isEqualTo("practicas_aplicadas");
		assertThat(herramienta.descripcion()).isNotBlank();
		assertThat(herramienta.esquemaEntrada()).contains("practica");
	}

	@Test
	void por_codigo_exacto_lista_a_quien_se_aplico_en_los_ultimos_30_dias() {
		when(catalogo.practicasVigentes(eq(7L), any())).thenReturn(List.of(practica(900L, "TENS", "Electroestimulación"),
				practica(901L, "TENS2", "TENS de alta")));
		when(tratamientos.aplicados(any(), eq(3L), eq(HOY.minusDays(29)), any(), any(), eq(List.of(900L)), eq(null)))
				.thenReturn(new TratamientoService.Aplicados(List.of(aplicado(1L)), 1, false));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("practica", "tens"), CTX);

		assertThat(r.filas()).singleElement().satisfies(f -> {
			assertThat(f).containsEntry("persona", "Quiroga, Pedro").containsEntry("practica", "TENS")
					.containsEntry("lateralidad", "DERECHA");
			assertThat(f).doesNotContainKey("zona");
		});
		assertThat(r.advertencias()).singleElement().asString().startsWith("Período consultado: últimos 30 días");
	}

	@Test
	void por_parte_del_nombre_incluye_todas_avisa_limitado_y_truncado() {
		when(catalogo.practicasVigentes(eq(7L), any())).thenReturn(List.of(practica(900L, "TENS", "Electro corta"),
				practica(901L, "EMS", "Electro larga"), practica(902L, "US", "Ultrasonido")));
		when(personas.find(7L, 20L)).thenReturn(Optional.of(mock(PacienteSnapshot.class)));
		when(tratamientos.aplicados(any(), eq(3L), any(), any(), any(), eq(List.of(900L, 901L)), eq(20L)))
				.thenReturn(new TratamientoService.Aplicados(List.of(aplicado(1L), aplicado(2L)), 80, true));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR,
				Map.of("practica", "electro", "persona_id", 20, "desde", "2026-11-01", "hasta", "2026-11-20"), CTX);

		assertThat(r.filas()).hasSize(2);
		assertThat(r.advertencias()).hasSize(4)
				.contains(PracticasAplicadasEnElAsistente.SOLO_PROPIOS, "Hay 80 resultados; muestro los primeros 2");
		assertThat(r.advertencias().getFirst()).isEqualTo("Período consultado: del 2026-11-01 al 2026-11-20.");
	}

	@Test
	void persona_sola_sin_practica() {
		when(personas.find(7L, 20L)).thenReturn(Optional.of(mock(PacienteSnapshot.class)));
		when(tratamientos.aplicados(any(), anyLong(), any(), any(), any(), eq(List.of()), eq(20L)))
				.thenReturn(new TratamientoService.Aplicados(List.of(), 0, false));

		assertThat(herramienta.ejecutar(ACTOR, Map.of("persona_id", 20), CTX).filas()).isEmpty();
		verifyNoInteractions(catalogo);
	}

	@Test
	void persona_de_otra_organizacion_o_practica_inexistente_no_consulta() {
		when(personas.find(7L, 20L)).thenReturn(Optional.empty());
		assertThat(herramienta.ejecutar(ACTOR, Map.of("persona_id", 20), CTX).advertencias())
				.containsExactly(PracticasAplicadasEnElAsistente.SIN_PERSONA);

		when(catalogo.practicasVigentes(eq(7L), any())).thenReturn(List.of(practica(900L, "TENS", "Electro")));
		assertThat(herramienta.ejecutar(ACTOR, Map.of("practica", "zumba"), CTX).advertencias())
				.containsExactly("No encontré la práctica 'zumba'");
		verifyNoInteractions(tratamientos);
		verify(personas).find(7L, 20L);
	}

	@Test
	void sin_practica_ni_persona_es_error() {
		assertThatThrownBy(() -> herramienta.ejecutar(ACTOR, Map.of(), CTX))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
