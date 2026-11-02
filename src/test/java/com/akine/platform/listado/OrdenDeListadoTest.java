package com.akine.platform.listado;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.akine.platform.spi.listado.ListadoEnMemoria;
import com.akine.platform.spi.listado.OrdenDeListado;
import com.akine.platform.spi.listado.TextoDeBusqueda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DP-45, punto 1: el parametro sort, el q y el listado en memoria. */
class OrdenDeListadoTest {

	private record Fila(long id, String nombre) {
	}

	@Test
	@DisplayName("sort ausente o en blanco no es un orden: el listado conserva el de siempre")
	void sin_sort() {
		assertThat(OrdenDeListado.leer(null, "nombre")).isEmpty();
		assertThat(OrdenDeListado.leer("  ", "nombre")).isEmpty();
		assertThat(OrdenDeListado.claveDe(Optional.empty())).isEmpty();
	}

	@Test
	@DisplayName("campo,asc y campo,desc de la lista blanca se leen; el Sort desempata por id")
	void sort_valido() {
		OrdenDeListado orden = OrdenDeListado.leer("nombre,desc", "nombre", "tipo").orElseThrow();

		assertThat(orden.clave()).isEqualTo("nombre,desc");
		assertThat(orden.aSort("name", "id"))
				.isEqualTo(Sort.by(Sort.Direction.DESC, "name").and(Sort.by(Sort.Direction.DESC, "id")));
		assertThat(OrdenDeListado.leer("tipo,asc", "nombre", "tipo").orElseThrow().descendente())
				.isFalse();
	}

	@Test
	@DisplayName("Campo, direccion o forma fuera de la lista blanca: 400, sin reflejar el valor")
	void sort_invalido() {
		for (String malo : List.of("email,asc", "nombre", "nombre,ASC", "nombre,up",
				"nombre,asc,tipo,desc", "nombre;drop,asc", ",asc")) {
			assertThatThrownBy(() -> OrdenDeListado.leer(malo, "nombre", "tipo"))
					.as(malo)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("nombre, tipo")
					.hasMessageNotContaining("drop");
		}
	}

	@Test
	@DisplayName("q: contiene, sin mayusculas ni acentos; el patron LIKE escapa los comodines")
	void texto() {
		assertThat(TextoDeBusqueda.contiene("Box Ñandú", "nandu")).isTrue();
		assertThat(TextoDeBusqueda.contiene("Pérez", "PEREZ")).isTrue();
		assertThat(TextoDeBusqueda.contiene("Gomez", "perez")).isFalse();
		assertThat(TextoDeBusqueda.contiene(null, "x")).isFalse();
		assertThat(TextoDeBusqueda.contiene(null, " ")).as("sin q todo coincide").isTrue();
		assertThat(TextoDeBusqueda.patronLike(" 50%_a\\b ")).isEqualTo("%50\\%\\_a\\\\b%");
		assertThat(TextoDeBusqueda.patronLike("")).isNull();
	}

	@Test
	@DisplayName("En memoria: filtra, ordena con el comparador del campo y desempata por id")
	void en_memoria() {
		List<Fila> filas = List.of(new Fila(3, "beta"), new Fila(1, "Álvaro"), new Fila(2, "beta"));
		Map<String, Comparator<Fila>> comparadores =
				Map.of("nombre", TextoDeBusqueda.comparadorDeTexto(Fila::nombre));
		Comparator<Fila> porId = Comparator.comparingLong(Fila::id);

		assertThat(ListadoEnMemoria.filtrarYOrdenar(filas, f -> true, Optional.empty(),
				comparadores, porId)).as("sin orden, el de entrada").containsExactlyElementsOf(filas);
		assertThat(ListadoEnMemoria.filtrarYOrdenar(filas, f -> true,
				OrdenDeListado.leer("nombre,asc", comparadores.keySet()), comparadores, porId))
				.extracting(Fila::id).containsExactly(1L, 2L, 3L);
		assertThat(ListadoEnMemoria.filtrarYOrdenar(filas, f -> true,
				OrdenDeListado.leer("nombre,desc", comparadores.keySet()), comparadores, porId))
				.extracting(Fila::id).containsExactly(3L, 2L, 1L);
		assertThat(ListadoEnMemoria.filtrarYOrdenar(filas,
				f -> TextoDeBusqueda.contiene(f.nombre(), "alva"), Optional.empty(), comparadores, porId))
				.extracting(Fila::id).containsExactly(1L);
	}
}
