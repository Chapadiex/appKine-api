package com.akine.notification.application;

import com.akine.notification.application.ConsultaDeNotificacionesService.NotificacionResumen;
import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.exception.NotificacionesNoAccesiblesException;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.notification.spi.NotificationType;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ConsultaDeNotificacionesServiceTest {

	private static final long ORG = 7L;
	private static final Instant AHORA = Instant.parse("2026-10-09T12:00:00Z");
	private static final OperatingActor ACTOR = new OperatingActor(18L, false, ORG);

	@Mock private NotificationOutboxRepositoryPort repository;
	@Mock private PermissionGuard permissionGuard;

	private ConsultaDeNotificacionesService service;

	@BeforeEach
	void setUp() {
		service = new ConsultaDeNotificacionesService(repository, permissionGuard, () -> AHORA);
	}

	@Test
	@DisplayName("sin estado lista FALLIDA y AGOTADA del contexto, con colaborador:manage exigido")
	void por_defecto_fallidas_y_agotadas() {
		NotificationOutboxEntry entry = entrada();
		given(repository.findByOrganizationIdAndEstadoIn(eq(ORG), anyCollection(), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(entry)));

		Page<NotificacionResumen> pagina = service.listar(ACTOR, ORG, null, 0, 20);

		ArgumentCaptor<PermissionQuery> permiso = ArgumentCaptor.forClass(PermissionQuery.class);
		then(permissionGuard).should().requirePermission(permiso.capture());
		assertThat(permiso.getValue().permissionCode()).isEqualTo("colaborador:manage");
		assertThat(permiso.getValue().organizationId()).isEqualTo(ORG);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<OutboxStatus>> estados = ArgumentCaptor.forClass(Collection.class);
		ArgumentCaptor<Pageable> paginacion = ArgumentCaptor.forClass(Pageable.class);
		then(repository).should().findByOrganizationIdAndEstadoIn(
				eq(ORG), estados.capture(), paginacion.capture());
		assertThat(estados.getValue())
				.containsExactlyInAnyOrder(OutboxStatus.FALLIDA, OutboxStatus.AGOTADA);
		assertThat(paginacion.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();

		assertThat(pagina.getContent()).hasSize(1);
	}

	@Test
	@DisplayName("el resumen no lleva PHI: destinatario enmascarado y error sanitizado y recortado")
	void resumen_sin_phi() {
		NotificationOutboxEntry entry = entrada();
		given(repository.findByOrganizationIdAndEstadoIn(eq(ORG), anyCollection(), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(entry)));

		NotificacionResumen resumen = service.listar(
				ACTOR, ORG, Set.of(OutboxStatus.AGOTADA), 0, 20).getContent().get(0);

		assertThat(resumen.id()).isEqualTo(42L);
		assertThat(resumen.tipo()).isEqualTo(NotificationType.INVITACION_COLABORADOR);
		assertThat(resumen.destinatarioEnmascarado()).isEqualTo("j***@ejemplo.test");
		assertThat(resumen.estado()).isEqualTo(OutboxStatus.AGOTADA);
		assertThat(resumen.reintentable()).isTrue();
		assertThat(resumen.intentos()).isEqualTo(5);
		assertThat(resumen.ultimoError())
				.doesNotContain("juana@ejemplo.test")
				.hasSizeLessThanOrEqualTo(ConsultaDeNotificacionesService.LARGO_RESUMEN_ERROR);
	}

	@Test
	@DisplayName("la organizacion de la ruta distinta de la del contexto es 404, antes del permiso")
	void ruta_de_otra_organizacion_es_404() {
		assertThatThrownBy(() -> service.listar(ACTOR, 99L, null, 0, 20))
				.isInstanceOf(NotificacionesNoAccesiblesException.class);
		then(permissionGuard).shouldHaveNoInteractions();
		then(repository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("sin contexto de trabajo es 403, no 401")
	void sin_contexto_es_403() {
		assertThatThrownBy(() -> service.listar(new OperatingActor(18L, false, null), ORG, null, 0, 20))
				.isInstanceOf(AccessDeniedException.class);
		then(repository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("sin el permiso no se lee nada")
	void sin_permiso_no_lee() {
		given(permissionGuard.requirePermission(any()))
				.willThrow(new AccessDeniedException("sin colaborador:manage"));

		assertThatThrownBy(() -> service.listar(ACTOR, ORG, null, 0, 20))
				.isInstanceOf(AccessDeniedException.class);
		then(repository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("un error vacio no se resume; uno largo se recorta")
	void resumen_del_error() {
		assertThat(ConsultaDeNotificacionesService.resumirError(null)).isNull();
		assertThat(ConsultaDeNotificacionesService.resumirError("  ")).isNull();
		assertThat(ConsultaDeNotificacionesService.resumirError("x ".repeat(300)))
				.hasSize(ConsultaDeNotificacionesService.LARGO_RESUMEN_ERROR)
				.endsWith("...");
	}

	private static NotificationOutboxEntry entrada() {
		NotificationOutboxEntry entry = mock(NotificationOutboxEntry.class);
		lenient().when(entry.getId()).thenReturn(42L);
		lenient().when(entry.getOrganizationId()).thenReturn(ORG);
		lenient().when(entry.getTipo()).thenReturn(NotificationType.INVITACION_COLABORADOR);
		lenient().when(entry.getDestinatario()).thenReturn("juana@ejemplo.test");
		lenient().when(entry.getEstado()).thenReturn(OutboxStatus.AGOTADA);
		lenient().when(entry.getIntentos()).thenReturn(5);
		lenient().when(entry.getMaxIntentos()).thenReturn(5);
		lenient().when(entry.getErrorSanitizado())
				.thenReturn("MailSendException: rebote para juana@ejemplo.test " + "y".repeat(400));
		lenient().when(entry.getCreatedAt()).thenReturn(AHORA.minusSeconds(3600));
		lenient().when(entry.getUpdatedAt()).thenReturn(AHORA);
		lenient().when(entry.getProximaEjecucionEn()).thenReturn(AHORA);
		return entry;
	}
}
