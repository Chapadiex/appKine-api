package com.akine.billing.domain.port;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;

import java.util.List;
import java.util.Optional;

/** Persistencia de obligaciones. Vive en {@code domain}: {@code application} consume puertos. */
public interface ObligacionRepositoryPort {

	Obligacion save(Obligacion obligacion);

	Optional<Obligacion> findByIdInScope(long organizationId, long consultorioId, long obligacionId);

	/**
	 * La obligacion ya devengada por esa prestacion, si existe.
	 *
	 * <p>Es la idempotencia de RN-M18-001: una prestacion genera su deuda una sola vez. Hace falta
	 * porque el cierre de sesion es idempotente por RN-M14-005 —cerrar dos veces devuelve lo
	 * mismo— pero el observador que devenga se ejecuta en el camino del cierre, y sin esta consulta
	 * un segundo cierre chocaria contra el unique de V36 en vez de no hacer nada.
	 */
	Optional<Obligacion> findPorPrestacion(long sesionId, Responsable responsable);

	/** La cuenta corriente de un paciente en la organizacion, de la mas reciente a la mas vieja. */
	List<Obligacion> findDeLaPersona(long organizationId, long personaId);
}
