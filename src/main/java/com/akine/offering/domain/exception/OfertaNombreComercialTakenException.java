package com.akine.offering.domain.exception;

/**
 * Ya hay una {@code Oferta} VIGENTE con ese nombre comercial en esa sede (409).
 *
 * <p>Traduce la violacion de {@code uk_oferta_sede_nombre_vigente} de la migracion V24 —
 * {@code UNIQUE (organization_id, consultorio_id, nombre_comercial, deleted_key)}—, la unica
 * comprobacion sin ventana de carrera entre un SELECT previo y el INSERT/UPDATE.
 *
 * <p>El nombre comercial de una oferta de OTRA sede no colisiona (el unique empieza por
 * {@code organization_id, consultorio_id}), y el de una oferta dada de baja tampoco: el
 * {@code deleted_key} la discrimina, asi que "Kinesiologia deportiva vespertina" se puede reusar
 * en la misma sede despues de una baja logica.
 */
public class OfertaNombreComercialTakenException extends RuntimeException {

	private final long consultorioId;
	private final String nombreComercial;

	public OfertaNombreComercialTakenException(long consultorioId, String nombreComercial) {
		super("Ya existe una oferta vigente con ese nombre comercial en la sede");
		this.consultorioId = consultorioId;
		this.nombreComercial = nombreComercial;
	}

	public long getConsultorioId() {
		return consultorioId;
	}

	public String getNombreComercial() {
		return nombreComercial;
	}
}
