package com.akine.clinical.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra el uso de acceso de soporte sobre datos clinicos, en su propia transaccion.
 *
 * <p>{@code REQUIRES_NEW} no es un detalle: si la operacion de negocio termina en rollback —un
 * choque de version, una regla que salta despues— la fila que dice "un administrador de plataforma
 * miro esta historia clinica" tiene que quedar igual. El intento existio, y en datos clinicos el
 * intento es justamente lo que hay que poder revisar.
 *
 * <p>Es una clase propia y no la de {@code person} por el motivo de siempre: modulos distintos.
 */
@Service
public class ClinicalSupportAccessAuditor {

	private final AuditTrail auditTrail;

	public ClinicalSupportAccessAuditor(AuditTrail auditTrail) {
		this.auditTrail = auditTrail;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(AuditEntry entry) {
		auditTrail.record(entry);
	}
}
