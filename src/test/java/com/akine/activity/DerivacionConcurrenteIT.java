package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.DerivacionService;
import com.akine.activity.application.DerivacionView;
import com.akine.activity.application.DerivarParticipanteCommand;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.clinical.application.CasoClinicoAltaCommand;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.IntegranteDelEquipo;
import com.akine.clinical.domain.RolEnCaso;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos (o mas) derivaciones SIMULTANEAS del mismo participante al mismo Caso (AKINE-08.04).
 *
 * <p>La sospecha: el pre-chequeo de {@code DerivacionClinicaService.registrar} no ve la fila del
 * otro, los dos intentan el INSERT y el perdedor choca contra el unique
 * {@code uk_derivacion_vigente}. El {@code catch} relee "la del ganador" <b>en la misma
 * transaccion</b>, que ya quedo marcada para rollback por la excepcion del repositorio y que bajo
 * {@code REPEATABLE READ} no ve la fila commiteada despues de su foto. Si eso pasa, el perdedor
 * recibe un 500 en vez de la derivacion ya existente.
 *
 * <p>Lo que se exige es la promesa del contrato: <b>ninguna falla</b>, una sola fila vigente, una
 * sola auditoria de alta, y todos devuelven el mismo id — uno con {@code yaExistia=false} y el
 * resto con {@code true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DerivacionConcurrenteIT {

	private static final int N = 4;
	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	@Autowired private DerivacionService derivaciones;
	@Autowired private InscripcionService inscripciones;
	@Autowired private AsistenciaService asistencias;
	@Autowired private CasoClinicoService casos;
	@Autowired private JdbcTemplate jdbc;

	private Mundo mundo;
	private OperatingActor actor;
	private long personaId;

	@BeforeEach
	void preparar() {
		mundo = new EncounterFixtures(jdbc).crearMundo();
		actor = new OperatingActor(mundo.profesional().cuentaId(), false,
				mundo.organizationId(), mundo.consultorioId());
		personaId = jdbc.queryForObject("SELECT persona_id FROM historia_clinica WHERE id = ?",
				Long.class, mundo.historiaClinicaId());
	}

	@Test
	@DisplayName("N derivaciones simultaneas del mismo participante: ninguna falla, una sola fila, mismo id")
	void derivaciones_simultaneas_no_fallan_ni_duplican() {
		long oferta = crearOfertaGrupal();
		long claseId = crearClase(oferta);
		long inscripcionId = inscripciones.inscribir(actor, mundo.consultorioId(), claseId,
				new InscribirCommand(personaId, false, null)).inscripcion().id();
		asistencias.iniciar(actor, mundo.consultorioId(), claseId);
		long asistenciaId = asistencias.registrar(actor, mundo.consultorioId(), claseId,
				new RegistrarAsistenciaCommand(inscripcionId, ResultadoAsistencia.PRESENTE,
						OrigenAsistencia.MOSTRADOR, null, null)).asistencia().id();
		long casoId = abrirCaso(oferta);

		DerivarParticipanteCommand comando = new DerivarParticipanteCommand(
				casoId, null, null, "Indicacion sintetica", false, JUSTIFICACION);
		List<Callable<DerivacionView>> tareas = IntStream.range(0, N)
				.<Callable<DerivacionView>>mapToObj(i -> () -> derivaciones.derivar(
						actor, mundo.consultorioId(), claseId, inscripcionId, comando))
				.toList();

		List<Desenlace<DerivacionView>> desenlaces = ActividadItFixture.enParalelo(tareas);

		assertThat(desenlaces)
				.as("el perdedor de la carrera recibe la derivacion existente, no un error. Desenlaces: %s",
						desenlaces)
				.noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().map(d -> d.valor().id()).distinct())
				.as("todos devuelven la misma derivacion")
				.hasSize(1);
		assertThat(desenlaces.stream().filter(d -> !d.valor().yaExistia()))
				.as("exactamente una la creo")
				.hasSize(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM derivacion_clinica
				 WHERE organization_id = ? AND origen = 'CLASE_PROGRAMADA' AND participacion_id = ?
				   AND caso_clinico_id = ? AND estado = 'VIGENTE'
				""", Integer.class, mundo.organizationId(), asistenciaId, casoId))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ?
				""", Integer.class, mundo.organizationId(), "DERIVACION_CLINICA_CREATED"))
				.as("una sola alta auditada")
				.isEqualTo(1);
	}

	// =================================================================================

	private long crearOfertaGrupal() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "DEC-" + sufijo.toUpperCase(), "Servicio " + sufijo);
		long servicioId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 8, 0, 1, 1, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), mundo.consultorioId(), servicioId, "Grupal " + sufijo);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long crearClase(long ofertaId) {
		jdbc.update("""
				INSERT INTO clase_programada (organization_id, consultorio_id, oferta_id, titulo,
				                              inicio, fin, capacidad, estado,
				                              programado_por_cuenta_id, programado_en, version)
				VALUES (?, ?, ?, 'Clase sintetica',
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY),
				        DATE_ADD(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY), INTERVAL 1 HOUR),
				        8, 'PROGRAMADA', ?, UTC_TIMESTAMP(6), 0)
				""", mundo.organizationId(), mundo.consultorioId(), ofertaId, actor.accountId());
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long abrirCaso(long ofertaId) {
		var actorClinico = new com.akine.clinical.application.OperatingActor(
				actor.accountId(), false, mundo.organizationId(), mundo.consultorioId());
		return casos.abrir(actorClinico, new CasoClinicoAltaCommand(
						mundo.historiaClinicaId(), ofertaId, "Lumbalgia sintetica", null,
						List.of(new IntegranteDelEquipo(
								mundo.profesional().membershipId(), RolEnCaso.RESPONSABLE)),
						true),
				JUSTIFICACION).id();
	}
}
