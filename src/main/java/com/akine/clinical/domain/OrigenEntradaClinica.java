package com.akine.clinical.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Como nacio una entrada clinica.
 *
 * <h2>Hoy solo se escribe {@link #MANUAL}, y el resto igual existe</h2>
 *
 * <p>Ningun modulo genera entradas clinicas todavia: {@code encounter} aporta al timeline desde
 * sus propias tablas de sesion y <b>no escribe entradas</b> (challenge seccion 1), justamente para
 * que la evolucion no viva duplicada en dos lugares que despues discrepan.
 *
 * <p>Los otros valores estan desde ahora porque agregar el origen <b>despues</b> de que haya
 * entradas obliga a decidir que valor llevan las viejas, y ninguna respuesta es buena: marcarlas
 * {@code MANUAL} mentiria sobre las importadas y dejarlas nulas rompe el CHECK. Es DP-10 literal:
 * se corta el <b>alcance</b>, no el <b>modelo</b>.
 *
 * <h2>La referencia viaja con el origen o no viaja</h2>
 *
 * <p>Todo origen que no sea {@code MANUAL} exige la fila de la que salio
 * ({@code ck_entrada_clinica_origen_trazable}). Una entrada que dice venir de una sesion sin decir
 * de cual no es trazable, y RN-M09-004 pide exactamente eso.
 */
public enum OrigenEntradaClinica {

	/** Escrita a mano por un profesional. El unico valor que la etapa emite. */
	MANUAL,

	/** Generada a partir de una sesion de atencion. Sin emisor hoy. */
	SESION,

	/** Generada a partir de una orden o autorizacion. Sin emisor hoy. */
	ORDEN,

	/** Importada desde un sistema externo. Sin emisor hoy. */
	EXTERNO;

	/** {@code true} si este origen exige declarar la fila de la que salio la entrada. */
	public boolean exigeReferencia() {
		return this != MANUAL;
	}

	/** El origen con ese nombre, o vacio si no existe: es un 400 del cliente, no un 500. */
	public static Optional<OrigenEntradaClinica> desde(String nombre) {
		if (nombre == null || nombre.isBlank()) {
			return Optional.empty();
		}
		String normalizado = nombre.strip().toUpperCase();
		return Arrays.stream(values()).filter(o -> o.name().equals(normalizado)).findFirst();
	}
}
