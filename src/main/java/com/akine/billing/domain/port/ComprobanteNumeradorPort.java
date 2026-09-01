package com.akine.billing.domain.port;

/**
 * Secuencia de comprobantes por sede.
 *
 * <p>Tres operaciones y no una, igual que el numerador de sesiones: asegurar la fila —en su propia
 * transaccion—, incrementar y leer. Esconderlo detras de un solo {@code siguiente()} haria
 * invisible que el primer paso NO puede ir dentro de la transaccion del cobro.
 */
public interface ComprobanteNumeradorPort {

	/** Sin lanzar nunca: leer-y-despues-insertar deadlockea entre los primeros cobros de una sede. */
	void crearSiFalta(long organizationId, long consultorioId);

	/** Toma el lock de fila y serializa. Un comprobante repetido es un problema fiscal. */
	void incrementar(long organizationId, long consultorioId);

	Integer leerUltimo(long organizationId, long consultorioId);
}
