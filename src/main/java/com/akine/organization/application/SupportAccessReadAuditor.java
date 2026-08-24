package com.akine.organization.application;

import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra {@code SUPPORT_ACCESS_USED} de las <b>lecturas</b> en una transaccion propia.
 *
 * <h2>El bug que cierra</h2>
 *
 * <p>Las lecturas del modulo corren con {@code @Transactional(readOnly = true)}. En una
 * transaccion de solo lectura Hibernate deja el {@code FlushMode} en {@code MANUAL}, asi que un
 * {@code save} hecho ahi adentro nunca llega a la base: no falla, no loguea, simplemente no
 * existe la fila. Y {@link AuditTrail#record(AuditEntry)} es {@code MANDATORY}, o sea que se
 * une justo a esa transaccion. El codigo decia que auditaba, el log decia que auditaba, y
 * {@code audit_event} quedaba vacia — el mismo modo de falla que se llevo puesta la revocacion
 * de familia de refresh en 01.02.
 *
 * <h2>Por que transaccion propia y no sacarle el {@code readOnly} a las lecturas</h2>
 *
 * <p>Sacar el {@code readOnly} —lo que hizo {@code ConsultorioService.find} en 02.01— arregla
 * el caso feliz y <b>deja abierto el que importa</b>: {@code find} y {@code grants} evaluan el
 * permiso primero y recien despues cargan la entidad, que puede no existir o ser de otro
 * tenant. Ahi lanzan y la transaccion hace rollback, arrastrando la fila de auditoria que se
 * acababa de escribir. Un administrador de plataforma con acceso de soporte podria recorrer
 * ids ajenos sin dejar un solo rastro, que es exactamente el escenario que el acceso de soporte
 * existe para hacer trazable.
 *
 * <p>Ademas {@code list} es un listado paginado: sin {@code readOnly} Hibernate mantiene el
 * dirty checking sobre las hasta 100 entidades de la pagina y hace flush contra la base al
 * cerrar, y la conexion se pide de lectura-escritura. Es costo puro en el endpoint mas
 * caliente de la pantalla de colaboradores.
 *
 * <p>El motivo de la regla general (T-2: "la auditoria se escribe DENTRO de la transaccion del
 * negocio") es que ninguna <b>mutacion</b> quede confirmada sin rastro. En una lectura no hay
 * mutacion que confirmar, asi que ese motivo no aplica — es el mismo razonamiento y el mismo
 * alcance acotado de {@link PermissionDenialAuditor} y {@link PlanLimitRejectionAuditor}. Esto
 * NO habilita {@code REQUIRES_NEW} en ningun otro lugar del modulo.
 *
 * <p>Esta en su propia clase porque {@code @Transactional} se aplica por proxy: si el metodo
 * viviera en el servicio que lo llama, la llamada interna lo saltearia y la anotacion no haria
 * absolutamente nada.
 */
@Service
public class SupportAccessReadAuditor {

	private final AuditTrail auditTrail;

	public SupportAccessReadAuditor(AuditTrail auditTrail) {
		this.auditTrail = auditTrail;
	}

	/**
	 * Escribe la entrada y la confirma, pase lo que pase con la lectura que la origino.
	 *
	 * <p>Si la lectura termina en 404 la fila queda igual, y esta bien que quede: el acceso de
	 * soporte se ejercio.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(AuditEntry entry) {
		auditTrail.record(entry);
	}
}
