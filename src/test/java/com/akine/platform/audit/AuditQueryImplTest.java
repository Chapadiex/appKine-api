package com.akine.platform.audit;

import com.akine.platform.domain.AuditEvent;
import com.akine.platform.infrastructure.AuditEventRepository;
import com.akine.platform.infrastructure.audit.AuditQueryImpl;
import com.akine.platform.spi.audit.AuditEventFilter;
import com.akine.platform.spi.audit.AuditEventSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * La lectura de la auditoria: el adaptador que traduce la entity al record del {@code spi}.
 *
 * <p>Lo que importa probar aca es que <b>el filtro llega a la consulta correcta</b> —cada RF de
 * M24 tiene su indice y usar la consulta equivocada convierte un seek en un scan del tenant
 * entero— y que un {@code details} ilegible no rompe la pagina completa.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditQueryImplTest {

	private static final long ORG_ID = 10L;
	private static final long CONSULTORIO_ID = 20L;
	private static final Instant AHORA = Instant.now();
	private static final Pageable PAGINA = PageRequest.of(0, 20);

	@Mock
	private AuditEventRepository auditEventRepository;

	@InjectMocks
	private AuditQueryImpl auditQuery;

	/**
	 * Un evento con id asignado.
	 *
	 * <p>La entity no expone setter de {@code id} —lo asigna la base—, asi que en un test sin
	 * base hay que ponerlo por reflexion. Agregarle un setter solo para los tests es como se
	 * abre la puerta a que produccion lo use.
	 */
	private static AuditEvent evento(String details) {
		AuditEvent evento = new AuditEvent(ORG_ID, CONSULTORIO_ID, 30L, "MEMBERSHIP_REVOKED",
				"Membership", 60L, "ACTIVA", "REVOCADA", details, "motivo declarado", "trace-1", AHORA);
		org.springframework.test.util.ReflectionTestUtils.setField(evento, "id", 1L);
		return evento;
	}

	private static Page<AuditEvent> pagina(AuditEvent... eventos) {
		return new PageImpl<>(List.of(eventos));
	}

	@Test
	@DisplayName("La consulta por entidad usa el filtro de entidad, no otro")
	void por_entidad() {
		given(auditEventRepository
				.findAllByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
						eq(ORG_ID), eq("Membership"), eq(60L), any()))
				.willReturn(pagina(evento(null)));

		Page<AuditEventSummary> resultado = auditQuery.porEntidad(
				new AuditEventFilter(ORG_ID, null, "Membership", 60L, null, null, null), PAGINA);

		assertThat(resultado.getContent()).singleElement().satisfies(e -> {
			assertThat(e.eventType()).isEqualTo("MEMBERSHIP_REVOKED");
			assertThat(e.entityType()).isEqualTo("Membership");
			assertThat(e.entityId()).isEqualTo(60L);
			assertThat(e.previousState()).isEqualTo("ACTIVA");
			assertThat(e.newState()).isEqualTo("REVOCADA");
			assertThat(e.reason()).isEqualTo("motivo declarado");
			assertThat(e.correlationId()).isEqualTo("trace-1");
			assertThat(e.organizationId()).isEqualTo(ORG_ID);
			assertThat(e.consultorioId()).isEqualTo(CONSULTORIO_ID);
			assertThat(e.actorAccountId()).isEqualTo(30L);
			assertThat(e.occurredAt()).isEqualTo(AHORA);
		});
	}

	@Test
	@DisplayName("La consulta por actor usa el filtro de actor")
	void por_actor() {
		given(auditEventRepository.findAllByOrganizationIdAndActorAccountIdOrderByOccurredAtDesc(
				eq(ORG_ID), eq(30L), any())).willReturn(pagina(evento(null)));

		assertThat(auditQuery.porActor(
				new AuditEventFilter(ORG_ID, null, null, null, 30L, null, null), PAGINA)
				.getContent()).hasSize(1);
	}

	@Test
	@DisplayName("Sin sede, el periodo usa el indice de organizacion y tiempo")
	void por_periodo_sin_sede() {
		given(auditEventRepository.findAllByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(
				eq(ORG_ID), any(), any(), any())).willReturn(pagina(evento(null)));

		auditQuery.porPeriodo(new AuditEventFilter(ORG_ID, null, null, null, null,
				AHORA.minus(1, ChronoUnit.DAYS), AHORA), PAGINA);

		verify(auditEventRepository)
				.findAllByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(
						eq(ORG_ID), any(), any(), any());
		verify(auditEventRepository, never())
				.findAllByOrganizationIdAndConsultorioIdAndOccurredAtBetweenOrderByOccurredAtDesc(
						any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("Con sede, el periodo usa el indice que la incluye: es el alcance de CONSULTORIO_ADMIN")
	void por_periodo_con_sede() {
		given(auditEventRepository
				.findAllByOrganizationIdAndConsultorioIdAndOccurredAtBetweenOrderByOccurredAtDesc(
						eq(ORG_ID), eq(CONSULTORIO_ID), any(), any(), any()))
				.willReturn(pagina(evento(null)));

		auditQuery.porPeriodo(new AuditEventFilter(ORG_ID, CONSULTORIO_ID, null, null, null,
				AHORA.minus(1, ChronoUnit.DAYS), AHORA), PAGINA);

		verify(auditEventRepository, never())
				.findAllByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(
						any(), any(), any(), any());
	}

	@Test
	@DisplayName("Los detalles JSON se devuelven como mapa")
	void los_detalles_se_deserializan() {
		given(auditEventRepository
				.findAllByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
						any(), any(), any(), any()))
				.willReturn(pagina(evento("{\"permissionCode\":\"colaborador:manage\"}")));

		assertThat(auditQuery.porEntidad(
				new AuditEventFilter(ORG_ID, null, "Membership", 60L, null, null, null), PAGINA)
				.getContent().get(0).details())
				.containsEntry("permissionCode", "colaborador:manage");
	}

	@Test
	@DisplayName("Un details ilegible devuelve un mapa vacio y NO rompe la pagina entera")
	void un_details_ilegible_no_rompe_la_pagina() {
		// Una fila vieja con un JSON que ya no parsea sigue diciendo QUE paso, QUIEN lo hizo y
		// SOBRE QUE, que es el 90 % de para lo que se consulta una auditoria. Hacer fallar la
		// consulta completa por el detalle de una fila seria perder todo lo demas.
		given(auditEventRepository
				.findAllByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
						any(), any(), any(), any()))
				.willReturn(pagina(evento("{esto no es json"), evento("   "), evento(null)));

		List<AuditEventSummary> resultado = auditQuery.porEntidad(
				new AuditEventFilter(ORG_ID, null, "Membership", 60L, null, null, null), PAGINA)
				.getContent();

		assertThat(resultado).hasSize(3);
		assertThat(resultado).allSatisfy(e -> {
			assertThat(e.details()).isEmpty();
			assertThat(e.eventType()).isEqualTo("MEMBERSHIP_REVOKED");
		});
	}

	@Test
	@DisplayName("El record del spi copia los detalles: el consumidor no puede alterarlos")
	void el_resumen_es_inmutable() {
		AuditEventSummary resumen = new AuditEventSummary(1L, ORG_ID, null, null, "X", "Y",
				null, null, null, Map.of("k", "v"), null, null, AHORA);

		assertThat(resumen.details()).containsEntry("k", "v");
		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> resumen.details().put("otra", "cosa"))
				.isInstanceOf(UnsupportedOperationException.class);

		assertThat(new AuditEventSummary(1L, ORG_ID, null, null, "X", "Y",
				null, null, null, null, null, null, AHORA).details()).isEmpty();
	}
}
