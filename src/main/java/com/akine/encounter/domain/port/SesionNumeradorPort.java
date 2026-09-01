package com.akine.encounter.domain.port;

/**
 * Secuencia de sesiones por historia clinica.
 *
 * <p>Tres operaciones y no una, para que el orden quede a la vista de quien lee el servicio:
 * asegurar la fila —en su propia transaccion—, incrementar y leer. Esconderlo detras de un solo
 * {@code siguiente()} haria invisible que el primer paso NO puede ir dentro de la transaccion del
 * cierre.
 */
public interface SesionNumeradorPort {

	/** Crea la fila si falta. Sin lanzar. Va en una transaccion aparte: ver la implementacion. */
	void crearSiFalta(long organizationId, long historiaClinicaId);

	/** Incrementa el contador tomando el lock de fila. Serializa los cierres del mismo paciente. */
	void incrementar(long organizationId, long historiaClinicaId);

	/** Lee el numero recien asignado. Se llama despues de {@link #incrementar}, en la misma transaccion. */
	Integer leerUltimo(long organizationId, long historiaClinicaId);
}
