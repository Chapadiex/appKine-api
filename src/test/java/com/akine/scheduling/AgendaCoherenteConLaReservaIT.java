package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaService;
import com.akine.scheduling.application.AgendaView.DiaDeAgenda;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.ReprogramacionCommand;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paquete E-2, contra MySQL real: dos de los cuatro defectos que destaparon los E2E de la agenda.
 *
 * <p>Los otros dos —los horarios de hoy que ya pasaron y la habilitacion cargada hoy— dependen de
 * la hora actual y se prueban con reloj fijo en {@code AgendaServiceTest}: un IT con el reloj del
 * sistema seria verde o rojo segun la hora a la que corre.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AgendaCoherenteConLaReservaIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 15);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private AgendaService agendaService;
	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("un dia con todo vendido declara COMPLETO, y vuelve a tener slots al cancelar uno")
	void dia_lleno_es_completo() {
		Fixture fixture = fixtures.crear(1);
		// Lunes de 09:00 a 13:00 con oferta de 60 minutos: cuatro slots individuales.
		reservar(fixture, fixture.personaA(), hora(9));
		reservar(fixture, fixture.personaB(), hora(10));
		reservar(fixture, fixture.personaC(), hora(11));
		TurnoView ultimo = reservar(fixture, fixture.personaA(), hora(12));

		DiaDeAgenda lleno = elLunes(fixture);
		assertThat(lleno.motivoSinSlots()).isEqualTo("COMPLETO");
		assertThat(lleno.slots()).isEmpty();

		cicloService.cancelar(fixture.actor(), fixture.consultorioId(), ultimo.id(),
				"El paciente aviso que no viene", ultimo.version());

		DiaDeAgenda conLugar = elLunes(fixture);
		assertThat(conLugar.motivoSinSlots()).isNull();
		assertThat(conLugar.slots())
				.as("con un lugar libre el dia vuelve a viajar entero, los llenos en cupo 0")
				.hasSize(4)
				.filteredOn(slot -> slot.cupoLibre() > 0)
				.singleElement()
				.satisfies(slot -> assertThat(slot.desde()).isEqualTo(hora(12)));
	}

	@Test
	@DisplayName("reprogramar sin profesional mueve el turno con el profesional que ya tenia")
	void reprogramar_sin_profesional() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, fixture.personaA(), hora(9));

		TurnoView movido = cicloService.reprogramar(fixture.actor(), fixture.consultorioId(),
				turno.id(), new ReprogramacionCommand(hora(11), null, "El paciente pidio mas tarde",
						turno.version()));

		assertThat(movido.inicio()).isEqualTo(hora(11));
		assertThat(movido.profesionalId()).isEqualTo(fixture.profesionalMembershipId());
		assertThat(jdbc.queryForObject("SELECT profesional_membership_id FROM turno WHERE id = ?",
				Long.class, turno.id()))
				.as("y asi quedo en la base, no solo en la respuesta")
				.isEqualTo(fixture.profesionalMembershipId());
	}

	// =================================================================================

	private DiaDeAgenda elLunes(Fixture fixture) {
		return agendaService.buscar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				LUNES, LUNES.plusDays(1), null).dias().get(0);
	}

	private TurnoView reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}

	private Instant hora(int horaLocal) {
		return LUNES.atTime(horaLocal, 0).atZone(ZONA).toInstant();
	}
}
