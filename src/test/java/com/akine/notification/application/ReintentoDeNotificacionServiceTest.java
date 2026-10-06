package com.akine.notification.application;

import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.exception.NotificacionNoEncontradaException;
import com.akine.notification.domain.exception.NotificacionNoReintentableException;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ReintentoDeNotificacionServiceTest {

	private static final long ORG = 7L;
	private static final long ID = 42L;
	private static final Instant AHORA = Instant.parse("2026-10-06T12:00:00Z");
	private static final OperatingActor ACTOR = new OperatingActor(18L, false, ORG);

	@Mock private NotificationOutboxRepositoryPort repository;
	@Mock private OutboxDispatchService dispatchService;
	@Mock private PermissionGuard permissionGuard;
	@Mock private AuditTrail auditTrail;

	private ReintentoDeNotificacionService service;

	@BeforeEach
	void setUp() {
		service = new ReintentoDeNotificacionService(
				repository, dispatchService, permissionGuard, auditTrail, () -> AHORA);
	}

	@Test
	@DisplayName("una AGOTADA del tenant vuelve a la cola, con permiso exigido y auditoria")
	void reintenta_y_audita() {
		existe(ORG, OutboxStatus.AGOTADA);
		given(dispatchService.reintentarManualmente(ID)).willReturn(true);

		service.reintentar(ACTOR, ORG, ID);

		ArgumentCaptor<PermissionQuery> permiso = ArgumentCaptor.forClass(PermissionQuery.class);
		then(permissionGuard).should().requirePermission(permiso.capture());
		assertThat(permiso.getValue().permissionCode()).isEqualTo("colaborador:manage");
		assertThat(permiso.getValue().organizationId()).isEqualTo(ORG);

		ArgumentCaptor<AuditEntry> auditoria = ArgumentCaptor.forClass(AuditEntry.class);
		then(auditTrail).should().record(auditoria.capture());
		assertThat(auditoria.getValue().eventType()).isEqualTo("NOTIFICACION_REINTENTADA");
		assertThat(auditoria.getValue().previousState()).isEqualTo("AGOTADA");
		assertThat(auditoria.getValue().newState()).isEqualTo("REINTENTABLE");
	}

	@Test
	@DisplayName("la de otra organizacion o sin tenant responde 404 y no se toca")
	void ajena_o_sin_tenant_es_404() {
		existe(99L, OutboxStatus.FALLIDA);
		assertThatThrownBy(() -> service.reintentar(ACTOR, ORG, ID))
				.isInstanceOf(NotificacionNoEncontradaException.class);

		existe(null, OutboxStatus.FALLIDA);
		assertThatThrownBy(() -> service.reintentar(ACTOR, ORG, ID))
				.isInstanceOf(NotificacionNoEncontradaException.class);

		then(dispatchService).should(never()).reintentarManualmente(any(Long.class));
	}

	@Test
	@DisplayName("la organizacion de la ruta distinta de la del contexto es 404, antes del permiso")
	void ruta_de_otra_organizacion_es_404() {
		assertThatThrownBy(() -> service.reintentar(ACTOR, 99L, ID))
				.isInstanceOf(NotificacionNoEncontradaException.class);
		then(permissionGuard).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("una que no esta FALLIDA ni AGOTADA es 409 y lleva su estado")
	void no_reintentable_es_409() {
		existe(ORG, OutboxStatus.ENVIADA);

		assertThatThrownBy(() -> service.reintentar(ACTOR, ORG, ID))
				.isInstanceOfSatisfying(NotificacionNoReintentableException.class,
						e -> assertThat(e.getEstado()).isEqualTo(OutboxStatus.ENVIADA));
		then(auditTrail).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("sin contexto de trabajo es 403, no 401")
	void sin_contexto_es_403() {
		assertThatThrownBy(() -> service.reintentar(new OperatingActor(18L, false, null), ORG, ID))
				.isInstanceOf(AccessDeniedException.class);
	}

	/** Arma la entrada antes del given: crearla adentro deja un stubbing anidado. */
	private void existe(Long organizationId, OutboxStatus estado) {
		NotificationOutboxEntry entry = entrada(organizationId, estado);
		given(repository.findById(ID)).willReturn(Optional.of(entry));
	}

	private static NotificationOutboxEntry entrada(Long organizationId, OutboxStatus estado) {
		NotificationOutboxEntry entry = mock(NotificationOutboxEntry.class);
		org.mockito.Mockito.lenient().when(entry.getOrganizationId()).thenReturn(organizationId);
		org.mockito.Mockito.lenient().when(entry.getEstado()).thenReturn(estado);
		org.mockito.Mockito.lenient().when(entry.getTipo())
				.thenReturn(NotificationType.INVITACION_COLABORADOR);
		return entry;
	}
}
