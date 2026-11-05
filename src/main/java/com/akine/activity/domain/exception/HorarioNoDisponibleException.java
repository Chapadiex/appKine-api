package com.akine.activity.domain.exception;

import com.akine.platform.spi.problem.ConflictoDeAgenda;

/**
 * El horario pedido no existe para esos recursos: el profesional no esta habilitado para la oferta,
 * no atiende en esa franja, o no hay espacio en servicio.
 *
 * <p>Reusa el tipo {@code slot-no-disponible} de M12: la accion de la pantalla es la misma,
 * recargar la grilla. Distinto de {@code recurso-ocupado}, donde el horario existe y lo que falta
 * es el recurso libre.
 *
 * <p>Disponibilidad central (0.158.0): si la causa es un cierre (bloqueo, ausencia, cierre de sede) de
 * cualquiera de los instructores, viaja como {@link #getConflicto()} con el instructor, la franja y la
 * sede.
 */
public class HorarioNoDisponibleException extends RuntimeException {

	private final String motivo;
	private final ConflictoDeAgenda conflicto;

	public HorarioNoDisponibleException(String motivo) {
		this(motivo, null);
	}

	public HorarioNoDisponibleException(String motivo, ConflictoDeAgenda conflicto) {
		super("El horario elegido no esta disponible: " + motivo);
		this.motivo = motivo;
		this.conflicto = conflicto;
	}

	public static HorarioNoDisponibleException por(ConflictoDeAgenda conflicto) {
		return new HorarioNoDisponibleException(
				conflicto.detalle("No es posible asignarle la clase en ese horario."), conflicto);
	}

	public String getMotivo() {
		return motivo;
	}

	/** {@code null} si la causa no es un conflicto identificable. */
	public ConflictoDeAgenda getConflicto() {
		return conflicto;
	}
}
