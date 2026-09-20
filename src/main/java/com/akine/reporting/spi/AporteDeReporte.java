package com.akine.reporting.spi;

import java.util.List;

/**
 * Lo que un modulo aporta a un reporte: sus indicadores y, opcionalmente, su detalle.
 *
 * <p>El mismo aporte sirve a la pantalla y al CSV de RF-M23-006. <b>No hay una consulta para la
 * pantalla y otra para el export</b>: la alternativa garantiza que algun dia los dos digan cosas
 * distintas y que nadie sepa cual de las dos miente.
 *
 * @param seccion      nombre estable; viaja al cliente y lo usa para ubicar el bloque
 * @param titulo       como se lee en pantalla
 * @param indicadores  los numeros. Nunca se suman entre secciones
 * @param columnas     encabezados del detalle; vacio cuando la seccion no tiene detalle
 * @param filas        detalle. Debe tener tantas celdas como {@code columnas}
 * @param advertencias lo que la seccion necesita aclarar sobre sus propios numeros
 */
public record AporteDeReporte(
		String seccion,
		String titulo,
		List<IndicadorDeReporte> indicadores,
		List<String> columnas,
		List<FilaDeReporte> filas,
		List<AdvertenciaDeReporte> advertencias) {

	public AporteDeReporte {
		if (seccion == null || seccion.isBlank()) {
			throw new IllegalArgumentException("Un aporte necesita seccion");
		}
		indicadores = indicadores == null ? List.of() : List.copyOf(indicadores);
		columnas = columnas == null ? List.of() : List.copyOf(columnas);
		filas = filas == null ? List.of() : List.copyOf(filas);
		advertencias = advertencias == null ? List.of() : List.copyOf(advertencias);
	}

	/** Sin detalle: solo indicadores. Es la forma de la mayoria de las secciones. */
	public static AporteDeReporte de(
			String seccion, String titulo, List<IndicadorDeReporte> indicadores) {

		return new AporteDeReporte(seccion, titulo, indicadores, List.of(), List.of(), List.of());
	}

	/**
	 * El aporte vacio de una seccion.
	 *
	 * <p>Lo usa {@code reporting} cuando un contribuyente devuelve {@code null}, que seria un bug
	 * del contribuyente: mejor una seccion vacia visible que una pantalla rota.
	 */
	public static AporteDeReporte vacio(String seccion) {
		return new AporteDeReporte(seccion, seccion, List.of(), List.of(), List.of(), List.of());
	}
}
