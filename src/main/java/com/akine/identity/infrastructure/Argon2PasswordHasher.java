package com.akine.identity.infrastructure;

import com.akine.identity.domain.port.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custodia de la contrasena (RN-M02-003, diseño §6.2).
 *
 * <h2>Argon2id cuando se puede, PBKDF2 cuando no — y el hash dice cual fue</h2>
 *
 * <p>El algoritmo elegido es <b>Argon2id</b> con los minimos de OWASP 2024: 19 456 KiB de
 * memoria, 2 iteraciones, paralelismo 1, salt de 16 B y hash de 32 B.
 * {@code Argon2PasswordEncoder} de Spring Security necesita <b>BouncyCastle en el classpath</b>
 * y hoy esa dependencia no esta declarada en el {@code pom.xml}. Instanciarlo igual dejaria una
 * aplicacion que compila, arranca y explota con {@code NoClassDefFoundError} en el primer
 * registro real.
 *
 * <p>Por eso el algoritmo se elige en el arranque: si BouncyCastle esta, se usa Argon2id; si no
 * esta, se usa <b>PBKDF2-HMAC-SHA256 con 600 000 iteraciones</b> —la recomendacion de OWASP
 * para PBKDF2, disponible en {@code spring-security-crypto} sin dependencias extra— y se emite
 * un {@code WARN} de arranque que deja el hueco visible en vez de silencioso.
 *
 * <p>La pieza que hace que esto no sea deuda irreversible es
 * {@link DelegatingPasswordEncoder}: cada hash queda etiquetado con el algoritmo que lo
 * produjo ({@code {argon2}...} / {@code {pbkdf2@...}...}). Agregar BouncyCastle manana cambia
 * el algoritmo de las contrasenas NUEVAS y <b>los hashes viejos siguen verificando</b>, sin
 * migracion y sin dejar a nadie afuera de su cuenta.
 *
 * <p>PBKDF2 no es memory-hard: contra una GPU rinde peor que Argon2id. Es un reemplazo
 * aceptable y no equivalente, y por eso el {@code WARN} existe.
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

	private static final Logger log = LoggerFactory.getLogger(Argon2PasswordHasher.class);

	/** Clase de BouncyCastle sin la cual {@code Argon2PasswordEncoder} no puede hashear. */
	private static final String CLASE_ARGON2_BC =
			"org.bouncycastle.crypto.generators.Argon2BytesGenerator";

	private static final String ID_ARGON2 = "argon2";
	private static final String ID_PBKDF2 = "pbkdf2@SpringSecurity_v5_8";

	/** Salt de 16 bytes y hash de 32 bytes: minimos OWASP para las dos familias. */
	private static final int LARGO_SALT = 16;
	private static final int LARGO_HASH = 32;

	/**
	 * Contrasena de la verificacion falsa. No es la de nadie: se compara contra un hash
	 * generado en el arranque a partir de otro valor, asi que {@link #dummyVerify()} siempre
	 * falla y siempre paga el costo completo.
	 */
	private static final String PASSWORD_DUMMY = "dummy-verify-no-corresponde-a-ninguna-cuenta";

	private final PasswordEncoder encoder;

	/** Hash precomputado contra el que corre la verificacion falsa. */
	private final String hashDummy;

	public Argon2PasswordHasher(IdentityProperties properties) {
		this.encoder = construirEncoder(properties.getHashing());
		// Se computa una sola vez, en el arranque: dummyVerify tiene que costar lo mismo que
		// una verificacion real, no lo mismo que una verificacion MAS un hasheo.
		this.hashDummy = this.encoder.encode(PASSWORD_DUMMY + ":semilla-de-arranque");
	}

	@Override
	public String hash(String passwordPlano) {
		if (passwordPlano == null) {
			throw new IllegalArgumentException("No se puede hashear una contrasena nula");
		}
		return encoder.encode(passwordPlano);
	}

	/**
	 * Verifica la contrasena contra el hash guardado.
	 *
	 * <p>Nunca lanza. Un hash {@code null} —cuenta invitada que todavia no fijo credencial— o
	 * uno corrupto, o uno con un prefijo de algoritmo que ya no esta mapeado, devuelven
	 * {@code false}. Convertir una fila rota en un 500 le diria al atacante que esa cuenta
	 * tiene algo distinto, que es justamente lo que no puede aprender.
	 */
	@Override
	public boolean matches(String passwordPlano, String hashGuardado) {
		if (passwordPlano == null || hashGuardado == null || hashGuardado.isBlank()) {
			return false;
		}
		try {
			return encoder.matches(passwordPlano, hashGuardado);
		} catch (IllegalArgumentException hashIlegible) {
			log.warn("Hash de credencial ilegible: se rechaza el intento sin distinguirlo de "
					+ "una contrasena equivocada");
			return false;
		}
	}

	/**
	 * Quema el mismo trabajo que una verificacion real, sin verificar nada.
	 *
	 * <p>Es la contramedida de enumeracion por tiempo del login: sin esto, la rama "el email no
	 * existe" respondaria en microsegundos y el reloj diria lo que el cuerpo de la respuesta se
	 * cuida de no decir.
	 */
	@Override
	public void dummyVerify() {
		boolean coincide = encoder.matches(PASSWORD_DUMMY, hashDummy);
		if (coincide) {
			// Imposible por construccion: el hash se genero con otro valor. Si alguna vez
			// ocurriera, el hasher estaria roto y hay que enterarse.
			log.error("La verificacion falsa coincidio: el hasher no esta funcionando");
		}
	}

	/**
	 * Arma el encoder delegante con el algoritmo mas fuerte disponible como algoritmo de
	 * escritura, y con todos los conocidos disponibles para verificar.
	 */
	private static PasswordEncoder construirEncoder(IdentityProperties.Hashing parametros) {
		Map<String, PasswordEncoder> algoritmos = new LinkedHashMap<>();
		algoritmos.put(ID_PBKDF2, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());

		boolean argon2Disponible =
				ClassUtils.isPresent(CLASE_ARGON2_BC, Argon2PasswordHasher.class.getClassLoader());
		if (argon2Disponible) {
			algoritmos.put(ID_ARGON2, new Argon2PasswordEncoder(
					LARGO_SALT,
					LARGO_HASH,
					parametros.getParallelism(),
					parametros.getMemoryKb(),
					parametros.getIterations()));
			log.info("Hasheo de credenciales: Argon2id memoria={}KiB iteraciones={} paralelismo={}",
					parametros.getMemoryKb(), parametros.getIterations(), parametros.getParallelism());
			return new DelegatingPasswordEncoder(ID_ARGON2, algoritmos);
		}

		log.warn("BouncyCastle no esta en el classpath: Argon2id no puede usarse y el hasheo de "
				+ "credenciales cae a PBKDF2-HMAC-SHA256 (600000 iteraciones). Es aceptable pero "
				+ "NO es memory-hard. Declarar org.bouncycastle:bcprov-jdk18on en el pom.xml "
				+ "restablece Argon2id; los hashes ya guardados siguen verificando por el "
				+ "prefijo de algoritmo.");
		return new DelegatingPasswordEncoder(ID_PBKDF2, algoritmos);
	}
}
