package com.akine.identity.application;

/**
 * Datos del alta self-service (ADR-0008).
 *
 * <p>Es un record de {@code application} y no un DTO de {@code api}: el alta tiene que poder
 * ejecutarse desde un test o un job sin que exista un request HTTP.
 *
 * @param claveIdempotencia {@code Idempotency-Key} del cliente. Obligatoria: es lo que hace
 *                          que un reintento no cree una segunda cuenta ni mande un segundo
 *                          correo
 * @param requestHash       SHA-256 del payload canonico, o {@code null} si el alta no vino
 *                          por HTTP. Se propaga tal cual al alta del tenant
 * @param email             tal como lo tipeo la persona; la normalizacion es del dominio
 * @param password          contrasena EN CLARO. Vive en memoria lo que dura el hasheo y no se
 *                          persiste, no se loguea y no viaja a ningun otro modulo
 * @param nombre            nombre de la persona
 * @param apellido          apellido de la persona
 * @param organizacionNombre nombre del centro que se esta dando de alta
 * @param organizacionSlug  identificador legible del tenant, o {@code null} para derivarlo
 * @param consultorioNombre nombre de la primera sede, o {@code null} para usar el de la
 *                          organizacion
 * @param planCode          plan a contratar, o {@code null} para el de defecto
 */
public record RegistroCuentaCommand(
		String claveIdempotencia,
		String requestHash,
		String email,
		String password,
		String nombre,
		String apellido,
		String organizacionNombre,
		String organizacionSlug,
		String consultorioNombre,
		String planCode) {

	public RegistroCuentaCommand {
		if (claveIdempotencia == null || claveIdempotencia.isBlank()) {
			throw new IllegalArgumentException("claveIdempotencia es obligatoria en el alta");
		}
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("email es obligatorio en el alta");
		}
		if (organizacionNombre == null || organizacionNombre.isBlank()) {
			throw new IllegalArgumentException("organizacionNombre es obligatorio en el alta");
		}
	}
}
