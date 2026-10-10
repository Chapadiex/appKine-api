package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.exception.SesionNotAccessibleException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE-G-5: la lectura y el cierre de una sesion dejan su fila en {@code audit_event}, contra
 * MySQL real.
 *
 * <h2>Por que contra la base y no con un mock</h2>
 *
 * <p>07.07 ({@code d6ea5d0}) cerro "encounter no auditaba nada" con unitarios que verificaban que el
 * servicio <b>llamaba</b> al puerto. La integracion del 29/09 perdio el cambio al resolver el merge y
 * nada fallo, porque los unitarios se fueron con el. Y aunque hubieran quedado, no prueban lo que
 * importa: con {@code @Transactional(readOnly = true)} el flush queda en MANUAL y la fila de la
 * lectura <b>no llega nunca a la tabla</b> mientras el mock sigue recibiendo la llamada. Es el
 * escenario 61 de {@code docs/tests-diferidos.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class SesionAuditadaIT {

	@Autowired private SesionService sesionService;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	@Test
	@DisplayName("G5-S1 leer una sesion escribe SESION_ACCEDIDA en la base, con ids y sin contenido")
	void la_lectura_llega_a_la_tabla() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);

		sesionService.ver(mundo.actor(), mundo.consultorioId(), sesionId);

		assertThat(contar("SESION_ACCEDIDA", sesionId)).isEqualTo(1L);
		Map<String, Object> fila = fila("SESION_ACCEDIDA", sesionId);
		assertThat(((Number) fila.get("organization_id")).longValue()).isEqualTo(mundo.organizationId());
		assertThat(((Number) fila.get("consultorio_id")).longValue()).isEqualTo(mundo.consultorioId());
		assertThat(((Number) fila.get("actor_account_id")).longValue())
				.isEqualTo(mundo.profesional().cuentaId());
		assertThat(fila.get("entity_type")).isEqualTo("Sesion");
		assertThat((String) fila.get("details")).contains("historiaClinicaId");
	}

	@Test
	@DisplayName("G5-S2 cerrar escribe SESION_CERRADA con la transicion, y la nota no viaja")
	void el_cierre_llega_a_la_tabla() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		long version = sesionService.ver(mundo.actor(), mundo.consultorioId(), sesionId).version();

		sesionService.cerrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "nota clinica sintetica G5", null, null, null,
						null),
				version);

		assertThat(contar("SESION_CERRADA", sesionId)).isEqualTo(1L);
		Map<String, Object> fila = fila("SESION_CERRADA", sesionId);
		assertThat(fila.get("previous_state")).isEqualTo("ABIERTA");
		assertThat(fila.get("new_state")).isEqualTo("CERRADA");
		assertThat((String) fila.get("details")).doesNotContain("nota clinica sintetica G5");
	}

	@Test
	@DisplayName("G5-S3 una sesion inexistente no deja rastro: auditarla seria un padron de existencia")
	void la_inexistente_no_deja_fila() {
		Mundo mundo = fixtures.crearMundo();
		long inexistente = 987_654_321L;

		assertThatThrownBy(() -> sesionService.ver(mundo.actor(), mundo.consultorioId(), inexistente))
				.isInstanceOf(SesionNotAccessibleException.class);
		assertThat(contar("SESION_ACCEDIDA", inexistente)).isZero();
	}

	private long contar(String evento, long sesionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND entity_id = ?",
				Long.class, evento, sesionId);
	}

	private Map<String, Object> fila(String evento, long sesionId) {
		return jdbc.queryForMap("""
				SELECT organization_id, consultorio_id, actor_account_id, entity_type,
				       previous_state, new_state, CAST(details AS CHAR) AS details
				  FROM audit_event WHERE event_type = ? AND entity_id = ?
				""", evento, sesionId);
	}
}
