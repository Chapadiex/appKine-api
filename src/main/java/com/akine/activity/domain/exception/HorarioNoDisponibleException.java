package com.akine.activity.domain.exception;

/**
 * El horario pedido no existe para esos recursos: el profesional no esta habilitado para la oferta,
 * no atiende en esa franja, o no hay espacio en servicio.
 *
 * <p>Reusa el tipo {@code slot-no-disponible} de M12: la accion de la pantalla es la misma,
 * recargar la grilla. Distinto de {@code recurso-ocupado}, donde el horario existe y lo que falta
 * es el recurso libre.
 */
public class HorarioNoDisponibleException extends RuntimeException {

	private final String motivo;

	public HorarioNoDisponibleException(String motivo) {
		super("El horario elegido no esta disponible: " + motivo);
		this.motivo = motivo;
	}

	public String getMotivo() {
		return motivo;
	}
}
