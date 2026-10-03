package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.api.EncounterProblemHandler;
import com.akine.encounter.application.OperatingActor;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.application.TratamientoView;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.ParametroAplicado;
import com.akine.encounter.domain.TipoDatoParametro;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.domain.exception.EspacioNoAccesibleException;
import com.akine.encounter.domain.exception.PracticaNoUtilizableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tratamientos realizados contra MySQL real: escenarios 39 a 43 de la seccion AKINE-06.04 de
 * {@code docs/tests-diferidos.md}.
 *
 * <p>Ninguno se puede probar con dobles: lo que se verifica es el comportamiento del motor
 * (CHECK, unique con columna generada, orden de sentencias dentro de la sesion de Hibernate, avance
 * de {@code @Version}). Cada test dice en un comentario {@code // Escenario NN} cual cubre.
 *
 * <p>Estos tests prueban el comportamiento <b>actual</b>: no versionan tratamientos en la
 * enmienda ni refuerzan el permiso (eso es C-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class TratamientoRealizadoIT {

	@Autowired private TratamientoService tratamientoService;
	@Autowired private SesionService sesionService;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;
	private final EncounterProblemHandler handler = new EncounterProblemHandler();

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	// =================================================================================
	// Escenario 39: V55 contra el motor
	// =================================================================================

	@Test
	@DisplayName("un tratamiento con lateralidad NO_APLICA entra por el servicio (C-1, V65)")
	void no_aplica_entra_por_el_servicio() {
		// Escenario 39 (ck_tratamiento_lateralidad con NO_APLICA) + dependencia de A1/V65.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		TratamientoView vista = registrar(mundo, sesionId,
				aplicado(mundo, "Columna lumbar", Lateralidad.NO_APLICA, null, List.of()));

		assertThat(vista.lateralidad()).isEqualTo("NO_APLICA");
		assertThat(jdbc.queryForObject(
				"SELECT lateralidad FROM tratamiento_realizado WHERE id = ?", String.class, vista.id()))
				.isEqualTo("NO_APLICA");
	}

	@Test
	@DisplayName("el parametro NUMERICO con el numero guardado como texto no entra: ck_tratamiento_parametro_valor")
	void parametro_numerico_con_valor_en_la_columna_equivocada_se_rechaza() {
		// Escenario 39: el "parametro legado sin tipo" del caso borde.
		long tratamientoId = tratamientoDirecto();

		assertThatThrownBy(() -> insertarParametro(tratamientoId, "intensidad", "NUMERICO",
				null, "12", null, null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_tratamiento_parametro_valor");

		// Control positivo: el mismo parametro con el valor en su columna entra.
		insertarParametro(tratamientoId, "intensidad", "NUMERICO", new BigDecimal("12.500"), null,
				null, "mA");
		assertThat(contar("tratamiento_parametro", "tratamiento_realizado_id", tratamientoId)).isEqualTo(1);
	}

	@Test
	@DisplayName("TEXTO con valor numerico, BOOLEANO sin valor y dos valores a la vez se rechazan")
	void el_valor_tiene_que_caer_en_la_columna_de_su_tipo() {
		// Escenario 39: ck_tratamiento_parametro_valor en las demas direcciones.
		long tratamientoId = tratamientoDirecto();

		assertThatThrownBy(() -> insertarParametro(tratamientoId, "a", "TEXTO",
				BigDecimal.ONE, null, null, null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_tratamiento_parametro_valor");
		assertThatThrownBy(() -> insertarParametro(tratamientoId, "b", "BOOLEANO",
				null, null, null, null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_tratamiento_parametro_valor");
		assertThatThrownBy(() -> insertarParametro(tratamientoId, "c", "NUMERICO",
				BigDecimal.ONE, "uno", null, null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_tratamiento_parametro_valor");

		insertarParametro(tratamientoId, "d", "TEXTO", null, "libre", null, null);
		insertarParametro(tratamientoId, "e", "BOOLEANO", null, null, 1, null);
		assertThat(contar("tratamiento_parametro", "tratamiento_realizado_id", tratamientoId)).isEqualTo(2);
	}

	@Test
	@DisplayName("un tipo_dato fuera de la lista cerrada se rechaza: ck_tratamiento_parametro_tipo")
	void tipo_de_dato_desconocido_se_rechaza() {
		// Escenario 39.
		long tratamientoId = tratamientoDirecto();

		assertThatThrownBy(() -> insertarParametro(tratamientoId, "x", "FECHA",
				null, "2026-01-01", null, null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_tratamiento_parametro_tipo");
	}

	@Test
	@DisplayName("una unidad sobre un TEXTO no entra: ck_tratamiento_parametro_unidad")
	void unidad_sobre_un_texto_se_rechaza() {
		// Escenario 39.
		long tratamientoId = tratamientoDirecto();

		assertThatThrownBy(() -> insertarParametro(tratamientoId, "x", "TEXTO",
				null, "firme", null, "mA"))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_tratamiento_parametro_unidad");
	}

	@Test
	@DisplayName("dos parametros con la misma clave en un tratamiento chocan: uk_tratamiento_parametro_clave")
	void clave_repetida_se_rechaza() {
		// Escenario 39.
		long tratamientoId = tratamientoDirecto();
		insertarParametro(tratamientoId, "series", "NUMERICO", new BigDecimal("3"), null, null, null);

		assertThatThrownBy(() -> insertarParametro(tratamientoId, "series", "NUMERICO",
				new BigDecimal("4"), null, null, null))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("uk_tratamiento_parametro_clave");
	}

	@Test
	@DisplayName("los CHECK de tratamiento_realizado rechazan lo invalido y admiten lo valido")
	void checks_de_tratamiento_realizado_en_las_dos_direcciones() {
		// Escenario 39: orden, duracion, lateralidad, lateralidad_con_zona, espacio_snapshot y baja.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 0, "DERECHA", "Rodilla",
				null, null, null, 1, null, null))
				.hasMessageContaining("ck_tratamiento_orden_positivo");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, null, null,
				0, null, null, 1, null, null))
				.hasMessageContaining("ck_tratamiento_duracion");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, null, null,
				1441, null, null, 1, null, null))
				.hasMessageContaining("ck_tratamiento_duracion");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, "ARRIBA", "Rodilla",
				null, null, null, 1, null, null))
				.hasMessageContaining("ck_tratamiento_lateralidad");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, "DERECHA", null,
				null, null, null, 1, null, null))
				.hasMessageContaining("ck_tratamiento_lateralidad_con_zona");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, null, null,
				null, mundo.espacioId(), null, 1, null, null))
				.hasMessageContaining("ck_tratamiento_espacio_snapshot");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, null, null,
				null, null, "Box huerfano", 1, null, null))
				.hasMessageContaining("ck_tratamiento_espacio_snapshot");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, null, null,
				null, null, null, 0, null, null))
				.hasMessageContaining("ck_tratamiento_baja_coherente");
		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, null, null,
				null, null, null, 1, "2026-01-01 10:00:00.000000", "motivo"))
				.hasMessageContaining("ck_tratamiento_baja_coherente");

		assertThat(contar("tratamiento_realizado", "sesion_id", sesionId))
				.as("ninguno de los rechazos dejo una fila").isZero();

		// Controles positivos: los extremos validos entran.
		insertarTratamiento(mundo, sesionId, 1, null, null, 1, null, null, 1, null, null);
		insertarTratamiento(mundo, sesionId, 2, "BILATERAL", "Hombros", 1440, mundo.espacioId(),
				"Box", 1, null, null);
		insertarTratamiento(mundo, sesionId, 3, "NO_APLICA", "Cervical", null, null, null, 0,
				"2026-01-01 10:00:00.000000", "cargado por error");
		assertThat(contar("tratamiento_realizado", "sesion_id", sesionId)).isEqualTo(3);
	}

	// =================================================================================
	// Escenario 40: uk_tratamiento_orden con deleted_key, y el orden no se reutiliza
	// =================================================================================

	@Test
	@DisplayName("dar de baja el tratamiento 2 y registrar otro produce el 3, no otro 2")
	void el_orden_no_se_reutiliza_despues_de_una_baja() {
		// Escenario 40.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		TratamientoView uno = registrar(mundo, sesionId, aplicado(mundo, "Rodilla", null, null, List.of()));
		TratamientoView dos = registrar(mundo, sesionId, aplicado(mundo, "Hombro", null, null, List.of()));
		assertThat(List.of(uno.orden(), dos.orden())).containsExactly(1, 2);

		tratamientoService.darDeBaja(mundo.actor(), mundo.consultorioId(), sesionId, dos.id(),
				"cargado por error", versionDe(mundo, sesionId));
		TratamientoView tres = registrar(mundo, sesionId, aplicado(mundo, "Cuello", null, null, List.of()));

		assertThat(tres.orden()).as("el orden de la baja no se libera").isEqualTo(3);
		assertThat(jdbc.queryForList("""
				SELECT orden FROM tratamiento_realizado
				 WHERE sesion_id = ? AND active = 1 ORDER BY orden
				""", Integer.class, sesionId)).containsExactly(1, 3);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM tratamiento_realizado
				 WHERE sesion_id = ? AND orden = 2 AND active = 0
				""", Long.class, sesionId)).as("la fila dada de baja sigue ahi").isEqualTo(1);
	}

	@Test
	@DisplayName("el unique de orden protege entre vigentes y deja convivir a las bajas (deleted_key)")
	void uk_tratamiento_orden_con_centinela() {
		// Escenario 40: varios NULL no colisionan en MySQL; el centinela de deleted_key si.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		insertarTratamiento(mundo, sesionId, 1, null, null, null, null, null, 1, null, null);

		assertThatThrownBy(() -> insertarTratamiento(mundo, sesionId, 1, null, null,
				null, null, null, 1, null, null))
				.as("dos vigentes con el mismo orden")
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("uk_tratamiento_orden");

		// Una baja con el mismo orden que un vigente entra: deleted_key ya no es el centinela.
		insertarTratamiento(mundo, sesionId, 1, null, null, null, null, null, 0,
				"2026-01-01 10:00:00.000000", "error 1");
		// Y dos bajas con el mismo orden en instantes distintos tambien.
		insertarTratamiento(mundo, sesionId, 1, null, null, null, null, null, 0,
				"2026-01-02 10:00:00.000000", "error 2");

		assertThat(contar("tratamiento_realizado", "sesion_id", sesionId)).isEqualTo(3);
	}

	// =================================================================================
	// Escenario 41: OPTIMISTIC_FORCE_INCREMENT sobre sesion avanza una sola vez
	// =================================================================================

	@Test
	@DisplayName("registrar avanza la version de la sesion UNA vez y el cliente puede seguir con ella")
	void la_version_avanza_una_sola_vez() {
		// Escenario 41: si avanzara dos veces el cliente comeria un 409 del que no puede salir.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		long leida = versionDe(mundo, sesionId);

		TratamientoView primero = registrar(mundo, sesionId,
				aplicado(mundo, "Rodilla", null, null, List.of()), leida);

		assertThat(primero.sesionVersion()).as("la respuesta devuelve leida+1").isEqualTo(leida + 1);
		assertThat(versionEnBase(sesionId)).as("y la base quedo en leida+1, no en leida+2")
				.isEqualTo(leida + 1);

		// El cliente sigue operando con la version que recibio, sin releer.
		TratamientoView segundo = registrar(mundo, sesionId,
				aplicado(mundo, "Hombro", null, null, List.of()), primero.sesionVersion());
		assertThat(segundo.sesionVersion()).isEqualTo(leida + 2);
		assertThat(versionEnBase(sesionId)).isEqualTo(leida + 2);
	}

	@Test
	@DisplayName("dos altas concurrentes con la misma version: una entra y la otra recibe conflicto")
	void dos_altas_concurrentes_una_sola_entra() {
		// Escenario 41.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		long leida = versionDe(mundo, sesionId);

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> registrar(mundo, sesionId, aplicado(mundo, "Rodilla", null, null, List.of()), leida),
				() -> registrar(mundo, sesionId, aplicado(mundo, "Hombro", null, null, List.of()), leida)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente una alta entra. Desenlaces: %s", desenlaces).isEqualTo(1);
		Exception perdedora = desenlaces.stream().filter(Desenlace::fallo).findFirst().orElseThrow().error();
		assertThat(perdedora)
				.as("la que pierde recibe un conflicto (409), no un error de infraestructura")
				.isInstanceOfAny(OptimisticLockingFailureException.class,
						DataIntegrityViolationException.class);

		assertThat(contar("tratamiento_realizado", "sesion_id", sesionId)).isEqualTo(1);
		assertThat(versionEnBase(sesionId)).isEqualTo(leida + 1);
	}

	@Test
	@DisplayName("registrar con una version vieja recibe conflicto antes de insertar nada")
	void version_vieja_es_conflicto_y_no_inserta() {
		// Escenario 41.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		long leida = versionDe(mundo, sesionId);
		registrar(mundo, sesionId, aplicado(mundo, "Rodilla", null, null, List.of()), leida);

		assertThatThrownBy(() -> registrar(mundo, sesionId,
				aplicado(mundo, "Hombro", null, null, List.of()), leida))
				.isInstanceOf(OptimisticLockingFailureException.class);

		assertThat(contar("tratamiento_realizado", "sesion_id", sesionId)).isEqualTo(1);
	}

	// =================================================================================
	// Escenario 42: borrado fisico de parametros en el PUT
	// =================================================================================

	@Test
	@DisplayName("reemplazar conservando una clave de parametro no choca contra uk_tratamiento_parametro_clave")
	void el_put_conserva_claves_sin_chocar() {
		// Escenario 42: sin flush previo los INSERT saldrian despues del DELETE; sin clear
		// posterior las entities borradas seguirian en el contexto de persistencia.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		TratamientoView original = registrar(mundo, sesionId, aplicado(mundo, "Rodilla", null, null, List.of(
				numerico("intensidad", "10", "mA"),
				numerico("frecuencia", "50", "Hz"))));

		TratamientoView reemplazado = tratamientoService.reemplazar(
				mundo.actor(), mundo.consultorioId(), sesionId, original.id(),
				aplicado(mundo, "Rodilla", null, null, List.of(
						numerico("intensidad", "15", "mA"),
						numerico("series", "3", null))),
				versionDe(mundo, sesionId));

		assertThat(reemplazado.parametros()).extracting(TratamientoView.ParametroView::clave)
				.containsExactlyInAnyOrder("intensidad", "series");
		assertThat(jdbc.queryForList("""
				SELECT clave FROM tratamiento_parametro
				 WHERE tratamiento_realizado_id = ? ORDER BY clave
				""", String.class, original.id()))
				.as("la clave que no vino se borro fisicamente y la que se conservo no se duplico")
				.containsExactly("intensidad", "series");
		assertThat(jdbc.queryForObject("""
				SELECT valor_numerico FROM tratamiento_parametro
				 WHERE tratamiento_realizado_id = ? AND clave = 'intensidad'
				""", BigDecimal.class, original.id())).isEqualByComparingTo("15");
	}

	@Test
	@DisplayName("reemplazar dos veces seguidas con las mismas claves tampoco choca")
	void el_put_repetido_con_las_mismas_claves() {
		// Escenario 42: la segunda pasada es la que fallaria si quedaran entities stale.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView original = registrar(mundo, sesionId, aplicado(mundo, "Rodilla", null, null,
				List.of(numerico("intensidad", "10", "mA"))));

		for (String valor : List.of("11", "12")) {
			tratamientoService.reemplazar(mundo.actor(), mundo.consultorioId(), sesionId,
					original.id(),
					aplicado(mundo, "Rodilla", null, null, List.of(numerico("intensidad", valor, "mA"))),
					versionDe(mundo, sesionId));
		}

		assertThat(contar("tratamiento_parametro", "tratamiento_realizado_id", original.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT valor_numerico FROM tratamiento_parametro WHERE tratamiento_realizado_id = ?
				""", BigDecimal.class, original.id())).isEqualByComparingTo("12");
	}

	@Test
	@DisplayName("reemplazar sin parametros borra todos los anteriores")
	void el_put_sin_parametros_los_borra() {
		// Escenario 42.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView original = registrar(mundo, sesionId, aplicado(mundo, "Rodilla", null, null,
				List.of(numerico("intensidad", "10", "mA"), numerico("series", "3", null))));

		tratamientoService.reemplazar(mundo.actor(), mundo.consultorioId(), sesionId, original.id(),
				aplicado(mundo, "Rodilla", null, null, List.of()), versionDe(mundo, sesionId));

		assertThat(contar("tratamiento_parametro", "tratamiento_realizado_id", original.id())).isZero();
	}

	// =================================================================================
	// Escenario 43: aislamiento de tenant, 404 y nunca 403
	// =================================================================================

	@Test
	@DisplayName("un actor de otro tenant no registra, lista, reemplaza ni da de baja: 404")
	void otro_tenant_no_toca_los_tratamientos_ajenos() {
		// Escenario 43 (AGENT.md seccion 6).
		Mundo a = fixtures.crearMundo();
		Mundo b = fixtures.crearMundo();
		long sesionA = fixtures.crearSesion(a);
		TratamientoView deA = registrar(a, sesionA, aplicado(a, "Rodilla", null, null, List.of()));
		long version = versionDe(a, sesionA);
		OperatingActor actorB = b.actor();

		// Con su propia sede: la sesion de A no existe para B.
		assertThatThrownBy(() -> tratamientoService.registrar(actorB, b.consultorioId(), sesionA,
				aplicado(b, "Hombro", null, null, List.of()), version))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> tratamientoService.listar(actorB, b.consultorioId(), sesionA))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> tratamientoService.reemplazar(actorB, b.consultorioId(), sesionA,
				deA.id(), aplicado(b, "Hombro", null, null, List.of()), version))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> tratamientoService.darDeBaja(actorB, b.consultorioId(), sesionA,
				deA.id(), "no es mio", version))
				.isInstanceOf(SesionNotAccessibleException.class);

		// Con la sede de A: la sede tampoco existe para B.
		assertThatThrownBy(() -> tratamientoService.listar(actorB, a.consultorioId(), sesionA))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> tratamientoService.registrar(actorB, a.consultorioId(), sesionA,
				aplicado(b, "Hombro", null, null, List.of()), version))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		// La respuesta HTTP de las dos es 404, nunca 403: un 403 confirmaria que la fila existe.
		assertThat(handler.handleSesionNoAccesible(new SesionNotAccessibleException(sesionA)).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(handler.handleConsultorioNoAccesible(
				new ConsultorioNoAccesibleException(a.consultorioId())).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND.value());

		// Nada cambio en la sesion de A.
		assertThat(contar("tratamiento_realizado", "sesion_id", sesionA)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT active FROM tratamiento_realizado WHERE id = ?", Integer.class, deA.id()))
				.isEqualTo(1);
		assertThat(versionEnBase(sesionA)).isEqualTo(version);
	}

	@Test
	@DisplayName("una practica o un espacio de otro tenant declarados en el cuerpo dan 404")
	void practica_y_espacio_de_otro_tenant_no_existen() {
		// Escenario 43.
		Mundo a = fixtures.crearMundo();
		Mundo b = fixtures.crearMundo();
		long sesionA = fixtures.crearSesion(a);
		long version = versionDe(a, sesionA);

		TratamientoAplicado conPracticaAjena = new TratamientoAplicado(
				b.practicaId(), null, null, null, null, null, null, null, List.of());
		assertThatThrownBy(() -> tratamientoService.registrar(a.actor(), a.consultorioId(), sesionA,
				conPracticaAjena, version))
				.isInstanceOfSatisfying(PracticaNoUtilizableException.class, error ->
						assertThat(handler.handlePracticaNoUtilizable(error).getStatus())
								.as("inexistente es 404, indistinguible de 'es de otro tenant'")
								.isEqualTo(HttpStatus.NOT_FOUND.value()));

		TratamientoAplicado conEspacioAjeno = new TratamientoAplicado(
				a.practicaId(), null, null, null, null, null, b.espacioId(), null, List.of());
		assertThatThrownBy(() -> tratamientoService.registrar(a.actor(), a.consultorioId(), sesionA,
				conEspacioAjeno, version))
				.isInstanceOfSatisfying(EspacioNoAccesibleException.class, error ->
						assertThat(handler.handleEspacioNoAccesible(error).getStatus())
								.isEqualTo(HttpStatus.NOT_FOUND.value()));

		assertThat(contar("tratamiento_realizado", "sesion_id", sesionA)).isZero();
		assertThat(versionEnBase(sesionA)).as("un rechazo no consume version").isEqualTo(version);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private TratamientoView registrar(Mundo mundo, long sesionId, TratamientoAplicado aplicado) {
		return registrar(mundo, sesionId, aplicado, versionDe(mundo, sesionId));
	}

	private TratamientoView registrar(
			Mundo mundo, long sesionId, TratamientoAplicado aplicado, long expectedVersion) {
		return tratamientoService.registrar(
				mundo.actor(), mundo.consultorioId(), sesionId, aplicado, expectedVersion);
	}

	private long versionDe(Mundo mundo, long sesionId) {
		return sesionService.ver(mundo.actor(), mundo.consultorioId(), sesionId).version();
	}

	private long versionEnBase(long sesionId) {
		return jdbc.queryForObject("SELECT version FROM sesion WHERE id = ?", Long.class, sesionId);
	}

	private static TratamientoAplicado aplicado(
			Mundo mundo, String zona, Lateralidad lateralidad, Long espacioId,
			List<ParametroAplicado> parametros) {
		return new TratamientoAplicado(
				mundo.practicaId(), null, zona, lateralidad, 20, null, espacioId, null, parametros);
	}

	private static ParametroAplicado numerico(String clave, String valor, String unidad) {
		return new ParametroAplicado(
				clave, TipoDatoParametro.NUMERICO, new BigDecimal(valor), null, null, unidad);
	}

	private long contar(String tabla, String columna, long valor) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM " + tabla + " WHERE " + columna + " = ?", Long.class, valor);
	}

	/** Un tratamiento valido insertado directo, para colgarle parametros. */
	private long tratamientoDirecto() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		insertarTratamiento(mundo, sesionId, 1, null, null, null, null, null, 1, null, null);
		this.organizacionDelUltimo = mundo.organizationId();
		return jdbc.queryForObject(
				"SELECT id FROM tratamiento_realizado WHERE sesion_id = ?", Long.class, sesionId);
	}

	private long organizacionDelUltimo;

	@SuppressWarnings("java:S107")
	private void insertarTratamiento(
			Mundo mundo, long sesionId, int orden, String lateralidad, String zona,
			Integer duracion, Long espacioId, String espacioNombre, int active,
			String deletedAt, String razon) {

		jdbc.update("""
				INSERT INTO tratamiento_realizado
				       (organization_id, consultorio_id, sesion_id, orden, practica_id,
				        practica_codigo, practica_nombre, zona, lateralidad, duracion_minutos,
				        profesional_membership_id, espacio_id, espacio_nombre, registrado_en,
				        registrado_por_cuenta_id, active, deleted_at, deactivation_reason,
				        version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'P-IT', 'Practica sintetica', ?, ?, ?, ?, ?, ?,
				        UTC_TIMESTAMP(6), ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""",
				mundo.organizationId(), mundo.consultorioId(), sesionId, orden, mundo.practicaId(),
				zona, lateralidad, duracion, mundo.profesional().membershipId(), espacioId,
				espacioNombre, mundo.profesional().cuentaId(), active, deletedAt, razon);
	}

	@SuppressWarnings("java:S107")
	private void insertarParametro(
			long tratamientoId, String clave, String tipo, BigDecimal numerico, String texto,
			Integer booleano, String unidad) {

		jdbc.update("""
				INSERT INTO tratamiento_parametro
				       (organization_id, tratamiento_realizado_id, clave, tipo_dato, valor_numerico,
				        valor_texto, valor_booleano, unidad, orden, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6))
				""", organizacionDelUltimo, tratamientoId, clave, tipo, numerico, texto, booleano, unidad);
	}

	// ---------------------------------------------------------------------------------
	// Ejecucion concurrente
	// ---------------------------------------------------------------------------------

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	private static List<Desenlace> enParalelo(List<Callable<TratamientoView>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<TratamientoView> tarea : tareas) {
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

	private record Desenlace(TratamientoView vista, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(orden=" + vista.orden() + ")";
		}
	}
}
