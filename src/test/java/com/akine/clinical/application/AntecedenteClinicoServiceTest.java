package com.akine.clinical.application;

import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.TipoAntecedente;
import com.akine.clinical.domain.exception.AntecedenteNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Antecedentes clinicos: registrar y dar de baja, nunca editar ni borrar.
 *
 * <p>Lo que este test fija es RN-M09-004 aplicada al dato clinico mas propenso a "corregirse" a
 * mano: un antecedente que deja de aplicar se da de baja con motivo y sigue siendo consultable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AntecedenteClinicoService")
class AntecedenteClinicoServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;
	private static final long ANTECEDENTE_ID = 800L;

	@Mock
	private HistoriaClinicaRepositoryPort historias;

	@Mock
	private AntecedenteClinicoRepositoryPort antecedentes;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private RelacionAsistencialProbe relaciones;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private ClinicalSupportAccessAuditor supportAccessAuditor;

	private AntecedenteClinicoService service;

	private final OperatingActor profesional = new OperatingActor(ACCOUNT_ID, false, ORG_ID, SEDE_ID);

	@BeforeEach
	void setUp() {
		service = new AntecedenteClinicoService(historias, antecedentes, permissionGuard, relaciones,
				auditTrail, supportAccessAuditor);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(historia()));
		given(antecedentes.save(any())).willAnswer(i -> conId(i.getArgument(0)));
	}

	@Test
	@DisplayName("Registrar deja el antecedente vigente y audita sin copiar su descripcion")
	void el_registro_no_filtra_contenido_clinico() {
		AntecedenteView vista = service.registrar(
				profesional, PERSONA_ID, TipoAntecedente.ALERGIA, "penicilina", null);

		assertThat(vista.vigente()).isTrue();
		assertThat(vista.tipo()).isEqualTo("ALERGIA");

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("ANTECEDENTE_CLINICO_REGISTERED");
		assertThat(fila.getValue().details())
				.containsEntry("tipo", "ALERGIA")
				.doesNotContainValue("penicilina");
	}

	@Test
	@DisplayName("Dar de baja sin motivo no se puede: la auditoria no puede quedar sin explicarse")
	void la_baja_exige_motivo() {
		given(antecedentes.findByIdAndOrganizationId(ANTECEDENTE_ID, ORG_ID))
				.willReturn(Optional.of(antecedenteVigente()));

		assertThatThrownBy(() ->
				service.darDeBaja(profesional, PERSONA_ID, ANTECEDENTE_ID, "  ", null))
				.isInstanceOf(IllegalArgumentException.class);

		verify(antecedentes, never()).save(any());
	}

	@Test
	@DisplayName("Dar de baja lo conserva consultable, con su motivo")
	void la_baja_es_logica() {
		// Regla maestra 10: un antecedente clinico no desaparece. Deja de aplicar.
		given(antecedentes.findByIdAndOrganizationId(ANTECEDENTE_ID, ORG_ID))
				.willReturn(Optional.of(antecedenteVigente()));

		AntecedenteView vista = service.darDeBaja(
				profesional, PERSONA_ID, ANTECEDENTE_ID, "el paciente ya no la toma", null);

		assertThat(vista.vigente()).isFalse();
		assertThat(vista.deactivationReason()).isEqualTo("el paciente ya no la toma");
		assertThat(vista.descripcion())
				.as("el contenido sigue estando: la baja no lo borra")
				.isEqualTo("ibuprofeno 600");
	}

	@Test
	@DisplayName("Dar de baja dos veces conserva el motivo original")
	void la_baja_repetida_no_pisa_el_motivo() {
		// El segundo pedido es el mismo pedido. Pisar el motivo original con el nuevo perderia la
		// unica explicacion que habia de por que dejo de aplicar.
		AntecedenteClinico yaDeBaja = antecedenteVigente();
		yaDeBaja.deactivate(Instant.now(), "cargado por error");
		given(antecedentes.findByIdAndOrganizationId(ANTECEDENTE_ID, ORG_ID))
				.willReturn(Optional.of(yaDeBaja));

		AntecedenteView vista = service.darDeBaja(
				profesional, PERSONA_ID, ANTECEDENTE_ID, "otro motivo distinto", null);

		assertThat(vista.deactivationReason()).isEqualTo("cargado por error");
		verify(antecedentes, never()).save(any());
		verify(auditTrail, never()).record(any());
	}

	@Test
	@DisplayName("Un antecedente de otra historia no es accesible aunque sea del mismo tenant")
	void el_antecedente_tiene_que_ser_de_esta_historia() {
		// El filtro por organizacion no alcanza: dentro de un mismo centro, los antecedentes de un
		// paciente no pueden alcanzarse desde la historia de otro.
		AntecedenteClinico deOtraHistoria = conId(new AntecedenteClinico(
				ORG_ID, 999L, TipoAntecedente.MEDICO, "otra cosa", Instant.now(), ACCOUNT_ID));
		given(antecedentes.findByIdAndOrganizationId(ANTECEDENTE_ID, ORG_ID))
				.willReturn(Optional.of(deOtraHistoria));

		assertThatThrownBy(() ->
				service.darDeBaja(profesional, PERSONA_ID, ANTECEDENTE_ID, "motivo", null))
				.isInstanceOf(AntecedenteNotAccessibleException.class);
	}

	private static HistoriaClinica historia() {
		HistoriaClinica historia = new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.now(), ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}

	private static AntecedenteClinico antecedenteVigente() {
		return conId(new AntecedenteClinico(ORG_ID, HC_ID, TipoAntecedente.MEDICACION,
				"ibuprofeno 600", Instant.now(), ACCOUNT_ID));
	}

	private static AntecedenteClinico conId(AntecedenteClinico antecedente) {
		ReflectionTestUtils.setField(antecedente, "id", ANTECEDENTE_ID);
		return antecedente;
	}
}
