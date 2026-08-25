package com.akine.resource.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe {@code SUPPORT_ACCESS_USED} en una transaccion PROPIA.
 *
 * <h2>Por que no alcanza con llamar al {@code AuditTrail} desde el servicio</h2>
 *
 * <p>Por dos motivos independientes, y hacen falta los dos:
 *
 * <ol>
 *   <li><b>Las lecturas son {@code readOnly = true}.</b> En una transaccion de solo lectura
 *       Hibernate deja el flush en modo MANUAL: la fila se guarda en la sesion y nunca llega a
 *       la base. El evento se perderia sin que nada fallara, que es la peor forma de
 *       perderlo.</li>
 *   <li><b>Una excepcion de negocio hace rollback de lo escrito antes de lanzarla.</b> El caso
 *       que importa es justamente ese: un administrador de plataforma pidiendo un id ajeno o
 *       inexistente. La operacion termina en 404 y la transaccion revierte — llevandose la
 *       unica fila que dejaba constancia de que ese id se probo. El acceso de soporte existe
 *       para hacer trazable exactamente eso.</li>
 * </ol>
 *
 * <p>{@code REQUIRES_NEW} resuelve los dos: la fila se confirma en su propia transaccion, viva
 * o muera la del negocio.
 *
 * <p><b>Esto NO habilita {@code REQUIRES_NEW} en ningun otro lugar de este modulo.</b> Las
 * mutaciones auditan dentro de su propia transaccion, y tiene que ser asi: una mutacion
 * confirmada cuya auditoria se revirtio por separado es peor que ninguna de las dos. Es la
 * misma regla, y la misma excepcion acotada, que {@code organization.SupportAccessReadAuditor}.
 */
@Service
public class SupportAccessAuditor {

	private final AuditTrail auditTrail;

	public SupportAccessAuditor(AuditTrail auditTrail) {
		this.auditTrail = auditTrail;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(AuditEntry entry) {
		auditTrail.record(entry);
	}
}
