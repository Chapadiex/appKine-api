package com.akine.resource.application;

import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.domain.CatalogoSolicitud;
import com.akine.resource.domain.CatalogoTipo;
import com.akine.resource.domain.SolicitudEstado;
import com.akine.resource.domain.exception.SolicitudDuplicadaException;
import com.akine.resource.domain.exception.SolicitudNotAccessibleException;
import com.akine.resource.domain.exception.SolicitudYaResueltaException;
import com.akine.resource.domain.port.CatalogoRepositoryPorts;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Las reglas de {@link CatalogoSolicitudService}: el tenant pide, la plataforma resuelve, y
 * ninguno puede hacer lo del otro.
 *
 * <p>La persistencia real del pedido y el unique de duplicados los cubre {@code CatalogoIT}.
 * Aca va lo que el servicio decide antes y despues de escribir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogoSolicitudServiceTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final long ACCOUNT_ID = 40L;
	private static final long ADMIN_ID = 1L;
	private static final long SOLICITUD_ID = 601L;

	@Mock
	private CatalogoRepositoryPorts.SolicitudPort solicitudes;

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private AuditTrail auditTrail;

	private CatalogoSolicitudService service;

	private final OperatingActor tenant =
			new OperatingActor(ACCOUNT_ID, false, ORG_ID, CONSULTORIO_ID);
	private final OperatingActor plataforma =
			new OperatingActor(ADMIN_ID, true, null, null);

	@BeforeEach
	void setUp() {
		service = new CatalogoSolicitudService(solicitudes, permissionGuard, auditTrail);

		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida("ORGANIZACION", false));
		given(solicitudes.saveAndFlush(any())).willAnswer(i -> conId(i.getArgument(0)));
		given(solicitudes.save(any())).willAnswer(i -> i.getArgument(0));
	}

	// =================================================================================
	// Alta
	// =================================================================================

	@Test
	@DisplayName("El tenant pide un concepto: queda PENDIENTE, de su organizacion y sede, y se audita")
	void el_pedido_del_tenant_queda_pendiente_y_se_audita() {
		CatalogoSolicitudView vista = service.crear(
				tenant, CatalogoTipo.PRACTICA, "Puncion seca", null, "La usamos a diario");

		assertThat(vista.estado()).isEqualTo(SolicitudEstado.PENDIENTE.name());
		assertThat(vista.organizationId()).isEqualTo(ORG_ID);
		assertThat(vista.consultorioId()).isEqualTo(CONSULTORIO_ID);
		assertThat(vista.solicitadaPorAccountId()).isEqualTo(ACCOUNT_ID);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.CATALOGO_SOLICITUD_CREATED);
		assertThat(auditoria.getValue().newState()).isEqualTo("PENDIENTE");
	}

	@Test
	@DisplayName("La plataforma no se pide conceptos a si misma: 403 sin escribir")
	void la_plataforma_no_solicita() {
		assertThatThrownBy(() -> service.crear(
				plataforma, CatalogoTipo.PRACTICA, "Puncion seca", null, "Justificacion"))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(solicitudes, auditTrail);
	}

	@Test
	@DisplayName("Sin consultorio:manage el tenant no pide nada")
	void el_pedido_sin_permiso_no_escribe() {
		given(permissionGuard.requirePermission(any()))
				.willThrow(new AccessDeniedException("sin consultorio:manage"));

		assertThatThrownBy(() -> service.crear(
				tenant, CatalogoTipo.PRACTICA, "Puncion seca", null, "Justificacion"))
				.isInstanceOf(AccessDeniedException.class);
		verify(solicitudes, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("Un pedido duplicado se traduce a 409 y no se audita")
	void el_pedido_duplicado_es_409_sin_auditoria() {
		willThrow(new DataIntegrityViolationException("uk")).given(solicitudes).saveAndFlush(any());

		assertThatThrownBy(() -> service.crear(
				tenant, CatalogoTipo.PRACTICA, "Puncion seca", null, "Justificacion"))
				.isInstanceOf(SolicitudDuplicadaException.class);
		verifyNoInteractions(auditTrail);
	}

	// =================================================================================
	// Listado
	// =================================================================================

	@Test
	@DisplayName("El tenant lista solo lo suyo; la plataforma lista todo")
	void el_listado_se_acota_segun_quien_mira() {
		given(solicitudes.listarPorTenant(any(), anyString())).willReturn(List.of());
		given(solicitudes.listarTodas(anyString())).willReturn(List.of());

		service.listar(tenant, SolicitudEstado.PENDIENTE);
		service.listar(plataforma, null);

		verify(solicitudes).listarPorTenant(ORG_ID, "PENDIENTE");
		verify(solicitudes).listarTodas("");
	}

	// =================================================================================
	// Resolucion
	// =================================================================================

	@Test
	@DisplayName("Solo la plataforma resuelve: el tenant recibe 403 sin leer la solicitud")
	void el_tenant_no_resuelve() {
		assertThatThrownBy(() -> service.resolver(tenant, SOLICITUD_ID,
				new SolicitudResolucionCommand(SolicitudEstado.APROBADA, "Ok", 0L)))
				.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(solicitudes);
	}

	@Test
	@DisplayName("Una solicitud inexistente es 404")
	void resolver_una_solicitud_inexistente_es_404() {
		given(solicitudes.findById(SOLICITUD_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.resolver(plataforma, SOLICITUD_ID,
				new SolicitudResolucionCommand(SolicitudEstado.APROBADA, "Ok", 0L)))
				.isInstanceOf(SolicitudNotAccessibleException.class);
	}

	@Test
	@DisplayName("Resolver una solicitud ya resuelta es 409")
	void resolver_dos_veces_es_409() {
		CatalogoSolicitud yaResuelta = solicitud();
		yaResuelta.resolver(SolicitudEstado.RECHAZADA, ADMIN_ID, "Ya existe", null, Instant.now());
		given(solicitudes.findById(SOLICITUD_ID)).willReturn(Optional.of(yaResuelta));

		assertThatThrownBy(() -> service.resolver(plataforma, SOLICITUD_ID,
				new SolicitudResolucionCommand(SolicitudEstado.APROBADA, "Ok", 0L)))
				.isInstanceOf(SolicitudYaResueltaException.class);
		verify(solicitudes, never()).save(any());
	}

	@Test
	@DisplayName("Resolver con una version vieja es 409 y no escribe")
	void resolver_con_version_vieja_es_409() {
		given(solicitudes.findById(SOLICITUD_ID)).willReturn(Optional.of(solicitud()));

		assertThatThrownBy(() -> service.resolver(plataforma, SOLICITUD_ID,
				new SolicitudResolucionCommand(SolicitudEstado.APROBADA, "Ok", 5L)))
				.isInstanceOf(OptimisticLockingFailureException.class);
		verify(solicitudes, never()).save(any());
	}

	@Test
	@DisplayName("La resolucion registra quien y por que, y se audita PENDIENTE -> APROBADA en el tenant")
	void la_resolucion_se_audita_en_el_tenant_que_pidio() {
		given(solicitudes.findById(SOLICITUD_ID)).willReturn(Optional.of(solicitud()));

		CatalogoSolicitudView vista = service.resolver(plataforma, SOLICITUD_ID,
				new SolicitudResolucionCommand(SolicitudEstado.APROBADA, "Se agrega al comun", 0L));

		assertThat(vista.estado()).isEqualTo("APROBADA");
		assertThat(vista.resueltaPorAccountId()).isEqualTo(ADMIN_ID);
		assertThat(vista.resolucionNota()).isEqualTo("Se agrega al comun");

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditTrail).record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo(AuditEvents.CATALOGO_SOLICITUD_RESOLVED);
		assertThat(auditoria.getValue().organizationId()).isEqualTo(ORG_ID);
		assertThat(auditoria.getValue().previousState()).isEqualTo("PENDIENTE");
		assertThat(auditoria.getValue().newState()).isEqualTo("APROBADA");
		assertThat(auditoria.getValue().reason()).isEqualTo("Se agrega al comun");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static CatalogoSolicitud solicitud() {
		return conId(new CatalogoSolicitud(ORG_ID, CONSULTORIO_ID, CatalogoTipo.PRACTICA,
				"Puncion seca", null, "La usamos a diario", ACCOUNT_ID));
	}

	private static CatalogoSolicitud conId(CatalogoSolicitud solicitud) {
		if (solicitud.getId() == null) {
			ReflectionTestUtils.setField(solicitud, "id", SOLICITUD_ID);
		}
		return solicitud;
	}
}
