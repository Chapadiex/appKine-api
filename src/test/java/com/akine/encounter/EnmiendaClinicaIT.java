package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.application.MedicionService;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionVersionView;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.application.TratamientoView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.ContenidoDeSesion;
import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.Lateralidad;
import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.encounter.domain.MedicionEnmendada;
import com.akine.encounter.domain.ParametroAplicado;
import com.akine.encounter.domain.TipoDatoParametro;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.domain.TratamientoEnmendado;
import com.akine.encounter.domain.ValorMedido;
import com.akine.encounter.domain.exception.EnmiendaCambiaPracticasException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * C-6 contra MySQL real: las enmiendas versionan tambien tratamientos y mediciones.
 *
 * <p>Lo que solo contesta el motor: que {@code V71} ejecute con sus {@code CHECK} y FK de nombre
 * propio, que la foto de la version anterior quede intacta cuando la enmienda corrige la tabla
 * viva, que dos enmiendas concurrentes con tratamientos terminen en una version y un 409, y que
 * un rechazo deshaga todo —cabecera, tratamientos y foto— en la misma transaccion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EnmiendaClinicaIT {

	private static final String MOTIVO = "Se corrigio la zona: era hombro y no rodilla";

	@Autowired private SesionService sesionService;
	@Autowired private TratamientoService tratamientoService;
	@Autowired private MedicionService medicionService;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	@Test
	@DisplayName("V71 ejecuto: las tres tablas existen con organization_id NOT NULL y sus CHECK propios")
	void v71_ejecuto() {
		for (String tabla : List.of("sesion_version_tratamiento",
				"sesion_version_tratamiento_parametro", "sesion_version_medicion")) {
			assertThat(jdbc.queryForObject("""
					SELECT is_nullable FROM information_schema.columns
					 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = 'organization_id'
					""", String.class, tabla)).as(tabla).isEqualTo("NO");
		}
		assertThat(jdbc.queryForList("""
				SELECT constraint_name FROM information_schema.table_constraints
				 WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
				   AND table_name LIKE 'sesion_version_%'
				""", String.class))
				.contains("ck_svt_lateralidad", "ck_svtp_valor", "ck_svm_valor_tipado");
		assertThat(jdbc.queryForObject("""
				SELECT version FROM flyway_schema_history WHERE version = '71' AND success = 1
				""", String.class)).isEqualTo("71");
	}

	@Test
	@DisplayName("enmendar un tratamiento y una medicion deja la v1 intacta y la v2 con el cambio")
	void la_version_anterior_queda_intacta() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView tratamiento = tratamientoService.registrar(mundo.actor(),
				mundo.consultorioId(), sesionId, aplicado(mundo.practicaId(), "Rodilla",
						new BigDecimal("2.5")), version(mundo, sesionId));
		medicionService.registrar(mundo.actor(), mundo.consultorioId(), sesionId,
				mundo.medicionDefinicionId(), LateralidadMedicion.DERECHA,
				new ValorMedido(new BigDecimal("90"), null, null), null);
		cerrar(mundo, sesionId);

		SesionView enmendada = sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				contenido(), List.of(new TratamientoEnmendado(tratamiento.id(),
						aplicado(mundo.practicaId(), "Hombro", new BigDecimal("4.0")))),
				List.of(new MedicionEnmendada(mundo.medicionDefinicionId(),
						LateralidadMedicion.DERECHA, new ValorMedido(new BigDecimal("120"), null, null),
						"corregida")),
				MOTIVO, version(mundo, sesionId));

		assertThat(enmendada.ultimoNumeroVersion()).isEqualTo(2);
		List<SesionVersionView> versiones =
				sesionService.versiones(mundo.actor(), mundo.consultorioId(), sesionId);
		assertThat(versiones).hasSize(2);

		SesionVersionView.TratamientoEnVersion antes = versiones.get(0).tratamientos().getFirst();
		assertThat(antes.zona()).as("la v1 sigue diciendo lo que decia").isEqualTo("Rodilla");
		assertThat(antes.parametros().getFirst().valorNumerico()).isEqualByComparingTo("2.5");
		assertThat(versiones.get(0).mediciones().getFirst().valorNumerico())
				.isEqualByComparingTo("90");

		SesionVersionView.TratamientoEnVersion despues = versiones.get(1).tratamientos().getFirst();
		assertThat(despues.tratamientoId()).isEqualTo(tratamiento.id());
		assertThat(despues.zona()).isEqualTo("Hombro");
		assertThat(despues.parametros().getFirst().valorNumerico()).isEqualByComparingTo("4.0");
		assertThat(versiones.get(1).mediciones().getFirst().valorNumerico())
				.isEqualByComparingTo("120");

		assertThat(jdbc.queryForObject("SELECT zona FROM tratamiento_realizado WHERE id = ?",
				String.class, tratamiento.id())).as("la tabla viva dice lo vigente").isEqualTo("Hombro");
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM sesion_version_tratamiento svt
				  JOIN sesion_version sv ON sv.id = svt.sesion_version_id
				 WHERE sv.sesion_id = ?
				""", Long.class, sesionId)).isEqualTo(2);
	}

	@Test
	@DisplayName("un tratamiento quitado por enmienda queda de BAJA con el motivo y sigue en la v1")
	void quitar_un_tratamiento_es_baja_logica() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView uno = tratamientoService.registrar(mundo.actor(), mundo.consultorioId(),
				sesionId, aplicado(mundo.practicaId(), "Rodilla", null), version(mundo, sesionId));
		TratamientoView duplicado = tratamientoService.registrar(mundo.actor(), mundo.consultorioId(),
				sesionId, aplicado(mundo.practicaId(), "Rodilla", null), version(mundo, sesionId));
		cerrar(mundo, sesionId);

		sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId, contenido(),
				List.of(new TratamientoEnmendado(uno.id(), aplicado(mundo.practicaId(), "Rodilla", null))),
				null, "Se habia cargado dos veces", version(mundo, sesionId));

		assertThat(jdbc.queryForMap("""
				SELECT active, deactivation_reason FROM tratamiento_realizado WHERE id = ?
				""", duplicado.id()))
				.containsEntry("active", false)
				.containsEntry("deactivation_reason", "Se habia cargado dos veces");
		List<SesionVersionView> versiones =
				sesionService.versiones(mundo.actor(), mundo.consultorioId(), sesionId);
		assertThat(versiones.get(0).tratamientos()).hasSize(2);
		assertThat(versiones.get(1).tratamientos())
				.extracting(SesionVersionView.TratamientoEnVersion::tratamientoId)
				.containsExactly(uno.id());
	}

	@Test
	@DisplayName("una enmienda que cambia la practica es 409 y no deja nada a medias")
	void cambiar_la_practica_es_409_y_se_deshace() {
		Mundo mundo = fixtures.crearMundo();
		long otraPractica = fixtures.crearPractica(mundo.organizationId(), mundo.especialidadId(),
				"PR2-" + mundo.sufijo(), "Otra " + mundo.sufijo());
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView tratamiento = tratamientoService.registrar(mundo.actor(),
				mundo.consultorioId(), sesionId, aplicado(mundo.practicaId(), "Rodilla", null),
				version(mundo, sesionId));
		cerrar(mundo, sesionId);
		long version = version(mundo, sesionId);

		assertThatThrownBy(() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(),
				sesionId, contenido(), List.of(new TratamientoEnmendado(tratamiento.id(),
						aplicado(otraPractica, "Rodilla", null))), null, MOTIVO, version))
				.isInstanceOf(EnmiendaCambiaPracticasException.class);

		assertThat(jdbc.queryForObject("SELECT ultimo_numero_version FROM sesion WHERE id = ?",
				Integer.class, sesionId)).as("la cabecera volvio atras").isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT version FROM sesion WHERE id = ?",
				Long.class, sesionId)).isEqualTo(version);
		assertThat(jdbc.queryForObject("SELECT practica_id FROM tratamiento_realizado WHERE id = ?",
				Long.class, tratamiento.id())).isEqualTo(mundo.practicaId());
	}

	@Test
	@DisplayName("dos enmiendas concurrentes con tratamientos: una entra y la otra recibe 409")
	void dos_enmiendas_concurrentes() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView tratamiento = tratamientoService.registrar(mundo.actor(),
				mundo.consultorioId(), sesionId, aplicado(mundo.practicaId(), "Rodilla", null),
				version(mundo, sesionId));
		cerrar(mundo, sesionId);
		long leida = version(mundo, sesionId);

		List<Desenlace> desenlaces = enParalelo(List.of(
				() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
						contenido(), List.of(new TratamientoEnmendado(tratamiento.id(),
								aplicado(mundo.practicaId(), "Hombro", null))), null, "A", leida),
				() -> sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
						contenido(), List.of(new TratamientoEnmendado(tratamiento.id(),
								aplicado(mundo.practicaId(), "Cuello", null))), null, "B", leida)));

		assertThat(desenlaces.stream().filter(d -> d.error() == null).count())
				.as("exactamente una entra. Desenlaces: %s", desenlaces).isEqualTo(1);
		assertThat(desenlaces.stream().filter(d -> d.error() != null).findFirst().orElseThrow().error())
				.isInstanceOfAny(OptimisticLockingFailureException.class,
						DataIntegrityViolationException.class);

		assertThat(jdbc.queryForList("""
				SELECT numero_version FROM sesion_version WHERE sesion_id = ? ORDER BY numero_version
				""", Integer.class, sesionId)).containsExactly(1, 2);
		String zonaViva = jdbc.queryForObject(
				"SELECT zona FROM tratamiento_realizado WHERE id = ?", String.class, tratamiento.id());
		assertThat(sesionService.versiones(mundo.actor(), mundo.consultorioId(), sesionId).get(1)
				.tratamientos().getFirst().zona())
				.as("la foto de la v2 es la de la enmienda que gano, y coincide con la tabla viva")
				.isEqualTo(zonaViva);
	}

	@Test
	@DisplayName("enmendar la sesion de otro tenant es 404 y no toca nada")
	void otro_tenant_es_404() {
		Mundo mundo = fixtures.crearMundo();
		Mundo ajeno = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView tratamiento = tratamientoService.registrar(mundo.actor(),
				mundo.consultorioId(), sesionId, aplicado(mundo.practicaId(), "Rodilla", null),
				version(mundo, sesionId));
		cerrar(mundo, sesionId);
		long version = version(mundo, sesionId);

		assertThatThrownBy(() -> sesionService.enmendar(ajeno.actor(), ajeno.consultorioId(),
				sesionId, contenido(), List.of(new TratamientoEnmendado(tratamiento.id(),
						aplicado(mundo.practicaId(), "Hombro", null))), null, MOTIVO, version))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThatThrownBy(() -> sesionService.versiones(
				ajeno.actor(), ajeno.consultorioId(), sesionId))
				.isInstanceOf(SesionNotAccessibleException.class);

		assertThat(jdbc.queryForObject("SELECT zona FROM tratamiento_realizado WHERE id = ?",
				String.class, tratamiento.id())).isEqualTo("Rodilla");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesion_version WHERE sesion_id = ?",
				Long.class, sesionId)).isEqualTo(1);
	}

	@Test
	@DisplayName("editar en el lugar un tratamiento de una sesion cerrada sigue siendo 409: se enmienda")
	void la_sesion_cerrada_no_se_edita_en_el_lugar() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView tratamiento = tratamientoService.registrar(mundo.actor(),
				mundo.consultorioId(), sesionId, aplicado(mundo.practicaId(), "Rodilla", null),
				version(mundo, sesionId));
		cerrar(mundo, sesionId);

		assertThatThrownBy(() -> tratamientoService.reemplazar(mundo.actor(), mundo.consultorioId(),
				sesionId, tratamiento.id(), aplicado(mundo.practicaId(), "Hombro", null),
				version(mundo, sesionId)))
				.isInstanceOf(SesionCerradaException.class);
		assertThatThrownBy(() -> medicionService.registrar(mundo.actor(), mundo.consultorioId(),
				sesionId, mundo.medicionDefinicionId(), LateralidadMedicion.DERECHA,
				new ValorMedido(new BigDecimal("90"), null, null), null))
				.isInstanceOf(SesionCerradaException.class);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private void cerrar(Mundo mundo, long sesionId) {
		sesionService.cerrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				version(mundo, sesionId));
	}

	private long version(Mundo mundo, long sesionId) {
		return sesionService.ver(mundo.actor(), mundo.consultorioId(), sesionId).version();
	}

	private static ContenidoDeSesion contenido() {
		return new ContenidoDeSesion(null, null, null, null, Evolucion.IGUAL, null, null,
				"Terapia manual", null, null, null, null);
	}

	private static TratamientoAplicado aplicado(long practicaId, String zona, BigDecimal intensidad) {
		return new TratamientoAplicado(practicaId, null, zona, Lateralidad.DERECHA, 20, null, null,
				null, intensidad == null
						? List.of()
						: List.of(new ParametroAplicado("intensidad", TipoDatoParametro.NUMERICO,
								intensidad, null, null, "mA")));
	}

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

		@Override
		public String toString() {
			return error != null
					? "FALLO(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"
					: "OK(version=" + sesion.ultimoNumeroVersion() + ")";
		}
	}
}
