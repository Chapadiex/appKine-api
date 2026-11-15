package com.akine.encounter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akine.TestcontainersConfiguration;
import com.akine.clinical.domain.exception.AccesoClinicoNoJustificadoException;
import com.akine.encounter.application.EvolucionDelCasoService;
import com.akine.encounter.application.EvolucionDelCasoView;
import com.akine.encounter.domain.evolucion.CodigoDeHallazgo;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Miembro;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M31 contra PostgreSQL: la lectura del caso, los turnos con cancelados, la puerta clinica y la
 * auditoria (RF-M31-001, RF-M31-002, RN-M31-003, RN-M31-005, RN-M31-008).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "akine.evolucion.enabled=true")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class EvolucionDelCasoIT {

	private static final Instant AHORA = Instant.now().truncatedTo(ChronoUnit.MICROS);

	@Autowired private EvolucionDelCasoService evolucion;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;
	private final AtomicInteger numeroDeSesion = new AtomicInteger();

	@BeforeEach
	void setUp() {
		fixtures = new EncounterFixtures(jdbc);
		numeroDeSesion.set(0);
	}

	@Test
	@DisplayName("el profesional que atiende el caso ve indicadores, alertas y advertencias, y queda auditado")
	void analiza_el_caso_y_audita() {
		Mundo m = fixtures.crearMundo();
		long caso = fixtures.crearCaso(m, 1);
		int[] dolor = {7, 7, 7, 6, 6, 6};
		for (int i = 0; i < dolor.length; i++) {
			Instant cuando = AHORA.minus(60 - 7L * i, ChronoUnit.DAYS);
			long turno = turno(m, caso, cuando, "CONFIRMADO", null);
			long sesion = sesionCerrada(m, caso, turno, cuando, dolor[i]);
			medicion(m, sesion, cuando, 90 + (i % 2));
		}
		turno(m, caso, AHORA.minus(14, ChronoUnit.DAYS), "AUSENTE", null);
		turno(m, caso, AHORA.minus(10, ChronoUnit.DAYS), "AUSENTE", null);
		turno(m, caso, AHORA.minus(5, ChronoUnit.DAYS), "CANCELADO", "PACIENTE");
		turno(m, caso, AHORA.minus(4, ChronoUnit.DAYS), "CANCELADO", "INSTITUCION");
		sesionCerrada(m, null, null, AHORA.minus(40, ChronoUnit.DAYS), 5);

		EvolucionDelCasoView vista = evolucion.analizar(m.actor(), m.consultorioId(), caso, null);

		assertThat(vista.analisis().sesionesAnalizadas()).isEqualTo(6);
		assertThat(vista.analisis().indicadores().dolor().puntos()).hasSize(6);
		assertThat(vista.analisis().indicadores().adherencia().citas()).isEqualTo(9);
		assertThat(vista.analisis().indicadores().adherencia().inasistencias()).isEqualTo(3);
		assertThat(vista.analisis().indicadores().adherencia().canceladosPorPaciente()).isEqualTo(1);
		assertThat(vista.analisis().indicadores().mediciones()).singleElement()
				.satisfies(serie -> assertThat(serie.serie().puntos()).hasSize(6));
		assertThat(vista.hallazgos()).extracting(EvolucionDelCasoView.HallazgoView::codigo)
				.contains(CodigoDeHallazgo.DOLOR_SIN_MEJORA, CodigoDeHallazgo.BAJA_ADHERENCIA,
						CodigoDeHallazgo.MEDICION_SIN_CAMBIO, CodigoDeHallazgo.SIN_PLAN_ACTIVO);
		assertThat(vista.hallazgos()).allSatisfy(h -> assertThat(h.texto()).isNotBlank());
		assertThat(vista.advertencias()).singleElement().asString().contains("1 sesión");

		Integer auditorias = jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'EVOLUCION_DEL_CASO_ANALIZADA'
				   AND entity_id = ? AND details ->> 'via' = 'RELACION_ASISTENCIAL'
				""", Integer.class, m.organizationId(), caso);
		assertThat(auditorias).isEqualTo(1);
	}

	@Test
	@DisplayName("un profesional sin relacion necesita justificacion; con ella entra y queda auditada")
	void sin_relacion_exige_justificacion() {
		Mundo m = fixtures.crearMundo();
		long caso = fixtures.crearCaso(m, 1);
		sesionCerrada(m, caso, null, AHORA.minus(3, ChronoUnit.DAYS), 4);
		Miembro otro = fixtures.crearMiembro(m.organizationId(), m.consultorioId(), "PROFESIONAL",
				"otro-" + m.sufijo());

		assertThatThrownBy(() -> evolucion.analizar(otro.actor(), m.consultorioId(), caso, null))
				.isInstanceOf(AccesoClinicoNoJustificadoException.class);

		EvolucionDelCasoView vista = evolucion.analizar(otro.actor(), m.consultorioId(), caso,
				"Interconsulta pedida por el equipo");
		assertThat(vista.hallazgos()).extracting(EvolucionDelCasoView.HallazgoView::codigo)
				.containsExactly(CodigoDeHallazgo.DATOS_INSUFICIENTES);
		Integer conMotivo = jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'EVOLUCION_DEL_CASO_ANALIZADA'
				   AND entity_id = ? AND reason = 'Interconsulta pedida por el equipo'
				""", Integer.class, m.organizationId(), caso);
		assertThat(conMotivo).isEqualTo(1);
	}

	@Test
	@DisplayName("sin hc:read no se ve, y el caso de otra organizacion responde como inexistente")
	void sin_permiso_y_otro_tenant() {
		Mundo m = fixtures.crearMundo();
		long caso = fixtures.crearCaso(m, 1);
		Miembro administrativo = fixtures.crearMiembro(m.organizationId(), m.consultorioId(),
				"ADMINISTRATIVO", "adm-" + m.sufijo());
		Mundo otro = fixtures.crearMundo();

		assertThatThrownBy(() -> evolucion.analizar(administrativo.actor(), m.consultorioId(), caso,
				"Necesito verlo")).isInstanceOf(PermissionDeniedException.class);
		assertThatThrownBy(() -> evolucion.analizar(otro.actor(), otro.consultorioId(), caso, null))
				.isInstanceOf(CasoNoAsignableException.class);
	}

	// =================================================================================
	// Armado
	// =================================================================================

	private long turno(Mundo m, long caso, Instant inicio, String estado, String origenCancelacion) {
		boolean cancelado = "CANCELADO".equals(estado);
		boolean ausente = "AUSENTE".equals(estado);
		return InsertarConId.insertar(jdbc, """
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, caso_id, inicio, fin, estado,
				                   motivo_cancelacion, cancelado_en, cancelado_por_cuenta_id,
				                   origen_cancelacion, ausente_en, reservado_por_cuenta_id,
				                   reservado_en, deleted_at, version, created_at, updated_at)
				VALUES (?, ?, ?, (SELECT persona_id FROM historia_clinica WHERE id = ?), ?, ?, ?, ?, ?,
				        ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
				m.organizationId(), m.consultorioId(), m.ofertaId(), m.historiaClinicaId(),
				m.profesional().membershipId(), caso, ts(inicio), ts(inicio.plus(1, ChronoUnit.HOURS)),
				estado,
				cancelado ? "Sintetico" : null,
				cancelado ? ts(inicio.minus(1, ChronoUnit.DAYS)) : null,
				cancelado ? m.profesional().cuentaId() : null,
				origenCancelacion,
				ausente ? ts(inicio.plus(1, ChronoUnit.HOURS)) : null,
				m.profesional().cuentaId(),
				ts(inicio.minus(7, ChronoUnit.DAYS)),
				cancelado ? ts(inicio.minus(1, ChronoUnit.DAYS)) : null);
	}

	private long sesionCerrada(Mundo m, Long caso, Long turno, Instant cerradaEn, int dolorEva) {
		int numero = numeroDeSesion.incrementAndGet();
		return InsertarConId.insertar(jdbc, """
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, caso_id, numero_en_caso, turno_id,
				                    numero_sesion, cerrada_en, cerrada_por_cuenta_id, asistencia,
				                    dolor_eva, nota_de_cierre, ultimo_numero_version, version,
				                    created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'CERRADA', ?, ?, ?, ?, ?, ?, ?, ?, 'PRESENTE', ?, 'Sintetica', 1, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""",
				m.organizationId(), m.consultorioId(), m.historiaClinicaId(), m.ofertaId(),
				m.profesional().membershipId(), ts(cerradaEn.minus(1, ChronoUnit.HOURS)),
				m.profesional().cuentaId(), caso, caso == null ? null : numero, turno, numero,
				ts(cerradaEn), m.profesional().cuentaId(), dolorEva);
	}

	private void medicion(Mundo m, long sesion, Instant cuando, int valor) {
		jdbc.update("""
				INSERT INTO sesion_medicion (organization_id, sesion_id, definicion_id, definicion_codigo,
				                             definicion_nombre, definicion_unidad, definicion_tipo,
				                             definicion_version, lateralidad, valor_numerico,
				                             registrada_en, registrada_por_cuenta_id, version,
				                             created_at, updated_at)
				VALUES (?, ?, ?, 'ROM', 'Rango articular', 'grados', 'NUMERICO', 0, 'DERECHA', ?, ?, ?, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", m.organizationId(), sesion, m.medicionDefinicionId(), valor, ts(cuando),
				m.profesional().cuentaId());
	}

	private static Timestamp ts(Instant instante) {
		return Timestamp.from(instante);
	}
}
