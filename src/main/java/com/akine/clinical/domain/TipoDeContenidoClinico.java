package com.akine.clinical.domain;

/**
 * La lista blanca de tipos de archivo que la Historia Clinica acepta, decidida por los BYTES.
 *
 * <h2>Por que no se reusa {@code person.domain.TipoDeArchivo}</h2>
 *
 * <p>Porque es de otro modulo y ArchUnit rechaza importarlo
 * ({@code modulos_solo_se_alcanzan_por_su_spi}), y porque promoverlo al {@code spi} de
 * {@code person} publicaria una decision de validacion de {@code person} como contrato hacia
 * todos los modulos. Tres firmas binarias duplicadas cuestan menos que eso. Es la misma
 * duplicacion deliberada que el adaptador de storage, y con la misma condicion de salida
 * escrita: <b>al tercer consumidor se extrae a {@code platform}</b>.
 *
 * <h2>El caso borde que esta clase cierra: "archivo malicioso"</h2>
 *
 * <p>RN-M25-001 pide validar tipo y tamano. El reflejo —mirar la extension o el
 * {@code Content-Type} de la parte multipart— <b>no valida nada</b>: los dos los elige quien
 * sube. Un {@code .pdf} que en realidad es HTML con un {@code <script>}, servido despues desde el
 * mismo origen, es un XSS almacenado. Por eso se decide por la firma binaria del contenido y el
 * tipo declarado se descarta: lo que se guarda en {@code content_type} y lo que se devuelve al
 * descargar es el tipo DETECTADO.
 *
 * <h2>La lista es la misma que la del padron, y eso es una decision de alcance</h2>
 *
 * <p>PDF, PNG y JPEG. Cubre lo que un consultorio adjunta hoy: un informe en PDF, la foto de una
 * lesion, una radiografia digitalizada, una evolucion escaneada.
 *
 * <p><b>DICOM no esta, y no es un olvido.</b> Ningun RF de la etapa lo pide, su deteccion no es
 * una firma en el byte cero —el marcador {@code DICM} vive despues de un preambulo de 128 bytes—
 * y un visor DICOM es una funcionalidad entera, no un tipo MIME mas en una lista. Admitir el
 * formato sin poder mostrarlo solo convertiria a la historia clinica en un repositorio de
 * archivos que nadie puede abrir desde la aplicacion. Ampliarla es una decision, no un parche.
 *
 * <p>No se usa {@code Files.probeContentType} ni {@code URLConnection.guessContentTypeFromStream}:
 * el primero mira la extension en varias plataformas y el segundo tiene su propia tabla, mas
 * amplia que esta lista blanca. Una deteccion que reconoce mas tipos de los que se aceptan es
 * exactamente lo contrario de lo que hace falta.
 */
public final class TipoDeContenidoClinico {

	/** PDF: {@code %PDF-}. */
	private static final byte[] FIRMA_PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};

	/** PNG: {@code \x89PNG\r\n\x1a\n}. */
	private static final byte[] FIRMA_PNG = {
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

	/** JPEG: {@code \xFF\xD8\xFF}. Cubre JFIF, EXIF y las variantes de camara. */
	private static final byte[] FIRMA_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

	private TipoDeContenidoClinico() {
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
