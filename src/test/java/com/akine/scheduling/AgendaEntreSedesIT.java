package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.resource.application.BloqueAltaCommand;
import com.akine.resource.application.DisponibilidadService;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.ResultadoDeReserva;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La misma persona no puede estar en dos sedes a la vez (agenda de profesionales §9.2 y §9.3),
 * contra MySQL real.
 *
 * <p>El defecto: el lock de reservas era el de la sede ({@code agenda_sede}) y el cruce de turnos
 * miraba UNA membership. Con memberships por sede, la misma persona tiene un id en cada una, y dos
 * reservas a la misma hora en dos sedes pasaban las dos. Ahora se toma ademas la fila de la persona
 * ({@code agenda_profesional}, V129) y el cruce mira todas sus memberships.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AgendaEntreSedesIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private TurnoService turnoService;
	@Autowired private DisponibilidadService disponibilidadService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("Dos reservas simultaneas de la misma persona a la misma hora en dos sedes: gana una")
	void no_hay_doble_turno_entre_sedes() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.otraSedeDelMismoProfesional(sedeA, true);
		Instant nueve = LUNES.atTime(9, 0).atZone(ZONA).toInstant();

		List<Desenlace<ResultadoDeReserva>> desenlaces = AgendaFixtures.enParalelo(List.<Callable<ResultadoDeReserva>>of(
				() -> reservar(sedeA, sedeA.personaA(), nueve),
				() -> reservar(sedeB, sedeB.personaB(), nueve)));

		assertThat(desenlaces).as("%s", desenlaces).filteredOn(d -> !d.fallo()).hasSize(1);
		assertThat(desenlaces).filteredOn(Desenlace::fallo)
				.singleElement()
				.satisfies(d -> assertThat(AgendaFixtures.causaEs(d.error(), RecursoOcupadoException.class))
						.as("%s", d).isTrue());
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno
				 WHERE organization_id = ? AND profesional_membership_id IN (?, ?) AND deleted_at IS NULL
				""", Long.class, sedeA.organizationId(), sedeA.profesionalMembershipId(),
				sedeB.profesionalMembershipId())).isEqualTo(1L);
	}

	@Test
	@DisplayName("El horario habitual de una persona no se pisa con el de su otra sede")
	void el_horario_habitual_no_se_pisa_entre_sedes() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.otraSedeDelMismoProfesional(sedeA, false);
		var admin = new com.akine.resource.application.OperatingActor(
				sedeB.actor().accountId(), false, sedeB.organizationId(), sedeB.consultorioId());

		// Lunes de 10 a 11 en la sede B, contra el lunes de 9 a 13 de la sede A.
		assertThatThrownBy(() -> disponibilidadService.crear(admin, sedeB.consultorioId(),
				sedeB.profesionalMembershipId(), new BloqueAltaCommand(DayOfWeek.MONDAY.getValue(),
						LocalTime.of(10, 0), LocalTime.of(11, 0), null, null)))
				.isInstanceOf(BloqueSolapadoException.class);

		// El martes no se pisa con nada: entra.
		assertThat(disponibilidadService.crear(admin, sedeB.consultorioId(),
				sedeB.profesionalMembershipId(), new BloqueAltaCommand(DayOfWeek.TUESDAY.getValue(),
						LocalTime.of(10, 0), LocalTime.of(11, 0), null, null)).id()).isPositive();
	}

	private ResultadoDeReserva reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null));
	}
}
