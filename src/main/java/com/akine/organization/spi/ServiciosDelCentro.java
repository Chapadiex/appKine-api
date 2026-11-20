package com.akine.organization.spi;

import java.util.Set;

/**
 * Que servicios contrato un centro (19/11/2026), para quien no es {@code organization}.
 *
 * <p>Lo usa la pantalla de la invitacion para elegir su ilustracion. Un centro con la
 * contratacion sin definir responde los dos, igual que el resto del sistema.
 */
public interface ServiciosDelCentro {

	Set<Servicio> serviciosDe(long organizationId);
}
