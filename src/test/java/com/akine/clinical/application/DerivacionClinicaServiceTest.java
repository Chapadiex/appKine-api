package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.DerivacionClinica;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.exception.AutorizacionNoElegibleException;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.CasoNoActivoException;
import com.akine.clinical.domain.exception.PlanTratamientoNotAccessibleException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.domain.port.DerivacionRepositoryPorts.DerivacionClinicaRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import com.akine.clinical.spi.ActorDeDerivacion;
import com.akine.clinical.spi.DerivacionSnapshot;
import com.akine.clinical.spi.EstadoClinicoDeParticipacion;
import com.akine.clinical.spi.OrigenDeParticipacion;
import com.akine.clinical.spi.ParticipacionDerivable;
import com.akine.clinical.spi.RegistroDeDerivacion;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.clinical.spi.ReversionDeDerivacion;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.AutorizacionDirectory;
import com.akine.person.spi.AutorizacionSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.time.LocalDate;
import java.util.List;
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
 * La derivacion de un participante de clase al circuito clinico (M28 → M10).
 *
 * <p>Lo que estos tests fijan es lo que cuesta caro si se rompe: que la historia clinica se abra
 * <b>recien</b> cuando todo lo demas valido —si no, una derivacion fallida deja una HC creada—,
 * que un caso, plan o autorizacion de otro paciente o caso sea 404 y no confirme que existe, que
 * las dos capas de idempotencia devuelvan la fila ganadora sin auditar dos veces, y que revertir
 * dos veces no pise el motivo de la primera.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DerivacionClinicaService")
class DerivacionClinicaServiceTest {

	private static final long ACCOUNT_ID = 40L;
	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;
	private static final long HC_ID = 700L;
	private static final long CASO_ID = 900L;
	private static final long PLAN_ID = 77L;
	private static final long OFERTA_ID = 42L;
	private static final long ASISTENCIA_ID = 3100L;
	private static final long AUTORIZACION_ID = 61L;
	private static final long DERIVACION_ID = 8800L;
	private static final LocalDate FECHA = LocalDate.of(2026, 10, 7);

	@Mock private DerivacionClinicaRepositoryPort derivaciones;
	@Mock private CasoClinicoRepositoryPort casos;
	@Mock private PlanTratamientoRepositoryPort planes;
	@Mock private HistoriaClinicaRepositoryPort historias;
	@Mock private HistoriaClinicaService historiaClinicaService;
	@Mock private AutorizacionDirectory autorizaciones;
	@Mock private PermissionGuard permissionGuard;
	@Mock private RelacionAsistencialProbe relaciones;
	@Mock private AuditTrail auditTrail;
	@Mock private ClinicalSupportAccessAuditor supportAccessAuditor;

	private DerivacionClinicaService service;

	private final ActorDeDerivacion profesional =
			new ActorDeDerivacion(ACCOUNT_ID, false, ORG_ID, SEDE_ID, null);

