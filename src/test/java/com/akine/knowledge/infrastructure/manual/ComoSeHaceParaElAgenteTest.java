package com.akine.knowledge.infrastructure.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.akine.knowledge.application.manual.IndiceDelManual;
import com.akine.knowledge.application.manual.PasajeEncontrado;
import com.akine.knowledge.application.manual.VisibilidadDelManual;
import com.akine.knowledge.domain.manual.TipoDePasaje;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@SuppressWarnings("unchecked")
class ComoSeHaceParaElAgenteTest {

	private final ComoSeHaceParaElAgente herramienta = new ComoSeHaceParaElAgente(
			new IndiceDelManual(new PathMatchingResourcePatternResolver(), mock(ObjectProvider.class)),
			mock(VisibilidadDelManual.class));

	@Test
	void unaPreguntaDeComoNoCompiteConElGlosario() {
		List<PasajeEncontrado> candidatos = herramienta.candidatos("¿cómo cierro la atención de la sesión?",
				f -> true, null);

		assertThat(candidatos).noneMatch(p -> p.pasaje().tipo() == TipoDePasaje.TERMINO);
		assertThat(candidatos).extracting(p -> p.pasaje().id()).contains("atencion#cerrar-la-atencion");
	}

	@Test
	void unaPreguntaDeDefinicionSiVeElGlosario() {
		List<PasajeEncontrado> candidatos = herramienta.candidatos("¿qué significa sesión o atención?", f -> true,
				null);

		assertThat(candidatos).anyMatch(p -> p.pasaje().tipo() == TipoDePasaje.TERMINO);
	}
}
