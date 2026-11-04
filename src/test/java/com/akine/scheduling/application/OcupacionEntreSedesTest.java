package com.akine.scheduling.application;

import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import com.akine.scheduling.spi.OcupacionExternaProbe.OcupacionExterna;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Los conflictos heredados turno–clase de la misma persona se derivan al leer, y la ocupacion de otra
 * sede viaja sin paciente ni id (agenda de clases y avisos, 0.156.0).
 */
class OcupacionEntreSedesTest {

	private static final long ORG = 1L;
	private static final long SEDE_A = 10L;
	private static final long SEDE_B = 20L;
	private static final long MEMBERSHIP_A = 31L;
	private static final long MEMBERSHIP_B = 41L;
	private static final Instant DIEZ = Instant.parse("2027-03-08T13:00:00Z");
	private static final Instant ONCE = DIEZ.plusSeconds(3600);

	@Test
	@DisplayName("Un turno de esta sede pisado por una clase de la otra es un conflicto, y la clase es ocupacion en otra sede")
	void conflicto_y_ocupacion() {
		TurnoRepositoryPort turnos = mock(TurnoRepositoryPort.class);
		OcupacionExternaProbe clases = mock(OcupacionExternaProbe.class);
		AgendaDelProfesional agenda = mock(AgendaDelProfesional.class);
		given(agenda.deLaMismaPersona(ORG, MEMBERSHIP_A)).willReturn(Set.of(MEMBERSHIP_A, MEMBERSHIP_B));
		Turno turno = new Turno(ORG, SEDE_A, 5L, 7L, MEMBERSHIP_A, null, DIEZ, ONCE, 1L, DIEZ, null, null);
		ReflectionTestUtils.setField(turno, "id", 500L);
		given(turnos.findVivosDeProfesionalesQueCruzan(anyLong(), any(), any(), any())).willReturn(List.of(turno));
		given(clases.ocupacionDeProfesionales(anyLong(), any(), any(), any())).willReturn(List.of(
				new OcupacionExterna("CLASE", 9L, SEDE_B, MEMBERSHIP_B, DIEZ.plusSeconds(1800), ONCE.plusSeconds(1800))));

		OcupacionEntreSedes.Resultado resultado = new OcupacionEntreSedes(turnos, clases, agenda)
				.de(ORG, SEDE_A, List.of(MEMBERSHIP_A), DIEZ.minusSeconds(36000), ONCE.plusSeconds(36000));

		assertThat(resultado.ocupaciones()).singleElement().satisfies(o -> {
			assertThat(o.membershipId()).isEqualTo(MEMBERSHIP_A);
			assertThat(o.tipo()).isEqualTo("CLASE");
			assertThat(o.consultorioId()).isEqualTo(SEDE_B);
		});
		assertThat(resultado.conflictos()).singleElement().satisfies(c -> {
			assertThat(c.turnoId()).isEqualTo(500L);
			assertThat(c.claseId()).as("la clase es de otra sede: sin id").isNull();
			assertThat(c.claseConsultorioId()).isEqualTo(SEDE_B);
			assertThat(c.inicio()).isEqualTo(DIEZ.plusSeconds(1800));
			assertThat(c.fin()).isEqualTo(ONCE);
		});
	}
}
