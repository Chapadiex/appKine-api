package com.akine.activity.infrastructure;

import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ClaseProgramadaRepositoryPort;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** El nombre de la clase de una Sesion grupal (AKINE-08.05, web #104). */
class TituloDeClaseParaLaSesionTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 7L;
	private static final long CLASE_ID = 640L;
	private static final long OFERTA_ID = 42L;
	private static final Instant INICIO = Instant.parse("2026-10-16T13:00:00Z");

	private final ClaseProgramadaRepositoryPort clases = mock(ClaseProgramadaRepositoryPort.class);
	private final OfertaDirectory ofertas = mock(OfertaDirectory.class);
	private final TituloDeClaseParaLaSesion titulos = new TituloDeClaseParaLaSesion(clases, ofertas);

	@Test
	@DisplayName("el titulo propio de la clase manda, sin preguntar por la oferta")
	void titulo_propio() {
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID))
				.willReturn(Optional.of(clase("Pilates terapeutico")));

		assertThat(titulos.de(ORG_ID, SEDE_ID, CLASE_ID)).contains("Pilates terapeutico");
		verifyNoInteractions(ofertas);
	}

	@Test
	@DisplayName("sin titulo, o en blanco, el nombre comercial de la oferta")
	void sin_titulo_cae_a_la_oferta() {
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(Optional.of(clase("  ")));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(new OfertaSnapshot(
				OFERTA_ID, ORG_ID, SEDE_ID, 5L, "Rehabilitacion grupal", 60, 8, true, false, false,
				true, true, LocalDate.of(2020, 1, 1), null, true)));

		assertThat(titulos.de(ORG_ID, SEDE_ID, CLASE_ID)).contains("Rehabilitacion grupal");
	}

	@Test
	@DisplayName("una clase que no es de esa sede no tiene nombre")
	void clase_ajena() {
		given(clases.findByIdInScope(ORG_ID, SEDE_ID, CLASE_ID)).willReturn(Optional.empty());

		assertThat(titulos.de(ORG_ID, SEDE_ID, CLASE_ID)).isEmpty();
	}

	private static ClaseProgramada clase(String titulo) {
		return new ClaseProgramada(ORG_ID, SEDE_ID, OFERTA_ID, 31L, null, titulo,
				INICIO, INICIO.plus(1, ChronoUnit.HOURS), 8, 91L, INICIO, null, null);
	}
}
