package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.resource.application.BloqueAltaCommand;
import com.akine.resource.application.BloqueEdicionCommand;
import com.akine.resource.application.BloqueView;
import com.akine.resource.application.DisponibilidadService;
import com.akine.resource.application.ExcepcionAltaCommand;
import com.akine.resource.application.ExcepcionService;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaAjenaException;
import com.akine.scheduling.application.CambioDeAgendaView;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.EventoDeTurnoView;
import com.akine.scheduling.application.FiltroDeHistorialDeAgenda;
import com.akine.scheduling.application.HistorialDeAgendaService;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ReprogramacionCommand;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TipoDeCambioDeAgenda;
import com.akine.scheduling.application.TrasladoCommand;
import com.akine.scheduling.application.TrasladoDeTurnoService;
import com.akine.scheduling.application.TrasladoView;
import com.akine.scheduling.application.TurnoDelDiaView;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El historial de agenda de una persona que atiende en dos sedes, contra MySQL real: las columnas
 * generadas de V134 encuentran los eventos de horario, excepcion y turno de sus dos memberships, el
 * filtro por tipo y el paginado funcionan, y el turno trasladado trae el indicador "reprogramado" con la
 * historia de su origen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HistorialDeAgendaIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private TurnoService turnos;
	@Autowired private CicloDeTurnoService ciclo;
	@Autowired private TrasladoDeTurnoService traslados;
	@Autowired private RecepcionService recepcion;
	@Autowired private DisponibilidadService disponibilidad;
	@Autowired private ExcepcionService excepciones;
	@Autowired private HistorialDeAgendaService historial;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("horario, excepcion, reserva, reprogramacion y traslado de la misma persona en dos sedes")
	void historial_de_la_persona_en_dos_sedes() {
		AgendaFixtures fixtures = new AgendaFixtures(jdbc);
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.otraSedeDelMismoProfesional(sedeA, true);
		long pro = sedeA.profesionalMembershipId();
		var admin = new com.akine.resource.application.OperatingActor(
				sedeA.actor().accountId(), false, sedeA.organizationId(), sedeA.consultorioId(), "ORG_ADMIN");

		// Horario habitual: alta del martes y edicion de 09 a 10.
		BloqueView martes = disponibilidad.crear(admin, sedeA.consultorioId(), pro, new BloqueAltaCommand(
				DayOfWeek.TUESDAY.getValue(), LocalTime.of(9, 0), LocalTime.of(13, 0), null, null));
		disponibilidad.editar(admin, sedeA.consultorioId(), pro, martes.id(), new BloqueEdicionCommand(
				null, LocalTime.of(10, 0), null, null, null, false, martes.version()));
		// Licencia.
		excepciones.crear(admin, sedeA.consultorioId(), new ExcepcionAltaCommand(pro, TipoExcepcion.CIERRE,
				MotivoExcepcion.LICENCIA, LUNES.plusWeeks(4), LUNES.plusWeeks(5), null, null, null, null, true));

		// Reserva a las 09, reprogramacion a las 10 y cambio a la otra sede a las 11, con la misma persona.
		TurnoView turno = turnos.reservar(sedeA.actor(), sedeA.consultorioId(), sedeA.ofertaId(),
				new ReservaCommand(sedeA.personaA(), hora(9), pro, null)).turno();
		TurnoView movido = ciclo.reprogramar(sedeA.actor(), sedeA.consultorioId(), turno.id(),
				new ReprogramacionCommand(hora(10), null, "Pidio mas tarde", turno.version()));
		TrasladoView traslado = traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), turno.id(),
				new TrasladoCommand(sedeB.consultorioId(), sedeB.ofertaId(), hora(11),
						sedeB.profesionalMembershipId(), null, "Se mudo", movido.version()));

		OperatingActor lector = new OperatingActor(
				sedeA.actor().accountId(), false, sedeA.organizationId(), sedeA.consultorioId(), "ORG_ADMIN");
		PageRequest tres = PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")));

		// Pedido por la membership de la OTRA sede: igual trae todo lo de la persona.
		Page<CambioDeAgendaView> primera = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(sedeB.profesionalMembershipId(), null, Set.of(), null, null, null), tres);
		assertThat(primera.getTotalElements()).isEqualTo(8);
		assertThat(primera.getTotalPages()).isEqualTo(3);
		assertThat(primera.getContent()).hasSize(3);
		Page<CambioDeAgendaView> todo = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(sedeB.profesionalMembershipId(), null, Set.of(), null, null, null),
				PageRequest.of(0, 10));
		assertThat(todo.getContent()).extracting(CambioDeAgendaView::tipo).containsExactlyInAnyOrder(
				TipoDeCambioDeAgenda.HORARIO_ALTA, TipoDeCambioDeAgenda.HORARIO_EDICION,
				TipoDeCambioDeAgenda.EXCEPCION_ALTA, TipoDeCambioDeAgenda.TURNO_RESERVA,
				TipoDeCambioDeAgenda.TURNO_REPROGRAMACION, TipoDeCambioDeAgenda.TURNO_CANCELACION,
				TipoDeCambioDeAgenda.TURNO_TRASLADO, TipoDeCambioDeAgenda.TURNO_RESERVA);

		CambioDeAgendaView edicion = soloUno(lector, sedeA, TipoDeCambioDeAgenda.HORARIO_EDICION);
		assertThat(edicion.antes()).startsWith("Mar 09:00–13:00");
		assertThat(edicion.despues()).startsWith("Mar 10:00–13:00");
		assertThat(edicion.actorRol()).isEqualTo("ORG_ADMIN");

		CambioDeAgendaView reprogramacion = soloUno(lector, sedeA, TipoDeCambioDeAgenda.TURNO_REPROGRAMACION);
		assertThat(reprogramacion.antes()).startsWith("Lun 08/03 09:00");
		assertThat(reprogramacion.despues()).startsWith("Lun 08/03 10:00");
		assertThat(reprogramacion.motivo()).isEqualTo("Pidio mas tarde");
		assertThat(reprogramacion.personaId()).isEqualTo(sedeA.personaA());
		assertThat(reprogramacion.personaNombre()).isNotBlank();

		CambioDeAgendaView cambioDeSede = soloUno(lector, sedeA, TipoDeCambioDeAgenda.TURNO_TRASLADO);
		assertThat(cambioDeSede.despues()).startsWith("Lun 08/03 11:00 · Sede Sintetica");

		// El profesional ve lo suyo; el de al lado, no.
		long cuentaPro = jdbc.queryForObject("SELECT account_id FROM membership WHERE id = ?", Long.class, pro);
		OperatingActor profesional = new OperatingActor(cuentaPro, false, sedeA.organizationId(), sedeA.consultorioId(),
				"PROFESIONAL");
		assertThat(historial.listar(profesional, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, Set.of(), null, null, null), PageRequest.of(0, 10))
				.getTotalElements()).isEqualTo(8);
		long otro = fixtures.agregarProfesional(sedeA);
		assertThatThrownBy(() -> historial.listar(profesional, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(otro, null, Set.of(), null, null, null), PageRequest.of(0, 10)))
				.isInstanceOf(AgendaAjenaException.class);

		// El turno nuevo es "reprogramado" (una reprogramacion del origen + el traslado), sin cambiar su estado.
		TurnoDelDiaView nuevo = recepcion.ver(sedeB.actor(), sedeB.consultorioId(), traslado.nuevo().id());
		assertThat(nuevo.operativo().estadoOperativo()).isEqualTo("RESERVADO");
		assertThat(nuevo.operativo().reprogramaciones()).isEqualTo(2);
		assertThat(nuevo.operativo().reprogramacion().inicioOriginal()).isEqualTo(hora(9));
		assertThat(nuevo.operativo().reprogramacion().ultimoTipo()).isEqualTo("TRASLADO");

		// Y su historial trae los eventos del turno de origen.
		assertThat(ciclo.historial(sedeB.actor(), sedeB.consultorioId(), traslado.nuevo().id()))
				.extracting(EventoDeTurnoView::turnoId)
				.contains(turno.id(), traslado.nuevo().id());
	}

	private CambioDeAgendaView soloUno(OperatingActor lector, Fixture sede, TipoDeCambioDeAgenda tipo) {
		Page<CambioDeAgendaView> pagina = historial.listar(lector, sede.organizationId(),
				new FiltroDeHistorialDeAgenda(sede.profesionalMembershipId(), null, Set.of(tipo), null, null, null),
				PageRequest.of(0, 10));
		assertThat(pagina.getContent()).hasSize(1);
		return pagina.getContent().get(0);
	}

	private static Instant hora(int horaLocal) {
		return LUNES.atTime(horaLocal, 0).atZone(ZONA).toInstant();
	}
}
