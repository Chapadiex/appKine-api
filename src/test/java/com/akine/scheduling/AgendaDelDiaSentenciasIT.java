package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaUnificadaService;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cuantas sentencias cuesta la agenda del dia, contadas en la conexion JDBC (docs/carga.md §6.4).
 *
 * <p>El N+1 que G-10 midio no crece con los turnos sino con lo que se resuelve por fila. La agenda
 * unificada ya pedia las ofertas en una consulta (#91); la recepcion del dia las cacheaba por id,
 * que es una consulta <b>por oferta distinta</b> para el nombre y otra para el precio del prepago.
 * Una sede con varios profesionales y una oferta por cada uno paga dos sentencias por profesional.
 *
 * <p>El contador envuelve el {@code DataSource} y cuenta, <b>solo en el hilo del test</b>, cada
 * {@code prepareStatement}/{@code createStatement}: los jobs de fondo (outbox) usan la misma base y
 * no deben ensuciar el numero. Se compara el mismo dia con una oferta y con seis: si la cuenta
 * crece, hay una consulta por oferta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import({TestcontainersConfiguration.class, AgendaDelDiaSentenciasIT.ContadorConfig.class})
class AgendaDelDiaSentenciasIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);
	private static final int OFERTAS_EXTRA = 5;

	@Autowired private TurnoService turnoService;
	@Autowired private RecepcionService recepcion;
	@Autowired private AgendaUnificadaService agenda;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("la recepcion y la agenda del dia hacen las mismas sentencias con una oferta que con seis")
	void las_sentencias_no_crecen_con_las_ofertas_del_dia() {
		Fixture base = fixtures.crear(1);
		reservar(base);

		int recepcionConUna = contar(() -> recepcion.delDia(base.actor(), base.consultorioId(), LUNES));
		int agendaConUna = contar(() -> agenda.delDia(base.actor(), base.consultorioId(), LUNES));

		// Cinco profesionales mas en la misma sede, cada uno con su oferta y su turno del lunes.
		for (int i = 0; i < OFERTAS_EXTRA; i++) {
			reservar(fixtures.poblar(base.organizationId(), base.consultorioId(),
					base.actor().accountId(), 1, false));
		}

		int[] recepcionConSeis = new int[1];
		int recepcionTurnos = contarConResultado(recepcionConSeis,
				() -> recepcion.delDia(base.actor(), base.consultorioId(), LUNES).turnos().size());
		int[] agendaConSeis = new int[1];
		int agendaEventos = contarConResultado(agendaConSeis,
				() -> agenda.delDia(base.actor(), base.consultorioId(), LUNES).eventos().size());

		System.out.printf("[sentencias] recepcion del dia: 1 oferta = %d, 6 ofertas = %d%n",
				recepcionConUna, recepcionConSeis[0]);
		System.out.printf("[sentencias] agenda unificada:  1 oferta = %d, 6 ofertas = %d%n",
				agendaConUna, agendaConSeis[0]);

		assertThat(recepcionTurnos).isEqualTo(1 + OFERTAS_EXTRA);
		assertThat(agendaEventos).isEqualTo(1 + OFERTAS_EXTRA);
		assertThat(recepcionConSeis[0])
				.as("la recepcion del dia no hace una consulta por oferta distinta")
				.isEqualTo(recepcionConUna);
		assertThat(agendaConSeis[0])
				.as("la agenda unificada no hace una consulta por oferta distinta")
				.isEqualTo(agendaConUna);
	}

	private void reservar(Fixture fixture) {
		turnoService.reservar(fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaA(), hora(9), fixture.profesionalMembershipId(), null));
	}

	private static int contar(Supplier<?> lectura) {
		int[] cuenta = new int[1];
		contarConResultado(cuenta, () -> {
			lectura.get();
			return 0;
		});
		return cuenta[0];
	}

	private static int contarConResultado(int[] cuenta, Supplier<Integer> lectura) {
		ContadorDeSentencias.EN_ESTE_HILO.set(new int[1]);
		try {
			int resultado = lectura.get();
			cuenta[0] = ContadorDeSentencias.EN_ESTE_HILO.get()[0];
			return resultado;
		} finally {
			ContadorDeSentencias.EN_ESTE_HILO.remove();
		}
	}

	private static Instant hora(int hora) {
		return LUNES.atTime(hora, 0).atZone(ZONA).toInstant();
	}

	/** Cuenta las sentencias que prepara el hilo que lo activo, y ninguna otra. */
	static final class ContadorDeSentencias extends DelegatingDataSource {

		static final ThreadLocal<int[]> EN_ESTE_HILO = new ThreadLocal<>();

		private static final Set<String> SENTENCIAS = Set.of("prepareStatement", "prepareCall", "createStatement");

		ContadorDeSentencias(DataSource destino) {
			super(destino);
		}

		@Override
		public Connection getConnection() throws SQLException {
			return contando(super.getConnection());
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return contando(super.getConnection(username, password));
		}

		private static Connection contando(Connection real) {
			return (Connection) Proxy.newProxyInstance(
					ContadorDeSentencias.class.getClassLoader(),
					new Class<?>[] {Connection.class},
					(proxy, metodo, args) -> {
						int[] cuenta = EN_ESTE_HILO.get();
						if (cuenta != null && SENTENCIAS.contains(metodo.getName())) {
							cuenta[0]++;
						}
						try {
							return metodo.invoke(real, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ContadorConfig {

		@Bean
		static BeanPostProcessor contadorDeSentencias() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					return bean instanceof DataSource ds && !(bean instanceof ContadorDeSentencias)
							? new ContadorDeSentencias(ds)
							: bean;
				}
			};
		}
	}
}
