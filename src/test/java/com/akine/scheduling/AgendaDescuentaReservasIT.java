package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaService;
import com.akine.scheduling.application.AgendaView;
import com.akine.scheduling.application.AgendaView.SlotDisponible;
import com.akine.scheduling.application.CicloDeTurnoService;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La agenda descuenta los turnos ya vendidos, contra MySQL real (F5).
 *
 * <p>{@code ReservaProbeSobreTurnos} reemplazo el 28/09 una implementacion provisoria que devolvia
 * el mapa vacio: la agenda ofrecia huecos ya vendidos y el usuario se comia un 409 al confirmar.
 * Hasta aca solo tenia unitarios con el repositorio mockeado, asi que nada probaba la consulta
 * {@code findVivosDeLaOfertaEnVentana} ni que el motor de slots use lo que devuelve.
 *
 * <p>Se mira el resultado que ve la pantalla —{@link AgendaService#buscar}— y no la sonda sola:
 * lo que importa es que el cupo libre baje, no que un metodo devuelva un mapa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AgendaDescuentaReservasIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
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
	@DisplayName("el cupo libre descuenta las reservas vivas y devuelve el de las canceladas")
	void la_agenda_descuenta_lo_vendido() {
		Fixture fixture = fixtures.crear(2);

		reservar(fixture, fixture.personaA(), hora(9));
		reservar(fixture, fixture.personaB(), hora(9));
		TurnoView cancelado = reservar(fixture, fixture.personaC(), hora(10));
		cicloService.cancelar(fixture.actor(), fixture.consultorioId(), cancelado.id(),
				"El paciente aviso que no viene", cancelado.version());
		reservar(fixture, fixture.personaA(), hora(11));

		Map<Instant, SlotDisponible> slots = slotsDelLunes(fixture);

		assertThat(slots.get(hora(9)))
				.as("las 9 estan vendidas: si aparecen, tienen que aparecer sin cupo")
				.satisfiesAnyOf(
						slot -> assertThat(slot).isNull(),
						slot -> assertThat(slot.cupoLibre()).isZero());
		assertThat(slots.get(hora(10)).cupoLibre())
				.as("la cancelacion devuelve el lugar: baja logica, deleted_at sellado")
				.isEqualTo(2);
		assertThat(slots.get(hora(11)).cupoLibre())
				.as("una reserva viva descuenta uno")
				.isEqualTo(1);
		assertThat(slots.get(hora(12)).cupoLibre())
				.as("y un horario sin reservas queda entero")
				.isEqualTo(2);
	}

	// =================================================================================

	private Map<Instant, SlotDisponible> slotsDelLunes(Fixture fixture) {
		AgendaView agenda = agendaService.buscar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				LUNES, LUNES.plusDays(1), null);
		return agenda.dias().stream()
				.flatMap(dia -> dia.slots().stream())
				.collect(Collectors.toMap(SlotDisponible::desde, Function.identity(), (a, b) -> a));
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
