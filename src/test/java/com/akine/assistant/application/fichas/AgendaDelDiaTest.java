package com.akine.assistant.application.fichas;

import com.akine.assistant.application.CatalogoDeHerramientas;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgendaDelDiaTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	@Test
	void con_la_lista_de_turnos_cortada_no_muestra_totales_parciales() {
		List<Map<String, Object>> cincuenta = IntStream.range(0, 50)
				.mapToObj(i -> Map.<String, Object>of("turno_id", (long) i, "estado", "RESERVADO"))
				.toList();
		ResultadoDeHerramienta r = ejecutar(new ResultadoDeHerramienta(cincuenta,
				List.of("Hay 80 resultados; muestro los primeros 50"), null, false));

		assertThat(r.filas()).extracting(f -> f.get("seccion")).containsExactly("turnos");
		assertThat(r.advertencias()).contains(AgendaDelDia.TOTALES_PARCIALES);
	}

	@Test
	void con_la_lista_completa_cuenta_por_estado() {
		ResultadoDeHerramienta r = ejecutar(new ResultadoDeHerramienta(List.of(
				Map.of("estado", "RESERVADO"), Map.of("estado", "RESERVADO"), Map.of("estado", "AUSENTE")),
				List.of(), null, false));

		assertThat(r.filas()).extracting(f -> f.get("seccion")).containsExactly("turnos", "totales_por_estado");
		assertThat(r.advertencias()).isEmpty();
	}

	@SuppressWarnings("unchecked")
	private static ResultadoDeHerramienta ejecutar(ResultadoDeHerramienta deTurnos) {
		HerramientaDelAgente turnos = new HerramientaDelAgente() {
			@Override
			public String nombre() {
				return "turnos";
			}

			@Override
			public String descripcion() {
				return "turnos";
			}

			@Override
			public String esquemaEntrada() {
				return "{\"type\":\"object\"}";
			}

			@Override
			public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
					ContextoDeConsulta ctx) {
				return deTurnos;
			}
		};
		ObjectProvider<CatalogoDeHerramientas> proveedor = mock(ObjectProvider.class);
		when(proveedor.getObject()).thenReturn(new CatalogoDeHerramientas(List.of(turnos)));
		return new AgendaDelDia(proveedor).ejecutar(ACTOR, Map.of(), CTX);
	}
}
