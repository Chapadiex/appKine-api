package com.akine.activity.domain;

/**
 * Como resulto la participacion de una persona en una clase (AKINE-08.03, RF-M28-007).
 *
 * <h2>Tres valores, y la inscripcion sigue con seis estados</h2>
 *
 * <p>El plan pide "presente/ausente/tarde/cancelado por participante". <b>{@code TARDE} no se
 * agrega a {@link EstadoInscripcion}</b>, y el motivo es el mismo con el que 08.02 se nego a
 * agregar un septimo estado para la ventana de aceptacion: RN-M28-004 enumera exactamente seis
 * estados de inscripcion, y sumar uno por cada matiz operativo convierte una maquina de estados
 * normativa en una lista abierta.
 *
 * <p>El vocabulario rico vive aca, en el hecho, donde no rompe nada; la proyeccion administrativa
 * se queda con los dos valores que la norma declara. Y <b>{@code cancelado} no es un resultado de
 * asistencia</b>: es {@link EstadoInscripcion#CANCELADA}, que significa otra cosa —no vino porque
 * se dio de baja antes, y libero el lugar—.
 *
 * <h2>{@link #estadoDeInscripcion()} vive aca y en ningun otro lado</h2>
 *
 * <p>Es la funcion que traduce el hecho a su proyeccion administrativa. Repetirla en un
 * {@code if} del servicio y en una consulta seria garantizar que las dos copias diverjan en la
 * primera modificacion, exactamente como {@link EstadoInscripcion#consumeCupo()}.
 *
 * <p><b>Que {@link #PRESENTE_TARDE} mapee a {@code ASISTIO} tiene una consecuencia que conviene
 * decir:</b> para el cupo y para la economia futura, llegar tarde es haber asistido. Si algun
 * centro quiere que la tardanza tenga consecuencia distinta, eso es una politica de 08.07 y tiene
 * el dato para leerlo.
 */
public enum ResultadoAsistencia {

	/** Vino y participo. */
	PRESENTE(EstadoInscripcion.ASISTIO),

	/** Vino tarde. <b>Asistio igual</b>: ver la cabecera. */
	PRESENTE_TARDE(EstadoInscripcion.ASISTIO),

	/**
	 * No vino. <b>El lugar sigue consumido</b> (08.02): estuvo reservado para ella y nadie mas pudo
	 * usarlo, asi que liberarlo retroactivamente falsearia el historico de ocupacion. Que el
	 * no-show tenga o no consecuencia economica es una decision de 08.07, no de cupo.
	 */
	AUSENTE(EstadoInscripcion.AUSENTE);

	private final EstadoInscripcion estadoDeInscripcion;

	ResultadoAsistencia(EstadoInscripcion estadoDeInscripcion) {
		this.estadoDeInscripcion = estadoDeInscripcion;
	}

	/** El estado en el que queda la inscripcion cuando el hecho tiene este resultado. */
	public EstadoInscripcion estadoDeInscripcion() {
		return estadoDeInscripcion;
	}

	/** {@code true} si la persona estuvo, con o sin demora. */
	public boolean estuvo() {
		return this != AUSENTE;
	}
}
