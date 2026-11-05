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
import com.akine.scheduling.domain.exception.ProfesionalNotAccessibleException;
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
		assertThat(primera.getTotalElements()).isEqualTo(6);
		assertThat(primera.getTotalPages()).isEqualTo(2);
		assertThat(primera.getContent()).hasSize(3);
		Page<CambioDeAgendaView> todo = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(sedeB.profesionalMembershipId(), null, Set.of(), null, null, null),
				PageRequest.of(0, 10));
		assertThat(todo.getContent()).extracting(CambioDeAgendaView::tipo).containsExactlyInAnyOrder(
				TipoDeCambioDeAgenda.HORARIO_ALTA, TipoDeCambioDeAgenda.HORARIO_EDICION,
				TipoDeCambioDeAgenda.EXCEPCION_ALTA, TipoDeCambioDeAgenda.TURNO_RESERVA,
				// El cambio de sede es UNA fila: ni la cancelacion del original ni la reserva del nuevo.
				TipoDeCambioDeAgenda.TURNO_REPROGRAMACION, TipoDeCambioDeAgenda.TURNO_TRASLADO);

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
		assertThat(cambioDeSede.antes()).startsWith("Lun 08/03 10:00");
		assertThat(cambioDeSede.cambioDeProfesional()).as("la misma persona en otra sede").isFalse();
		assertThat(cambioDeSede.turnoNuevoId()).isEqualTo(traslado.nuevo().id());
		assertThat(cambioDeSede.operacionId()).isNotBlank();
		assertThat(cambioDeSede.eventosRelacionados()).extracting(CambioDeAgendaView.EventoRelacionadoView::eventType)
				.containsExactlyInAnyOrder("TURNO_CANCELADO", "TURNO_RESERVADO");
		// El id de operacion une las tres filas de auditoria, que siguen todas ahi.
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(details, '$.operacionId')) = ?
				""", Integer.class, sedeA.organizationId(), cambioDeSede.operacionId())).isEqualTo(3);

		// El profesional ve lo suyo; el de al lado, no.
		long cuentaPro = jdbc.queryForObject("SELECT account_id FROM membership WHERE id = ?", Long.class, pro);
		OperatingActor profesional = new OperatingActor(cuentaPro, false, sedeA.organizationId(), sedeA.consultorioId(),
				"PROFESIONAL");
		assertThat(historial.listar(profesional, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, Set.of(), null, null, null), PageRequest.of(0, 10))
				.getTotalElements()).isEqualTo(6);
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

	@Test
	@DisplayName("cierre de sede: aparece para el afectado con su revocacion, no para el de otra sede ni de otro dia")
	void cierre_de_sede_para_los_afectados() {
		AgendaFixtures fixtures = new AgendaFixtures(jdbc);
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.agregarSede(sedeA);
		long pro = sedeA.profesionalMembershipId();
		long deB = sedeB.profesionalMembershipId();
		var adminA = new com.akine.resource.application.OperatingActor(
				sedeA.actor().accountId(), false, sedeA.organizationId(), sedeA.consultorioId(), "ORG_ADMIN");
		var adminB = new com.akine.resource.application.OperatingActor(
				sedeB.actor().accountId(), false, sedeB.organizationId(), sedeB.consultorioId(), "ORG_ADMIN");
		LocalDate feriado = LUNES.plusWeeks(2);

		// Un turno de la persona ese lunes, que el cierre deja pendiente de reubicar.
		turnos.reservar(sedeA.actor(), sedeA.consultorioId(), sedeA.ofertaId(),
				new ReservaCommand(sedeA.personaA(), feriado.atTime(9, 0).atZone(ZONA).toInstant(), pro, null));
		// Feriado de toda la sede A ese lunes (fin exclusivo), que despues se revoca.
		long cierre = excepciones.crear(adminA, sedeA.consultorioId(), new ExcepcionAltaCommand(null,
				TipoExcepcion.CIERRE, MotivoExcepcion.FERIADO, feriado, feriado.plusDays(1), null, null, null, null,
				true)).id();
		excepciones.darDeBaja(adminA, sedeA.consultorioId(), cierre, "Se trabaja igual");
		// Un martes cerrado en A (la persona atiende solo los lunes) y un lunes cerrado en B.
		excepciones.crear(adminA, sedeA.consultorioId(), new ExcepcionAltaCommand(null, TipoExcepcion.CIERRE,
				MotivoExcepcion.FERIADO, feriado.plusDays(1), feriado.plusDays(2), null, null, null, null, true));
		long cierreDeB = excepciones.crear(adminB, sedeB.consultorioId(), new ExcepcionAltaCommand(null,
				TipoExcepcion.CIERRE, MotivoExcepcion.FERIADO, feriado, feriado.plusDays(1), null, null, null, null,
				true)).id();

		OperatingActor lector = new OperatingActor(
				sedeA.actor().accountId(), false, sedeA.organizationId(), sedeA.consultorioId(), "ORG_ADMIN");
		Set<TipoDeCambioDeAgenda> deCierre = Set.of(TipoDeCambioDeAgenda.CIERRE_DE_SEDE_ALTA,
				TipoDeCambioDeAgenda.CIERRE_DE_SEDE_EDICION, TipoDeCambioDeAgenda.CIERRE_DE_SEDE_BAJA);

		Page<CambioDeAgendaView> dePro = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, deCierre, null, null, null), PageRequest.of(0, 10));
		assertThat(dePro.getTotalElements()).isEqualTo(2);
		assertThat(dePro.getContent()).extracting(CambioDeAgendaView::tipo).containsExactlyInAnyOrder(
				TipoDeCambioDeAgenda.CIERRE_DE_SEDE_ALTA, TipoDeCambioDeAgenda.CIERRE_DE_SEDE_BAJA);
		CambioDeAgendaView baja = dePro.getContent().get(0);
		assertThat(baja.tipo()).isEqualTo(TipoDeCambioDeAgenda.CIERRE_DE_SEDE_BAJA);
		assertThat(baja.entidadId()).isEqualTo(cierre);
		assertThat(baja.motivo()).isEqualTo("Se trabaja igual");
		assertThat(baja.profesionalId()).isEqualTo(pro);
		assertThat(baja.consultorioId()).isEqualTo(sedeA.consultorioId());
		assertThat(baja.cierreDeSede().fechaDesde()).isEqualTo(feriado);
		assertThat(baja.cierreDeSede().ultimoDia()).isEqualTo(feriado);
		assertThat(baja.cierreDeSede().motivo()).isEqualTo("FERIADO");
		assertThat(baja.cierreDeSede().vigente()).isFalse();
		assertThat(baja.cierreDeSede().conHorarioHabitual()).isTrue();
		assertThat(baja.cierreDeSede().turnosPendientes()).isEqualTo(1);

		// Sin filtro de tipo tambien estan, junto al resto, y el paginado cuenta bien.
		Page<CambioDeAgendaView> todo = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, Set.of(), null, null, null), PageRequest.of(0, 2));
		assertThat(todo.getTotalElements()).isEqualTo(3);
		// Solo EXCEPCION_*: los cierres de sede no se cuelan.
		assertThat(historial.listar(lector, sedeA.organizationId(), new FiltroDeHistorialDeAgenda(pro, null,
				Set.of(TipoDeCambioDeAgenda.EXCEPCION_ALTA, TipoDeCambioDeAgenda.EXCEPCION_BAJA), null, null, null),
				PageRequest.of(0, 10)).getTotalElements()).isZero();

		// El de la otra sede ve el cierre de SU sede, no el de A.
		Page<CambioDeAgendaView> deOtro = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(deB, null, deCierre, null, null, null), PageRequest.of(0, 10));
		assertThat(deOtro.getContent()).extracting(CambioDeAgendaView::entidadId).containsExactly(cierreDeB);

		// Una administrativa de B no ve el cierre de A en el historial de la persona de A.
		OperatingActor administrativaB = fixtures.actorDe(sedeB, "ADMINISTRATIVO");
		OperatingActor conRol = new OperatingActor(administrativaB.accountId(), false, sedeA.organizationId(),
				sedeB.consultorioId(), "ADMINISTRATIVO");
		assertThat(historial.listar(conRol, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, deCierre, null, null, null), PageRequest.of(0, 10))
				.getTotalElements()).isZero();

		// Otra organizacion: 404.
		Fixture ajena = fixtures.crear(1);
		OperatingActor deOtraOrg = new OperatingActor(
				ajena.actor().accountId(), false, ajena.organizationId(), ajena.consultorioId(), "ORG_ADMIN");
		assertThatThrownBy(() -> historial.listar(deOtraOrg, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, deCierre, null, null, null), PageRequest.of(0, 10)))
				.isInstanceOf(ProfesionalNotAccessibleException.class);
		assertThatThrownBy(() -> historial.listar(deOtraOrg, ajena.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, deCierre, null, null, null), PageRequest.of(0, 10)))
				.isInstanceOf(ProfesionalNotAccessibleException.class);
	}

	@Test
	@DisplayName("traslado a otro profesional: una fila para cada uno; una cancelacion real sigue siendo cancelacion")
	void traslado_a_otro_profesional_y_cancelacion_real() {
		AgendaFixtures fixtures = new AgendaFixtures(jdbc);
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.agregarSede(sedeA);
		long pro = sedeA.profesionalMembershipId();
		long deB = sedeB.profesionalMembershipId();

		TurnoView movido = turnos.reservar(sedeA.actor(), sedeA.consultorioId(), sedeA.ofertaId(),
				new ReservaCommand(sedeA.personaA(), hora(9), pro, null)).turno();
		TrasladoView traslado = traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), movido.id(),
				new TrasladoCommand(sedeB.consultorioId(), sedeB.ofertaId(), hora(11), deB, null, "Se mudo",
						movido.version()));
		TurnoView cancelado = turnos.reservar(sedeA.actor(), sedeA.consultorioId(), sedeA.ofertaId(),
				new ReservaCommand(sedeA.personaB(), hora(10), pro, null)).turno();
		ciclo.cancelar(sedeA.actor(), sedeA.consultorioId(), cancelado.id(), "No puede venir", cancelado.version());

		OperatingActor lector = new OperatingActor(
				sedeA.actor().accountId(), false, sedeA.organizationId(), sedeA.consultorioId(), "ORG_ADMIN");
		Page<CambioDeAgendaView> dePro = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(pro, null, Set.of(), null, null, null), PageRequest.of(0, 10));
		assertThat(dePro.getContent()).extracting(CambioDeAgendaView::tipo).containsExactlyInAnyOrder(
				TipoDeCambioDeAgenda.TURNO_RESERVA, TipoDeCambioDeAgenda.TURNO_TRASLADO,
				TipoDeCambioDeAgenda.TURNO_RESERVA, TipoDeCambioDeAgenda.TURNO_CANCELACION);
		CambioDeAgendaView cancelacion = dePro.getContent().stream()
				.filter(c -> c.tipo() == TipoDeCambioDeAgenda.TURNO_CANCELACION).findFirst().orElseThrow();
		assertThat(cancelacion.turnoId()).isEqualTo(cancelado.id());
		CambioDeAgendaView visto = dePro.getContent().stream()
				.filter(c -> c.tipo() == TipoDeCambioDeAgenda.TURNO_TRASLADO).findFirst().orElseThrow();
		assertThat(visto.cambioDeProfesional()).isTrue();

		// La persona de destino ve el MISMO movimiento, y no una reserva suelta.
		Page<CambioDeAgendaView> deDestino = historial.listar(lector, sedeA.organizationId(),
				new FiltroDeHistorialDeAgenda(deB, null, Set.of(), null, null, null), PageRequest.of(0, 10));
		assertThat(deDestino.getContent()).extracting(CambioDeAgendaView::id).containsExactly(visto.id());
		assertThat(deDestino.getContent().get(0).cambioDeProfesional()).isTrue();
		assertThat(deDestino.getContent().get(0).turnoNuevoId()).isEqualTo(traslado.nuevo().id());
		// Y filtrando por TURNO_RESERVA no aparece la reserva absorbida.
		assertThat(historial.listar(lector, sedeA.organizationId(), new FiltroDeHistorialDeAgenda(deB, null,
				Set.of(TipoDeCambioDeAgenda.TURNO_RESERVA), null, null, null), PageRequest.of(0, 10))
				.getTotalElements()).isZero();
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
