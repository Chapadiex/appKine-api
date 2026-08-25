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
	RESET(Duration.ofMinutes(30)),

	/**
	 * Invita a colaborar en una organizacion (M05, AKINE-02.03).
	 *
	 * <p><b>Es el unico tipo que NO produce una fila en {@code token_verificacion}</b>: esa
	 * tabla exige {@code cuenta_id NOT NULL} y una invitacion se emite antes de que la cuenta
	 * exista. Su hash vive en {@code colaborador_invitacion}. Igual esta aca porque este enum
	 * es lo que decide la vigencia y la ruta del enlace, y las dos cosas aplican.
	 *
	 * <p>Catorce dias, entre los siete de la activacion y los treinta minutos del reset: quien
	 * recibe una invitacion no la estaba esperando —a diferencia de quien acaba de pedir un
	 * reset— y suele responderla cuando vuelve de trabajar, no en el momento.
	 */
	INVITACION(Duration.ofDays(14));

	private final Duration vigencia;

	TipoTokenVerificacion(Duration vigencia) {
		this.vigencia = vigencia;
	}

	/** Cuanto vive un token de este tipo desde su emision. */
	public Duration vigencia() {
		return vigencia;
	}
}
