package com.akine.person;

import com.akine.TestcontainersConfiguration;
import com.akine.person.application.AutorizacionAltaCommand;
import com.akine.person.application.AutorizacionService;
import com.akine.person.application.AutorizacionView;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.ResolucionDeAutorizacionCommand;
import com.akine.person.domain.AccionSobreAutorizacion;
import com.akine.person.domain.EstadoAutorizacion;
import com.akine.person.domain.exception.AutorizacionSuperpuestaException;
import com.akine.person.domain.exception.AutorizacionTransicionNoPermitidaException;
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
 * La regla de solapamiento de AKINE-03.06, ejercida por hilos de verdad contra MySQL real.
 *
 * <h2>Por que esto no puede ser un test unitario</h2>
 *
 * <p>{@code AutorizacionServiceTest} verifica con dobles que {@code asegurar} corre antes de
 * {@code lockByScope} y que la comprobacion rechaza. Eso prueba el <b>orden de las llamadas</b>, no
 * que dos transacciones se esperen de verdad. La regla de esta etapa es <b>lee, valida y
 * escribe</b>: sin serializacion las dos lecturas ocurren antes de las dos escrituras, las dos ven
 * una base sin conflicto y las dos pasan. Quien decide es el {@code FOR UPDATE} sobre
 * {@code autorizacion_persona_lock} y el aislamiento de InnoDB, y eso solo lo contesta el motor.
 *
 * <p>Es la leccion de 02.07, cuyo test miraba el codigo de respuesta y nunca simulaba dos
 * guardados seguidos, y la de 03.04, que la volvio a aplicar sobre coberturas.
 *
 * <h2>Lo que ningun unique de la base puede garantizar</h2>
 *
 * <p>El solapamiento de intervalos. Enero-diciembre y marzo-junio no comparten un solo valor de
 * columna, y MySQL 8.4 no tiene exclusion constraints. Y lo que se pierde si falla es concreto: el
 * saldo autorizado se cuenta dos veces y el centro cree tener veinte sesiones donde el financiador
 * dio diez.
 *
 * <h2>Verificacion por mutacion — como comprobar que este test tiene dientes</h2>
 *
 * <p>Los tres escenarios pasan a la primera, que en concurrencia es motivo de sospecha. Se
 * comprobo removiendo {@code BloqueoDeAutorizaciones.tomar} de {@code registrar} y de
 * {@code resolver}: los dos primeros escenarios fallan con <b>las dos autorizaciones aprobadas</b>.
 * Restaurado el lock, vuelven a verde. El detalle esta en el registro de cierre de la etapa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AutorizacionConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Fechas fijas y lejanas: un escenario atado al reloj de pared falla el dia que lo alcanza. */
	private static final LocalDate DESDE = LocalDate.of(2027, 1, 1);
	private static final LocalDate HASTA = LocalDate.of(2027, 12, 31);

	@Autowired private AutorizacionService servicio;
	@Autowired private JdbcTemplate jdbc;

	@Test
	@DisplayName("dos altas aprobadas concurrentes de la misma practica con vigencias solapadas: una entra y la otra recibe 409")
	void dos_aprobadas_solapadas_no_pasan_las_dos() {
		Fixture fixture = crearFixture();

		// La MISMA practica y vigencias que se cruzan sin ser iguales: enero-diciembre contra
		// marzo-junio. Ningun unique las distingue —el numero de autorizacion es distinto, que es
		// lo unico que la base sabe comparar—: el que corta es el lock.
		Callable<AutorizacionView> primera = () -> registrar(
				fixture, fixture.practicaId(), "A-1", EstadoAutorizacion.APROBADA, DESDE, HASTA);
		Callable<AutorizacionView> segunda = () -> registrar(
				fixture, fixture.practicaId(), "A-2", EstadoAutorizacion.APROBADA,
				LocalDate.of(2027, 3, 1), LocalDate.of(2027, 6, 30));

		List<Desenlace<AutorizacionView>> desenlaces = enParalelo(List.of(primera, segunda));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente una alta entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), AutorizacionSuperpuestaException.class))
				.count())
				.as("la otra recibe el 409 de solapamiento, no un deadlock ni un 500 generico. "
						+ "Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(contarAprobadas(fixture))
				.as("y en la base queda UNA sola autorizacion aprobada: el saldo no se cuenta dos veces")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("dos aprobaciones concurrentes de dos pendientes solapadas: solo una queda APROBADA")
	void dos_aprobaciones_concurrentes_no_pasan_las_dos() {
		Fixture fixture = crearFixture();

		// Las dos nacen PENDIENTE y solapadas, que es LEGITIMO: una pendiente no autoriza ninguna
		// cantidad y no puede duplicar ningun saldo. El conflicto aparece recien al aprobarlas, y
		// es el caso borde "aprobacion concurrente" que la etapa nombra.
		AutorizacionView una = registrar(
				fixture, fixture.practicaId(), "P-1", EstadoAutorizacion.PENDIENTE, DESDE, HASTA);
		AutorizacionView otra = registrar(
				fixture, fixture.practicaId(), "P-2", EstadoAutorizacion.PENDIENTE,
				LocalDate.of(2027, 3, 1), LocalDate.of(2027, 6, 30));

		assertThat(contarAprobadas(fixture))
				.as("las dos PENDIENTE conviven solapadas: eso no es un conflicto")
				.isZero();

		Callable<AutorizacionView> aprobarUna = () -> aprobar(fixture, una);
		Callable<AutorizacionView> aprobarOtra = () -> aprobar(fixture, otra);

		List<Desenlace<AutorizacionView>> desenlaces =
				enParalelo(List.of(aprobarUna, aprobarOtra));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("una sola aprobacion entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), AutorizacionSuperpuestaException.class)
						|| causaEs(d.error(), AutorizacionTransicionNoPermitidaException.class))
				.count())
				.as("la otra recibe un 409 explicable —solapamiento o transicion—, nunca un error "
						+ "generico. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(contarAprobadas(fixture))
				.as("y en la base hay UNA sola aprobada")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("el candado se crea una sola vez aunque cuatro altas aprobadas lleguen juntas a una persona nueva")
	void el_candado_no_produce_deadlock_en_la_primera_rafaga() {
		// La trampa que este proyecto ya pago cuatro veces —agenda_sede, consultorio_calendario,
		// sesion_numerador y cobertura_persona_lock—: crear la fila-lock perezosamente DENTRO de la
		// transaccion que la bloquea produce deadlock entre las primeras N escrituras concurrentes,
		// y el try/catch no salva porque atrapar una excepcion de persistencia no des-marca la
		// transaccion. Cuatro altas simultaneas sobre una persona SIN candado previo lo destapa.
		Fixture fixture = crearFixture();
		assertThat(contarCandados(fixture))
				.as("la persona arranca sin candado: es lo que hace valido al escenario")
				.isZero();

		List<Callable<AutorizacionView>> rafaga = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			LocalDate desde = DESDE.plusYears(i);
			String numero = "R-" + i;
			rafaga.add(() -> registrar(
					fixture, fixture.practicaId(), numero, EstadoAutorizacion.APROBADA,
					desde, desde.plusMonths(6)));
		}

		List<Desenlace<AutorizacionView>> desenlaces = enParalelo(rafaga);

		// Vigencias disjuntas: ninguna choca con ninguna, asi que las cuatro tienen que entrar. Si
		// alguna falla, lo que fallo es la creacion del candado y no la regla de negocio.
		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguna alta falla: las vigencias son disjuntas. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(contarAprobadas(fixture)).isEqualTo(4);
		assertThat(contarCandados(fixture))
				.as("y el candado quedo creado UNA sola vez")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("dos aprobadas solapadas de PRACTICAS DISTINTAS entran las dos")
	void practicas_distintas_conviven() {
		Fixture fixture = crearFixture();

		// La otra mitad de la regla, y la que hace que valga la pena serializar en vez de
		// prohibir: el mismo paciente puede tener autorizada kinesiologia y fonoaudiologia a la
		// vez, con las mismas fechas. Sin este escenario, un lock demasiado grueso pasaria
		// inadvertido.
		Callable<AutorizacionView> una = () -> registrar(
				fixture, fixture.practicaId(), "D-1", EstadoAutorizacion.APROBADA, DESDE, HASTA);
		Callable<AutorizacionView> otra = () -> registrar(
				fixture, fixture.practicaAlternativaId(), "D-2", EstadoAutorizacion.APROBADA,
				DESDE, HASTA);

		List<Desenlace<AutorizacionView>> desenlaces = enParalelo(List.of(una, otra));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("las dos entran: la regla es por practica. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(contarAprobadas(fixture)).isEqualTo(2);
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
	// Operaciones bajo prueba
	// =================================================================================

	private AutorizacionView registrar(
			Fixture fixture,
			long practicaId,
			String numero,
			EstadoAutorizacion estado,
			LocalDate desde,
			LocalDate hasta) {

		return servicio.registrar(fixture.actor(), fixture.personaId(), new AutorizacionAltaCommand(
				fixture.coberturaId(), practicaId, null, numero, estado, 10, desde, hasta, null));
	}

	private AutorizacionView aprobar(Fixture fixture, AutorizacionView autorizacion) {
		return servicio.resolver(
				fixture.actor(),
				fixture.personaId(),
				autorizacion.id(),
				new ResolucionDeAutorizacionCommand(
						AccionSobreAutorizacion.APROBAR, null, null, null, null,
						autorizacion.version()));
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long personaId,
			long coberturaId,
			long practicaId,
			long practicaAlternativaId,
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

		long financiadorId = insertarFinanciador(organizationId, "OS-" + sufijo);
		long planId = insertarPlan(organizationId, financiadorId, "P-" + sufijo);
		long coberturaId =
				insertarCobertura(organizationId, personaId, financiadorId, planId, sufijo);

		long especialidadId = insertarEspecialidad(organizationId, "E-" + sufijo);
		long practicaId = insertarPractica(organizationId, especialidadId, "PR1-" + sufijo);
		long practicaAlternativaId =
				insertarPractica(organizationId, especialidadId, "PR2-" + sufijo);

		return new Fixture(
				organizationId, consultorioId, personaId, coberturaId, practicaId,
				practicaAlternativaId,
				new OperatingActor(accountId, false, organizationId, consultorioId));
	}

	private long insertarOrganization(String sufijo) {
		String slug = "autorizacion-it-" + sufijo;
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
		String email = "autorizacion-it-" + sufijo + "@ejemplo.test";
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
	 * contexto aunque la persona sea de la organizacion. El test corre contra el evaluador REAL,
	 * asi que tambien falla si esa asignacion se quita por descuido.
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

	private long insertarPlan(long organizationId, long financiadorId, String codigo) {
		jdbc.update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 1, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, financiadorId, codigo, "Plan " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	/**
	 * Cobertura FINANCIADA con la copia congelada ENTERA.
	 *
	 * <p>Se inserta a mano y no por el servicio de M08 a proposito: lo que este test mide es la
	 * exclusion entre autorizaciones, y pasar por el alta de cobertura agregaria una segunda
	 * fuente de fallo. La copia va completa porque {@code ck_cobertura_referencia_coherente}
	 * rechaza media referencia — que es justamente el CHECK que 03.04 puso para que nadie guarde
	 * un puntero disfrazado de copia.
	 */
	private long insertarCobertura(
			long organizationId, long personaId, long financiadorId, long planId, String sufijo) {

		jdbc.update("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, vigencia_desde, principal, active, version,
				        created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'PREPAGA', ?, ?, ?, 1, 0, UTC_TIMESTAMP(6),
				        ?, '2020-01-01', 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				organizationId, personaId,
				financiadorId, "OS-" + sufijo, "Financiador OS-" + sufijo,
				planId, "P-" + sufijo, "Plan P-" + sufijo,
				"AF-" + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarEspecialidad(long organizationId, String codigo) {
		jdbc.update("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, "Especialidad " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarPractica(long organizationId, long especialidadId, String codigo) {
		jdbc.update("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, especialidadId, codigo, "Practica " + codigo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private int contarAprobadas(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion
				 WHERE organization_id = ? AND persona_id = ? AND active = 1
				   AND estado = 'APROBADA'
				""", Integer.class, fixture.organizationId(), fixture.personaId());
	}

	private int contarCandados(Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM autorizacion_persona_lock
				 WHERE organization_id = ? AND persona_id = ?
				""", Integer.class, fixture.organizationId(), fixture.personaId());
	}
}
