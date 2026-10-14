package com.akine.billing.domain.port;

/**
 * Secuencia de comprobantes por sede.
 *
 * <p>Dos pasos y no uno, igual que el numerador de sesiones: asegurar la fila —en su propia
 * transaccion— e incrementar. Esconderlo detras de un solo {@code siguiente()} haria invisible que
 * el primer paso NO puede ir dentro de la transaccion del cobro.
 */
public interface ComprobanteNumeradorPort {

	/** Sin lanzar nunca: leer-y-despues-insertar deadlockea entre los primeros cobros de una sede. */
	void crearSiFalta(long organizationId, long consultorioId);

	/** Lectura SIN lock: si la fila ya existe, el iniciador no la toca. Ver FilaLockPrevia. */
	boolean existeFila(long organizationId, long consultorioId);

	/**
	 * Suma uno y devuelve el numero nuevo, en <b>una sola sentencia</b>.
	 *
	 * <p>Toma el lock de fila y serializa: un comprobante repetido es un problema fiscal. El lock
	 * queda tomado hasta el commit del cobro, asi que todo lo que corre despues de esta llamada
	 * corre con los demas cobros de la sede esperando (docs/carga.md §10). Por eso el numero vuelve
	 * con la respuesta del {@code UPDATE} y no con un {@code SELECT} posterior.
	 *
	 * @throws IllegalStateException si la fila no existe: nadie llamo a {@link #crearSiFalta} antes
	 */
	int siguiente(long organizationId, long consultorioId);
}
