package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.akine.scheduling.AgendaFixtures.causaEs;
import static com.akine.scheduling.AgendaFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El criterio de aceptacion de AKINE-05.02: <b>una sola reserva gana</b>.
 *
 * <h2>Por que esto no puede ser un test unitario</h2>
 *
 * <p>Un test con mocks verificaria el orden de las llamadas, no que dos transacciones se esperen de
 * verdad. Lo que decide la correctitud aca es el {@code FOR UPDATE} sobre {@code agenda_sede} y el
 * aislamiento de InnoDB, y eso solo lo puede contestar MySQL real: <b>hasta el 31/08/2026 este test
 * no se podia correr en esta maquina</b> porque el motor de Docker no arrancaba.
 *
 * <h2>Lo que ningun unique de la base puede garantizar</h2>
 *
 * <p>El solapamiento. Un unique compara igualdad y dos turnos se pisan cuando sus INTERVALOS se
 * cruzan; un turno de 09:00 a 10:00 y otro de 09:30 a 10:00 no comparten un solo valor de columna.
 * MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL— asi que la regla la hace cumplir la
 * validacion de {@code TurnoService} bajo el lock, y este test es la unica prueba de que funciona.
 *
 * <p>El fixture y el ejecutor concurrente viven en {@link AgendaFixtures}, compartidos con
 * {@code TurnoCicloConcurrenteIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class TurnoConcurrenteIT {

	/**
	 * Un lunes fijo y lejano en el futuro, para que el bloque de disponibilidad lo cubra siempre.
	 *
	 * <p>Fecha fija y no "el proximo lunes": un escenario que se mueve con el reloj de pared falla
	 * el dia que el calendario lo alcanza, sin que haya nada roto. Es la misma leccion que el
	 * fixture de {@code DisponibilidadIT} documenta para {@code valid_from}.
	 */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 1);

	@Autowired private TurnoService turnoService;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("dos reservas concurrentes del mismo slot individual: una entra y la otra recibe 409")
	void dos_reservas_del_mismo_slot_no_pasan_las_dos() {
		Fixture fixture = fixtures.crear(1);

		Instant inicio = LUNES.atTime(9, 0).atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();

		// Dos PACIENTES distintos, el mismo hueco. Con el mismo paciente el desenlace tambien
		// seria uno solo, pero por otra razon, y el test no distinguiria cual de las dos reglas
		// actuo.
		Callable<TurnoView> primera = () -> reservar(fixture, fixture.personaA(), inicio);
		Callable<TurnoView> segunda = () -> reservar(fixture, fixture.personaB(), inicio);

		List<Desenlace<TurnoView>> desenlaces = enParalelo(List.of(primera, segunda));

		long exitos = desenlaces.stream().filter(d -> !d.fallo()).count();
		long rechazos = desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), SlotCompletoException.class)
						|| causaEs(d.error(), RecursoOcupadoException.class))
				.count();

		assertThat(exitos)
				.as("exactamente una reserva entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(rechazos)
				.as("la otra recibe un 409 de agenda, no un error generico. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(contarTurnosVivos(fixture))
				.as("y en la base queda UNA sola fila viva para ese slot")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("un slot grupal admite hasta su capacidad y rechaza el que sobra")
	void el_grupal_admite_hasta_su_capacidad() {
		// Capacidad 2 y tres reservas a la vez. Prueba las dos mitades de la misma regla: que el
		// control de solapamiento NO rechace a los companeros de grupo —comparten profesional,
		// oferta y hora a proposito— y que el cupo si corte al tercero.
		Fixture fixture = fixtures.crear(2);
		Instant inicio = LUNES.atTime(9, 0).atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();

		List<Desenlace<TurnoView>> desenlaces = enParalelo(List.of(
				() -> reservar(fixture, fixture.personaA(), inicio),
				() -> reservar(fixture, fixture.personaB(), inicio),
				() -> reservar(fixture, fixture.personaC(), inicio)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("entran dos, que es la capacidad. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), SlotCompletoException.class))
				.count())
				.as("el tercero recibe slot-completo. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(contarTurnosVivos(fixture)).isEqualTo(2);
	}

	@Test
	@DisplayName("la misma clave de idempotencia no crea dos turnos, ni siquiera en paralelo")
	void la_idempotencia_aguanta_el_doble_click() {
		// El doble click real: el mismo cliente manda dos veces el mismo pedido con la misma
		// clave. Las dos peticiones tienen que devolver EL MISMO turno, no dos.
		Fixture fixture = fixtures.crear(1);
		Instant inicio = LUNES.atTime(9, 0).atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();
		String clave = UUID.randomUUID().toString();

		Callable<TurnoView> click = () -> turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaA(), inicio, fixture.profesionalMembershipId(), clave));

		List<Desenlace<TurnoView>> desenlaces = enParalelo(List.of(click, click));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("las dos peticiones responden bien. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
		assertThat(desenlaces.stream().map(d -> d.valor().id()).distinct())
				.as("y las dos devuelven el mismo turno")
				.hasSize(1);
		assertThat(contarTurnosVivos(fixture))
				.as("en la base hay una sola fila")
				.isEqualTo(1);
	}

	private TurnoView reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null));
	}

	/**
	 * Cuenta los turnos vivos de la oferta del fixture.
	 *
	 * <p><b>Sin filtrar por {@code inicio}, y no es descuido.</b> Enlazar un {@code Instant} como
	 * parametro de JDBC lo convierte con la zona de la sesion, que no es necesariamente la misma
	 * conversion que hace Hibernate al guardar: la consulta devolvia cero contra filas que existian.
	 * Cada test crea su propia organizacion y su propia oferta, asi que el predicado por hora no
	 * agrega precision — solo una forma de que el test mienta.
	 */
	private long contarTurnosVivos(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno
				 WHERE organization_id = ? AND oferta_id = ? AND deleted_at IS NULL
				""", Long.class, fixture.organizationId(), fixture.ofertaId());
	}
}
