package com.akine.platform.infrastructure.audit;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.audit.AuditoriaAparte;
import com.akine.platform.spi.transaccion.AlTerminarLaTransaccion;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementacion de {@link AuditoriaAparte}.
 *
 * <p>La plantilla es {@code REQUIRES_NEW} y en la aplicacion nunca anida: cuando corre, la
 * transaccion del pedido ya se limpio y no hay ninguna abierta. El {@code REQUIRES_NEW} solo
 * importa sin el transaction manager de la aplicacion —un slice de test—, donde
 * {@link AlTerminarLaTransaccion} corre en el acto: ahi conserva la conducta de antes, que la fila
 * sobreviva al rollback de afuera y que su falla haga fallar la operacion.
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
		AlTerminarLaTransaccion.ejecutar(() -> escribirAparte(entry));
	}

	@Override
	public void registrarAccesoDeSoporte(AuditEntry entry) {
		boolean enEscritura = TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
		if (enEscritura && AlTerminarLaTransaccion.siguiendoUnaTransaccion()) {
			// Regla 01.01: en la transaccion de negocio. Si falla, la mutacion no sale.
			auditTrail.record(entry);
			// Y si la transaccion revierte por otra causa, el intento queda igual.
			AlTerminarLaTransaccion.ejecutarSiRevierte(() -> escribirAparte(entry));
			return;
		}
		AlTerminarLaTransaccion.ejecutarObligatorio(() -> escribirAparte(entry));
	}

	private void escribirAparte(AuditEntry entry) {
		transaccionPropia.executeWithoutResult(estado -> auditTrail.record(entry));
	}
}
