package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.DisponibilidadExcepcion;
import com.akine.resource.domain.MotivoExcepcion;
import com.akine.resource.domain.TipoExcepcion;
import com.akine.resource.domain.exception.ExcepcionInactivaException;
import com.akine.resource.domain.exception.ExcepcionNotAccessibleException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.DisponibilidadExcepcionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los caminos de ESCRITURA de las excepciones de disponibilidad, sin base de datos.
 *
 * <p>La tarea 8 dejo este servicio sin tests porque su brief solo nombraba los diez de la
 * disponibilidad efectiva. Dos caminos que mutan datos sin ninguna prueba no son "menos tests",
 * son caminos de escritura sin verificar; esto cubre lo que muta y no lo que se lee.
 *
 * <p>Lo que se prueba es lo que este servicio decide: la idempotencia del alta, que la baja sea
 * logica y que el alcance de sede no exija ninguna membership. Que el alcance de sede se APLIQUE
 * a un profesional sin excepciones propias es una pregunta del calculo, y vive en
 * {@code DisponibilidadEfectivaServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExcepcionServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long MEMBERSHIP_ID = 30L;
	private static final long ACCOUNT_ID = 40L;
	private static final long EXCEPCION_ID = 701L;

	private static final LocalDate DESDE = LocalDate.of(2026, 3, 10);
	private static final LocalDate HASTA = LocalDate.of(2026, 3, 12);

	@Mock
	private DisponibilidadExcepcionRepositoryPort excepciones;

	@Mock
	private CalendarioSedeRepositoryPort calendarios;

	@Mock
	private ConsultorioDirectory consultorioDirectory;

	@Mock
	private ConsultorioMembershipDirectory membershipDirectory;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	private ExcepcionService service;

	private final OperatingActor actor =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new ExcepcionService(
				excepciones, calendarios, new CalendarioSedeIniciador(calendarios),
				consultorioDirectory, membershipDirectory,
				permissionGuard, auditTrail, org.mockito.Mockito.mock(SimuladorDeImpacto.class));

		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.of(
				new ConsultorioSnapshot(CONSULTORIO_ID, ORG_ID, "Sede Sintetica",
						"America/Argentina/Cordoba", true)));
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID)).willReturn(Optional.of(
				new ConsultorioMembershipSnapshot(
						MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, "PROFESIONAL", "ACTIVA",
						Instant.parse("2020-01-01T00:00:00Z"), null, true, true)));
		given(calendarios.lockByScope(ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(new CalendarioSede(ORG_ID, CONSULTORIO_ID)));
		given(excepciones.findQueCubren(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(excepciones.findDeSedeQueCubren(anyLong(), anyLong(), any(), any()))
				.willReturn(List.of());
		given(excepciones.save(any())).willAnswer(invocacion -> {
			DisponibilidadExcepcion guardada = invocacion.getArgument(0);
			if (guardada.getId() == null) {
				ReflectionTestUtils.setField(guardada, "id", EXCEPCION_ID);
			}
			return guardada;
		});
	}

	// =================================================================================
	// Idempotencia del alta (CA-M05-004-05)
	// =================================================================================

	@Test
	@DisplayName("Un alta identica repetida devuelve la excepcion que ya existe, no crea otra y no vuelve a auditar")
	void un_alta_identica_repetida_es_idempotente() {
		DisponibilidadExcepcion yaCargada = cierreDelProfesional();
		given(excepciones.findQueCubren(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, DESDE, HASTA))
				.willReturn(List.of(yaCargada));

		ExcepcionView vista = service.crear(actor, CONSULTORIO_ID, altaDeCierre(MEMBERSHIP_ID));

		assertThat(vista.id()).isEqualTo(EXCEPCION_ID);
		// El reintento de red tiene que dejar el estado y el historial como los dejo el primero.
		verify(excepciones, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Un alta que solo difiere en las notas NO es la misma: se crea igual")
	void un_alta_que_difiere_en_algo_sustantivo_crea_una_fila_nueva() {
		DisponibilidadExcepcion otroHorario = new DisponibilidadExcepcion(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID,
				TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA,
				DESDE, HASTA, LocalTime.of(15, 0), LocalTime.of(18, 0), null, null);
		ReflectionTestUtils.setField(otroHorario, "id", 999L);
		given(excepciones.findQueCubren(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, DESDE, HASTA))
				.willReturn(List.of(otroHorario));

		ExcepcionView vista = service.crear(actor, CONSULTORIO_ID, altaDeCierre(MEMBERSHIP_ID));

		// Solapa, pero no coincide: dos ausencias distintas del mismo dia son dos hechos.
		verify(excepciones).save(any());
		assertThat(vista.id()).isEqualTo(EXCEPCION_ID);
	}

	// =================================================================================
	// Alcance de sede
	// =================================================================================

	@Test
	@DisplayName("Una excepcion de sede no exige ninguna membership y se audita con su alcance")
	void una_excepcion_de_sede_no_exige_membership() {
		ExcepcionView vista = service.crear(actor, CONSULTORIO_ID, altaDeCierre(null));

		assertThat(vista.membershipId())
				.as("membershipId nulo es ALCANCE, no un dato faltante")
				.isNull();
		// El cierre de un feriado local no depende de que haya alguien vinculado hoy.
		verifyNoInteractions(membershipDirectory);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().details()).containsEntry("alcance", "SEDE");
	}

	@Test
	@DisplayName("El listado sin membershipId trae SOLO las de sede, por una consulta distinta")
	void el_listado_sin_membership_trae_solo_las_de_sede() {
		service.listar(actor, CONSULTORIO_ID, DESDE, HASTA, null);

		verify(excepciones).findDeSedeQueCubren(ORG_ID, CONSULTORIO_ID, DESDE, HASTA);
		// Nunca con un id centinela inventado: asi se cuelan los bugs de alcance.
		verify(excepciones, never()).findQueCubren(anyLong(), anyLong(), anyLong(), any(), any());
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	@Test
	@DisplayName("La baja es LOGICA: la fila sobrevive con su motivo y deja de computar")
	void la_baja_es_logica_la_fila_sobrevive() {
		DisponibilidadExcepcion cargada = cierreDelProfesional();
		given(excepciones.findByIdScoped(EXCEPCION_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(cargada));

		ExcepcionView vista = service.darDeBaja(
				actor, CONSULTORIO_ID, EXCEPCION_ID, "cargada por error");

		ArgumentCaptor<DisponibilidadExcepcion> guardada =
				ArgumentCaptor.forClass(DisponibilidadExcepcion.class);
		verify(excepciones).save(guardada.capture());

		assertThat(guardada.getValue().isActive()).isFalse();
		assertThat(guardada.getValue().getDeletedAt()).isNotNull();
		assertThat(guardada.getValue().getDeactivationReason()).isEqualTo("cargada por error");
		assertThat(guardada.getValue().isOperable())
				.as("deja de computar en la disponibilidad efectiva")
				.isFalse();
		assertThat(guardada.getValue().getFechaDesde())
				.as("y nada de lo que decia se modifica: RN-M05-003 conserva la historia")
				.isEqualTo(DESDE);
		assertThat(vista.estado()).isEqualTo("INACTIVO");

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().previousState()).isEqualTo("ACTIVO");
		assertThat(auditoria.getValue().newState()).isEqualTo("INACTIVO");
		assertThat(auditoria.getValue().reason()).isEqualTo("cargada por error");
	}

	@Test
	@DisplayName("La baja exige un motivo declarado y no toca nada sin el")
	void la_baja_exige_motivo() {
		assertThatThrownBy(() -> service.darDeBaja(actor, CONSULTORIO_ID, EXCEPCION_ID, "  "))
				.isInstanceOf(IllegalArgumentException.class);

		verify(excepciones, never()).save(any());
	}

	@Test
	@DisplayName("Dar de baja dos veces la misma excepcion es 409, no un segundo evento de auditoria")
	void dar_de_baja_dos_veces_es_409() {
		DisponibilidadExcepcion cargada = cierreDelProfesional();
		cargada.deactivate(Instant.parse("2026-03-01T12:00:00Z"), "ya estaba");
		given(excepciones.findByIdScoped(EXCEPCION_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(cargada));

		assertThatThrownBy(() -> service.darDeBaja(actor, CONSULTORIO_ID, EXCEPCION_ID, "otra vez"))
				.isInstanceOf(ExcepcionInactivaException.class);

		verify(excepciones, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Una excepcion de otra sede o de otro tenant sale por 404, nunca por 403")
	void una_excepcion_ajena_da_404() {
		given(excepciones.findByIdScoped(EXCEPCION_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.darDeBaja(actor, CONSULTORIO_ID, EXCEPCION_ID, "motivo"))
				.isInstanceOf(ExcepcionNotAccessibleException.class);
	}

	// =================================================================================
	// Quien puede ser SUJETO de una excepcion de profesional (ruling R18)
	// =================================================================================

	@ParameterizedTest(name = "un vinculo {0} SI puede tener una excepcion propia")
	@ValueSource(strings = {"PROFESIONAL", "CONSULTORIO_ADMIN", "ORG_ADMIN"})
	@DisplayName("Los tres roles que atienden pacientes pueden ser sujeto de una excepcion")
	void los_roles_que_atienden_pueden_tener_excepcion(String roleCode) {
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(conRol(roleCode)));

		ExcepcionView vista = service.crear(actor, CONSULTORIO_ID, altaDeCierre(MEMBERSHIP_ID));

		assertThat(vista.nuevo()).isTrue();
		verify(excepciones).save(any());
	}

	@ParameterizedTest(name = "un vinculo {0} NO puede tener una excepcion propia")
	@ValueSource(strings = {"ADMINISTRATIVO", "PACIENTE"})
	@DisplayName("Los dos roles que por definicion no atienden reciben 409 tambien en la excepcion")
	void los_roles_que_no_atienden_no_pueden_tener_excepcion(String roleCode) {
		// Una excepcion con alcance de profesional recorta o amplia LA DISPONIBILIDAD DE ESA
		// PERSONA: si no puede tener disponibilidad, tampoco puede tener una excepcion suya. Sin
		// este control quedaba una segunda puerta abierta al mismo dato invalido.
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(conRol(roleCode)));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, altaDeCierre(MEMBERSHIP_ID)))
				.isInstanceOf(ProfesionalNoVinculadoException.class);

		verify(excepciones, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private static ConsultorioMembershipSnapshot conRol(String roleCode) {
		return new ConsultorioMembershipSnapshot(
				MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, CONSULTORIO_ID, roleCode, "ACTIVA",
				Instant.parse("2020-01-01T00:00:00Z"), null, true, true);
	}

	private static ExcepcionAltaCommand altaDeCierre(Long membershipId) {
		return new ExcepcionAltaCommand(
				membershipId, TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA,
				DESDE, HASTA, null, null, null, null);
	}

	private static DisponibilidadExcepcion cierreDelProfesional() {
		DisponibilidadExcepcion cierre = new DisponibilidadExcepcion(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID,
				TipoExcepcion.CIERRE, MotivoExcepcion.AUSENCIA,
				DESDE, HASTA, null, null, null, null);
		ReflectionTestUtils.setField(cierre, "id", EXCEPCION_ID);
		return cierre;
	}
}
