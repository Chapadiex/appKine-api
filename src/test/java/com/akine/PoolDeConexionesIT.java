package com.akine;

import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroService;
import com.akine.billing.domain.MedioDePago;
import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.CannotCreateTransactionException;

import java.math.BigDecimal;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import static com.akine.scheduling.AgendaFixtures.causaEs;
import static com.akine.scheduling.AgendaFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El pool de conexiones no se traba con escrituras simultaneas (G-10, docs/carga.md §6.1).
 *
 * <h2>Lo que reproduce</h2>
 *
 * <p>Hasta el 11/10/2026 la reserva y el cobro abrian la transaccion de la fila-lock
 * ({@code agenda_sede}, {@code comprobante_numerador}) como {@code REQUIRES_NEW} <b>desde
 * adentro</b> de su propia transaccion: cada pedido retenia una conexion y pedia otra. Con un pool
 * de N conexiones, N escrituras simultaneas tomaban todas y se quedaban esperando la segunda. La
 * prueba de carga lo vio con el pool de 10 y menos de 100 usuarios: el backend se trababa a los 20
 * segundos.
 *
 * <p>Aca el pool es de <b>2</b> y la espera por una conexion de <b>3 segundos</b>, y se lanzan
 * {@value #PEDIDOS} escrituras a la vez con una barrera: con el codigo viejo, las dos primeras
 * toman las dos conexiones, piden una tercera que no existe y mueren por
 * {@code CannotCreateTransactionException}; el resto hereda la cola. Con la fila asegurada ANTES
 * de abrir la transaccion de negocio cada pedido usa una conexion por vez, y lo peor que puede
 * pasar es esperar su turno.
 *
 * <p>Se corre dos veces por camino: con la fila-lock todavia sin crear (la primera escritura de una
 * sede nueva) y con la fila ya creada, que es el caso del 99,9 % de los pedidos.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.datasource.hikari.maximum-pool-size=2",
				"spring.datasource.hikari.minimum-idle=2",
				"spring.datasource.hikari.connection-timeout=3000"
		})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PoolDeConexionesIT {

	private static final int PEDIDOS = 8;

	/** Lunes fijo y lejano: la disponibilidad del fixture cubre los lunes de 09:00 a 13:00. */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 1);

	private static final BigDecimal IMPORTE = new BigDecimal("1000.00");

	@Autowired private TurnoService turnoService;
	@Autowired private CobroService cobroService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() throws Exception {
		fixtures = new AgendaFixtures(jdbc);
		assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize())
				.as("el escenario solo prueba algo con un pool mas chico que los pedidos")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("reservas simultaneas en una sede nueva y en una con agenda: ninguna se queda sin conexion")
	void las_reservas_no_traban_el_pool() {
		// Capacidad = PEDIDOS: todas caben en el mismo horario y ninguna choca por agenda. Lo unico
		// que puede hacerlas fallar es la conexion.
		Fixture fixture = fixtures.crear(PEDIDOS);
		Instant nueve = LUNES.atTime(9, 0).atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();
		Instant diez = LUNES.atTime(10, 0).atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();

		// Primera ronda: agenda_sede todavia no existe para esta sede.
		exigirSinTraba(enParalelo(reservas(fixture, nueve)), "sin la fila de agenda_sede");
		// Segunda ronda: la fila ya existe y solo se bloquea.
		exigirSinTraba(enParalelo(reservas(fixture, diez)), "con la fila de agenda_sede creada");
	}

	@Test
	@DisplayName("cobros simultaneos en una sede nueva y en una con numerador: ninguno se queda sin conexion")
	void los_cobros_no_traban_el_pool() {
		Fixture fixture = fixtures.crear(1);

		exigirSinTraba(enParalelo(cobros(fixture)), "sin la fila de comprobante_numerador");
		exigirSinTraba(enParalelo(cobros(fixture)), "con la fila de comprobante_numerador creada");

		// Y la numeracion sigue siendo correlativa y sin huecos: el arreglo no la toco.
		List<Integer> numeros = jdbc.queryForList("""
				SELECT comprobante_numero FROM cobro
				 WHERE organization_id = ? AND consultorio_id = ?
				 ORDER BY comprobante_numero
				""", Integer.class, fixture.organizationId(), fixture.consultorioId());
		assertThat(numeros).containsExactlyElementsOf(
				IntStream.rangeClosed(1, 2 * PEDIDOS).boxed().toList());
	}

	private List<Callable<Object>> reservas(Fixture fixture, Instant inicio) {
		// Tres pacientes para ocho pedidos: los que repiten paciente pueden recibir un 409 de
		// agenda, que es negocio y no traba. Lo que se mira es que nadie muera por la conexion.
		long[] personas = {fixture.personaA(), fixture.personaB(), fixture.personaC()};
		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			long persona = personas[i % personas.length];
			tareas.add(() -> turnoService.reservar(
					fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
					new ReservaCommand(persona, inicio, fixture.profesionalMembershipId(), null)));
		}
		return tareas;
	}

	private List<Callable<Object>> cobros(Fixture fixture) {
		var actor = new com.akine.billing.application.OperatingActor(
				fixture.actor().accountId(), false, fixture.organizationId(), fixture.consultorioId());
		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			// Anticipo por transferencia: no necesita deuda ni caja abierta, y pide comprobante.
			tareas.add(() -> cobroService.registrar(actor, fixture.consultorioId(), new CobroCommand(
					fixture.personaA(), IMPORTE,
					List.of(new CobroCommand.MedioPedido(MedioDePago.TRANSFERENCIA, IMPORTE, null)),
					List.of(), null, IMPORTE, "ARS")));
		}
		return tareas;
	}

	private static void exigirSinTraba(List<Desenlace<Object>> desenlaces, String escenario) {
		List<Desenlace<Object>> sinConexion = desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), CannotCreateTransactionException.class)
						|| causaEs(d.error(), SQLTransientConnectionException.class))
				.toList();
		assertThat(sinConexion)
				.as("%s: ningun pedido puede quedarse sin conexion. Desenlaces: %s", escenario, desenlaces)
				.isEmpty();
		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("%s: y alguno tiene que haber escrito. Desenlaces: %s", escenario, desenlaces)
				.isPositive();
	}
}
