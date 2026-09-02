package com.akine.contracting.domain.exception;

/**
 * La practica del catalogo clinico (M06) no existe o no la ve este tenant.
 *
 * <p>La FK a {@code practica} garantiza que el id exista, no que el tenant pueda verlo: una
 * practica es GLOBAL o propia de una organizacion (ADR-0021), y una FK compara ids y no alcances.
 * Quien lo resuelve es {@code resource.spi.CatalogoDirectory}, y lo que se responde es 404 — misma
 * regla que cualquier otro cross-tenant.
 */
public class PracticaNoAccesibleException extends RuntimeException {

	private final long practicaId;

	public PracticaNoAccesibleException(long practicaId) {
		super("Practica no accesible: " + practicaId);
		this.practicaId = practicaId;
	}

	public long getPracticaId() {
		return practicaId;
	}
}
