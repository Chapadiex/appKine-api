package com.akine.organization.application;

import com.akine.organization.spi.FuncionIa;

import java.util.List;

/**
 * Por cada funcion: si el despliegue la ofrece, si el centro la quiere y si queda activa (las dos).
 * {@code version} 0 = el centro nunca toco la pantalla.
 */
public record FuncionesIaView(long organizationId, long version, List<Funcion> funciones) {

	public record Funcion(FuncionIa funcion, boolean despliegue, boolean centro, boolean activa) {
	}
}
