package com.akine.scheduling.application;

/**
 * Quien pide agenda, con el contexto que el filtro de tenant ya resolvio.
 *
 * <p>Cada modulo declara el suyo a proposito: un record compartido en {@code platform} obligaria a
 * todos los modulos a depender de el, y {@code AGENT.md} §4 lo prohibe. Ver la misma decision en
 * {@code resource.application.OperatingActor}.
 */
public record OperatingActor(
		long accountId,
		boolean platformAdmin,
		Long contextOrganizationId,
		Long consultorioId,
		String roleCode) {

	/**
	 * Sin rol: tests y llamadores internos que no vienen de un request con contexto. El rol solo
	 * viaja a la auditoria (agenda de profesionales, checklist §12.4); ninguna regla lo usa para
	 * autorizar, eso lo decide el evaluador.
	 */
	public OperatingActor(long accountId, boolean platformAdmin, Long contextOrganizationId, Long consultorioId) {
		this(accountId, platformAdmin, contextOrganizationId, consultorioId, null);
	}
}
