package com.akine.identity.domain.exception;

/**
 * El refresh presentado no habilita renovar la sesion.
 *
 * <p><b>Una sola excepcion para cinco causas</b>, y eso es la funcionalidad, igual que en
 * {@link InvalidCredentialsException}: el token no existe, esta vencido, fue revocado, ya se
 * roto (reuso) o la cuenta dejo de poder autenticarse. Todas responden {@code 401
 * invalid-refresh} con el mismo cuerpo.
 *
 * <p>El caso que importa es el reuso. Cuando se detecta, el servidor revoca la familia entera
 * y <b>responde exactamente esto</b>: si contestara algo distinto —"token reutilizado",
 * {@code 409}, un {@code type} propio— le estaria confirmando al atacante que su copia fue
 * detectada y en que momento, que es justo lo que le permite ajustar el ataque. La deteccion
 * queda en la auditoria ({@code REFRESH_REUSO_DETECTADO}), no en la respuesta.
 */
public class InvalidRefreshTokenException extends RuntimeException {

	public InvalidRefreshTokenException() {
		super("Refresh token invalido");
	}
}
