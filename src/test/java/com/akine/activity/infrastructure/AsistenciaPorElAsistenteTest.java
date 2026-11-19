package com.akine.activity.infrastructure;

import com.akine.activity.infrastructure.AsistenciaPorElAsistente.Participante;
import com.akine.assistant.spi.PedidoDeAccion;
import com.akine.assistant.spi.Tarjeta.ItemDeAsistencia;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** La regla de marcado de la asistencia dictada (AKINE-asistente-acciones §6.1). */
class AsistenciaPorElAsistenteTest {

	private static final List<Participante> INSCRIPTOS = List.of(
			new Participante(1, "María", "Gómez", null),
			new Participante(2, "Juan", "Pérez", null),
			new Participante(3, "Ana", "Díaz", "AUSENTE"));

	@Test
	void todosMenosMarcaPresentesSalvoLosNombrados() {
		List<String> avisos = new ArrayList<>();

		List<ItemDeAsistencia> items = AsistenciaPorElAsistente.marcar(
				INSCRIPTOS, PedidoDeAccion.TODOS_MENOS, List.of("maria"), avisos);

		assertThat(items).extracting(ItemDeAsistencia::resultado)
				.containsExactly("AUSENTE", "PRESENTE", "PRESENTE");
		assertThat(items).extracting(ItemDeAsistencia::nombre)
				.containsExactly("Gómez, María", "Pérez, Juan", "Díaz, Ana");
		assertThat(items).extracting(ItemDeAsistencia::yaRegistrado).containsExactly(null, null, "AUSENTE");
		assertThat(avisos).isEmpty();
	}

	@Test
	void soloMarcaPresentesALosNombradosYAusenteAlResto() {
		List<String> avisos = new ArrayList<>();

		List<ItemDeAsistencia> items = AsistenciaPorElAsistente.marcar(
				INSCRIPTOS, PedidoDeAccion.SOLO, List.of("Ana", "juan  perez", "PEREZ"), avisos);

		assertThat(items).extracting(ItemDeAsistencia::resultado)
				.containsExactly("AUSENTE", "PRESENTE", "PRESENTE");
		assertThat(avisos).isEmpty();
	}

	@Test
	void sinModoNadieQuedaMarcado() {
		List<String> avisos = new ArrayList<>();

		List<ItemDeAsistencia> items = AsistenciaPorElAsistente.marcar(INSCRIPTOS, null, List.of("María"), avisos);

		assertThat(items).hasSize(3).extracting(ItemDeAsistencia::resultado).containsOnlyNulls();
		assertThat(avisos).isEmpty();
	}

	@Test
	void unNombreQueNoEstaQuedaComoAvisoYNoCambiaElResto() {
		List<String> avisos = new ArrayList<>();

		List<ItemDeAsistencia> items = AsistenciaPorElAsistente.marcar(
				INSCRIPTOS, PedidoDeAccion.TODOS_MENOS, List.of("Lucía"), avisos);

		assertThat(items).extracting(ItemDeAsistencia::resultado)
				.containsExactly("PRESENTE", "PRESENTE", "PRESENTE");
		assertThat(avisos).containsExactly("«Lucía» no está entre los inscriptos.");
	}

	@Test
	void unNombreQueCoincideConDosLosDejaSinMarcarYAvisa() {
		List<Participante> conDosMarias = List.of(
				new Participante(1, "María", "Gómez", null),
				new Participante(2, "María", "López", null),
				new Participante(3, "Juan", "Pérez", null));
		List<String> avisos = new ArrayList<>();

		List<ItemDeAsistencia> items = AsistenciaPorElAsistente.marcar(
				conDosMarias, PedidoDeAccion.TODOS_MENOS, List.of("María"), avisos);

		assertThat(items).extracting(ItemDeAsistencia::resultado).containsExactly(null, null, "PRESENTE");
		assertThat(avisos).containsExactly("Hay más de una persona que coincide con «María»: marcala a mano.");
	}
}
