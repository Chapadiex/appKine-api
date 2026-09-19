package com.akine.clinical.api;

/**
 * Por donde viaja la justificacion de acceso clinico de DP-03.
 *
 * <h2>Una cabecera, y por que no la query string</h2>
 *
 * <p>DP-03 exige relacion asistencial <b>o justificacion declarada</b> para leer o escribir una
 * historia clinica, y la justificacion es texto libre que escribe un profesional: "el paciente
 * llamo por el resultado del estudio de rodilla". Eso es contenido clinico en miniatura.
 *
 * <p>Un parametro de query <b>se escribe en los logs de acceso de cualquier proxy</b>, queda en
 * el historial del navegador y en el {@code Referer} que se manda al siguiente origen. Una
 * cabecera no aparece en ninguno de los tres por defecto. El diseño de la etapa (seccion 6) lo
 * declara como requisito y esta clase es donde se cumple.
 *
 * <h2>Por que la MISMA cabecera en las doce operaciones</h2>
 *
 * <p>La alternativa era un campo del cuerpo en las que tienen cuerpo y una cabecera en las que no
 * —cinco de las doce son {@code GET}—. Dos mecanismos para el mismo dato es la forma mas barata
 * de que dentro de seis meses una operacion nueva se olvide del que le tocaba y el acceso quede
 * sin justificar. Uno solo, obligatorio de leer en cada firma, no se olvida en silencio.
 *
 * <h2>Ausente no es rechazo inmediato</h2>
 *
 * <p>La cabecera es {@code required = false} en los controllers <b>a proposito</b>: quien decide
 * si hacia falta es {@code AutorizacionClinica}, porque con relacion asistencial no hace falta
 * ninguna. Si hacia falta y no vino, el rechazo es 403 {@code forbidden} con
 * {@code requiereJustificacion} en true, no un 400 por cabecera faltante.
 */
final class AccesoClinicoHeaders {

	/**
	 * Motivo declarado del acceso clinico.
	 *
	 * <p>Nombre en castellano como el resto del vocabulario del dominio, y con prefijo
	 * {@code X-} porque no es una cabecera registrada por IANA.
	 */
	static final String JUSTIFICACION = "X-Justificacion-Acceso";

	/** Texto de la anotacion {@code @Parameter}, repetido en doce firmas desde un solo lugar. */
	static final String JUSTIFICACION_DOC =
			"Motivo declarado del acceso (DP-03). Obligatorio cuando quien opera no tiene "
					+ "relacion asistencial con el paciente, que HOY es siempre, porque la sonda "
					+ "de relacion asistencial todavia no tiene implementacion real. Viaja en "
					+ "cabecera y NO en query string: un motivo clinico en la URL termina en los "
					+ "logs de cualquier proxy. Queda auditado junto con el acceso.";

	private AccesoClinicoHeaders() {
	}
}