	@BeforeEach
	void setUp() {
		service = new DerivacionClinicaService(derivaciones, casos, planes, historias,
				historiaClinicaService, autorizaciones, permissionGuard, relaciones, auditTrail,
				supportAccessAuditor);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("CONSULTORIO", false));
		given(relaciones.tieneRelacionAsistencial(anyLong(), anyLong(), anyLong(), anyLong()))
				.willReturn(true);
		given(derivaciones.findVigenteAlCaso(ORG_ID, OrigenDeParticipacion.CLASE_PROGRAMADA,
				ASISTENCIA_ID, CASO_ID)).willReturn(Optional.empty());
		given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID))
				.willReturn(Optional.of(caso(HC_ID, true)));
		given(historiaClinicaService.asegurar(ORG_ID, PERSONA_ID, ACCOUNT_ID)).willReturn(historia());
		given(derivaciones.saveAndFlush(any())).willAnswer(invocacion -> {
			DerivacionClinica fila = invocacion.getArgument(0);
			ReflectionTestUtils.setField(fila, "id", DERIVACION_ID);
			return fila;
		});
	}

	@Nested
	@DisplayName("registrar")
	class Registrar {

		@Test
		@DisplayName("crea la derivacion vigente, la audita y recorta el motivo")
		void crea_y_audita() {
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID))
					.willReturn(Optional.of(plan(CASO_ID)));
			given(autorizaciones.find(ORG_ID, PERSONA_ID, AUTORIZACION_ID, FECHA))
					.willReturn(Optional.of(autorizacion(true, null)));

			DerivacionSnapshot creada = service.registrar(registro(
					PLAN_ID, AUTORIZACION_ID, "  Dolor lumbar al flexionar  "));

			assertThat(creada.yaExistia()).isFalse();
			assertThat(creada.estaVigente()).isTrue();
			assertThat(creada.historiaClinicaId()).isEqualTo(HC_ID);
			assertThat(creada.planTratamientoId()).isEqualTo(PLAN_ID);
			assertThat(creada.autorizacionId()).isEqualTo(AUTORIZACION_ID);
			assertThat(creada.motivo()).isEqualTo("Dolor lumbar al flexionar");

			AuditEntry auditada = auditada();
			assertThat(auditada.eventType()).isEqualTo(AuditEvents.DERIVACION_CLINICA_CREATED);
			assertThat(auditada.details())
					.containsEntry("casoClinicoId", String.valueOf(CASO_ID))
					.containsEntry("viaDeAcceso", "RELACION_ASISTENCIAL");
			verifyNoInteractions(supportAccessAuditor);
		}

		@Test
		@DisplayName("el doble submit devuelve la vigente sin abrir historia, guardar ni auditar")
		void doble_submit() {
			DerivacionClinica vigente = derivacion();
			given(derivaciones.findVigenteAlCaso(ORG_ID, OrigenDeParticipacion.CLASE_PROGRAMADA,
					ASISTENCIA_ID, CASO_ID)).willReturn(Optional.of(vigente));

			DerivacionSnapshot respuesta = service.registrar(registro(null, null, null));

			assertThat(respuesta.yaExistia()).isTrue();
			assertThat(respuesta.id()).isEqualTo(DERIVACION_ID);
			verify(historiaClinicaService, never()).asegurar(anyLong(), anyLong(), anyLong());
			verify(derivaciones, never()).saveAndFlush(any());
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("un caso cerrado es 409 y no deja historia clinica abierta")
		void caso_cerrado() {
			given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID))
					.willReturn(Optional.of(caso(HC_ID, false)));

			assertThatThrownBy(() -> service.registrar(registro(null, null, null)))
					.isInstanceOf(CasoNoActivoException.class);
			verify(historiaClinicaService, never()).asegurar(anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("un caso de otro paciente es 404, no 'no es de este paciente'")
		void caso_de_otra_persona() {
			given(casos.findByIdAndOrganizationId(CASO_ID, ORG_ID))
					.willReturn(Optional.of(caso(HC_ID + 1, true)));

			assertThatThrownBy(() -> service.registrar(registro(null, null, null)))
					.isInstanceOf(CasoClinicoNotAccessibleException.class);
			verify(derivaciones, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("un plan de otro caso es 404, y se rechaza antes de abrir la historia")
		void plan_de_otro_caso() {
			given(planes.findByIdAndOrganizationId(PLAN_ID, ORG_ID))
					.willReturn(Optional.of(plan(CASO_ID + 1)));

			assertThatThrownBy(() -> service.registrar(registro(PLAN_ID, null, null)))
					.isInstanceOf(PlanTratamientoNotAccessibleException.class);
			verify(historiaClinicaService, never()).asegurar(anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("una autorizacion que no habilita viaja con su motivo; una ajena, NO_ACCESIBLE")
		void autorizacion_no_elegible() {
			given(autorizaciones.find(ORG_ID, PERSONA_ID, AUTORIZACION_ID, FECHA))
					.willReturn(Optional.of(autorizacion(false, "VENCIDA")));

			assertThatThrownBy(() -> service.registrar(registro(null, AUTORIZACION_ID, null)))
					.isInstanceOfSatisfying(AutorizacionNoElegibleException.class,
							e -> assertThat(e.getMotivo()).isEqualTo("VENCIDA"));

			given(autorizaciones.find(ORG_ID, PERSONA_ID, AUTORIZACION_ID, FECHA))
					.willReturn(Optional.empty());

			assertThatThrownBy(() -> service.registrar(registro(null, AUTORIZACION_ID, null)))
					.isInstanceOfSatisfying(AutorizacionNoElegibleException.class,
							e -> assertThat(e.getMotivo()).isEqualTo("NO_ACCESIBLE"));
			verify(derivaciones, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("la carrera perdida contra el unique devuelve la fila ganadora sin auditar")
		void carrera_perdida() {
			DataIntegrityViolationException choque = new DataIntegrityViolationException("uk");
			willThrow(choque).given(derivaciones).saveAndFlush(any());
			given(derivaciones.findVigenteAlCaso(ORG_ID, OrigenDeParticipacion.CLASE_PROGRAMADA,
					ASISTENCIA_ID, CASO_ID))
					.willReturn(Optional.empty(), Optional.of(derivacion()));

			DerivacionSnapshot respuesta = service.registrar(registro(null, null, null));

			assertThat(respuesta.yaExistia()).isTrue();
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("si el unique salto y no hay ganadora, el choque se propaga: no se inventa nada")
		void choque_sin_ganadora() {
			DataIntegrityViolationException choque = new DataIntegrityViolationException("fk");
			willThrow(choque).given(derivaciones).saveAndFlush(any());

			assertThatThrownBy(() -> service.registrar(registro(null, null, null)))
					.isSameAs(choque);
		}
	}

	@Nested
	@DisplayName("revertir")
	class Revertir {

		@Test
		@DisplayName("revierte, audita y deja SUPPORT_ACCESS_USED cuando entro por soporte")
		void revierte_por_soporte() {
			given(derivaciones.findByIdInScope(ORG_ID, DERIVACION_ID))
					.willReturn(Optional.of(derivacion()));
			given(permissionGuard.requirePermission(any()))
					.willReturn(PermissionDecision.concedida("CONSULTORIO", true));

			DerivacionSnapshot revertida = service.revertir(
					new ReversionDeDerivacion(profesional, DERIVACION_ID, "Derivada por error"));

			assertThat(revertida.estado()).isEqualTo("REVERTIDA");
			assertThat(revertida.motivoReversion()).isEqualTo("Derivada por error");
			assertThat(auditada().eventType()).isEqualTo(AuditEvents.DERIVACION_CLINICA_REVERTED);
			ArgumentCaptor<AuditEntry> soporte = ArgumentCaptor.forClass(AuditEntry.class);
			verify(supportAccessAuditor).record(soporte.capture());
			assertThat(soporte.getValue().details()).containsEntry("operacion", "revertir");
		}

		@Test
		@DisplayName("revertir dos veces no pisa el primer motivo ni audita de nuevo")
		void segunda_reversion_idempotente() {
			DerivacionClinica fila = derivacion();
			fila.revertir("Primer motivo", Instant.EPOCH, ACCOUNT_ID);
			given(derivaciones.findByIdInScope(ORG_ID, DERIVACION_ID)).willReturn(Optional.of(fila));

			DerivacionSnapshot respuesta = service.revertir(
					new ReversionDeDerivacion(profesional, DERIVACION_ID, "Segundo motivo"));

			assertThat(respuesta.yaExistia()).isTrue();
			assertThat(respuesta.motivoReversion()).isEqualTo("Primer motivo");
			verify(derivaciones, never()).saveAndFlush(any());
			verifyNoInteractions(auditTrail);
		}

		@Test
		@DisplayName("sin motivo no se revierte")
		void sin_motivo() {
			given(derivaciones.findByIdInScope(ORG_ID, DERIVACION_ID))
					.willReturn(Optional.of(derivacion()));

			assertThatThrownBy(() -> service.revertir(
					new ReversionDeDerivacion(profesional, DERIVACION_ID, "   ")))
					.isInstanceOf(IllegalArgumentException.class);
			verify(derivaciones, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("sin contexto de trabajo es 403 antes de buscar la fila")
		void sin_contexto() {
			ActorDeDerivacion sinContexto = new ActorDeDerivacion(ACCOUNT_ID, false, ORG_ID, null, null);

			assertThatThrownBy(() -> service.revertir(
					new ReversionDeDerivacion(sinContexto, DERIVACION_ID, "x")))
					.isInstanceOf(AccessDeniedException.class);
			verify(derivaciones, never()).findByIdInScope(anyLong(), anyLong());
		}
	}

	@Test
	@DisplayName("consultar devuelve la historia vigente y las derivaciones, y la lectura se audita")
	void consultar() {
		given(derivaciones.findDeLaParticipacion(ORG_ID, OrigenDeParticipacion.CLASE_PROGRAMADA,
				ASISTENCIA_ID)).willReturn(List.of(derivacion()));
		given(historias.buscarVigentePorPersona(ORG_ID, PERSONA_ID))
				.willReturn(Optional.of(historia()));

		EstadoClinicoDeParticipacion estado = service.consultar(
				profesional, OrigenDeParticipacion.CLASE_PROGRAMADA, ASISTENCIA_ID, PERSONA_ID);

		assertThat(estado.historiaClinicaId()).isEqualTo(HC_ID);
		assertThat(estado.tieneDerivacionVigente()).isTrue();
		AuditEntry auditada = auditada();
		assertThat(auditada.eventType()).isEqualTo(AuditEvents.DERIVACION_CLINICA_ACCESSED);
		assertThat(auditada.details()).containsEntry("derivaciones", "1");
	}

	// =================================================================================

	private AuditEntry auditada() {
		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(entrada.capture());
		return entrada.getValue();
	}

	private RegistroDeDerivacion registro(Long planId, Long autorizacionId, String motivo) {
		return new RegistroDeDerivacion(
				profesional,
				new ParticipacionDerivable(OrigenDeParticipacion.CLASE_PROGRAMADA, ASISTENCIA_ID,
						PERSONA_ID, SEDE_ID, OFERTA_ID, true, FECHA),
				CASO_ID, planId, autorizacionId, motivo);
	}

	private static DerivacionClinica derivacion() {
		DerivacionClinica fila = DerivacionClinica.abrir(ORG_ID, SEDE_ID,
				OrigenDeParticipacion.CLASE_PROGRAMADA, ASISTENCIA_ID, PERSONA_ID, HC_ID, CASO_ID,
				null, OFERTA_ID, true, null, "Motivo", Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(fila, "id", DERIVACION_ID);
		return fila;
	}

	private static HistoriaClinica historia() {
		HistoriaClinica historia = new HistoriaClinica(ORG_ID, PERSONA_ID, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(historia, "id", HC_ID);
		return historia;
	}

	private static CasoClinico caso(long historiaId, boolean activo) {
		CasoClinico caso = new CasoClinico(ORG_ID, historiaId, 3, OFERTA_ID, SEDE_ID,
				"Lumbalgia", null, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(caso, "id", CASO_ID);
		if (!activo) {
			caso.cerrar("Alta", Instant.EPOCH, ACCOUNT_ID);
		}
		return caso;
	}

	private static PlanTratamiento plan(long casoId) {
		PlanTratamiento plan = new PlanTratamiento(ORG_ID, casoId, 1, Instant.EPOCH, ACCOUNT_ID);
		ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		return plan;
	}

	private static AutorizacionSnapshot autorizacion(boolean habilita, String motivo) {
		return new AutorizacionSnapshot(AUTORIZACION_ID, ORG_ID, PERSONA_ID, 1L, 2L, "A-1", 10, 3,
				7, FECHA.minusDays(30), FECHA.plusDays(30), FECHA, habilita, motivo);
	}
}
