package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.AtencionDeParticipanteView;
import com.akine.activity.application.AtencionDeParticipantesService;
import com.akine.activity.application.DerivacionService;
import com.akine.activity.application.DerivarParticipanteCommand;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.PanelDeAtencionGrupalView;
import com.akine.activity.application.PedidoDeAtencionDeParticipante;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAtencionGrupal;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.OfertaNoClinicaException;
import com.akine.clinical.application.CasoClinicoAltaCommand;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.IntegranteDelEquipo;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.exception.DerivacionConSesionException;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.exception.CasoDeAtencionGrupalException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import com.akine.platform.spi.problem.ProblemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La atencion clinica grupal por participante, de punta a punta contra MySQL (AKINE-08.05).
 *
 * <p>La clase y las ofertas se insertan por JDBC como en {@link DerivacionParticipanteIT};
 * inscripcion, asistencia, Caso, derivacion, atencion, cierre y reversion pasan por los servicios
 * reales. Lo que mide es lo que ningun unitario puede: que {@code V102} ejecute y sus CHECK acepten
 * la fila, que dos pacientes de la misma clase tengan Sesiones y numeros independientes
 * (CA-M14-012-06, CA-M14-013-06), que el cierre grupal no devengue (U-41), que el lote no esconda
 * a nadie, que la reversion con Sesion choque, y que N inicios simultaneos dejen una sola Sesion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AtencionGrupalIT {

	private static final String JUSTIFICACION = "Prueba de integracion sintetica";
	private static final int N = 4;

	@Autowired private AtencionDeParticipantesService atenciones;
	@Autowired private DerivacionService derivaciones;
	@Autowired private InscripcionService inscripciones;
	@Autowired private AsistenciaService asistencias;
	@Autowired private CasoClinicoService casos;
	@Autowired private SesionService sesiones;
	@Autowired private JdbcTemplate jdbc;

	private Mundo mundo;
	private OperatingActor actor;
	private long maria;
	private long historiaDeMaria;

	@BeforeEach
	void preparar() {
		mundo = new EncounterFixtures(jdbc).crearMundo();
		actor = new OperatingActor(mundo.profesional().cuentaId(), false,
				mundo.organizationId(), mundo.consultorioId());
		historiaDeMaria = mundo.historiaClinicaId();
		maria = jdbc.queryForObject("SELECT persona_id FROM historia_clinica WHERE id = ?",
				Long.class, historiaDeMaria);
	}

	@Test
	@DisplayName("dos pacientes de la misma clase: Sesiones, cierres y numeros independientes, sin deuda")
	void atenciones_independientes() {
		long oferta = crearOfertaGrupal(true);
		long claseId = crearClase(oferta);
		long[] juan = crearPaciente();

		long inscripcionMaria = inscribir(claseId, maria);
		long inscripcionJuan = inscribir(claseId, juan[0]);
		long inscripcionSinDerivar = inscribir(claseId, crearPaciente()[0]);
		marcar(claseId, inscripcionMaria);
		marcar(claseId, inscripcionJuan);
		marcar(claseId, inscripcionSinDerivar);
		long casoMaria = abrirCaso(historiaDeMaria, oferta);
		long casoJuan = abrirCaso(juan[1], oferta);
		long derivacionMaria = derivar(claseId, inscripcionMaria, casoMaria);
		derivar(claseId, inscripcionJuan, casoJuan);

		ResultadoDeAtencionGrupal lote = atenciones.iniciarLote(actor, mundo.consultorioId(), claseId,
				List.of(new PedidoDeAtencionDeParticipante(inscripcionMaria, null),
						new PedidoDeAtencionDeParticipante(inscripcionJuan, null),
						new PedidoDeAtencionDeParticipante(inscripcionSinDerivar, null)),
				JUSTIFICACION);

		assertThat(lote.exitosos()).isEqualTo(2);
		assertThat(lote.items().get(2).problemType())
				.as("el que no esta derivado falla solo, con el tipo de la operacion individual")
				.isEqualTo(ProblemType.PARTICIPACION_SIN_DERIVACION.value());
		long sesionMaria = lote.items().get(0).atencion().sesionId();
		long sesionJuan = lote.items().get(1).atencion().sesionId();
		assertThat(sesionMaria).isNotEqualTo(sesionJuan);

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT turno_id, caso_id, derivacion_id, clase_id FROM sesion WHERE id = ?", sesionMaria);
		assertThat(fila.get("turno_id")).as("Turno != Clase").isNull();
		assertThat(((Number) fila.get("caso_id")).longValue()).isEqualTo(casoMaria);
		assertThat(((Number) fila.get("derivacion_id")).longValue()).isEqualTo(derivacionMaria);
		assertThat(((Number) fila.get("clase_id")).longValue()).isEqualTo(claseId);

		AtencionDeParticipanteView otraVez = atenciones.iniciar(actor, mundo.consultorioId(), claseId,
				inscripcionMaria, null, JUSTIFICACION);
		assertThat(otraVez.atencion().yaExistia()).isTrue();
		assertThat(otraVez.atencion().sesionId()).isEqualTo(sesionMaria);

		assertThatThrownBy(() -> sesiones.cambiarCaso(mundo.actor(), mundo.consultorioId(),
				sesionMaria, casoJuan, otraVez.atencion().version()))
				.isInstanceOf(CasoDeAtencionGrupalException.class);

		SesionView cerradaMaria = cerrar(sesionMaria);
		assertThat(estado(sesionJuan))
				.as("cerrar la atencion de Maria no finaliza la de Juan (CA-M14-013-06)")
				.isEqualTo("BORRADOR");
		SesionView cerradaJuan = cerrar(sesionJuan);
		assertThat(cerradaMaria.numeroEnCaso()).isEqualTo(1);
		assertThat(cerradaJuan.numeroEnCaso())
				.as("numeros independientes segun sus Casos (CA-M14-012-06)")
				.isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM obligacion WHERE sesion_id IN (?, ?)",
				Integer.class, sesionMaria, sesionJuan))
				.as("U-41: la atencion grupal no devenga; la clase se cobra por su esquema")
				.isZero();

		assertThatThrownBy(() -> derivaciones.revertir(actor, mundo.consultorioId(), claseId,
				inscripcionMaria, derivacionMaria, "Caso equivocado", JUSTIFICACION))
				.isInstanceOf(DerivacionConSesionException.class);
		assertThat(jdbc.queryForObject("SELECT estado FROM derivacion_clinica WHERE id = ?",
				String.class, derivacionMaria)).isEqualTo("VIGENTE");

		PanelDeAtencionGrupalView panel = atenciones.panel(actor, mundo.consultorioId(), claseId);
		assertThat(panel.participantes()).hasSize(3);
		assertThat(panel.participantes().stream().mapToLong(p -> p.atenciones().size()).sum())
				.isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event WHERE organization_id = ? AND event_type = ?
				""", Integer.class, mundo.organizationId(), "SESION_GRUPAL_CONSULTADA"))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("una clase no clinica no abre nada, y otro tenant no ve la sede")
	void clase_no_clinica_y_otro_tenant() {
		long claseYoga = crearClase(crearOfertaGrupal(false));
		long inscripcion = inscribir(claseYoga, maria);
		marcar(claseYoga, inscripcion);

		assertThatThrownBy(() -> atenciones.iniciar(actor, mundo.consultorioId(), claseYoga,
				inscripcion, null, JUSTIFICACION))
				.isInstanceOf(OfertaNoClinicaException.class);

		Mundo otro = new EncounterFixtures(jdbc).crearMundo();
		OperatingActor ajeno = new OperatingActor(otro.profesional().cuentaId(), false,
				otro.organizationId(), otro.consultorioId());
		assertThatThrownBy(() -> atenciones.iniciar(ajeno, mundo.consultorioId(), claseYoga,
				inscripcion, null, JUSTIFICACION))
				.isInstanceOf(ConsultorioNoAccesibleException.class);

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM sesion WHERE organization_id = ? AND clase_id IS NOT NULL",
				Integer.class, mundo.organizationId()))
				.as("ninguna Sesion de origen grupal")
				.isZero();
	}

	@Test
	@DisplayName("N inicios simultaneos del mismo participante: ninguno falla y queda una sola Sesion")
	void inicios_simultaneos() {
		long oferta = crearOfertaGrupal(true);
		long claseId = crearClase(oferta);
		long inscripcion = inscribir(claseId, maria);
		marcar(claseId, inscripcion);
		long derivacion = derivar(claseId, inscripcion, abrirCaso(historiaDeMaria, oferta));

		List<Callable<AtencionDeParticipanteView>> tareas = IntStream.range(0, N)
				.<Callable<AtencionDeParticipanteView>>mapToObj(i -> () -> atenciones.iniciar(
						actor, mundo.consultorioId(), claseId, inscripcion, null, JUSTIFICACION))
				.toList();

		List<Desenlace<AtencionDeParticipanteView>> desenlaces = ActividadItFixture.enParalelo(tareas);

		assertThat(desenlaces).as("Desenlaces: %s", desenlaces).noneMatch(Desenlace::fallo);
		assertThat(desenlaces.stream().map(d -> d.valor().atencion().sesionId()).distinct()).hasSize(1);
		assertThat(desenlaces.stream().filter(d -> !d.valor().atencion().yaExistia())).hasSize(1);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM sesion WHERE organization_id = ? AND derivacion_id = ?",
				Integer.class, mundo.organizationId(), derivacion))
				.isEqualTo(1);
	}

	// =================================================================================

	private SesionView cerrar(long sesionId) {
		long version = jdbc.queryForObject("SELECT version FROM sesion WHERE id = ?", Long.class, sesionId);
		return sesiones.cerrar(mundo.actor(), mundo.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Ejercicio grupal sintetico", null, null, null,
						null),
				version);
	}

	private String estado(long sesionId) {
		return jdbc.queryForObject("SELECT estado FROM sesion WHERE id = ?", String.class, sesionId);
	}

	private long derivar(long claseId, long inscripcionId, long casoId) {
		return derivaciones.derivar(actor, mundo.consultorioId(), claseId, inscripcionId,
				new DerivarParticipanteCommand(casoId, null, null, "Indicacion sintetica", false,
						JUSTIFICACION)).id();
	}

	/** Una persona con perfil de paciente e historia: {persona, historia}. */
	private long[] crearPaciente() {
		String apellido = "Grupal" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), apellido, apellido.toUpperCase());
		long personaId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), personaId, actor.accountId());
		jdbc.update("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), personaId, actor.accountId());
		long historiaId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		return new long[]{personaId, historiaId};
	}

	private long crearOfertaGrupal(boolean clinica) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "AGR-" + sufijo.toUpperCase(), "Servicio " + sufijo);
		long servicioId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 8, 0, ?, ?, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), mundo.consultorioId(), servicioId, "Grupal " + sufijo,
				clinica, clinica);
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

	private long inscribir(long claseId, long persona) {
		return inscripciones.inscribir(actor, mundo.consultorioId(), claseId,
				new InscribirCommand(persona, false, null)).inscripcion().id();
	}

	private void marcar(long claseId, long inscripcionId) {
		asistencias.iniciar(actor, mundo.consultorioId(), claseId);
		asistencias.registrar(actor, mundo.consultorioId(), claseId,
				new RegistrarAsistenciaCommand(inscripcionId, ResultadoAsistencia.PRESENTE,
						OrigenAsistencia.MOSTRADOR, null, null));
	}

	private long abrirCaso(long historiaId, long ofertaId) {
		var actorClinico = new com.akine.clinical.application.OperatingActor(
				actor.accountId(), false, mundo.organizationId(), mundo.consultorioId());
		return casos.abrir(actorClinico, new CasoClinicoAltaCommand(
						historiaId, ofertaId, "Lumbalgia sintetica", null,
						List.of(new IntegranteDelEquipo(
								mundo.profesional().membershipId(), RolEnCaso.RESPONSABLE)),
						true),
				JUSTIFICACION).id();
	}
}
