package com.akine.identity.domain;

/**
 * En que punto de su ciclo esta una invitacion a colaborar (M05, AKINE-02.03).
 *
 * <h2>Por que EXPIRADA no esta aca</h2>
 *
 * <p>Expirar no es una decision de nadie: es el reloj pasando. Materializarlo como estado
 * obligaria a un job que lo escriba, y entre que el enlace vence y el job corre la base diria
 * {@code PENDIENTE} sobre algo que ya no se puede aceptar — una ventana en la que el listado
 * del administrador miente. La expiracion se deriva de {@code expiraEn} en cada lectura, que
 * no puede desincronizarse porque no hay nada que sincronizar.
 *
 * <p>Consecuencia deliberada: una invitacion vencida sigue siendo {@code PENDIENTE} en la
 * columna, y por lo tanto <b>sigue ocupando el unique</b> que impide invitar de nuevo al mismo
 * email. Es lo correcto: reinvitar a alguien cuyo enlace vencio es un <b>reenvio</b> —mismo
 * pedido, token nuevo— y no una invitacion nueva.
 */
public enum EstadoInvitacion {

	/** Emitida y sin respuesta. Es el unico estado desde el que se puede hacer algo. */
	PENDIENTE,

	/** El invitado entro por el enlace y quedo vinculado. Terminal. */
	ACEPTADA,

	/** El invitado dijo que no. Terminal, y no bloquea volver a invitarlo mas adelante. */
	RECHAZADA,

	/** El administrador se arrepintio o se equivoco de persona. Terminal, exige motivo. */
	CANCELADA;

	/** Indica si desde este estado se puede pasar al destino. */
	public boolean puedePasarA(EstadoInvitacion destino) {
		return this == PENDIENTE && destino != PENDIENTE;
	}

	/** {@code true} para los tres estados de los que ya no se sale. */
	public boolean esTerminal() {
		return this != PENDIENTE;
	}
}
