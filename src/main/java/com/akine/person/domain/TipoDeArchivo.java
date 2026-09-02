package com.akine.person.domain;

/**
 * La lista blanca de tipos de archivo que el padron acepta, decidida por los BYTES.
 *
 * <h2>El caso borde que esta clase existe para cerrar: "archivo malicioso"</h2>
 *
 * <p>RN-M25-001 pide validar tipo y tamano, y la etapa lista "archivo malicioso" como caso borde.
 * El reflejo —mirar la extension, o el {@code Content-Type} de la parte multipart— <b>no valida
 * nada</b>: los dos los elige quien sube. Un {@code .pdf} que en realidad es un HTML con un
 * {@code <script>}, servido despues desde el mismo origen, es un XSS almacenado; y un
 * {@code Content-Type: application/pdf} sobre un ejecutable es una linea de texto que el cliente
 * escribio.
 *
 * <p>Por eso se decide por la firma binaria del contenido y el tipo declarado <b>se descarta</b>:
 * lo que se guarda en {@code content_type} y lo que se devuelve al descargar es el tipo
 * DETECTADO. Un cliente que miente no consigue nada, ni siquiera confundir a la proxima descarga.
 *
 * <p><b>La lista blanca es corta a proposito.</b> PDF y las dos imagenes que salen de la camara
 * de un telefono cubren lo que un mostrador adjunta: una credencial, un DNI fotografiado, un
 * consentimiento escaneado. Todo lo demas se rechaza, incluidos los formatos ofimaticos: un
 * {@code .docx} es un ZIP con macros posibles y no hay ningun caso de uso administrativo que lo
 * necesite. Ampliarla es una decision, no un parche.
 *
 * <p>No se usa {@code Files.probeContentType} ni {@code URLConnection.guessContentTypeFromStream}:
 * el primero mira la extension en varias plataformas y el segundo tiene su propia tabla, mas
 * amplia que esta lista blanca. Una deteccion que reconoce mas tipos de los que se aceptan es
 * exactamente lo contrario de lo que hace falta.
 */
public final class TipoDeArchivo {

	/** PDF: {@code %PDF-}. */
	private static final byte[] FIRMA_PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};

	/** PNG: {@code \x89PNG\r\n\x1a\n}. */
	private static final byte[] FIRMA_PNG = {
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

	/** JPEG: {@code \xFF\xD8\xFF}. Cubre JFIF, EXIF y las variantes de camara. */
	private static final byte[] FIRMA_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

	private TipoDeArchivo() {
		// Detector sin estado.
	}

	/**
	 * El tipo MIME que corresponde a estos bytes, o {@code null} si no esta en la lista blanca.
	 *
	 * <p>Un contenido vacio devuelve {@code null}: no es un tipo desconocido, es que no hay
	 * archivo, y el llamador lo trata igual que a un tipo rechazado.
	 */
	public static String detectar(byte[] contenido) {
		if (contenido == null || contenido.length == 0) {
			return null;
		}
		if (empiezaCon(contenido, FIRMA_PDF)) {
			return "application/pdf";
		}
		if (empiezaCon(contenido, FIRMA_PNG)) {
			return "image/png";
		}
		if (empiezaCon(contenido, FIRMA_JPEG)) {
			return "image/jpeg";
		}
		return null;
	}

	/** Los tipos aceptados, para poder decirlos en el mensaje de error y en el contrato. */
	public static String permitidos() {
		return "application/pdf, image/png, image/jpeg";
	}

	private static boolean empiezaCon(byte[] contenido, byte[] firma) {
		if (contenido.length < firma.length) {
			return false;
		}
		for (int i = 0; i < firma.length; i++) {
			if (contenido[i] != firma[i]) {
				return false;
			}
		}
		return true;
	}
}
