package com.akine.person.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.person.application.CoberturaPacienteService;
import com.akine.person.application.CoberturaView;
import com.akine.person.application.MascaraDeAfiliado;
import com.akine.person.application.PersonaPagina;
import com.akine.person.application.PersonaService;
import com.akine.person.application.PersonaView;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code buscar_persona}, {@code datos_persona} y la mascara del afiliado. */
class PersonaParaElAgenteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	private final PersonaService personas = mock(PersonaService.class);
	private final CoberturaPacienteService coberturas = mock(CoberturaPacienteService.class);

	private static PersonaView persona(long id, String nombre, String apellido, String documento) {
		PersonaView p = mock(PersonaView.class);
		when(p.id()).thenReturn(id);
		when(p.nombre()).thenReturn(nombre);
		when(p.apellido()).thenReturn(apellido);
		when(p.tipoDocumento()).thenReturn("DNI");
		when(p.numeroDocumento()).thenReturn(documento);
		return p;
	}

	private static CoberturaView cobertura(boolean vigente, String afiliado) {
		CoberturaView c = mock(CoberturaView.class);
		when(c.vigente()).thenReturn(vigente);
		when(c.financiadorNombre()).thenReturn("OSDE");
		when(c.numeroAfiliado()).thenReturn(afiliado);
		when(c.principal()).thenReturn(true);
		return c;
	}

	// ---- MascaraDeAfiliado ----

	@Test
	void la_mascara_deja_los_ultimos_cuatro_y_tapa_los_cortos() {
		assertThat(MascaraDeAfiliado.enmascarar(" 123456789 ")).isEqualTo("···6789");
		assertThat(MascaraDeAfiliado.enmascarar("1234")).isEqualTo("···");
		assertThat(MascaraDeAfiliado.enmascarar(" ")).isNull();
		assertThat(MascaraDeAfiliado.enmascarar(null)).isNull();
	}

	// ---- buscar_persona ----

	@Test
	void buscar_devuelve_id_nombre_y_documento_enmascarado() {
		PersonasParaElAgente herramienta = new PersonasParaElAgente(personas);
		List<PersonaView> encontradas = List.of(persona(20L, "Pedro", "Quiroga", "30123456"),
				persona(21L, "Ana", "Díaz", null));
		when(personas.buscar(any(), any(), eq(0), eq(PersonasParaElAgente.MAXIMO)))
				.thenReturn(new PersonaPagina(encontradas, 12));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("texto", "quiroga"), CTX);

		assertThat(herramienta.nombre()).isEqualTo("buscar_persona");
		assertThat(herramienta.descripcion()).isNotBlank();
		assertThat(herramienta.esquemaEntrada()).contains("texto");
		assertThat(r.filas().getFirst()).containsEntry("persona_id", 20L).containsEntry("nombre", "Quiroga, Pedro")
				.containsEntry("documento", "DNI ***456");
		assertThat(r.filas().get(1)).containsEntry("documento", null);
		assertThat(r.advertencias()).singleElement().asString().startsWith("Hay 12 resultados");
	}

	@Test
	void buscar_nombre_y_apellido_juntos_ancla_en_la_palabra_mas_larga() {
		PersonasParaElAgente herramienta = new PersonasParaElAgente(personas);
		when(personas.buscar(any(), argThat(b -> b != null && "pedro quiroga".equals(b.texto())), eq(0), eq(10)))
				.thenReturn(new PersonaPagina(List.of(), 0));
		List<PersonaView> quirogas = List.of(persona(20L, "Pedro", "Quiroga", "12"),
				persona(22L, "Juan", "Quiroga", "99"));
		when(personas.buscar(any(), argThat(b -> b != null && "quiroga".equals(b.texto())), eq(0), eq(50)))
				.thenReturn(new PersonaPagina(quirogas, 2));

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("texto", "pedro quiroga"), CTX);

		assertThat(r.filas()).extracting(f -> f.get("persona_id")).containsExactly(20L);
		assertThat(r.filas().getFirst()).containsEntry("documento", "DNI ***12");
		assertThat(r.advertencias()).isEmpty();
	}

	@Test
	void buscar_sin_texto_es_error() {
		assertThatThrownBy(() -> new PersonasParaElAgente(personas).ejecutar(ACTOR, Map.of(), CTX))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ---- datos_persona ----

	@Test
	void datos_con_coberturas_vigentes_y_afiliado_enmascarado() {
		DatosDePersonaParaElAgente herramienta = new DatosDePersonaParaElAgente(personas, coberturas);
		PersonaView pedro = persona(20L, "Pedro", "Quiroga", "30123456");
		when(pedro.esPaciente()).thenReturn(true);
		when(personas.ver(any(), eq(20L))).thenReturn(pedro);
		List<CoberturaView> suyas = List.of(cobertura(true, "998877665"), cobertura(false, "111122223"));
		when(coberturas.listar(any(), eq(20L), any(), eq(null))).thenReturn(suyas);

		ResultadoDeHerramienta r = herramienta.ejecutar(ACTOR, Map.of("persona_id", 20), CTX);

		assertThat(herramienta.nombre()).isEqualTo("datos_persona");
		assertThat(herramienta.descripcion()).isNotBlank();
		assertThat(herramienta.esquemaEntrada()).contains("persona_id");
		Map<String, Object> fila = r.filas().getFirst();
		assertThat(fila).containsEntry("nombre", "Quiroga, Pedro").containsEntry("documento", "DNI ***456")
				.containsEntry("es_paciente", true);
		assertThat((List<?>) fila.get("coberturas_vigentes")).singleElement()
				.satisfies(c -> assertThat(((Map<?, ?>) c).get("afiliado")).isEqualTo("···7665"));
	}

	@Test
	void datos_de_una_persona_ajena_no_la_encuentra() {
		when(personas.ver(any(), eq(20L))).thenThrow(new PersonaNotAccessibleException(20L));

		assertThat(new DatosDePersonaParaElAgente(personas, coberturas).ejecutar(ACTOR, Map.of("persona_id", 20), CTX)
				.advertencias()).containsExactly("No encontré esa persona");
	}

	@Test
	void datos_sin_persona_es_error() {
		assertThatThrownBy(() -> new DatosDePersonaParaElAgente(personas, coberturas).ejecutar(ACTOR, Map.of(), CTX))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
