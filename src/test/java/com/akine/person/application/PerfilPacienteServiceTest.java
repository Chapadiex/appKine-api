package com.akine.person.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.domain.PerfilPaciente;
import com.akine.person.domain.Persona;
import com.akine.person.domain.TipoDocumento;
import com.akine.person.domain.exception.PersonaInactivaException;
import com.akine.person.domain.exception.PersonaNotAccessibleException;
import com.akine.person.domain.port.PersonRepositoryPorts.PerfilPacienteRepositoryPort;
import com.akine.person.domain.port.PersonRepositoryPorts.PersonaRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La activacion del perfil clinico: el unico camino del sistema que crea un paciente.
 *
 * <p>Lo que este test fija es que ese camino sea <b>idempotente por dos capas independientes</b>
 * —el pre-chequeo y el unique— y que no cree ningun artefacto clinico mas alla del perfil.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PerfilPacienteService")
class PerfilPacienteServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long PERFIL_ID = 900L;

	@Mock
	private PersonaRepositoryPort personas;

	@Mock
	private PerfilPacienteRepositoryPort perfiles;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private PersonSupportAccessAuditor supportAccessAuditor;

	private PerfilPacienteService service;

	private final OperatingActor delMostrador =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	private final OperatingActor sinSede = new OperatingActor(ACCOUNT_ID, false, ORG_ID, null);

	@BeforeEach
	void setUp() {
		service = new PerfilPacienteService(
				personas, perfiles, permissionGuard, auditTrail, supportAccessAuditor);
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID))
				.willReturn(Optional.of(personaVigente()));
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());
		given(perfiles.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
	}

	@Test
	@DisplayName("Activar sobre una persona vigente la convierte en paciente y lo audita")
	void la_activacion_deja_su_rastro() {
		PersonaView vista = service.activar(delMostrador, PERSONA_ID, "inicia rehabilitacion");

		assertThat(vista.esPaciente()).isTrue();
		assertThat(vista.perfilPacienteId()).isEqualTo(PERFIL_ID);

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("PERFIL_PACIENTE_ACTIVATED");
		assertThat(fila.getValue().previousState()).isEqualTo("SIN_PERFIL");
		assertThat(fila.getValue().newState()).isEqualTo("PACIENTE");
		assertThat(fila.getValue().reason()).isEqualTo("inicia rehabilitacion");
	}

	@Test
	@DisplayName("Activar dos veces devuelve el mismo perfil y NO audita de nuevo")
	void la_activacion_es_idempotente() {
		// Primera capa: el pre-chequeo. No ocurrio ningun hecho nuevo, asi que registrarlo
		// llenaria la auditoria de activaciones que nunca pasaron.
		PerfilPaciente yaExistente = conId(
				new PerfilPaciente(ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID, null));
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID)).willReturn(Optional.of(yaExistente));

		PersonaView vista = service.activar(delMostrador, PERSONA_ID, null);

		assertThat(vista.esPaciente()).isTrue();
		assertThat(vista.perfilPacienteId()).isEqualTo(PERFIL_ID);
		verify(perfiles, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Dos activaciones simultaneas no producen dos perfiles ni un 500")
	void la_carrera_se_resuelve_como_idempotente() {
		// Segunda capa: el unique. Los dos requests pasan el pre-chequeo —esa ventana un SELECT
		// previo no la cierra nunca— y el segundo choca. Sin esta rama, dos clicks rapidos serian
		// un 500.
		willThrow(new DataIntegrityViolationException("Duplicate entry 'uk_perfil_paciente_persona'"))
				.given(perfiles).saveAndFlush(any());

		PersonaView vista = service.activar(delMostrador, PERSONA_ID, null);

		// Se responde con la persona y sin el perfil: informacion incompleta pero cierta. Volver a
		// consultar la sesion JPA despues del flush fallido produciria el 500 que se esta evitando.
		assertThat(vista.id()).isEqualTo(PERSONA_ID);
		verify(perfiles, never()).buscarVigentesDePersonas(anyLong(), any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Una persona dada de baja no se convierte en paciente: 409")
	void una_persona_inactiva_no_se_activa() {
		Persona baja = personaVigente();
		baja.deactivate(Instant.now(), "duplicada");
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID)).willReturn(Optional.of(baja));

		assertThatThrownBy(() -> service.activar(delMostrador, PERSONA_ID, null))
				.isInstanceOf(PersonaInactivaException.class);

		verify(perfiles, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Una persona de otra organizacion no resuelve: 404, nunca 403")
	void una_persona_ajena_da_404() {
		given(personas.findByIdAndOrganizationId(PERSONA_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.activar(delMostrador, PERSONA_ID, null))
				.isInstanceOf(PersonaNotAccessibleException.class);
	}

	@Test
	@DisplayName("Sin sede en el contexto no se activa nada: 403 antes de leer")
	void sin_sede_da_403() {
		assertThatThrownBy(() -> service.activar(sinSede, PERSONA_ID, null))
				.isInstanceOf(AccessDeniedException.class);

		// El chequeo va ANTES de leer: sin esto, un 404 delataria que ese id existe a alguien que
		// ni siquiera tiene derecho a preguntar.
		verifyNoInteractions(personas, perfiles, auditTrail);
	}

	// =================================================================================
	// Baja del perfil (RF-M07-005, AKINE-03.02)
	// =================================================================================

	@Test
	@DisplayName("la baja del perfil deja a la persona VIGENTE en el padron")
	void la_baja_del_perfil_no_da_de_baja_a_la_persona() {
		PerfilPaciente perfil = conId(new PerfilPaciente(
				ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID, null));
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID)).willReturn(Optional.of(perfil));
		given(perfiles.save(any())).willAnswer(i -> i.getArgument(0));

		PersonaView vista = service.desactivar(
				delMostrador, PERSONA_ID, "Viene solo a clases grupales");

		assertThat(vista.estado()).isEqualTo("ACTIVO");
		assertThat(vista.esPaciente()).isFalse();
		assertThat(perfil.isVigente()).isFalse();

		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		assertThat(entrada.getValue().eventType()).isEqualTo("PERFIL_PACIENTE_DEACTIVATED");
		assertThat(entrada.getValue().previousState()).isEqualTo("PACIENTE");
		assertThat(entrada.getValue().newState()).isEqualTo("SIN_PERFIL");
	}

	@Test
	@DisplayName("dar de baja un perfil que ya no esta vigente es idempotente y no audita")
	void la_baja_del_perfil_es_idempotente() {
		given(perfiles.buscarVigente(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());

		PersonaView vista = service.desactivar(delMostrador, PERSONA_ID, "otra vez");

		assertThat(vista.esPaciente()).isFalse();
		verifyNoInteractions(auditTrail);
		verify(perfiles, never()).save(any());
	}

	@Test
	@DisplayName("sin contexto de sede la baja del perfil es 403 y no lee nada")
	void la_baja_del_perfil_exige_permiso() {
		assertThatThrownBy(() -> service.desactivar(sinSede, PERSONA_ID, "motivo"))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(personas, perfiles, auditTrail);
	}

	private static Persona personaVigente() {
		Persona persona = new Persona(
				ORG_ID, TipoDocumento.DNI, "12345678", "Perez", "Ana", null, null, null, null);
		ReflectionTestUtils.setField(persona, "id", PERSONA_ID);
		return persona;
	}

	private static PerfilPaciente conId(PerfilPaciente perfil) {
		if (perfil.getId() == null) {
			ReflectionTestUtils.setField(perfil, "id", PERFIL_ID);
		}
		return perfil;
	}
}
