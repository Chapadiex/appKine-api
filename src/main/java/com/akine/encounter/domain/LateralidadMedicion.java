package com.akine.encounter.domain;

/**
 * De que lado se tomo una medicion (RF-M14-004).
 *
 * <h2>Por que NO reusa {@link Lateralidad} y por que {@code BILATERAL} no esta</h2>
 *
 * <p>{@link Lateralidad} describe <b>un sintoma referido</b> —"me duelen las dos rodillas"— y
 * admite {@code BILATERAL} con razon: el paciente relata una sola cosa. Aca el enum es la
 * <b>identidad</b> de una medida, y un lado bilateral no es un valor: son dos.
 *
 * <p>El valor de los dos lados <b>es distinto</b> —ese es el punto de medirlos— asi que una unica
 * fila {@code BILATERAL} obligaria a guardar dos numeros en un campo o a promediarlos, que es
 * perder exactamente la informacion que la medicion existe para capturar. <b>Una medicion
 * bilateral son dos filas</b>, y por eso este valor entra en el unique de {@code sesion_medicion}.
 *
 * <p>Reusar el otro enum habria dejado {@code BILATERAL} disponible con un CHECK de base
 * rechazandolo: una opcion que el modelo ofrece y la tabla niega. Dos enums son mas honestos que
 * uno con la mitad prohibida.
 */
public enum LateralidadMedicion {

	IZQUIERDA,

	DERECHA,

	/**
	 * Lo que no tiene lado: frecuencia cardiaca, Borg, saturacion, peso.
	 *
	 * <p>No es lo mismo que dejarlo vacio, y por eso la columna es {@code NOT NULL}: decir "no
	 * corresponde" explicitamente distingue la medida central de la que alguien olvido lateralizar,
	 * y ademas le da al unique un valor con el que comparar.
	 */
	NO_APLICA
}
