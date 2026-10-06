package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ResultadoDeCancelacion;
import com.akine.activity.infrastructure.InscripcionClaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 45 de {@code docs/tests-diferidos.md}: <b>el invariante
 * {@code cupo_ocupado == InscripcionClaseRepository#contarQueConsumenCupo}</b>.
 *
 * <p>El contador es el asignador y las inscripciones son los recibos. Si divergen, hoy no lo
 * detecta nada: es el gemelo exacto de la deuda que 04.05 dejo con el ledger de autorizaciones.
 *
 * <h2>Lo que destapo la primera corrida</h2>
 *
 * <p>El caso {@link #baja_de_quien_espera_mientras_lo_promueven} reproduce un defecto real que la
 * rafaga mezclada no alcanzo en cinco rondas —la ventana es estrecha—: la baja de una persona que
 * esta en la cola, concurrente con la baja de otra que la promueve. La primera lee la inscripcion como {@code LISTA_ESPERA} —no
 * consume cupo, asi que no libera nada—; la segunda la promueve por un {@code UPDATE} nativo que
 * <b>no tocaba {@code version}</b>; y el flush de la primera, con la version vieja todavia valida,
 * la pisaba con {@code CANCELADA}. Resultado: un lugar otorgado que ningun recibo justifica, para
 * siempre. Es el mismo mecanismo del defecto 5 de la integracion del 29/09 —{@code descontarSaldo}
 * pisado por el flush de una edicion—.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CupoOcupadoInvarianteIT {

	private static final String MOTIVO = "Baja sintetica de prueba";

	@Autowired private InscripcionService inscripcionService;
	@Autowired private InscripcionClaseRepository inscripcionRepository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("rafaga mezclada de altas, bajas con lugar, bajas de la cola y promociones: el contador cuadra con los recibos")
	void rafaga_mezclada_conserva_el_invariante() {
		// Cinco rondas sobre cinco clases: una sola rafaga encuentra una sola intercalacion, y lo que
		// se busca es la que rompe.
		Tenant tenant = fx.crearTenant(6, 13);
		for (int ronda = 0; ronda < 5; ronda++) {
			long clase = fx.programarClase(tenant, 6, 7 + ronda);
			List<Long> conLugar = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				conLugar.add(fx.inscribir(tenant, clase, tenant.persona(i), false).inscripcion().id());
			}
			List<Long> enEspera = new ArrayList<>();
			for (int i = 6; i < 9; i++) {
				enEspera.add(fx.inscribir(tenant, clase, tenant.persona(i), true).inscripcion().id());
			}

			// Ocho a la vez: tres bajas con lugar (cada una promueve), dos bajas de la cola —una de
			// ellas la cabeza, que es la que alguna de las tres va a querer promover— y tres altas
			// nuevas que compiten por lo que se libere.
			List<Callable<Object>> rafaga = List.of(
					() -> cancelar(tenant, clase, conLugar.get(0)),
					() -> cancelar(tenant, clase, conLugar.get(1)),
					() -> cancelar(tenant, clase, conLugar.get(2)),
					() -> cancelar(tenant, clase, enEspera.get(0)),
					() -> cancelar(tenant, clase, enEspera.get(2)),
					() -> fx.inscribir(tenant, clase, tenant.persona(9), true),
					() -> fx.inscribir(tenant, clase, tenant.persona(10), true),
					() -> fx.inscribir(tenant, clase, tenant.persona(11), true));
			List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(rafaga);

			// Un 409 por modificacion concurrente es un desenlace legitimo: es lo que el cliente
			// reintenta. Cualquier otra falla —un deadlock, un CHECK, un 500— no lo es.
			assertThat(desenlaces)
					.as("ronda %d: solo se admite conflicto optimista. Desenlaces: %s", ronda, desenlaces)
					.allMatch(d -> !d.fallo()
							|| ActividadItFixture.causaEs(d.error(), OptimisticLockingFailureException.class));

			int contador = fx.cupoOcupado(clase);
			assertThat(contador)
					.as("ronda %d: cupo_ocupado == recibos. Desenlaces: %s", ronda, desenlaces)
					.isEqualTo(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase));
			assertThat(contador).as("ronda %d: nunca por encima de la capacidad", ronda).isLessThanOrEqualTo(6);
			assertThat(fx.personasDuplicadas(clase)).isZero();
		}
	}

	@Test
	@DisplayName("la baja de quien espera, mientras otra baja lo promueve: el flush no pisa la promocion")
	void baja_de_quien_espera_mientras_lo_promueven() throws Exception {
		Tenant tenant = fx.crearTenant(3, 4);
		long clase = fx.programarClase(tenant, 3, 7);
		long a = fx.inscribir(tenant, clase, tenant.persona(0), false).inscripcion().id();
		fx.inscribir(tenant, clase, tenant.persona(1), false);
		fx.inscribir(tenant, clase, tenant.persona(2), false);
		long w = fx.inscribir(tenant, clase, tenant.persona(3), true).inscripcion().id();

		// La intercalacion exacta, forzada con un lock propio sobre la fila de quien espera:
		//   1. la baja de A libera el lugar, lee la cola y se queda esperando en `promover(w)`;
		//   2. la baja de W lee la fila —lectura consistente, no se bloquea— como LISTA_ESPERA, y
		//      su flush se encola detras de la promocion;
		//   3. se suelta el lock: la promocion commitea y despues llega el flush de la baja de W.
		CompletableFuture<ResultadoDeCancelacion> bajaDeA;
		CompletableFuture<ResultadoDeCancelacion> bajaDeW;
		try (Connection candado = dataSource.getConnection()) {
			candado.setAutoCommit(false);
			try (PreparedStatement lock = candado.prepareStatement(
					"SELECT id FROM inscripcion_clase WHERE id = ? FOR UPDATE")) {
				lock.setLong(1, w);
				lock.executeQuery().close();
			}

			bajaDeA = CompletableFuture.supplyAsync(() -> cancelar(tenant, clase, a));
			esperarSentencia(sql -> sql.contains("promovida_en") && sql.contains("id=" + w + "and"));

			bajaDeW = CompletableFuture.supplyAsync(() -> cancelar(tenant, clase, w));
			esperarSentencia(sql -> sql.contains("updateinscripcion_claseset")
					&& sql.contains("whereid=" + w + "and"));

			candado.commit();
		}

		ResultadoDeCancelacion resultadoA = bajaDeA.get(60, TimeUnit.SECONDS);
		assertThat(resultadoA.promovida()).as("la baja de A promovio a W").isNotNull();
		assertThat(resultadoA.promovida().id()).isEqualTo(w);

		Throwable falloDeW = null;
		try {
			bajaDeW.get(60, TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			falloDeW = e.getCause();
		}
		// El invariante primero: si se rompe, el mensaje tiene que mostrar los dos numeros.
		assertThat(fx.cupoOcupado(clase))
				.as("cupo_ocupado == recibos despues de la carrera (W quedo en %s, su baja termino en %s)",
						fx.estadoDe(w), falloDeW)
				.isEqualTo(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase))
				.isEqualTo(3);
		assertThat(fx.estadoDe(w)).isEqualTo("RESERVADA");
		assertThat(falloDeW)
				.as("la baja de W leyo una foto vieja: tiene que perder por version, no pisar la promocion")
				.isNotNull();
		assertThat(ActividadItFixture.causaEs(falloDeW, OptimisticLockingFailureException.class))
				.as("y perder con un conflicto optimista (409), no con otra cosa: %s", falloDeW)
				.isTrue();

		// El reintento del cliente: ahora W tiene lugar, y su baja lo libera.
		ResultadoDeCancelacion reintento = cancelar(tenant, clase, w);
		assertThat(reintento.inscripcion().estado()).isEqualTo("CANCELADA");
		assertThat(fx.cupoOcupado(clase))
				.isEqualTo(inscripcionRepository.contarQueConsumenCupo(tenant.organizationId(), clase))
				.isEqualTo(2);
	}

	// =================================================================================

	private ResultadoDeCancelacion cancelar(Tenant tenant, long clase, long inscripcion) {
		return inscripcionService.cancelar(
				tenant.actor(), tenant.consultorioId(), clase, inscripcion, MOTIVO);
	}

	/**
	 * Espera a que una sentencia que cumpla el predicado este en curso en el motor —o sea, bloqueada
	 * por el lock del test—.
	 *
	 * <p>Lee {@code information_schema.PROCESSLIST}, que muestra las conexiones del mismo usuario sin
	 * privilegio {@code PROCESS}. El texto se compara en minusculas y sin espacios: Hibernate escribe
	 * {@code id=?} y la nativa {@code id = :id}, y el driver interpola los valores en el cliente.
	 */
	private void esperarSentencia(Predicate<String> predicado) throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < limite) {
			List<String> enCurso = jdbc.queryForList(
					"SELECT INFO FROM information_schema.PROCESSLIST WHERE INFO IS NOT NULL", String.class);
			if (enCurso.stream()
					.map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""))
					.anyMatch(predicado)) {
				// Margen para que la sentencia termine de encolarse en el gestor de locks.
				Thread.sleep(200);
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("La sentencia esperada nunca llego al motor");
	}
}
