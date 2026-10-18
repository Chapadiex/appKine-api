package com.akine.clinical.application;

import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.exception.AccesoClinicoNoJustificadoException;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La politica de acceso clinico de DP-03.
 *
 * <p>Lo que este test fija es la regla que distingue a un modulo clinico de todos los demas:
 * <b>tener el permiso no alcanza</b>. Hace falta ademas relacion asistencial o justificacion
 * declarada, y cual de las dos fue tiene que quedar dicho para que la auditoria sirva.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AutorizacionClinica")
class AutorizacionClinicaTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private ClinicalSupportAccessAuditor rechazos;

	private final OperatingActor profesional = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(false);
	}

	@Test
	@DisplayName("Con relacion asistencial no hace falta justificar, y asi queda dicho")
	void la_relacion_asistencial_alcanza_sola() {
		given(relaciones.tieneRelacionAsistencial(ORG_ID, SEDE_ID, ACCOUNT_ID, PERSONA_ID))
				.willReturn(true);

		AccesoClinico acceso = exigirLectura(profesional, null);

		assertThat(acceso.conRelacionAsistencial()).isTrue();
		assertThat(acceso.via()).isEqualTo("RELACION_ASISTENCIAL");
		assertThat(acceso.justificacion())
				.as("el motivo se descarta cuando no hizo falta: guardarlo diria que se entro por "
						+ "excepcion cuando se entro por atencion")
				.isNull();
	}

	@Test
	@DisplayName("Sin relacion asistencial y sin justificacion, el permiso NO alcanza")
	void el_permiso_solo_no_alcanza() {
		// Es la mitad de DP-03 que se pierde si uno lee la matriz de permisos sin leer la decision:
		// con `hc:read` y nada mas, cualquier profesional del centro leeria la historia de
		// cualquier paciente sin dejar mas rastro que un evento de lectura mas.
		assertThatThrownBy(() -> exigirLectura(profesional, null))
				.isInstanceOf(AccesoClinicoNoJustificadoException.class);

		// Y el rechazo queda auditado aparte (appKine-web #83): sobre la persona, con la operacion
		// y el motivo del rechazo, sin justificacion porque es justamente lo que falto.
		ArgumentCaptor<AuditEntry> rechazo = ArgumentCaptor.forClass(AuditEntry.class);
		verify(rechazos).registrarRechazo(rechazo.capture());
		assertThat(rechazo.getValue().eventType()).isEqualTo("HISTORIA_CLINICA_ACCESS_DENIED");
		assertThat(rechazo.getValue().entityType()).isEqualTo("Persona");
		assertThat(rechazo.getValue().entityId()).isEqualTo(PERSONA_ID);
		assertThat(rechazo.getValue().actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(rechazo.getValue().details())
				.containsEntry("motivoDelRechazo", "SIN_JUSTIFICACION")
				.containsEntry("permissionCode", PermissionCodes.HC_READ)
				.containsEntry("operacion", "Ver historia clinica");
		assertThat(rechazo.getValue().reason()).isNull();
	}

	@Test
	@DisplayName("Una justificacion en blanco no es una justificacion")
	void el_motivo_vacio_no_cuenta() {
		assertThatThrownBy(() -> exigirLectura(profesional, "   "))
				.isInstanceOf(AccesoClinicoNoJustificadoException.class);
	}

	@Test
	@DisplayName("Sin relacion asistencial, la justificacion habilita el acceso y queda registrada")
	void la_justificacion_habilita_y_se_guarda() {
		// Es el caso borde "acceso de emergencia autorizado" de la etapa. No hay catalogo de
		// motivos que validar: lo que hace revisable el acceso es que el texto quede.
		AccesoClinico acceso = exigirLectura(profesional, "  urgencia en guardia  ");

		assertThat(acceso.conRelacionAsistencial()).isFalse();
		assertThat(acceso.via()).isEqualTo("JUSTIFICACION");
		assertThat(acceso.justificacion()).isEqualTo("urgencia en guardia");
	}

	@Test
	@DisplayName("Sin sede en el contexto no se evalua nada: ni siquiera se consulta el permiso")
	void sin_contexto_no_se_llega_a_evaluar_permiso() {
		// La sede es obligatoria aunque la historia sea de la organizacion: sin ella no se puede
		// preguntar por relacion asistencial ni registrar desde donde se accedio.
		OperatingActor sinSede = new OperatingActor(ACCOUNT_ID, false, ORG_ID, null);

		assertThatThrownBy(() -> exigirLectura(sinSede, "urgencia"))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(permissionGuard, relaciones);
	}

	@Test
	@DisplayName("La consulta de permiso lleva el codigo, el tenant y la sede del actor")
	void la_consulta_de_permiso_va_acotada_al_contexto() {
		// Sin consultorioId en la query, el evaluador decidiria sobre la organizacion ENTERA y un
		// alcance de sede no la cubriria: el profesional quedaria afuera de su propia historia.
		exigirLectura(profesional, "urgencia");

		ArgumentCaptor<PermissionQuery> query = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(query.capture());
		assertThat(query.getValue().permissionCode()).isEqualTo(PermissionCodes.HC_READ);
		assertThat(query.getValue().organizationId()).isEqualTo(ORG_ID);
		assertThat(query.getValue().consultorioId()).isEqualTo(SEDE_ID);
	}

	@Test
	@DisplayName("Una persona de otra organizacion es 404 y NO deja auditoria de rechazo con su id")
	void persona_ajena_no_se_audita() {
		// Una fila HISTORIA_CLINICA_ACCESS_DENIED con ese personaId confirmaria que existe en
		// algun lado. La pertenencia va antes que el motivo y que el permiso (merge con D7).
		com.akine.person.spi.PacienteDirectory pacientes =
				org.mockito.Mockito.mock(com.akine.person.spi.PacienteDirectory.class);
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> AutorizacionClinica.exigirSobrePersona(permissionGuard,
				relaciones, rechazos, pacientes, profesional, PermissionCodes.HC_READ, PERSONA_ID,
				null, "Ver historia clinica"))
				.isInstanceOf(com.akine.clinical.domain.exception
						.HistoriaClinicaNotAccessibleException.class);

		verifyNoInteractions(permissionGuard, relaciones, rechazos);
	}

	private AccesoClinico exigirLectura(OperatingActor actor, String justificacion) {
		return AutorizacionClinica.exigir(permissionGuard, relaciones, rechazos, actor,
				PermissionCodes.HC_READ, PERSONA_ID, justificacion, "Ver historia clinica");
	}
}
