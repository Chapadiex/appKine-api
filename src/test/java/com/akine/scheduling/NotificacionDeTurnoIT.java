package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.AgendaSedeIniciador;
import com.akine.scheduling.application.CicloDeTurnoService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los avisos de turno salen por el outbox transaccional (RF-M26-002/003, AKINE E-5).
 *
 * <h2>Por que esto tiene que correr contra MySQL</h2>
 *
 * <p>Lo que se promete es que la fila del outbox comitea <b>junto</b> con el turno o no comitea
 * ninguna de las dos. Un mock puede verificar que se llamo a {@code enqueue}; no puede verificar
 * que la propagacion {@code MANDATORY} se unio a la transaccion de la reserva, ni que un rollback
 * se lleva las dos filas. Eso solo lo contesta el motor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class NotificacionDeTurnoIT {

	/** Lunes fijo y lejano: ver {@code TurnoConcurrenteIT}. */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 1);
	private static final ZoneId ZONA = ZoneId.of(AgendaFixtures.ZONA);

	@Autowired private AgendaSedeIniciador agendaSedeIniciador;
	@Autowired private TurnoService turnoService;
	@Autowired private CicloDeTurnoService cicloService;
	@Autowired private TrasladoDeTurnoService traslados;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new AgendaFixtures(jdbc);
	}

	@Test
	@DisplayName("reservar deja UNA fila TURNO_RESERVADO para el paciente, con fecha y hora de la sede")
	void reservar_encola_el_aviso() {
		Fixture fixture = fixtures.crear(1);
		String email = conCorreo(fixture.personaA());

		TurnoView turno = reservar(fixture, fixture.personaA(), a(9, 0), null);

		Map<String, Object> fila = filaDe("turno-reservado:" + turno.id());
		assertThat(fila.get("tipo")).isEqualTo("TURNO_RESERVADO");
		assertThat(fila.get("destinatario")).isEqualTo(email);
		assertThat(((Number) fila.get("organization_id")).longValue()).isEqualTo(fixture.organizationId());
		assertThat(fila.get("turno_inicio")).isEqualTo("01/03/2027 a las 09:00");
		assertThat(fila.get("referencia_token_id")).isNull();
	}

	@Test
	@DisplayName("una persona sin correo reserva igual, y no queda ninguna fila")
	void sin_correo_reserva_igual() {
		Fixture fixture = fixtures.crear(1);

		TurnoView turno = reservar(fixture, fixture.personaA(), a(9, 0), null);

		assertThat(turno.id()).isPositive();
		assertThat(contar("turno-reservado:" + turno.id())).isZero();
	}

	/**
	 * La prueba de que el aviso vive en la transaccion de la reserva: una transaccion externa que
	 * envuelve la reserva y hace rollback se lleva el turno Y la fila del outbox. Si el encolado
	 * hubiera abierto su propia transaccion, la fila sobreviviria avisando de un turno que no
	 * existe.
	 */
	@Test
	@DisplayName("si la reserva hace rollback, no queda ni el turno ni la notificacion")
	void el_rollback_se_lleva_el_aviso() {
		Fixture fixture = fixtures.crear(1);
		conCorreo(fixture.personaA());
		// La fila de agenda_sede tiene que existir antes de abrir la transaccion de afuera: dentro de
		// una transaccion ajena la reserva no puede crearla sin pedir otra conexion (FilaLockPrevia).
		agendaSedeIniciador.asegurar(fixture.organizationId(), fixture.consultorioId());
		// Lo mismo para la fila de agenda_profesional del profesional del turno.
		agendaSedeIniciador.asegurarProfesionales(
				fixture.organizationId(), List.of(fixture.profesionalMembershipId()));

		Long turnoId = new TransactionTemplate(transactionManager).execute(estado -> {
			TurnoView turno = reservar(fixture, fixture.personaA(), a(9, 0), null);
			assertThat(contar("turno-reservado:" + turno.id()))
					.as("dentro de la transaccion la fila existe")
					.isEqualTo(1);
			estado.setRollbackOnly();
			return turno.id();
		});

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE id = ?", Long.class, turnoId))
				.isZero();
		assertThat(contar("turno-reservado:" + turnoId)).isZero();
	}

	@Test
	@DisplayName("una reserva rechazada no encola nada")
	void la_reserva_rechazada_no_encola() {
		Fixture fixture = fixtures.crear(1);
		conCorreo(fixture.personaA());
		conCorreo(fixture.personaB());
		reservar(fixture, fixture.personaA(), a(9, 0), null);

		assertThatThrownBy(() -> reservar(fixture, fixture.personaB(), a(9, 0), null))
				.isInstanceOf(SlotCompletoException.class);

		assertThat(contarDeLaOrganizacion(fixture))
				.as("solo el aviso de la reserva que entro")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el reintento con la misma clave de idempotencia no duplica el aviso")
	void el_reintento_no_duplica() {
		Fixture fixture = fixtures.crear(1);
		conCorreo(fixture.personaA());
		String clave = UUID.randomUUID().toString();

		TurnoView primero = reservar(fixture, fixture.personaA(), a(9, 0), clave);
		TurnoView segundo = reservar(fixture, fixture.personaA(), a(9, 0), clave);

		assertThat(segundo.id()).isEqualTo(primero.id());
		assertThat(contarDeLaOrganizacion(fixture)).isEqualTo(1);
	}

	@Test
	@DisplayName("reprogramar y cancelar encolan cada uno su aviso, sin el motivo")
	void reprogramar_y_cancelar_encolan() {
		Fixture fixture = fixtures.crear(1);
		conCorreo(fixture.personaA());
		TurnoView turno = reservar(fixture, fixture.personaA(), a(9, 0), null);

		TurnoView movido = cicloService.reprogramar(fixture.actor(), fixture.consultorioId(), turno.id(),
				new ReprogramacionCommand(a(10, 0), fixture.profesionalMembershipId(),
						"dolor lumbar agudo", turno.version()));
		cicloService.cancelar(fixture.actor(), fixture.consultorioId(), turno.id(),
				"internacion", movido.version());

		Map<String, Object> reprogramado = filaDe("turno-reprogramado:" + turno.id() + ":v" + movido.version());
		assertThat(reprogramado.get("tipo")).isEqualTo("TURNO_REPROGRAMADO");
		assertThat(reprogramado.get("turno_inicio")).isEqualTo("01/03/2027 a las 10:00");
		assertThat(String.valueOf(reprogramado.get("payload")))
				.contains("01/03/2027 a las 09:00")
				.doesNotContain("lumbar");

		Map<String, Object> cancelado = filaDe("turno-cancelado:" + turno.id());
		assertThat(cancelado.get("tipo")).isEqualTo("TURNO_CANCELADO");
		assertThat(String.valueOf(cancelado.get("payload"))).doesNotContain("internacion");

		assertThat(contarDeLaOrganizacion(fixture)).isEqualTo(3);
	}

	/**
	 * Defecto que E-5 evita y corrige en {@code AvisosDeClase}: "Secreto" contiene {@code secret},
	 * que el outbox rechaza. Mandado tal cual, el rechazo ocurria dentro de la transaccion y la
	 * reserva moria por el nombre de la sede (RN-M26-001).
	 */
	@Test
	@DisplayName("una sede cuyo nombre el outbox rechazaria no impide reservar: el aviso sale sin el")
	void la_sede_con_nombre_rechazado_no_rompe_la_reserva() {
		Fixture fixture = fixtures.crear(1);
		conCorreo(fixture.personaA());
		jdbc.update("UPDATE consultorio SET name = ? WHERE id = ?",
				"Jardin Secreto " + fixture.consultorioId(), fixture.consultorioId());

		TurnoView turno = reservar(fixture, fixture.personaA(), a(9, 0), null);

		Map<String, Object> fila = filaDe("turno-reservado:" + turno.id());
		assertThat(String.valueOf(fila.get("payload")))
				.doesNotContain("consultorioNombre")
				.contains("turnoInicio");
	}

	// =================================================================================
	// Una operacion, un aviso: cambio de sede y reprogramacion con otro profesional
	// =================================================================================

	/**
	 * El defecto: el cambio de sede cancela el original y reserva uno nuevo, y cada mitad avisaba por
	 * su cuenta. El paciente recibia "se cancelo tu turno" y "te reservamos un turno". Ahora es UN
	 * aviso con lo que cambio, y ninguno de cancelacion ni de reserva del turno nuevo.
	 */
	@Test
	@DisplayName("cambio de sede: UN aviso con sede, hora y profesional de antes y ahora; ni cancelacion ni reserva")
	void el_traslado_encola_un_solo_aviso() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.agregarSede(sedeA);
		String email = conCorreo(sedeA.personaA());
		TurnoView original = reservar(sedeA, sedeA.personaA(), a(9, 0), null);

		TrasladoView traslado = traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), original.id(),
				new TrasladoCommand(sedeB.consultorioId(), sedeB.ofertaId(), a(10, 0),
						sedeB.profesionalMembershipId(), null, "dolor lumbar agudo", original.version()));

		long nuevoId = traslado.nuevo().id();
		assertThat(contar("turno-cancelado:" + original.id())).as("sin aviso de cancelacion").isZero();
		assertThat(contar("turno-reservado:" + nuevoId)).as("sin aviso de reserva del nuevo").isZero();
		Map<String, Object> fila = filaDe("turno-trasladado:" + nuevoId + ":desde:" + original.id());
		assertThat(fila.get("tipo")).isEqualTo("TURNO_REPROGRAMADO");
		assertThat(fila.get("destinatario")).isEqualTo(email);
		assertThat(fila.get("turno_inicio")).isEqualTo("01/03/2027 a las 10:00");
		String nombreA = jdbc.queryForObject("SELECT name FROM consultorio WHERE id = ?", String.class,
				sedeA.consultorioId());
		String nombreB = jdbc.queryForObject("SELECT name FROM consultorio WHERE id = ?", String.class,
				sedeB.consultorioId());
		assertThat(String.valueOf(fila.get("payload")))
				.contains("\"consultorioNombreAnterior\": \"" + nombreA + "\"")
				.contains("\"consultorioNombre\": \"" + nombreB + "\"")
				.contains("01/03/2027 a las 09:00")
				.contains("\"cambioProfesional\": \"si\"")
				.doesNotContain("lumbar");
		// La reserva del original (antes del traslado) y el unico aviso del traslado.
		assertThat(contarDeLaOrganizacion(sedeA)).isEqualTo(2);
	}

	@Test
	@DisplayName("cambio de sede sin lugar en el destino: rollback y ningun aviso nuevo")
	void el_traslado_fallido_no_encola() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.agregarSede(sedeA);
		conCorreo(sedeA.personaA());
		conCorreo(sedeB.personaB());
		TurnoView original = reservar(sedeA, sedeA.personaA(), a(11, 0), null);
		reservar(sedeB, sedeB.personaB(), a(11, 0), null);

		assertThatThrownBy(() -> traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), original.id(),
				new TrasladoCommand(sedeB.consultorioId(), sedeB.ofertaId(), a(11, 0),
						sedeB.profesionalMembershipId(), null, "Cambio", original.version())))
				.isInstanceOfAny(SlotCompletoException.class, RecursoOcupadoException.class);

		assertThat(contar("turno-cancelado:" + original.id())).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM notification_outbox
				 WHERE organization_id = ? AND clave_idempotente LIKE 'turno-trasladado:%'
				""", Long.class, sedeA.organizationId())).isZero();
		assertThat(contarDeLaOrganizacion(sedeA)).as("solo las dos reservas").isEqualTo(2);
	}

	@Test
	@DisplayName("reintentar el mismo cambio de sede no encola un segundo aviso")
	void el_reintento_del_traslado_no_duplica() {
		Fixture sedeA = fixtures.crear(1);
		Fixture sedeB = fixtures.agregarSede(sedeA);
		conCorreo(sedeA.personaA());
		TurnoView original = reservar(sedeA, sedeA.personaA(), a(9, 0), null);
		TrasladoCommand mismo = new TrasladoCommand(sedeB.consultorioId(), sedeB.ofertaId(), a(10, 0),
				sedeB.profesionalMembershipId(), null, "Cambio", original.version());

		traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), original.id(), mismo);
		assertThatThrownBy(() -> traslados.trasladar(sedeA.actor(), sedeA.consultorioId(), original.id(), mismo))
				.isInstanceOf(RuntimeException.class);

		assertThat(contarDeLaOrganizacion(sedeA)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turno WHERE turno_origen_id = ?", Long.class,
				original.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("reprogramar con otro profesional: UN aviso que dice hora y profesional, sin nombrarlo")
	void la_reprogramacion_con_otro_profesional_es_un_aviso() {
		Fixture fixture = fixtures.crear(1);
		long otro = fixtures.agregarProfesional(fixture);
		conCorreo(fixture.personaA());
		TurnoView turno = reservar(fixture, fixture.personaA(), a(9, 0), null);

		TurnoView movido = cicloService.reprogramar(fixture.actor(), fixture.consultorioId(), turno.id(),
				new ReprogramacionCommand(a(10, 0), otro, "Cambio", turno.version()));

		Map<String, Object> fila = filaDe("turno-reprogramado:" + turno.id() + ":v" + movido.version());
		assertThat(String.valueOf(fila.get("payload")))
				.contains("\"cambioProfesional\": \"si\"")
				.contains("01/03/2027 a las 09:00")
				.doesNotContain("consultorioNombreAnterior");
		assertThat(contarDeLaOrganizacion(fixture)).as("la reserva y un aviso de cambio").isEqualTo(2);
	}

	// =================================================================================

	private TurnoView reservar(Fixture fixture, long personaId, Instant inicio, String clave) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), clave))
				.turno();
	}

	private static Instant a(int hora, int minuto) {
		return LUNES.atTime(hora, minuto).atZone(ZONA).toInstant();
	}

	private String conCorreo(long personaId) {
		String email = "paciente-" + personaId + "@ejemplo.test";
		jdbc.update("UPDATE persona SET email = ? WHERE id = ?", email, personaId);
		return email;
	}

	private Map<String, Object> filaDe(String clave) {
		List<Map<String, Object>> filas = jdbc.queryForList("""
				SELECT tipo, destinatario, organization_id, referencia_token_id,
				       CAST(payload_sanitizado AS text) AS payload,
				       payload_sanitizado ->> 'turnoInicio' AS turno_inicio
				  FROM notification_outbox
				 WHERE clave_idempotente = ?
				""", clave);
		assertThat(filas).as("una sola fila para %s", clave).hasSize(1);
		return filas.getFirst();
	}

	private long contar(String clave) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM notification_outbox WHERE clave_idempotente = ?", Long.class, clave);
	}

	private long contarDeLaOrganizacion(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM notification_outbox
				 WHERE organization_id = ? AND tipo LIKE 'TURNO_%'
				""", Long.class, fixture.organizationId());
	}
}
