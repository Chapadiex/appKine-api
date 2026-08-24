package com.akine.organization.application;

import com.akine.organization.spi.DenialKind;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registra los rechazos por PERMISO en una transaccion propia.
 *
 * <h2>Por que {@code REQUIRES_NEW}, si la regla del proyecto dice lo contrario</h2>
 *
 * <p>La regla general (T-2) es que la auditoria se escribe DENTRO de la transaccion de negocio:
 * si el registro falla, la operacion no se confirma. Existe para que no pueda quedar una
 * mutacion hecha sin rastro.
 *
 * <p>Un rechazo es el caso opuesto y la regla, aplicada literalmente, lo pierde: la transaccion
 * termina en excepcion y hace rollback, arrastrando la fila que se acaba de insertar. El evento
 * quedaria escrito en el codigo y ausente en la base — y los tests unitarios verian el
 * {@code record(...)} sin que nadie notara que en produccion no hay nada. Es exactamente el bug
 * que se perdio una revocacion de familia de refresh en 01.02: el log decia que la habia hecho
 * y la base quedaba intacta.
 *
 * <p>Como aca no hay ninguna mutacion que confirmar —la operacion se rechaza siempre— el motivo
 * de T-2 no aplica. Mismo razonamiento, mismo patron y mismo alcance acotado que
 * {@link PlanLimitRejectionAuditor}: esto NO habilita {@code REQUIRES_NEW} en ningun otro lugar
 * del modulo.
 *
 * <h2>Que NO se registra aca</h2>
 *
 * <p>Los rechazos por ALCANCE —los que terminan en 404— no se auditan en la tabla. Registrarlos
 * construiria dentro de {@code audit_event} el mismo padron de existencia de tenants ajenos que
 * el 404 uniforme existe para no entregar; es el mismo razonamiento por el que
 * {@code IdentityAuditEvents.ACTIVACION_REENVIADA} no audita el pedido sobre un email
 * inexistente. Esos rechazos van al log estructurado, correlacionados por {@code traceId}.
 *
 * <p>Esta en su propia clase porque {@code @Transactional} se aplica por proxy: si el metodo
 * viviera en {@code PermissionEvaluatorService}, la llamada interna lo saltearia y la anotacion
 * no haria absolutamente nada.
 */
@Service
public class PermissionDenialAuditor {

	private final AuditTrail auditTrail;

	public PermissionDenialAuditor(AuditTrail auditTrail) {
		this.auditTrail = auditTrail;
	}

	/**
	 * Deja constancia de que una operacion se rechazo por falta de permiso.
	 *
	 * <p>Los detalles son codigos de catalogo: que permiso falto y de que clase fue el rechazo.
	 * Nada del recurso que se intentaba tocar, que podria ser un dato sensible.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordDenial(PermissionQuery query, DenialKind denial) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("permissionCode", query.permissionCode());
		details.put("denialKind", denial.name());
		if (query.consultorioId() != null) {
			details.put("consultorioId", String.valueOf(query.consultorioId()));
		}

		auditTrail.record(new AuditEntry(
				query.organizationId(),
				query.consultorioId(),
				query.accountId(),
				AuditEvents.PERMISSION_DENIED,
				AuditEvents.ENTITY_PERMISSION,
				query.targetAccountId(),
				null,
				null,
				details,
				null,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
