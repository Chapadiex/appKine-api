package com.akine.identity.infrastructure;

import com.akine.identity.domain.port.TokenGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Generador de los valores opacos que viajan en los enlaces de correo y en la cookie de
 * refresh.
 *
 * <p>32 bytes de {@code SecureRandom} —256 bits— codificados en Base64 seguro para URL y sin
 * relleno. Esa entropia es lo que permite guardar el token como SHA-256 sin salt: un espacio
 * de 2^256 no se recorre, asi que una tabla precomputada no existe y el hash no necesita
 * encarecerse como el de una contrasena (ver {@code TokenDigest}).
 *
 * <p>El {@code SecureRandom} se crea una vez y se comparte: es thread-safe, y reinstanciarlo
 * por llamada obligaria a resembrarlo, que es caro y no agrega entropia.
 *
 * <p><b>Nada de {@code Random} ni de {@code UUID.randomUUID()} para el token.</b> Un generador
 * predecible aca no debilita una defensa: las rompe todas, porque el token ES la credencial —
 * quien pueda anticiparlo activa cuentas ajenas y resetea contrasenas sin ver un solo correo.
 */
@Component
public class RandomTokenGenerator implements TokenGenerator {

	/** 256 bits: el minimo que fija el contrato del puerto. */
	private static final int BYTES_DE_ENTROPIA = 32;

	private final SecureRandom secureRandom = new SecureRandom();
	private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

	@Override
	public String nuevoToken() {
		byte[] material = new byte[BYTES_DE_ENTROPIA];
		secureRandom.nextBytes(material);
		return encoder.encodeToString(material);
	}

	/**
	 * Identificador de familia de refresh.
	 *
	 * <p>Aca si {@code UUID.randomUUID()} es lo correcto: la familia no es una credencial —no
	 * autentica a nadie, solo agrupa los eslabones de una misma sesion para poder revocarlos
	 * juntos— y su formato de 36 caracteres es el que espera la columna {@code familia_id}.
	 */
	@Override
	public String nuevaFamilia() {
		return UUID.randomUUID().toString();
	}
}
