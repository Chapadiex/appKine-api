package com.akine.billing.domain.port;

/**
 * Secuencia de lotes por sede y financiador.
 *
 * <p>Tres operaciones y no una, igual que el numerador de comprobantes y el de sesiones: asegurar
 * la fila —<b>en su propia transaccion</b>—, incrementar y leer. Esconderlo detras de un solo
 * {@code siguiente()} haria invisible que el primer paso NO puede ir dentro de la transaccion que
 * bloquea la fila: la creacion perezosa alli produce deadlock, y el {@code try/catch} no salva
 * porque atrapar una excepcion de persistencia no des-marca la transaccion y Spring lanza
 * {@code UnexpectedRollbackException} al commitear. Este repositorio ya lo pago cuatro veces.
 */
public interface PresentacionNumeradorPort {

	/** Sin lanzar nunca. Va en una transaccion propia. */
	void crearSiFalta(long organizationId, long consultorioId, long financiadorId);

	/** Toma el lock de fila y serializa. Nunca {@code MAX + 1}. */
	void incrementar(long organizationId, long consultorioId, long financiadorId);

	Integer leerUltimo(long organizationId, long consultorioId, long financiadorId);
}
