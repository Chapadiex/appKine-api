package com.akine.scheduling.domain.exception;

/**
 * El intervalo pedido no corresponde a ningun slot que la oferta ofrezca hoy. <b>409</b>.
 *
 * <p>Es la revalidacion de RN-M12-004: entre que la pantalla mostro la agenda y el usuario apreto
 * confirmar pudo cambiar cualquier cosa —el horario del profesional, un feriado, la vigencia de la
 * oferta—. El motor de slots no persiste nada, asi que la unica forma de saber que el hueco sigue
 * existiendo es recalcularlo dentro de la transaccion que lo reserva.
 *
 * <p>El {@code motivo} viaja para que la pantalla no diga solo "ya no esta disponible": si el
 * profesional dejo de atender ese dia, refrescar la agenda alcanza; si la duracion no coincide, el
 * cliente esta desactualizado y hay que recargar.
 */
public class SlotNoDisponibleException extends RuntimeException {

	private final String motivo;

	public SlotNoDisponibleException(String motivo) {
		super("El slot pedido ya no esta disponible: " + motivo);
		this.motivo = motivo;
	}

	public String getMotivo() {
		return motivo;
	}
}
