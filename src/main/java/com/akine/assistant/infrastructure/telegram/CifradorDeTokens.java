package com.akine.assistant.infrastructure.telegram;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM para los tokens de los bots. La clave es el SHA-256 de la clave del despliegue; cada
 * cifrado lleva su IV aleatorio delante. Un token alterado o cifrado con otra clave no se descifra:
 * {@link #descifrar} lanza y el bot queda como sin token.
 */
public class CifradorDeTokens {

	private static final String VERSION = "v1:";
	private static final String SEPARADOR = ":";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec clave;
	private final SecureRandom azar = new SecureRandom();

	public CifradorDeTokens(String claveDelDespliegue) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest(("akine-telegram:" + claveDelDespliegue).getBytes(StandardCharsets.UTF_8));
			this.clave = new SecretKeySpec(hash, "AES");
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 no disponible", e);
		}
	}

	private CifradorDeTokens() {
		this.clave = null;
	}

	/** Con Telegram apagado y sin clave: existe para cablear, y se niega a cifrar. */
	public static CifradorDeTokens inutilizable() {
		return new CifradorDeTokens();
	}

	public String cifrar(String texto) {
		exigirClave();
		try {
			byte[] iv = new byte[IV_BYTES];
			azar.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, iv));
			byte[] cifrado = cipher.doFinal(texto.getBytes(StandardCharsets.UTF_8));
			// IV y cifrado por separado: "v1:<iv>:<cifrado>", sin concatenar arreglos.
			Base64.Encoder base64 = Base64.getEncoder();
			return VERSION + base64.encodeToString(iv) + SEPARADOR + base64.encodeToString(cifrado);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("No se pudo cifrar el token", e);
		}
	}

	public String descifrar(String guardado) {
		exigirClave();
		if (guardado == null || !guardado.startsWith(VERSION)) {
			throw new IllegalArgumentException("Token cifrado con un formato desconocido");
		}
		String[] partes = guardado.substring(VERSION.length()).split(SEPARADOR, -1);
		if (partes.length != 2) {
			throw new IllegalArgumentException("Token cifrado con un formato desconocido");
		}
		try {
			Base64.Decoder base64 = Base64.getDecoder();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, base64.decode(partes[0])));
			return new String(cipher.doFinal(base64.decode(partes[1])), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalArgumentException("El token no se pudo descifrar con la clave del despliegue", e);
		}
	}

	private void exigirClave() {
		if (clave == null) {
			throw new IllegalStateException("La carga por Telegram esta apagada: no hay clave para cifrar tokens");
		}
	}
}
