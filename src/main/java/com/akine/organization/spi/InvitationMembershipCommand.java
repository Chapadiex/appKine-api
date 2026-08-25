package com.akine.organization.spi;

/**
 * Datos del vinculo que nace al aceptarse una invitacion (RF-M05-002).
 *
 * <p><b>No lleva actor.</b> Es la diferencia con {@link DirectMembershipCommand} y es toda la
 * decision: en el alta directa la autoridad es el permiso {@code colaborador:manage} del
 * administrador, y en la aceptacion es <b>el token</b>, que ya demostro que quien lo presenta
 * llega al buzon del invitado. El invitado no tiene —ni puede tener— permiso sobre un tenant al
 * que todavia no pertenece: exigirselo haria que ninguna invitacion pudiera aceptarse nunca.
 *
 * <p>Quien decidio fue el administrador, y ese momento ya esta autorizado y auditado: es la
 * emision de la invitacion. Lo que llega aca es la ejecucion de esa decision, y por eso
 * {@code invitacionId} e {@code invitadaPorAccountId} viajan: sin ellos, la auditoria del
 * vinculo diria que se creo solo.
 *
 * @param accountId             cuenta que acepta, existente o recien creada
 * @param consultorioId         sede del vinculo, o {@code null} para alcance ORGANIZACION
 * @param roleCode              rol propuesto en la invitacion, validado de nuevo aca
 * @param invitacionId          invitacion que lo origina, para la auditoria
 * @param invitadaPorAccountId  administrador que la emitio, para la auditoria
 */
public record InvitationMembershipCommand(
		long accountId,
		Long consultorioId,
		String roleCode,
		long invitacionId,
		long invitadaPorAccountId) {
}
