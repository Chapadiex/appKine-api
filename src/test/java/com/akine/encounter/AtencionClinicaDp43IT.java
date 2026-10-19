package com.akine.encounter;

import com.akine.TestcontainersConfiguration;
import com.akine.encounter.api.EncounterProblemHandler;
import com.akine.encounter.application.IdempotencyKeyConflictException;
import com.akine.encounter.application.InicioSinTurno;
import com.akine.encounter.application.OperatingActor;
import com.akine.encounter.application.PoliticaDeEnmiendaService;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.ContenidoDeSesion;
import com.akine.encounter.domain.Evolucion;
import com.akine.encounter.domain.exception.CasoClinicoRequeridoException;
import com.akine.encounter.domain.exception.EnmiendaFueraDeVentanaException;
import com.akine.encounter.domain.exception.MotivoObligatorioException;
import com.akine.encounter.domain.exception.OfertaNoAtendibleException;
import com.akine.encounter.domain.exception.ProfesionalNoAsignableException;
import com.akine.encounter.domain.exception.SesionAjenaException;
import com.akine.encounter.domain.exception.SesionCerradaException;
import com.akine.encounter.support.EncounterFixtures;
import com.akine.encounter.support.EncounterFixtures.Miembro;
import com.akine.encounter.support.EncounterFixtures.Mundo;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DP-43 contra MySQL real: caso obligatorio por oferta (U-09, RF-M10-007), atencion sin turno y
 * reemplazo autorizado del profesional (U-24) y permiso y ventana para enmendar (U-25).
 *
 * <p>Lo que solo contesta el motor: que V115 acepte la atencion sin turno con su motivo y rechace
 * el motivo en una de turno, que el unique de idempotencia resuelva el doble click concurrente con
 * una sola fila, que el reemplazo deje su registro y mueva al dueño de verdad, y que la ventana se
 * cuente contra el {@code cerrada_en} guardado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AtencionClinicaDp43IT {

	private static final String MOTIVO = "Paciente con dolor agudo que espero sin turno";

	@Autowired private SesionService sesionService;
	@Autowired private PoliticaDeEnmiendaService politicas;
	@Autowired private JdbcTemplate jdbc;

	private EncounterFixtures fixtures;
	private final EncounterProblemHandler handler = new EncounterProblemHandler();

	@BeforeEach
	void preparar() {
		fixtures = new EncounterFixtures(jdbc);
	}

	// =================================================================================
	// U-09 — el caso es obligatorio solo en las ofertas que lo exigen
	// =================================================================================

	@Nested
	@DisplayName("U-09: caso obligatorio por oferta")
	class CasoObligatorio {

		@Test
		@DisplayName("una oferta que exige caso no inicia el turno sin caso: 409 y ni sesion ni nada")
		void sin_caso_es_409() {
			Mundo mundo = fixtures.crearMundo();
			exigirCaso(mundo);
			long turnoId = turno(mundo);

			assertThatThrownBy(() -> sesionService.iniciar(
					mundo.actor(), mundo.consultorioId(), turnoId, null))
					.isInstanceOfSatisfying(CasoClinicoRequeridoException.class, error -> {
						ProblemDetail problema = handler.handleCasoClinicoRequerido(error);
						assertThat(problema.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
						assertThat(problema.getType().toString())
								.isEqualTo("https://akine.app/problems/caso-clinico-requerido");
						assertThat(problema.getProperties()).containsEntry("ofertaId", mundo.ofertaId());
					});

			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesion WHERE turno_id = ?",
					Long.class, turnoId)).isZero();
		}

		@Test
		@DisplayName("con caso inicia, y una oferta que no lo exige sigue iniciando sin caso")
		void con_caso_o_sin_exigencia_inicia() {
			Mundo exige = fixtures.crearMundo();
			exigirCaso(exige);
			long caso = fixtures.crearCaso(exige, 1);
			SesionView conCaso = sesionService.iniciar(
					exige.actor(), exige.consultorioId(), turno(exige), caso);
			assertThat(conCaso.casoId()).isEqualTo(caso);

			Mundo libre = fixtures.crearMundo();
			SesionView sinCaso = sesionService.iniciar(
					libre.actor(), libre.consultorioId(), turno(libre), null);
			assertThat(sinCaso.casoId()).as("Pilates General no pide un caso artificial").isNull();
		}

		@Test
		@DisplayName("no se puede quitar el caso de una atencion cuya oferta lo exige")
		void quitar_el_caso_es_409() {
			Mundo mundo = fixtures.crearMundo();
			exigirCaso(mundo);
			long caso = fixtures.crearCaso(mundo, 1);
			SesionView sesion = sesionService.iniciar(
					mundo.actor(), mundo.consultorioId(), turno(mundo), caso);

			assertThatThrownBy(() -> sesionService.cambiarCaso(mundo.actor(), mundo.consultorioId(),
					sesion.id(), null, sesion.version()))
					.isInstanceOf(CasoClinicoRequeridoException.class);
			assertThat(casoEnBase(sesion.id())).isEqualTo(caso);
		}

		@Test
		@DisplayName("una atencion vieja sin caso no cierra PRESENTE hasta tener caso; AUSENTE cierra")
		void la_atencion_vieja_sin_caso() {
			// Las sesiones viejas no se migran: la que estaba abierta sin caso se cierra
			// asignandole uno. Una ausencia no es prestacion y no pide caso artificial.
			Mundo mundo = fixtures.crearMundo();
			long vieja = fixtures.crearSesion(mundo);
			long ausente = fixtures.crearSesion(mundo);
			exigirCaso(mundo);

			assertThatThrownBy(() -> sesionService.cerrar(mundo.actor(), mundo.consultorioId(), vieja,
					presente("Terapia manual"), version(vieja)))
					.isInstanceOf(CasoClinicoRequeridoException.class);
			assertThat(jdbc.queryForObject("SELECT numero_sesion FROM sesion WHERE id = ?",
					Integer.class, vieja)).as("sin numero consumido").isNull();

			long caso = fixtures.crearCaso(mundo, 1);
			sesionService.cambiarCaso(mundo.actor(), mundo.consultorioId(), vieja, caso, version(vieja));
			assertThat(sesionService.cerrar(mundo.actor(), mundo.consultorioId(), vieja,
					presente("Terapia manual"), version(vieja)).numeroEnCaso()).isEqualTo(1);

			SesionView cerradaAusente = sesionService.cerrar(mundo.actor(), mundo.consultorioId(),
					ausente, new CierreDeSesion(Asistencia.AUSENTE, null, null, null, null, null),
					version(ausente));
			assertThat(cerradaAusente.casoId()).isNull();
			assertThat(cerradaAusente.numeroSesion()).isNotNull();
		}
	}

	// =================================================================================
	// U-24 — atencion sin turno
	// =================================================================================

	@Nested
	@DisplayName("U-24: atencion sin turno")
	class SinTurno {

		@Test
		@DisplayName("abre la atencion sin turno con su motivo, y el motivo no va a la auditoria")
		void abre_sin_turno() {
			Mundo mundo = fixtures.crearMundo();

			SesionView sesion = sesionService.iniciarSinTurno(mundo.actor(), mundo.consultorioId(),
					pedido(mundo, null), null);

			assertThat(sesion.turnoId()).isNull();
			assertThat(sesion.motivoSinTurno()).isEqualTo(MOTIVO);
			assertThat(sesion.historiaClinicaId()).isEqualTo(mundo.historiaClinicaId());
			assertThat(sesion.profesionalId()).isEqualTo(mundo.profesional().membershipId());
			assertThat(jdbc.queryForObject("SELECT motivo_sin_turno FROM sesion WHERE id = ?",
					String.class, sesion.id())).isEqualTo(MOTIVO);

			String detalles = jdbc.queryForObject("""
					SELECT CAST(details AS CHAR) FROM audit_event
					 WHERE event_type = 'SESION_INICIADA' AND entity_id = ?
					""", String.class, sesion.id());
			assertThat(detalles).contains("sinTurno").doesNotContain(MOTIVO);
		}

		@Test
		@DisplayName("sin motivo es 400 y no abre nada")
		void sin_motivo() {
			Mundo mundo = fixtures.crearMundo();
			InicioSinTurno sinMotivo = new InicioSinTurno(persona(mundo), mundo.ofertaId(), null, "  ");

			assertThatThrownBy(() -> sesionService.iniciarSinTurno(
					mundo.actor(), mundo.consultorioId(), sinMotivo, null))
					.isInstanceOfSatisfying(MotivoObligatorioException.class, error ->
							assertThat(handler.handleMotivoObligatorio(error).getStatus())
									.isEqualTo(HttpStatus.BAD_REQUEST.value()));
			assertThat(sesionesDe(mundo)).isZero();
		}

		@Test
		@DisplayName("V115: el motivo sin turno no entra en una atencion de turno")
		void el_check_del_motivo() {
			Mundo mundo = fixtures.crearMundo();
			SesionView deTurno = sesionService.iniciar(
					mundo.actor(), mundo.consultorioId(), turno(mundo), null);

			assertThatThrownBy(() -> jdbc.update(
					"UPDATE sesion SET motivo_sin_turno = 'x' WHERE id = ?", deTurno.id()))
					.hasMessageContaining("ck_sesion_motivo_solo_sin_turno");
		}

		@Test
		@DisplayName("la misma clave devuelve la misma atencion; otro pedido con la clave es 409")
		void idempotencia() {
			Mundo mundo = fixtures.crearMundo();
			String clave = UUID.randomUUID().toString();

			SesionView primera = sesionService.iniciarSinTurno(
					mundo.actor(), mundo.consultorioId(), pedido(mundo, null), clave);
			SesionView reintento = sesionService.iniciarSinTurno(
					mundo.actor(), mundo.consultorioId(), pedido(mundo, null), clave);
			assertThat(reintento.id()).isEqualTo(primera.id());

			long caso = fixtures.crearCaso(mundo, 1);
			assertThatThrownBy(() -> sesionService.iniciarSinTurno(
					mundo.actor(), mundo.consultorioId(), pedido(mundo, caso), clave))
					.isInstanceOfSatisfying(IdempotencyKeyConflictException.class, error ->
							assertThat(handler.handleIdempotencyKeyConflict(error).getType().toString())
									.endsWith("/idempotency-key-conflict"));
			assertThat(sesionesDe(mundo)).isEqualTo(1);
		}

		@Test
		@DisplayName("el doble click concurrente con la misma clave deja UNA atencion y devuelve la misma")
		void doble_click_concurrente() {
			Mundo mundo = fixtures.crearMundo();
			String clave = UUID.randomUUID().toString();
			Callable<SesionView> abrir = () -> sesionService.iniciarSinTurno(
					mundo.actor(), mundo.consultorioId(), pedido(mundo, null), clave);

			List<Desenlace> desenlaces = enParalelo(List.of(abrir, abrir, abrir));

			assertThat(desenlaces).as("%s", desenlaces).noneMatch(Desenlace::fallo);
			assertThat(desenlaces).extracting(d -> d.sesion().id()).containsOnly(desenlaces.get(0).sesion().id());
			assertThat(sesionesDe(mundo)).isEqualTo(1);
		}

		@Test
		@DisplayName("una oferta de otro tenant es 404; un profesional no habilitado, 409")
		void oferta_y_habilitacion() {
			Mundo mundo = fixtures.crearMundo();
			Mundo otro = fixtures.crearMundo();

			assertThatThrownBy(() -> sesionService.iniciarSinTurno(mundo.actor(), mundo.consultorioId(),
					new InicioSinTurno(persona(mundo), otro.ofertaId(), null, MOTIVO), null))
					.isInstanceOfSatisfying(OfertaNoAtendibleException.class, error ->
							assertThat(handler.handleOfertaNoAtendible(error).getStatus())
									.isEqualTo(HttpStatus.NOT_FOUND.value()));

			// Con una habilitacion declarada para OTRO profesional, la lista deja de ser "todos".
			Miembro otroProfesional = fixtures.crearMiembro(mundo.organizationId(),
					mundo.consultorioId(), "PROFESIONAL", "hab-" + mundo.sufijo());
			habilitar(mundo, otroProfesional.membershipId());

			assertThatThrownBy(() -> sesionService.iniciarSinTurno(mundo.actor(), mundo.consultorioId(),
					pedido(mundo, null), null))
					.isInstanceOfSatisfying(OfertaNoAtendibleException.class, error -> {
						ProblemDetail problema = handler.handleOfertaNoAtendible(error);
						assertThat(problema.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
						assertThat(problema.getType().toString())
								.endsWith("/profesional-no-habilitado-en-oferta");
					});
			assertThat(sesionService.iniciarSinTurno(otroProfesional.actor(), mundo.consultorioId(),
					pedido(mundo, null), null).profesionalId()).isEqualTo(otroProfesional.membershipId());
		}

		@Test
		@DisplayName("sin turno tambien exige caso si la oferta lo exige, y sin sesion:register es 403")
		void caso_y_permiso() {
			Mundo mundo = fixtures.crearMundo();
			exigirCaso(mundo);
			assertThatThrownBy(() -> sesionService.iniciarSinTurno(
					mundo.actor(), mundo.consultorioId(), pedido(mundo, null), null))
					.isInstanceOf(CasoClinicoRequeridoException.class);

			Miembro administrativo = fixtures.crearMiembro(mundo.organizationId(),
					mundo.consultorioId(), "ADMINISTRATIVO", "adm-" + mundo.sufijo());
			assertThatThrownBy(() -> sesionService.iniciarSinTurno(administrativo.actor(),
					mundo.consultorioId(), pedido(mundo, fixtures.crearCaso(mundo, 1)), null))
					.isInstanceOfSatisfying(PermissionDeniedException.class, error ->
							assertThat(error.getPermissionCode()).isEqualTo("sesion:register"));
			assertThat(sesionesDe(mundo)).isZero();
		}
	}

	// =================================================================================
	// U-24 — reemplazo autorizado del profesional
	// =================================================================================

	@Nested
	@DisplayName("U-24: reemplazo del profesional")
	class Reemplazo {

		@Test
		@DisplayName("el CONSULTORIO_ADMIN reemplaza: cambia el dueño, queda el registro y la ajena sigue siendo 409")
		void reemplaza() {
			Mundo mundo = fixtures.crearMundo();
			long sesionId = fixtures.crearSesion(mundo);
			Miembro admin = admin(mundo);
			Miembro nuevo = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
					"PROFESIONAL", "nuevo-" + mundo.sufijo());
			long leida = version(sesionId);

			SesionView reemplazada = sesionService.reemplazarProfesional(admin.actor(),
					mundo.consultorioId(), sesionId, nuevo.membershipId(), "Licencia por enfermedad", leida);

			assertThat(reemplazada.profesionalId()).isEqualTo(nuevo.membershipId());
			assertThat(reemplazada.version()).as("avanza una vez").isEqualTo(leida + 1);
			assertThat(jdbc.queryForMap("""
					SELECT profesional_anterior_membership_id AS anterior,
					       profesional_nuevo_membership_id AS nuevo, motivo,
					       reemplazado_por_cuenta_id AS por
					  FROM sesion_reemplazo_profesional WHERE sesion_id = ?
					""", sesionId))
					.containsEntry("anterior", mundo.profesional().membershipId())
					.containsEntry("nuevo", nuevo.membershipId())
					.containsEntry("motivo", "Licencia por enfermedad")
					.containsEntry("por", admin.cuentaId());
			String detalles = jdbc.queryForObject("""
					SELECT CAST(details AS CHAR) FROM audit_event
					 WHERE event_type = 'SESION_PROFESIONAL_REEMPLAZADO' AND entity_id = ?
					""", String.class, sesionId);
			assertThat(detalles).doesNotContain("Licencia");

			// El anterior ya no escribe: la propiedad no se aflojo, cambio de dueño.
			assertThatThrownBy(() -> sesionService.guardarBorrador(mundo.actor(), mundo.consultorioId(),
					sesionId, "{\"a\":1}", reemplazada.version()))
					.isInstanceOf(SesionAjenaException.class);
			assertThat(sesionService.guardarBorrador(nuevo.actor(), mundo.consultorioId(), sesionId,
					"{\"a\":1}", reemplazada.version()).borrador()).isEqualTo("{\"a\":1}");
		}

		@Test
		@DisplayName("el profesional no reemplaza (403), ni se reemplaza en una cerrada (409)")
		void permiso_y_estado() {
			Mundo mundo = fixtures.crearMundo();
			long abierta = fixtures.crearSesion(mundo);
			Miembro nuevo = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
					"PROFESIONAL", "nuevo-" + mundo.sufijo());

			assertThatThrownBy(() -> sesionService.reemplazarProfesional(mundo.actor(),
					mundo.consultorioId(), abierta, nuevo.membershipId(), "motivo", version(abierta)))
					.isInstanceOfSatisfying(PermissionDeniedException.class, error ->
							assertThat(error.getPermissionCode()).isEqualTo("sesion:reassign"));

			sesionService.cerrar(mundo.actor(), mundo.consultorioId(), abierta, presente("Nota"),
					version(abierta));
			assertThatThrownBy(() -> sesionService.reemplazarProfesional(admin(mundo).actor(),
					mundo.consultorioId(), abierta, nuevo.membershipId(), "motivo", version(abierta)))
					.isInstanceOf(SesionCerradaException.class);
			assertThat(reemplazos(abierta)).isZero();
		}

		@Test
		@DisplayName("quien entra tiene que poder atender: un ADMINISTRATIVO es 409; una version vieja, 409")
		void destino_y_version() {
			Mundo mundo = fixtures.crearMundo();
			long sesionId = fixtures.crearSesion(mundo);
			Miembro admin = admin(mundo);
			Miembro administrativo = fixtures.crearMiembro(mundo.organizationId(),
					mundo.consultorioId(), "ADMINISTRATIVO", "adm-" + mundo.sufijo());
			Miembro nuevo = fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
					"PROFESIONAL", "nuevo-" + mundo.sufijo());

			assertThatThrownBy(() -> sesionService.reemplazarProfesional(admin.actor(),
					mundo.consultorioId(), sesionId, administrativo.membershipId(), "motivo",
					version(sesionId)))
					.isInstanceOf(ProfesionalNoAsignableException.class);
			assertThatThrownBy(() -> sesionService.reemplazarProfesional(admin.actor(),
					mundo.consultorioId(), sesionId, nuevo.membershipId(), "motivo",
					version(sesionId) + 7))
					.isInstanceOf(OptimisticLockingFailureException.class);
			assertThat(reemplazos(sesionId)).isZero();
			assertThat(jdbc.queryForObject(
					"SELECT profesional_membership_id FROM sesion WHERE id = ?", Long.class, sesionId))
					.isEqualTo(mundo.profesional().membershipId());
		}
	}

	// =================================================================================
	// U-25 — sesion:amend y ventana de enmienda
	// =================================================================================

	@Nested
	@DisplayName("U-25: ventana para enmendar")
	class Ventana {

		@Test
		@DisplayName("fuera de la ventana por defecto (30 dias) es 409 con las fechas; con 60 dias vuelve a poder")
		void la_ventana() {
			Mundo mundo = fixtures.crearMundo();
			long sesionId = cerrada(mundo);
			Instant cerradaEn = Instant.now().minus(Duration.ofDays(31));
			jdbc.update("UPDATE sesion SET cerrada_en = ? WHERE id = ?",
					java.sql.Timestamp.from(cerradaEn), sesionId);

			assertThat(sesionService.ver(mundo.actor(), mundo.consultorioId(), sesionId).enmendableHasta())
					.isBefore(Instant.now());
			assertThatThrownBy(() -> enmendar(mundo, sesionId))
					.isInstanceOfSatisfying(EnmiendaFueraDeVentanaException.class, error -> {
						ProblemDetail problema = handler.handleEnmiendaFueraDeVentana(error);
						assertThat(problema.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
						assertThat(problema.getType().toString()).endsWith("/enmienda-fuera-de-ventana");
						assertThat(problema.getProperties()).containsEntry("ventanaDias", 30);
					});
			assertThat(jdbc.queryForObject(
					"SELECT COUNT(*) FROM sesion_version WHERE sesion_id = ?", Long.class, sesionId))
					.as("sin version nueva").isEqualTo(1);

			Miembro orgAdmin = fixtures.crearMiembroDeOrganizacion(mundo.organizationId(),
					"ORG_ADMIN", "oa-" + mundo.sufijo());
			OperatingActor oa = orgAdmin.actor();
			var porDefecto = politicas.ver(oa);
			assertThat(porDefecto.porDefecto()).isTrue();
			assertThat(politicas.fijar(oa, 60, porDefecto.version()).ventanaDias()).isEqualTo(60);

			assertThat(enmendar(mundo, sesionId).ultimoNumeroVersion()).isEqualTo(2);
		}

		@Test
		@DisplayName("la politica: version para cambiarla, rango 1..3650 y solo quien administra la organizacion")
		void la_politica() {
			Mundo mundo = fixtures.crearMundo();
			OperatingActor oa = fixtures.crearMiembroDeOrganizacion(mundo.organizationId(),
					"ORG_ADMIN", "oa-" + mundo.sufijo()).actor();

			var primera = politicas.fijar(oa, 45, null);
			assertThat(primera.porDefecto()).isFalse();
			assertThatThrownBy(() -> politicas.fijar(oa, 50, null))
					.as("la version leida era la del valor por defecto")
					.isInstanceOf(OptimisticLockingFailureException.class);
			assertThat(politicas.fijar(oa, 50, primera.version()).ventanaDias()).isEqualTo(50);
			assertThatThrownBy(() -> politicas.fijar(oa, 0, politicas.ver(oa).version()))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> politicas.fijar(mundo.actor(), 10, politicas.ver(oa).version()))
					.isInstanceOfSatisfying(PermissionDeniedException.class, error ->
							assertThat(error.getPermissionCode()).isEqualTo("consultorio:manage"));
			assertThat(politicas.ventanaDe(mundo.organizationId())).isEqualTo(50);
			assertThat(jdbc.queryForObject("""
					SELECT COUNT(*) FROM audit_event
					 WHERE event_type = 'POLITICA_DE_ENMIENDA_FIJADA' AND organization_id = ?
					""", Long.class, mundo.organizationId())).isEqualTo(2);
		}
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private void exigirCaso(Mundo mundo) {
		jdbc.update("UPDATE oferta_servicio_consultorio SET requiere_caso_clinico = 1 WHERE id = ?",
				mundo.ofertaId());
	}

	private long persona(Mundo mundo) {
		return jdbc.queryForObject("SELECT persona_id FROM historia_clinica WHERE id = ?",
				Long.class, mundo.historiaClinicaId());
	}

	private InicioSinTurno pedido(Mundo mundo, Long casoId) {
		return new InicioSinTurno(persona(mundo), mundo.ofertaId(), casoId, MOTIVO);
	}

	private long turno(Mundo mundo) {
		return InsertarConId.insertar(jdbc, """
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), UTC_TIMESTAMP(6),
				        'RESERVADO', ?, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), mundo.consultorioId(), mundo.ofertaId(), persona(mundo),
				mundo.profesional().membershipId(), mundo.profesional().cuentaId());
	}

	private void habilitar(Mundo mundo, long membershipId) {
		jdbc.update("""
				INSERT INTO oferta_profesional_habilitado
				    (organization_id, consultorio_id, oferta_id, membership_id,
				     valid_from, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", mundo.organizationId(), mundo.consultorioId(), mundo.ofertaId(), membershipId);
	}

	private Miembro admin(Mundo mundo) {
		return fixtures.crearMiembro(mundo.organizationId(), mundo.consultorioId(),
				"CONSULTORIO_ADMIN", "ca-" + mundo.sufijo() + "-" + UUID.randomUUID().toString().substring(0, 4));
	}

	private long cerrada(Mundo mundo) {
		long sesionId = fixtures.crearSesion(mundo);
		sesionService.cerrar(mundo.actor(), mundo.consultorioId(), sesionId, presente("Nota original"),
				version(sesionId));
		return sesionId;
	}

	private SesionView enmendar(Mundo mundo, long sesionId) {
		return sesionService.enmendar(mundo.actor(), mundo.consultorioId(), sesionId,
				new ContenidoDeSesion(null, null, null, null, Evolucion.IGUAL, null, null,
						"Nota corregida", null, null, null, null),
				"motivo de la correccion", version(sesionId));
	}

	private static CierreDeSesion presente(String nota) {
		return new CierreDeSesion(Asistencia.PRESENTE, nota, null, null, null, null);
	}

	private long version(long sesionId) {
		return jdbc.queryForObject("SELECT version FROM sesion WHERE id = ?", Long.class, sesionId);
	}

	private Long casoEnBase(long sesionId) {
		return jdbc.queryForObject("SELECT caso_id FROM sesion WHERE id = ?", Long.class, sesionId);
	}

	private long sesionesDe(Mundo mundo) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM sesion WHERE historia_clinica_id = ?",
				Long.class, mundo.historiaClinicaId());
	}

	private long reemplazos(long sesionId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM sesion_reemplazo_profesional WHERE sesion_id = ?",
				Long.class, sesionId);
	}

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
			return fallo() ? "FALLO(" + error + ")" : "OK(" + sesion.id() + ")";
		}
	}
}
