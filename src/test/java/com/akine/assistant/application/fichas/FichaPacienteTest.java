package com.akine.assistant.application.fichas;

import com.akine.assistant.application.CatalogoDeHerramientas;
import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.HerramientaDelAgente;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FichaPacienteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	private final List<String> llamadas = new ArrayList<>();

	private HerramientaDelAgente parte(String nombre, boolean deniega) {
		return new HerramientaDelAgente() {
			@Override
			public String nombre() {
				return nombre;
			}

			@Override
			public String descripcion() {
				return nombre;
			}

			@Override
			public String esquemaEntrada() {
				return "{\"type\":\"object\"}";
			}

			@Override
			public ResultadoDeHerramienta ejecutar(ActorDelAsistente actor, Map<String, Object> entrada,
					ContextoDeConsulta ctx) {
				llamadas.add(nombre);
				if (deniega) {
					throw new AccessDeniedException("sin permiso");
				}
				return new ResultadoDeHerramienta(List.of(Map.of("persona_id", 5L)), List.of(), null, false);
			}
		};
	}

	@Test
	@SuppressWarnings("unchecked")
	void con_casos_denegado_devuelve_el_resto_y_la_advertencia() {
		CatalogoDeHerramientas catalogo = new CatalogoDeHerramientas(List.of(parte("datos_persona", false),
				parte("turnos", false), parte("casos_persona", true), parte("practicas_aplicadas", false),
				parte("deudas_persona", false)));
		ObjectProvider<CatalogoDeHerramientas> proveedor = mock(ObjectProvider.class);
		when(proveedor.getObject()).thenReturn(catalogo);

		ResultadoDeHerramienta r = new FichaPaciente(proveedor).ejecutar(ACTOR, Map.of("persona_id", 5), CTX);

		assertThat(r.sinPermiso()).isFalse();
		assertThat(r.filas()).extracting(f -> f.get("seccion"))
				.containsExactly("datos_persona", "turnos", "practicas_aplicadas", "deudas_persona");
		assertThat(r.advertencias()).containsExactly("omitida_por_permiso: casos_persona");
		assertThat(llamadas).containsExactly("datos_persona", "turnos", "casos_persona", "practicas_aplicadas",
				"deudas_persona");
	}
}
