package com.akine.organization.spi;

import java.util.Set;

/**
 * Los servicios que el centro tiene contratados (kinesiologia, actividades), para quien no es
 * {@code organization}. Un centro con la contratacion sin definir responde los dos, igual que el
 * resto del sistema.
 */
public interface ServiciosDelCentro {

	/** {@code true} si el centro tiene contratado el servicio. */
	boolean tiene(long organizationId, Servicio servicio);

	/** Todos los servicios contratados; lo usa la invitacion para elegir su ilustracion. */
	Set<Servicio> serviciosDe(long organizationId);
}
