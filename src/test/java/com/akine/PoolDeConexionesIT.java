package com.akine;

import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroService;
import com.akine.billing.domain.MedioDePago;
import com.akine.clinical.application.AdjuntoClinicoAltaCommand;
import com.akine.clinical.application.AdjuntoClinicoService;
import com.akine.clinical.application.HistoriaClinicaService;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.domain.exception.AdjuntoClinicoNoDisponibleException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.application.AdjuntoAltaCommand;
import com.akine.person.application.AdjuntoService;
import com.akine.person.domain.CategoriaAdjunto;
import com.akine.scheduling.AgendaFixtures;
import com.akine.scheduling.AgendaFixtures.Desenlace;
import com.akine.scheduling.AgendaFixtures.Fixture;
import com.akine.scheduling.application.ReservaCommand;
import com.akine.scheduling.application.TurnoService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.CannotCreateTransactionException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import static com.akine.scheduling.AgendaFixtures.causaEs;
import static com.akine.scheduling.AgendaFixtures.enParalelo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El pool de conexiones no se traba con escrituras simultaneas (G-10, docs/carga.md §6.1).
 *
 * <h2>Lo que reproduce</h2>
 *
 * <p>Hasta el 11/10/2026 la reserva y el cobro abrian la transaccion de la fila-lock
 * ({@code agenda_sede}, {@code comprobante_numerador}) como {@code REQUIRES_NEW} <b>desde
 * adentro</b> de su propia transaccion: cada pedido retenia una conexion y pedia otra. Con un pool
 * de N conexiones, N escrituras simultaneas tomaban todas y se quedaban esperando la segunda. La
 * prueba de carga lo vio con el pool de 10 y menos de 100 usuarios: el backend se trababa a los 20
 * segundos.
 *
 * <p>Aca el pool es de <b>2</b> y la espera por una conexion de <b>10 segundos</b> —con 3, la
 * segunda ronda de cobros fallaba a veces sin ningun anidamiento: ocho cobros serializados en el
 * numerador pueden esperar mas que eso—, y se lanzan {@value #PEDIDOS} escrituras a la vez con una barrera: con el codigo viejo, las dos primeras
 * toman las dos conexiones, piden una tercera que no existe y mueren por
 * {@code CannotCreateTransactionException}; el resto hereda la cola. Con la fila asegurada ANTES
 * de abrir la transaccion de negocio cada pedido usa una conexion por vez, y lo peor que puede
 * pasar es esperar su turno.
 *
 * <p>Se corre dos veces por camino: con la fila-lock todavia sin crear (la primera escritura de una
 * sede nueva) y con la fila ya creada, que es el caso del 99,9 % de los pedidos.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"spring.datasource.hikari.maximum-pool-size=2",
				"spring.datasource.hikari.minimum-idle=2",
				"spring.datasource.hikari.connection-timeout=10000"
		})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PoolDeConexionesIT {

	private static final int PEDIDOS = 8;

	/** Lunes fijo y lejano: la disponibilidad del fixture cubre los lunes de 09:00 a 13:00. */
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 1);

	private static final BigDecimal IMPORTE = new BigDecimal("1000.00");

	@Autowired private TurnoService turnoService;
	@Autowired private CobroService cobroService;
	@Autowired private HistoriaClinicaService historiaClinicaService;
	@Autowired private AdjuntoClinicoService adjuntoClinicoService;
	@Autowired private AdjuntoService adjuntoService;
	@Autowired private PermissionGuard permissionGuard;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private AgendaFixtures fixtures;

	@BeforeEach
	void prepararFixtures() throws Exception {
		fixtures = new AgendaFixtures(jdbc);
		assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize())
				.as("el escenario solo prueba algo con un pool mas chico que los pedidos")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("reservas simultaneas en una sede nueva y en una con agenda: ninguna se queda sin conexion")
	void las_reservas_no_traban_el_pool() {
		// Capacidad = PEDIDOS: todas caben en el mismo horario y ninguna choca por agenda. Lo unico
		// que puede hacerlas fallar es la conexion.
		Fixture fixture = fixtures.crear(PEDIDOS);
		Instant nueve = LUNES.atTime(9, 0).atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();
		Instant diez = LUNES.atTime(10, 0).atZone(ZoneId.of(AgendaFixtures.ZONA)).toInstant();

		// Primera ronda: agenda_sede todavia no existe para esta sede.
		exigirSinTraba(enParalelo(reservas(fixture, nueve)), "sin la fila de agenda_sede");
		// Segunda ronda: la fila ya existe y solo se bloquea.
		exigirSinTraba(enParalelo(reservas(fixture, diez)), "con la fila de agenda_sede creada");
	}

	@Test
	@DisplayName("cobros simultaneos en una sede nueva y en una con numerador: ninguno se queda sin conexion")
	void los_cobros_no_traban_el_pool() {
		Fixture fixture = fixtures.crear(1);

		exigirSinTraba(enParalelo(cobros(fixture)), "sin la fila de comprobante_numerador");
		exigirSinTraba(enParalelo(cobros(fixture)), "con la fila de comprobante_numerador creada");

		// Y la numeracion sigue siendo correlativa y sin huecos: el arreglo no la toco.
		List<Integer> numeros = jdbc.queryForList("""
				SELECT comprobante_numero FROM cobro
				 WHERE organization_id = ? AND consultorio_id = ?
				 ORDER BY comprobante_numero
				""", Integer.class, fixture.organizationId(), fixture.consultorioId());
		assertThat(numeros).containsExactlyElementsOf(
				IntStream.rangeClosed(1, 2 * PEDIDOS).boxed().toList());
	}

	// =================================================================================
	// Los anidamientos que no eran filas-lock (docs/carga.md §8)
	// =================================================================================
	//
	// Cada uno abria un REQUIRES_NEW con la conexion de la transaccion de negocio tomada. No hace
	// falta ninguna carrera para trabarlos: con el pool de 2 y ocho pedidos a la vez, los dos que
	// consiguen conexion piden una tercera que no existe y mueren cuando vence la espera.

	@Test
	@DisplayName("primeras aperturas simultaneas de historia clinica, dos por paciente: ninguna se queda sin conexion")
	void la_primera_apertura_de_historia_clinica_no_traba_el_pool() {
		Fixture fixture = fixtures.crear(1);
		long admin = fixture.actor().accountId();
		long[] pacientes = new long[PEDIDOS / 2];
		for (int i = 0; i < pacientes.length; i++) {
			pacientes[i] = insertarPaciente(fixture.organizationId(), admin);
		}

		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			long persona = pacientes[i % pacientes.length];
			tareas.add(() -> historiaClinicaService.asegurar(fixture.organizationId(), persona, admin)
					.getId());
		}
		List<Desenlace<Object>> desenlaces = enParalelo(tareas);
		exigirSinTraba(desenlaces, "apertura de historia clinica");

		// Y la apertura sigue siendo idempotente: dos pedidos por paciente, una historia y un
		// evento de apertura por paciente, y ninguno falla.
		assertThat(desenlaces).as("ninguna apertura falla: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM historia_clinica WHERE organization_id = ?",
				Integer.class, fixture.organizationId())).isEqualTo(pacientes.length);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'HISTORIA_CLINICA_OPENED'
				""", Integer.class, fixture.organizationId())).isEqualTo(pacientes.length);
	}

	@Test
	@DisplayName("subidas simultaneas de adjuntos clinicos, dos por contenido: ninguna se queda sin conexion")
	void la_subida_de_adjuntos_clinicos_no_traba_el_pool() {
		Fixture fixture = fixtures.crear(1);
		long profesional = insertarProfesional(fixture);
		long persona = insertarPaciente(fixture.organizationId(), profesional);
		long historia = insertarHistoria(fixture.organizationId(), persona, profesional);
		var actor = new com.akine.clinical.application.OperatingActor(
				profesional, false, fixture.organizationId(), fixture.consultorioId());

		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			byte[] contenido = pdf("estudio " + (i % (PEDIDOS / 2)) + " " + historia);
			tareas.add(() -> adjuntoClinicoService.subir(actor, historia,
					new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, null, null,
							"estudio.pdf", "application/pdf", contenido),
					"Prueba de pool").creado());
		}
		List<Desenlace<Object>> desenlaces = enParalelo(tareas);
		exigirSinTraba(desenlaces, "subida de adjunto clinico");

		assertThat(desenlaces).as("ninguna subida falla: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().filter(d -> Boolean.TRUE.equals(d.valor())).count())
				.as("una crea por contenido; la otra es la respuesta idempotente: %s", desenlaces)
				.isEqualTo(PEDIDOS / 2);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM adjunto_clinico
				 WHERE historia_clinica_id = ? AND estado = 'DISPONIBLE' AND deleted_at IS NULL
				""", Integer.class, historia)).isEqualTo(PEDIDOS / 2);
	}

	@Test
	@DisplayName("descargas simultaneas de adjuntos clinicos sin binario: todas marcan NO_DISPONIBLE sin trabar el pool")
	void la_descarga_sin_binario_no_traba_el_pool() {
		Fixture fixture = fixtures.crear(1);
		long profesional = insertarProfesional(fixture);
		long persona = insertarPaciente(fixture.organizationId(), profesional);
		long historia = insertarHistoria(fixture.organizationId(), persona, profesional);
		var actor = new com.akine.clinical.application.OperatingActor(
				profesional, false, fixture.organizationId(), fixture.consultorioId());

		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			long adjunto = insertarAdjuntoSinBinario(fixture, historia, profesional, i);
			tareas.add(() -> adjuntoClinicoService.contenido(actor, historia, adjunto,
					"Prueba de pool"));
		}
		List<Desenlace<Object>> desenlaces = enParalelo(tareas);
		exigirRechazoSinTraba(desenlaces, AdjuntoClinicoNoDisponibleException.class,
				"descarga sin binario");

		// La marca sobrevive al 409: es lo que la escritura aparte protege.
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM adjunto_clinico
				 WHERE historia_clinica_id = ? AND estado = 'NO_DISPONIBLE'
				""", Integer.class, historia)).isEqualTo(PEDIDOS);
	}

	@Test
	@DisplayName("subidas simultaneas de adjuntos administrativos, dos por contenido: ninguna se queda sin conexion")
	void la_subida_de_adjuntos_administrativos_no_traba_el_pool() {
		Fixture fixture = fixtures.crear(1);
		var actor = new com.akine.person.application.OperatingActor(
				fixture.actor().accountId(), false, fixture.organizationId(), fixture.consultorioId());

		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			byte[] contenido = pdf("credencial " + (i % (PEDIDOS / 2)) + " " + fixture.personaA());
			tareas.add(() -> adjuntoService.subir(actor, fixture.personaA(), new AdjuntoAltaCommand(
					CategoriaAdjunto.CREDENCIAL_COBERTURA, "Credencial", "credencial.pdf",
					"application/pdf", contenido)).creado());
		}
		List<Desenlace<Object>> desenlaces = enParalelo(tareas);
		exigirSinTraba(desenlaces, "subida de adjunto administrativo");

		assertThat(desenlaces).as("ninguna subida falla: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().filter(d -> Boolean.TRUE.equals(d.valor())).count())
				.as("una crea por contenido: %s", desenlaces)
				.isEqualTo(PEDIDOS / 2);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM adjunto_administrativo
				 WHERE persona_id = ? AND estado = 'DISPONIBLE' AND deleted_at IS NULL
				""", Integer.class, fixture.personaA())).isEqualTo(PEDIDOS / 2);
	}

	@Test
	@DisplayName("rechazos de permiso simultaneos: todos quedan auditados y ninguno se queda sin conexion")
	void los_rechazos_de_permiso_no_traban_el_pool() {
		Fixture fixture = fixtures.crear(1);
		long profesional = insertarProfesional(fixture);

		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			// El PROFESIONAL no administra colaboradores: 403, que se audita.
			tareas.add(() -> permissionGuard.requirePermission(PermissionQuery.of(
					profesional, "colaborador:manage", fixture.organizationId(), Instant.now())));
		}
		List<Desenlace<Object>> desenlaces = enParalelo(tareas);
		exigirRechazoSinTraba(desenlaces, PermissionDeniedException.class, "rechazo de permiso");

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND actor_account_id = ?
				   AND event_type = 'PERMISSION_DENIED'
				""", Integer.class, fixture.organizationId(), profesional))
				.as("cada 403 deja su fila aunque el pedido falle")
				.isEqualTo(PEDIDOS);
	}

	private static void exigirRechazoSinTraba(
			List<Desenlace<Object>> desenlaces, Class<? extends Throwable> esperado, String escenario) {
		assertThat(desenlaces)
				.as("%s: todos terminan en %s y ninguno por falta de conexion. Desenlaces: %s",
						escenario, esperado.getSimpleName(), desenlaces)
				.allMatch(d -> d.fallo() && causaEs(d.error(), esperado)
						&& !causaEs(d.error(), CannotCreateTransactionException.class)
						&& !causaEs(d.error(), SQLTransientConnectionException.class));
	}

	private List<Callable<Object>> reservas(Fixture fixture, Instant inicio) {
		// Tres pacientes para ocho pedidos: los que repiten paciente pueden recibir un 409 de
		// agenda, que es negocio y no traba. Lo que se mira es que nadie muera por la conexion.
		long[] personas = {fixture.personaA(), fixture.personaB(), fixture.personaC()};
		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			long persona = personas[i % personas.length];
			tareas.add(() -> turnoService.reservar(
					fixture.actor(), fixture.consultorioId(), fixture.ofertaId(),
					new ReservaCommand(persona, inicio, fixture.profesionalMembershipId(), null)));
		}
		return tareas;
	}

	private List<Callable<Object>> cobros(Fixture fixture) {
		var actor = new com.akine.billing.application.OperatingActor(
				fixture.actor().accountId(), false, fixture.organizationId(), fixture.consultorioId());
		List<Callable<Object>> tareas = new ArrayList<>();
		for (int i = 0; i < PEDIDOS; i++) {
			// Anticipo por transferencia: no necesita deuda ni caja abierta, y pide comprobante.
			tareas.add(() -> cobroService.registrar(actor, fixture.consultorioId(), new CobroCommand(
					fixture.personaA(), IMPORTE,
					List.of(new CobroCommand.MedioPedido(MedioDePago.TRANSFERENCIA, IMPORTE, null)),
					List.of(), null, IMPORTE, "ARS")));
		}
		return tareas;
	}

	private static void exigirSinTraba(List<Desenlace<Object>> desenlaces, String escenario) {
		List<Desenlace<Object>> sinConexion = desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), CannotCreateTransactionException.class)
						|| causaEs(d.error(), SQLTransientConnectionException.class))
				.toList();
		assertThat(sinConexion)
				.as("%s: ningun pedido puede quedarse sin conexion. Desenlaces: %s", escenario, desenlaces)
				.isEmpty();
		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("%s: y alguno tiene que haber escrito. Desenlaces: %s", escenario, desenlaces)
				.isPositive();
	}

	// =================================================================================
	// Inserciones de los escenarios clinicos y de padron
	// =================================================================================

	private long insertarProfesional(Fixture fixture) {
		String email = "pool-it-" + UUID.randomUUID().toString().substring(0, 12) + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		long cuenta = jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), cuenta);
		return cuenta;
	}

	private long insertarPaciente(long organizationId, long activadoPor) {
		String apellido = "Pool" + UUID.randomUUID().toString().substring(0, 12);
		jdbc.update("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, apellido, apellido.toUpperCase());
		long persona = jdbc.queryForObject(
				"SELECT id FROM persona WHERE organization_id = ? AND apellido = ?",
				Long.class, organizationId, apellido);
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, persona, activadoPor);
		return persona;
	}

	private long insertarHistoria(long organizationId, long persona, long abiertaPor) {
		jdbc.update("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, persona, abiertaPor);
		return jdbc.queryForObject("""
				SELECT id FROM historia_clinica WHERE organization_id = ? AND persona_id = ?
				""", Long.class, organizationId, persona);
	}

	/** Una fila DISPONIBLE cuyo binario el almacenamiento no tiene: la descarga tiene que marcarla. */
	private long insertarAdjuntoSinBinario(Fixture fixture, long historia, long subidoPor, int n) {
		String clave = UUID.randomUUID().toString().replace("-", "");
		jdbc.update("""
				INSERT INTO adjunto_clinico (organization_id, historia_clinica_id, consultorio_id,
				                             categoria, nombre_archivo, content_type, tamano_bytes,
				                             checksum_sha256, storage_key, estado, subido_por,
				                             subido_en, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'ESTUDIO', 'perdido.pdf', 'application/pdf', 10, ?, ?, 'DISPONIBLE',
				        ?, UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), historia, fixture.consultorioId(),
				String.format("%064d", n), clave, subidoPor);
		return jdbc.queryForObject(
				"SELECT id FROM adjunto_clinico WHERE storage_key = ?", Long.class, clave);
	}

	/** Un PDF sintetico: la lista blanca mira la firma {@code %PDF-} del byte cero. */
	private static byte[] pdf(String marca) {
		return ("%PDF-1.7\n% " + marca + "\n").getBytes(StandardCharsets.UTF_8);
	}
}
