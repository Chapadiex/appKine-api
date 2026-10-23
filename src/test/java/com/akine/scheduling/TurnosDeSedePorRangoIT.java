package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.application.TurnosDeSedeView;
import com.akine.scheduling.domain.exception.ConsultorioNoAccesibleException;
import com.akine.scheduling.domain.exception.VentanaDeAgendaDemasiadoAmpliaException;
import com.akine.testsupport.ContadorDeSentencias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turnos de una sede por rango de fechas (DP-45, punto 2), contra MySQL real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import({TestcontainersConfiguration.class, ContadorDeSentencias.Config.class})
class TurnosDeSedePorRangoIT {

	private static final LocalDate LUNES_1 = LocalDate.of(2027, 3, 8);
	private static final LocalDate LUNES_2 = LocalDate.of(2027, 3, 15);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService ciclo;
	@Autowired private RecepcionService recepcion;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("devuelve los turnos del rango por hora, sin cancelados, con paciente y profesional resueltos")
	void lista_el_rango_ordenado_y_sin_cancelados() {
		Fixture f = fixtures.crear(1);
		TurnoView tardio = reservar(f, f.personaA(), LUNES_2, 10);
		TurnoView temprano = reservar(f, f.personaB(), LUNES_1, 9);
		TurnoView cancelado = reservar(f, f.personaC(), LUNES_1, 10);
		ciclo.cancelar(f.actor(), f.consultorioId(), cancelado.id(), "Aviso que no viene", cancelado.version());

		TurnosDeSedeView vista = recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_1, LUNES_2.plusDays(1), null, null, false);

		assertThat(vista.timezone()).isEqualTo(AgendaFixtures.ZONA);
		assertThat(vista.turnos()).extracting(t -> t.turno().id())
				.containsExactly(temprano.id(), tardio.id());
		var primero = vista.turnos().get(0);
		assertThat(primero.turno().personaNombre()).isNotBlank().doesNotContain("no disponible");
		assertThat(primero.turno().ofertaNombre()).isNotBlank();
		assertThat(primero.turno().profesionalId()).isEqualTo(f.profesionalMembershipId());
		assertThat(primero.profesionalNombre()).isNotBlank();

		TurnosDeSedeView conCancelados = recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_1, LUNES_2.plusDays(1), null, null, true);
		assertThat(conCancelados.turnos()).extracting(t -> t.turno().id())
				.containsExactly(temprano.id(), cancelado.id(), tardio.id());
	}

	@Test
	@DisplayName("hasta es exclusivo, filtra por profesional y oferta, y un rango vacio trae la zona")
	void hasta_exclusivo_y_filtros() {
		Fixture f = fixtures.crear(1);
		TurnoView turno = reservar(f, f.personaA(), LUNES_2, 9);

		assertThat(recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_1, LUNES_2, null, null, false).turnos()).isEmpty();
		assertThat(recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_2, LUNES_2.plusDays(1), f.profesionalMembershipId(), f.ofertaId(), false).turnos())
				.extracting(t -> t.turno().id()).containsExactly(turno.id());
		assertThat(recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_2, LUNES_2.plusDays(1), f.profesionalMembershipId() + 1000, null, false).turnos())
				.isEmpty();
		assertThat(recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_2, LUNES_2.plusDays(1), null, f.ofertaId() + 1000, false).turnos())
				.isEmpty();
		assertThat(recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_1, LUNES_1.plusDays(1), null, null, false).timezone())
				.isEqualTo(AgendaFixtures.ZONA);
	}

	@Test
	@DisplayName("validacion 400 (rango invertido o de mas de 42 dias), 404 de otra organizacion y 403 sin contexto de trabajo")
	void validacion_tenant_y_permiso() {
		Fixture f = fixtures.crear(1);
		Fixture ajeno = fixtures.crear(1);

		assertThat(recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_1, LUNES_1.plusDays(42), null, null, false)).isNotNull();
		assertThatThrownBy(() -> recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_1, LUNES_1.plusDays(43), null, null, false))
				.isInstanceOf(VentanaDeAgendaDemasiadoAmpliaException.class);
		assertThatThrownBy(() -> recepcion.delRango(f.actor(), f.consultorioId(),
				LUNES_1, LUNES_1, null, null, false))
				.isInstanceOf(IllegalArgumentException.class);

		// El actor de otra organizacion pidiendo la sede ajena: la sede no existe para el.
		assertThatThrownBy(() -> recepcion.delRango(ajeno.actor(), f.consultorioId(),
				LUNES_1, LUNES_2, null, null, false))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		OperatingActor sinContexto = new OperatingActor(
				f.actor().accountId(), false, null, null);
		assertThatThrownBy(() -> recepcion.delRango(sinContexto, f.consultorioId(),
				LUNES_1, LUNES_2, null, null, false))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("las sentencias no crecen con la cantidad de turnos ni de profesionales")
	void sin_n_mas_uno() {
		Fixture f = fixtures.crear(1);
		reservar(f, f.personaA(), LUNES_1, 9);
		int conUno = ContadorDeSentencias.contar(() -> recepcion.delRango(
				f.actor(), f.consultorioId(), LUNES_1, LUNES_2, null, null, false));

		reservar(f, f.personaB(), LUNES_1, 10);
		for (int i = 0; i < 3; i++) {
			Fixture otro = fixtures.poblar(f.organizationId(), f.consultorioId(),
					f.actor().accountId(), 1, false);
			reservar(otro, otro.personaA(), LUNES_1, 9);
		}
		int conCinco = ContadorDeSentencias.contar(() -> recepcion.delRango(
				f.actor(), f.consultorioId(), LUNES_1, LUNES_2, null, null, false));

		assertThat(conCinco).isEqualTo(conUno);
	}

	private TurnoView reservar(Fixture f, long personaId, LocalDate dia, int hora) {
		Instant inicio = dia.atTime(hora, 0).atZone(ZONA).toInstant();
		return turnoService.reservar(f.actor(), f.consultorioId(), f.ofertaId(),
				new ReservaCommand(personaId, inicio, f.profesionalMembershipId(), null)).turno();
	}
}
