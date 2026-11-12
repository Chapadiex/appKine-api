package com.akine.knowledge.application;

import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.domain.port.AlmacenVectorial.FragmentoEncontrado;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import com.akine.knowledge.spi.CaminoBusqueda;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusquedaHibridaTest {

	private static final BusquedaHibrida.Ajustes AJUSTES = new BusquedaHibrida.Ajustes(
			20, 20, 60, 5, 6, 0.005, 2, new BigDecimal("0.818"));

	/** Almacen falso: listas fijas y textos por id (libro = id / 100). */
	private static final class Falso implements AlmacenVectorial {
		List<Coincidencia> vector = List.of();
		List<Coincidencia> palabras = List.of();
		Map<Long, String> textos = Map.of();

		@Override
		public List<Coincidencia> porVector(long orgId, String modelo, float[] consulta, int k) {
			return vector;
		}

		@Override
		public List<Coincidencia> porPalabras(long orgId, String consulta, int k) {
			return palabras;
		}

		@Override
		public List<FragmentoEncontrado> fragmentos(long orgId, List<Long> ids) {
			List<FragmentoEncontrado> r = new ArrayList<>();
			for (Long id : ids) {
				if (textos.containsKey(id)) {
					r.add(new FragmentoEncontrado(id, id / 100, "Libro", 1, 1, textos.get(id)));
				}
			}
			return r;
		}
	}

	private static Coincidencia c(long id, String puntaje) {
		return new Coincidencia(id, new BigDecimal(puntaje));
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<ModeloEmbeddings> proveedor(ModeloEmbeddings m) {
		ObjectProvider<ModeloEmbeddings> p = mock(ObjectProvider.class);
		when(p.getIfAvailable()).thenReturn(m);
		return p;
	}

	private static ModeloEmbeddings modeloOk() {
		ModeloEmbeddings m = mock(ModeloEmbeddings.class);
		when(m.id()).thenReturn("m");
		when(m.consulta("q")).thenReturn(new float[] {1f});
		return m;
	}

	@Test
	void fusionaPorPosicionSinDuplicados() {
		Falso a = new Falso();
		a.vector = List.of(c(1, "0.9"), c(2, "0.8"));
		a.palabras = List.of(c(2, "0.7"), c(3, "0.6"));
		a.textos = Map.of(1L, "x", 2L, "x", 3L, "x");

		ResultadoHibrido r = new BusquedaHibrida(a, proveedor(modeloOk()), AJUSTES)
				.buscar(1, List.of("q"), null, Set.of());

		assertThat(r.camino()).isEqualTo(CaminoBusqueda.VECTOR);
		assertThat(r.pasajes()).hasSize(3);
		// El 2 esta en las dos listas (posiciones 2 y 1): 1/62 + 1/61 supera al 1 (1/61 solo).
		assertThat(r.pasajes().get(0).rrf()).isEqualTo(1d / 62 + 1d / 61);
		assertThat(r.pasajes().get(0).pasaje().texto()).isEqualTo("x");
		assertThat(r.pasajes().stream().map(p -> p.rrf()).toList())
				.containsExactly(1d / 62 + 1d / 61, 1d / 61, 1d / 62);
	}

	@Test
	void bonusDeLibroSoloReordena() {
		Falso a = new Falso();
		a.vector = List.of(c(100, "0.9"), c(200, "0.89"));
		a.textos = Map.of(100L, "x", 200L, "x");
		BusquedaHibrida h = new BusquedaHibrida(a, proveedor(modeloOk()), AJUSTES);

		ResultadoHibrido sin = h.buscar(1, List.of("q"), null, Set.of());
		ResultadoHibrido con = h.buscar(1, List.of("q"), null, Set.of(2L));

		assertThat(sin.pasajes().get(0).pasaje().libroId()).isEqualTo(1L);
		// 1/62 + 0.005 > 1/61: el libro preferido pasa adelante, y nada sale ni entra.
		assertThat(con.pasajes().get(0).pasaje().libroId()).isEqualTo(2L);
		assertThat(con.pasajes()).hasSameSizeAs(sin.pasajes());
		assertThat(con.hayEvidencia()).isEqualTo(sin.hayEvidencia());
	}

	@Test
	void evidenciaConPalabrasAunqueNoSupereElCorte() {
		Falso a = new Falso();
		a.vector = List.of(c(1, "0.70"), c(2, "0.69"));
		a.palabras = List.of(c(1, "0.4"), c(2, "0.3"), c(3, "0.2"));
		a.textos = Map.of(1L, "El Manguito rotador", 2L, "tratamiento del MANGUITO", 3L, "otra cosa");

		ResultadoHibrido r = new BusquedaHibrida(a, proveedor(modeloOk()), AJUSTES)
				.buscar(1, List.of("q"), "manguito rotador", Set.of());

		assertThat(r.pasajes()).noneMatch(PasajeFusionado::superaCorte);
		assertThat(r.pasajes()).filteredOn(PasajeFusionado::coincidePalabra).hasSize(2);
		assertThat(r.hayEvidencia()).isTrue();
	}

	@Test
	void evidenciaSoloCuentaLoQueSeDevuelve() {
		Falso a = new Falso();
		// El vector llena 20 posiciones con pasajes sin evidencia (ids 1..20) y las palabras traen
		// 14 pasajes sin evidencia (ids 101..114) y recien en las posiciones 15 y 16 dos con
		// evidencia (ids 115 y 116): su RRF (1/75, 1/76) queda por debajo del puesto 6 (>= 1/66).
		List<Coincidencia> flojos = new ArrayList<>();
		Map<Long, String> textos = new java.util.HashMap<>();
		for (long i = 1; i <= 20; i++) {
			flojos.add(c(i, "0.5"));
			textos.put(i, "nada que ver");
		}
		a.vector = flojos;
		List<Coincidencia> palabras = new ArrayList<>();
		for (long i = 101; i <= 116; i++) {
			palabras.add(c(i, "0.1"));
			textos.put(i, i >= 115 ? "habla de lumbalgia" : "otra cosa");
		}
		a.palabras = palabras;
		a.textos = textos;

		ResultadoHibrido r = new BusquedaHibrida(a, proveedor(modeloOk()), AJUSTES)
				.buscar(1, List.of("q"), "lumbalgia", Set.of());

		assertThat(r.pasajes()).hasSize(6);
		assertThat(r.pasajes()).extracting(p -> p.pasaje().texto()).doesNotContain("habla de lumbalgia");
		assertThat(r.pasajes()).noneMatch(p -> p.superaCorte() || p.coincidePalabra());
		assertThat(r.hayEvidencia()).isFalse();
	}

	@Test
	void coincidenciaEsPorPalabraCompleta() {
		Falso a = new Falso();
		a.palabras = List.of(c(1, "0.5"), c(2, "0.4"));
		a.textos = Map.of(1L, "El manometro mide presion", 2L, "La mano, sana.");

		ResultadoHibrido r = new BusquedaHibrida(a, proveedor(null), AJUSTES)
				.buscar(1, List.of("q"), "mano dolor", Set.of());

		assertThat(r.pasajes()).hasSize(2);
		assertThat(r.pasajes().get(0).coincidePalabra()).isFalse();
		assertThat(r.pasajes().get(1).coincidePalabra()).isTrue();
		assertThat(r.hayEvidencia()).isFalse();
	}

	@Test
	void temaQueElLibroNoNombraNoTieneEvidenciaAunqueSupereElCorte() {
		Falso a = new Falso();
		a.vector = List.of(c(1, "0.84"), c(2, "0.84"));
		a.palabras = List.of(c(1, "0.4"), c(2, "0.3"));
		a.textos = Map.of(1L, "Tratamiento del dolor cronico", 2L, "Fibrosis quistica y ejercicio");

		ResultadoHibrido r = new BusquedaHibrida(a, proveedor(modeloOk()), AJUSTES)
				.buscar(1, List.of("q"), "fibromialgia", Set.of());

		assertThat(r.pasajes()).allMatch(PasajeFusionado::superaCorte);
		assertThat(r.hayEvidencia()).isFalse();
	}

	@Test
	void siglaNoEsObligatoriaYElComienzoDeLaPalabraAlcanza() {
		Falso a = new Falso();
		a.palabras = List.of(c(1, "0.4"), c(2, "0.3"));
		a.textos = Map.of(1L, "Dolor de hombro tras un accidente cerebrovascular", 2L, "El hombro y la medula");

		ResultadoHibrido r = new BusquedaHibrida(a, proveedor(null), AJUSTES)
				.buscar(1, List.of("q"), "acv hombro doloroso", Set.of());

		assertThat(r.hayEvidencia()).isTrue();
	}

	@Test
	void sinNadaNoHayEvidencia() {
		ResultadoHibrido r = new BusquedaHibrida(new Falso(), proveedor(modeloOk()), AJUSTES)
				.buscar(1, List.of("q"), "lumbalgia", Set.of());

		assertThat(r.pasajes()).isEmpty();
		assertThat(r.hayEvidencia()).isFalse();
	}

	@Test
	void sinModeloCaeAPalabras() {
		Falso a = new Falso();
		a.vector = List.of(c(1, "0.99"));
		a.palabras = List.of(c(2, "0.5"));
		a.textos = Map.of(1L, "x", 2L, "lumbalgia cronica");

		ResultadoHibrido sinModelo = new BusquedaHibrida(a, proveedor(null), AJUSTES)
				.buscar(1, List.of("q"), "lumbalgia", Set.of());
		ModeloEmbeddings roto = mock(ModeloEmbeddings.class);
		when(roto.consulta("q")).thenThrow(new IllegalStateException("boom"));
		ResultadoHibrido fallo = new BusquedaHibrida(a, proveedor(roto), AJUSTES)
				.buscar(1, List.of("q"), "lumbalgia", Set.of());

		for (ResultadoHibrido r : List.of(sinModelo, fallo)) {
			assertThat(r.camino()).isEqualTo(CaminoBusqueda.PALABRAS);
			assertThat(r.pasajes()).hasSize(1);
			assertThat(r.pasajes().get(0).coincidePalabra()).isTrue();
		}
	}
}
