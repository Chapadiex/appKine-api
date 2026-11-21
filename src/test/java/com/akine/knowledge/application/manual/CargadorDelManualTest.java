package com.akine.knowledge.application.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.knowledge.domain.manual.ManualInvalidoException;
import com.akine.knowledge.domain.manual.PasajeDelManual;
import com.akine.knowledge.domain.manual.TipoDePasaje;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class CargadorDelManualTest {

	private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	@Test
	void cargaLasFichasRealesSinErrores() {
		List<FichaDelManual> fichas = CargadorDelManual.cargar(resolver);

		assertThat(fichas).isNotEmpty();
		assertThat(fichas).allSatisfy(f -> assertThat(f.pasajes())
				.anyMatch(p -> p.tipo() == TipoDePasaje.PARA_QUE_SIRVE));
	}

	@Test
	void partePasajesPorTarea() {
		FichaDelManual ficha = CargadorDelManual.cargar(resolver, "classpath:manual-prueba/*.md").stream()
				.filter(f -> f.id().equals("ficha")).findFirst().orElseThrow();

		assertThat(ficha.pasajes()).filteredOn(p -> p.tipo() == TipoDePasaje.TAREA)
				.extracting(PasajeDelManual::id)
				.containsExactly("ficha#abrir-la-caja", "ficha#cerrar-la-caja");
		assertThat(ficha.pasajes()).extracting(PasajeDelManual::tipo)
				.contains(TipoDePasaje.PARA_QUE_SIRVE, TipoDePasaje.PROBLEMAS);
	}

	@Test
	void rechazaIdRepetido() {
		assertThatThrownBy(() -> CargadorDelManual.cargar(resolver, "classpath:manual-roto-repetido/*.md"))
				.isInstanceOf(ManualInvalidoException.class);
	}

	@Test
	void rechazaRelacionadaInexistente() {
		assertThatThrownBy(() -> CargadorDelManual.cargar(resolver, "classpath:manual-roto-relacionada/*.md"))
				.isInstanceOf(ManualInvalidoException.class)
				.hasMessageContaining("no-existe");
	}
}
