package com.akine;

import com.akine.clinical.application.AdjuntoClinicoAltaCommand;
import com.akine.clinical.application.AdjuntoClinicoService;
import com.akine.clinical.application.ContenidoDeAdjuntoClinico;
import com.akine.clinical.domain.CategoriaAdjuntoClinico;
import com.akine.clinical.spi.ActorDeDerivacion;
import com.akine.clinical.spi.DerivacionClinicaRegistry;
import com.akine.clinical.spi.EstadoClinicoDeParticipacion;
import com.akine.clinical.spi.OrigenDeParticipacion;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.TratamientoService;
import com.akine.encounter.application.TratamientoView;
import com.akine.encounter.domain.TratamientoAplicado;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Miembro;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import com.akine.person.application.AdjuntoAltaCommand;
import com.akine.person.application.AdjuntoService;
import com.akine.person.application.ContenidoDeAdjunto;
import com.akine.person.domain.CategoriaAdjunto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las lecturas que se AUDITAN, ejecutadas por el camino feliz contra MySQL real.
 *
 * <h2>El defecto que fija (simulacro de G-13, appKine-api #77)</h2>
 *
 * <p>Descargar cualquier adjunto administrativo daba <b>500</b>. {@code AdjuntoService.contenido}
 * corria con {@code @Transactional(readOnly = true)} y escribia {@code ADJUNTO_DOWNLOADED} en
 * {@code audit_event}. {@code AuditTrail.record} es {@code MANDATORY}: se une a la transaccion del
 * llamador, que era de solo lectura. Spring marca esa conexion como read-only, el INSERT de un id
 * {@code AUTO_INCREMENT} sale en el acto —no espera al flush— y MySQL lo rechaza con
 * "Connection is read-only. Queries leading to data modification are not allowed".
 *
 * <p>Existia desde 03.02 y ningun test lo vio: los unitarios mockean {@code AuditTrail}, y ningun IT
 * descargaba un adjunto administrativo. El barrido del mismo patron encontro dos mas, con la misma
 * forma: {@code TratamientoService.listar} —solo tenia ITs de sus caminos de error, que lanzan
 * antes de auditar— y {@code DerivacionClinicaService.consultar}, que todavia no tiene consumidor.
 *
 * <p>Cada caso afirma las dos cosas: que la lectura responde, y que <b>la fila de auditoria quedo
 * escrita</b>. Lo segundo es lo que impide el "arreglo" de sacar la auditoria.
 *
 * <p>La descarga del adjunto CLINICO ya corria con {@code @Transactional} de escritura —04.02 lo
 * escribio asi— y esta aca como control: es el mismo hecho de negocio por el otro modulo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class LecturaAuditadaIT {

	private static final String JUSTIFICACION = "Prueba de integracion sintetica";

	@Autowired private AdjuntoService adjuntos;
	@Autowired private AdjuntoClinicoService adjuntosClinicos;
	@Autowired private TratamientoService tratamientos;
	@Autowired private SesionService sesiones;
	@Autowired private DerivacionClinicaRegistry derivaciones;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	@Test
	@DisplayName("descargar un adjunto ADMINISTRATIVO responde y deja ADJUNTO_DOWNLOADED")
	void descarga_de_adjunto_administrativo() {
		Mundo mundo = fixtures.crearMundo();
		long personaId = personaDe(mundo);
		Miembro admin = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
				"CONSULTORIO_ADMIN", "adm-" + mundo.sufijo());
		var actor = new com.akine.person.application.OperatingActor(
				admin.cuentaId(), false, mundo.organizationId(), mundo.consultorioId());
		byte[] contenido = pdf("dni del paciente " + mundo.sufijo());

		long adjuntoId = adjuntos.subir(actor, personaId, new AdjuntoAltaCommand(
				CategoriaAdjunto.CREDENCIAL_COBERTURA, "Credencial sintetica", "credencial.pdf",
				"application/pdf", contenido)).adjunto().id();

		ContenidoDeAdjunto bajado = adjuntos.contenido(actor, personaId, adjuntoId);

		assertThat(bajado.contenido()).isEqualTo(contenido);
		assertThat(auditados(mundo, "ADJUNTO_DOWNLOADED"))
				.as("la descarga se audita: la fila tiene que estar en la base")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("control: descargar un adjunto CLINICO responde y deja ADJUNTO_CLINICO_DOWNLOADED")
	void descarga_de_adjunto_clinico() {
		Mundo mundo = fixtures.crearMundo();
		var actor = new com.akine.clinical.application.OperatingActor(
				mundo.profesional().cuentaId(), false, mundo.organizationId(), mundo.consultorioId());
		byte[] contenido = pdf("estudio " + mundo.sufijo());

		long adjuntoId = adjuntosClinicos.subir(actor, mundo.historiaClinicaId(),
				new AdjuntoClinicoAltaCommand(CategoriaAdjuntoClinico.ESTUDIO, null, null,
						"estudio.pdf", "application/pdf", contenido),
				JUSTIFICACION).adjunto().id();

		ContenidoDeAdjuntoClinico bajado = adjuntosClinicos.contenido(
				actor, mundo.historiaClinicaId(), adjuntoId, JUSTIFICACION);

		assertThat(bajado.contenido()).isEqualTo(contenido);
		assertThat(auditados(mundo, "ADJUNTO_CLINICO_DOWNLOADED")).isEqualTo(1);
	}

	@Test
	@DisplayName("listar los tratamientos de una sesion responde y deja TRATAMIENTO_CONSULTADO")
	void listado_de_tratamientos() {
		Mundo mundo = fixtures.crearMundo();
		long sesionId = fixtures.crearSesion(mundo);
		long version = sesiones.ver(mundo.actor(), mundo.consultorioId(), sesionId).version();
		tratamientos.registrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new TratamientoAplicado(mundo.practicaId(), null, "Rodilla", null, 20, null, null,
						null, List.of()),
				version);

		List<TratamientoView> vistas =
				tratamientos.listar(mundo.actor(), mundo.consultorioId(), sesionId);

		assertThat(vistas).hasSize(1);
		assertThat(auditados(mundo, "TRATAMIENTO_CONSULTADO")).isEqualTo(1);
	}

	@Test
	@DisplayName("consultar las derivaciones de una participacion responde y deja DERIVACION_CLINICA_ACCESSED")
	void consulta_de_derivaciones() {
		Mundo mundo = fixtures.crearMundo();
		long personaId = personaDe(mundo);
		ActorDeDerivacion actor = new ActorDeDerivacion(mundo.profesional().cuentaId(), false,
				mundo.organizationId(), mundo.consultorioId(), JUSTIFICACION);

		EstadoClinicoDeParticipacion estado = derivaciones.consultar(
				actor, OrigenDeParticipacion.CLASE_PROGRAMADA, 999_999L, personaId);

		assertThat(estado.historiaClinicaId()).isEqualTo(mundo.historiaClinicaId());
		assertThat(auditados(mundo, "DERIVACION_CLINICA_ACCESSED")).isEqualTo(1);
	}

	// =================================================================================

	private long personaDe(Mundo mundo) {
		return jdbc.queryForObject("SELECT persona_id FROM historia_clinica WHERE id = ?",
				Long.class, mundo.historiaClinicaId());
	}

	private int auditados(Mundo mundo, String eventType) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ?
				""", Integer.class, mundo.organizationId(), eventType);
	}

	/** Un PDF minimo: los servicios detectan el tipo por la firma de los bytes. */
	private static byte[] pdf(String marca) {
		return ("%PDF-1.7\n% " + marca + "\n").getBytes(StandardCharsets.UTF_8);
	}
}
