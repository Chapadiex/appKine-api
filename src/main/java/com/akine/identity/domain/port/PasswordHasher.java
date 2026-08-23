package com.akine.identity.domain.port;

/**
 * Custodia de la contrasena (RN-M02-003: nada de credenciales en texto plano).
 *
 * <p>Es un puerto y no una clase concreta por dos motivos que no son academicos: permite
 * testear los servicios sin pagar el costo real del hasheo —que es alto A PROPOSITO— y
 * permite cambiar de algoritmo sin tocar una sola linea de {@code application}.
 *
 * <p>La implementacion tiene que cumplir tres cosas:
 * <ol>
 *   <li>ser <b>memory-hard</b> y de costo configurable, para que una GPU no pueda probar
 *       millones de candidatas por segundo si la base se filtra;</li>
 *   <li>llevar los parametros DENTRO del hash (formato PHC), para que subirlos manana no
 *       invalide los hashes ya guardados;</li>
 *   <li>ofrecer {@link #dummyVerify()} con el MISMO costo que una verificacion real.</li>
 * </ol>
 */
public interface PasswordHasher {

	/** Devuelve el hash en formato PHC de la contrasena en claro. */
	String hash(String passwordPlano);

	/**
	 * Verifica la contrasena contra el hash guardado.
	 *
	 * @return {@code false} tambien si el hash guardado es {@code null} —cuenta invitada que
	 *         nunca fijo credencial— o esta corrupto. Nunca lanza por eso: una fila rota no
	 *         puede convertirse en un 500 que le diga al atacante que esa cuenta es especial
	 */
	boolean matches(String passwordPlano, String hashGuardado);

	/**
	 * Quema el mismo trabajo que una verificacion real, sin verificar nada.
	 *
	 * <p>Es la contramedida de enumeracion por tiempo. Si el login respondiera de inmediato
	 * cuando el email no existe y tardara 300 ms cuando si existe, la diferencia se mide desde
	 * cualquier lado y el endpoint pasa a ser un buscador de direcciones registradas, aunque
	 * el cuerpo de las dos respuestas sea identico byte a byte. Por eso la rama "no existe"
	 * llama a esto antes de responder.
	 */
	void dummyVerify();
}
