package com.akine.identity.domain;

import java.util.Optional;

/**
 * Terminos y condiciones vigentes para el alta (DP-45).
 *
 * <p>Vive en {@code domain} por la misma razon que {@link SessionSettings}: {@code application}
 * no puede leer {@code IdentityProperties}. {@code IdentityConfig} la arma desde
 * {@code akine.identity.terminos.*}.
 *
 * @param versionVigente version del texto que se exige aceptar, o {@code null} si no se exige
 *                       ninguna (el alta es la de siempre)
 * @param url            donde se publica el texto, o {@code null}. El backend no guarda el texto
 */
public record TerminosSettings(String versionVigente, String url) {

	/** Largo maximo de una version: es lo que cabe en {@code aceptacion_terminos.version}. */
	public static final int VERSION_MAX = 40;

	public TerminosSettings {
		versionVigente = vacioANull(versionVigente);
		url = vacioANull(url);
		if (versionVigente != null && versionVigente.length() > VERSION_MAX) {
			throw new IllegalArgumentException(
					"akine.identity.terminos.version-vigente no puede superar los "
							+ VERSION_MAX + " caracteres");
		}
	}

	public static TerminosSettings sinTerminos() {
		return new TerminosSettings(null, null);
	}

	public boolean exigeAceptacion() {
		return versionVigente != null;
	}

	public Optional<String> version() {
		return Optional.ofNullable(versionVigente);
	}

	private static String vacioANull(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
	}
}
