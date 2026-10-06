package com.akine.activity.infrastructure;

import com.akine.activity.application.CapacidadDeClase;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import com.akine.scheduling.spi.EventoExternoDeAgenda.EventoDeAgendaExterno;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * Lo que M12 ve de las clases: si ocupan un recurso y como se proyectan en la agenda unificada.
 *
 * <p><b>Defecto real cubierto aca.</b> La proyeccion mandaba {@code ocupados = 0} fijo —"hasta
 * 08.02", decia el comentario— y 08.02 nunca volvio a tocarla: la agenda mostraba vacia una clase
 * completa. Y la capacidad ignoraba el box, asi que la grilla y la pantalla de cupos daban dos
 * numeros distintos para la misma clase.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClasesEnLaAgendaDeTurnos")
class ClasesEnLaAgendaDeTurnosTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long OFERTA_ID = 45L;
	private static final long ESPACIO_ID = 8L;
	private static final Instant INICIO = Instant.now().plus(2, ChronoUnit.DAYS)
			.truncatedTo(ChronoUnit.HOURS);
	private static final Instant FIN = INICIO.plus(1, ChronoUnit.HOURS);

	@Mock private ClaseProgramadaRepository clases;
	@Mock private OfertaDirectory ofertas;
	@Mock private EspacioDirectory espacios;

	private ClasesEnLaAgendaDeTurnos probe;

	@BeforeEach
	void setUp() {
		probe = new ClasesEnLaAgendaDeTurnos(clases, ofertas, new CapacidadDeClase(ofertas, espacios));
	}

	@Test
	@DisplayName("La clase viaja con los ocupados reales y la capacidad efectiva que incluye el box")
	void proyecta_ocupacion_y_capacidad_efectiva() {
		given(clases.findDeLaSedeEnVentana(ORG_ID, SEDE_ID, INICIO, FIN))
				.willReturn(List.of(clase(10, 5)));
		given(ofertas.find(ORG_ID, SEDE_ID, OFERTA_ID)).willReturn(Optional.of(oferta(12)));
		given(espacios.find(ORG_ID, ESPACIO_ID, INICIO)).willReturn(Optional.of(espacio(6)));

		List<EventoDeAgendaExterno> eventos = probe.enVentana(ORG_ID, SEDE_ID, INICIO, FIN);

		assertThat(eventos).singleElement().satisfies(evento -> {
			assertThat(evento.tipo()).isEqualTo(ClasesEnLaAgendaDeTurnos.TIPO_CLASE);
			assertThat(evento.ofertaNombre()).isEqualTo("Pilates grupal");
			assertThat(evento.estado()).isEqualTo("PROGRAMADA");
			assertThat(evento.capacidad()).as("min(clase 10, oferta 12, box 6)").isEqualTo(6);
			assertThat(evento.ocupados()).as("la columna que otorga el lugar").isEqualTo(5);
		});
	}

	@Test
	@DisplayName("Sin oferta resoluble queda la capacidad propia y el nombre vacio")
	void sin_oferta() {
		given(clases.findDeLaSedeEnVentana(ORG_ID, SEDE_ID, INICIO, FIN))
				.willReturn(List.of(clase(10, 3)));
		given(ofertas.find(anyLong(), anyLong(), anyLong())).willReturn(Optional.empty());

		EventoDeAgendaExterno evento = probe.enVentana(ORG_ID, SEDE_ID, INICIO, FIN).getFirst();

		assertThat(evento.capacidad()).isEqualTo(10);
		assertThat(evento.ofertaNombre()).isNull();
		assertThat(evento.ocupados()).isEqualTo(3);
	}

	@Test
	@DisplayName("Un recurso esta ocupado si alguna clase viva se le cruza")
	void ocupacion_de_recursos() {
		given(clases.findVivasDeProfesionalQueCruzan(ORG_ID, 31L, INICIO, FIN))
				.willReturn(List.of(clase(8, 0)));
		given(clases.findVivasDeEspacioQueCruzan(any(Long.class), any(Long.class), any(), any()))
				.willReturn(List.of());

		assertThat(probe.profesionalOcupado(ORG_ID, 31L, INICIO, FIN)).isTrue();
		assertThat(probe.espacioOcupado(ORG_ID, ESPACIO_ID, INICIO, FIN)).isFalse();
	}

	private static ClaseProgramada clase(int capacidad, int ocupados) {
		ClaseProgramada clase = new ClaseProgramada(ORG_ID, SEDE_ID, OFERTA_ID, 31L, ESPACIO_ID,
				"Pilates", INICIO, FIN, capacidad, 99L, Instant.now(), null, null);
		ReflectionTestUtils.setField(clase, "id", 77L);
		ReflectionTestUtils.setField(clase, "cupoOcupado", ocupados);
		return clase;
	}

	private static OfertaSnapshot oferta(int capacidad) {
		return new OfertaSnapshot(OFERTA_ID, ORG_ID, SEDE_ID, 12L, "Pilates grupal", 60,
				capacidad, true, true, true, false, false, LocalDate.now().minusYears(1), null,
				true);
	}

	private static EspacioSnapshot espacio(int capacidad) {
		return new EspacioSnapshot(ESPACIO_ID, ORG_ID, SEDE_ID, "Box 1", "SALON", capacidad,
				Instant.now().minus(365, ChronoUnit.DAYS), null, true, true);
	}
}
