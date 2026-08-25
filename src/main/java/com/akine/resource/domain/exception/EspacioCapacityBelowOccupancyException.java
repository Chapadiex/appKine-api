package com.akine.resource.domain.exception;

/**
 * Se intento reducir la capacidad por debajo de lo ya comprometido (409).
 *
 * <p>Es el caso borde "capacidad reducida bajo ocupacion" que nombra la etapa AKINE-02.02, y
 * es una CARRERA: entre que la pantalla lee la capacidad actual y confirma la reduccion, otro
 * request puede haber ocupado los lugares que sobraban. Por eso la comprobacion no ocurre en
 * la pantalla ni en la entidad, sino dentro de la transaccion que ya bloqueo la fila del
 * espacio.
 *
 * <p>Se publican los dos numeros —lo pedido y lo comprometido— porque sin ellos el mensaje es
 * inaccionable: el usuario necesita saber a cuanto SI puede bajar. Son datos de su propio
 * tenant y no revelan nada de la implementacion ni de ninguna persona.
 */
public class EspacioCapacityBelowOccupancyException extends RuntimeException {

	private final long espacioId;
	private final int requestedCapacity;
	private final long currentOccupancy;
	private final String occupancyType;

	public EspacioCapacityBelowOccupancyException(
			long espacioId, int requestedCapacity, long currentOccupancy, String occupancyType) {

		super("La capacidad pedida es menor que la ocupacion comprometida del espacio "
				+ espacioId);
		this.espacioId = espacioId;
		this.requestedCapacity = requestedCapacity;
		this.currentOccupancy = currentOccupancy;
		this.occupancyType = occupancyType;
	}

	public long getEspacioId() {
		return espacioId;
	}

	public int getRequestedCapacity() {
		return requestedCapacity;
	}

	public long getCurrentOccupancy() {
		return currentOccupancy;
	}

	/** Vocabulario del modulo que declaro la ocupacion, p.ej. {@code turnos-futuros}. */
	public String getOccupancyType() {
		return occupancyType;
	}
}
