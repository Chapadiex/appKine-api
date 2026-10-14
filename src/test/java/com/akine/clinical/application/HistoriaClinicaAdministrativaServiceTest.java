package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.CasoProfesional;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.RolEnCaso;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoProfesionalRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La lectura limitada de la HC (matriz §32, "Ver Historia Clinica" del ADMINISTRATIVO).
 */
class HistoriaClinicaAdministrativaServiceTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 2L;
	private static final long ACCOUNT_ID = 3L;
	private static final long PERSONA_ID = 12L;
	private static final long HC_ID = 88L;

	private final PermissionGuard permissionGuard = mock(PermissionGuard.class);
	private final PacienteDirectory pacientes = mock(PacienteDirectory.class);
	private final HistoriaClinicaRepositoryPort historias = mock(HistoriaClinicaRepositoryPort.class);
	private final CasoClinicoRepositoryPort casos = mock(CasoClinicoRepositoryPort.class);
	private final CasoProfesionalRepositoryPort equipos = mock(CasoProfesionalRepositoryPort.class);

	private final OperatingActor actor = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	private HistoriaClinicaAdministrativaService servicio;

	@BeforeEach
	void armar() {
		servicio = new HistoriaClinicaAdministrativaService(
				permissionGuard, pacientes, historias, casos, equipos);
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(snapshot(true, true)));
	}

	@Test
	@DisplayName("autoriza con paciente:read, no con hc:read")
	void autoriza_con_el_permiso_del_padron() {
		servicio.datosAdministrativos(actor, PERSONA_ID);

		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo("paciente:read");
	}

	@Test
	@DisplayName("sin contexto es 403 y no lee nada")
	void sin_contexto_no_lee() {
		OperatingActor sinContexto = new OperatingActor(ACCOUNT_ID, false, null, null);

		assertThatThrownBy(() -> servicio.datosAdministrativos(sinContexto, PERSONA_ID))
				.isInstanceOf(AccessDeniedException.class);
		verifyNoInteractions(permissionGuard, historias);
	}

	@Test
	@DisplayName("una persona que no es paciente de la organizacion es 404")
	void persona_ajena_es_404() {
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> servicio.datosAdministrativos(actor, PERSONA_ID))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		verifyNoInteractions(historias);
	}

	@Test
	@DisplayName("una persona del tenant sin perfil de paciente vigente es 404, tenga o no historia")
	void persona_sin_perfil_vigente_es_404() {
		// Lo que el directorio real devuelve: la persona es del tenant y no es paciente.
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(snapshot(true, false)));

		assertThatThrownBy(() -> servicio.datosAdministrativos(actor, PERSONA_ID))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		verifyNoInteractions(historias, casos);
	}

	@Test
	@DisplayName("una persona con la ficha dada de baja es 404")
	void persona_dada_de_baja_es_404() {
		given(pacientes.find(ORG_ID, PERSONA_ID)).willReturn(Optional.of(snapshot(false, true)));

		assertThatThrownBy(() -> servicio.datosAdministrativos(actor, PERSONA_ID))
				.isInstanceOf(HistoriaClinicaNotAccessibleException.class);
		verifyNoInteractions(historias, casos);
	}

	@Test
	@DisplayName("un paciente sin historia es 200 con tieneHistoriaClinica = false")
	void sin_historia_no_es_404() {
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID)).willReturn(Optional.empty());

		DatosAdministrativosDeHistoriaView datos = servicio.datosAdministrativos(actor, PERSONA_ID);

		assertThat(datos.tieneHistoriaClinica()).isFalse();
		assertThat(datos.historiaClinicaId()).isNull();
		assertThat(datos.casos()).isEmpty();
	}

	@Test
	@DisplayName("los casos viajan activos primero, con su equipo vigente y sin contenido clinico")
	void casos_activos_primero_con_equipo() {
		HistoriaClinica historia = new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID)).willReturn(Optional.of(historia));

		CasoClinico cerrado = caso(301L, 2);
		cerrado.cerrar("Alta", Instant.EPOCH.plusSeconds(60), ACCOUNT_ID);
		CasoClinico activo = caso(300L, 1);
		given(casos.buscarDeHistoria(ORG_ID, HC_ID, false)).willReturn(List.of(cerrado, activo));
		CasoProfesional responsable =
				new CasoProfesional(ORG_ID, 300L, 31L, RolEnCaso.RESPONSABLE, Instant.EPOCH);
		ReflectionTestUtils.setField(responsable, "id", 904L);
		given(equipos.buscarDeCaso(ORG_ID, 300L, true)).willReturn(List.of(responsable));
		given(equipos.buscarDeCaso(ORG_ID, 301L, true)).willReturn(List.of());

		DatosAdministrativosDeHistoriaView datos = servicio.datosAdministrativos(actor, PERSONA_ID);

		assertThat(datos.tieneHistoriaClinica()).isTrue();
		assertThat(datos.historiaClinicaId()).isEqualTo(HC_ID);
		assertThat(datos.casos()).extracting(DatosAdministrativosDeHistoriaView.Caso::id)
				.containsExactly(300L, 301L);
		assertThat(datos.casos().getFirst().equipo())
				.extracting(CasoProfesionalView::profesionalMembershipId)
				.containsExactly(31L);
		assertThat(datos.casos().get(1).cerradoEn()).isNotNull();
		assertThat(datos.toString())
				.as("ni el diagnostico ni el motivo de cierre llegan a la vista")
				.doesNotContain("Diagnostico sintetico")
				.doesNotContain("Alta");
		verify(permissionGuard).requirePermission(any());
	}

	private static PacienteSnapshot snapshot(boolean activa, boolean esPacienteVigente) {
		return new PacienteSnapshot(PERSONA_ID, ORG_ID, "Sintetico", "Paciente", "DNI", "30111222",
				null, activa, esPacienteVigente, esPacienteVigente ? 7L : null,
				esPacienteVigente ? Instant.EPOCH : null);
	}

	private static CasoClinico caso(long id, int numero) {
		CasoClinico caso = new CasoClinico(ORG_ID, HC_ID, numero, 14L, SEDE_ID,
				"Diagnostico sintetico", null, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(caso, "id", id);
		return caso;
	}
}
