package com.akine.identity.application;

import com.akine.identity.domain.exception.PasswordPolicyViolationException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Unica autoridad sobre que contrasena se acepta (RNF-M02-008: la regla vive en el backend;
 * el frontend puede replicarla como ayuda visual, jamas como control).
 *
 * <h2>Por que NO exige mayusculas, numeros y simbolos</h2>
 *
 * <p>Sigue NIST SP 800-63B, que retiro esa exigencia despues de medir lo que produce en la
 * practica: la gente no inventa una contrasena mas dificil, escribe {@code Password1!} —que
 * esta en cualquier diccionario de ataque— y despues la anota. Las reglas de composicion
 * eliminan un porcentaje minusculo del espacio de busqueda y empujan a todo el mundo hacia el
 * mismo puñado de patrones predecibles.
 *
 * <p>Lo que si mueve la aguja es el largo minimo y descartar las contrasenas que ya estan en
 * las listas de ataque. Por eso la politica es: 10 a 128 caracteres, y no estar en la lista de
 * las mas usadas. Tampoco hay expiracion periodica ni historial: forzar el cambio cada 90 dias
 * produce {@code Marzo2026}, {@code Junio2026}, y eso es peor que la contrasena original.
 *
 * <p>El maximo de 128 no es una regla de seguridad sino un limite de recursos: sin tope,
 * mandar 10 MB en el campo contrasena obliga al servidor a hashear 10 MB con Argon2id, y eso
 * es una denegacion de servicio barata de ejecutar.
 */
@Service
public class PasswordPolicy {

	/** Minimo NIST 800-63B para credenciales elegidas por la persona. */
	public static final int LARGO_MINIMO = 10;

	/** Tope contra el abuso del hasheo, no contra la debilidad de la contrasena. */
	public static final int LARGO_MAXIMO = 128;

	/**
	 * Contrasenas que aparecen primero en cualquier ataque por diccionario.
	 *
	 * <p>Es una muestra de las listas publicas de credenciales filtradas, acotada a las que
	 * tienen al menos {@link #LARGO_MINIMO} caracteres: las mas cortas ya las rechaza el
	 * largo, y meterlas aca solo agrandaria el conjunto sin cambiar ninguna decision.
	 * Ampliarla a la lista completa de las mil mas usadas es aditivo y no cambia el contrato.
	 */
	private static final Set<String> DENYLIST = Set.of(
			"password1", "password12", "password123", "password1234", "password!",
			"contrasena1", "contrasena123", "contraseña123", "123456789", "1234567890",
			"12345678910", "qwertyuiop", "qwerty12345", "1q2w3e4r5t", "1234qwerty",
			"iloveyou1", "iloveyou123", "princess1", "sunshine1", "football1",
			"babygirl1", "superman1", "michael123", "jennifer1", "trustno1234",
			"welcome123", "welcome1234", "admin12345", "administrador", "letmein123",
			"passw0rd12", "p@ssw0rd12", "abc12345678", "monkey12345", "dragon12345",
			"master12345", "shadow12345", "killer12345", "hola12345", "holamundo",
			"argentina1", "boca12345", "river12345", "kinesiologia", "fisioterapia",
			"consultorio", "akine12345", "asdfghjkl1", "zxcvbnm123", "qazwsxedc1");

	/**
	 * Valida la contrasena propuesta.
	 *
	 * <p>Falla en vez de devolver un booleano para que el llamador no pueda ignorar el
	 * resultado por descuido: la validacion y el hasheo quedan en la misma linea de codigo.
	 *
	 * @throws PasswordPolicyViolationException con un motivo legible. El mensaje habla de lo
	 *         que la persona acaba de tipear, no de si existe algo en la base: no hay nada que
	 *         enumerar aca
	 */
	public void validar(String password) {
		if (password == null || password.isEmpty()) {
			throw new PasswordPolicyViolationException("La contrasena es obligatoria");
		}
		if (password.length() < LARGO_MINIMO) {
			throw new PasswordPolicyViolationException(
					"La contrasena debe tener al menos " + LARGO_MINIMO + " caracteres");
		}
		if (password.length() > LARGO_MAXIMO) {
			throw new PasswordPolicyViolationException(
					"La contrasena no puede superar los " + LARGO_MAXIMO + " caracteres");
		}
		// Comparacion en minusculas con Locale.ROOT: la denylist no puede depender de la
		// locale del servidor, y "PASSWORD123" es tan conocida como "password123".
		if (DENYLIST.contains(password.toLowerCase(Locale.ROOT))) {
			throw new PasswordPolicyViolationException(
					"Esa contrasena aparece en listas publicas de credenciales filtradas: "
							+ "elegi otra");
		}
	}
}
