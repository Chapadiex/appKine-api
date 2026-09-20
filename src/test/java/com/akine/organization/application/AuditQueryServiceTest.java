package com.akine.organization.application;

import com.akine.organization.domain.PermissionScope;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEventFilter;
import com.akine.platform.spi.audit.AuditEventSummary;
import com.akine.platform.spi.audit.AuditQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.akine.organization.application.Fixtures.ACCOUNT_ID;
import static com.akine.organization.application.Fixtures.CONSULTORIO_ID;
import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Consulta autorizada de la auditoria (RF-M24-002/003/004).
 *
 * <p>Lo que se prueba es lo que hace que esta clase exista: <b>que el alcance del filtro salga
 * de la decision del evaluador</b> y no se recalcule, y que el {@code organizationId} venga del
 * contexto y no del cliente. Recalcular el alcance seria abrir la puerta a que las dos versiones
 * divergieran, y sobre la auditoria eso significa que alguien lee lo que no le corresponde.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditQueryServiceTest {

	private static final Instant AHORA = Instant.now();

	@Mock
	private PermissionGuard permissionGuard;

	@Mock
	private com.akine.organization.spi.PermissionEvaluator permissionEvaluator;

	@Mock
	private AuditQuery auditQuery;

	@InjectMocks
	private AuditQueryService service;

	private final OperatingActor actor = new OperatingActor(ACCOUNT_ID, false, CONSULTORIO_ID);

	private void concedidoCon(PermissionScope alcance) {
		given(permissionGuard.requirePermission(any()))
				.willReturn(PermissionDecision.concedida(alcance.name(), false));
		// Default deliberado: auditoria:read NO trae auditoria:read-clinica. Es el caso del
		// ORG_ADMIN, que tiene el primero por asignacion base y el segundo solo por grant.
		clinicoConcedido(false);
	}

	private void devuelveVacio() {
		Page<AuditEventSummary> vacia = new PageImpl<>(List.of());
		given(auditQuery.porEntidad(any(), any())).willReturn(vacia);
		given(auditQuery.porActor(any(), any())).willReturn(vacia);
		given(auditQuery.porPeriodo(any(), any())).willReturn(vacia);
	}

	private AuditEventFilter filtroDePeriodo() {
		ArgumentCaptor<AuditEventFilter> captor = ArgumentCaptor.forClass(AuditEventFilter.class);
		verify(auditQuery).porPeriodo(captor.capture(), any());
		return captor.getValue();
	}

	// =================================================================================
	// Alcance
	// =================================================================================

	@Test
	@DisplayName("Con alcance de organizacion se lee el tenant entero: sin filtro de sede")
	void alcance_de_organizacion_lee_todo_el_tenant() {
		concedidoCon(PermissionScope.ORGANIZACION);
		devuelveVacio();

		service.porPeriodo(actor, ORG_ID, AHORA.minus(7, ChronoUnit.DAYS), AHORA,
				PageRequest.of(0, 20));

		assertThat(filtroDePeriodo().consultorioId()).isNull();
		assertThat(filtroDePeriodo().organizationId()).isEqualTo(ORG_ID);
	}

	@Test
	@DisplayName("Con alcance de sede se lee SU sede: el filtro sale de la decision, no se recalcula")
	void alcance_de_sede_acota_la_lectura() {
		// El alcance del permiso y el filtro de la consulta son el mismo dato. Recalcularlo aca
		// abriria la puerta a que las dos versiones divergieran.
		concedidoCon(PermissionScope.CONSULTORIO);
		devuelveVacio();

		service.porPeriodo(actor, ORG_ID, AHORA.minus(7, ChronoUnit.DAYS), AHORA,
				PageRequest.of(0, 20));

		assertThat(filtroDePeriodo().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	@Test
	@DisplayName("Con alcance global tampoco se acota")
	void alcance_global_no_acota() {
		concedidoCon(PermissionScope.GLOBAL);
		devuelveVacio();

		service.porPeriodo(new OperatingActor(ACCOUNT_ID, true, null), ORG_ID,
				AHORA.minus(1, ChronoUnit.DAYS), AHORA, PageRequest.of(0, 20));

		assertThat(filtroDePeriodo().consultorioId()).isNull();
	}

	@Test
	@DisplayName("Sin auditoria:read no se lee nada, y el rechazo se propaga tal cual")
	void sin_permiso_no_se_lee() {
		willThrow(new PermissionDeniedException("auditoria:read", ACCOUNT_ID, ORG_ID))
				.given(permissionGuard).requirePermission(any());

		assertThatThrownBy(() -> service.porPeriodo(
				actor, ORG_ID, AHORA.minus(1, ChronoUnit.DAYS), AHORA, PageRequest.of(0, 20)))
				.isInstanceOf(PermissionDeniedException.class);

		verifyNoInteractions(auditQuery);
	}

	@Test
	@DisplayName("Se pide auditoria:read con la organizacion y la sede del contexto")
	void se_pide_el_permiso_correcto() {
		concedidoCon(PermissionScope.ORGANIZACION);
		devuelveVacio();

		service.porActor(actor, ORG_ID, 99L, PageRequest.of(0, 20));

		var captor = ArgumentCaptor.forClass(com.akine.organization.spi.PermissionQuery.class);
		verify(permissionGuard).requirePermission(captor.capture());
		assertThat(captor.getValue().permissionCode()).isEqualTo("auditoria:read");
		assertThat(captor.getValue().organizationId()).isEqualTo(ORG_ID);
		assertThat(captor.getValue().consultorioId()).isEqualTo(CONSULTORIO_ID);
	}

	// =================================================================================
	// Los tres filtros
	// =================================================================================

	@Test
	@DisplayName("Cada RF va por su consulta, y por lo tanto por su indice")
	void cada_rf_por_su_consulta() {
		concedidoCon(PermissionScope.ORGANIZACION);
		devuelveVacio();
		Pageable pagina = PageRequest.of(0, 20);

		service.porEntidad(actor, ORG_ID, "Membership", 60L, pagina);
		service.porActor(actor, ORG_ID, 99L, pagina);
		service.porPeriodo(actor, ORG_ID, AHORA.minus(1, ChronoUnit.DAYS), AHORA, pagina);

		var entidad = ArgumentCaptor.forClass(AuditEventFilter.class);
		verify(auditQuery).porEntidad(entidad.capture(), any());
		assertThat(entidad.getValue().entityType()).isEqualTo("Membership");
		assertThat(entidad.getValue().entityId()).isEqualTo(60L);

		var actorFiltro = ArgumentCaptor.forClass(AuditEventFilter.class);
		verify(auditQuery).porActor(actorFiltro.capture(), any());
		assertThat(actorFiltro.getValue().actorAccountId()).isEqualTo(99L);

		assertThat(filtroDePeriodo().desde()).isNotNull();
		assertThat(filtroDePeriodo().hasta()).isNotNull();
	}

	// =================================================================================
	// Limites (D-8: defaults provisorios, pero innegociables una vez fijados)
	// =================================================================================

	@Test
	@DisplayName("Un rango invertido o incompleto es 400")
	void un_rango_invalido_es_400() {
		concedidoCon(PermissionScope.ORGANIZACION);

		assertThatThrownBy(() -> service.porPeriodo(
				actor, ORG_ID, AHORA, AHORA.minus(1, ChronoUnit.DAYS), PageRequest.of(0, 20)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.porPeriodo(
				actor, ORG_ID, null, AHORA, PageRequest.of(0, 20)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Un rango mayor al tope es 400: sin tope, from=1970 es un scan del tenant entero")
	void un_rango_demasiado_amplio_es_400() {
		concedidoCon(PermissionScope.ORGANIZACION);
		Instant muyAtras = AHORA.minus(AuditQueryService.RANGO_MAXIMO.plusDays(1));

		assertThatThrownBy(() -> service.porPeriodo(
				actor, ORG_ID, muyAtras, AHORA, PageRequest.of(0, 20)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("dias");
	}

	@Test
	@DisplayName("El rango exacto del tope pasa: el limite es inclusivo")
	void el_rango_del_tope_pasa() {
		concedidoCon(PermissionScope.ORGANIZACION);
		devuelveVacio();

		service.porPeriodo(actor, ORG_ID, AHORA.minus(AuditQueryService.RANGO_MAXIMO), AHORA,
				PageRequest.of(0, 20));

		assertThat(filtroDePeriodo()).isNotNull();
	}

	@Test
	@DisplayName("Una pagina demasiado grande se recorta, no se rechaza")
	void una_pagina_grande_se_recorta() {
		// Un cliente que pide 10.000 filas no esta atacando, esta mal configurado: devolverle
		// 100 le sirve mas que un 400. El tope si es innegociable.
		concedidoCon(PermissionScope.ORGANIZACION);
		devuelveVacio();

		service.porPeriodo(actor, ORG_ID, AHORA.minus(1, ChronoUnit.DAYS), AHORA,
				PageRequest.of(3, 10_000));

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(auditQuery).porPeriodo(any(), captor.capture());
		assertThat(captor.getValue().getPageSize())
				.isEqualTo(AuditQueryService.TAMANIO_MAXIMO_DE_PAGINA);
		// Y la pagina pedida se conserva: recortar el tamaño no puede mover al usuario de lugar.
		assertThat(captor.getValue().getPageNumber()).isEqualTo(3);
	}

	@Test
	@DisplayName("Una pagina dentro del tope pasa intacta")
	void una_pagina_normal_pasa_intacta() {
		concedidoCon(PermissionScope.ORGANIZACION);
		devuelveVacio();

		service.porPeriodo(actor, ORG_ID, AHORA.minus(1, ChronoUnit.DAYS), AHORA,
				PageRequest.of(1, 25));

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(auditQuery).porPeriodo(any(), captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(25);
	}

	@Test
	@DisplayName("Una consulta sin paginar recibe la pagina maxima, no todo")
	void sin_paginar_se_pagina_igual() {
		concedidoCon(PermissionScope.ORGANIZACION);
		devuelveVacio();

		service.porEntidad(actor, ORG_ID, "Membership", 60L, Pageable.unpaged());

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(auditQuery).porEntidad(any(), captor.capture());
		assertThat(captor.getValue().isPaged()).isTrue();
		assertThat(captor.getValue().getPageSize())
				.isEqualTo(AuditQueryService.TAMANIO_MAXIMO_DE_PAGINA);
	}

	@Test
	@DisplayName("Los topes tienen valores explicitos y en un solo lugar")
	void los_topes_estan_declarados() {
		// Son defaults provisorios: la decision D-8 no los fijo. Estan con nombre para que
		// fijarlos sea cambiar dos constantes y no auditar la aplicacion entera.
		assertThat(AuditQueryService.RANGO_MAXIMO).isEqualTo(Duration.ofDays(90));
		assertThat(AuditQueryService.TAMANIO_MAXIMO_DE_PAGINA).isEqualTo(100);
	}

	// =================================================================================
	// AKINE-07.07 — la justificacion clinica exige auditoria:read-clinica
	// =================================================================================

	private AuditEventSummary fila(String eventType, String motivo) {
		return new AuditEventSummary(
				1L, ORG_ID, CONSULTORIO_ID, ACCOUNT_ID, eventType, "HistoriaClinica", 9L,
				null, null, java.util.Map.of("viaDeAcceso", "RELACION_ASISTENCIAL"),
				motivo, "corr-1", AHORA);
	}

	private void devuelve(AuditEventSummary... filas) {
		given(auditQuery.porPeriodo(any(), any()))
				.willReturn(new PageImpl<>(List.of(filas)));
	}

	private void clinicoConcedido(boolean concedido) {
		given(permissionEvaluator.evaluate(any())).willReturn(concedido
				? PermissionDecision.concedida(PermissionScope.ORGANIZACION.name(), false)
				: PermissionDecision.rechazada(com.akine.organization.spi.DenialKind.NO_PERMISSION));
	}

	@Test
	@DisplayName("Sin auditoria:read-clinica, el motivo del evento clinico se tapa y los detalles se vacian")
	void sin_permiso_clinico_se_redacta() {
		// El motivo es texto libre que escribe un profesional: "el paciente llamo por el
		// resultado del estudio de rodilla" es contenido clinico. Un ORG_ADMIN tiene
		// auditoria:read por asignacion base y NO tiene hc:read; sin esta redaccion leia la
		// justificacion de cada acceso a la historia de cada paciente de su organizacion.
		concedidoCon(PermissionScope.ORGANIZACION);
		clinicoConcedido(false);
		devuelve(fila("HISTORIA_CLINICA_ACCESSED", "llamo por el estudio de rodilla"));

		AuditEventSummary leida = service
				.porPeriodo(actor, ORG_ID, AHORA.minus(1, ChronoUnit.DAYS), AHORA,
						PageRequest.of(0, 10))
				.getContent()
				.getFirst();

		assertThat(leida.reason()).isEqualTo(AuditQueryService.MOTIVO_REDACTADO);
		assertThat(leida.details()).isEmpty();
		// El rastro de acceso NO se tapa: quien, cuando y sobre que es lo que la matriz §6 le
		// concede a auditoria:read, y es lo que sirve para detectar un acceso indebido.
		assertThat(leida.eventType()).isEqualTo("HISTORIA_CLINICA_ACCESSED");
		assertThat(leida.actorAccountId()).isEqualTo(ACCOUNT_ID);
		assertThat(leida.entityId()).isEqualTo(9L);
	}

	@Test
	@DisplayName("Con auditoria:read-clinica, la fila clinica llega entera")
	void con_permiso_clinico_no_se_redacta() {
		concedidoCon(PermissionScope.ORGANIZACION);
		clinicoConcedido(true);
		devuelve(fila("HISTORIA_CLINICA_ACCESSED", "llamo por el estudio de rodilla"));

		AuditEventSummary leida = service
				.porPeriodo(actor, ORG_ID, AHORA.minus(1, ChronoUnit.DAYS), AHORA,
						PageRequest.of(0, 10))
				.getContent()
				.getFirst();

		assertThat(leida.reason()).isEqualTo("llamo por el estudio de rodilla");
		assertThat(leida.details()).containsEntry("viaDeAcceso", "RELACION_ASISTENCIAL");
	}

	@Test
	@DisplayName("Un evento que no es clinico llega entero aunque falte el permiso clinico")
	void lo_no_clinico_no_se_toca() {
		// Redactar de mas volveria inutil la pantalla de auditoria para lo que si le compete a
		// un administrador: memberships, suscripcion, colaboradores.
		concedidoCon(PermissionScope.ORGANIZACION);
		clinicoConcedido(false);
		devuelve(fila("MEMBERSHIP_REVOKED", "dejo el equipo"));

		AuditEventSummary leida = service
				.porPeriodo(actor, ORG_ID, AHORA.minus(1, ChronoUnit.DAYS), AHORA,
						PageRequest.of(0, 10))
				.getContent()
				.getFirst();

		assertThat(leida.reason()).isEqualTo("dejo el equipo");
		assertThat(leida.details()).isNotEmpty();
	}
}
