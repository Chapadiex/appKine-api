package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.CicloDeRecepcionService.ResultadoDeLlegada;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La recepcion bajo concurrencia real (AKINE E-4). Ver el §8 y el caso 8 del design challenge de
 * {@code docs/diseno/AKINE-E-4-recepcion.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class RecepcionConcurrenteIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 15);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private CicloDeRecepcionService recepcion;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	/**
	 * El doble click del mostrador, de verdad en paralelo. Los dos tienen que recibir la MISMA
	 * recepcion con exito —el {@code FOR UPDATE} del turno los serializa y el segundo encuentra la
	 * del primero—, no uno 200 y otro 409 por el unique.
	 */
	@Test
	@DisplayName("dos check-in simultaneos dan los dos la misma recepcion, y queda una sola fila")
	void dos_check_in_simultaneos() {
		Fixture fixture = fixtures.crear(1);
		TurnoView turno = reservar(fixture, hora(9));

		List<Desenlace<ResultadoDeLlegada>> desenlaces = AgendaFixtures.enParalelo(List.of(
				() -> recepcion.registrarLlegada(fixture.actor(), fixture.consultorioId(), turno.id()),
				() -> recepcion.registrarLlegada(fixture.actor(), fixture.consultorioId(), turno.id())));

		assertThat(desenlaces).as("ninguno falla: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(desenlaces).extracting(d -> d.valor().recepcion().id()).containsOnly(
				desenlaces.get(0).valor().recepcion().id());
		assertThat(desenlaces).extracting(d -> d.valor().creada())
				.as("uno la creo y el otro la encontro")
				.containsExactlyInAnyOrder(true, false);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recepcion WHERE turno_id = ?",
				Integer.class, turno.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recepcion_evento WHERE turno_id = ?",
				Integer.class, turno.id())).isEqualTo(1);
	}

	/**
	 * El caso que rompe el diseno: el check-in contra la cancelacion del mismo turno.
	 *
	 * <p>Sin la version forzada, la cancelacion lee "sin recepcion", el check-in inserta una y el
	 * turno queda CANCELADO con alguien "en la sala". Se corre varias rondas porque el orden de
	 * llegada cambia de una a otra; lo que se afirma es el invariante en todas: <b>nunca un turno
	 * cancelado con una recepcion abierta</b>, y que el perdedor recibe un rechazo que lo explica.
	 */
	@Test
	@DisplayName("check-in contra cancelacion: nunca queda un turno cancelado con la recepcion abierta")
	void check_in_contra_cancelacion() {
		Fixture fixture = fixtures.crear(1);

		for (int ronda = 0; ronda < 4; ronda++) {
			TurnoView turno = reservar(fixture, hora(9 + ronda));
			long version = turno.version();

			List<Callable<Object>> tareas = List.of(
					() -> recepcion.registrarLlegada(fixture.actor(), fixture.consultorioId(), turno.id()),
					() -> cicloService.cancelar(fixture.actor(), fixture.consultorioId(), turno.id(),
							"El profesional avisa que no viene", version));
			List<Desenlace<Object>> desenlaces = AgendaFixtures.enParalelo(tareas);

			Map<String, Object> filaTurno = jdbc.queryForMap(
					"SELECT estado FROM turno WHERE id = ?", turno.id());
			List<String> abiertas = jdbc.queryForList("""
					SELECT estado FROM recepcion
					 WHERE turno_id = ? AND estado NOT IN ('ANULADA', 'CERRADA')
					""", String.class, turno.id());

			if ("CANCELADO".equals(filaTurno.get("estado"))) {
				assertThat(abiertas)
						.as("ronda %d: turno cancelado con recepcion abierta: %s", ronda, desenlaces)
						.isEmpty();
			}
			for (Desenlace<Object> desenlace : desenlaces) {
				if (desenlace.fallo()) {
					assertThat(AgendaFixtures.causaEs(desenlace.error(), OptimisticLockingFailureException.class)
							|| AgendaFixtures.causaEs(desenlace.error(), TransicionDeTurnoNoPermitidaException.class))
							.as("ronda %d: el perdedor recibe 409, no otra cosa: %s", ronda, desenlace)
							.isTrue();
				}
			}
			assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
					.as("ronda %d: gana exactamente una de las dos: %s", ronda, desenlaces)
					.isEqualTo(1);
		}
	}

	private TurnoView reservar(Fixture fixture, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaA(), inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}

	private static Instant hora(int hora) {
		return LUNES.atTime(hora, 0).atZone(ZONA).toInstant();
	}
}
