package com.akine.organization.application;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.ConsultorioAlta;
import com.akine.organization.domain.exception.ConsultorioHasActiveReferencesException;
import com.akine.organization.domain.exception.ConsultorioInactiveException;
import com.akine.organization.domain.exception.ConsultorioNameTakenException;
import com.akine.organization.domain.exception.ConsultorioNotAccessibleException;
import com.akine.organization.domain.exception.LastConsultorioException;
import com.akine.organization.domain.port.ConsultorioAltaRepositoryPort;
import com.akine.organization.domain.port.ConsultorioRepositoryPort;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import com.akine.organization.spi.ConsultorioDeactivationProbe;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PlanGate;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static com.akine.organization.application.Fixtures.CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static com.akine.organization.application.Fixtures.conId;
import static com.akine.organization.application.Fixtures.consultorio;
import static com.akine.organization.application.Fixtures.organizacion;
import static com.akine.organization.application.Fixtures.suscripcionActiva;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las reglas de {@link ConsultorioService} que no necesitan MySQL: la idempotencia del alta, el
 * orden de bloqueo de la baja, el invariante de ultima sede y la auditoria del uso de soporte.
 *
 * <p>{@code ConsultoriosIT}, {@code LimiteDePlanConcurrenteIT} y {@code SoporteEnLecturasIT}
 * siguen siendo los que prueban el lock real y la transaccion propia de la lectura.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsultorioServiceTest {

	private static final long ACCOUNT_ID = 30L;
	private static final String CLAVE = "clave-sintetica-1";
	private static final String HASH = "hash-1";

	@Mock
	private ConsultorioRepositoryPort consultorios;

	@Mock
	private ConsultorioAltaRepositoryPort altas;

	@Mock
	private OrganizationRepositoryPort organizaciones;

	@Mock
	private SubscriptionRepositoryPort suscripciones;

	@Mock
	private PlanGate planGate;

	@Mock
	private TenantUsageCounter usageCounter;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	@Mock
	private SupportAccessReadAuditor supportAccessReadAuditor;

	@Mock
	private ConsultorioDeactivationProbe turnosFuturos;

	private ConsultorioService service;

	private final OperatingActor actor = new OperatingActor(ACCOUNT_ID, false, CONSULTORIO_ID);

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		service = new ConsultorioService(
				consultorios, altas, organizaciones, suscripciones, planGate, usageCounter,
				permissionGuard, auditTrail, supportAccessReadAuditor, List.of(turnosFuturos));

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(planGate.createWithinLimit(anyLong(), any(), any(), any()))
				.willAnswer(i -> ((Supplier<Object>) i.getArgument(3)).get());
		given(organizaciones.findByIdAndActiveTrue(ORG_ID)).willReturn(Optional.of(organizacion()));
		given(suscripciones.findByOrganizationIdForUpdate(ORG_ID))
				.willReturn(Optional.of(suscripcionActiva(40L)));
		given(altas.findByOrganizationIdAndIdempotencyKey(ORG_ID, CLAVE)).willReturn(Optional.empty());
		given(consultorios.saveAndFlush(any())).willAnswer(i -> {
			Consultorio c = i.getArgument(0);
			return c.getId() == null ? conId(c, CONSULTORIO_ID) : c;
		});
		given(consultorios.save(any())).willAnswer(i -> i.getArgument(0));
		given(consultorios.countActiveForShare(ORG_ID, CONSULTORIO_ID)).willReturn(1L);
		given(turnosFuturos.activeReferencesOn(anyLong(), anyLong(), any()))
				.willReturn(new ConsultorioDeactivationProbe.ActiveReferences("TURNO", 0));
	}

	// =================================================================================
	// Alta e idempotencia
	// =================================================================================

	@Test
	@DisplayName("Sin clave de idempotencia el alta se rechaza antes de tocar el gate de plan")
	void el_alta_sin_clave_se_rechaza() {
		assertThatThrownBy(() -> service.create(actor, ORG_ID, alta(" ", HASH)))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(planGate);
	}

	@Test
	@DisplayName("Un reintento con la misma clave devuelve la sede ya creada sin pasar por el gate")
	void el_reintento_con_la_misma_clave_es_un_replay() {
		given(altas.findByOrganizationIdAndIdempotencyKey(ORG_ID, CLAVE)).willReturn(Optional.of(
				new ConsultorioAlta(ORG_ID, CLAVE, HASH, CONSULTORIO_ID, Instant.now())));
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(consultorio(CONSULTORIO_ID, "Sede Centro")));

		ConsultorioView vista = service.create(actor, ORG_ID, alta(CLAVE, HASH));

		assertThat(vista.id()).isEqualTo(CONSULTORIO_ID);
		// Un replay no consume cupo de plan: si pasara por el gate podria rechazarlo un limite
		// que el alta original ya respeto.
		verifyNoInteractions(planGate, auditTrail);
		verify(consultorios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("La misma clave con otro contenido es un conflicto, no un replay")
	void la_misma_clave_con_otro_contenido_es_conflicto() {
		given(altas.findByOrganizationIdAndIdempotencyKey(ORG_ID, CLAVE)).willReturn(Optional.of(
				new ConsultorioAlta(ORG_ID, CLAVE, HASH, CONSULTORIO_ID, Instant.now())));

		assertThatThrownBy(() -> service.create(actor, ORG_ID, alta(CLAVE, "otro-hash")))
				.isInstanceOf(IdempotencyKeyConflictException.class);
		verifyNoInteractions(planGate);
	}

	@Test
	@DisplayName("El alta pasa por el limite MAX_CONSULTORIOS, toma la zona de la organizacion y se audita")
	void el_alta_valida_pasa_por_el_limite_y_se_audita() {
		ConsultorioView vista = service.create(actor, ORG_ID, alta(CLAVE, HASH));

		assertThat(vista.name()).isEqualTo("Sede Centro");
		assertThat(vista.timezone()).isEqualTo(organizacion().getTimezone());
		verify(planGate).createWithinLimit(eq(ORG_ID), eq(LimitCode.MAX_CONSULTORIOS), any(), any());
		verify(altas).saveAndFlush(any());

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.CONSULTORIO_CREATED);
		assertThat(auditoria.getValue().newState()).isEqualTo("ACTIVO");
	}

	@Test
	@DisplayName("Un nombre repetido es 409, no registra la clave y no audita")
	void el_nombre_repetido_es_409() {
		willThrow(new DataIntegrityViolationException("uk")).given(consultorios).saveAndFlush(any());

		assertThatThrownBy(() -> service.create(actor, ORG_ID, alta(CLAVE, HASH)))
				.isInstanceOf(ConsultorioNameTakenException.class);

		verify(altas, never()).saveAndFlush(any());
		verifyNoInteractions(auditTrail);
	}

	@Test
	@DisplayName("Dos altas concurrentes con la misma clave: la perdedora es alta-en-curso")
	void la_clave_concurrente_es_alta_en_curso() {
		willThrow(new DataIntegrityViolationException("uk")).given(altas).saveAndFlush(any());

		assertThatThrownBy(() -> service.create(actor, ORG_ID, alta(CLAVE, HASH)))
				.isInstanceOf(ConsultorioAltaEnCursoException.class);
		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Lectura
	// =================================================================================

	@Test
	@DisplayName("Una lectura via soporte se audita por la transaccion propia, aunque termine en 404")
	void la_lectura_via_soporte_se_audita_aparte_aun_con_404() {
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("SOPORTE", true));
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.find(actor, ORG_ID, CONSULTORIO_ID))
				.isInstanceOf(ConsultorioNotAccessibleException.class);

		verify(supportAccessReadAuditor).record(any());
		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Edicion
	// =================================================================================

	@Test
	@DisplayName("Editar una sede dada de baja es 409")
	void editar_una_sede_inactiva_es_409() {
		Consultorio dadaDeBaja = consultorio(CONSULTORIO_ID, "Sede Centro");
		dadaDeBaja.deactivate(Instant.now(), "Cierre");
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(dadaDeBaja));

		assertThatThrownBy(() -> service.update(actor, ORG_ID, CONSULTORIO_ID, edicion(null, 0L)))
				.isInstanceOf(ConsultorioInactiveException.class);
	}

	@Test
	@DisplayName("Editar con una version vieja es 409 y no escribe")
	void editar_con_version_vieja_es_409() {
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(consultorio(CONSULTORIO_ID, "Sede Centro")));

		assertThatThrownBy(() -> service.update(actor, ORG_ID, CONSULTORIO_ID, edicion(null, 4L)))
				.isInstanceOf(OptimisticLockingFailureException.class);
		verify(consultorios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Un intervalo de agenda fuera de 5..480 minutos se rechaza")
	void el_slot_fuera_de_rango_se_rechaza() {
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(consultorio(CONSULTORIO_ID, "Sede Centro")));

		assertThatThrownBy(() -> service.update(actor, ORG_ID, CONSULTORIO_ID, edicion(4, 0L)))
				.isInstanceOf(IllegalArgumentException.class);
		verify(consultorios, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Una edicion via soporte deja UN SUPPORT_ACCESS_USED, no dos")
	void la_edicion_via_soporte_audita_el_uso_una_sola_vez() {
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("SOPORTE", true));
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(consultorio(CONSULTORIO_ID, "Sede Centro")));

		service.update(actor, ORG_ID, CONSULTORIO_ID, edicion(45, 0L));

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail, times(2)).record(auditoria.capture());
		// Una operacion amparada por soporte es UN hecho de soporte (matriz seccion 7). Dos filas
		// harian creer, a quien reconstruye la intervencion, que hubo dos operaciones.
		assertThat(auditoria.getAllValues()).extracting(AuditEntry::eventType)
				.containsExactly(AuditEvents.CONSULTORIO_UPDATED, AuditEvents.SUPPORT_ACCESS_USED);
		assertThat(auditoria.getAllValues().get(0).details()).containsEntry("slotMinutes", "30 -> 45");
	}

	// =================================================================================
	// Baja
	// =================================================================================

	@Test
	@DisplayName("La baja bloquea la suscripcion ANTES de evaluar nada: orden subscription -> organization")
	void la_baja_bloquea_la_suscripcion_primero() {
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(consultorio(CONSULTORIO_ID, "Sede Centro")));

		service.deactivate(actor, ORG_ID, CONSULTORIO_ID, "Cierre de la sucursal");

		InOrder orden = inOrder(suscripciones, permissionGuard, consultorios);
		orden.verify(suscripciones).findByOrganizationIdForUpdate(ORG_ID);
		orden.verify(permissionGuard).requirePermission(any());
		orden.verify(consultorios).countActiveForShare(ORG_ID, CONSULTORIO_ID);
	}

	@Test
	@DisplayName("La baja sin motivo se rechaza y no evalua permisos")
	void la_baja_sin_motivo_se_rechaza() {
		assertThatThrownBy(() -> service.deactivate(actor, ORG_ID, CONSULTORIO_ID, ""))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(permissionGuard);
	}

	@Test
	@DisplayName("No se da de baja la ultima sede activa de la organizacion: 409")
	void la_ultima_sede_no_se_da_de_baja() {
		Consultorio ultima = consultorio(CONSULTORIO_ID, "Sede Centro");
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID)).willReturn(Optional.of(ultima));
		given(consultorios.countActiveForShare(ORG_ID, CONSULTORIO_ID)).willReturn(0L);

		assertThatThrownBy(() -> service.deactivate(actor, ORG_ID, CONSULTORIO_ID, "Cierre"))
				.isInstanceOf(LastConsultorioException.class);
		assertThat(ultima.isOperable()).isTrue();
		verify(consultorios, never()).save(any());
	}

	@Test
	@DisplayName("Si una sonda declara referencias vigentes, la baja es 409")
	void la_baja_con_referencias_vigentes_es_409() {
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(consultorio(CONSULTORIO_ID, "Sede Centro")));
		given(turnosFuturos.activeReferencesOn(anyLong(), anyLong(), any()))
				.willReturn(new ConsultorioDeactivationProbe.ActiveReferences("TURNO", 3));

		assertThatThrownBy(() -> service.deactivate(actor, ORG_ID, CONSULTORIO_ID, "Cierre"))
				.isInstanceOf(ConsultorioHasActiveReferencesException.class);
		verify(consultorios, never()).save(any());
	}

	@Test
	@DisplayName("La baja es logica y se audita ACTIVO -> INACTIVO con el motivo")
	void la_baja_es_logica_y_se_audita() {
		given(consultorios.findByIdAndOrganizationId(CONSULTORIO_ID, ORG_ID))
				.willReturn(Optional.of(consultorio(CONSULTORIO_ID, "Sede Centro")));

		ConsultorioView vista = service.deactivate(actor, ORG_ID, CONSULTORIO_ID, "Cierre");

		assertThat(vista.active()).isFalse();
		assertThat(vista.deactivationReason()).isEqualTo("Cierre");
		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.CONSULTORIO_DEACTIVATED);
		assertThat(auditoria.getValue().previousState()).isEqualTo("ACTIVO");
		assertThat(auditoria.getValue().newState()).isEqualTo("INACTIVO");
		assertThat(auditoria.getValue().reason()).isEqualTo("Cierre");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsultorioAltaCommand alta(String clave, String hash) {
		return new ConsultorioAltaCommand(
				"Sede Centro", null, null, null, null, null, null, null, clave, hash);
	}

	private static ConsultorioEdicionCommand edicion(Integer slotMinutes, long version) {
		return new ConsultorioEdicionCommand(
				null, null, slotMinutes, null, null, null, null, null, version);
	}
}
