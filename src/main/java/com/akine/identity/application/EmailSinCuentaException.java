package com.akine.identity.application;

/**
 * El email tipeado en un alta directa no corresponde a ninguna cuenta.
 *
 * <h2>Por que un tipo propio y no {@code AccountNotFoundException}</h2>
 *
 * <p>Las dos responden exactamente lo mismo —404 con el cuerpo unico de "no existe o no esta
 * disponible"— y eso no es casual: para el cliente, un email sin cuenta y una cuenta ajena
 * tienen que ser indistinguibles. Lo que cambia es la entrada: aquella se construye con un
 * {@code accountId} y esta parte de una direccion, que no tiene id que poner en el mensaje.
 * Forzar el id ahi obligaria a inventar uno.
 *
 * <p>Vive en {@code application} y no en {@code domain} por el mismo criterio que
 * {@code organization.application.PlanNotFoundException}: la condicion que la produce es del
 * caso de uso —resolver un email antes de delegar el alta— y no un invariante de la entidad
 * {@code Cuenta}.
 *
 * <h2>Que NO significa este 404</h2>
 *
 * <p>Significa "no existe", y por lo tanto <b>es un oraculo de enumeracion</b>: quien tenga
 * {@code colaborador:manage} puede distinguir emails registrados de los que no. Es una decision
 * tomada de frente, con dos mitigaciones obligatorias —rate limit propio de la ruta y auditoria
 * de cada intento fallido—. Estan explicadas en {@link DirectMembershipService} y en el
 * controller que lo publica.
 */
public class EmailSinCuentaException extends RuntimeException {

	/**
	 * @param emailNormalizado direccion en forma canonica. Queda en el mensaje interno, que
	 *                         <b>nunca</b> viaja al cliente: el advice arma su propio cuerpo
	 */
	public EmailSinCuentaException(String emailNormalizado) {
		super("Ninguna cuenta tiene el email " + emailNormalizado);
	}
}
