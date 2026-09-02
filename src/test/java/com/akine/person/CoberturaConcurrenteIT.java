package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.person.application.CoberturaAltaCommand;
import com.akine.person.application.CoberturaPacienteService;
import com.akine.person.application.CoberturaView;
import com.akine.person.application.OperatingActor;
import com.akine.person.domain.TipoCobertura;
import com.akine.person.domain.exception.CoberturaPrincipalSuperpuestaException;
import com.akine.person.domain.exception.CoberturaSuperpuestaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
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
 * Las dos reglas de solapamiento de AKINE-03.04, ejercidas por dos hilos contra MySQL real.
 *
 * <h2>Por que esto no puede ser un test unitario</h2>
 *
 * <p>{@code CoberturaPacienteServiceTest} verifica con dobles que {@code asegurar} corre antes de
 * {@code lockByScope} y que la comprobacion rechaza. Eso prueba el <b>orden de las llamadas</b>, no
 * que dos transacciones se esperen de verdad. Las dos reglas de esta etapa son <b>lee, valida y
 * escribe</b>: sin serializacion las dos lecturas ocurren antes de las dos escrituras, las dos ven
 * una base sin conflicto y las dos pasan. Quien decide es el {@code FOR UPDATE} sobre
 * {@code cobertura_persona_lock} y el aislamiento de InnoDB, y eso solo lo contesta el motor.
 *
 * <p>Es la leccion de 05.02 —tres defectos de concurrencia que solo MySQL real destapo— y la de
 * 02.07, cuyo test miraba el codigo de respuesta y nunca simulaba dos guardados seguidos.
 *
 * <h2>Lo que ningun unique de la base puede garantizar</h2>
 *
 * <p>El solapamiento de intervalos. Una vigencia de enero a diciembre y otra de marzo a junio no
 * comparten un solo valor de columna, y MySQL 8.4 no tiene exclusion constraints. La regla la hace
 * cumplir {@code CoberturaPacienteService} bajo el lock, y este test es la unica prueba de que
 * funciona.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CoberturaConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Fechas fijas y lejanas: un escenario atado al reloj de pared falla el dia que lo alcanza. */
	private static final LocalDate DESDE = LocalDate.of(2027, 1, 1);
	private static final LocalDate HASTA = LocalDate.of(2027, 12, 31);

	@Autowired private CoberturaPacienteService servicio;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("dos altas concurrentes del mismo plan con vigencias solapadas: una entra y la otra recibe 409")
	void dos_altas_del_mismo_plan_no_pasan_las_dos() {
		Fixture fixture = crearFixture();

		// El MISMO plan y vigencias que se cruzan sin ser iguales: enero-diciembre contra
		// marzo-junio. Ningun unique las distingue; el que corta es el lock.
		Callable<CoberturaView> primera = () -> agregar(fixture, fixture.planId(), DESDE, HASTA, false);
		Callable<CoberturaView> segunda = () -> agregar(
				fixture, fixture.planId(), LocalDate.of(2027, 3, 1), LocalDate.of(2027, 6, 30), false);

		List<Desenlace<CoberturaView>> desenlaces = enParalelo(List.of(primera, segunda));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente una alta entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), CoberturaSuperpuestaException.class))
				.count())
				.as("la otra recibe el 409 de solapamiento, no un error generico. Desenlaces: %s",
						desenlaces)
				.isEqualTo(1);
		assertThat(contarActivas(fixture))
				.as("y en la base queda UNA sola cobertura activa")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("dos altas concurrentes de planes distintos marcadas principal: solo una queda principal")
	void solo_una_principal_sobrevive() {
		Fixture fixture = crearFixture();

		// Planes DISTINTOS: la regla de solapamiento por plan no aplica y las dos coberturas son
		// legitimas por separado. Lo unico que las enfrenta es que las dos se marcan principal
		// sobre la misma ventana. Sin lock las dos leerian "no hay ninguna principal" y entrarian.
		Callable<CoberturaView> primera = () -> agregar(fixture, fixture.planId(), DESDE, HASTA, true);
		Callable<CoberturaView> segunda =
				() -> agregar(fixture, fixture.planAlternativoId(), DESDE, HASTA, true);

		List<Desenlace<CoberturaView>> desenlaces = enParalelo(List.of(primera, segunda));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("una sola alta entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), CoberturaPrincipalSuperpuestaException.class))
				.count())
				.as("la otra recibe el 409 de principal solapada. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(contarPrincipalesActivas(fixture))
				.as("y en la base hay UNA sola cobertura principal")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el candado se crea una sola vez aunque cuatro altas lleguen juntas a una persona nueva")
	void el_candado_no_produce_deadlock_en_la_primera_rafaga() {
		// La trampa que este proyecto ya pago tres veces —agenda_sede, consultorio_calendario y
		// sesion_numerador—: crear la fila-lock perezosamente DENTRO de la transaccion que la
		// bloquea produce deadlock entre las primeras N escrituras concurrentes, y el try/catch no
		// salva porque atrapar una excepcion de persistencia no des-marca la transaccion. Cuatro
		// altas simultaneas sobre una persona SIN candado previo es el escenario que lo destapa.
		Fixture fixture = crearFixture();
		assertThat(contarCandados(fixture))
				.as("la persona arranca sin candado: es lo que hace valido al escenario")
				.isZero();

		List<Callable<CoberturaView>> rafaga = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			LocalDate desde = DESDE.plusYears(i);
			rafaga.add(() -> agregar(fixture, fixture.planId(), desde, desde.plusMonths(6), false));
		}

		List<Desenlace<CoberturaView>> desenlaces = enParalelo(rafaga);

		// Vigencias disjuntas: ninguna choca con ninguna, asi que las cuatro tienen que entrar. Si
		// alguna falla, lo que fallo es la creacion del candado y no la regla de negocio.
		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguna alta falla: las vigencias son disjuntas. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(contarActivas(fixture)).isEqualTo(4);
		assertThat(contarCandados(fixture))
				.as("y el candado quedo creado UNA sola vez")
				.isEqualTo(1);
	}

	// =================================================================================
	// Ejecucion concurrente
	// =================================================================================

	/** Barrera de salida para que las N tareas arranquen juntas y no en fila. */
	private static <T> List<Desenlace<T>> enParalelo(List<Callable<T>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace<T>>> futuros = new ArrayList<>();
			for (Callable<T> tarea : tareas) {
				futuros.add(pool.submit(() -> {
					salida.await(10, TimeUnit.SECONDS);
					try {
						return new Desenlace<>(tarea.call(), null);
					} catch (Exception error) {
						return new Desenlace<T>(null, error);
					}
				}));
			}
			List<Desenlace<T>> desenlaces = new ArrayList<>();
			for (Future<Desenlace<T>> futuro : futuros) {
				desenlaces.add(futuro.get(30, TimeUnit.SECONDS));
			}
			return desenlaces;
		} catch (Exception error) {
			throw new IllegalStateException("La ejecucion concurrente no pudo completarse", error);
		}
	}

	private static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	private record Desenlace<T>(T valor, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(" + valor + ")";
		}
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private CoberturaView agregar(
			Fixture fixture, long planId, LocalDate desde, LocalDate hasta, boolean principal) {

		return servicio.agregar(fixture.actor(), fixture.personaId(), new CoberturaAltaCommand(
				TipoCobertura.FINANCIADA, planId, "AF-" + planId + "-" + desde,
				null, desde, hasta, principal, null));
	}

	private record Fixture(
			long organizationId,
			long consultorioId,
			long personaId,
			long planId,
			long planAlternativoId,
			OperatingActor actor) {
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);
		long accountId = insertarCuenta(sufijo);
		insertarMembership(organizationId, consultorioId, accountId);

		long personaId = insertarPersona(organizationId, sufijo);
		insertarPerfilPaciente(organizationId, personaId, accountId);

		long planId = insertarPlan(
				organizationId, insertarFinanciador(organizationId, "OS1-" + sufijo), "P1-" + sufijo);
		long planAlternativoId = insertarPlan(
				organizationId, insertarFinanciador(organizationId, "OS2-" + sufijo), "P2-" + sufijo);

		return new Fixture(organizationId, consultorioId, personaId, planId, planAlternativoId,
				new OperatingActor(accountId, false, organizationId, consultorioId));
	}

	private long insertarOrganization(String sufijo) {
		String slug = "cobertura-it-" + sufijo;
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
		String email = "cobertura-it-" + sufijo + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	/**
	 * {@code CONSULTORIO_ADMIN} es el rol que 03.01 dejo con {@code paciente:manage} de alcance de
	 * sede, y el actor lleva {@code consultorioId} porque el permiso se evalua con la sede del
	 * contexto aunque la persona sea de la organizacion.
	 */
	private void insertarMembership(long organizationId, long consultorioId, long accountId) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'CONSULTORIO_ADMIN', 0,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR), 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, accountId);
	}

	private long insertarPersona(long organizationId, String sufijo) {
		String documento = String.valueOf(10000000 + Math.abs(sufijo.hashCode()) % 80000000);
		jdbc.update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				VALUES (?, 'DNI', ?, ?, 'Sintetica', 'Paciente', 'SINTETICA', 'PACIENTE',
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertarPerfilPaciente(long organizationId, long personaId, long accountId) {
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, accountId);
	}

	private long insertarFinanciador(long organizationId, String codigo) {
		jdbc.update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, "Financiador " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	/**
	 * {@code requiere_credencial = 0} a proposito: lo que estos tests miden es la exclusion sobre
	 * la vigencia. Exigir credencial agregaria una segunda fuente de rechazo y el test no podria
	 * decir cual de las dos actuo.
	 */
	private long insertarPlan(long organizationId, long financiadorId, String codigo) {
		jdbc.update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, financiadorId, codigo, "Plan " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private int contarActivas(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM cobertura_paciente
				 WHERE organization_id = ? AND persona_id = ? AND active = 1
				""", Integer.class, fixture.organizationId(), fixture.personaId());
	}

	private int contarPrincipalesActivas(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM cobertura_paciente
				 WHERE organization_id = ? AND persona_id = ? AND active = 1 AND principal = 1
				""", Integer.class, fixture.organizationId(), fixture.personaId());
	}

	private int contarCandados(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM cobertura_persona_lock
				 WHERE organization_id = ? AND persona_id = ?
				""", Integer.class, fixture.organizationId(), fixture.personaId());
	}
}
