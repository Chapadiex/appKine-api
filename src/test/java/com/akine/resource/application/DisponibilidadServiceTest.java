package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.BloqueDisponibilidad;
import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.exception.BloqueSolapadoException;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.BloqueDisponibilidadRepositoryPort;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import com.akine.resource.spi.DisponibilidadImpactProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Los invariantes de la disponibilidad semanal, sin base de datos.
 *
 * <h2>Que puede y que no puede probar este test</h2>
 *
 * <p><b>Puede</b> probar el ORDEN del protocolo —que el lock de la sede es anterior a la primera
 * lectura de bloques, que la pertenencia se comprueba antes que el permiso—, los dos predicados
 * que la etapa confunde con facilidad (coincidencia exacta contra solapamiento, y contiguo
 * contra solapado) y que la auditoria se escribe en la transaccion del negocio.
 *
 * <p><b>No puede</b> probar que el invariante se sostenga bajo concurrencia real: eso necesita
 * dos transacciones contra MySQL y es un IT. Lo que este test fija es que el codigo pida el lock
 * en el momento correcto; que el lock funcione es del motor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisponibilidadServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long OTRA_SEDE_ID = 21L;
	private static final long MEMBERSHIP_ID = 30L;
	private static final long ACCOUNT_ID = 40L;
	private static final long BLOQUE_ID = 50L;
	private static final String ZONA = "America/Argentina/Buenos_Aires";

	private static final int MARTES = 2;
	private static final LocalDate DESDE_MARZO = LocalDate.of(2026, 3, 1);

	@Mock
	private BloqueDisponibilidadRepositoryPort bloques;

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

	@Mock
	private DisponibilidadImpactProbe impactProbe;

	private DisponibilidadService service;

	private final OperatingActor actor =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);

	@BeforeEach
	void setUp() {
		service = new DisponibilidadService(
				bloques, calendarios, consultorioDirectory, membershipDirectory,
				permissionGuard, auditTrail, impactProbe);

		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(new ConsultorioSnapshot(
						CONSULTORIO_ID, ORG_ID, "Sede Centro", ZONA, true)));
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(profesionalDeLaSede(CONSULTORIO_ID)));
		given(calendarios.lockByScope(ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(new CalendarioSede(ORG_ID, CONSULTORIO_ID)));
		given(bloques.save(any())).willAnswer(invocacion -> {
			BloqueDisponibilidad guardado = invocacion.getArgument(0);
			if (guardado.getId() == null) {
				ReflectionTestUtils.setField(guardado, "id", BLOQUE_ID);
			}
			return guardado;
		});
		given(impactProbe.turnosEn(anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(DisponibilidadImpactProbe.Impacto.ninguno());
	}

	// =================================================================================
	// Autorizacion
	// =================================================================================

	@Test
	@DisplayName("Una sede de otro tenant sale por 404 y ni siquiera llega al evaluador de permisos")
	void una_sede_de_otro_tenant_da_404_y_no_403() {
		given(consultorioDirectory.find(ORG_ID, CONSULTORIO_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(ConsultorioNotAccessibleException.class);

		// Lo que fija este verify es el ORDEN: si el permiso se evaluara primero, un tenant ajeno
		// recibiria 403 y eso confirmaria que la sede existe.
		verifyNoInteractions(permissionGuard);
		verifyNoInteractions(bloques);
	}

	@Test
	@DisplayName("Sin consultorio:manage el alta responde 403 y no escribe nada")
	void sin_consultorio_manage_el_alta_da_403() {
		given(permissionGuard.requirePermission(any()))
				.willThrow(new AccessDeniedException("sin permiso"));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(AccessDeniedException.class);

		verify(bloques, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Un profesional no puede cargar su propio bloque: el codigo exigido es consultorio:manage")
	void un_profesional_no_puede_crear_su_propio_bloque() {
		// La matriz seccion 6 le NIEGA consultorio:manage a PROFESIONAL, asi que el evaluador
		// rechaza. Lo que este test fija es que el servicio pida ESE codigo y no uno de lectura:
		// si alguien lo cambiara por colaborador:read, el profesional pasaria.
		given(permissionGuard.requirePermission(any()))
				.willThrow(new AccessDeniedException("PROFESIONAL no gestiona la sede"));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(AccessDeniedException.class);

		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo("consultorio:manage");
		assertThat(consulta.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("La lectura exige colaborador:read con la sede como alcance, no espacio:read")
	void con_colaborador_read_puede_listar() {
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		List<BloqueView> horario = service.listar(actor, CONSULTORIO_ID, MEMBERSHIP_ID);

		assertThat(horario).hasSize(1);
		assertThat(horario.getFirst().estado()).isEqualTo("ACTIVO");

		ArgumentCaptor<PermissionQuery> consulta = ArgumentCaptor.forClass(PermissionQuery.class);
		verify(permissionGuard).requirePermission(consulta.capture());
		assertThat(consulta.getValue().permissionCode()).isEqualTo("colaborador:read");
		assertThat(consulta.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("Una membership que no cubre la sede se rechaza con 409, no con 404")
	void una_membership_que_no_cubre_la_sede_es_rechazada() {
		given(membershipDirectory.find(ORG_ID, MEMBERSHIP_ID))
				.willReturn(Optional.of(profesionalDeLaSede(OTRA_SEDE_ID)));

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes()))
				.isInstanceOf(ProfesionalNoVinculadoException.class);

		verify(bloques, never()).save(any());
	}

	// =================================================================================
	// Idempotencia y solapamiento
	// =================================================================================

	@Test
	@DisplayName("Un alta identica devuelve el bloque que ya existe: no duplica y no da 409")
	void un_alta_exactamente_igual_devuelve_el_bloque_existente() {
		BloqueDisponibilidad yaCargado =
				bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0));
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(yaCargado));

		BloqueView vista = service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		assertThat(vista.id()).isEqualTo(BLOQUE_ID);
		verify(bloques, never()).save(any());
		// Tampoco se audita de nuevo: el reintento de red tiene que dejar el historial igual que
		// lo dejo el primer intento.
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Un alta que se pisa sin coincidir responde 409")
	void un_alta_que_solapa_sin_coincidir_da_409() {
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		BloqueAltaCommand pisando = new BloqueAltaCommand(
				MARTES, LocalTime.of(11, 0), LocalTime.of(13, 0), DESDE_MARZO, null);

		assertThatThrownBy(() -> service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, pisando))
				.isInstanceOf(BloqueSolapadoException.class)
				.extracting(conflicto -> ((BloqueSolapadoException) conflicto).getBloqueEnConflictoId())
				.isEqualTo(BLOQUE_ID);

		verify(bloques, never()).save(any());
	}

	@Test
	@DisplayName("Manana y tarde del mismo dia son contiguas, no solapadas: 09-12 y 12-15 conviven")
	void dos_bloques_contiguos_del_mismo_dia_son_validos() {
		given(bloques.findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID))
				.willReturn(List.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		BloqueAltaCommand tarde = new BloqueAltaCommand(
				MARTES, LocalTime.of(12, 0), LocalTime.of(15, 0), DESDE_MARZO, null);

		BloqueView vista = service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, tarde);

		assertThat(vista.horaDesde()).isEqualTo(LocalTime.of(12, 0));
		assertThat(vista.horaHasta()).isEqualTo(LocalTime.of(15, 0));
		verify(bloques).save(any());
	}

	// =================================================================================
	// Concurrencia
	// =================================================================================

	@Test
	@DisplayName("El lock de la sede se toma ANTES de leer los bloques: leer primero es una escalada S->X")
	void el_lock_se_toma_antes_de_leer_los_bloques() {
		service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		InOrder protocolo = inOrder(calendarios, bloques);
		protocolo.verify(calendarios).lockByScope(ORG_ID, CONSULTORIO_ID);
		protocolo.verify(bloques).findActivosDe(ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID);
	}

	@Test
	@DisplayName("Editar con una version vieja responde 409 concurrent-modification, no pisa el cambio ajeno")
	void la_edicion_con_version_vieja_da_409_concurrent_modification() {
		BloqueDisponibilidad enBase =
				bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0));
		ReflectionTestUtils.setField(enBase, "version", 3L);
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(enBase));

		BloqueEdicionCommand conVersionVieja = new BloqueEdicionCommand(
				null, LocalTime.of(10, 0), null, null, null, false, 1L);

		assertThatThrownBy(() -> service.editar(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID, conVersionVieja))
				.isInstanceOf(OptimisticLockingFailureException.class);

		verify(bloques, never()).save(any());
		assertThat(enBase.getHoraDesde()).isEqualTo(LocalTime.of(9, 0));
	}

	// =================================================================================
	// Baja logica
	// =================================================================================

	@Test
	@DisplayName("La baja sin motivo se rechaza: sin el, la auditoria no responde por que")
	void la_baja_exige_motivo() {
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0))));

		assertThatThrownBy(() -> service.darDeBaja(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID, "   "))
				.isInstanceOf(IllegalArgumentException.class);

		verify(bloques, never()).save(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("La baja es logica: conserva la fila, el motivo y la historia (RN-M05-003)")
	void la_baja_es_logica_y_conserva_la_fila() {
		BloqueDisponibilidad enBase =
				bloque(BLOQUE_ID, MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0));
		given(bloques.findByIdScoped(BLOQUE_ID, ORG_ID, CONSULTORIO_ID))
				.willReturn(Optional.of(enBase));

		BloqueView vista = service.darDeBaja(
				actor, CONSULTORIO_ID, MEMBERSHIP_ID, BLOQUE_ID, "El profesional cambio de turno");

		ArgumentCaptor<BloqueDisponibilidad> guardado =
				ArgumentCaptor.forClass(BloqueDisponibilidad.class);
		verify(bloques).save(guardado.capture());

		assertThat(guardado.getValue().isActive()).isFalse();
		assertThat(guardado.getValue().getDeletedAt()).isNotNull();
		assertThat(guardado.getValue().getDeactivationReason())
				.isEqualTo("El profesional cambio de turno");
		// La fila sigue ahi con todos sus datos: el dia y las horas no se tocan.
		assertThat(guardado.getValue().getDiaSemana()).isEqualTo(MARTES);
		assertThat(guardado.getValue().getHoraDesde()).isEqualTo(LocalTime.of(9, 0));
		assertThat(vista.estado()).isEqualTo("INACTIVO");
		assertThat(vista.deactivationReason()).isEqualTo("El profesional cambio de turno");
	}

	// =================================================================================
	// Auditoria
	// =================================================================================

	@Test
	@DisplayName("La auditoria se escribe en la transaccion del negocio, no en un listener post-commit")
	void la_auditoria_se_escribe_en_la_misma_transaccion() throws NoSuchMethodException {
		service.crear(actor, CONSULTORIO_ID, MEMBERSHIP_ID, altaMartes());

		ArgumentCaptor<AuditEntry> fila = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(fila.capture());
		assertThat(fila.getValue().eventType()).isEqualTo("DISPONIBILIDAD_BLOQUE_CREATED");
		assertThat(fila.getValue().organizationId()).isEqualTo(ORG_ID);
		assertThat(fila.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(fila.getValue().entityId()).isEqualTo(BLOQUE_ID);
		assertThat(fila.getValue().actorAccountId()).isEqualTo(ACCOUNT_ID);

		// Que la escritura ocurra DENTRO de la transaccion no lo prueba el verify de arriba por si
		// solo: lo prueba que el metodo que la produjo sea transaccional. Un listener post-commit
		// que falla dejaria la mutacion sin rastro, y es lo que este assert impide reintroducir.
		Transactional demarcacion = DisponibilidadService.class
				.getMethod("crear", OperatingActor.class, long.class, long.class, BloqueAltaCommand.class)
				.getAnnotation(Transactional.class);
		assertThat(demarcacion).isNotNull();
		assertThat(demarcacion.readOnly()).isFalse();
	}

	// =================================================================================
	// Fixtures sinteticas
	// =================================================================================

	private static BloqueAltaCommand altaMartes() {
		return new BloqueAltaCommand(MARTES, LocalTime.of(9, 0), LocalTime.of(12, 0), DESDE_MARZO, null);
	}

	private static BloqueDisponibilidad bloque(long id, int dia, LocalTime desde, LocalTime hasta) {
		BloqueDisponibilidad bloque = new BloqueDisponibilidad(
				ORG_ID, CONSULTORIO_ID, MEMBERSHIP_ID, dia, desde, hasta, DESDE_MARZO, null);
		ReflectionTestUtils.setField(bloque, "id", id);
		return bloque;
	}

	private static ConsultorioMembershipSnapshot profesionalDeLaSede(Long consultorioId) {
		return new ConsultorioMembershipSnapshot(
				MEMBERSHIP_ID, ACCOUNT_ID, ORG_ID, consultorioId, "PROFESIONAL", "ACTIVA",
				Instant.parse("2026-01-01T00:00:00Z"), null, true, true);
	}
}
