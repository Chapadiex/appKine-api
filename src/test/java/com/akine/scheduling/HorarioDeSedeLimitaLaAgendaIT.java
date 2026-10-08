package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.resource.application.CalendarioService;
import com.akine.resource.application.CalendarioView;
import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaService;
import com.akine.scheduling.application.AgendaView.DiaDeAgenda;
import com.akine.scheduling.application.AgendaView.SlotDisponible;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;
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
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A-8b (DP-19), contra MySQL real: el horario general de la sede limita la agenda y la reserva, y
 * una sede sin horario cargado sigue exactamente igual que antes.
 *
 * <p>El profesional de {@link AgendaFixtures} atiende los lunes de 09:00 a 13:00 con una oferta de
 * 60 minutos: cuatro slots. El horario de la sede se carga por el servicio real del calendario
 * —el mismo {@code PUT} que usa la pantalla—, no con un INSERT a mano.
 *
 * <p>El lunes es el proximo a partir de la semana que viene: el impacto del {@code PUT} mira noventa
 * dias hacia adelante, y una fecha fija lejana quedaria fuera del horizonte.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HorarioDeSedeLimitaLaAgendaIT {

	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);
	private static final LocalDate LUNES = LocalDate.now(ZONA).plusDays(7)
			.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

	@Autowired private AgendaService agendaService;
	@Autowired private TurnoService turnoService;
	@Autowired private CalendarioService calendarioService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("Sin horario de sede cargado la agenda ofrece lo mismo que antes y la reserva acepta las 09:00")
	void sin_horario_no_cambia_nada() {
		Fixture fixture = fixtures.crear(1);

		assertThat(inicios(elLunes(fixture))).containsExactly(hora(9), hora(10), hora(11), hora(12));
		assertThat(reservar(fixture, hora(9)).inicio()).isEqualTo(hora(9));
	}

	@Test
	@DisplayName("Con horario de sede, la agenda se recorta y la reserva fuera de horario da slot-no-disponible")
	void el_horario_recorta_agenda_y_reserva() {
		Fixture fixture = fixtures.crear(1);
		fijarHorario(fixture, List.of(franja(DayOfWeek.MONDAY, 10, 12)));

		DiaDeAgenda lunes = elLunes(fixture);
		assertThat(inicios(lunes))
				.as("09:00 y 12:00 quedan fuera del horario de la sede aunque el profesional atienda")
				.containsExactly(hora(10), hora(11));

		assertThatThrownBy(() -> reservar(fixture, hora(9)))
				.as("el mismo 409 que un slot no disponible")
				.isInstanceOf(SlotNoDisponibleException.class);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM turno WHERE organization_id = ? AND oferta_id = ?",
				Long.class, fixture.organizationId(), fixture.ofertaId()))
				.as("y no quedo nada escrito")
				.isZero();

		assertThat(reservar(fixture, hora(10)).inicio()).isEqualTo(hora(10));
	}

	@Test
	@DisplayName("Un dia en que la sede no abre declara FUERA_DE_HORARIO_SEDE, y quitar el horario lo devuelve")
	void dia_sin_franja_de_la_sede() {
		Fixture fixture = fixtures.crear(1);
		fijarHorario(fixture, List.of(franja(DayOfWeek.TUESDAY, 9, 18)));

		DiaDeAgenda lunes = elLunes(fixture);
		assertThat(lunes.slots()).isEmpty();
		assertThat(lunes.motivoSinSlots()).isEqualTo("FUERA_DE_HORARIO_SEDE");

		fijarHorario(fixture, List.of());
		assertThat(inicios(elLunes(fixture)))
				.as("una lista vacia quita el limite")
				.hasSize(4);
	}

	@Test
	@DisplayName("Cambiar el horario informa los turnos que quedan fuera y NO los cancela")
	void el_put_informa_el_impacto_sin_cancelar() {
		Fixture fixture = fixtures.crear(1);
		TurnoView temprano = reservar(fixture, hora(9));
		reservar(fixture, hora(11));

		CalendarioView vista = fijarHorario(fixture, List.of(franja(DayOfWeek.MONDAY, 10, 13)));

		assertThat(vista.impactoDelHorario().turnosAfectados()).isEqualTo(1L);
		assertThat(vista.impactoDelHorario().turnos())
				.singleElement()
				.satisfies(t -> assertThat(t.turnoId()).isEqualTo(temprano.id()));
		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?",
				String.class, temprano.id()))
				.as("el turno fuera de horario sigue vivo: se informa, no se cancela")
				.isEqualTo(temprano.estado());
	}

	// =================================================================================

	private CalendarioView fijarHorario(Fixture fixture, List<Franja> horario) {
		com.akine.resource.application.OperatingActor actor =
				new com.akine.resource.application.OperatingActor(
						fixture.actor().accountId(), false,
						fixture.organizationId(), fixture.consultorioId());
		return calendarioService.actualizar(actor, fixture.consultorioId(), null, null, horario);
	}

	private DiaDeAgenda elLunes(Fixture fixture) {
		return agendaService.buscar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				LUNES, LUNES.plusDays(1), null).dias().get(0);
	}

	private TurnoView reservar(Fixture fixture, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaA(), inicio, fixture.profesionalMembershipId(),
						null))
				.turno();
	}

	private static List<Instant> inicios(DiaDeAgenda dia) {
		return dia.slots().stream().map(SlotDisponible::desde).toList();
	}

	private static Instant hora(int horaLocal) {
		return LUNES.atTime(horaLocal, 0).atZone(ZONA).toInstant();
	}

	private static Franja franja(DayOfWeek dia, int desde, int hasta) {
		return new Franja(dia.getValue(), LocalTime.of(desde, 0), LocalTime.of(hasta, 0));
	}
}
