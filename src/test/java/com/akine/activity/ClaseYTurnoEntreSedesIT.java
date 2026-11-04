package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ProgramarClaseCommand;
import com.akine.activity.application.ResultadoDeProgramacion;
import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaOperativaService;
import com.akine.scheduling.application.AgendaOperativaView;
import com.akine.scheduling.application.AgendaService;
import com.akine.scheduling.application.AgendaView;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.ResultadoDeReserva;
import com.akine.scheduling.application.TurnoService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Una clase y un turno de la MISMA PERSONA en dos sedes de la organizacion (agenda de clases y avisos,
 * contrato 0.156.0), contra MySQL real.
 *
 * <p>El defecto: la clase miraba los turnos de UNA membership y el turno miraba las clases de UNA
 * membership. Con una membership por sede, una clase en la Sede A de 09:30 a 10:30 dejaba a la misma
 * persona libre en la Sede B a las 10:00: la agenda la ofrecia y la reserva pasaba. Al reves, se
 * programaba una clase encima de un turno de la otra sede.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ClaseYTurnoEntreSedesIT {

	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private ClaseService claseService;
	@Autowired private TurnoService turnoService;
	@Autowired private AgendaService agendaService;
	@Autowired private AgendaOperativaService agendaOperativa;
	@Autowired private JdbcTemplate jdbc;

	private AgendaFixtures fixtures;

	@BeforeEach
	void setUp() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("Una clase en la Sede A rechaza el turno que se le cruza en parte en la Sede B, y la agenda de B no lo ofrece")
	void la_clase_de_otra_sede_bloquea_el_turno() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.otraSedeDelMismoProfesional(sedeA, true);
		ResultadoDeProgramacion clase = programar(sedeA, ofertaGrupal(sedeA), a(9, 30), a(10, 30));

		// La agenda de B no ofrece ni las 09:00 ni las 10:00: las dos se cruzan con la clase de A.
		AgendaView agenda = agendaService.buscar(sedeB.actor(), sedeB.consultorioId(), sedeB.ofertaId(),
				LUNES, LUNES.plusDays(1), sedeB.profesionalMembershipId());
		List<AgendaView.SlotDisponible> slots = agenda.dias().getFirst().slots();
		assertThat(slots).filteredOn(s -> s.desde().equals(a(9, 0)) || s.desde().equals(a(10, 0)))
				.hasSize(2).allMatch(s -> s.cupoLibre() == 0);
		assertThat(slots).filteredOn(s -> s.desde().equals(a(11, 0))).singleElement()
				.matches(s -> s.cupoLibre() == 1);

		// Y la reserva, bajo los locks, la rechaza nombrando la clase y su sede.
		assertThatThrownBy(() -> reservar(sedeB, a(10, 0)))
				.isInstanceOfSatisfying(com.akine.scheduling.domain.exception.RecursoOcupadoException.class, e -> {
					assertThat(e.getConflictoTipo()).isEqualTo("CLASE");
					assertThat(e.getClaseEnConflictoId()).isEqualTo(clase.clase().id());
					assertThat(e.getConflictoConsultorioId()).isEqualTo(sedeA.consultorioId());
					assertThat(e.getMessage()).contains("otra sede");
				});
		// El sobreturno tampoco la salva: la persona no puede estar dando la clase en otra sede.
		assertThatThrownBy(() -> turnoService.reservar(sedeB.actor(), sedeB.consultorioId(), sedeB.ofertaId(),
				new ReservaCommand(sedeB.personaA(), a(10, 0), sedeB.profesionalMembershipId(), null, null, null,
						null, null, null, "Urgencia", null)))
				.isInstanceOf(com.akine.scheduling.domain.exception.RecursoOcupadoException.class);
		assertThat(turnosVivos(sedeB)).isZero();

		// La agenda operativa de B dibuja la clase de A como ocupacion en otra sede, sin paciente ni id.
		AgendaOperativaView operativa = agendaOperativa.ver(sedeB.actor(), sedeB.consultorioId(), LUNES,
				LUNES.plusDays(1), null, null, null, false);
		assertThat(operativa.ocupacionesEnOtrasSedes()).singleElement().satisfies(o -> {
			assertThat(o.membershipId()).isEqualTo(sedeB.profesionalMembershipId());
			assertThat(o.tipo()).isEqualTo("CLASE");
			assertThat(o.consultorioId()).isEqualTo(sedeA.consultorioId());
			assertThat(o.inicio()).isEqualTo(a(9, 30));
		});
		assertThat(operativa.conflictos()).isEmpty();
	}

	@Test
	@DisplayName("Un turno en la Sede B impide programar en la Sede A una clase que se le cruza; la que no se cruza entra")
	void el_turno_de_otra_sede_bloquea_la_clase() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.otraSedeDelMismoProfesional(sedeA, true);
		long grupal = ofertaGrupal(sedeA);
		ResultadoDeReserva turno = reservar(sedeB, a(11, 0));

		assertThatThrownBy(() -> programar(sedeA, grupal, a(11, 30), a(12, 30)))
				.isInstanceOfSatisfying(com.akine.activity.domain.exception.RecursoOcupadoException.class, e -> {
					assertThat(e.getConflictoTipo()).isEqualTo("TURNO");
					assertThat(e.getConflictoId()).isEqualTo(turno.turno().id());
					assertThat(e.getConflictoConsultorioId()).isEqualTo(sedeB.consultorioId());
				});
		assertThat(clasesVivas(sedeA)).isZero();

		programar(sedeA, grupal, a(9, 0), a(10, 0));
		assertThat(clasesVivas(sedeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("Clase en A y turno en B a la vez, misma persona y horario: entra uno solo (lock de la persona)")
	void clase_y_turno_simultaneos_en_dos_sedes() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.otraSedeDelMismoProfesional(sedeA, true);
		long grupal = ofertaGrupal(sedeA);

		List<Desenlace<Object>> desenlaces = AgendaFixtures.enParalelo(List.<Callable<Object>>of(
				() -> programar(sedeA, grupal, a(12, 0), a(13, 0)),
				() -> reservar(sedeB, a(12, 0))));

		assertThat(desenlaces).as("%s", desenlaces).filteredOn(d -> !d.fallo()).hasSize(1);
		assertThat(desenlaces).filteredOn(Desenlace::fallo).singleElement()
				.satisfies(d -> assertThat(
						AgendaFixtures.causaEs(d.error(), com.akine.activity.domain.exception.RecursoOcupadoException.class)
								|| AgendaFixtures.causaEs(d.error(),
										com.akine.scheduling.domain.exception.RecursoOcupadoException.class))
						.as("%s", d).isTrue());
		assertThat(clasesVivas(sedeA) + turnosVivos(sedeB)).isEqualTo(1);
	}

	// =================================================================================

	private ResultadoDeProgramacion programar(Fixture sede, long ofertaId, Instant inicio, Instant fin) {
		return claseService.programar(
				new OperatingActor(sede.actor().accountId(), false, sede.organizationId(), sede.consultorioId()),
				sede.consultorioId(), ofertaId,
				new ProgramarClaseCommand(inicio, fin, sede.profesionalMembershipId(), 5, "Clase sintetica",
						null, null));
	}

	private ResultadoDeReserva reservar(Fixture sede, Instant inicio) {
		return turnoService.reservar(sede.actor(), sede.consultorioId(), sede.ofertaId(),
				new ReservaCommand(sede.personaA(), inicio, sede.profesionalMembershipId(), null));
	}

	private static Instant a(int hora, int minuto) {
		return LUNES.atTime(hora, minuto).atZone(ZONA).toInstant().truncatedTo(ChronoUnit.MINUTES);
	}

	private long turnosVivos(Fixture sede) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE consultorio_id = ? AND deleted_at IS NULL",
				Long.class, sede.consultorioId());
	}

	private long clasesVivas(Fixture sede) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM clase_programada WHERE consultorio_id = ? AND deleted_at IS NULL",
				Long.class, sede.consultorioId());
	}

	/** Una oferta GRUPAL de la sede, sin habilitaciones (= todos), con profesional y sin box. */
	private long ofertaGrupal(Fixture sede) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "SEDES-" + sufijo, "Servicio " + sufijo);
		long servicio = jdbc.queryForObject("SELECT id FROM servicio WHERE codigo = ?", Long.class, "SEDES-" + sufijo);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 10, 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", sede.organizationId(), sede.consultorioId(), servicio, "Grupal " + sufijo);
		return jdbc.queryForObject(
				"SELECT id FROM oferta_servicio_consultorio WHERE organization_id = ? AND nombre_comercial = ?",
				Long.class, sede.organizationId(), "Grupal " + sufijo);
	}
}
