package com.akine.reporting.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditoriaAparte;
import org.springframework.stereotype.Service;

/**
 * Escribe {@code SUPPORT_ACCESS_USED} en una transaccion PROPIA (AKINE-G-1).
 *
 * <p>Un {@code PLATFORM_ADMIN} lee reportes de un tenant solo con acceso de soporte vigente
 * —{@code reporte:read} es SOPORTE en su columna, matriz §9.7— y la matriz §7 exige que CADA
 * operacion amparada quede auditada. Transaccion propia ({@link AuditoriaAparte}) por los dos motivos que
 * {@code person.application.PersonSupportAccessAuditor} ya documenta: el catalogo corre en una
 * transaccion de solo lectura, donde la fila nunca llegaria a la base, y un periodo invalido
 * rechazado despues del gate haria rollback de la unica constancia del intento.
 *
 * <p>No habilita la auditoria aparte en ningun otro lugar del modulo: la auditoria de lectura
 * clinica del reporte va dentro de la transaccion del negocio, a proposito.
 */
@Service
public class ReportingSupportAccessAuditor {

	private final AuditoriaAparte auditoria;

	public ReportingSupportAccessAuditor(AuditoriaAparte auditoria) {
		this.auditoria = auditoria;
	}

	public void record(AuditEntry entry) {
		auditoria.registrarAccesoDeSoporte(entry);
	}
}
