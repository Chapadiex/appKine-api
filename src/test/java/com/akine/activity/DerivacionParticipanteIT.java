package com.akine.activity;

import com.akine.testsupport.InsertarConId;
import com.akine.TestcontainersConfiguration;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.DerivacionService;
import com.akine.activity.application.DerivacionView;
import com.akine.activity.application.DerivarParticipanteCommand;
import com.akine.activity.application.EstadoDeDerivacionView;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.OfertaNoClinicaException;
import com.akine.activity.domain.exception.ParticipacionSinAsistenciaException;
import com.akine.activity.domain.exception.PersonaSinPerfilPacienteException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La derivacion de un participante de clase al circuito clinico, de punta a punta contra MySQL
 * (AKINE-08.04, escenario 65 de {@code docs/tests-diferidos.md}).
 *
 * <p>La clase y las ofertas se insertan por JDBC, como en {@link ActividadItFixture}; inscripcion,
 * inicio, asistencia, Caso y derivacion pasan por los servicios reales. Lo que mide: que la fila
 * {@code derivacion_clinica} nazca colgada de la ASISTENCIA, que la auditoria quede en la misma
 * transaccion, que el doble submit no duplique, que la reversion conserve la fila, y que los gates
 * grupales corten <b>antes</b> de escribir nada clinico.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class DerivacionParticipanteIT {

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
	@DisplayName("derivar crea la fila colgada de la asistencia, audita, es idempotente y se revierte sin borrarse")
	void derivar_consultar_y_revertir() {
		long ofertaClinica = crearOfertaGrupal(true);
		long claseId = crearClase(ofertaClinica);
		long inscripcionId = inscribir(claseId, personaId);
		long asistenciaId = marcar(claseId, inscripcionId, ResultadoAsistencia.PRESENTE);
		long casoId = abrirCaso(ofertaClinica);

		DerivacionView creada = derivaciones.derivar(actor, mundo.consultorioId(), claseId,
				inscripcionId, comando(casoId, false));
		DerivacionView repetida = derivaciones.derivar(actor, mundo.consultorioId(), claseId,
				inscripcionId, comando(casoId, false));

		assertThat(creada.yaExistia()).isFalse();
		assertThat(creada.asistenciaId()).isEqualTo(asistenciaId);
		assertThat(creada.historiaClinicaId()).isEqualTo(mundo.historiaClinicaId());
		assertThat(creada.requiereCasoClinico()).isTrue();
		assertThat(repetida.yaExistia()).isTrue();
		assertThat(repetida.id()).isEqualTo(creada.id());
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM derivacion_clinica
				 WHERE organization_id = ? AND origen = 'CLASE_PROGRAMADA' AND participacion_id = ?
				   AND caso_clinico_id = ? AND estado = 'VIGENTE'
				""", Integer.class, mundo.organizationId(), asistenciaId, casoId))
				.as("el doble submit no duplica: una sola fila vigente por destino")
				.isEqualTo(1);
		assertThat(auditados("DERIVACION_CLINICA_CREATED")).isEqualTo(1);

		EstadoDeDerivacionView estado = derivaciones.consultar(actor, mundo.consultorioId(),
				claseId, inscripcionId, JUSTIFICACION);
		assertThat(estado.historiaClinicaId()).isEqualTo(mundo.historiaClinicaId());
		assertThat(estado.derivaciones()).extracting(DerivacionView::id).containsExactly(creada.id());

		DerivacionView revertida = derivaciones.revertir(actor, mundo.consultorioId(), claseId,
				inscripcionId, creada.id(), "Caso equivocado", JUSTIFICACION);
		assertThat(revertida.estado()).isEqualTo("REVERTIDA");
		assertThat(jdbc.queryForObject(
				"SELECT estado FROM derivacion_clinica WHERE id = ?", String.class, creada.id()))
				.as("la reversion conserva la fila")
				.isEqualTo("REVERTIDA");
		assertThat(auditados("DERIVACION_CLINICA_REVERTED")).isEqualTo(1);
	}

	@Test
	@DisplayName("los gates grupales cortan antes de escribir: oferta no clinica, ausente y persona sin perfil")
	void gates_grupales() {
		long casoId = abrirCaso(crearOfertaGrupal(true));

		long claseNoClinica = crearClase(crearOfertaGrupal(false));
		long enYoga = inscribir(claseNoClinica, personaId);
		marcar(claseNoClinica, enYoga, ResultadoAsistencia.PRESENTE);
		assertThatThrownBy(() -> derivaciones.derivar(actor, mundo.consultorioId(), claseNoClinica,
				enYoga, comando(casoId, false)))
				.isInstanceOf(OfertaNoClinicaException.class);

		long claseClinica = crearClase(crearOfertaGrupal(true));
		long ausente = inscribir(claseClinica, personaId);
		long sinPerfil = crearPersonaSinPerfil();
		long inscripcionSinPerfil = inscribir(claseClinica, sinPerfil);
		marcar(claseClinica, ausente, ResultadoAsistencia.AUSENTE);
		assertThatThrownBy(() -> derivaciones.derivar(actor, mundo.consultorioId(), claseClinica,
				ausente, comando(casoId, false)))
				.isInstanceOf(ParticipacionSinAsistenciaException.class);

		marcar(claseClinica, inscripcionSinPerfil, ResultadoAsistencia.PRESENTE);
		assertThatThrownBy(() -> derivaciones.derivar(actor, mundo.consultorioId(), claseClinica,
				inscripcionSinPerfil, comando(casoId, false)))
				.isInstanceOf(PersonaSinPerfilPacienteException.class);

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM derivacion_clinica WHERE organization_id = ?",
				Integer.class, mundo.organizationId()))
				.as("ningun gate grupal deja una fila clinica")
				.isZero();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM perfil_paciente WHERE organization_id = ? AND persona_id = ?",
				Integer.class, mundo.organizationId(), sinPerfil))
				.as("sin la bandera, nadie se convierte en paciente de costado")
				.isZero();
	}

	// =================================================================================

	private DerivarParticipanteCommand comando(long casoId, boolean activarPerfil) {
		return new DerivarParticipanteCommand(casoId, null, null, "Indicacion sintetica",
				activarPerfil, JUSTIFICACION);
	}

	private long crearOfertaGrupal(boolean clinica) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		long servicioId = InsertarConId.insertar(jdbc, """
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "DER-" + sufijo.toUpperCase(), "Servicio " + sufijo);
		return InsertarConId.insertar(jdbc, """
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 8, 0, ?, ?, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), mundo.consultorioId(), servicioId, "Grupal " + sufijo,
				clinica, clinica);
	}

	private long crearClase(long ofertaId) {
		return InsertarConId.insertar(jdbc, """
				INSERT INTO clase_programada (organization_id, consultorio_id, oferta_id, titulo,
				                              inicio, fin, capacidad, estado,
				                              programado_por_cuenta_id, programado_en, version)
				VALUES (?, ?, ?, 'Clase sintetica',
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY),
				        DATE_ADD(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY), INTERVAL 1 HOUR),
				        8, 'PROGRAMADA', ?, UTC_TIMESTAMP(6), 0)
				""", mundo.organizationId(), mundo.consultorioId(), ofertaId, actor.accountId());
	}

	private long inscribir(long claseId, long persona) {
		return inscripciones.inscribir(actor, mundo.consultorioId(), claseId,
				new InscribirCommand(persona, false, null)).inscripcion().id();
	}

	private long marcar(long claseId, long inscripcionId, ResultadoAsistencia resultado) {
		// Iniciar es idempotente: la primera marca abre la clase, las siguientes no cambian nada.
		asistencias.iniciar(actor, mundo.consultorioId(), claseId);
		return asistencias.registrar(actor, mundo.consultorioId(), claseId,
				new RegistrarAsistenciaCommand(inscripcionId, resultado, OrigenAsistencia.MOSTRADOR,
						null, null)).asistencia().id();
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

	private long crearPersonaSinPerfil() {
		String apellido = "SinPerfil" + UUID.randomUUID().toString().substring(0, 8);
		return InsertarConId.insertar(jdbc, """
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetica', ?, 'SINTETICA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), apellido, apellido.toUpperCase());
	}

	private int auditados(String eventType) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ?
				""", Integer.class, mundo.organizationId(), eventType);
	}
}
