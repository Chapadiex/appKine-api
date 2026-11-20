package com.akine.knowledge.application.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.akine.knowledge.domain.manual.FichaDelManual;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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

	@Test
	@SuppressWarnings("unchecked")
	void buscarSigueConPalabrasSiElModeloFalla() {
		List<FichaDelManual> fichas = CargadorDelManual.cargar(new PathMatchingResourcePatternResolver(),
				"classpath:manual-prueba/*.md");
		ModeloEmbeddings modelo = mock(ModeloEmbeddings.class);
		when(modelo.pasajes(anyList())).thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream()
				.map(texto -> new float[] {1f, 0f}).toList());
		when(modelo.consulta(anyString())).thenThrow(new IllegalStateException("onnx caido"));
		ObjectProvider<ModeloEmbeddings> proveedor = mock(ObjectProvider.class);
		when(proveedor.getIfAvailable()).thenReturn(modelo);
		IndiceDelManual conModelo = new IndiceDelManual(fichas, proveedor);
		conModelo.calcularVectores(modelo);

		List<PasajeEncontrado> encontrados = conModelo.buscar("arqueo", TODAS, null, 5);

		assertThat(encontrados).isNotEmpty();
		assertThat(encontrados.getFirst().ficha().id()).isEqualTo("ficha");
	}

	@Test
	void unaRutaArmadaParaColgarLaBusquedaSeResuelveAlInstante() {
		String ruta = "/" + "#".repeat(50_000) + "/".repeat(50_000);

		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> indice.porRuta(ruta, TODAS));
		assertThat(IndiceDelManual.sinQuery("/pacientes/1?tab=x#y")).isEqualTo("/pacientes/1");
		assertThat(IndiceDelManual.segmentos("//pacientes//1/")).containsExactly("pacientes", "1");
	}
}
