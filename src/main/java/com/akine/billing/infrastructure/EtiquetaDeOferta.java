package com.akine.billing.infrastructure;

import com.akine.offering.spi.ClasificacionDeOferta;

import java.util.List;
import java.util.Map;

/**
 * Las tres primeras celdas de una fila por servicio —servicio, naturaleza, oferta— y su orden
 * (DP-39). Comun a la rentabilidad y a la caja por servicio, para que las dos secciones del reporte
 * {@code SERVICIOS} nombren y ordenen igual la misma oferta.
 *
 * <p>Una oferta que la sede no clasifica (la deuda era de otra sede de la organizacion) se muestra
 * por su id, como en {@code IngresosPorServicioEnElReporte}: ocultarla haria que la seccion no
 * sumara su propio total.
 */
record EtiquetaDeOferta(String servicio, String naturaleza, String oferta) {

	private static final String SIN_IMPUTAR = "(sin imputar)";

	static EtiquetaDeOferta de(long ofertaId, Map<Long, ClasificacionDeOferta> clasificacion) {
		ClasificacionDeOferta c = clasificacion.get(ofertaId);
		return c == null
				? new EtiquetaDeOferta("(oferta " + ofertaId + ")", "", "(oferta " + ofertaId + ")")
				: new EtiquetaDeOferta(c.servicioNombre(), c.naturaleza(), c.nombreComercial());
	}

	static EtiquetaDeOferta sinImputar(String detalle) {
		return new EtiquetaDeOferta(SIN_IMPUTAR, "", detalle);
	}

	List<String> celdas() {
		return List.of(servicio, naturaleza, oferta);
	}

	String orden() {
		return servicio + "\u0000" + oferta;
	}
}
