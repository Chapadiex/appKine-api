package com.akine.billing.infrastructure;

import com.akine.billing.domain.LiquidacionHonorario;
import com.akine.billing.domain.LiquidacionHonorarioClase;
import com.akine.billing.domain.port.LiquidacionHonorarioRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** El puerto de las liquidaciones de honorarios sobre los dos repositorios de Spring Data. */
@Component
class LiquidacionHonorarioRepositoryAdapter implements LiquidacionHonorarioRepositoryPort {

	private final LiquidacionHonorarioRepository liquidaciones;
	private final LiquidacionHonorarioClaseRepository clases;

	LiquidacionHonorarioRepositoryAdapter(
			LiquidacionHonorarioRepository liquidaciones, LiquidacionHonorarioClaseRepository clases) {
		this.liquidaciones = liquidaciones;
		this.clases = clases;
	}

	@Override
	public LiquidacionHonorario save(LiquidacionHonorario liquidacion) {
		return liquidaciones.saveAndFlush(liquidacion);
	}

	@Override
	public LiquidacionHonorarioClase guardarClase(LiquidacionHonorarioClase clase) {
		return clases.save(clase);
	}

	@Override
	public Optional<LiquidacionHonorario> findByIdInScope(
			long organizationId, long consultorioId, long liquidacionId) {
		return liquidaciones.findByIdInScope(organizationId, consultorioId, liquidacionId);
	}

	@Override
	public Optional<LiquidacionHonorario> findByIdempotencyKey(long organizationId, String idempotencyKey) {
		return liquidaciones.findByIdempotencyKey(organizationId, idempotencyKey);
	}

	@Override
	public Optional<LiquidacionHonorario> findDelEgreso(long organizationId, long egresoId) {
		return liquidaciones.findDelEgreso(organizationId, egresoId);
	}

	@Override
	public List<LiquidacionHonorarioClase> clasesDe(long organizationId, long liquidacionId) {
		return liquidaciones.clasesDe(organizationId, liquidacionId);
	}

	@Override
	public List<Object[]> clasesVigentesDelColaborador(
			long organizationId, long colaboradorMembershipId, Collection<Long> claseIds) {
		if (claseIds.isEmpty()) {
			return List.of();
		}
		return liquidaciones.clasesVigentesDelColaborador(organizationId, colaboradorMembershipId, claseIds);
	}

	@Override
	public boolean existeVigenteDeLaClase(long organizationId, long claseId) {
		return liquidaciones.existeVigenteDeLaClase(organizationId, claseId);
	}

	@Override
	public int liberarClases(long organizationId, long liquidacionId) {
		return liquidaciones.liberarClases(organizationId, liquidacionId);
	}
}
