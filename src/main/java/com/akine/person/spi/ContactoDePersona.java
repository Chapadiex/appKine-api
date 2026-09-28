package com.akine.person.spi;

/**
 * El contacto administrativo de una persona, para los modulos que le <b>mandan avisos</b>.
 *
 * @param email {@code null} cuando la ficha no tiene correo cargado. No es un error: el padron
 *              admite personas sin email —se las atiende igual— y quien notifica tiene que poder
 *              saltearlas sin fallar
 * @param nombre nombre de pila, unico dato personal que viaja al template (RN-M26-002)
 */
public record ContactoDePersona(long personaId, String email, String nombre) {

	/** {@code true} si esta persona se puede notificar por correo. */
	public boolean notificable() {
		return email != null && !email.isBlank();
	}
}
