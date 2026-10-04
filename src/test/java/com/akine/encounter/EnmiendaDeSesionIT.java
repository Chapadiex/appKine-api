package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.api.EncounterProblemHandler;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionVersionView;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.ContenidoDeSesion;
import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.exception.CierreIncompletoException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.exception.EnmiendaSinMotivoException;
import com.akine.encounter.domain.exception.EvaluacionIncoherenteException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionNoCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import com.akine.organization.api.OrganizationProblemHandler;
import com.akine.organization.domain.exception.PermissionDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enmiendas de una sesion cerrada (AKINE-06.06) contra MySQL real, incluida la auditoria
 * {@code SESION_AMENDED} y el permiso de enmendar.
 *
 * <p>Lo que solo contesta el motor: que {@code V53} guarde la v1 al cerrar, que dos enmiendas
 * concurrentes terminen en conflicto y no en dos versiones con el mismo numero, que un rechazo
 * por falta de motivo deshaga la escritura de la cabecera, y que la auditoria quede en
 * {@code audit_event} con la transicion y sin el texto del motivo.
 *
 * <p>Prueban el comportamiento <b>actual</b>: el permiso es {@code sesion:register} mas propiedad
 * de la atencion, y los tratamientos y las mediciones no se versionan (eso es C-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EnmiendaDeSesionIT {

	private static final String EVENTO = "SESION_AMENDED";
	private static final String MOTIVO_SECRETO = "el paciente refirio una molestia que no figuraba";

	@Autowired private SesionService sesionService;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;
	private final EncounterProblemHandler handler = new EncounterProblemHandler();
	private final OrganizationProblemHandler permisos = new OrganizationProblemHandler();

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	// =================================================================================
	// Versionado del contenido
	// =================================================================================

	@Test
	@DisplayName("cerrar escribe la v1 y enmendar escribe la v2 con su motivo, sin pisar la original")
	void la_enmienda_conserva_la_version_original() {
		// 06.06 §4: el historial no se reescribe, se agrega.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Terapia manual");

		assertThat(sesionService.versiones(mundo.actor(), mundo.consultorioId(), sesionId))
				.extracting(SesionVersionView::numeroVersion).containsExactly(1);

		SesionView enmendada = enmendar(mundo, sesionId, "Terapia manual y ejercicios", MOTIVO_SECRETO);

		assertThat(enmendada.ultimoNumeroVersion()).isEqualTo(2);
		assertThat(enmendada.fueEnmendada()).isTrue();
		List<SesionVersionView> versiones = sesionService.versiones(
				mundo.actor(), mundo.consultorioId(), sesionId);
		assertThat(versiones).extracting(SesionVersionView::numeroVersion).containsExactly(1, 2);
		assertThat(versiones.get(0).notaDeCierre()).as("la v1 sigue diciendo lo que decia")
				.isEqualTo("Terapia manual");
		assertThat(versiones.get(0).motivoEnmienda()).isNull();
		assertThat(versiones.get(1).notaDeCierre()).isEqualTo("Terapia manual y ejercicios");
		assertThat(versiones.get(1).motivoEnmienda()).isEqualTo(MOTIVO_SECRETO);
		assertThat(versiones.get(1).registradaPor()).isEqualTo(mundo.profesional().cuentaId());
	}

	@Test
	@DisplayName("siete versiones seguidas se numeran 1..7 sin huecos ni repetidos")
	void rafaga_secuencial_sin_huecos() {
		// 06.06 §4: no hay MAX(numero_version) + 1.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");

		for (int i = 2; i <= 7; i++) {
			enmendar(mundo, sesionId, "Nota " + i, "motivo " + i);
		}

		assertThat(jdbc.queryForList("""
				SELECT numero_version FROM sesion_version WHERE sesion_id = ? ORDER BY numero_version
				""", Integer.class, sesionId)).containsExactly(1, 2, 3, 4, 5, 6, 7);
		assertThat(jdbc.queryForObject(
				"SELECT ultimo_numero_version FROM sesion WHERE id = ?", Integer.class, sesionId))
				.isEqualTo(7);
	}

	@Test
	@DisplayName("enmendar avanza la version de la cabecera UNA vez y el cliente puede seguir con ella")
	void la_version_de_la_cabecera_avanza_una_vez() {
		// 06.06 §4: force-increment solo donde la escritura no toca ninguna columna del padre; aca la
		// toca. Con los dos aplicados la base quedaria en leida+2 y la siguiente enmienda del mismo
		// cliente comeria un 409 espurio.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		long leida = version(mundo, sesionId);

		SesionView primera = sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				contenido("Nota 2"), "motivo 2", leida);

		assertThat(primera.version()).isEqualTo(leida + 1);
		assertThat(versionEnBase(sesionId)).as("la base quedo en leida+1, no en leida+2")
				.isEqualTo(leida + 1);

		SesionView segunda = sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				contenido("Nota 3"), "motivo 3", primera.version());
		assertThat(segunda.version()).isEqualTo(leida + 2);
		assertThat(segunda.ultimoNumeroVersion()).isEqualTo(3);
	}

	@Test
	@DisplayName("dos enmiendas concurrentes con la misma version: una entra y la otra recibe conflicto")
	void dos_enmiendas_concurrentes() {
		// 06.06 §11: "que dos enmiendas concurrentes reales terminen en 409 y no en dos versiones
		// con el mismo numero".
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		long leida = version(mundo, sesionId);

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
						contenido("Nota A"), "motivo A", leida),
				() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
						contenido("Nota B"), "motivo B", leida)));

		assertThat(desenlaces.stream().filter(d -> !d.fallo()).count())
				.as("exactamente una enmienda entra. Desenlaces: %s", desenlaces).isEqualTo(1);
		assertThat(desenlaces.stream().filter(Desenlace::fallo).findFirst().orElseThrow().error())
				.as("la perdedora recibe un conflicto (409), no un error de infraestructura")
				.isInstanceOfAny(OptimisticLockingFailureException.class,
						DataIntegrityViolationException.class);

		assertThat(jdbc.queryForList("""
				SELECT numero_version FROM sesion_version WHERE sesion_id = ? ORDER BY numero_version
				""", Integer.class, sesionId)).containsExactly(1, 2);
		assertThat(versionEnBase(sesionId)).isEqualTo(leida + 1);
	}

	// =================================================================================
	// Auditoria SESION_AMENDED
	// =================================================================================

	@Test
	@DisplayName("enmendar deja un SESION_AMENDED con la transicion de version y sin el texto del motivo")
	void auditoria_de_la_enmienda() {
		// 06.06 §7: va la transicion, no la prosa clinica; audit_event se consulta con auditoria:read.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		long antes = contarAuditoria(sesionId);

		enmendar(mundo, sesionId, "Nota enmendada", MOTIVO_SECRETO);

		assertThat(contarAuditoria(sesionId)).isEqualTo(antes + 1);
		Map<String, Object> fila = jdbc.queryForMap("""
				SELECT organization_id, consultorio_id, actor_account_id, event_type, entity_type,
				       entity_id, previous_state, new_state, CAST(details AS CHAR) AS details,
				       reason, correlation_id
				  FROM audit_event WHERE event_type = ? AND entity_id = ?
				""", EVENTO, sesionId);
		assertThat(fila).containsEntry("organization_id", mundo.organizationId())
				.containsEntry("consultorio_id", mundo.consultorioId())
				.containsEntry("actor_account_id", mundo.profesional().cuentaId())
				.containsEntry("entity_type", "Sesion")
				.containsEntry("previous_state", "VERSION_1")
				.containsEntry("new_state", "VERSION_2");
		// El sanitizador de la auditoria redacta el id de la historia clinica: la clave queda, el
		// valor no sale. El numero de sesion si, que es lo que lleva a la fila con el detalle.
		assertThat(fila.get("details").toString())
				.contains("historiaClinicaId", "[REDACTADO]", "\"numeroSesion\": \"1\"");
		assertThat(fila.values().stream().filter(valor -> valor != null)
				.map(Object::toString).toList())
				.as("el motivo es prosa clinica y no va a la auditoria")
				.noneMatch(texto -> texto.contains(MOTIVO_SECRETO));
	}

	@Test
	@DisplayName("cada enmienda deja su propio SESION_AMENDED con su transicion")
	void una_auditoria_por_enmienda() {
		// 06.06 §7.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");

		enmendar(mundo, sesionId, "Nota 2", "motivo 2");
		enmendar(mundo, sesionId, "Nota 3", "motivo 3");

		assertThat(jdbc.queryForList("""
				SELECT CONCAT(previous_state, '>', new_state) FROM audit_event
				 WHERE event_type = ? AND entity_id = ? ORDER BY id
				""", String.class, EVENTO, sesionId))
				.containsExactly("VERSION_1>VERSION_2", "VERSION_2>VERSION_3");
	}

	@Test
	@DisplayName("una enmienda rechazada no deja auditoria ni version ni cambia el contenido")
	void el_rechazo_no_deja_rastro() {
		// 06.06: sin motivo es 400 y la transaccion se deshace entera, cabecera incluida.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		long version = version(mundo, sesionId);

		assertThatThrownBy(() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				contenido("Nota sin motivo"), "   ", version))
				.isInstanceOfSatisfying(EnmiendaSinMotivoException.class, error ->
						assertThat(handler.handleEnmiendaSinMotivo(error).getStatus())
								.isEqualTo(HttpStatus.BAD_REQUEST.value()));

		assertThat(contarAuditoria(sesionId)).isZero();
		assertThat(contarVersiones(sesionId)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT nota_de_cierre FROM sesion WHERE id = ?", String.class, sesionId))
				.as("la cabecera volvio atras: la escritura sin motivo no quedo a medias")
				.isEqualTo("Nota original");
		assertThat(jdbc.queryForObject(
				"SELECT ultimo_numero_version FROM sesion WHERE id = ?", Integer.class, sesionId))
				.isEqualTo(1);
		assertThat(versionEnBase(sesionId)).isEqualTo(version);
	}

	// =================================================================================
	// Permiso de enmendar
	// =================================================================================

	@Test
	@DisplayName("sin sesion:register no se enmienda: 403 y sin efectos")
	void sin_el_permiso_es_403() {
		// 06.06 §6: sesion:register mas propiedad. Un ADMINISTRATIVO no atiende, no registra.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		var administrativo = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
				"ADMINISTRATIVO", "admin-" + mundo.sufijo());
		long version = version(mundo, sesionId);

		assertThatThrownBy(() -> sesionService.enmendar(administrativo.actor(), mundo.consultorioId(),
				sesionId, contenido("Nota ajena"), "motivo", version))
				.isInstanceOfSatisfying(PermissionDeniedException.class, error -> {
					assertThat(error.getPermissionCode()).isEqualTo("sesion:register");
					assertThat(permisos.handlePermissionDenied(error).getStatus())
							.isEqualTo(HttpStatus.FORBIDDEN.value());
				});

		assertThat(contarAuditoria(sesionId)).isZero();
		assertThat(contarVersiones(sesionId)).isEqualTo(1);
		assertThat(versionEnBase(sesionId)).isEqualTo(version);
	}

	@Test
	@DisplayName("con el permiso pero sobre la atencion de otro profesional es 409, nunca 403")
	void atencion_ajena_es_409() {
		// 06.06 §6: la propiedad no es un permiso.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		var otro = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
				"PROFESIONAL", "otro-" + mundo.sufijo());
		long version = version(mundo, sesionId);

		assertThatThrownBy(() -> sesionService.enmendar(otro.actor(), mundo.consultorioId(), sesionId,
				contenido("Nota ajena"), "motivo", version))
				.isInstanceOfSatisfying(SesionAjenaException.class, error ->
						assertThat(handler.handleSesionAjena(error).getStatus())
								.isEqualTo(HttpStatus.CONFLICT.value()));

		assertThat(contarAuditoria(sesionId)).isZero();
		assertThat(contarVersiones(sesionId)).isEqualTo(1);
	}

	@Test
	@DisplayName("un actor de otro tenant no enmienda ni ve el historial: 404")
	void aislamiento_de_tenant() {
		// AGENT.md §6.
		Mundo a = fixtures.crearMundo();
		Mundo b = fixtures.crearMundo();
		long sesionA = sesionCerrada(a, "Nota original");
		long version = version(a, sesionA);

		assertThatThrownBy(() -> sesionService.enmendar(b.actor(), b.consultorioId(), sesionA,
				contenido("Nota ajena"), "motivo", version))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> sesionService.versiones(b.actor(), b.consultorioId(), sesionA))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> sesionService.enmendar(b.actor(), a.consultorioId(), sesionA,
				contenido("Nota ajena"), "motivo", version))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		assertThat(handler.handleSesionNoAccesible(new SesionNotAccessibleException(sesionA)).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(contarAuditoria(sesionA)).isZero();
		assertThat(contarVersiones(sesionA)).isEqualTo(1);
	}

	// =================================================================================
	// Reglas de la enmienda
	// =================================================================================

	@Test
	@DisplayName("una sesion abierta no se enmienda: 409, eso se guarda")
	void sesion_abierta() {
		// 06.06: se enmienda lo cerrado.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		long version = version(mundo, sesionId);

		assertThatThrownBy(() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				contenido("Nota"), "motivo", version))
				.isInstanceOfSatisfying(SesionNoCerradaException.class, error ->
						assertThat(handler.handleSesionNoCerrada(error).getStatus())
								.isEqualTo(HttpStatus.CONFLICT.value()));
		assertThat(contarAuditoria(sesionId)).isZero();
		assertThat(sesionService.versiones(mundo.actor(), mundo.consultorioId(), sesionId))
				.as("una sesion abierta no tiene historial: lista vacia, no error").isEmpty();
	}

	@Test
	@DisplayName("una enmienda incoherente o sin nota de cierre se rechaza sin dejar rastro")
	void contenido_invalido() {
		// 06.06: una enmienda no puede guardar lo que el camino normal habria rechazado.
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		long version = version(mundo, sesionId);

		assertThatThrownBy(() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				new ContenidoDeSesion(null, 11, null, null, null, null, null, "Nota", null, null, null, null),
				"motivo", version))
				.isInstanceOf(EvaluacionIncoherenteException.class);
		assertThatThrownBy(() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				new ContenidoDeSesion(null, null, null, null, null, null, null, null, null, null, null, null),
				"motivo", version))
				.isInstanceOf(CierreIncompletoException.class);

		assertThat(contarAuditoria(sesionId)).isZero();
		assertThat(contarVersiones(sesionId)).isEqualTo(1);
		assertThat(versionEnBase(sesionId)).isEqualTo(version);
	}

	@Test
	@DisplayName("enmendar no mueve plata: ni la asistencia ni la deuda devengada cambian")
	void no_hay_cambios_economicos_implicitos() {
		// 06.06 §5: criterio de aceptacion del plan, "no hay cambios economicos implicitos".
		Mundo mundo = fixtures.crearMundo();
		long sesionId = sesionCerrada(mundo, "Nota original");
		Map<String, Object> deudaAntes = deuda(sesionId);

		enmendar(mundo, sesionId, "Nota enmendada", "motivo");

		assertThat(deuda(sesionId)).isEqualTo(deudaAntes);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM obligacion WHERE sesion_id = ?", Long.class, sesionId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT estado FROM sesion WHERE id = ?", String.class, sesionId))
				.isEqualTo("CERRADA");
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	/** Una sesion del profesional del mundo, ya cerrada con la nota indicada (la v1). */
	private long sesionCerrada(Mundo mundo, String nota) {
		long sesionId = fixtures.crearSesion(mundo);
		sesionService.cerrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, nota, null, null, null, null),
				version(mundo, sesionId));
		return sesionId;
	}

	private SesionView enmendar(Mundo mundo, long sesionId, String nota, String motivo) {
		return sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				contenido(nota), motivo, version(mundo, sesionId));
	}

	private static ContenidoDeSesion contenido(String nota) {
		return new ContenidoDeSesion(null, null, null, null, Evolucion.IGUAL, null, null, nota,
				null, null, null, null);
	}

	private long version(Mundo mundo, long sesionId) {
		return sesionService.ver(mundo.actor(), mundo.consultorioId(), sesionId).version();
	}

	private long versionEnBase(long sesionId) {
		return jdbc.queryForObject("SELECT version FROM sesion WHERE id = ?", Long.class, sesionId);
	}

	private long contarAuditoria(long sesionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND entity_id = ?",
				Long.class, EVENTO, sesionId);
	}

	private long contarVersiones(long sesionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM sesion_version WHERE sesion_id = ?", Long.class, sesionId);
	}

	private Map<String, Object> deuda(long sesionId) {
		return jdbc.queryForMap("""
				SELECT importe_original, saldo, moneda, estado, responsable
				  FROM obligacion WHERE sesion_id = ?
				""", sesionId);
	}

	// ---------------------------------------------------------------------------------
	// Ejecucion concurrente
	// ---------------------------------------------------------------------------------

	/** La barrera es lo que hace real la carrera: sin ella la primera suele terminar antes. */
	private static List<Desenlace> enParalelo(List<Callable<SesionView>> tareas) {
		CyclicBarrier salida = new CyclicBarrier(tareas.size());
		try (ExecutorService pool = Executors.newFixedThreadPool(tareas.size())) {
			List<Future<Desenlace>> futuros = new ArrayList<>();
			for (Callable<SesionView> tarea : tareas) {
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

	private record Desenlace(SesionView sesion, Exception error) {

		boolean fallo() {
			return error != null;
		}

		@Override
		public String toString() {
			return fallo()
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(version=" + sesion.ultimoNumeroVersion() + ")";
		}
	}
}
