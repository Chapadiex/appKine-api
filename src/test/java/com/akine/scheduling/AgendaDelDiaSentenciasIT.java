package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaUnificadaService;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.testsupport.ContadorDeSentencias;
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
@Import({TestcontainersConfiguration.class, ContadorDeSentencias.Config.class})
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
		return ContadorDeSentencias.contar(() -> lectura.get());
	}

	private static int contarConResultado(int[] cuenta, Supplier<Integer> lectura) {
		return ContadorDeSentencias.contar(cuenta, lectura);
	}

	private static Instant hora(int hora) {
		return LUNES.atTime(hora, 0).atZone(ZONA).toInstant();
	}
}
