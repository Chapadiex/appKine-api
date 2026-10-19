package com.akine.billing.domain.port;

import com.akine.billing.domain.LiquidacionHonorario;
import com.akine.billing.domain.LiquidacionHonorarioClase;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de las liquidaciones de honorarios por clase (AKINE-08.09b). Toda consulta lleva
 * {@code organizationId}.
 */
public interface LiquidacionHonorarioRepositoryPort {

	LiquidacionHonorario save(LiquidacionHonorario liquidacion);

	LiquidacionHonorarioClase guardarClase(LiquidacionHonorarioClase clase);

	Optional<LiquidacionHonorario> findByIdInScope(long organizationId, long consultorioId, long liquidacionId);

	Optional<LiquidacionHonorario> findByIdempotencyKey(long organizationId, String idempotencyKey);

	/** La del egreso, en cualquier estado: un egreso nace de a lo sumo una liquidacion. */
	Optional<LiquidacionHonorario> findDelEgreso(long organizationId, long egresoId);

	/** El detalle, en el orden de las clases. */
	List<LiquidacionHonorarioClase> clasesDe(long organizationId, long liquidacionId);

	/**
	 * Las clases de esa lista que el colaborador tiene liquidadas en una liquidacion VIGENTE, con
	 * el egreso que las tiene: {@code [claseId, egresoId]}.
	 */
	List<Object[]> clasesVigentesDelColaborador(
			long organizationId, long colaboradorMembershipId, Collection<Long> claseIds);

	/** Si la clase tiene alguna liquidacion VIGENTE, de cualquier colaborador. */
	boolean existeVigenteDeLaClase(long organizationId, long claseId);

	/**
	 * Libera el detalle de una liquidacion: {@code liberada_key = id}. UPDATE nativo, porque la
	 * entidad no escribe esa columna. Devuelve las filas tocadas.
	 */
	int liberarClases(long organizationId, long liquidacionId);
}
