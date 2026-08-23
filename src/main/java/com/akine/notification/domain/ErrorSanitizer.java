package com.akine.notification.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el fallo de un envio en un motivo que se puede guardar y mostrar.
 *
 * <p><b>Por que no se guarda la excepcion cruda.</b> El mensaje de un fallo SMTP trae
 * habitualmente el host y el puerto del relay, el usuario con el que se autentico, la
 * direccion completa del destinatario y, si el adaptador la incluyo por descuido, la URL del
 * enlace con el token. Todo eso queda en {@code error_sanitizado}, que se lee desde una
 * pantalla administrativa y termina en los backups. Guardar el mensaje crudo convierte a la
 * tabla de diagnostico en un deposito de credenciales.
 *
 * <p>Lo que si queda: el tipo de excepcion —que es lo que sirve para diagnosticar— y un texto
 * con emails enmascarados, hosts y URLs reemplazados, y todo lo que parezca token borrado.
 *
 * <p>Clase PURA: sin Spring, sin log, sin reloj.
 */
public final class ErrorSanitizer {

	/** Cabe en {@code error_sanitizado VARCHAR(500)} con margen para el prefijo del tipo. */
	public static final int LARGO_MAXIMO = 500;

	private static final String REDACTADO = "[redactado]";

	private static final Pattern URL = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://\\S*");
	private static final Pattern EMAIL =
			Pattern.compile("\\b([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");
	private static final Pattern HOST_PUERTO = Pattern.compile("\\b[A-Za-z0-9.-]+\\.[A-Za-z]{2,}:\\d{1,5}\\b");
	private static final Pattern OPACO = Pattern.compile("\\b[A-Za-z0-9_-]{24,}\\b");
	private static final Pattern CLAVE_VALOR = Pattern.compile(
			"(?i)\\b(token|password|passwd|pwd|secret|api[_-]?key|authorization|credential)\\b\\s*[:=]\\s*\\S+");

	private ErrorSanitizer() {
		// Clase de utilidad.
	}

	/**
	 * Motivo sanitizado a partir de una excepcion.
	 *
	 * <p>Formato: {@code TipoDeExcepcion: mensaje sanitizado}. Sin causa encadenada: cada
	 * nivel de causa multiplica la superficie de fuga y aporta poco al diagnostico.
	 */
	public static String sanitize(Throwable error) {
		if (error == null) {
			return "Fallo desconocido";
		}
		String tipo = error.getClass().getSimpleName();
		String mensaje = sanitize(error.getMessage());
		return mensaje.isBlank() ? recortar(tipo) : recortar(tipo + ": " + mensaje);
	}

	/** Motivo sanitizado a partir de un texto libre. */
	public static String sanitize(String mensaje) {
		if (mensaje == null || mensaje.isBlank()) {
			return "";
		}
		String limpio = mensaje.replace('\n', ' ').replace('\r', ' ').trim();
		limpio = CLAVE_VALOR.matcher(limpio).replaceAll(matcher ->
				Matcher.quoteReplacement(matcher.group(1).toLowerCase(Locale.ROOT) + "=" + REDACTADO));
		limpio = URL.matcher(limpio).replaceAll(Matcher.quoteReplacement(REDACTADO));
		limpio = EMAIL.matcher(limpio).replaceAll("$1***@$2");
		limpio = HOST_PUERTO.matcher(limpio).replaceAll(Matcher.quoteReplacement(REDACTADO));
		limpio = OPACO.matcher(limpio).replaceAll(Matcher.quoteReplacement(REDACTADO));
		return recortar(limpio.replaceAll("\\s{2,}", " ").trim());
	}

	private static String recortar(String texto) {
		return texto.length() <= LARGO_MAXIMO ? texto : texto.substring(0, LARGO_MAXIMO - 3) + "...";
	}

	/**
	 * Enmascara un email para logs y pantallas administrativas: {@code juan@akine.com} ->
	 * {@code j***@akine.com}.
	 *
	 * <p>El dominio queda visible porque es lo que hace falta para diagnosticar ("todos los
	 * de este dominio rebotan") y no identifica a una persona.
	 */
	public static String maskEmail(String email) {
		if (email == null || email.isBlank()) {
			return REDACTADO;
		}
		int arroba = email.indexOf('@');
		if (arroba <= 0) {
			return REDACTADO;
		}
		return email.charAt(0) + "***" + email.substring(arroba);
	}
}
