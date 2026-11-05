package com.akine.scheduling.domain.exception;

import com.akine.platform.spi.problem.ConflictoDeAgenda;

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
 *
 * <p>Disponibilidad central (0.158.0): si lo que dejo el horario afuera es un cierre —un bloqueo o
 * una ausencia del profesional, o el cierre de la sede—, viaja como {@link #getConflicto()} con su
 * franja y su sede, y el motivo lo dice.
 */
public class SlotNoDisponibleException extends RuntimeException {

	private final String motivo;
	private final ConflictoDeAgenda conflicto;

	public SlotNoDisponibleException(String motivo) {
		this(motivo, null);
	}

	public SlotNoDisponibleException(String motivo, ConflictoDeAgenda conflicto) {
		super("El slot pedido ya no esta disponible: " + motivo);
		this.motivo = motivo;
		this.conflicto = conflicto;
	}

	/** Por un cierre: el motivo es el detalle del conflicto. */
	public static SlotNoDisponibleException por(ConflictoDeAgenda conflicto) {
		return new SlotNoDisponibleException(
				conflicto.detalle("No es posible asignarle un turno en ese horario."), conflicto);
	}

	public String getMotivo() {
		return motivo;
	}

	/** {@code null} si la causa no es un cierre identificable. */
	public ConflictoDeAgenda getConflicto() {
		return conflicto;
	}
}
