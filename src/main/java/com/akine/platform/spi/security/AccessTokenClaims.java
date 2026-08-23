package com.akine.platform.spi.security;

import java.time.Instant;

/**
 * Lo que dice un access token ya verificado.
 *
 * <p>Vive en {@code platform.spi} y no en {@code identity} porque lo consume el filtro de
 * autenticacion, que es de {@code platform}: una flecha {@code platform -> identity} cerraria
 * un ciclo. Quien emite y quien verifica es {@code identity.infrastructure}; quien lo usa para
 * construir el principal es {@code platform.infrastructure.security}.
 *
 * <h2>El rol NO es una autoridad</h2>
 * <p>{@link #roleCode()} es una <b>pista</b>, util para que el frontend dibuje un menu y para
 * correlacionar auditoria. La verificacion real la hace {@code TenantContextFilter} contra la
 * base en cada request. Si alguien empieza a autorizar por este campo, la ventana de
 * revocacion de permisos deja de ser cero y pasa a ser el TTL del token: una membership
 * revocada seguiria funcionando hasta diez minutos. No lo hagas.
 *
 * <p>Lo mismo vale, con mas fuerza todavia, para {@link #organizationId()} y
 * {@link #consultorioId()}: dicen QUE contexto se pide, no que ese contexto sea accesible.
 *
 * @param accountId      cuenta autenticada (claim {@code sub})
 * @param tokenId        identificador unico del token (claim {@code jti}), para correlacionar
 *                       auditoria. <b>No hay lista de revocacion por jti</b> (D-3)
 * @param scope          alcance del token
 * @param organizationId organizacion pedida, {@code null} en {@link AccessTokenScope#PRE_CONTEXT}
 * @param consultorioId  consultorio pedido, {@code null} en {@link AccessTokenScope#PRE_CONTEXT}
 * @param roleCode       rol de la membership al momento de emitir. Pista, no autoridad
 * @param familyId       familia del refresh que origino la sesion; liga access con sesion
 * @param issuedAt       emision
 * @param expiresAt      vencimiento
 */
public record AccessTokenClaims(
		long accountId,
		String tokenId,
		AccessTokenScope scope,
		Long organizationId,
		Long consultorioId,
		String roleCode,
		String familyId,
		Instant issuedAt,
		Instant expiresAt) {

	/** Indica si el token ya vencio en el instante dado. */
	public boolean expiradoEn(Instant ahora) {
		return !ahora.isBefore(expiresAt);
	}
}
