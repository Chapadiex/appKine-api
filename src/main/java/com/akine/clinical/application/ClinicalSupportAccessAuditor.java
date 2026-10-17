package com.akine.clinical.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditoriaAparte;
import org.springframework.stereotype.Service;

/**
 * Registra el uso de acceso de soporte sobre datos clinicos, en su propia transaccion.
 *
 * <p>La transaccion propia ({@link AuditoriaAparte}) no es un detalle: si la operacion de negocio termina en rollback —un
 * choque de version, una regla que salta despues— la fila que dice "un administrador de plataforma
 * miro esta historia clinica" tiene que quedar igual. El intento existio, y en datos clinicos el
 * intento es justamente lo que hay que poder revisar.
 *
 * <p>Es una clase propia y no la de {@code person} por el motivo de siempre: modulos distintos.
 */
@Service
public class ClinicalSupportAccessAuditor {

	private final AuditoriaAparte auditoria;

	public ClinicalSupportAccessAuditor(AuditoriaAparte auditoria) {
		this.auditoria = auditoria;
	}

	public void record(AuditEntry entry) {
		auditoria.registrarAccesoDeSoporte(entry);
	}

	/**
	 * Registra un acceso clinico RECHAZADO, aparte y cuando la transaccion del pedido ya termino.
	 *
	 * <p>Vive aca porque es el mismo problema que el acceso de soporte: un hecho que tiene que
	 * quedar aunque la operacion revierta. Al mejor esfuerzo ({@link AuditoriaAparte#registrar}):
	 * si la escritura falla, se loguea y el pedido sale con su 403 igual.
	 */
	public void registrarRechazo(AuditEntry entry) {
		auditoria.registrar(entry);
	}
}
