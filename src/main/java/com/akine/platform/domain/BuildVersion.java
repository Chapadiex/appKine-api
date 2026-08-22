package com.akine.platform.domain;

/**
 * Identidad tecnica del backend en ejecucion.
 *
 * <p>{@code contractVersion} es la version SemVer del contrato OpenAPI publicado, y no
 * necesariamente coincide con {@code applicationVersion}: el contrato solo cambia cuando
 * cambia la API, mientras que la aplicacion se versiona en cada release.
 *
 * <p>Es un objeto de dominio del modulo {@code platform}. No sale del backend tal cual:
 * la capa {@code api} lo traduce a su DTO.
 */
public record BuildVersion(
		String applicationName,
		String applicationVersion,
		String contractVersion) {
}
