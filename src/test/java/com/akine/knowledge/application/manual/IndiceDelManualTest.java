package com.akine.knowledge.application.manual;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.knowledge.domain.manual.FichaDelManual;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class IndiceDelManualTest {

	private static final Predicate<FichaDelManual> TODAS = f -> true;

	private final IndiceDelManual indice = new IndiceDelManual(
			CargadorDelManual.cargar(new PathMatchingResourcePatternResolver(), "classpath:manual-prueba/*.md"), null);

	@Test
	void porRutaResuelveParametrosYQuery() {
		assertThat(indice.porRuta("/pacientes/123/resumen?tab=x", TODAS))
				.map(FichaDelManual::id).contains("pacientes-prueba");
	}

	@Test
	void porRutaSinFichaEsVacio() {
		assertThat(indice.porRuta("/no-existe/tampoco", TODAS)).isEmpty();
	}

	@Test
	void buscarEncuentraPorSinonimo() {
		List<PasajeEncontrado> encontrados = indice.buscar("¿cómo hago el arqueo?", TODAS, null, 5);

		assertThat(encontrados).isNotEmpty();
		assertThat(encontrados.getFirst().ficha().id()).isEqualTo("ficha");
	}

	@Test
	void buscarNoDevuelveFichasInvisibles() {
		List<PasajeEncontrado> encontrados = indice.buscar("caja buscar persona", f -> !f.id().equals("ficha"),
				null, 10);

		assertThat(encontrados).isNotEmpty().noneMatch(p -> p.ficha().id().equals("ficha"));
	}

	@Test
	void buscarPrefiereLaFichaDeLaRutaActual() {
		// "tocá" no esta en ningun titulo: los dos pasajes con "Tocá" empatan por palabras.
		List<PasajeEncontrado> desdePadron = indice.buscar("nombre buscador caja", TODAS, "/pacientes", 10);
		List<PasajeEncontrado> desdeCaja = indice.buscar("nombre buscador caja", TODAS, "/caja", 10);

		assertThat(desdePadron.getFirst().puntaje()).isGreaterThan(0d);
		assertThat(desdeCaja.stream().filter(p -> p.ficha().id().equals("ficha")).findFirst().orElseThrow().puntaje())
				.isGreaterThan(desdePadron.stream().filter(p -> p.ficha().id().equals("ficha")).findFirst()
						.orElseThrow().puntaje());
	}
}
