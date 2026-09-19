package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.application.EntradaClinicaService;
import com.akine.clinical.application.EntradaClinicaView;
import com.akine.clinical.application.OperatingActor;
import com.akine.clinical.domain.TipoEntradaClinica;
import com.akine.clinical.domain.exception.EntradaClinicaNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La numeracion de versiones de una entrada clinica, ejercida por hilos de verdad contra MySQL.
 *
 * <p><b>ESCRITO EL 19/09/2026 Y NUNCA EJECUTADO: Docker no estaba disponible.</b> El motor de
 * contenedores de esta maquina no arranca sin elevacion, asi que esta clase compila y no corrio.
 * Nada de lo que afirma esta verificado todavia.
 *
 * <h2>Que escenario prueba</h2>
 *
 * <p><b>Dos profesionales enmendando la MISMA entrada al mismo tiempo.</b> Las dos enmiendas son
 * versiones nuevas —esa es la ventaja de versionar en filas y no en columnas, y por eso ninguna
 * pisa el texto de la otra—, pero las dos compiten por el <b>numero</b>. El challenge de la etapa
 * (seccion 8, punto 3) lo declara como el caso que rompe el diseño si esta mal resuelto.
 *
 * <h2>Por que ningun test unitario puede probarlo</h2>
 *
 * <p>{@code EntradaClinicaServiceTest} verifica con dobles que la enmienda numera con
 * {@code entrada.siguienteNumeroDeVersion()} y no con un {@code MAX(numero_version) + 1}. Eso
 * prueba <b>de donde sale el numero</b>, no que dos transacciones concurrentes no puedan sacar el
 * mismo. Quien decide eso es Hibernate al cerrar la transaccion: la cabecera se lee con
 * {@link org.springframework.data.jpa.repository.Lock}
 * {@code OPTIMISTIC_FORCE_INCREMENT}, y que su {@code @Version} <b>efectivamente avance</b> cuando
 * la escritura solo agrega una fila hija es comportamiento del proveedor de persistencia contra
 * una base real. Debajo esta el unique {@code uk_entrada_version_numero}
 * {@code (organization_id, entrada_clinica_id, numero_version)}, que es la red — no el mecanismo.
 *
 * <p>Es exactamente la leccion de 02.07, escrita en el escenario diferido 20: alla el
 * {@code @Version} del padre no protegia nada porque la escritura solo tocaba tablas hijas, el
 * test miraba el codigo de respuesta y nunca simulaba dos guardados seguidos, y el defecto
 * sobrevivio a la etapa.
 *
 * <h2>El control negativo, que es lo que a 02.07 le falto</h2>
 *
 * <p>Un mecanismo que serializa de mas pasa inadvertido si solo se prueba que rechaza. El cuarto
 * escenario enmienda <b>dos entradas distintas</b> a la vez y exige que las dos entren: si
 * fallara, lo que esta mal es el alcance del bloqueo y no la regla.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EntradaClinicaConcurrenteIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	/** Sin relacion asistencial no hay acceso clinico: hoy el probe siempre dice "sin evidencia". */
	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	@Autowired private EntradaClinicaService servicio;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// (a) La carrera
	// =================================================================================

	@Test
	@DisplayName("dos enmiendas concurrentes sobre la misma entrada: una entra y la otra recibe 409")
	void dos_enmiendas_concurrentes_no_numeran_igual() {
		Fixture fixture = crearFixture();
		EntradaClinicaView entrada = registrar(fixture, "Dolor lumbar, marcha conservada");
		long versionLeida = entrada.version();

		// Las dos leyeron la MISMA version de la cabecera, que es el caso real: dos profesionales
		// abrieron la entrada y escriben su enmienda sin saber la una de la otra. El control
		// explicito de `expectedVersion` las deja pasar a las dos, porque las dos leyeron bien.
		// Lo que tiene que impedir que numeren igual es el incremento forzado, no ese control.
		Callable<EntradaClinicaView> unProfesional = () -> servicio.enmendar(
				fixture.actor(), entrada.id(), "Se corrige la lateralidad: izquierda",
				"Error de lado", versionLeida, JUSTIFICACION);
		Callable<EntradaClinicaView> otroProfesional = () -> servicio.enmendar(
				fixture.actor(), entrada.id(), "Se agrega el resultado del test de Lasegue",
				"Dato faltante", versionLeida, JUSTIFICACION);

		List<Desenlace<EntradaClinicaView>> desenlaces =
				enParalelo(List.of(unProfesional, otroProfesional));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente una enmienda entra. Desenlaces: %s", desenlaces)
				.isEqualTo(1);
		assertThat(desenlaces.stream()
				.filter(Desenlace::fallo)
				.filter(d -> causaEs(d.error(), OptimisticLockingFailureException.class)
						|| causaEs(d.error(), DataIntegrityViolationException.class))
				.count())
				.as("la perdedora recibe un 409 explicable —conflicto optimista o el unique de "
						+ "V45—, nunca un error generico. Desenlaces: %s", desenlaces)
				.isEqualTo(1);

		// La afirmacion que de verdad importa: en la base no puede haber dos versiones con el
		// mismo numero. Si las hubiera, el historico clinico de esa entrada dejaria de tener un
		// orden y RF-M09-006 no se podria cumplir ni leyendo a mano.
		assertThat(numerosDeVersion(entrada.id()))
				.as("el historico queda 1 y 2, sin repetidos")
				.containsExactly(1, 2);
		assertThat(jdbc.queryForObject("""
				SELECT ultimo_numero_version FROM entrada_clinica WHERE id = ?
				""", Integer.class, entrada.id()))
				.as("y el contador de la cabecera avanzo UNA sola vez")
				.isEqualTo(2);
	}

	// =================================================================================
	// (b) La rafaga secuencial
	// =================================================================================

	@Test
	@DisplayName("seis enmiendas secuenciales numeran 1..7 sin huecos ni repetidos")
	void la_numeracion_secuencial_no_deja_huecos() {
		// Un hueco en la numeracion de versiones de una entrada clinica se lee como "aca hubo una
		// version que alguien borro", que es exactamente lo que ADR-0011 prohibe que parezca. El
		// riesgo concreto es reservar numero ANTES de resolver la idempotencia o de validar, y
		// gastar correlativos en intentos que no escriben nada: la leccion de 06.05.
		Fixture fixture = crearFixture();
		EntradaClinicaView entrada = registrar(fixture, "Version original");

		for (int i = 2; i <= 7; i++) {
			// La version se vuelve a leer en cada vuelta a proposito: lo que este escenario mide es
			// la numeracion, no si la vista que devuelve `enmendar` trae la version ya
			// incrementada. Encadenar la del retorno mezclaria las dos preguntas.
			long version = servicio.ver(fixture.actor(), entrada.id(), JUSTIFICACION).version();
			servicio.enmendar(fixture.actor(), entrada.id(), "Cuerpo " + i,
					"Motivo " + i, version, JUSTIFICACION);
		}

		assertThat(numerosDeVersion(entrada.id()))
				.as("1..7, sin huecos y sin repetidos")
				.containsExactly(1, 2, 3, 4, 5, 6, 7);
		assertThat(servicio.versiones(fixture.actor(), entrada.id(), JUSTIFICACION))
				.as("y el historico que RF-M09-006 promete tiene las siete")
				.hasSize(7);
	}

	@Test
	@DisplayName("la version 1 no lleva motivo y toda enmienda posterior si")
	void solo_las_enmiendas_llevan_motivo() {
		// Es el CHECK `ck_entrada_version_motivo_de_enmienda` visto desde el servicio: sin motivo,
		// una enmienda es indistinguible de una correccion de tipeo y el historico deja de servir
		// para lo unico que sirve, que es entender por que cambio el texto.
		Fixture fixture = crearFixture();
		EntradaClinicaView entrada = registrar(fixture, "Version original");
		servicio.enmendar(fixture.actor(), entrada.id(), "Corregido",
				"Se completa la evolucion", entrada.version(), JUSTIFICACION);

		List<String> motivos = jdbc.queryForList("""
				SELECT motivo_enmienda FROM entrada_clinica_version
				 WHERE entrada_clinica_id = ? ORDER BY numero_version
				""", String.class, entrada.id());

		assertThat(motivos).containsExactly(null, "Se completa la evolucion");
	}

	// =================================================================================
	// (c) El control negativo
	// =================================================================================

	@Test
	@DisplayName("dos enmiendas concurrentes sobre entradas DISTINTAS entran las dos")
	void entradas_distintas_no_se_estorban() {
		// La otra mitad de la regla, y la que hace que valga la pena serializar en vez de
		// prohibir. Dos profesionales enmendando dos hechos clinicos distintos del mismo paciente
		// es lo normal en un centro, y un mecanismo que los rechazara seria peor que el problema
		// que resuelve. Sin este escenario, un bloqueo demasiado grueso —por historia, por
		// ejemplo— pasaria inadvertido con los otros tres en verde.
		Fixture fixture = crearFixture();
		EntradaClinicaView una = registrar(fixture, "Evolucion de la mañana");
		EntradaClinicaView otra = registrar(fixture, "Indicacion de la tarde");

		Callable<EntradaClinicaView> enmendarUna = () -> servicio.enmendar(
				fixture.actor(), una.id(), "Corregido A", "Motivo A", una.version(), JUSTIFICACION);
		Callable<EntradaClinicaView> enmendarOtra = () -> servicio.enmendar(
				fixture.actor(), otra.id(), "Corregido B", "Motivo B", otra.version(), JUSTIFICACION);

		List<Desenlace<EntradaClinicaView>> desenlaces =
				enParalelo(List.of(enmendarUna, enmendarOtra));

		assertThat(desenlaces.stream().filter(Desenlace::fallo).toList())
				.as("ninguna falla: no comparten cabecera ni contador. Desenlaces: %s", desenlaces)
				.isEmpty();
		assertThat(numerosDeVersion(una.id())).containsExactly(1, 2);
		assertThat(numerosDeVersion(otra.id())).containsExactly(1, 2);
	}

	// =================================================================================
	// Aislamiento de tenant — 404, nunca 403
	// =================================================================================

	@Test
	@DisplayName("un actor del tenant B no ve ni enmienda la entrada del tenant A: 404, nunca 403")
	void un_tenant_no_alcanza_la_entrada_del_otro() {
		// AGENT.md seccion 6: un token del tenant B nunca puede ver datos del tenant A, y hay que
		// verificarlo en cada test de integracion. La respuesta es "no accesible" —404— y no 403:
		// un 403 confirmaria que esa entrada existe en algun lado, que es media fuga.
		Fixture tenantA = crearFixture();
		Fixture tenantB = crearFixture();
		EntradaClinicaView deA = registrar(tenantA, "Contenido del tenant A");

		assertThatThrownBy(() -> servicio.ver(tenantB.actor(), deA.id(), JUSTIFICACION))
				.isInstanceOf(EntradaClinicaNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.versiones(tenantB.actor(), deA.id(), JUSTIFICACION))
				.isInstanceOf(EntradaClinicaNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.enmendar(tenantB.actor(), deA.id(), "Intruso",
				"Motivo", deA.version(), JUSTIFICACION))
				.isInstanceOf(EntradaClinicaNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.darDeBaja(tenantB.actor(), deA.id(), "Motivo",
				deA.version(), JUSTIFICACION))
				.isInstanceOf(EntradaClinicaNotAccessibleException.class);

		// Y por el otro lado: la historia de A tampoco se alcanza desde B para listar o registrar.
		assertThatThrownBy(() -> servicio.listar(
				tenantB.actor(), tenantA.historiaClinicaId(), true, JUSTIFICACION))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		assertThatThrownBy(() -> servicio.registrar(
				tenantB.actor(), tenantA.historiaClinicaId(), TipoEntradaClinica.EVOLUCION,
				"Intruso", null, JUSTIFICACION))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);

		assertThat(numerosDeVersion(deA.id()))
				.as("y nada se escribio en la entrada de A")
				.containsExactly(1);
	}

	// =================================================================================
	// Ejecucion concurrente — calcado de AutorizacionConcurrenteIT
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
	// Operaciones y consultas
	// =================================================================================

	private EntradaClinicaView registrar(Fixture fixture, String cuerpo) {
		return servicio.registrar(
				fixture.actor(), fixture.historiaClinicaId(), TipoEntradaClinica.EVOLUCION,
				cuerpo, Instant.now().minus(1, ChronoUnit.HOURS), JUSTIFICACION);
	}

	private List<Integer> numerosDeVersion(long entradaClinicaId) {
		return jdbc.queryForList("""
				SELECT numero_version FROM entrada_clinica_version
				 WHERE entrada_clinica_id = ? ORDER BY numero_version
				""", Integer.class, entradaClinicaId);
	}

	// =================================================================================
	// Fixture — todo sintetico (AGENT.md seccion 10)
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long personaId, long historiaClinicaId,
			OperatingActor actor) {
	}

	/**
	 * Un centro con una sede, un profesional y un paciente con historia abierta.
	 *
	 * <p>{@code PROFESIONAL} es el <b>unico</b> rol con {@code hc:read} y {@code hc:write}
	 * (matriz de permisos seccion 2). El test corre contra el evaluador real, asi que tambien
	 * falla si esa asignacion se quita por descuido.
	 */
	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{"Centro Sintetico " + sufijo, "entrada-it-" + sufijo, ZONA});

		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, "Sede Sintetica " + sufijo, ZONA});

		String email = "entrada-it-" + sufijo + "@ejemplo.test";
		long cuentaId = insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{email, email});

		insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, 'PROFESIONAL', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, consultorioId, cuentaId});

		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{
						organizationId, "Paciente" + sufijo, ("PACIENTE" + sufijo).toUpperCase()});

		insertar("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});

		long historiaClinicaId = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", new Object[]{organizationId, personaId, cuentaId});

		return new Fixture(organizationId, consultorioId, personaId, historiaClinicaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId));
	}

	private long insertar(String sql, Object[] args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
