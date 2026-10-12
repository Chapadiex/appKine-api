package com.akine.platform.infrastructure.audit;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.audit.AuditoriaAparte;
import com.akine.platform.spi.transaccion.AlTerminarLaTransaccion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementacion de {@link AuditoriaAparte}: difiere la escritura hasta que la transaccion del
 * pedido haya terminado y la hace en una propia.
 *
 * <p>La plantilla es {@code REQUIRES_NEW} y en la aplicacion nunca anida: cuando corre, la
 * transaccion del pedido ya se limpio y no hay ninguna abierta. El {@code REQUIRES_NEW} solo
 * importa sin el transaction manager de la aplicacion —un slice de test—, donde
 * {@link AlTerminarLaTransaccion} corre en el acto: ahi conserva la conducta de antes, que la fila
 * sobreviva al rollback de afuera.
 */
@Component
public class AuditoriaAparteImpl implements AuditoriaAparte {

	private final AuditTrail auditTrail;
	private final TransactionTemplate transaccionPropia;

	public AuditoriaAparteImpl(AuditTrail auditTrail, PlatformTransactionManager transactionManager) {
		this.auditTrail = auditTrail;
		this.transaccionPropia = new TransactionTemplate(transactionManager);
		this.transaccionPropia.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public void registrar(AuditEntry entry) {
		AlTerminarLaTransaccion.ejecutar(
				() -> transaccionPropia.executeWithoutResult(estado -> auditTrail.record(entry)));
	}
}
