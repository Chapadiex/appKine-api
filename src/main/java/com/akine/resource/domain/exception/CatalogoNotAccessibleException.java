package com.akine.resource.domain.exception;

/**
 * El concepto del catalogo no existe, o pertenece a otro tenant (404).
 *
 * <p><b>Los dos casos se responden igual, a proposito</b> (ADR-0018). Distinguirlos permitiria
 * recorrer ids consecutivos y averiguar que practicas propias tiene cada centro del SaaS, que
 * es informacion comercial de sus clientes.
 *
 * <p>Ojo con lo que esto NO cubre, porque es la mitad de la etapa: un concepto <b>GLOBAL</b> lo
 * ve todo el mundo y nunca produce esta excepcion, y un concepto dado de baja del propio tenant
 * <b>tambien se devuelve</b>, con 200. RN-M06-001 y RN-M06-002 exigen que los historicos sigan
 * resolviendo, y un 404 ahi seria borrar historia por la puerta de atras.
 */
public class CatalogoNotAccessibleException extends RuntimeException {

	private final long conceptoId;

	public CatalogoNotAccessibleException(long conceptoId) {
		super("Concepto de catalogo no accesible: " + conceptoId);
		this.conceptoId = conceptoId;
	}

	public long getConceptoId() {
		return conceptoId;
	}
}
