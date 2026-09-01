package com.akine.scheduling;

import com.akine.TestcontainersConfiguration;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.TurnoView;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El criterio de aceptacion de AKINE-05.02: <b>una sola reserva gana</b>.
 *
 * <h2>Por que esto no puede ser un test unitario</h2>
 *
 * <p>Un test con mocks verificaria el orden de las llamadas, no que dos transacciones se esperen de
 * verdad. Lo que decide la correctitud aca es el {@code FOR UPDATE} sobre {@code agenda_sede} y el
 * aislamiento de InnoDB, y eso solo lo puede contestar MySQL real: <b>hasta el 31/08/2026 este test
 * no se podia correr en esta maquina</b> porque el motor de Docker no arrancaba.
 *
 * <h2>Lo que ningun unique de la base puede garantizar</h2>
 *
 * <p>El solapamiento. Un unique compara igualdad y dos turnos se pisan cuando sus INTERVALOS se
 * cruzan; un turno de 09:00 a 10:00 y otro de 09:30 a 10:00 no comparten un solo valor de columna.
 * MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL— asi que la regla la hace cumplir la
 * validacion de {@code TurnoService} bajo el lock, y este test es la unica prueba de que funciona.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class TurnoConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/**
	 * Un lunes fijo y lejano en el futuro, para que el bloque de disponibilidad lo cubra siempre.
	 *
	 * <p>Fecha fija y no "el proximo lunes": un escenario que se mueve con el reloj de pared falla
	 * el dia que el calendario lo alcanza, sin que haya nada roto. Es la misma leccion que el
	 * fixture de {@code DisponibilidadIT} documenta para {@code valid_from}.
	 */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 1);

	@Autowired private TurnoService turnoService;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("dos reservas concurrentes del mismo slot individual: una entra y la otra recibe 409")
	void dos_reservas_del_mismo_slot_no_pasan_las_dos() {
		Fixture fixture = crearFixture(1);

		Instant inicio = LUNES.atTime(9, 0).atZone(ZoneId.of(ZONA)).toInstant();

		// Dos PACIENTES distintos, el mismo hueco. Con el mismo paciente el desenlace tambien
		// seria uno solo, pero por otra razon, y el test no distinguiria cual de las dos reglas
		// actuo.
		Callable<TurnoView> primera = () -> reservar(fixture, fixture.personaA(), inicio);
		Callable<TurnoView> segunda = () -> reservar(fixture, fixture.personaB(), inicio);

		List<Desenlace> desenlaces = enParalelo(List.of(primera, segunda));

		long exitos = desenlaces.stream().filter(d -> !d.fallo()).count();
		long rechazos = desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), SlotCompletoException.class)
						|| causaEs(d.error(), RecursoOcupadoException.class))
				.count();

		assertThat(exitos)
				.as("exactamente una reserva entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(rechazos)
				.as("la otra recibe un 409 de agenda, no un error generico. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		assertThat(contarTurnosVivos(fixture))
				.as("y en la base queda UNA sola fila viva para ese slot")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("un slot grupal admite hasta su capacidad y rechaza el que sobra")
	void el_grupal_admite_hasta_su_capacidad() {
		// Capacidad 2 y tres reservas a la vez. Prueba las dos mitades de la misma regla: que el
		// control de solapamiento NO rechace a los companeros de grupo —comparten profesional,
		// oferta y hora a proposito— y que el cupo si corte al tercero.
		Fixture fixture = crearFixture(2);
		Instant inicio = LUNES.atTime(9, 0).atZone(ZoneId.of(ZONA)).toInstant();

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> reservar(fixture, fixture.personaA(), inicio),
				() -> reservar(fixture, fixture.personaB(), inicio),
				() -> reservar(fixture, fixture.personaC(), inicio)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("entran dos, que es la capacidad. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), SlotCompletoException.class))
				.count())
				.as("el tercero recibe slot-completo. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(contarTurnosVivos(fixture)).isEqualTo(2);
	}

	@Test
	@DisplayName("la misma clave de idempotencia no crea dos turnos, ni siquiera en paralelo")
	void la_idempotencia_aguanta_el_doble_click() {
		// El doble click real: el mismo cliente manda dos veces el mismo pedido con la misma
		// clave. Las dos peticiones tienen que devolver EL MISMO turno, no dos.
		Fixture fixture = crearFixture(1);
		Instant inicio = LUNES.atTime(9, 0).atZone(ZoneId.of(ZONA)).toInstant();
		String clave = UUID.randomUUID().toString();

		Callable<TurnoView> click = () -> turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(fixture.personaA(), inicio, fixture.profesionalMembershipId(), clave))
				.turno();

		List<Desenlace> desenlaces = enParalelo(List.of(click, click));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("las dos peticiones responden bien. Desenlaces: %s", desenlaces)
				.isEqualTo(2);
		assertThat(desenlaces.stream().map(d -> d.turno().id()).distinct())
				.as("y las dos devuelven el mismo turno")
				.hasSize(1);
		assertThat(contarTurnosVivos(fixture))
				.as("en la base hay una sola fila")
				.isEqualTo(1);
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/**
	 * Corre las tareas de verdad en paralelo y devuelve el desenlace de cada una.
	 *
	 * <p>La {@link CyclicBarrier} es lo que hace real la carrera: sin ella, la primera tarea suele
	 * terminar antes de que la segunda arranque y el test pasaria sin haber probado nada. Con la
	 * barrera, las dos llegan al servicio dentro de la misma ventana de microsegundos.
	 *
	 * <p>Ninguna excepcion se propaga: se recogen las dos y el test decide cuantos exitos y cuantos
	 * rechazos esperaba. Dejar que una explote perderia el desenlace de la otra, que es justamente
	 * la mitad de la informacion.
	 */
	private static List<Desenlace> enParalelo(List<Callable<TurnoView>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<TurnoView> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace(null, error);
					}
				}));
			}
			List<Desenlace> desenlaces = new ArrayList<>();
			for (Future<Desenlace> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception fallo) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", fallo);
		}
	}

	/** Recorre la cadena de causas: Spring envuelve las excepciones de servicio. */
	private static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	private record Desenlace(TurnoView turno, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(turnoId=" + turno.id() + ")";
		}
	}

	private TurnoView reservar(Fixture fixture, long personaId, Instant inicio) {
		return turnoService.reservar(
				fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
				new ReservaCommand(personaId, inicio, fixture.profesionalMembershipId(), null))
				.turno();
	}

	/**
	 * Cuenta los turnos vivos de la oferta del fixture.
	 *
	 * <p><b>Sin filtrar por {@code inicio}, y no es descuido.</b> Enlazar un {@code Instant} como
	 * parametro de JDBC lo convierte con la zona de la sesion, que no es necesariamente la misma
	 * conversion que hace Hibernate al guardar: la consulta devolvia cero contra filas que existian.
	 * Cada test crea su propia organizacion y su propia oferta, asi que el predicado por hora no
	 * agrega precision — solo una forma de que el test mienta.
	 */
	private long contarTurnosVivos(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM turno
				 WHERE organization_id = ? AND oferta_id = ? AND deleted_at IS NULL
				""", Long.class, fixture.organizationId(), fixture.ofertaId());
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long ofertaId,
			long profesionalMembershipId,
			long personaA,
			long personaB,
			long personaC,
			OperatingActor actor) {
	}

	private Fixture crearFixture(int capacidad) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);

		long adminAccountId = insertarCuenta("admin-" + sufijo);
		insertarMembership(organizationId, consultorioId, adminAccountId, "ORG_ADMIN");

		long profesionalAccountId = insertarCuenta("pro-" + sufijo);
		long profesionalMembershipId =
				insertarMembership(organizationId, consultorioId, profesionalAccountId, "PROFESIONAL");

		long servicioId = insertarServicio(sufijo);
		long ofertaId = insertarOferta(organizationId, consultorioId, servicioId, capacidad, sufijo);
		insertarHabilitacion(organizationId, consultorioId, ofertaId, profesionalMembershipId);
		// Lunes de 09:00 a 13:00. El slot de las 09:00 cae adentro con cualquier duracion sensata.
		insertarDisponibilidad(organizationId, consultorioId, profesionalMembershipId);

		return new Fixture(
				organizationId, consultorioId, ofertaId, profesionalMembershipId,
				insertarPaciente(organizationId, adminAccountId, "A" + sufijo),
				insertarPaciente(organizationId, adminAccountId, "B" + sufijo),
				insertarPaciente(organizationId, adminAccountId, "C" + sufijo),
				new OperatingActor(adminAccountId, false, organizationId, consultorioId));
	}

	private long insertarOrganization(String sufijo) {
		String slug = "turno-it-" + sufijo;
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro Sintetico " + sufijo, slug, ZONA);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String sufijo) {
		String nombre = "Sede Sintetica " + sufijo;
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, nombre, ZONA);
		return jdbc.queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, nombre);
	}

	private long insertarCuenta(String sufijo) {
		String email = "turno-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	/** {@code valid_from} cinco anos atras: ver el javadoc del fixture de {@code DisponibilidadIT}. */
	private long insertarMembership(
			long organizationId, long consultorioId, long accountId, String rol) {

		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 'ACTIVA',
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId, rol);
		return jdbc.queryForObject("""
				SELECT id FROM membership
				 WHERE organization_id = ? AND consultorio_id = ? AND account_id = ?
				""", Long.class, organizationId, consultorioId, accountId);
	}

	private long insertarServicio(String sufijo) {
		String codigo = "TURNO-IT-" + sufijo.toUpperCase();
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", codigo, "Servicio Sintetico " + sufijo);
		return jdbc.queryForObject("SELECT id FROM servicio WHERE codigo = ?", Long.class, codigo);
	}

	/**
	 * {@code requiere_espacio = 0} a proposito: lo que este test mide es la exclusion sobre el
	 * profesional y el cupo. Meter un box agregaria una segunda fuente de rechazo y el test no
	 * podria decir cual de las dos actuo.
	 */
	private long insertarOferta(
			long organizationId, long consultorioId, long servicioId, int capacidad, String sufijo) {

		String nombre = "Oferta Sintetica " + sufijo;
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 60, ?, 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, servicioId, nombre,
				capacidad > 1 ? "GRUPAL" : "INDIVIDUAL", capacidad);
		return jdbc.queryForObject("""
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", Long.class, organizationId, nombre);
	}

	private void insertarHabilitacion(
			long organizationId, long consultorioId, long ofertaId, long membershipId) {

		jdbc.update("""
				INSERT INTO oferta_profesional_habilitado
				       (organization_id, consultorio_id, oferta_id, membership_id, valid_from,
				        active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, ofertaId, membershipId);
	}

	private void insertarDisponibilidad(
			long organizationId, long consultorioId, long membershipId) {

		jdbc.update("""
				INSERT INTO profesional_disponibilidad
				       (organization_id, consultorio_id, membership_id, dia_semana,
				        hora_desde, hora_hasta, vigencia_desde, active, version,
				        created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, membershipId,
				DayOfWeek.MONDAY.getValue(), LocalTime.of(9, 0), LocalTime.of(13, 0));
	}

	/**
	 * Persona MAS perfil de paciente: {@code TurnoService} exige el perfil vigente y no lo crea.
	 *
	 * <p>RF-M07-010 sostenido por la estructura: son dos tablas y no hay ninguna columna
	 * {@code es_paciente}. Un fixture que inserte solo la persona hace fallar la reserva con
	 * {@code persona-sin-perfil-paciente}, que es el comportamiento correcto.
	 */
	private long insertarPaciente(long organizationId, long activadoPor, String sufijo) {
		String apellido = "Paciente" + sufijo;
		jdbc.update("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, apellido, apellido.toUpperCase());
		long personaId = jdbc.queryForObject("""
				SELECT id FROM persona WHERE organization_id = ? AND apellido = ?
				""", Long.class, organizationId, apellido);

		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, activadoPor);
		return personaId;
	}
}
