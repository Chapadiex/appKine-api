package com.akine.assistant.spi;

import java.util.List;
import java.util.Map;

/**
 * Lo que devuelve una herramienta: filas planas que la plantilla del asistente convierte en texto.
 *
 * <p>Sin datos clinicos de contenido: fechas, estados, nombres y montos. Lo que no se puede ver en la
 * pantalla con el mismo permiso no viaja aca.
 *
 * @param filas        una por elemento, con claves estables por intencion (las fija cada plantilla)
 * @param advertencias lo que el usuario tiene que saber para no leer mal el resultado
 * @param enlace       ruta del frontend donde ver el detalle, o {@code null}
 * @param sinPermiso   {@code true} si el servicio rechazo al actor; las filas vienen vacias
 */
public record ResultadoDeHerramienta(
		List<Map<String, Object>> filas,
		List<String> advertencias,
		String enlace,
		boolean sinPermiso) {

	public ResultadoDeHerramienta {
		filas = filas == null ? List.of() : List.copyOf(filas);
		advertencias = advertencias == null ? List.of() : List.copyOf(advertencias);
	}

	/** El servicio dueño del dato rechazo al actor. Se responde con 200 y un texto, no con 403. */
	public static ResultadoDeHerramienta denegado() {
		return new ResultadoDeHerramienta(List.of(), List.of(), null, true);
	}
}
