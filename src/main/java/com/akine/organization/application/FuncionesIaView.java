package com.akine.organization.application;

import com.akine.organization.spi.FuncionIa;

import java.time.Instant;
import java.util.List;

/**
 * Por cada funcion: si el despliegue la ofrece, si el centro la quiere y si queda activa (las dos,
 * mas el consentimiento vigente). {@code version} 0 = el centro nunca toco la pantalla.
 */
public record FuncionesIaView(long organizationId, long version, List<Funcion> funciones, Consentimiento consentimiento) {

	public record Funcion(FuncionIa funcion, boolean despliegue, boolean centro, boolean activa) {
	}

	/**
	 * Estado del consentimiento de IA del centro. {@code exigido} falso = el despliegue no lo pide y
	 * todo lo demas viene vacio. {@code aceptadoPor} es el nombre copiado al aceptar.
	 */
	public record Consentimiento(boolean exigido, String versionVigente, boolean aceptado, String aceptadoPor,
			Instant aceptadoAt) {
	}
}
