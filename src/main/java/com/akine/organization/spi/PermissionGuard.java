package com.akine.organization.spi;

/**
 * La version que corta el flujo de {@link PermissionEvaluator}: exige el permiso o lanza.
 *
 * <h2>Por que esto no viola la regla de modulos</h2>
 *
 * <p>{@code identity} llama a {@link #requirePermission} y <b>no nombra nunca el tipo de la
 * excepcion</b>: no la captura, la deja propagar. ArchUnit prohibe IMPORTAR
 * {@code organization.domain}, y una excepcion no capturada no genera un import. El advice que
 * la traduce es {@code @RestControllerAdvice}, es decir global: atrapa la excepcion aunque el
 * controller sea de {@code identity}.
 *
 * <p>Es el mismo mecanismo, ya probado, de {@code ContextNotAuthorizedException}: la lanza
 * {@code AccountContextDirectory.selectContext} y la consume el advice de {@code organization}
 * desde un endpoint de {@code identity}.
 *
 * <p>Si alguien la captura, aparece el import y ArchUnit falla. Eso esta bien: es la red
 * funcionando.
 */
public interface PermissionGuard {

	/**
	 * Exige el permiso descripto por la consulta.
	 *
	 * <p>El rechazo se traduce a UN codigo HTTP en UN solo lugar, segun el
	 * {@link DenialKind}: fuera de alcance &rarr; 404, sin contexto &rarr; 403
	 * {@code missing-tenant-context}, sin permiso &rarr; 403 {@code forbidden}.
	 *
	 * <p>El rechazo por permiso (403) deja ademas una fila {@code PERMISSION_DENIED} en la
	 * auditoria. El rechazo por alcance (404) <b>no</b>: registrarlo construiria dentro de
	 * {@code audit_event} el mismo padron de existencia de tenants ajenos que el 404 uniforme
	 * existe para no entregar. Esos van al log estructurado, correlacionados por {@code traceId}.
	 *
	 * @return la decision concedida, con el alcance y si se apoyo en un acceso de soporte —lo
	 *         segundo obliga al llamador a registrar {@code SUPPORT_ACCESS_USED}
	 * @throws com.akine.organization.domain.exception.PermissionDeniedException si el actor no
	 *         tiene el permiso sobre un recurso de su propio alcance (403)
	 * @throws com.akine.organization.domain.exception.OrganizationNotFoundException si el
	 *         recurso esta fuera de su alcance (404)
	 * @throws org.springframework.security.access.AccessDeniedException si el request no trae
	 *         contexto de tenant y la operacion lo exige (403 {@code missing-tenant-context})
	 */
	PermissionDecision requirePermission(PermissionQuery query);
}
