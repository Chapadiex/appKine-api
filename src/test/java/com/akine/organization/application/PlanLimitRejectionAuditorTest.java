package com.akine.organization.application;

import com.akine.organization.spi.LimitCode;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditoriaAparte;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registro de los rechazos por limite de plan (RF-M01-004).
 *
 * <p>El rechazo va por {@link AuditoriaAparte} y no por el {@code AuditTrail} de la transaccion:
 * el rollback del alta rechazada se lo llevaria puesto. Que efectivamente sobreviva lo prueba
 * {@code PoolDeConexionesIT} con el rechazo por permiso, que usa el mismo mecanismo.
 */
@ExtendWith(MockitoExtension.class)
class PlanLimitRejectionAuditorTest {

	@Mock
	private AuditoriaAparte auditoria;

	@InjectMocks
	private PlanLimitRejectionAuditor auditor;

	private AuditEntry registrar(Integer tope, long uso) {
		auditor.recordRejection(ORG_ID, LimitCode.MAX_CONSULTORIOS, tope, uso);
		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		org.mockito.Mockito.verify(auditoria).registrar(entrada.capture());
		return entrada.getValue();
	}

	@Test
	@DisplayName("El rechazo por limite queda auditado como PLAN_LIMIT_REJECTED")
	void el_rechazo_queda_auditado() {
		AuditEntry evento = registrar(5, 5L);

		assertThat(evento.eventType()).isEqualTo(AuditEvents.PLAN_LIMIT_REJECTED);
		assertThat(evento.entityType()).isEqualTo(AuditEvents.ENTITY_SUBSCRIPTION);
		assertThat(evento.organizationId()).isEqualTo(ORG_ID);
		assertThat(evento.entityId()).isNull();
		assertThat(evento.details())
				.containsEntry("limitCode", "MAX_CONSULTORIOS")
				.containsEntry("limitValue", "5")
				.containsEntry("currentUsage", "5");
		assertThat(evento.occurredAt()).isNotNull();
	}

	@Test
	@DisplayName("El actor va nulo: el gate lo invoca el modulo consumidor y no lo recibe")
	void el_actor_va_nulo() {
		AuditEntry evento = registrar(5, 6L);

		assertThat(evento.actorAccountId()).isNull();
		assertThat(evento.consultorioId()).isNull();
		// Quien hizo el intento se reconstruye por correlationId contra el log del request.
		assertThat(evento.details()).hasSize(3);
	}

	@Test
	@DisplayName("Los detalles no llevan nada del recurso que se intentaba crear")
	void los_detalles_no_llevan_el_recurso() {
		AuditEntry evento = registrar(null, 9L);

		assertThat(evento.details()).containsOnlyKeys("limitCode", "limitValue", "currentUsage");
		assertThat(evento.details()).containsEntry("limitValue", "null");
	}
}
