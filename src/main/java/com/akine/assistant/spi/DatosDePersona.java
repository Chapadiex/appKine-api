package com.akine.assistant.spi;

import java.time.LocalDate;

/** Los datos de una persona que una accion puede cargar o cambiar. Lo que no se dijo viene en {@code null}. */
public record DatosDePersona(
		String nombre,
		String apellido,
		String documento,
		String telefono,
		String email,
		LocalDate fechaNacimiento) {

	public static final DatosDePersona VACIO = new DatosDePersona(null, null, null, null, null, null);

	/** Si no trae ningun dato. */
	public boolean vacio() {
		return nombre == null && apellido == null && documento == null && telefono == null && email == null
				&& fechaNacimiento == null;
	}
}
