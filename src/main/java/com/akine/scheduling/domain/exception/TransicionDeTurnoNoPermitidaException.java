package com.akine.scheduling.domain.exception;

/**
 * La transicion pedida no existe en la maquina de estados del turno, o su ventana temporal no la
 * admite. <b>409 siempre.</b>
 *
 * <p>Un solo tipo para las dos familias —estado y ventana— con el {@code motivo} adentro, y no dos:
 * para la pantalla el desenlace es el mismo —refrescar el turno y mostrar por que no se puede— y
 * publicar dos {@code type} obligaria al cliente a manejar dos codigos para una misma accion
 * imposible. Lo que si cambia entre casos es el texto, y por eso viaja como propiedad.
 *
 * <p>El id es {@code Long} y no {@code long} porque la valida el DOMINIO: un turno que todavia no
 * se persistio no tiene id, y forzar el desempaquetado convertiria un rechazo de negocio legitimo
 * en un {@code NullPointerException}.
 */
public class TransicionDeTurnoNoPermitidaException extends RuntimeException {

	private final Long turnoId;
	private final String motivo;

	public TransicionDeTurnoNoPermitidaException(Long turnoId, String motivo) {
		super("El turno " + turnoId + " no admite la operacion: " + motivo);
		this.turnoId = turnoId;
		this.motivo = motivo;
	}

	public Long getTurnoId() {
		return turnoId;
	}

	public String getMotivo() {
		return motivo;
	}
}
