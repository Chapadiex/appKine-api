package com.akine.billing.domain.exception;

/**
 * Ese financiador no existe, es de otro tenant o esta dado de baja. <b>404</b>.
 *
 * <p>{@code billing} no conoce la entidad {@code Financiador}: la consulta por
 * {@code contracting.spi.CoberturaCatalogoDirectory}, que es el unico camino permitido entre
 * modulos. El {@code empty} de esa consulta se traduce aca.
 */
public class FinanciadorNoAccesibleException extends RuntimeException {

	private final long financiadorId;

	public FinanciadorNoAccesibleException(long financiadorId) {
		super("El financiador " + financiadorId + " no existe o no es accesible");
		this.financiadorId = financiadorId;
	}

	public long getFinanciadorId() {
		return financiadorId;
	}
}
