package com.akine.resource.domain.exception;

/**
 * La baja del espacio esta bloqueada por hechos vigentes que lo referencian (409).
 *
 * <p>Es el caso borde "baja con reservas futuras" de la etapa AKINE-02.02. Lo declara algun
 * modulo a traves de {@code resource.spi.EspacioOccupancyProbe}; en F2 <b>no lo declara
 * nadie</b> porque {@code scheduling} no existe, y el codigo queda reservado en el contrato
 * desde ya para que su aparicion en F5 no sea un cambio de comportamiento sorpresivo para el
 * frontend. Es exactamente lo que ya se hizo con
 * {@code consultorio-has-active-references} en AKINE-02.01.
 *
 * <p>El {@code type} viaja al cliente, asi que es un valor estable del vocabulario del modulo
 * que responde y nunca lleva datos de personas.
 */
public class EspacioHasActiveReferencesException extends RuntimeException {

	private final long espacioId;
	private final String referenceType;
	private final long count;

	public EspacioHasActiveReferencesException(long espacioId, String referenceType, long count) {
		super("El espacio " + espacioId + " tiene referencias vigentes: " + referenceType);
		this.espacioId = espacioId;
		this.referenceType = referenceType;
		this.count = count;
	}

	public long getEspacioId() {
		return espacioId;
	}

	public String getReferenceType() {
		return referenceType;
	}

	public long getCount() {
		return count;
	}
}
