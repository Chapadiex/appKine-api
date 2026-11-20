package com.akine.organization.spi;

/** Los servicios que el centro tiene contratados (kinesiologia, actividades). */
public interface ServiciosDelCentro {

	/** {@code true} si el centro tiene contratado el servicio. */
	boolean tiene(long organizationId, Servicio servicio);
}
