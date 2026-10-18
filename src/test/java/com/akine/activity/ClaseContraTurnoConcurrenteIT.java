package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.CarreraDirigida.Retencion;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ProgramarClaseCommand;
import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 39 de {@code docs/tests-diferidos.md} (AKINE-08.01): <b>la exclusion entre una clase y
 * un turno, con dos transacciones de verdad</b>.
 *
 * <p>La etapa afirma que una clase y un turno no pueden tomar el mismo profesional a la vez porque
 * <b>se disputan la misma fila de {@code agenda_sede}</b>: {@code ClaseService} la bloquea antes de
 * mirar turnos y clases, y {@code RevalidadorDeSlot} la bloquea antes de mirar turnos y, por la
 * sonda {@code ClasesEnLaAgendaDeTurnos}, clases. Eso lo contesta InnoDB y nada mas.
 *
 * <p>Las dos direcciones se fuerzan con {@link CarreraDirigida}: la fila de la sede queda retenida,
 * las dos escrituras se largan en el orden elegido y se espera a verlas bloqueadas en ella antes de
 * soltarla. Asi la carrera ocurre siempre, y no "cuando el planificador quiere".
 *
 * <p><b>Verificacion por mutacion</b> (corrida el 17/10/2026): con la llamada a
 * {@code agenda.bloquear(...)} sacada de {@code ClaseService#programarEnTransaccion}, la clase deja
 * de esperar la fila retenida, {@code largarHastaBloquearse} ya no la ve bloqueada y los dos casos
 * dirigidos fallan; la rafaga de dos clases sobre el mismo profesional vende el profesional dos
 * veces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ClaseContraTurnoConcurrenteIT {

	/** Fijo y lejano, como en {@code TurnoConcurrenteIT}: la disponibilidad del fixture son los lunes. */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 1);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);
	private static final String LOCK_DE_AGENDA = "%agenda_sede%FOR UPDATE%";

	@Autowired private ClaseService claseService;
	@Autowired private TurnoService turnoService;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private AgendaFixtures agenda;

	@BeforeEach
	void setUp() {
		agenda = new AgendaFixtures(jdbc);
	}

	@ParameterizedTest(name = "la clase llega primero: {0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("Clase y turno por el mismo profesional y horario, en los dos ordenes: uno entra y el otro es recurso-ocupado")
	void clase_contra_turno_en_los_dos_ordenes(boolean claseLlegaPrimero) {
		Fixture sede = agenda.crear(1);
		long grupal = ofertaGrupal(sede, true);
		long fila = filaDeAgenda(sede);
		Instant nueve = hora(9);

		Callable<Object> clase = () -> programar(sede, grupal, nueve, sede.profesionalMembershipId());
		Callable<Object> turno = () -> reservar(sede, sede.personaA(), nueve);

		Desenlace<Object> primera;
		Desenlace<Object> segunda;
		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion retenida = Retencion.de(dataSource, "agenda_sede", fila)) {
			Future<Desenlace<Object>> f1 =
					carrera.largarHastaBloquearse(claseLlegaPrimero ? clase : turno, LOCK_DE_AGENDA);
			Future<Desenlace<Object>> f2 =
					carrera.largarHastaBloquearse(claseLlegaPrimero ? turno : clase, LOCK_DE_AGENDA);
			retenida.soltar();
			primera = CarreraDirigida.resultado(f1);
			segunda = CarreraDirigida.resultado(f2);
		}

		List<Desenlace<Object>> desenlaces = List.of(primera, segunda);
		assertThat(desenlaces.stream().filter(d -> !d.fallo()))
				.as("exactamente una entra. Desenlaces: %s", desenlaces)
				.hasSize(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo))
				.as("y la otra es recurso-ocupado, no un error tecnico. Desenlaces: %s", desenlaces)
				.allMatch(d -> esRecursoOcupado(d.error()));
		assertThat(clasesVivasDelProfesional(sede) + turnosVivos(sede))
				.as("en la base hay UN evento del profesional a las 09:00, no dos")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("Rafaga de clases sobre el mismo profesional y horario: entra una sola")
	void dos_clases_sobre_el_mismo_profesional() {
		Fixture sede = agenda.crear(1);
		long grupal = ofertaGrupal(sede, true);
		filaDeAgenda(sede);
		Instant diez = hora(10);

		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			tareas.add(() -> programar(sede, grupal, diez, sede.profesionalMembershipId()));
		}
		List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(tareas);

		assertThat(desenlaces.stream().filter(d -> !d.fallo()))
				.as("Desenlaces: %s", desenlaces).hasSize(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo))
				.allMatch(d -> esRecursoOcupado(d.error()));
		assertThat(clasesVivasDelProfesional(sede)).isEqualTo(1);
	}

	@Test
	@DisplayName("Control negativo: con la agenda de una sede retenida, otra sede de la misma organizacion reserva y programa sin esperar")
	void otra_sede_no_espera_el_lock_ajeno() {
		Fixture sedeA = agenda.crear(1);
		// El ORG_ADMIN del fixture nace con alcance de la sede A; para operar en las dos se lo
		// lleva al alcance de la organizacion (consultorio_id NULL, T-4).
		jdbc.update("UPDATE membership SET consultorio_id = NULL WHERE organization_id = ? AND account_id = ?",
				sedeA.organizationId(), sedeA.actor().accountId());
		Fixture sedeB = agenda.poblar(sedeA.organizationId(), otraSede(sedeA.organizationId()),
				sedeA.actor().accountId(), 1, false);
		long grupalB = ofertaGrupal(sedeB, true);
		long filaA = filaDeAgenda(sedeA);
		filaDeAgenda(sedeB);
		Instant nueve = hora(9);

		// Si el lock fuera por organizacion —o por tabla—, estas dos llamadas esperarian a la fila
		// de A hasta el innodb_lock_wait_timeout y el test fallaria por el error, no por colgarse.
		try (Retencion retenida = Retencion.de(dataSource, "agenda_sede", filaA)) {
			reservar(sedeB, sedeB.personaA(), nueve);
			programar(sedeB, grupalB, hora(11), sedeB.profesionalMembershipId());
			assertThat(turnosVivos(sedeB) + clasesVivasDelProfesional(sedeB)).isEqualTo(2);
		}

		// Y con la fila suelta, la misma hora en A tambien entra: es otro profesional y otra sede.
		reservar(sedeA, sedeA.personaA(), nueve);
		assertThat(turnosVivos(sedeA)).isEqualTo(1);
	}

	@Test
	@DisplayName("Las primeras escrituras de una sede sin fila de agenda: clases y turnos a la vez, sin deadlock ni rechazo")
	void primeras_escrituras_de_la_sede() {
		Fixture sede = agenda.crear(1);
		long grupalSinProfesional = ofertaGrupal(sede, false);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agenda_sede WHERE consultorio_id = ?",
				Integer.class, sede.consultorioId()))
				.as("la sede arranca sin fila de agenda: es el caso de la creacion perezosa")
				.isZero();

		// Seis escrituras que no compiten por ningun recurso —horarios distintos para el profesional,
		// clases sin profesional ni box—: las seis tienen que entrar. Lo unico que disputan es la
		// CREACION de la fila-lock, que es donde el patron leer-y-despues-insertar deadlockea.
		List<Callable<Object>> tareas = List.of(
				() -> reservar(sede, sede.personaA(), hora(9)),
				() -> reservar(sede, sede.personaB(), hora(10)),
				() -> reservar(sede, sede.personaC(), hora(11)),
				() -> programar(sede, grupalSinProfesional, hora(9), null),
				() -> programar(sede, grupalSinProfesional, hora(10), null),
				() -> programar(sede, grupalSinProfesional, hora(11), null));
		List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(tareas);

		assertThat(desenlaces).as("Desenlaces: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agenda_sede WHERE consultorio_id = ?",
				Integer.class, sede.consultorioId())).isEqualTo(1);
		assertThat(turnosVivos(sede)).isEqualTo(3);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM clase_programada WHERE consultorio_id = ? AND deleted_at IS NULL",
				Integer.class, sede.consultorioId())).isEqualTo(3);
	}

	// =================================================================================

	private static Instant hora(int h) {
		return LUNES.atTime(h, 0).atZone(ZONA).toInstant();
	}

	private Object programar(Fixture sede, long ofertaId, Instant inicio, Long profesional) {
		return claseService.programar(
				new OperatingActor(sede.actor().accountId(), false, sede.organizationId(), sede.consultorioId()),
				sede.consultorioId(), ofertaId,
				new ProgramarClaseCommand(inicio, inicio.plus(1, ChronoUnit.HOURS), profesional, 5,
						"Clase sintetica", null, null));
	}

	private Object reservar(Fixture sede, long persona, Instant inicio) {
		return turnoService.reservar(sede.actor(), sede.consultorioId(), sede.ofertaId(),
				new ReservaCommand(persona, inicio, sede.profesionalMembershipId(), null));
	}

	private static boolean esRecursoOcupado(Throwable error) {
		return AgendaFixtures.causaEs(error, com.akine.activity.domain.exception.RecursoOcupadoException.class)
				|| AgendaFixtures.causaEs(error, com.akine.scheduling.domain.exception.RecursoOcupadoException.class);
	}

	/** Crea la fila de la sede, si falta, y devuelve su id. */
	private long filaDeAgenda(Fixture sede) {
		jdbc.update("""
				INSERT INTO agenda_sede (organization_id, consultorio_id, created_at)
				VALUES (?, ?, UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id = id
				""", sede.organizationId(), sede.consultorioId());
		return jdbc.queryForObject(
				"SELECT id FROM agenda_sede WHERE organization_id = ? AND consultorio_id = ?",
				Long.class, sede.organizationId(), sede.consultorioId());
	}

	/** Una oferta GRUPAL de la sede; sin filas de habilitacion, lo que significa "todos". */
	private long ofertaGrupal(Fixture sede, boolean requiereProfesional) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "EXCL-" + sufijo, "Servicio " + sufijo);
		long servicio = jdbc.queryForObject("SELECT id FROM servicio WHERE codigo = ?", Long.class,
				"EXCL-" + sufijo);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 10, 0, 0, 0, ?, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", sede.organizationId(), sede.consultorioId(), servicio, "Grupal " + sufijo,
				requiereProfesional ? 1 : 0);
		return jdbc.queryForObject("""
				SELECT id FROM oferta_servicio_consultorio WHERE organization_id = ? AND nombre_comercial = ?
				""", Long.class, sede.organizationId(), "Grupal " + sufijo);
	}

	private long otraSede(long organizationId) {
		String nombre = "Sede B " + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, AgendaFixtures.ZONA);
		return jdbc.queryForObject("SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
	}

	private long clasesVivasDelProfesional(Fixture sede) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM clase_programada
				 WHERE organization_id = ? AND profesional_membership_id = ? AND deleted_at IS NULL
				""", Long.class, sede.organizationId(), sede.profesionalMembershipId());
	}

	private long turnosVivos(Fixture sede) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno WHERE organization_id = ? AND oferta_id = ? AND deleted_at IS NULL
				""", Long.class, sede.organizationId(), sede.ofertaId());
	}
}
