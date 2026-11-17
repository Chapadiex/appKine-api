package com.akine.assistant.domain.port;

import java.util.List;
import java.util.Map;

/**
 * Ejecuta una consulta libre ya validada sobre las vistas de {@code asistente_sql}, recortadas a una
 * organizacion y una sede.
 *
 * <p>La implementacion corre en una transaccion de solo lectura, con tiempo maximo, y fija la
 * organizacion y la sede como parametros locales de esa transaccion: las vistas se filtran con ellos.
 */
public interface LectorDeVistas {

	/**
	 * @param sql          un unico SELECT ya validado por {@code ValidadorDeSql}
	 * @param limiteFilas  cuantas filas como maximo; una mas indica que hubo mas
	 * @return las filas, columna a valor, en el orden de la consulta
	 * @throws ConsultaInvalidaException si el motor la rechaza (sintaxis, columna inexistente, tiempo)
	 */
	List<Map<String, Object>> leer(String sql, long organizationId, long consultorioId, int limiteFilas);

	/** El motor rechazo la consulta; el mensaje es el del motor, para devolverselo al modelo. */
	final class ConsultaInvalidaException extends RuntimeException {
		public ConsultaInvalidaException(String mensaje, Throwable causa) {
			super(mensaje, causa);
		}
	}
}
