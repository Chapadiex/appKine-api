package com.akine.activity.domain;

/**
 * Los seis estados minimos de una inscripcion (RN-M28-004).
 *
 * <h2>{@link #consumeCupo()} vive aca y en ningun otro lado</h2>
 *
 * <p>Es la funcion que decide si una fila ocupa un lugar, y de ella depende el invariante entero de
 * la etapa —{@code cupo_ocupado} igual a la cantidad de inscripciones que consumen—. Repetirla en
 * una consulta JPQL y en un {@code if} del servicio seria garantizar que las dos copias diverjan en
 * la primera modificacion, y la divergencia se ve como una clase que dice tener lugar y no lo
 * tiene.
 *
 * <p><b>{@link #AUSENTE} consume cupo, y no es un descuido.</b> La persona no vino, pero el lugar
 * estuvo reservado para ella y nadie mas pudo usarlo. Liberarlo retroactivamente falsearia el
 * historico de ocupacion de una clase que ya paso — y en 08.03, cuando la ausencia tenga
 * consecuencia economica, hara falta poder decir que el lugar se consumio.
 */
public enum EstadoInscripcion {

	/** Tiene el lugar. Es el estado inicial de quien entra con vacante disponible. */
	RESERVADA(true),

	/** Confirmada por el mostrador. Mismo lugar: la transicion no otorga ni libera nada. */
	CONFIRMADA(true),

	/** Vino y uso el lugar. Lo escribe 08.03. */
	ASISTIO(true),

	/** No vino. <b>Consume igual</b>: ver la cabecera. Lo escribe 08.03. */
	AUSENTE(true),

	/** Dio de baja. Libero el lugar, y la fila queda con su motivo y su actor. */
	CANCELADA(false),

	/** En la cola. <b>No consume cupo confirmado</b> (RN-M28-005). */
	LISTA_ESPERA(false);

	private final boolean consumeCupo;

	EstadoInscripcion(boolean consumeCupo) {
		this.consumeCupo = consumeCupo;
	}

	/** {@code true} si una fila en este estado ocupa uno de los lugares de la clase. */
	public boolean consumeCupo() {
		return consumeCupo;
	}

	/** {@code true} si la inscripcion sigue viva: ni cancelada, ni con la clase ya resuelta. */
	public boolean estaViva() {
		return this != CANCELADA;
	}
}
