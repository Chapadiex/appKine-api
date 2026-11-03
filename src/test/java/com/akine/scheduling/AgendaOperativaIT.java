package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.resource.application.ExcepcionAltaCommand;
import com.akine.resource.application.ExcepcionService;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.EventoDeTurnoView;
import com.akine.scheduling.application.ReprogramacionCommand;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TrasladoCommand;
import com.akine.scheduling.application.TrasladoDeTurnoService;
import com.akine.scheduling.application.TrasladoView;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La agenda operativa contra MySQL real: el cambio de sede atomico, la reprogramacion que guarda el
 * profesional de antes, y que {@code V131}/{@code V132} ejecutan y dejan el CHECK de motivos completo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AgendaOperativaIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private TurnoService turnos;
	@Autowired private CicloDeTurnoService ciclo;
	@Autowired private TrasladoDeTurnoService traslados;
	@Autowired private ExcepcionService excepciones;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;
	private Fixture sedeA;
	private Fixture sedeB;

	@BeforeEach
	void setUp() {
		fixtures = new AgendaFixtures(jdbc);
		sedeA = fixtures.crear(1);
		sedeB = fixtures.agregarSede(sedeA);
	}

	@Test
	@DisplayName("cambio de sede: el original queda cancelado por la institucion y el nuevo apunta a el, con las observaciones")
	void traslado_cancela_y_reserva_en_una_transaccion() {
		TurnoView original = turnos.reservar(sedeA.actor(), sedeA.consultorioId(), sedeA.ofertaId(),
				new ReservaCommand(sedeA.personaA(), hora(9), sedeA.profesionalMembershipId(), null, null,
						null, null, null, null, null, "Trae los estudios")).turno();
		assertThat(original.observaciones()).isEqualTo("Trae los estudios");

		TrasladoView traslado = traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), original.id(),
				new TrasladoCommand(sedeB.consultorioId(), sedeB.ofertaId(), hora(10),
						sedeB.profesionalMembershipId(), null, "Se mudo cerca de la otra sede", original.version()));

		assertThat(traslado.original().estado()).isEqualTo("CANCELADO");
		assertThat(traslado.nuevo().consultorioId()).isEqualTo(sedeB.consultorioId());
		assertThat(traslado.nuevo().turnoOrigenId()).isEqualTo(original.id());
		assertThat(traslado.nuevo().observaciones()).isEqualTo("Trae los estudios");

		Map<String, Object> filaOriginal = jdbc.queryForMap(
				"SELECT estado, origen_cancelacion, motivo_cancelacion FROM turno WHERE id = ?", original.id());
		assertThat(filaOriginal.get("estado")).isEqualTo("CANCELADO");
		assertThat(filaOriginal.get("origen_cancelacion")).isEqualTo("INSTITUCION");
		assertThat((String) filaOriginal.get("motivo_cancelacion")).startsWith("Cambio de sede a ");
		Map<String, Object> filaNueva = jdbc.queryForMap(
				"SELECT consultorio_id, origen, turno_origen_id, persona_id FROM turno WHERE id = ?",
				traslado.nuevo().id());
		assertThat(((Number) filaNueva.get("consultorio_id")).longValue()).isEqualTo(sedeB.consultorioId());
		assertThat(filaNueva.get("origen")).isEqualTo("TURNO_ANTERIOR");
		assertThat(((Number) filaNueva.get("turno_origen_id")).longValue()).isEqualTo(original.id());
		assertThat(((Number) filaNueva.get("persona_id")).longValue()).isEqualTo(sedeA.personaA());
		assertThat(jdbc.queryForList("SELECT event_type FROM audit_event WHERE entity_id IN (?, ?) "
						+ "AND entity_type = 'Turno'", String.class, original.id(), traslado.nuevo().id()))
				.contains("TURNO_RESERVADO", "TURNO_CANCELADO", "TURNO_TRASLADADO");
	}

	@Test
	@DisplayName("cambio de sede sin lugar en el destino: 409 y el original sigue reservado, sin turno nuevo")
	void traslado_sin_lugar_no_toca_el_original() {
		TurnoView original = reservar(sedeA, sedeA.personaA(), hora(11));
		reservar(sedeB, sedeB.personaB(), hora(11));

		assertThatThrownBy(() -> traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), original.id(),
				new TrasladoCommand(sedeB.consultorioId(), sedeB.ofertaId(), hora(11),
						sedeB.profesionalMembershipId(), null, "Cambio", original.version())))
				.isInstanceOfAny(SlotCompletoException.class, RecursoOcupadoException.class);

		assertThat(jdbc.queryForObject("SELECT estado FROM turno WHERE id = ?", String.class, original.id()))
				.isEqualTo("RESERVADO");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE turno_origen_id = ?", Long.class,
				original.id())).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno_evento WHERE turno_id = ? AND tipo = 'CANCELACION'",
				Long.class, original.id())).isZero();
	}

	@Test
	@DisplayName("reprogramar a otro profesional guarda en el historial el profesional de antes y el de despues")
	void reprogramacion_guarda_profesional_anterior() {
		long otro = fixtures.agregarProfesional(sedeA);
		TurnoView turno = reservar(sedeA, sedeA.personaA(), hora(9));

		ciclo.reprogramar(sedeA.actor(), sedeA.consultorioId(), turno.id(),
				new ReprogramacionCommand(hora(10), otro, "Licencia", turno.version()));

		List<EventoDeTurnoView> historial = ciclo.historial(sedeA.actor(), sedeA.consultorioId(), turno.id());
		EventoDeTurnoView reprogramacion = historial.get(historial.size() - 1);
		assertThat(reprogramacion.tipo()).isEqualTo("REPROGRAMACION");
		assertThat(reprogramacion.profesionalAnteriorId()).isEqualTo(sedeA.profesionalMembershipId());
		assertThat(reprogramacion.profesionalNuevoId()).isEqualTo(otro);
	}

	@Test
	@DisplayName("V132: VACACIONES entra, CAPACITACION sigue entrando, y un motivo inventado no")
	void check_de_motivos_completo() {
		var vacaciones = excepciones.crear(actorDeResource(), sedeA.consultorioId(), new ExcepcionAltaCommand(
				sedeA.profesionalMembershipId(), TipoExcepcion.CIERRE, MotivoExcepcion.VACACIONES,
				LUNES.plusWeeks(4), LUNES.plusWeeks(5), null, null, null, null, true));
		excepciones.crear(actorDeResource(), sedeA.consultorioId(), new ExcepcionAltaCommand(
				sedeA.profesionalMembershipId(), TipoExcepcion.CIERRE, MotivoExcepcion.CAPACITACION,
				LUNES.plusWeeks(6), LUNES.plusWeeks(7), null, null, null, null, true));

		assertThat(vacaciones.createdByCuentaId()).isEqualTo(sedeA.actor().accountId());
		assertThat(jdbc.queryForObject("SELECT created_by_cuenta_id FROM disponibilidad_excepcion WHERE id = ?",
				Long.class, vacaciones.id())).isEqualTo(sedeA.actor().accountId());
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE disponibilidad_excepcion SET motivo = 'INVENTADO' WHERE id = ?", vacaciones.id()))
				.hasMessageContaining("ck_disponibilidad_excepcion_motivo");
	}

	private TurnoView reservar(Fixture sede, long personaId, Instant inicio) {
		return turnos.reservar(sede.actor(), sede.consultorioId(), sede.ofertaId(),
				new ReservaCommand(personaId, inicio, sede.profesionalMembershipId(), null)).turno();
	}

	private com.akine.resource.application.OperatingActor actorDeResource() {
		return new com.akine.resource.application.OperatingActor(
				sedeA.actor().accountId(), false, sedeA.organizationId(), sedeA.consultorioId());
	}

	private static Instant hora(int horaLocal) {
		return LUNES.atTime(horaLocal, 0).atZone(ZONA).toInstant();
	}
}
