package com.akine.billing.infrastructure;

import com.akine.billing.application.LiquidacionDeHonorariosService;
import com.akine.billing.spi.LiquidacionesDeHonorarios;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Las liquidaciones de honorarios por clase, expuestas a {@code activity} (AKINE-08.09b).
 *
 * <p>Confirmar es {@code MANDATORY} por lo mismo que {@link DevengoDeClasesAdapter}: solo tiene
 * sentido dentro de la transaccion que tomo el lock de las clases, y una segunda conexion
 * ({@code REQUIRES_NEW}) es la traba del pool que midio G-10. Las lecturas no abren
 * transaccion: corren en la del llamador.
 */
@Component
class LiquidacionesDeHonorariosAdapter implements LiquidacionesDeHonorarios {

	private final LiquidacionDeHonorariosService servicio;

	LiquidacionesDeHonorariosAdapter(LiquidacionDeHonorariosService servicio) {
		this.servicio = servicio;
	}

	@Override
	public Map<Long, Long> clasesLiquidadas(
			long organizationId, long colaboradorMembershipId, Collection<Long> claseIds) {
		return servicio.clasesLiquidadas(organizationId, colaboradorMembershipId, claseIds);
	}

	@Override
	public boolean claseLiquidada(long organizationId, long claseId) {
		return servicio.claseLiquidada(organizationId, claseId);
	}

	@Override
	public Optional<Liquidacion> porIdempotencyKey(long organizationId, String idempotencyKey) {
		return servicio.porIdempotencyKey(organizationId, idempotencyKey);
	}

	@Override
	public Optional<Liquidacion> ver(long organizationId, long consultorioId, long liquidacionId) {
		return servicio.ver(organizationId, consultorioId, liquidacionId);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Liquidacion confirmar(LiquidacionAConfirmar pedido) {
		return servicio.confirmar(pedido);
	}
}
