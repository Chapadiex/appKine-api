package com.akine.identity.domain;

import java.time.Duration;

/**
 * Para que sirve un token de un solo uso (RF-M02-003, RF-M26-001).
 *
 * <p>Cada tipo trae su propia vigencia porque no responden al mismo riesgo: el reset lo pide
 * alguien que dice haber perdido el acceso —una ventana larga ahi es una ventana de secuestro
 * de cuenta—, mientras que la activacion la recibe alguien que todavia no tiene credencial y
 * que puede tardar dias en abrir el correo.
 */
public enum TipoTokenVerificacion {

	/** Confirma el alta y habilita la cuenta. Tolera demora: el correo puede esperar. */
	ACTIVACION(Duration.ofDays(7)),

	/** Habilita fijar una contrasena nueva. Corto a proposito. */
	RESET(Duration.ofMinutes(30));

	private final Duration vigencia;

	TipoTokenVerificacion(Duration vigencia) {
		this.vigencia = vigencia;
	}

	/** Cuanto vive un token de este tipo desde su emision. */
	public Duration vigencia() {
		return vigencia;
	}
}
