package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.api.EncounterProblemHandler;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionVersionView;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.application.TratamientoView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.domain.exception.BorradorNoEsJsonException;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los cuatro pedidos que la pantalla de atencion (appKine-web #80) le hizo a {@code encounter},
 * contra MySQL real: lo que importa es lo que el motor hace con la columna {@code JSON}, que
 * {@code caso_id} ahora se escribe de verdad y que la version que se anuncia es la de la base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PedidosDeLaAtencionIT {

	@Autowired private SesionService sesionService;
	@Autowired private TratamientoService tratamientoService;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;
	private final EncounterProblemHandler handler = new EncounterProblemHandler();

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	// =================================================================================
	// 1. Borrador que no es JSON
	// =================================================================================

	@Test
	@DisplayName("un borrador que no es JSON es 400 con el campo, y la base no se toca")
	void borrador_que_no_es_json() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		sesionService.guardarBorrador(mundo.actor(), mundo.consultorioId(), sesionId,
				"{\"motivoConsulta\":\"lumbalgia\"}", 0L);

		assertThatThrownBy(() -> sesionService.guardarBorrador(
				mundo.actor(), mundo.consultorioId(), sesionId, "no es json", 1L))
				.isInstanceOfSatisfying(BorradorNoEsJsonException.class, error -> {
					var problem = handler.handleBorradorNoEsJson(error);
					assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
					assertThat(problem.getProperties()).containsKey("errors");
					assertThat(problem.getProperties().get("errors").toString())
							.startsWith("{contenido=");
				});

		assertThat(jdbc.queryForObject(
				"SELECT JSON_UNQUOTE(JSON_EXTRACT(borrador, '$.motivoConsulta')) FROM sesion WHERE id = ?",
				String.class, sesionId)).isEqualTo("lumbalgia");
		assertThat(versionEnBase(sesionId)).as("un rechazo no consume version").isEqualTo(1L);
	}

	@Test
	@DisplayName("lo que el validador acepta, la columna JSON lo acepta: escalares, null y 99 niveles")
	void lo_que_se_acepta_entra_en_la_columna() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		long version = 0L;

		for (String valido : new String[] {"\"texto\"", "42", "null", "[]",
				"[".repeat(99) + "]".repeat(99)}) {
			version = sesionService.guardarBorrador(
					mundo.actor(), mundo.consultorioId(), sesionId, valido, version).version();
		}

		assertThat(jdbc.queryForObject("SELECT JSON_DEPTH(borrador) FROM sesion WHERE id = ?",
				Integer.class, sesionId)).isEqualTo(99);
	}

	// =================================================================================
	// 2. La baja de un tratamiento devuelve la version nueva
	// =================================================================================

	@Test
	@DisplayName("la baja de un tratamiento anuncia la version de la sesion que quedo en la base")
	void la_baja_anuncia_la_version_de_la_base() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		TratamientoView alta = tratamientoService.registrar(mundo.actor(), mundo.consultorioId(),
				sesionId, new TratamientoAplicado(mundo.practicaId(), null, "Rodilla", null, null,
						null, null, null, List.of()), 0L);

		TratamientoView baja = tratamientoService.darDeBaja(mundo.actor(), mundo.consultorioId(),
				sesionId, alta.id(), "cargado por error", alta.sesionVersion());

		assertThat(baja.vigente()).isFalse();
		assertThat(baja.sesionVersion()).isEqualTo(versionEnBase(sesionId)).isEqualTo(2L);

		// Y la version anunciada sirve para la escritura siguiente, sin releer la sesion.
		sesionService.guardarBorrador(mundo.actor(), mundo.consultorioId(), sesionId, "{}",
				baja.sesionVersion());
	}

	// =================================================================================
	// 3. Nombre del autor en el historial
	// =================================================================================

	@Test
	@DisplayName("cada version del historial trae el nombre visible de la cuenta que la escribio")
	void el_historial_trae_el_nombre() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		sesionService.cerrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null), 0L);

		List<SesionVersionView> historial =
				sesionService.versiones(mundo.actor(), mundo.consultorioId(), sesionId);

		assertThat(historial).singleElement().satisfies(version -> {
			assertThat(version.registradaPor()).isEqualTo(mundo.profesional().cuentaId());
			assertThat(version.registradaPorNombre()).isEqualTo("Sintetico DePrueba");
		});
	}

	// =================================================================================
	// 4. Asociar o cambiar el caso de una sesion iniciada
	// =================================================================================

	@Test
	@DisplayName("cambiar el caso escribe caso_id, avanza la version una vez, audita, y el cierre "
			+ "numera dentro del caso nuevo")
	void cambiar_el_caso_y_cerrar() {
		Mundo mundo = fixtures.crearMundo();
		long casoUno = fixtures.crearCaso(mundo, 1);
		long casoDos = fixtures.crearCaso(mundo, 2);
		long sesionId = fixtures.crearSesion(mundo);

		SesionView conUno = sesionService.cambiarCaso(
				mundo.actor(), mundo.consultorioId(), sesionId, casoUno, 0L);
		SesionView conDos = sesionService.cambiarCaso(
				mundo.actor(), mundo.consultorioId(), sesionId, casoDos, conUno.version());

		assertThat(conDos.casoId()).isEqualTo(casoDos);
		assertThat(conDos.version()).isEqualTo(versionEnBase(sesionId)).isEqualTo(2L);
		assertThat(jdbc.queryForObject("SELECT caso_id FROM sesion WHERE id = ?", Long.class,
				sesionId)).isEqualTo(casoDos);
		assertThat(jdbc.queryForList("""
				SELECT new_state FROM audit_event
				 WHERE event_type = 'SESION_CASO_CAMBIADO' AND entity_id = ? ORDER BY id
				""", String.class, sesionId))
				.containsExactly("CASO_" + casoUno, "CASO_" + casoDos);

		SesionView cerrada = sesionService.cerrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				conDos.version());
		assertThat(cerrada.numeroEnCaso()).isEqualTo(1);

		// Cerrada, el caso queda fijo: ya numero dentro de el.
		assertThatThrownBy(() -> sesionService.cambiarCaso(mundo.actor(), mundo.consultorioId(),
				sesionId, casoUno, cerrada.version()))
				.isInstanceOf(SesionCerradaException.class);
		assertThat(jdbc.queryForObject("SELECT caso_id FROM sesion WHERE id = ?", Long.class,
				sesionId)).isEqualTo(casoDos);
	}

	@Test
	@DisplayName("quitar el caso deja caso_id en NULL; un caso cerrado o de otro paciente no entra")
	void quitar_y_casos_no_asignables() {
		Mundo mundo = fixtures.crearMundo();
		Mundo otro = fixtures.crearMundo();
		long caso = fixtures.crearCaso(mundo, 1);
		long cerrado = fixtures.crearCaso(mundo, 2);
		long ajeno = fixtures.crearCaso(otro, 1);
		jdbc.update("""
				UPDATE caso_clinico SET estado = 'CERRADO', cerrado_en = UTC_TIMESTAMP(6),
				       cerrado_por = 1, motivo_cierre = 'Alta' WHERE id = ?
				""", cerrado);
		long sesionId = fixtures.crearSesion(mundo);

		long version = sesionService.cambiarCaso(
				mundo.actor(), mundo.consultorioId(), sesionId, caso, 0L).version();
		SesionView sinCaso = sesionService.cambiarCaso(
				mundo.actor(), mundo.consultorioId(), sesionId, null, version);

		assertThat(sinCaso.casoId()).isNull();
		assertThat(jdbc.queryForObject("SELECT caso_id FROM sesion WHERE id = ?", Long.class,
				sesionId)).isNull();

		assertThatThrownBy(() -> sesionService.cambiarCaso(mundo.actor(), mundo.consultorioId(),
				sesionId, cerrado, sinCaso.version()))
				.isInstanceOfSatisfying(CasoNoAsignableException.class, error ->
						assertThat(handler.handleCasoNoAsignable(error).getStatus())
								.isEqualTo(HttpStatus.CONFLICT.value()));
		assertThatThrownBy(() -> sesionService.cambiarCaso(mundo.actor(), mundo.consultorioId(),
				sesionId, ajeno, sinCaso.version()))
				.isInstanceOfSatisfying(CasoNoAsignableException.class, error ->
						assertThat(handler.handleCasoNoAsignable(error).getStatus())
								.isEqualTo(HttpStatus.NOT_FOUND.value()));
		assertThat(versionEnBase(sesionId)).isEqualTo(sinCaso.version());
	}

	private long versionEnBase(long sesionId) {
		return jdbc.queryForObject("SELECT version FROM sesion WHERE id = ?", Long.class, sesionId);
	}
}
