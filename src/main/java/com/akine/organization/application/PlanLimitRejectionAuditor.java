package com.akine.organization.application;

import com.akine.organization.spi.LimitCode;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registra los rechazos por limite de plan en una transaccion propia.
 *
 * <h2>Por que aca si se usa {@code REQUIRES_NEW}</h2>
 *
 * <p>La regla general del proyecto (T-2) es que la auditoria se escribe DENTRO de la
 * transaccion de negocio: si el registro falla, la operacion no se confirma. Esa regla existe
 * para que no pueda quedar una mutacion hecha sin rastro.
 *
 * <p>Un rechazo por limite es el caso opuesto y la regla, aplicada literalmente, lo pierde: la
 * transaccion del alta termina en excepcion y hace rollback, arrastrando la fila de auditoria
 * que se acaba de insertar. El evento {@code PLAN_LIMIT_REJECTED} que exige el catalogo
 * quedaria escrito en el codigo y ausente en la base — lo peor de los dos mundos, porque los
 * tests unitarios verian el {@code record(...)} y nadie notaria que en produccion no hay nada.
 *
 * <p>Como aca no hay ninguna mutacion que confirmar —el alta se rechaza siempre—, el motivo de
 * T-2 no aplica y la transaccion separada es lo correcto: el rechazo sobrevive al rollback del
 * alta. Esto NO habilita {@code REQUIRES_NEW} en ningun otro lugar del modulo, y en particular
 * queda prohibido en el onboarding compuesto (B-5), donde partir la transaccion romperia la
 * atomicidad exigida por ADR-0008.
 *
 * <p>Esta separado en su propia clase porque {@code @Transactional} se aplica por proxy: si el
 * metodo viviera en {@code PlanGateService}, la llamada interna lo saltearia y la anotacion no
 * haria absolutamente nada.
 */
@Service
public class PlanLimitRejectionAuditor {

	private final AuditTrail auditTrail;

	public PlanLimitRejectionAuditor(AuditTrail auditTrail) {
		this.auditTrail = auditTrail;
	}

	/**
	 * Deja constancia de que un alta se rechazo por limite de plan.
	 *
	 * <p>Los detalles son numeros y codigos de catalogo: que limite, cual era el tope y cuanto
	 * se estaba usando. Nada del recurso que se intentaba crear, que podria ser un dato
	 * sensible.
	 *
	 * <p>{@code actorAccountId} va en {@code null}: el gate se invoca desde el modulo
	 * consumidor y no recibe al actor, y hacerselo pasar obligaria a que cada llamador lo
	 * propague —con un olvido garantizado en el primero que se sume—. Quien hizo el intento se
	 * reconstruye por {@code correlationId} contra el log del request, que si lo tiene.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordRejection(
			long organizationId, LimitCode limit, Integer limitValue, long currentUsage) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("limitCode", limit.name());
		details.put("limitValue", String.valueOf(limitValue));
		details.put("currentUsage", String.valueOf(currentUsage));

		auditTrail.record(new AuditEntry(
				organizationId,
				null,
				null,
				AuditEvents.PLAN_LIMIT_REJECTED,
				AuditEvents.ENTITY_SUBSCRIPTION,
				null,
				null,
				null,
				details,
				null,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
