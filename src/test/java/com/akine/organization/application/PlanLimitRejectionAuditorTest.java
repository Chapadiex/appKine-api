package com.akine.organization.application;

import com.akine.organization.spi.LimitCode;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static com.akine.organization.application.Fixtures.ORG_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registro de los rechazos por limite de plan (RF-M01-004).
 *
 * <p>Esta clase existe separada de {@code PlanGateService} por una razon estructural, y
 * {@link #el_registro_corre_en_una_transaccion_propia()} la fija: si el metodo volviera a
 * vivir en el gate, la llamada interna saltearia el proxy y {@code REQUIRES_NEW} no haria
 * nada. El rechazo quedaria escrito en el codigo y ausente en la base tras el rollback del
 * alta, que es el peor de los dos mundos.
 */
@ExtendWith(MockitoExtension.class)
class PlanLimitRejectionAuditorTest {

	@Mock
	private AuditTrail auditTrail;

	@InjectMocks
	private PlanLimitRejectionAuditor auditor;

	private AuditEntry registrar(Integer tope, long uso) {
		auditor.recordRejection(ORG_ID, LimitCode.MAX_CONSULTORIOS, tope, uso);
		ArgumentCaptor<AuditEntry> entrada = ArgumentCaptor.forClass(AuditEntry.class);
		org.mockito.Mockito.verify(auditTrail).record(entrada.capture());
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

	@Test
	@DisplayName("El registro corre en una transaccion propia y sobrevive al rollback del alta")
	void el_registro_corre_en_una_transaccion_propia() throws NoSuchMethodException {
		// REQUIRES_NEW aca es la excepcion documentada a T-2, no una licencia general: sin
		// ella el rollback del alta rechazada se llevaria puesta la fila de auditoria.
		Method metodo = PlanLimitRejectionAuditor.class.getMethod(
				"recordRejection", long.class, LimitCode.class, Integer.class, long.class);

		assertThat(metodo.getAnnotation(Transactional.class)).isNotNull();
		assertThat(metodo.getAnnotation(Transactional.class).propagation())
				.isEqualTo(Propagation.REQUIRES_NEW);
	}
}
